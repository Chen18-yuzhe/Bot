package com.example.demo.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

class AlertPushServiceTests {

	@SuppressWarnings("unchecked")
	private static ObjectProvider<StringRedisTemplate> noRedis() {
		ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(null);
		return provider;
	}

	private static StockAlertService.AlertEvent event(String id) throws Exception {
		return new StockAlertService.AlertEvent(id, "alert-1", "1398.500000", "1400.000000",
				"2026-07-26T02:30:00Z", new ObjectMapper().readTree("{}"));
	}

	private static StockAlertService.AlertRule rule() {
		return new StockAlertService.AlertRule("alert-1", "600519", "PRICE_BELOW", "LTE",
				"1400.000000", true, 60, "");
	}

	@Test
	void shouldPushAndReceiptPendingEvents() throws Exception {
		StockAlertService alertService = mock(StockAlertService.class);
		when(alertService.isConfigured()).thenReturn(true);
		when(alertService.pendingEvents()).thenReturn(List.of(event("event-1")));
		when(alertService.listAlerts()).thenReturn(List.of(rule()));
		when(alertService.markDelivered("event-1")).thenReturn(true);
		AlertPushService push = new AlertPushService(alertService, noRedis(), "default::user-1", "test:alert:");
		AtomicInteger sent = new AtomicInteger();
		push.setSender((sessionId, text) -> {
			assertEquals("default::user-1", sessionId);
			assertTrue(text.contains("600519"));
			assertTrue(text.contains("下破"));
			assertTrue(text.contains("1400"));
			sent.incrementAndGet();
			return true;
		});
		push.deliverPendingAlerts();

		assertEquals(1, sent.get());
		verify(alertService).markDelivered("event-1");
	}

	@Test
	void failedPushMustNotReceiptTheEvent() throws Exception {
		StockAlertService alertService = mock(StockAlertService.class);
		when(alertService.isConfigured()).thenReturn(true);
		when(alertService.pendingEvents()).thenReturn(List.of(event("event-1")));
		when(alertService.listAlerts()).thenReturn(List.of(rule()));
		AlertPushService push = new AlertPushService(alertService, noRedis(), "default::user-1", "test:alert:");
		push.setSender((sessionId, text) -> false);
		push.deliverPendingAlerts();

		verify(alertService, never()).markDelivered("event-1");
	}

	@Test
	void shouldStayIdleWithoutPushTarget() throws Exception {
		StockAlertService alertService = mock(StockAlertService.class);
		when(alertService.isConfigured()).thenReturn(true);
		AlertPushService push = new AlertPushService(alertService, noRedis(), "", "test:alert:");
		push.deliverPendingAlerts();

		verify(alertService, never()).pendingEvents();
	}

	@Test
	void renderShouldFallBackToEventDataWhenRuleIsGone() throws Exception {
		String text = AlertPushService.renderEvent(event("event-1"), null);
		assertTrue(text.contains("1400"));
		assertTrue(text.contains("1398.5"));
	}

	@Test
	void trimNumberShouldDropTrailingZeros() {
		assertEquals("1400", AlertPushService.trimNumber("1400.000000"));
		assertEquals("1398.5", AlertPushService.trimNumber("1398.500000"));
		assertEquals("0", AlertPushService.trimNumber("0.000000"));
		assertEquals("5", AlertPushService.trimNumber("5"));
	}
}
