package com.example.bot.chat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * SQLite-backed persistence for conversation context. Every operation opens a
 * short-lived connection so the service remains safe across restarts and does
 * not leak a database handle into request threads.
 */
@Component
public class SqliteConversationStore {
	private static final Logger log = LoggerFactory.getLogger(SqliteConversationStore.class);
	private static final int MAX_ADMIN_PAGE_SIZE = 200;
	private final boolean enabled;
	private final Path databaseFile;
	private final String jdbcUrl;
	private final ObjectMapper objectMapper;
	private final AtomicBoolean available = new AtomicBoolean();
	private final AtomicBoolean warningLogged = new AtomicBoolean();

	public SqliteConversationStore(@Value("${assistant.conversation.sqlite.enabled:true}") boolean enabled,
			@Value("${assistant.conversation.sqlite-file:/var/lib/wechat-agent-platform/conversations.db}") String databaseFile,
			ObjectMapper objectMapper) {
		this.enabled = enabled;
		this.databaseFile = Path.of(databaseFile).toAbsolutePath().normalize();
		this.jdbcUrl = "jdbc:sqlite:" + this.databaseFile;
		this.objectMapper = objectMapper;
		if (enabled) initialize();
	}

	public boolean isAvailable() {
		return enabled && available.get();
	}

	public String databasePath() {
		return databaseFile.toString();
	}

	public List<PersistedConversation> loadActive(long cutoffMs, int maxTurns) {
		if (!isAvailable()) return List.of();
		String sql = "SELECT session_id, account_id, user_id, summary, turns_json, last_access_at "
				+ "FROM conversation_sessions WHERE last_access_at >= ? ORDER BY last_access_at DESC";
		List<PersistedConversation> result = new ArrayList<>();
		try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setLong(1, cutoffMs);
			try (ResultSet rows = statement.executeQuery()) {
				while (rows.next()) result.add(read(rows, maxTurns));
			}
			warningLogged.set(false);
			return result;
		} catch (Exception error) {
			warn("加载", error);
			return List.of();
		}
	}

	public void upsert(String sessionId, String summary, List<ConversationTurn> turns, long lastAccessAt) {
		if (!isAvailable()) return;
		SessionAddress address = sessionAddress(sessionId);
		String sql = "INSERT INTO conversation_sessions "
				+ "(session_id, account_id, user_id, summary, turns_json, last_access_at, updated_at) "
				+ "VALUES (?, ?, ?, ?, ?, ?, ?) "
				+ "ON CONFLICT(session_id) DO UPDATE SET summary=excluded.summary, turns_json=excluded.turns_json, "
				+ "last_access_at=excluded.last_access_at, updated_at=excluded.updated_at";
		try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setString(1, sessionId);
			statement.setString(2, address.accountId());
			statement.setString(3, address.userId());
			statement.setString(4, summary == null ? "" : summary);
			statement.setString(5, objectMapper.writeValueAsString(turns));
			statement.setLong(6, lastAccessAt);
			statement.setLong(7, System.currentTimeMillis());
			statement.executeUpdate();
			warningLogged.set(false);
		} catch (Exception error) {
			warn("保存", error);
		}
	}

	public void delete(String sessionId) {
		if (!isAvailable() || sessionId == null || sessionId.isBlank()) return;
		executeDelete("DELETE FROM conversation_sessions WHERE session_id = ?", sessionId);
	}

	public boolean updateSummary(String sessionId, String summary) {
		if (!isAvailable() || sessionId == null || sessionId.isBlank()) return false;
		String sql = "UPDATE conversation_sessions SET summary = ?, updated_at = ? WHERE session_id = ?";
		try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setString(1, summary == null ? "" : summary);
			statement.setLong(2, System.currentTimeMillis());
			statement.setString(3, sessionId);
			int updated = statement.executeUpdate();
			warningLogged.set(false);
			return updated == 1;
		} catch (Exception error) {
			warn("更新", error);
			return false;
		}
	}

	public int deleteByAccount(String accountId) {
		if (!isAvailable() || accountId == null || accountId.isBlank()) return 0;
		return executeDelete("DELETE FROM conversation_sessions WHERE account_id = ?", accountId);
	}

	public int deleteBySessionPrefix(String sessionPrefix) {
		if (!isAvailable() || sessionPrefix == null || sessionPrefix.isBlank()) return 0;
		return executeDelete("DELETE FROM conversation_sessions WHERE session_id LIKE ? ESCAPE '\\'",
				escapeLike(sessionPrefix) + "%");
	}

	public int deleteExpired(long cutoffMs) {
		if (!isAvailable()) return 0;
		return executeDelete("DELETE FROM conversation_sessions WHERE last_access_at < ?", cutoffMs);
	}

	public List<ConversationView> listByAccount(String accountId, int limit) {
		return listByAccount(accountId, limit, Long.MIN_VALUE);
	}

	public List<ConversationView> listByAccount(String accountId, int limit, long cutoffMs) {
		if (!isAvailable() || accountId == null || accountId.isBlank()) return List.of();
		String sql = "SELECT session_id, user_id, summary, turns_json, last_access_at, updated_at "
				+ "FROM conversation_sessions WHERE account_id = ? AND last_access_at >= ? "
				+ "ORDER BY last_access_at DESC LIMIT ?";
		List<ConversationView> result = new ArrayList<>();
		try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setString(1, accountId);
			statement.setLong(2, cutoffMs);
			statement.setInt(3, Math.max(1, Math.min(MAX_ADMIN_PAGE_SIZE, limit)));
			try (ResultSet rows = statement.executeQuery()) {
				while (rows.next()) {
					List<ConversationTurn> turns = parseTurns(rows.getString("turns_json"), Integer.MAX_VALUE);
					result.add(new ConversationView(rows.getString("session_id"), rows.getString("user_id"),
							rows.getString("summary"), turns, rows.getLong("last_access_at"), rows.getLong("updated_at")));
				}
			}
			warningLogged.set(false);
			return result;
		} catch (Exception error) {
			warn("查询", error);
			return List.of();
		}
	}

	private void initialize() {
		try {
			Path parent = databaseFile.getParent();
			if (parent != null) Files.createDirectories(parent);
			try (Connection connection = open(); Statement statement = connection.createStatement()) {
				statement.execute("PRAGMA journal_mode=WAL");
				statement.execute("PRAGMA foreign_keys=ON");
				statement.execute("CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, applied_at INTEGER NOT NULL)");
				statement.execute("CREATE TABLE IF NOT EXISTS conversation_sessions ("
						+ "session_id TEXT PRIMARY KEY, account_id TEXT NOT NULL, user_id TEXT NOT NULL, "
						+ "summary TEXT NOT NULL DEFAULT '', turns_json TEXT NOT NULL, last_access_at INTEGER NOT NULL, "
						+ "updated_at INTEGER NOT NULL)");
				statement.execute("CREATE INDEX IF NOT EXISTS idx_conversation_sessions_account_access "
						+ "ON conversation_sessions(account_id, last_access_at DESC)");
				statement.execute("INSERT OR IGNORE INTO schema_migrations(version, applied_at) VALUES (1, "
						+ System.currentTimeMillis() + ")");
			}
			available.set(true);
			log.info("SQLite 会话数据库已就绪：{}", databaseFile);
		} catch (Exception error) {
			available.set(false);
			warn("初始化", error);
		}
	}

	private Connection open() throws java.sql.SQLException {
		Connection connection = DriverManager.getConnection(jdbcUrl);
		try (Statement statement = connection.createStatement()) {
			statement.execute("PRAGMA busy_timeout=5000");
		}
		return connection;
	}

	private PersistedConversation read(ResultSet row, int maxTurns) throws Exception {
		return new PersistedConversation(row.getString("session_id"), row.getString("summary"),
				parseTurns(row.getString("turns_json"), maxTurns), row.getLong("last_access_at"));
	}

	private List<ConversationTurn> parseTurns(String json, int maxTurns) throws Exception {
		List<ConversationTurn> turns = objectMapper.readValue(json, new TypeReference<List<ConversationTurn>>() { });
		if (turns == null || turns.isEmpty()) return List.of();
		return turns.subList(Math.max(0, turns.size() - maxTurns), turns.size());
	}

	private int executeDelete(String sql, Object value) {
		try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
			if (value instanceof Long number) statement.setLong(1, number);
			else statement.setString(1, String.valueOf(value));
			int deleted = statement.executeUpdate();
			warningLogged.set(false);
			return deleted;
		} catch (Exception error) {
			warn("删除", error);
			return 0;
		}
	}

	private void warn(String operation, Exception error) {
		if (warningLogged.compareAndSet(false, true)) {
			log.warn("SQLite 会话{}失败，将继续使用内存/Redis：{}", operation, error.getMessage());
		}
	}

	private static String escapeLike(String value) {
		return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

	private static SessionAddress sessionAddress(String sessionId) {
		int separator = sessionId.indexOf("::");
		return separator > 0 ? new SessionAddress(sessionId.substring(0, separator), sessionId.substring(separator + 2))
				: new SessionAddress("default", sessionId);
	}

	public record PersistedConversation(String sessionId, String summary, List<ConversationTurn> turns, long lastAccessAt) { }
	public record ConversationView(String sessionId, String userId, String summary, List<ConversationTurn> turns,
			long lastAccessAt, long updatedAt) { }
	private record SessionAddress(String accountId, String userId) { }
}
