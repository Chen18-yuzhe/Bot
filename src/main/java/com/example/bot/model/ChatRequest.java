package com.example.bot.model;

import java.util.List;

/** 一次模型推理请求，与具体供应商 SDK 无关。 */
public record ChatRequest(List<ChatMessage> messages, List<ToolDefinition> tools, int maxTokens,
		ToolChoice toolChoice) {

	public ChatRequest {
		messages = List.copyOf(messages);
		tools = tools == null ? List.of() : List.copyOf(tools);
		toolChoice = toolChoice == null ? ToolChoice.auto() : toolChoice;
	}

	/**
	 * 工具选择策略。mode ∈ auto / none / forced：
	 * auto 由模型自行决定；none 禁止本轮调用工具；forced 强制调用指定工具。
	 */
	public record ToolChoice(String mode, String toolName) {

		public static ToolChoice auto() {
			return new ToolChoice("auto", null);
		}

		public static ToolChoice none() {
			return new ToolChoice("none", null);
		}

		public static ToolChoice forced(String toolName) {
			return new ToolChoice("forced", toolName);
		}
	}
}
