package com.example.demo.chat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class FunctionCallingServiceTests {
	@Test
	void shouldForceImageToolForExplicitGenerationRequest() {
		assertTrue(FunctionCallingService.requiresImageGeneration("生成一张小狗的图片", null));
		assertTrue(FunctionCallingService.requiresImageGeneration("帮我设计一个蓝色图标", null));
	}

	@Test
	void shouldNotForceImageToolWhenOnlyDiscussingImages() {
		assertFalse(FunctionCallingService.requiresImageGeneration("这张图片好看吗", null));
		assertFalse(FunctionCallingService.requiresImageGeneration("怎么理解图像识别技术", null));
	}

	@Test
	void shouldForceImageEditToolForReferenceImageEdit() {
		List<MediaContent> media = List.of(new MediaContent(MediaContent.MediaType.IMAGE, new byte[] { 1 }, "image/png"));
		assertTrue(FunctionCallingService.requiresImageEditing("把背景换成雪山", media));
		assertFalse(FunctionCallingService.requiresImageGeneration("把背景换成雪山", "本轮图片事实"));
		assertFalse(FunctionCallingService.requiresImageGeneration("比较这两张图", "本轮图片事实"));
	}

	@Test
	void shouldRouteAttachmentsAndPersonalActionsToTools() {
		List<MediaContent> document = List.of(new MediaContent(MediaContent.MediaType.DOCUMENT,
				new byte[] { 1 }, "text/plain", "notes.txt"));
		List<MediaContent> audio = List.of(new MediaContent(MediaContent.MediaType.AUDIO,
				new byte[] { 1 }, "audio/wav", "voice.wav"));
		assertEquals("process_document", FunctionCallingService.forcedTool("总结这个文件", null, document));
		assertEquals("add_to_knowledge_base", FunctionCallingService.forcedTool("把文件加入知识库", null, document));
		assertEquals("transcribe_audio", FunctionCallingService.forcedTool("识别音频", null, audio));
		assertEquals("manage_reminder", FunctionCallingService.forcedTool("明天三点提醒我开会", null, List.of()));
		assertEquals("synthesize_speech", FunctionCallingService.forcedTool("语音合成：你好", null, List.of()));
	}
}
