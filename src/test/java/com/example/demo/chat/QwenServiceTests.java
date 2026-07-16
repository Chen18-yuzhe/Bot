package com.example.demo.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class QwenServiceTests {

	@Test
	void shouldGiveClearMessageWhenApiKeyIsMissing() {
		QwenService service = new QwenService("", "qwen3.7-plus", "");

		MissingApiKeyException exception = assertThrows(MissingApiKeyException.class,
				() -> service.chat("你好"));

		assertEquals("请先配置环境变量 DASHSCOPE_API_KEY", exception.getMessage());
	}
}
