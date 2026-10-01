package com.example.bot.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.bot.ApplicationException;
import com.example.bot.chat.DashScopeModelFallback;

class DashScopeChatModelTests {

	@Test
	void shouldFailClearlyWhenApiKeyIsMissing() {
		DashScopeChatModel model = new DashScopeChatModel("", "",
				new DashScopeModelFallback("qwen3.7-plus", "", 300));

		ApplicationException exception = assertThrows(ApplicationException.class, () -> model.chat(
				new ChatRequest(List.of(ChatMessage.user("你好")), List.of(), 100, ChatRequest.ToolChoice.none())));

		assertEquals("请先配置环境变量 DASHSCOPE_API_KEY", exception.getMessage());
	}

	@Test
	void shouldRejectForcedToolThatIsNotRegistered() {
		DashScopeChatModel model = new DashScopeChatModel("test-key", "",
				new DashScopeModelFallback("qwen3.7-plus", "", 300));
		List<ToolDefinition> tools = List.of(new ToolDefinition("query_weather", "查询天气", "{}"));

		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> model.chat(
				new ChatRequest(List.of(ChatMessage.user("你好")), tools, 100,
						ChatRequest.ToolChoice.forced("missing_tool"))));

		assertTrue(exception.getMessage().contains("missing_tool"));
	}
}
