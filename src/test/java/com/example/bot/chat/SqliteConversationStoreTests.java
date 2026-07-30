package com.example.bot.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

class SqliteConversationStoreTests {
	@TempDir Path tempDir;

	@Test
	void shouldInitializePersistLoadAndDeleteAccountConversations() throws Exception {
		Path database = tempDir.resolve("conversations.db");
		SqliteConversationStore store = new SqliteConversationStore(true, database.toString(), new ObjectMapper());
		long now = System.currentTimeMillis();
		store.upsert("account-a::user-1", "summary", List.of(new ConversationTurn("question", "answer")), now);
		store.upsert("account-b::user-1", "other", List.of(new ConversationTurn("q2", "a2")), now);

		assertTrue(Files.isRegularFile(database));
		assertEquals(2, store.loadActive(now - 1_000, 10).size());
		var accountA = store.listByAccount("account-a", 100);
		assertEquals(1, accountA.size());
		assertEquals("user-1", accountA.getFirst().userId());
		assertEquals("answer", accountA.getFirst().turns().getFirst().assistantMessage());
		assertTrue(store.listByAccount("account-a", 100, now + 1).isEmpty());
		assertTrue(store.updateSummary("account-a::user-1", "updated summary"));
		assertEquals("updated summary", store.listByAccount("account-a", 100).getFirst().summary());

		assertEquals(1, store.deleteByAccount("account-a"));
		assertTrue(store.listByAccount("account-a", 100).isEmpty());
		assertEquals(1, store.loadActive(now - 1_000, 10).size());
	}
}
