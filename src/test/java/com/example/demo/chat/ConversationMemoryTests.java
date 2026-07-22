package com.example.demo.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class ConversationMemoryTests {
	@TempDir Path tempDir;

	@Test
	void shouldKeepUsersIsolatedAndLimitTurns() {
		ConversationMemory memory = new ConversationMemory(2, 30);

		memory.remember("user-a", "q1", "a1");
		memory.remember("user-a", "q2", "a2");
		memory.remember("user-a", "q3", "a3");
		memory.remember("user-b", "other", "answer");

		assertEquals(3, memory.history("user-a").size());
		assertTrue(memory.history("user-a").get(0).assistantMessage().contains("a1"));
		assertEquals("q2", memory.history("user-a").get(1).userMessage());
		assertEquals(1, memory.history("user-b").size());
	}

	@Test
	void shouldClearOneConversation() {
		ConversationMemory memory = new ConversationMemory(8, 30);
		memory.remember("user-a", "q", "a");

		memory.clear("user-a");

		assertTrue(memory.history("user-a").isEmpty());
	}

	@Test
	void shouldPersistAndRestoreBoundedConversation() {
		Path file = tempDir.resolve("conversations.json");
		ConversationMemory first = new ConversationMemory(2, 30, 10, 200, 6000, file.toString(), new ObjectMapper());
		first.remember("user-a", "q1", "a1");
		first.remember("user-a", "q2", "a2");
		first.close();

		ConversationMemory restored = new ConversationMemory(2, 30, 10, 200, 6000, file.toString(), new ObjectMapper());
		assertEquals(2, restored.history("user-a").size());
		restored.close();
	}

	@SuppressWarnings("unchecked")
	@Test
	void shouldStoreRedisConversationWithSlidingTtlAndHashedKey() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		ValueOperations<String, String> values = mock(ValueOperations.class);
		when(redis.opsForValue()).thenReturn(values);
		when(values.get(anyString())).thenReturn(null);
		ConversationMemory memory = new ConversationMemory(8, 1440, new ObjectMapper(), redis, "test:conversation:");

		memory.remember("wechat-user-secret", "你好", "你好呀");

		ArgumentCaptor<java.time.Duration> ttl = ArgumentCaptor.forClass(java.time.Duration.class);
		verify(values).set(org.mockito.ArgumentMatchers.startsWith("test:conversation:"),
				org.mockito.ArgumentMatchers.contains("你好呀"), ttl.capture());
		assertEquals(java.time.Duration.ofHours(24), ttl.getValue());
	}

	@SuppressWarnings("unchecked")
	@Test
	void shouldDeleteRedisConversationWhenCleared() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		ValueOperations<String, String> values = mock(ValueOperations.class);
		when(redis.opsForValue()).thenReturn(values);
		ConversationMemory memory = new ConversationMemory(8, 1440, new ObjectMapper(), redis, "test:conversation:");

		memory.clear("user-a");

		verify(redis).delete(org.mockito.ArgumentMatchers.startsWith("test:conversation:"));
	}
}
