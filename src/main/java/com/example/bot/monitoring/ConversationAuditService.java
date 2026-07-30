package com.example.bot.monitoring;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Opt-in conversation audit log for the private console. Disabled by default:
 * WeChat conversations are personal data, so recording only happens after the
 * operator explicitly turns it on (config default or a runtime toggle that
 * resets to the configured default on restart). Entries go to a bounded Redis
 * list with a short TTL, with a small in-memory ring as degraded storage.
 */
@Service
public class ConversationAuditService {
	private static final Logger log = LoggerFactory.getLogger(ConversationAuditService.class);
	private static final int MAX_MEMORY_ENTRIES = 200;
	private static final int MAX_REDIS_ENTRIES = 2_000;
	private static final int MAX_TEXT_CHARS = 1_000;
	private static final Duration RETENTION = Duration.ofDays(7);

	private final ObjectMapper objectMapper;
	private final StringRedisTemplate redis;
	private final String redisKey;
	private final AtomicBoolean enabled;
	private final ArrayDeque<AuditEntry> memory = new ArrayDeque<>();
	private final AtomicBoolean redisWarningLogged = new AtomicBoolean();

	public ConversationAuditService(ObjectMapper objectMapper,
			ObjectProvider<StringRedisTemplate> redisProvider,
			@Value("${assistant.audit.enabled:false}") boolean enabledByDefault,
			@Value("${assistant.audit.redis-key:bot:audit:messages}") String redisKey) {
		this.objectMapper = objectMapper;
		this.redis = redisProvider.getIfAvailable();
		this.redisKey = redisKey == null || redisKey.isBlank() ? "bot:audit:messages" : redisKey.trim();
		this.enabled = new AtomicBoolean(enabledByDefault);
	}

	public boolean isEnabled() {
		return enabled.get();
	}

	public void setEnabled(boolean value) {
		boolean previous = enabled.getAndSet(value);
		if (previous != value) log.info("对话审计已{}", value ? "开启" : "关闭");
	}

	public void record(String sessionId, String question, String answer) {
		if (!enabled.get()) return;
		AuditEntry entry = new AuditEntry(System.currentTimeMillis(),
				ToolMetricsService.maskSession(sessionId), truncate(question), truncate(answer));
		synchronized (memory) {
			memory.addFirst(entry);
			while (memory.size() > MAX_MEMORY_ENTRIES) memory.removeLast();
		}
		if (redis == null) return;
		try {
			redis.opsForList().leftPush(redisKey, objectMapper.writeValueAsString(entry));
			redis.opsForList().trim(redisKey, 0, MAX_REDIS_ENTRIES - 1);
			redis.expire(redisKey, RETENTION);
			redisWarningLogged.set(false);
		} catch (Exception error) {
			if (redisWarningLogged.compareAndSet(false, true)) {
				log.warn("Redis 审计写入失败，审计记录暂时只保存在内存：{}", error.getMessage());
			}
		}
	}

	public List<AuditEntry> recent(int limit) {
		int bounded = Math.max(1, Math.min(MAX_REDIS_ENTRIES, limit));
		if (redis != null) {
			try {
				List<String> raw = redis.opsForList().range(redisKey, 0, bounded - 1);
				redisWarningLogged.set(false);
				if (raw != null) {
					List<AuditEntry> entries = new ArrayList<>();
					for (String json : raw) {
						try {
							entries.add(objectMapper.readValue(json, AuditEntry.class));
						} catch (Exception ignored) { }
					}
					return entries;
				}
			} catch (Exception error) {
				if (redisWarningLogged.compareAndSet(false, true)) {
					log.warn("Redis 审计读取失败，返回内存记录：{}", error.getMessage());
				}
			}
		}
		synchronized (memory) {
			return memory.stream().limit(bounded).toList();
		}
	}

	private static String truncate(String value) {
		if (value == null) return "";
		return value.length() > MAX_TEXT_CHARS ? value.substring(0, MAX_TEXT_CHARS) + "…" : value;
	}

	public record AuditEntry(long timestampMs, String session, String question, String answer) { }
}
