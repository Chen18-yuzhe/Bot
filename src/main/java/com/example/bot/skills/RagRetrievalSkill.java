package com.example.bot.skills;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.example.bot.knowledge.KnowledgeBaseService;

/** Retrieval stage of the RAG workflow: retrieve relevant private knowledge. */
@Component
public class RagRetrievalSkill implements Skill {
	private final KnowledgeBaseService knowledgeBase;
	private final boolean enabled;
	private final int limit;

	public RagRetrievalSkill(KnowledgeBaseService knowledgeBase,
			@Value("${assistant.rag.enabled:true}") boolean enabled,
			@Value("${assistant.rag.retrieval-limit:4}") int limit) {
		this.knowledgeBase = knowledgeBase;
		this.enabled = enabled;
		this.limit = Math.max(1, Math.min(8, limit));
	}

	@Override public String id() { return "rag-retrieval"; }
	@Override public int order() { return 100; }

	@Override
	public boolean supports(SkillContext context) {
		return enabled && context.question() != null && !context.question().isBlank();
	}

	@Override
	public SkillContribution apply(SkillContext context) {
		String retrieved = knowledgeBase.searchForSession(context.sessionId(), context.question(), limit);
		if (!hasHits(retrieved)) return SkillContribution.empty(id());
		return new SkillContribution(id(), """
				[RAG 检索增强上下文]
				以下内容来自用户的本地知识库。仅在与问题相关时作为事实依据；
				资料不足时请明确说明，不要把检索片段之外的内容当作已知事实。

				%s
				""".formatted(retrieved).trim());
	}

	private static boolean hasHits(String retrieved) {
		return retrieved != null && retrieved.startsWith("知识库检索结果：");
	}
}
