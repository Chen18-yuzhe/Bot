package com.example.demo.document;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DocumentReplyPolicyTests {
	@Test
	void normalSummaryShouldReplyDirectly() {
		assertFalse(DocumentReplyPolicy.shouldReturnFile("总结这个文件", "简短总结", 2_000));
	}

	@Test
	void explicitExportShouldReturnFile() {
		assertTrue(DocumentReplyPolicy.shouldReturnFile("请整理成 Word 文档", "简短总结", 2_000));
		assertTrue(DocumentReplyPolicy.shouldReturnFile("整理成文档给我", "简短总结", 2_000));
		assertTrue(DocumentReplyPolicy.shouldReturnFile("导出报告", "简短总结", 2_000));
	}

	@Test
	void longSummaryShouldReturnFile() {
		assertTrue(DocumentReplyPolicy.shouldReturnFile("总结一下", "内容超过限制", 3));
	}

	@Test
	void shouldRecognizeFollowUpForRecentlyUploadedFile() {
		assertTrue(DocumentReplyPolicy.referencesUploadedFile("分析"));
		assertTrue(DocumentReplyPolicy.referencesUploadedFile("总结一下"));
		assertTrue(DocumentReplyPolicy.referencesUploadedFile("翻译成英文"));
		assertTrue(DocumentReplyPolicy.referencesUploadedFile("提取重点"));
		assertTrue(DocumentReplyPolicy.referencesUploadedFile("整理成文件给我"));
		assertTrue(DocumentReplyPolicy.referencesUploadedFile("把刚才的 PDF 翻译一下"));
		assertTrue(DocumentReplyPolicy.referencesUploadedFile("加入我的知识库"));
		assertTrue(DocumentReplyPolicy.referencesUploadedFile("保存到知识库"));
		assertFalse(DocumentReplyPolicy.referencesUploadedFile("帮我写一段自我介绍"));
	}
}
