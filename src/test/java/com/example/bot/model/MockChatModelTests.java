package com.example.bot.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class MockChatModelTests {

	@Test
	void shouldEchoLastUserMessageWithoutNetwork() {
		MockChatModel model = new MockChatModel();
		ChatResponse response = model.chat(new ChatRequest(
				List.of(ChatMessage.system("系统提示"), ChatMessage.user("帮我查一下天气")), List.of(), 200,
				ChatRequest.ToolChoice.auto()));

		assertEquals("mock", model.name());
		assertTrue(response.text().contains("帮我查一下天气"));
		assertTrue(response.toolCalls().isEmpty());
	}

	@Test
	void shouldHandleEmptyMessages() {
		MockChatModel model = new MockChatModel();
		ChatResponse response = model.chat(new ChatRequest(List.of(), List.of(), 200, ChatRequest.ToolChoice.auto()));

		assertTrue(response.text().contains("我收到了你的消息"));
	}
}
