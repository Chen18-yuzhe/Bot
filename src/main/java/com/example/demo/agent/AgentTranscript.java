package com.example.demo.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.alibaba.dashscope.common.MultiModalMessage;
import com.alibaba.dashscope.tools.ToolCallBase;
import com.alibaba.dashscope.tools.ToolCallFunction;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Agent 循环的工作对话记录。与 SDK 消息解耦持久化，
 * 任务挂起 / 恢复 / 服务重启后都能重建模型上下文，保证断点续跑不丢进度。
 */
public final class AgentTranscript {

	private AgentTranscript() {
	}

	/** 一条工具调用记录（assistant 消息携带）。 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ToolCallRecord(String id, String name, String argumentsJson) {
	}

	/**
	 * 一条对话记录。role ∈ user / assistant / tool；
	 * tool 消息带 toolCallId 与 name；assistant 消息可带 toolCalls。
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Entry(String role, String text, String name, String toolCallId,
			List<ToolCallRecord> toolCalls) {

		public static Entry user(String text) {
			return new Entry("user", text, null, null, List.of());
		}

		public static Entry assistant(String text, List<ToolCallRecord> toolCalls) {
			return new Entry("assistant", text, null, null,
					toolCalls == null ? List.of() : List.copyOf(toolCalls));
		}

		public static Entry tool(String toolCallId, String name, String content) {
			return new Entry("tool", content, name, toolCallId, List.of());
		}
	}

	/** 把持久化的对话记录重建为 DashScope SDK 消息列表。 */
	public static List<Object> toSdkMessages(List<Entry> entries) {
		List<Object> messages = new ArrayList<>();
		for (Entry entry : entries) {
			switch (entry.role()) {
				case "user" -> messages.add(MultiModalMessage.builder().role("user")
						.content(List.of(Map.of("text", entry.text() == null ? "" : entry.text()))).build());
				case "assistant" -> {
					var builder = MultiModalMessage.builder().role("assistant");
					/*
					 * DashScope's next-turn serializer iterates assistant.content even
					 * when the assistant turn contains only tool calls.  Persisted tool
					 * turns therefore must be reconstructed with an empty text part;
					 * omitting content causes an SDK-side NullPointerException on the
					 * following reasoning step.
					 */
					builder.content(List.of(Map.of("text", entry.text() == null ? "" : entry.text())));
					if (!entry.toolCalls().isEmpty()) {
						List<ToolCallBase> calls = new ArrayList<>();
						for (ToolCallRecord record : entry.toolCalls()) calls.add(rebuildToolCall(record));
						builder.toolCalls(calls);
					}
					messages.add(builder.build());
				}
				case "tool" -> messages.add(MultiModalMessage.builder().role("tool")
						.toolCallId(entry.toolCallId()).name(entry.name())
						.content(List.of(Map.of("text", entry.text() == null ? "" : entry.text()))).build());
				default -> throw new IllegalArgumentException("未知的对话记录角色：" + entry.role());
			}
		}
		return messages;
	}

	private static ToolCallFunction rebuildToolCall(ToolCallRecord record) {
		ToolCallFunction call = new ToolCallFunction();
		call.setId(record.id());
		ToolCallFunction.CallFunction function = call.new CallFunction();
		function.setName(record.name());
		function.setArguments(record.argumentsJson());
		call.setFunction(function);
		return call;
	}

	/**
	 * 上下文安全网：记录过长时把最旧的一半折叠为一条摘要，
	 * 只在 user / 纯文本 assistant 边界处裁剪，避免 tool 消息与调用脱节。
	 */
	static List<Entry> compact(List<Entry> entries, int maxEntries) {
		if (entries.size() <= maxEntries) return entries;
		int target = maxEntries / 2;
		int cut = target;
		while (cut < entries.size() && !isSafeBoundary(entries.get(cut))) cut++;
		if (cut >= entries.size()) return entries;
		StringBuilder folded = new StringBuilder("[早期步骤已折叠，共 ").append(cut).append(" 条] ");
		for (int index = 0; index < cut; index++) {
			Entry entry = entries.get(index);
			if (entry.text() == null) continue;
			String snippet = entry.text().replaceAll("\\s+", " ");
			folded.append(entry.role()).append(": ")
					.append(snippet.length() > 120 ? snippet.substring(0, 120) + "…" : snippet).append(" | ");
			if (folded.length() > 2000) break;
		}
		List<Entry> result = new ArrayList<>();
		result.add(Entry.user(folded.toString()));
		result.addAll(entries.subList(cut, entries.size()));
		return result;
	}

	private static boolean isSafeBoundary(Entry entry) {
		return "user".equals(entry.role())
				|| ("assistant".equals(entry.role()) && entry.toolCalls().isEmpty());
	}
}
