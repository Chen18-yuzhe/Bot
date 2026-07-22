package com.example.demo.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.demo.chat.MediaContent;

class ToolContextTests {
	@Test
	void shouldKeepAttachmentBytesOutsideJsonArgumentsAndGroupByType() {
		ToolContext context = new ToolContext("user", "处理附件", List.of(
				new MediaContent(MediaContent.MediaType.IMAGE, new byte[] { 1 }, "image/png"),
				new MediaContent(MediaContent.MediaType.DOCUMENT, new byte[] { 2 }, "text/plain", "a.txt"),
				new MediaContent(MediaContent.MediaType.AUDIO, new byte[] { 3 }, "audio/wav", "a.wav")));

		assertEquals(1, context.images().size());
		assertEquals(1, context.documents().size());
		assertEquals(1, context.audio().size());
	}
}
