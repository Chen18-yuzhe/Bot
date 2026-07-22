package com.example.demo.tools;

import org.springframework.stereotype.Component;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.example.demo.audio.AudioService;
import com.example.demo.audio.GeneratedAudio;
import com.example.demo.audio.VoicePreferenceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.gson.JsonParser;

@Component
public class SpeechSynthesisTool implements AssistantTool {
	private static final FunctionDefinition DEFINITION = FunctionDefinition.builder()
			.name("synthesize_speech")
			.description("把用户指定的文字合成为 WAV 语音文件。用户只是讨论语音技术时不要调用。")
			.parameters(JsonParser.parseString("""
					{"type":"object","properties":{"text":{"type":"string","description":"需要朗读的完整文字"},"voice":{"type":"string","description":"可选音色名称或 ID，不填则使用个人当前音色"}},"required":["text"],"additionalProperties":false}
					""").getAsJsonObject()).build();
	private final AudioService audioService;
	private final VoicePreferenceService preferences;

	public SpeechSynthesisTool(AudioService audioService, VoicePreferenceService preferences) {
		this.audioService = audioService;
		this.preferences = preferences;
	}
	@Override public FunctionDefinition definition() { return DEFINITION; }
	@Override public ToolResult execute(JsonNode arguments, ToolContext context) {
		String text = arguments.path("text").asText("").trim();
		if (text.isBlank()) throw new IllegalArgumentException("缺少必填参数：text");
		String requestedVoice = arguments.path("voice").asText("").trim();
		var voice = requestedVoice.isBlank() ? preferences.current(context.sessionId())
				: preferences.select(context.sessionId(), requestedVoice);
		if (voice == null) throw new IllegalArgumentException("没有找到该音色，请先查看音色列表");
		byte[] bytes = audioService.synthesize(text, voice.id());
		return ToolResult.audio("语音已使用“" + voice.name() + "”音色生成。",
				new GeneratedAudio(bytes, "audio/wav", "speech.wav"));
	}
}
