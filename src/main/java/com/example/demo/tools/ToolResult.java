package com.example.demo.tools;

import com.example.demo.audio.GeneratedAudio;
import com.example.demo.chat.GeneratedImage;
import com.example.demo.document.GeneratedDocument;

public record ToolResult(String content, GeneratedImage image, GeneratedDocument document, GeneratedAudio audio) {
	public static ToolResult text(String content) {
		return new ToolResult(content, null, null, null);
	}

	public static ToolResult image(String content, GeneratedImage image) {
		return new ToolResult(content, image, null, null);
	}

	public static ToolResult document(String content, GeneratedDocument document) {
		return new ToolResult(content, null, document, null);
	}

	public static ToolResult audio(String content, GeneratedAudio audio) {
		return new ToolResult(content, null, null, audio);
	}
}
