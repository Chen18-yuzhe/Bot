package com.example.bot.tools;

import java.nio.file.Files;

import org.springframework.stereotype.Component;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.example.bot.audio.AudioService;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.gson.JsonParser;

@Component
public class AudioTranscriptionTool implements AssistantTool {
	private static final FunctionDefinition DEFINITION = FunctionDefinition.builder()
			.name("transcribe_audio")
			.description("识别用户上传的音频文件并返回文字。必须有音频附件；不支持微信 SILK 语音气泡。")
			.parameters(JsonParser.parseString("""
					{"type":"object","properties":{"instruction":{"type":"string","description":"识别后的处理要求，例如仅转写或根据内容回答问题"}},"additionalProperties":false}
					""").getAsJsonObject()).build();
	private final AudioService service;

	public AudioTranscriptionTool(AudioService service) { this.service = service; }
	@Override public FunctionDefinition definition() { return DEFINITION; }
	@Override public ToolResult execute(JsonNode arguments, ToolContext context) {
		if (context.audio().isEmpty()) throw new IllegalArgumentException("请先上传音频文件");
		var audio = context.audio().get(0);
		if ("audio/silk".equals(audio.mimeType())) {
			throw new IllegalArgumentException("暂不支持微信语音气泡，请改发 WAV、MP3 等音频文件");
		}
		java.nio.file.Path temporary = null;
		try {
			temporary = Files.createTempFile("tool-audio-", extension(audio.mimeType()));
			Files.write(temporary, audio.data());
			String transcript = service.recognize(temporary.toFile());
			return transcript == null || transcript.isBlank()
					? ToolResult.noResult("音频中没有识别到有效文字。")
					: ToolResult.text("语音识别结果：\n" + transcript);
		} catch (java.io.IOException error) {
			throw new IllegalStateException("无法准备音频识别文件", error);
		} finally {
			if (temporary != null) try { Files.deleteIfExists(temporary); } catch (java.io.IOException ignored) { }
		}
	}

	private static String extension(String mimeType) {
		return switch (mimeType) {
			case "audio/wav" -> ".wav"; case "audio/mpeg" -> ".mp3"; case "audio/mp4" -> ".m4a";
			case "audio/flac" -> ".flac"; case "audio/ogg" -> ".ogg"; case "audio/opus" -> ".opus";
			case "audio/aac" -> ".aac"; case "audio/amr" -> ".amr"; default -> ".bin";
		};
	}
}
