package com.example.bot.chat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ImageReplyPolicyTests {
	@Test
	void shouldRecognizeImageFollowUpInstructions() {
		assertTrue(ImageReplyPolicy.referencesUploadedImage("描述一下"));
		assertTrue(ImageReplyPolicy.referencesUploadedImage("帮我分析这张图片"));
		assertTrue(ImageReplyPolicy.referencesUploadedImage("比较两张图有什么不同"));
		assertTrue(ImageReplyPolicy.referencesUploadedImage("把刚才的照片背景换掉"));
		assertTrue(ImageReplyPolicy.referencesUploadedImage("背景换成夜景"));
		assertTrue(ImageReplyPolicy.referencesUploadedImage("把人物去掉"));
		assertTrue(ImageReplyPolicy.referencesUploadedImage("变清晰一点"));
	}

	@Test
	void shouldNotConsumeUnrelatedConversation() {
		assertFalse(ImageReplyPolicy.referencesUploadedImage("帮我分析一下 Java 代码"));
		assertFalse(ImageReplyPolicy.referencesUploadedImage("给代码添加日志"));
		assertFalse(ImageReplyPolicy.referencesUploadedImage("今天北京天气怎么样"));
	}
}
