package com.example.demo.chat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
public class ImageGenerationIntentParser {

	private static final Pattern MARKER = Pattern.compile("\\[IMAGE:(.{1,5000})\\]", Pattern.DOTALL);

	public ImageGenerationIntent parse(String qwenAnswer) {
		if (qwenAnswer == null || qwenAnswer.isBlank()) {
			return ImageGenerationIntent.none();
		}
		Matcher matcher = MARKER.matcher(qwenAnswer);
		if (!matcher.find() || matcher.group(1).isBlank()) {
			return ImageGenerationIntent.none();
		}
		return new ImageGenerationIntent(true, matcher.group(1).trim());
	}
}
