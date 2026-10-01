package com.example.bot.model;

import java.util.List;

/** 一次模型推理响应，与具体供应商 SDK 无关。 */
public record ChatResponse(String text, List<ToolCall> toolCalls, int inputTokens, int outputTokens,
		String model) {

	public ChatResponse {
		toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
	}

	public int totalTokens() {
		return inputTokens + outputTokens;
	}

	public boolean requestsTools() {
		return !toolCalls.isEmpty();
	}
}
