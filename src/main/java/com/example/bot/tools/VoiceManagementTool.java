package com.example.bot.tools;

import org.springframework.stereotype.Component;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.example.bot.audio.VoicePreferenceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.gson.JsonParser;

@Component
public class VoiceManagementTool implements AssistantTool {
	private static final FunctionDefinition DEFINITION = FunctionDefinition.builder()
			.name("manage_voice")
			.description("查看可用音色、查看当前音色或修改当前用户的语音合成音色。")
			.parameters(JsonParser.parseString("""
					{"type":"object","properties":{"action":{"type":"string","enum":["list","current","set"]},"voice":{"type":"string","description":"set 时的音色名称或 ID"}},"required":["action"],"additionalProperties":false}
					""").getAsJsonObject()).build();
	private final VoicePreferenceService service;

	public VoiceManagementTool(VoicePreferenceService service) { this.service = service; }
	@Override public FunctionDefinition definition() { return DEFINITION; }
	@Override public ToolResult execute(JsonNode arguments, ToolContext context) {
		return switch (arguments.path("action").asText("")) {
			case "list" -> ToolResult.text(service.describeAll());
			case "current" -> {
				var current = service.current(context.sessionId());
				yield ToolResult.text("当前音色：" + current.name() + "（" + current.id() + "）");
			}
			case "set" -> {
				String requested = arguments.path("voice").asText("").trim();
				if (requested.isBlank()) throw new IllegalArgumentException("缺少必填参数：voice");
				var selected = service.select(context.sessionId(), requested);
				if (selected == null) throw new IllegalArgumentException("没有找到该音色");
				yield ToolResult.text("音色已切换为：" + selected.name() + "（" + selected.id() + "）");
			}
			default -> throw new IllegalArgumentException("action 只能是 list、current 或 set");
		};
	}
}
