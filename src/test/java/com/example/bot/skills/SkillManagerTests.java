package com.example.bot.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.example.bot.knowledge.KnowledgeBaseService;

class SkillManagerTests {
	@TempDir Path directory;

	@Test
	void shouldRunDiscoveredSkillsInOrderWithoutCentralRouting() {
		Skill later = skill("later", 20, "second");
		Skill first = skill("first", 10, "first");

		SkillManager manager = new SkillManager(List.of(later, first));

		assertEquals("first\n\nsecond", manager.enrich(context("question")));
		assertEquals(List.of("first", "later"), manager.registeredSkillIds());
	}

	@Test
	void shouldRejectDuplicateSkillIds() {
		assertThrows(IllegalStateException.class,
				() -> new SkillManager(List.of(skill("same", 1, "a"), skill("same", 2, "b"))));
	}

	@Test
	void ragSkillShouldInjectOnlyRelevantKnowledge() throws Exception {
		KnowledgeBaseService knowledgeBase = new KnowledgeBaseService(directory.toString());
		Path accountDirectory = knowledgeBase.accountRoot("user-1");
		Files.createDirectories(accountDirectory);
		Files.writeString(accountDirectory.resolve("architecture.md"), "会话记忆使用 Redis，并设置二十四小时过期。");
		SkillManager manager = new SkillManager(List.of(new RagRetrievalSkill(knowledgeBase, true, 4)));

		String result = manager.enrich(context("user-1::chat", "Redis 会话记忆多久过期"));

		assertTrue(result.contains("[RAG 检索增强上下文]"));
		assertTrue(result.contains("architecture.md"));
		assertEquals("", manager.enrich(context("user-1::chat", "量子物理")));
	}

	private static SkillContext context(String question) {
		return context("user", question);
	}

	private static SkillContext context(String sessionId, String question) {
		return new SkillContext(sessionId, question, List.of(), List.of());
	}

	private static Skill skill(String id, int order, String contribution) {
		return new Skill() {
			@Override public String id() { return id; }
			@Override public int order() { return order; }
			@Override public SkillContribution apply(SkillContext context) {
				return new SkillContribution(id, contribution);
			}
		};
	}
}
