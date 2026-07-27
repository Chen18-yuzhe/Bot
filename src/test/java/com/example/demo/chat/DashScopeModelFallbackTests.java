package com.example.demo.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.example.demo.ApplicationException;

class DashScopeModelFallbackTests {

	@Test
	void shouldUseNextModelAfterFreeQuotaIsExhausted() throws Exception {
		DashScopeModelFallback fallback = new DashScopeModelFallback("first", "second,third", Duration.ofMinutes(5));
		AtomicInteger calls = new AtomicInteger();

		String result = fallback.call("test", model -> {
			calls.incrementAndGet();
			if (model.equals("first")) throw new IllegalStateException("AllocationQuota.FreeTierOnly: Free quota exhausted");
			return model;
		});

		assertEquals("second", result);
		assertEquals(2, calls.get());
	}

	@Test
	void shouldNotRetryOtherModelsForNonQuotaFailure() {
		DashScopeModelFallback fallback = new DashScopeModelFallback("first", "second", Duration.ofMinutes(5));

		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
				() -> fallback.call("test", model -> { throw new IllegalArgumentException("请求参数错误"); }));

		assertEquals("请求参数错误", exception.getMessage());
	}

	@Test
	void shouldReturnFriendlyMessageWhenEveryModelIsExhausted() {
		DashScopeModelFallback fallback = new DashScopeModelFallback("first", "second", Duration.ofMinutes(5));

		ApplicationException exception = assertThrows(ApplicationException.class,
				() -> fallback.call("test", model -> { throw new IllegalStateException("free quota exhausted"); }));

		assertTrue(exception.getMessage().contains("免费额度均已耗尽"));
	}

	@Test
	void shouldRecognizeOnlyQuotaExhaustionMessages() {
		assertTrue(DashScopeModelFallback.isQuotaExhausted(
				new IllegalStateException("AllocationQuota.FreeTierOnly")));
		assertFalse(DashScopeModelFallback.isQuotaExhausted(new IllegalStateException("网络超时")));
	}
}
