package com.example.demo.guard;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.example.demo.ApplicationException;

class UpstreamGuardTests {

	@Test
	void shouldOpenAfterConsecutiveFailuresAndFailFast() {
		UpstreamGuard guard = new UpstreamGuard(2, 60);
		AtomicInteger attempts = new AtomicInteger();
		for (int i = 0; i < 2; i++) {
			assertThrows(ApplicationException.class, () -> guard.call("image-generation", () -> {
				attempts.incrementAndGet();
				throw new ApplicationException(HttpStatus.BAD_GATEWAY, "上游超时");
			}));
		}
		// 第三次调用被熔断拦截，动作本身不再执行。
		assertThrows(ApplicationException.class,
				() -> guard.call("image-generation", attempts::incrementAndGet));
		assertEquals(2, attempts.get());
	}

	@Test
	void circuitsAreIsolatedPerUpstream() {
		UpstreamGuard guard = new UpstreamGuard(1, 60);
		assertThrows(ApplicationException.class, () -> guard.call("web-search", () -> {
			throw new ApplicationException(HttpStatus.BAD_GATEWAY, "boom");
		}));
		assertDoesNotThrow(() -> guard.ensureAvailable("image-generation"));
		assertThrows(ApplicationException.class, () -> guard.ensureAvailable("web-search"));
	}

	@Test
	void clientFaultsMustNotTripTheCircuit() {
		UpstreamGuard guard = new UpstreamGuard(1, 60);
		assertThrows(IllegalArgumentException.class, () -> guard.call("image-generation", () -> {
			throw new IllegalArgumentException("提示词不合法");
		}));
		assertThrows(ApplicationException.class, () -> guard.call("image-generation", () -> {
			throw new ApplicationException(HttpStatus.BAD_REQUEST, "触发内容审核");
		}));
		assertDoesNotThrow(() -> guard.ensureAvailable("image-generation"));
	}

	@Test
	void successfulProbeClosesTheCircuit() {
		UpstreamGuard guard = new UpstreamGuard(1, 0);
		assertThrows(ApplicationException.class, () -> guard.call("web-search", () -> {
			throw new ApplicationException(HttpStatus.BAD_GATEWAY, "boom");
		}));
		// open-seconds=0：立即进入半开，探测成功后恢复。
		assertEquals("ok", guard.call("web-search", () -> "ok"));
		assertDoesNotThrow(() -> guard.ensureAvailable("web-search"));
	}
}
