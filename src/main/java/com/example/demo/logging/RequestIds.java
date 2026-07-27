package com.example.demo.logging;

import java.util.UUID;

/** requestId 生成与校验：HTTP 过滤器与 iLink 消息链路共用，贯穿全链路日志。 */
public final class RequestIds {
	public static final String MDC_KEY = "requestId";

	private RequestIds() { }

	public static String newId() {
		return UUID.randomUUID().toString().substring(0, 8);
	}

	/** 透传调用方的 X-Request-Id，但拒绝超长或含控制字符的值。 */
	public static String sanitizeOrNew(String provided) {
		if (provided == null || provided.isBlank()) return newId();
		String value = provided.trim();
		if (value.length() > 64 || !value.matches("[A-Za-z0-9_.-]+")) return newId();
		return value;
	}
}
