package com.example.demo.reminder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.example.demo.reminder.ReminderService.Recurrence;
import com.fasterxml.jackson.databind.ObjectMapper;

class ReminderServiceTests {
	@Test
	void shouldCreateListAndCancelReminderWithoutRedis() {
		@SuppressWarnings("unchecked")
		ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(null);
		ReminderService service = new ReminderService(new ObjectMapper(), provider, true, "test:reminder:");
		var reminder = service.create("user-1", "提交日报", ZonedDateTime.now().plusHours(1), Recurrence.NONE);

		assertEquals(1, service.list("user-1").size());
		assertTrue(service.cancel("user-1", reminder.id()));
		assertTrue(service.list("user-1").isEmpty());
	}

	@Test
	void failedDeliveryShouldNotRetryOnEveryPoll() {
		@SuppressWarnings("unchecked")
		ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
		ReminderService service = new ReminderService(new ObjectMapper(), provider, true, "test:reminder:");
		var reminder = service.create("user-2", "喝水", ZonedDateTime.now().minusSeconds(1), Recurrence.NONE);
		service.setNotifier(item -> false);
		service.deliverDue();

		assertTrue(service.list("user-2").getFirst().nextAttemptAtMs() >= reminder.triggerAtMs() + 50_000L);
	}
}
