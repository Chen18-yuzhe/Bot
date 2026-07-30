package com.example.bot.travel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.bot.ApplicationException;

/** Durable SQLite store for plans, quotes and orders referenced by resumable Agent tasks. */
@Component
public class TravelStateStore {
	public static final String PLAN = "PLAN";
	public static final String QUOTE = "QUOTE";
	public static final String ORDER = "ORDER";

	private static final Logger log = LoggerFactory.getLogger(TravelStateStore.class);
	private final boolean enabled;
	private final Path databaseFile;
	private final String jdbcUrl;
	private final long planTtlMs;
	private final long orderTtlMs;
	private final AtomicBoolean available = new AtomicBoolean();

	public TravelStateStore(
			@Value("${assistant.conversation.sqlite-file:/var/lib/wechat-agent-platform/conversations.db}") String databaseFile,
			@Value("${travel.state.enabled:true}") boolean enabled,
			@Value("${travel.state.plan-ttl-days:30}") long planTtlDays,
			@Value("${travel.state.order-ttl-days:365}") long orderTtlDays) {
		this.enabled = enabled;
		this.databaseFile = Path.of(databaseFile).toAbsolutePath().normalize();
		this.jdbcUrl = "jdbc:sqlite:" + this.databaseFile;
		this.planTtlMs = Duration.ofDays(Math.max(1, planTtlDays)).toMillis();
		this.orderTtlMs = Duration.ofDays(Math.max(30, orderTtlDays)).toMillis();
		if (enabled) initialize();
	}

	public boolean isAvailable() {
		return enabled && available.get();
	}

	public long defaultExpiry(String kind) {
		long ttl = ORDER.equals(kind) ? orderTtlMs : planTtlMs;
		return System.currentTimeMillis() + ttl;
	}

	public void put(String kind, String id, String userId, String payloadJson, long expiresAtMs) {
		if (!enabled) return;
		if (!isAvailable()) throw databaseUnavailable("写入");
		String sql = "INSERT INTO travel_state(kind,state_id,user_id,payload_json,expires_at,updated_at) "
				+ "VALUES(?,?,?,?,?,?) ON CONFLICT(kind,state_id) DO UPDATE SET "
				+ "user_id=excluded.user_id,payload_json=excluded.payload_json,"
				+ "expires_at=excluded.expires_at,updated_at=excluded.updated_at";
		try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setString(1, kind);
			statement.setString(2, id);
			statement.setString(3, userId == null ? "" : userId);
			statement.setString(4, payloadJson);
			statement.setLong(5, expiresAtMs);
			statement.setLong(6, System.currentTimeMillis());
			statement.executeUpdate();
		} catch (Exception exception) {
			throw databaseUnavailable("写入", exception);
		}
	}

	public Optional<String> get(String kind, String id, String userId) {
		if (!isAvailable() || id == null || id.isBlank()) return Optional.empty();
		String sql = "SELECT payload_json FROM travel_state WHERE kind=? AND state_id=? "
				+ "AND user_id=? AND expires_at>=?";
		try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setString(1, kind);
			statement.setString(2, id);
			statement.setString(3, userId == null ? "" : userId);
			statement.setLong(4, System.currentTimeMillis());
			try (ResultSet rows = statement.executeQuery()) {
				return rows.next() ? Optional.ofNullable(rows.getString(1)) : Optional.empty();
			}
		} catch (Exception exception) {
			throw databaseUnavailable("读取", exception);
		}
	}

	public Optional<String> getUnscoped(String kind, String id) {
		if (!isAvailable() || id == null || id.isBlank()) return Optional.empty();
		String sql = "SELECT payload_json FROM travel_state WHERE kind=? AND state_id=? AND expires_at>=?";
		try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setString(1, kind);
			statement.setString(2, id);
			statement.setLong(3, System.currentTimeMillis());
			try (ResultSet rows = statement.executeQuery()) {
				return rows.next() ? Optional.ofNullable(rows.getString(1)) : Optional.empty();
			}
		} catch (Exception exception) {
			throw databaseUnavailable("读取", exception);
		}
	}

	public Optional<String> getLatest(String kind, String userId) {
		if (!isAvailable()) return Optional.empty();
		String sql = "SELECT payload_json FROM travel_state WHERE kind=? AND user_id=? "
				+ "AND expires_at>=? ORDER BY updated_at DESC LIMIT 1";
		try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setString(1, kind);
			statement.setString(2, userId == null ? "" : userId);
			statement.setLong(3, System.currentTimeMillis());
			try (ResultSet rows = statement.executeQuery()) {
				return rows.next() ? Optional.ofNullable(rows.getString(1)) : Optional.empty();
			}
		} catch (Exception exception) {
			throw databaseUnavailable("读取", exception);
		}
	}

	@Scheduled(fixedDelayString = "${travel.state.cleanup-interval-ms:86400000}")
	public void deleteExpired() {
		if (!isAvailable()) return;
		try (Connection connection = open();
				PreparedStatement statement = connection.prepareStatement(
						"DELETE FROM travel_state WHERE expires_at < ?")) {
			statement.setLong(1, System.currentTimeMillis());
			int deleted = statement.executeUpdate();
			if (deleted > 0) log.info("已清理过期旅游状态：{} 条", deleted);
		} catch (Exception exception) {
			log.warn("清理旅游状态失败：{}", exception.getMessage());
		}
	}

	private void initialize() {
		try {
			Path parent = databaseFile.getParent();
			if (parent != null) Files.createDirectories(parent);
			try (Connection connection = open(); Statement statement = connection.createStatement()) {
				statement.execute("PRAGMA journal_mode=WAL");
				statement.execute("CREATE TABLE IF NOT EXISTS travel_state ("
						+ "kind TEXT NOT NULL,state_id TEXT NOT NULL,user_id TEXT NOT NULL,"
						+ "payload_json TEXT NOT NULL,expires_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,"
						+ "PRIMARY KEY(kind,state_id))");
				statement.execute("CREATE INDEX IF NOT EXISTS idx_travel_state_user "
						+ "ON travel_state(kind,user_id,updated_at DESC)");
				statement.execute("CREATE INDEX IF NOT EXISTS idx_travel_state_expiry "
						+ "ON travel_state(expires_at)");
			}
			available.set(true);
		} catch (Exception exception) {
			available.set(false);
			log.warn("旅游状态 SQLite 初始化失败：{}", exception.getMessage());
		}
	}

	private Connection open() throws java.sql.SQLException {
		Connection connection = DriverManager.getConnection(jdbcUrl);
		try (Statement statement = connection.createStatement()) {
			statement.execute("PRAGMA busy_timeout=5000");
		}
		return connection;
	}

	private static ApplicationException databaseUnavailable(String action) {
		return databaseUnavailable(action, null);
	}

	private static ApplicationException databaseUnavailable(String action, Exception cause) {
		return new ApplicationException(HttpStatus.SERVICE_UNAVAILABLE,
				"旅行状态数据库" + action + "失败，需要人工检查数据库文件权限和磁盘状态", cause);
	}
}
