package com.example.demo.guard;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Per-user fixed-window message limiter. DashScope image generation and web
 * search are metered upstream calls, so one user must not be able to hammer
 * the assistant in a tight loop. Counters live in Redis (shared across
 * restarts); when Redis is down we degrade to an in-process window rather
 * than failing open silently forever.
 */
@Service
public class UserRateLimiter {
	private static final Logger log = LoggerFactory.getLogger(UserRateLimiter.class);
	private static final int MAX_LOCAL_WINDOWS = 5_000;

	private final StringRedisTemplate redis;
	private final boolean enabled;
	private final int perMinute;
	private final String prefix;
	private final ConcurrentHashMap<String, LocalWindow> localWindows = new ConcurrentHashMap<>();
	private final AtomicBoolean redisWarningLogged = new AtomicBoolean();

	public UserRateLimiter(ObjectProvider<StringRedisTemplate> redisProvider,
			@Value("${assistant.rate-limit.enabled:true}") boolean enabled,
			@Value("${assistant.rate-limit.per-minute:10}") int perMinute,
			@Value("${assistant.rate-limit.redis-key-prefix:demo-assistant:ratelimit:}") String prefix) {
		this.redis = redisProvider.getIfAvailable();
		this.enabled = enabled;
		this.perMinute = Math.max(1, perMinute);
		this.prefix = prefix == null || prefix.isBlank() ? "demo-assistant:ratelimit:" : prefix.trim();
	}

	/** True when this message may proceed; false when the user hit the window cap. */
	public boolean tryAcquire(String sessionId) {
		if (!enabled || sessionId == null || sessionId.isBlank()) return true;
		long minute = System.currentTimeMillis() / 60_000L;
		if (redis != null) {
			try {
				String key = prefix + sessionId + ":" + minute;
				Long count = redis.opsForValue().increment(key);
				if (count != null && count == 1L) {
					redis.expire(key, Duration.ofSeconds(120));
				}
				redisWarningLogged.set(false);
				return count == null || count <= perMinute;
			} catch (Exception error) {
				if (redisWarningLogged.compareAndSet(false, true)) {
					log.warn("Redis 限流计数失败，暂时使用内存窗口：{}", error.getMessage());
				}
			}
		}
		return acquireLocal(sessionId, minute);
	}

	public int perMinute() {
		return perMinute;
	}

	private boolean acquireLocal(String sessionId, long minute) {
		if (localWindows.size() > MAX_LOCAL_WINDOWS) evictStale(minute);
		LocalWindow window = localWindows.compute(sessionId, (ignored, existing) ->
				existing == null || existing.minute != minute ? new LocalWindow(minute) : existing);
		return window.count.incrementAndGet() <= perMinute;
	}

	private void evictStale(long minute) {
		Iterator<Map.Entry<String, LocalWindow>> iterator = localWindows.entrySet().iterator();
		while (iterator.hasNext()) {
			if (iterator.next().getValue().minute < minute) iterator.remove();
		}
	}

	private static final class LocalWindow {
		private final long minute;
		private final AtomicInteger count = new AtomicInteger();

		private LocalWindow(long minute) {
			this.minute = minute;
		}
	}
}
