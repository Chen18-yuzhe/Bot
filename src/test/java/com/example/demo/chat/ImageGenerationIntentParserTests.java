package com.example.demo.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ImageGenerationIntentParserTests {

	private final ImageGenerationIntentParser parser = new ImageGenerationIntentParser();

	@Test
	void shouldParseImageGenerationMarker() {
		ImageGenerationIntent intent = parser.parse("[IMAGE:一只水彩风格的橘猫]");

		assertTrue(intent.requested());
		assertEquals("一只水彩风格的橘猫", intent.prompt());
	}

	@Test
	void shouldIgnoreNormalImageDiscussion() {
		assertFalse(parser.parse("这张图片的构图很平衡。").requested());
	}
}
