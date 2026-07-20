package com.example.demo.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import com.fasterxml.jackson.databind.ObjectMapper;

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
}
