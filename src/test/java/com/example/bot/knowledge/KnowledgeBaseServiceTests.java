package com.example.bot.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KnowledgeBaseServiceTests {
	@TempDir Path directory;

	@Test
	void upsertForSessionReplacesThePriorImportedDocument() throws Exception {
		KnowledgeBaseService service = new KnowledgeBaseService(directory.toString());

		service.upsertForSession("account-a::user-1", "bilibili-BV1234567890.md", "first");
		service.upsertForSession("account-a::user-1", "bilibili-BV1234567890.md", "second");

		assertThat(service.list("account-a")).hasSize(1);
		assertThat(Files.readString(directory.resolve("accounts/account-a/bilibili-BV1234567890.md")))
				.isEqualTo("second");
	}

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
