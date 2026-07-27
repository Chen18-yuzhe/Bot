package com.example.demo.tools;

import com.example.demo.audio.GeneratedAudio;
import com.example.demo.chat.GeneratedImage;
import com.example.demo.document.GeneratedDocument;

public record ToolResult(String content, GeneratedImage image, GeneratedDocument document, GeneratedAudio audio,
		Outcome outcome) {
	public enum Outcome { SUCCESS, NO_RESULT }

	public static ToolResult text(String content) {
		return new ToolResult(content, null, null, null, Outcome.SUCCESS);
	}

	public static ToolResult noResult(String content) {
		return new ToolResult(content, null, null, null, Outcome.NO_RESULT);
	}

	public static ToolResult image(String content, GeneratedImage image) {
		return new ToolResult(content, image, null, null, Outcome.SUCCESS);
	}

	public static ToolResult document(String content, GeneratedDocument document) {
		return new ToolResult(content, null, document, null, Outcome.SUCCESS);
	}

	public static ToolResult audio(String content, GeneratedAudio audio) {
		return new ToolResult(content, null, null, audio, Outcome.SUCCESS);
	}

	public boolean useful() {
		return outcome == Outcome.SUCCESS && ((content != null && !content.isBlank())
				|| image != null || document != null || audio != null);
	}
}
