package com.example.bot.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.bot.model.ChatMessage;

class AgentTranscriptTests {

	@Test
	void shouldKeepEmptyContentForAssistantToolOnlyTurn() {
		var entry = AgentTranscript.Entry.assistant(null,
				List.of(new AgentTranscript.ToolCallRecord("call-1", "query_weather", "{\"city\":\"北京\"}")));

		List<ChatMessage> messages = AgentTranscript.toChatMessages(List.of(entry));
		ChatMessage message = messages.getFirst();

		assertNotNull(message.content());
		assertTrue(message.requestsTools());
		assertEquals("call-1", message.toolCalls().getFirst().id());
		assertEquals("query_weather", message.toolCalls().getFirst().name());
	}
}
