package com.example.bot.model;

import java.util.List;

/** 一次模型对话中的单条消息，与具体供应商 SDK 无关。 */
public record ChatMessage(ChatRole role, String content, String toolCallId, String name,
		List<ToolCall> toolCalls) {

	public ChatMessage {
		toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
	}

	public static ChatMessage system(String text) {
		return new ChatMessage(ChatRole.SYSTEM, text, null, null, List.of());
	}

	public static ChatMessage user(String text) {
		return new ChatMessage(ChatRole.USER, text, null, null, List.of());
	}

	/** assistant 消息可同时携带文本与工具调用（工具调用为空表示给出最终回答）。 */
	public static ChatMessage assistant(String text, List<ToolCall> toolCalls) {
		return new ChatMessage(ChatRole.ASSISTANT, text, null, null, toolCalls);
	}

	/** tool 消息回传某个工具调用的执行结果。 */
	public static ChatMessage tool(String toolCallId, String name, String content) {
		return new ChatMessage(ChatRole.TOOL, content, toolCallId, name, List.of());
	}

	public boolean requestsTools() {
		return !toolCalls.isEmpty();
	}
}
