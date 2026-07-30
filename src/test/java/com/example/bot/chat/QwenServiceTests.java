package com.example.bot.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.example.bot.ApplicationException;

class QwenServiceTests {

	@Test
	void shouldGiveClearMessageWhenApiKeyIsMissing() {
		QwenService service = new QwenService("", "qwen3.7-plus", "");

		ApplicationException exception = assertThrows(ApplicationException.class,
				() -> service.chat("你好"));

		assertEquals("请先配置环境变量 DASHSCOPE_API_KEY", exception.getMessage());
	}
}
