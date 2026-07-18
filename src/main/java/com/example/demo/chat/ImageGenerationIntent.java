package com.example.demo.chat;

public record ImageGenerationIntent(boolean requested, String prompt) {
	public static ImageGenerationIntent none() {
		return new ImageGenerationIntent(false, null);
	}
}
