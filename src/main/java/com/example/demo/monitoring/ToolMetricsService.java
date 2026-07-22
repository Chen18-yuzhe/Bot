package com.example.demo.monitoring;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import org.springframework.stereotype.Service;

@Service
public class ToolMetricsService {
	private final ConcurrentHashMap<String, MutableMetric> tools = new ConcurrentHashMap<>();

	public Invocation start(String toolName) {
		return new Invocation(toolName, System.nanoTime());
	}

	public void success(Invocation invocation) {
		record(invocation, true);
	}

	public void failure(Invocation invocation) {
		record(invocation, false);
	}

	private void record(Invocation invocation, boolean success) {
		MutableMetric metric = tools.computeIfAbsent(invocation.toolName(), ignored -> new MutableMetric());
		metric.calls.increment();
		if (success) metric.successes.increment(); else metric.failures.increment();
		metric.totalDurationMs.add((System.nanoTime() - invocation.startedAtNanos()) / 1_000_000L);
	}

	public Map<String, ToolMetric> snapshot() {
		Map<String, ToolMetric> result = new LinkedHashMap<>();
		tools.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
			MutableMetric value = entry.getValue();
			long calls = value.calls.sum();
			result.put(entry.getKey(), new ToolMetric(calls, value.successes.sum(), value.failures.sum(),
					calls == 0 ? 0 : value.totalDurationMs.sum() / calls));
		});
		return result;
	}

	public SystemMetric systemSnapshot() {
		Runtime runtime = Runtime.getRuntime();
		return new SystemMetric(Instant.now(), ManagementFactory.getRuntimeMXBean().getUptime(),
				runtime.totalMemory() - runtime.freeMemory(), runtime.maxMemory(), tools.size());
	}

	public record Invocation(String toolName, long startedAtNanos) { }
	public record ToolMetric(long calls, long successes, long failures, long averageDurationMs) { }
	public record SystemMetric(Instant timestamp, long uptimeMs, long usedHeapBytes, long maxHeapBytes,
			int observedToolCount) { }

	private static final class MutableMetric {
		private final LongAdder calls = new LongAdder();
		private final LongAdder successes = new LongAdder();
		private final LongAdder failures = new LongAdder();
		private final LongAdder totalDurationMs = new LongAdder();
	}
}
