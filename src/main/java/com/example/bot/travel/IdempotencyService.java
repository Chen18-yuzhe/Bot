package com.example.bot.travel;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.example.bot.ApplicationException;

@Service
public class IdempotencyService {
	private final Map<String, Entry> entries = new ConcurrentHashMap<>();

	public <T> T execute(String scope, String userId, String key, String fingerprint, Supplier<T> operation) {
		if (key == null || key.isBlank()) {
			throw new ApplicationException(HttpStatus.BAD_REQUEST, "缺少 Idempotency-Key 请求头");
		}
		if (key.length() > 128) throw new ApplicationException(HttpStatus.BAD_REQUEST, "Idempotency-Key 不能超过 128 个字符");
		String storageKey = scope + ":" + userId + ":" + key.trim();
		Entry existing = entries.get(storageKey);
		if (existing != null) {
			if (!existing.fingerprint().equals(fingerprint)) {
				throw new ApplicationException(HttpStatus.CONFLICT, "同一 Idempotency-Key 不能用于不同请求");
			}
			@SuppressWarnings("unchecked") T cached = (T) existing.result();
			return cached;
		}
		synchronized (entries) {
			existing = entries.get(storageKey);
			if (existing != null) {
				if (!existing.fingerprint().equals(fingerprint)) throw new ApplicationException(HttpStatus.CONFLICT, "同一 Idempotency-Key 不能用于不同请求");
				@SuppressWarnings("unchecked") T cached = (T) existing.result();
				return cached;
			}
			T result = operation.get();
			entries.put(storageKey, new Entry(fingerprint, result));
			return result;
		}
	}

	private record Entry(String fingerprint, Object result) { }
}
