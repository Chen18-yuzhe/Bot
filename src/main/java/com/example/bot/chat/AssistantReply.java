package com.example.bot.chat;

import com.example.bot.audio.GeneratedAudio;
import com.example.bot.document.GeneratedDocument;

public record AssistantReply(String text, GeneratedImage image, GeneratedDocument document, GeneratedAudio audio) {
	public static AssistantReply text(String text) {
		return new AssistantReply(text, null, null, null);
	}

	public static AssistantReply image(String text, GeneratedImage image) {
		return new AssistantReply(text, image, null, null);
	}

	public static AssistantReply document(String text, GeneratedDocument document) {
		return new AssistantReply(text, null, document, null);
	}

	public static AssistantReply audio(String text, GeneratedAudio audio) {
		return new AssistantReply(text, null, null, audio);
	}
}
