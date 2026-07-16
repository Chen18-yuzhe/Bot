package com.example.demo.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ConversationMemoryTests {

	@Test
	void shouldKeepUsersIsolatedAndLimitTurns() {
		ConversationMemory memory = new ConversationMemory(2, 30);

		memory.remember("user-a", "q1", "a1");
		memory.remember("user-a", "q2", "a2");
		memory.remember("user-a", "q3", "a3");
		memory.remember("user-b", "other", "answer");

		assertEquals(2, memory.history("user-a").size());
		assertEquals("q2", memory.history("user-a").get(0).userMessage());
		assertEquals(1, memory.history("user-b").size());
	}

	@Test
	void shouldClearOneConversation() {
		ConversationMemory memory = new ConversationMemory(8, 30);
		memory.remember("user-a", "q", "a");

		memory.clear("user-a");

		assertTrue(memory.history("user-a").isEmpty());
	}
}
