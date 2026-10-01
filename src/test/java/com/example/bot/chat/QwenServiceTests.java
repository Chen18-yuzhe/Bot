package com.example.bot.chat;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.example.bot.model.MockChatModel;

class QwenServiceTests {

	@Test
	void shouldReplyThroughMockModelWithoutApiKey() {
		QwenService service = new QwenService(new MockChatModel());

		String answer = service.chat("你好");

		assertTrue(answer.contains("你好"));
	}
}
