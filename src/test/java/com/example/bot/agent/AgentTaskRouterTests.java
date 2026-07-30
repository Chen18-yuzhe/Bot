package com.example.bot.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import com.example.bot.chat.ConversationTurn;

class AgentTaskRouterTests {
	private final AgentTaskRouter router = new AgentTaskRouter(List.of(
			new GenericTaskType(), new DecisionTaskType(), new LearningCoachTaskType(),
			new DeepResearchTaskType(), new TravelTaskType()));

	@Test
	void discoversAndRoutesSpecializedTaskTypesWithoutCentralBranches() {
		assertEquals(TravelTaskType.NAME, router.route("做一份杭州三日旅游攻略", List.of()).name());
		assertEquals(DeepResearchTaskType.NAME, router.route("调研国内 AI 编程助手并输出行业报告", List.of()).name());
		assertEquals(LearningCoachTaskType.NAME, router.route("给我一份六周 Spring Boot 学习计划", List.of()).name());
		assertEquals(DecisionTaskType.NAME, router.route("帮我做 PostgreSQL 和 MySQL 技术选型", List.of()).name());
		assertEquals(GenericTaskType.NAME, router.route("帮我算一道题", List.of()).name());
	}

	@Test
	void recentContextSupportsFollowUpsAndOnlyExplicitTravelLinksAutoStart() {
		List<ConversationTurn> history = List.of(
				new ConversationTurn("帮我做杭州攻略", "这里有三个方案"));
		assertEquals(TravelTaskType.NAME, router.route("第二个呢", history).name());
		assertTrue(router.shouldAutoStart("给我故宫门票 H5 链接", List.of()));
		assertFalse(router.shouldAutoStart("做一份 AI 行业研究报告", List.of()));
	}

	@ParameterizedTest(name = "{0} -> {1}")
	@CsvFileSource(resources = "/agent-routing-evals.csv", numLinesToSkip = 1)
	void routesOfflineEvaluationSet(String request, String expectedType) {
		assertEquals(expectedType, router.route(request, List.of()).name());
	}
}
