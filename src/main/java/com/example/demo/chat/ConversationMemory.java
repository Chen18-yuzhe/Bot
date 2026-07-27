package com.example.demo.chat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.HexFormat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;

@Service
public class ConversationMemory {
	private static final Logger log = LoggerFactory.getLogger(ConversationMemory.class);
	private final Map<String, SessionMemory> sessions = new ConcurrentHashMap<>();
	private final int maxTurns;
	private final long ttlMillis;
	private final int maxSessions;
	private final int maxMessageChars;
	private final int summaryMaxChars;
	private final Path storageFile;
	private final ObjectMapper objectMapper;
	private final StringRedisTemplate redis;
	private final boolean redisEnabled;
	private final String redisKeyPrefix;
	private final AtomicBoolean redisWarningLogged = new AtomicBoolean();
	private final AtomicBoolean saveScheduled = new AtomicBoolean();
	private final ScheduledExecutorService persistenceExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread thread = new Thread(r, "conversation-store");
		thread.setDaemon(true);
		return thread;
	});

	@Autowired
	public ConversationMemory(@Value("${assistant.conversation.max-turns:8}") int maxTurns,
			@Value("${assistant.conversation.ttl-minutes:1440}") long ttlMinutes,
			@Value("${assistant.conversation.max-sessions:1000}") int maxSessions,
			@Value("${assistant.conversation.max-message-chars:4000}") int maxMessageChars,
			@Value("${assistant.conversation.summary-max-chars:6000}") int summaryMaxChars,
			@Value("${assistant.conversation.storage-file:}") String storageFile,
			@Value("${assistant.conversation.redis-enabled:true}") boolean redisEnabled,
			@Value("${assistant.conversation.redis-key-prefix:demo-assistant:conversation:}") String redisKeyPrefix,
			ObjectMapper objectMapper, ObjectProvider<StringRedisTemplate> redisProvider) {
		this(maxTurns, ttlMinutes, maxSessions, maxMessageChars, summaryMaxChars, storageFile,
				objectMapper, redisEnabled, redisKeyPrefix, redisProvider.getIfAvailable());
	}

	private ConversationMemory(int maxTurns, long ttlMinutes, int maxSessions, int maxMessageChars,
			int summaryMaxChars, String storageFile, ObjectMapper objectMapper, boolean redisEnabled,
			String redisKeyPrefix, StringRedisTemplate redis) {
		this.maxTurns = Math.max(1, maxTurns);
		this.ttlMillis = Duration.ofMinutes(Math.max(1, ttlMinutes)).toMillis();
		this.maxSessions = Math.max(10, maxSessions);
		this.maxMessageChars = Math.max(200, maxMessageChars);
		this.summaryMaxChars = Math.max(500, summaryMaxChars);
		this.storageFile = storageFile == null || storageFile.isBlank() ? null : Path.of(storageFile).toAbsolutePath();
		this.objectMapper = objectMapper;
		this.redis = redis;
		this.redisEnabled = redisEnabled && redis != null;
		this.redisKeyPrefix = redisKeyPrefix == null || redisKeyPrefix.isBlank()
				? "demo-assistant:conversation:" : redisKeyPrefix.trim();
		load();
		migrateLocalSessionsToRedis();
	}

	ConversationMemory(int maxTurns, long ttlMinutes) {
		this(maxTurns, ttlMinutes, 1000, 4000, 6000, "", new ObjectMapper(), false, "", null);
	}

	ConversationMemory(int maxTurns, long ttlMinutes, int maxSessions, int maxMessageChars,
			int summaryMaxChars, String storageFile, ObjectMapper objectMapper) {
		this(maxTurns, ttlMinutes, maxSessions, maxMessageChars, summaryMaxChars, storageFile,
				objectMapper, false, "", null);
	}

	ConversationMemory(int maxTurns, long ttlMinutes, ObjectMapper objectMapper,
			StringRedisTemplate redis, String redisKeyPrefix) {
		this(maxTurns, ttlMinutes, 1000, 4000, 6000, "", objectMapper, true, redisKeyPrefix, redis);
	}

	public List<ConversationTurn> history(String sessionId) {
		if (sessionId == null || sessionId.isBlank()) return List.of();
		RedisRead redisRead = readRedis(sessionId);
		if (redisRead.available()) {
			if (redisRead.memory() == null) {
				SessionMemory local = sessions.get(sessionId);
				if (local == null || expired(local)) {
					sessions.remove(sessionId);
					return List.of();
				}
				writeRedis(sessionId, local);
			} else {
				SessionMemory local = sessions.get(sessionId);
				SessionMemory newest = local != null && local.lastAccessAt > redisRead.memory().lastAccessAt
						? local : redisRead.memory();
				sessions.put(sessionId, newest);
				if (newest == local) writeRedis(sessionId, newest);
			}
		}
		SessionMemory memory = sessions.get(sessionId);
		if (memory == null) return List.of();
		synchronized (memory) {
			if (expired(memory)) {
				sessions.remove(sessionId, memory);
				scheduleSave();
				return List.of();
			}
			memory.lastAccessAt = System.currentTimeMillis();
			List<ConversationTurn> result = new ArrayList<>();
			if (!memory.summary.isBlank()) {
				result.add(new ConversationTurn("Earlier conversation summary", memory.summary));
			}
			result.addAll(memory.turns);
			return result;
		}
	}

	public void remember(String sessionId, String question, String answer) {
		if (sessionId == null || sessionId.isBlank() || question == null || answer == null) return;
		SessionMemory memory = sessions.get(sessionId);
		if (memory == null) {
			RedisRead redisRead = readRedis(sessionId);
			SessionMemory candidate = redisRead.memory() == null ? new SessionMemory() : redisRead.memory();
			SessionMemory existing = sessions.putIfAbsent(sessionId, candidate);
			memory = existing == null ? candidate : existing;
		}
		synchronized (memory) {
			if (expired(memory)) memory.turns.clear();
			memory.turns.addLast(new ConversationTurn(limit(question), limit(answer)));
			while (memory.turns.size() > maxTurns) {
				ConversationTurn old = memory.turns.removeFirst();
				appendSummary(memory, old);
			}
			memory.lastAccessAt = System.currentTimeMillis();
		}
		prune();
		writeRedis(sessionId, memory);
		scheduleSave();
	}

	public void clear(String sessionId) {
		if (sessionId == null || sessionId.isBlank()) return;
		sessions.remove(sessionId);
		deleteRedis(sessionId);
		scheduleSave();
	}

	/** Removes every persisted conversation whose session id belongs to an account namespace. */
	public int clearBySessionPrefix(String sessionPrefix) {
		if (sessionPrefix == null || sessionPrefix.isBlank()) return 0;
		Set<String> sessionIds = new LinkedHashSet<>();
		sessions.keySet().stream().filter(id -> id.startsWith(sessionPrefix)).forEach(sessionIds::add);
		if (redisEnabled) {
			try {
				Set<String> keys = redis.keys(redisKeyPrefix + "*");
				if (keys != null) {
					for (String key : keys) {
						String json = redis.opsForValue().get(key);
						if (json == null) continue;
						StoredSession stored = objectMapper.readValue(json, StoredSession.class);
						if (stored.sessionId() != null && stored.sessionId().startsWith(sessionPrefix)) {
							sessionIds.add(stored.sessionId());
						}
					}
				}
				redisWarningLogged.set(false);
			} catch (Exception error) {
				warnRedis("枚举待删除会话", error);
			}
		}
		sessionIds.forEach(this::clear);
		return sessionIds.size();
	}

	public int activeSessionCount() {
		prune();
		return sessions.size();
	}

	private RedisRead readRedis(String sessionId) {
		if (!redisEnabled) return new RedisRead(false, null);
		try {
			String key = redisKey(sessionId);
			String json = redis.opsForValue().get(key);
			if (json == null) return new RedisRead(true, null);
			StoredSession stored = objectMapper.readValue(json, StoredSession.class);
			SessionMemory memory = fromStored(stored);
			redis.expire(key, Duration.ofMillis(ttlMillis));
			redisWarningLogged.set(false);
			return new RedisRead(true, memory);
		} catch (Exception error) {
			warnRedis("读取", error);
			return new RedisRead(false, null);
		}
	}

	private void writeRedis(String sessionId, SessionMemory memory) {
		if (!redisEnabled) return;
		try {
			StoredSession stored;
			synchronized (memory) {
				stored = new StoredSession(sessionId, memory.lastAccessAt, memory.summary,
						new ArrayList<>(memory.turns));
			}
			redis.opsForValue().set(redisKey(sessionId), objectMapper.writeValueAsString(stored),
					Duration.ofMillis(ttlMillis));
			redisWarningLogged.set(false);
		} catch (Exception error) {
			warnRedis("写入", error);
		}
	}

	private void deleteRedis(String sessionId) {
		if (!redisEnabled) return;
		try {
			redis.delete(redisKey(sessionId));
			redisWarningLogged.set(false);
		} catch (Exception error) {
			warnRedis("删除", error);
		}
	}

	private String redisKey(String sessionId) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(sessionId.getBytes(StandardCharsets.UTF_8));
			return redisKeyPrefix + HexFormat.of().formatHex(digest);
		} catch (Exception impossible) {
			throw new IllegalStateException("无法生成 Redis 会话键", impossible);
		}
	}

	private SessionMemory fromStored(StoredSession stored) {
		SessionMemory memory = new SessionMemory();
		memory.summary = stored.summary() == null ? "" : stored.summary();
		List<ConversationTurn> turns = stored.turns() == null ? List.of() : stored.turns();
		turns.stream().skip(Math.max(0, turns.size() - maxTurns)).forEach(memory.turns::addLast);
		memory.lastAccessAt = stored.lastAccessAt();
		return memory;
	}

	private void warnRedis(String operation, Exception error) {
		if (redisWarningLogged.compareAndSet(false, true)) {
			log.warn("Redis 对话存储{}失败，暂时降级到本地存储：{}", operation, error.getMessage());
		}
	}

	private void migrateLocalSessionsToRedis() {
		if (!redisEnabled || sessions.isEmpty()) return;
		int migrated = 0;
		for (Map.Entry<String, SessionMemory> entry : sessions.entrySet()) {
			try {
				String key = redisKey(entry.getKey());
				if (Boolean.FALSE.equals(redis.hasKey(key))) {
					writeRedis(entry.getKey(), entry.getValue());
					migrated++;
				}
			} catch (Exception error) {
				warnRedis("迁移", error);
				break;
			}
		}
		if (migrated > 0) log.info("已将 {} 个本地对话会话迁移到 Redis", migrated);
	}

	private String limit(String value) {
		return value.length() <= maxMessageChars ? value : value.substring(0, maxMessageChars);
	}

	private void appendSummary(SessionMemory memory, ConversationTurn turn) {
		String addition = "用户：" + turn.userMessage() + "\n助手：" + turn.assistantMessage() + "\n";
		String combined = memory.summary.isBlank() ? addition : memory.summary + addition;
		if (combined.length() > summaryMaxChars) {
			combined = combined.substring(combined.length() - summaryMaxChars);
			int boundary = combined.indexOf('\n');
			if (boundary >= 0 && boundary < combined.length() - 1) combined = combined.substring(boundary + 1);
		}
		memory.summary = combined;
	}

	private void prune() {
		long now = System.currentTimeMillis();
		sessions.entrySet().removeIf(entry -> now - entry.getValue().lastAccessAt > ttlMillis);
		int excess = sessions.size() - maxSessions;
		if (excess <= 0) return;
		sessions.entrySet().stream().sorted(Comparator.comparingLong(e -> e.getValue().lastAccessAt))
				.limit(excess).map(Map.Entry::getKey).toList().forEach(sessions::remove);
	}

	private boolean expired(SessionMemory memory) {
		return System.currentTimeMillis() - memory.lastAccessAt > ttlMillis;
	}

	private void scheduleSave() {
		if (storageFile == null || !saveScheduled.compareAndSet(false, true)) return;
		persistenceExecutor.schedule(() -> {
			try { save(); } finally { saveScheduled.set(false); }
		}, 1, TimeUnit.SECONDS);
	}

	private void load() {
		if (storageFile == null || !Files.isRegularFile(storageFile)) return;
		try {
			StoredState state = objectMapper.readValue(storageFile.toFile(), StoredState.class);
			long now = System.currentTimeMillis();
			for (StoredSession stored : state.sessions()) {
				if (now - stored.lastAccessAt() > ttlMillis) continue;
				SessionMemory memory = new SessionMemory();
				memory.summary = stored.summary() == null ? "" : stored.summary();
				stored.turns().stream().skip(Math.max(0, stored.turns().size() - maxTurns)).forEach(memory.turns::addLast);
				memory.lastAccessAt = stored.lastAccessAt();
				sessions.put(stored.sessionId(), memory);
			}
			prune();
			log.info("已恢复 {} 个持久化对话会话", sessions.size());
		} catch (Exception error) {
			log.warn("读取对话存储失败，将使用空会话：{}", error.getMessage());
		}
	}

	private void save() {
		if (storageFile == null) return;
		try {
			prune();
			List<StoredSession> stored = new ArrayList<>();
			for (Map.Entry<String, SessionMemory> entry : sessions.entrySet()) {
				SessionMemory memory = entry.getValue();
				synchronized (memory) {
					stored.add(new StoredSession(entry.getKey(), memory.lastAccessAt, memory.summary, new ArrayList<>(memory.turns)));
				}
			}
			Files.createDirectories(storageFile.getParent());
			Path temporary = storageFile.resolveSibling(storageFile.getFileName() + ".tmp");
			objectMapper.writeValue(temporary.toFile(), new StoredState(stored));
			Files.move(temporary, storageFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (Exception error) {
			log.warn("保存对话存储失败：{}", error.getMessage());
		}
	}

	@PreDestroy
	void close() {
		persistenceExecutor.shutdownNow();
		save();
	}

	private static final class SessionMemory {
		private final ArrayDeque<ConversationTurn> turns = new ArrayDeque<>();
		private String summary = "";
		private long lastAccessAt = System.currentTimeMillis();
	}

	private record StoredState(List<StoredSession> sessions) { }
	private record StoredSession(String sessionId, long lastAccessAt, String summary, List<ConversationTurn> turns) { }
	private record RedisRead(boolean available, SessionMemory memory) { }
}
