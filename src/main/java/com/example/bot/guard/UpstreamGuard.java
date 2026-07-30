package com.example.bot.guard;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.example.bot.ApplicationException;

/**
 * Circuit breaker for metered upstreams (DashScope image generation/editing
 * and web search). After N consecutive failures the circuit opens and calls
 * fail fast for the cooldown period — a broken paid upstream must not keep
 * burning quota and threads on requests that will fail anyway. After the
 * cooldown, one probe call is let through (half-open); its outcome decides
 * whether the circuit closes again.
 */
@Service
public class UpstreamGuard {
	private static final Logger log = LoggerFactory.getLogger(UpstreamGuard.class);

	private final int failureThreshold;
	private final long openMillis;
	private final Map<String, CircuitState> circuits = new ConcurrentHashMap<>();

	public UpstreamGuard(@Value("${assistant.circuit-breaker.failure-threshold:5}") int failureThreshold,
			@Value("${assistant.circuit-breaker.open-seconds:60}") long openSeconds) {
		this.failureThreshold = Math.max(1, failureThreshold);
		this.openMillis = Math.max(0, openSeconds) * 1000L;
	}

	/**
	 * Throws when the named circuit is open. Call before the upstream request,
	 * then report the outcome via recordSuccess/recordFailure.
	 */
	public void ensureAvailable(String upstream) {
		CircuitState state = circuits.computeIfAbsent(upstream, ignored -> new CircuitState());
		synchronized (state) {
			if (state.openUntilMs == 0) return;
			long now = System.currentTimeMillis();
			if (now >= state.openUntilMs && !state.probing) {
				// Half-open: exactly one caller probes; the rest keep failing fast.
				state.probing = true;
				log.info("熔断进入半开状态，放行一次探测请求：上游={}", upstream);
				return;
			}
			throw new ApplicationException(HttpStatus.SERVICE_UNAVAILABLE,
					"该能力暂时熔断保护中，请约 " + Math.max(1, (state.openUntilMs - now) / 1000) + " 秒后再试");
		}
	}

	public void recordSuccess(String upstream) {
		CircuitState state = circuits.get(upstream);
		if (state == null) return;
		synchronized (state) {
			if (state.openUntilMs > 0) log.info("熔断恢复关闭：上游={}", upstream);
			state.consecutiveFailures = 0;
			state.openUntilMs = 0;
			state.probing = false;
		}
	}

	public void recordFailure(String upstream) {
		CircuitState state = circuits.computeIfAbsent(upstream, ignored -> new CircuitState());
		synchronized (state) {
			state.consecutiveFailures++;
			if (state.probing || state.consecutiveFailures >= failureThreshold) {
				state.openUntilMs = System.currentTimeMillis() + openMillis;
				state.probing = false;
				log.warn("熔断开启：上游={}，连续失败={}，{} 秒后允许探测",
						upstream, state.consecutiveFailures, openMillis / 1000);
			}
		}
	}

	/** Convenience wrapper: guard + outcome bookkeeping around one upstream call. */
	public <T> T call(String upstream, java.util.function.Supplier<T> action) {
		ensureAvailable(upstream);
		try {
			T result = action.get();
			recordSuccess(upstream);
			return result;
		} catch (RuntimeException exception) {
			// Bad user input (invalid args, content-audit rejections) is not an
			// upstream failure and must not trip the circuit.
			boolean clientFault = exception instanceof IllegalArgumentException
					|| (exception instanceof ApplicationException applicationException
							&& applicationException.getStatus().is4xxClientError());
			if (!clientFault) recordFailure(upstream);
			throw exception;
		}
	}

	private static final class CircuitState {
		private int consecutiveFailures;
		private long openUntilMs;
		private boolean probing;
	}
}
