package com.example.bot.monitoring;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;

/**
 * Tool invocation metrics with three sinks:
 * 1) in-memory counters (fast status snapshot, zeroed on restart),
 * 2) Micrometer timers with P95/P99 percentiles (via Actuator's registry),
 * 3) Redis daily buckets (survive restarts; feed the console usage report).
 * A bounded in-memory ring additionally keeps the most recent invocation
 * details (duration, outcome, error summary) for the console detail page.
 */
@Service
public class ToolMetricsService {
	private static final Logger log = LoggerFactory.getLogger(ToolMetricsService.class);
	private static final ZoneId BEIJING_ZONE = ZoneId.of("Asia/Shanghai");
	private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
	private static final Duration DAILY_BUCKET_TTL = Duration.ofDays(35);
	private static final int MAX_RECENT_INVOCATIONS = 500;
	private static final int MAX_ERROR_CHARS = 200;

	private final ConcurrentHashMap<String, MutableMetric> tools = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, Timer> timers = new ConcurrentHashMap<>();
	private final ArrayDeque<InvocationRecord> recent = new ArrayDeque<>();
	private final MeterRegistry meterRegistry;
	private final StringRedisTemplate redis;
	private final String dailyKeyPrefix;
	private final AtomicBoolean redisWarningLogged = new AtomicBoolean();

	@Autowired
	public ToolMetricsService(ObjectProvider<MeterRegistry> meterRegistryProvider,
			ObjectProvider<StringRedisTemplate> redisProvider) {
		this(meterRegistryProvider.getIfAvailable(), redisProvider.getIfAvailable());
	}

	ToolMetricsService(MeterRegistry meterRegistry, StringRedisTemplate redis) {
		this.meterRegistry = meterRegistry;
		this.redis = redis;
		this.dailyKeyPrefix = "bot:metrics:day:";
	}

	/** 测试便捷构造：仅内存计数，无 Micrometer/Redis。 */
	ToolMetricsService() {
		this(null, (StringRedisTemplate) null);
	}

	public Invocation start(String toolName) {
		return start(toolName, null);
	}

	public Invocation start(String toolName, String sessionId) {
		return new Invocation(toolName, sessionId, System.nanoTime(), System.currentTimeMillis());
	}

	public void success(Invocation invocation) {
		record(invocation, true, null);
	}

	public void failure(Invocation invocation) {
		record(invocation, false, null);
	}

	public void failure(Invocation invocation, String errorMessage) {
		record(invocation, false, errorMessage);
	}

	private void record(Invocation invocation, boolean success, String errorMessage) {
		long durationMs = (System.nanoTime() - invocation.startedAtNanos()) / 1_000_000L;
		MutableMetric metric = tools.computeIfAbsent(invocation.toolName(), ignored -> new MutableMetric());
		metric.calls.increment();
		if (success) metric.successes.increment(); else metric.failures.increment();
		metric.totalDurationMs.add(durationMs);
		recordTimer(invocation.toolName(), durationMs);
		recordDailyBucket(invocation.toolName(), success, durationMs);
		recordRecent(invocation, success, durationMs, errorMessage);
	}

	private void recordTimer(String toolName, long durationMs) {
		if (meterRegistry == null) return;
		try {
			timers.computeIfAbsent(toolName, name -> Timer.builder("assistant.tool.duration")
					.description("Function Calling 工具执行耗时")
					.tag("tool", name)
					.publishPercentiles(0.5, 0.95, 0.99)
					.register(meterRegistry)).record(durationMs, TimeUnit.MILLISECONDS);
		} catch (RuntimeException error) {
			log.debug("Micrometer 记录失败：{}", error.getMessage());
		}
	}

	private void recordDailyBucket(String toolName, boolean success, long durationMs) {
		if (redis == null) return;
		try {
			String key = dailyKeyPrefix + LocalDate.now(BEIJING_ZONE).format(DAY_FORMAT);
			redis.opsForHash().increment(key, toolName + ":calls", 1);
			if (!success) redis.opsForHash().increment(key, toolName + ":failures", 1);
			redis.opsForHash().increment(key, toolName + ":totalMs", durationMs);
			redis.expire(key, DAILY_BUCKET_TTL);
			redisWarningLogged.set(false);
		} catch (Exception error) {
			if (redisWarningLogged.compareAndSet(false, true)) {
				log.warn("Redis 指标日桶写入失败，用量日报可能缺数据：{}", error.getMessage());
			}
		}
	}

	private void recordRecent(Invocation invocation, boolean success, long durationMs, String errorMessage) {
		String error = errorMessage == null || errorMessage.isBlank() ? null
				: errorMessage.length() > MAX_ERROR_CHARS
						? errorMessage.substring(0, MAX_ERROR_CHARS) + "…" : errorMessage;
		InvocationRecord record = new InvocationRecord(invocation.toolName(),
				maskSession(invocation.sessionId()), invocation.startedAtEpochMs(), durationMs, success, error);
		synchronized (recent) {
			recent.addFirst(record);
			while (recent.size() > MAX_RECENT_INVOCATIONS) recent.removeLast();
		}
	}

	/** 会话 ID 含微信用户标识，控制台展示前保留首尾少量字符。 */
	static String maskSession(String sessionId) {
		if (sessionId == null || sessionId.isBlank()) return "";
		if (sessionId.length() <= 8) return sessionId.charAt(0) + "***";
		return sessionId.substring(0, 6) + "***" + sessionId.substring(sessionId.length() - 3);
	}

	public List<InvocationRecord> recentInvocations(int limit) {
		int bounded = Math.max(1, Math.min(MAX_RECENT_INVOCATIONS, limit));
		synchronized (recent) {
			return recent.stream().limit(bounded).toList();
		}
	}

	public Map<String, ToolMetric> snapshot() {
		Map<String, ToolMetric> result = new LinkedHashMap<>();
		tools.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
			MutableMetric value = entry.getValue();
			long calls = value.calls.sum();
			long[] percentiles = percentiles(entry.getKey());
			result.put(entry.getKey(), new ToolMetric(calls, value.successes.sum(), value.failures.sum(),
					calls == 0 ? 0 : value.totalDurationMs.sum() / calls, percentiles[0], percentiles[1]));
		});
		return result;
	}

	/** [p95Ms, p99Ms]，Micrometer 不可用时为 0。 */
	private long[] percentiles(String toolName) {
		Timer timer = timers.get(toolName);
		if (timer == null) return new long[] { 0, 0 };
		long p95 = 0;
		long p99 = 0;
		for (ValueAtPercentile value : timer.takeSnapshot().percentileValues()) {
			if (Math.abs(value.percentile() - 0.95) < 0.001) p95 = (long) value.value(TimeUnit.MILLISECONDS);
			if (Math.abs(value.percentile() - 0.99) < 0.001) p99 = (long) value.value(TimeUnit.MILLISECONDS);
		}
		return new long[] { p95, p99 };
	}

	/** 近 N 天各工具用量（含今天），来自 Redis 日桶；Redis 不可用时返回空列表。 */
	public List<DailyUsage> dailyUsage(int days) {
		int bounded = Math.max(1, Math.min(35, days));
		if (redis == null) return List.of();
		List<DailyUsage> result = new ArrayList<>();
		LocalDate today = LocalDate.now(BEIJING_ZONE);
		try {
			for (int offset = bounded - 1; offset >= 0; offset--) {
				LocalDate day = today.minusDays(offset);
				Map<Object, Object> raw = redis.opsForHash().entries(dailyKeyPrefix + day.format(DAY_FORMAT));
				result.add(new DailyUsage(day.toString(), toToolUsage(raw)));
			}
			redisWarningLogged.set(false);
			return result;
		} catch (Exception error) {
			if (redisWarningLogged.compareAndSet(false, true)) {
				log.warn("Redis 指标日桶读取失败：{}", error.getMessage());
			}
			return List.of();
		}
	}

	private static Map<String, ToolUsage> toToolUsage(Map<Object, Object> raw) {
		Map<String, long[]> byTool = new LinkedHashMap<>();
		for (Map.Entry<Object, Object> entry : raw.entrySet()) {
			String field = String.valueOf(entry.getKey());
			int separator = field.lastIndexOf(':');
			if (separator <= 0) continue;
			String tool = field.substring(0, separator);
			String kind = field.substring(separator + 1);
			long value;
			try {
				value = Long.parseLong(String.valueOf(entry.getValue()));
			} catch (NumberFormatException ignored) {
				continue;
			}
			long[] usage = byTool.computeIfAbsent(tool, ignored -> new long[3]);
			switch (kind) {
				case "calls" -> usage[0] = value;
				case "failures" -> usage[1] = value;
				case "totalMs" -> usage[2] = value;
				default -> { }
			}
		}
		Map<String, ToolUsage> result = new LinkedHashMap<>();
		byTool.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
			long[] usage = entry.getValue();
			result.put(entry.getKey(), new ToolUsage(usage[0], usage[1],
					usage[0] == 0 ? 0 : usage[2] / usage[0]));
		});
		return result;
	}

	public SystemMetric systemSnapshot() {
		Runtime runtime = Runtime.getRuntime();
		return new SystemMetric(Instant.now().toString(), ManagementFactory.getRuntimeMXBean().getUptime(),
				runtime.totalMemory() - runtime.freeMemory(), runtime.maxMemory(), tools.size());
	}

	public record Invocation(String toolName, String sessionId, long startedAtNanos, long startedAtEpochMs) { }
	public record ToolMetric(long calls, long successes, long failures, long averageDurationMs,
			long p95DurationMs, long p99DurationMs) { }
	public record InvocationRecord(String tool, String session, long startedAtMs, long durationMs,
			boolean success, String error) { }
	public record DailyUsage(String date, Map<String, ToolUsage> tools) { }
	public record ToolUsage(long calls, long failures, long averageDurationMs) { }
	public record SystemMetric(String timestamp, long uptimeMs, long usedHeapBytes, long maxHeapBytes,
			int observedToolCount) { }

	private static final class MutableMetric {
		private final LongAdder calls = new LongAdder();
		private final LongAdder successes = new LongAdder();
		private final LongAdder failures = new LongAdder();
		private final LongAdder totalDurationMs = new LongAdder();
	}
}
