package com.example.demo.chat;

public record AssistantReply(String text, GeneratedImage image) {
	public static AssistantReply text(String text) {
		return new AssistantReply(text, null);
	}

	public static AssistantReply image(String text, GeneratedImage image) {
		return new AssistantReply(text, image);
	}
}
