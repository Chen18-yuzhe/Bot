package com.example.demo.knowledge;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KnowledgeBaseServiceTests {
	@TempDir Path directory;

	@Test
	void shouldFindRelevantPersonalKnowledge() throws Exception {
		Files.writeString(directory.resolve("redis.md"), "对话记忆使用 Redis，采用二十四小时滑动过期。每次访问都会续期。");
		Files.writeString(directory.resolve("image.md"), "图片编辑使用万相模型。");
		KnowledgeBaseService service = new KnowledgeBaseService(directory.toString());

		String result = service.search("Redis 对话多久过期", 3);

		assertTrue(result.contains("redis.md"));
		assertTrue(result.contains("二十四小时"));
	}

	@Test
	void shouldAddUploadedKnowledgeSafely() throws Exception {
		KnowledgeBaseService service = new KnowledgeBaseService(directory.toString());
		Path added = service.add("../项目说明.md", "Function Calling 工具开发规范");

		assertTrue(added.startsWith(directory));
		assertTrue(Files.readString(added).contains("工具开发规范"));
	}

	@Test
	void shouldStoreExtractedBinaryDocumentAsSearchableTextWithoutOverwrite() throws Exception {
		KnowledgeBaseService service = new KnowledgeBaseService(directory.toString());
		Path first = service.add("需求.pdf", "第一版需求包含联网搜索");
		Path second = service.add("需求.pdf", "第二版需求包含提醒事项");

		assertTrue(first.getFileName().toString().endsWith(".pdf.txt"));
		assertTrue(second.getFileName().toString().endsWith(".txt"));
		assertTrue(!first.equals(second));
		assertTrue(service.search("提醒事项", 3).contains("第二版需求"));
	}
}
