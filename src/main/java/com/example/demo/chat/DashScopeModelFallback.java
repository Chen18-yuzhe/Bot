package com.example.demo.chat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.example.demo.ApplicationException;

/**
 * Selects a compatible general-purpose model and skips models whose free quota
 * has already been exhausted. DashScope free quota is independent per model,
 * but DashScope does not perform this fallback automatically.
 */
@Component
public class DashScopeModelFallback {

	private static final Logger log = LoggerFactory.getLogger(DashScopeModelFallback.class);
	private static final Duration DEFAULT_COOLDOWN = Duration.ofMinutes(5);

	private final List<String> candidates;
	private final long cooldownNanos;
	private final Map<String, Long> quotaExhaustedUntil = new ConcurrentHashMap<>();

	@Autowired
	public DashScopeModelFallback(@Value("${aliyun.dashscope.model:qwen3.7-plus}") String primaryModel,
			@Value("${aliyun.dashscope.fallback-models:qwen3.7-plus-2026-05-26,qwen3.6-plus-2026-04-02,qwen3.6-flash-2026-04-16,qwen3.5-plus-2026-02-15,qwen3.5-flash}") String fallbackModels,
			@Value("${aliyun.dashscope.model-quota-cooldown-seconds:300}") long cooldownSeconds) {
		this(primaryModel, fallbackModels, Duration.ofSeconds(Math.max(1, cooldownSeconds)));
	}

	DashScopeModelFallback(String primaryModel, String fallbackModels, Duration cooldown) {
		LinkedHashSet<String> configured = new LinkedHashSet<>();
		add(configured, primaryModel);
		if (fallbackModels != null) {
			for (String model : fallbackModels.split(",")) add(configured, model);
		}
		if (configured.isEmpty()) throw new IllegalArgumentException("至少需要配置一个千问模型");
		this.candidates = List.copyOf(configured);
		this.cooldownNanos = cooldown.toNanos();
	}

	public <T> T call(String operation, ModelCall<T> request) throws Exception {
		Exception lastQuotaError = null;
		boolean attempted = false;
		for (String model : candidates) {
			if (isKnownExhausted(model)) continue;
			attempted = true;
			try {
				return request.call(model);
			} catch (Exception error) {
				if (!isQuotaExhausted(error)) throw error;
				lastQuotaError = error;
				quotaExhaustedUntil.put(model, System.nanoTime() + cooldownNanos);
				log.warn("模型免费额度不可用，切换候选模型：操作={}，模型={}", operation, model);
			}
		}
		if (!attempted) {
			log.warn("所有候选模型仍处于额度冷却期：操作={}，候选={}", operation, candidates);
		}
		throw new ApplicationException(HttpStatus.SERVICE_UNAVAILABLE,
				"当前可用模型的免费额度均已耗尽，请稍后再试或在百炼控制台查看各模型额度。", lastQuotaError);
	}

	List<String> candidates() {
		return candidates;
	}

	static boolean isQuotaExhausted(Throwable error) {
		for (Throwable current = error; current != null; current = current.getCause()) {
			String message = current.getMessage();
			if (message == null) continue;
			String value = message.toLowerCase(Locale.ROOT);
			if (value.contains("allocationquota.freetieronly")
					|| value.contains("free quota exhausted")
					|| value.contains("quota exhausted")
					|| value.contains("免费额度已用尽")) return true;
		}
		return false;
	}

	private boolean isKnownExhausted(String model) {
		Long until = quotaExhaustedUntil.get(model);
		if (until == null) return false;
		if (until > System.nanoTime()) return true;
		quotaExhaustedUntil.remove(model, until);
		return false;
	}

	private static void add(LinkedHashSet<String> models, String value) {
		if (value != null && !value.isBlank()) models.add(value.trim());
	}

	@FunctionalInterface
	public interface ModelCall<T> {
		T call(String model) throws Exception;
	}
}
