package com.example.demo.guard;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

class UserRateLimiterTests {

	@SuppressWarnings("unchecked")
	private static UserRateLimiter limiter(boolean enabled, int perMinute) {
		ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(null);
		return new UserRateLimiter(provider, enabled, perMinute, "test:ratelimit:");
	}

	@Test
	void shouldBlockBeyondPerMinuteCap() {
		UserRateLimiter limiter = limiter(true, 3);
		assertTrue(limiter.tryAcquire("default::user-1"));
		assertTrue(limiter.tryAcquire("default::user-1"));
		assertTrue(limiter.tryAcquire("default::user-1"));
		assertFalse(limiter.tryAcquire("default::user-1"));
		// 其他用户不受影响。
		assertTrue(limiter.tryAcquire("default::user-2"));
	}

	@Test
	void disabledLimiterAlwaysAllows() {
		UserRateLimiter limiter = limiter(false, 1);
		assertTrue(limiter.tryAcquire("default::user-1"));
		assertTrue(limiter.tryAcquire("default::user-1"));
	}
}
