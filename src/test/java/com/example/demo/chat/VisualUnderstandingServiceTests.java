package com.example.demo.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class VisualUnderstandingServiceTests {

	@Test
	void shouldBuildImageAndVideoContentForDashScope() {
		List<MediaContent> media = List.of(
				new MediaContent(MediaContent.MediaType.IMAGE, new byte[] { 1, 2, 3 }, "image/png"),
				new MediaContent(MediaContent.MediaType.VIDEO, new byte[] { 4, 5, 6 }, "video/mp4"));

		List<Map<String, Object>> content = VisualUnderstandingService.buildContent(media, 2.0f);

		assertEquals("data:image/png;base64,AQID", content.get(0).get("image"));
		assertEquals("data:video/mp4;base64,BAUG", content.get(1).get("video"));
		assertEquals(2.0f, content.get(1).get("fps"));
		assertTrue(content.get(2).get("text").toString().contains("不要使用“这是一张图片"));
	}
}
