package com.example.bot.agent;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SpecializedTaskTypeTests {
	@Test
	void researchRejectsInventedLinksButAcceptsToolEvidence() {
		DeepResearchTaskType type = new DeepResearchTaskType();
		AgentTask task = new AgentTask();
		task.getTranscript().add(AgentTranscript.Entry.tool(
				"call-1", "search_web", "来源：https://example.org/report"));

		assertTrue(type.validateCompletion(task, "参考：https://made-up.example/report")
				.contains("未经工具结果验证"));
		assertNull(type.validateCompletion(task, "参考：https://example.org/report"));
		assertTrue(type.allowedTools().contains("search_knowledge_base"));
	}

	@Test
	void learningAndDecisionHaveOutcomeQualityGates() {
		LearningCoachTaskType learning = new LearningCoachTaskType();
		assertTrue(learning.validateCompletion(new AgentTask(), "这是一个资料列表").contains("学习目标"));
		assertNull(learning.validateCompletion(new AgentTask(),
				"学习目标：掌握基础。练习：完成项目。验收标准：通过测验。"));

		DecisionTaskType decision = new DecisionTaskType();
		assertTrue(decision.validateCompletion(new AgentTask(), "推荐 A").contains("评价标准"));
		assertNull(decision.validateCompletion(new AgentTask(),
				"评价标准与权重：成本 50%。推荐：选择 A。风险：迁移成本不确定。"));
	}
}
