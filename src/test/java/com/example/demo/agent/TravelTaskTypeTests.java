package com.example.demo.agent;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class TravelTaskTypeTests {

	@Test
	void reservesEnoughBudgetAndDefersTicketLookupUntilTheUserChooses() {
		TravelTaskType type = new TravelTaskType();

		assertTrue(type.tokenBudget(12_000) >= 500_000);
		assertTrue(type.maxSteps(12) >= 24);
		assertTrue(type.systemPrompt(new AgentTask()).contains("只调用一次 travel_discovery attractions、一次 query_weather 和一次 travel_plan"));
		assertTrue(type.systemPrompt(new AgentTask()).contains("最需要购票的 1 至 2 个景点"));
		assertTrue(type.systemPrompt(new AgentTask()).contains("依据与可信度"));
		assertTrue(type.systemPrompt(new AgentTask()).contains("只调用一次 travel_ticket_platform_entries"));
		assertTrue(!type.allowedTools().contains("travel_ticket_search_links"));
		assertTrue(!type.allowedTools().contains("travel_purchase_links"));
	}

	@Test
	void rejectsPlaceholderAndUnsourcedLinksOrFalseCheckoutClaims() {
		TravelTaskType type = new TravelTaskType();
		AgentTask task = new AgentTask();
		task.getTranscript().add(AgentTranscript.Entry.tool("call-1", "search_web",
				"官方来源：https://official.example.cn/ticket/123"));

		assertTrue(type.validateCompletion(task,
				"直达页：https://mp.weixin.qq.com/s/xxxxxx").contains("占位"));
		assertTrue(type.validateCompletion(task,
				"直达页：https://made-up.example.cn/ticket").contains("未由工具结果提供"));
		assertTrue(type.validateCompletion(task,
				"已为您生成官方购票清单。").contains("没有成功调用"));
		assertNull(type.validateCompletion(task,
				"已为您生成三种行程方案。选定后可以查询购票入口。"));
		assertNull(type.validateCompletion(task,
				"已移除所有示例链接，目前没有可验证的直达入口。"));
	}

	@Test
	void onlyAllowsPurchaseLinksThatPassedTheDetailPageVerifier() {
		TravelTaskType type = new TravelTaskType();
		AgentTask task = new AgentTask();
		task.setGoal("给我故宫的购票直达链接");
		task.getTranscript().add(AgentTranscript.Entry.tool("call-search", "search_web",
				"携程搜索结果：https://m.ctrip.com/webapp/you/search?keyword=故宫"));

		assertTrue(type.validateCompletion(task,
				"携程：https://m.ctrip.com/webapp/you/search?keyword=故宫")
				.contains("必须来自 travel_ticket_platform_entries"));

		task.getTranscript().add(AgentTranscript.Entry.tool("call-platforms", "travel_ticket_platform_entries",
				"""
				{"entries":[
				  {"platform":"美团","url":"https://i.meituan.com/awp/hfe/travel-merchant/index.html"},
				  {"platform":"携程","url":"https://piao.ctrip.com/dest/d-China-60979/s-tickets/"},
				  {"platform":"飞猪","url":"https://btrip.alitrip.com/piao/"}
				]}
				"""));
		assertNull(type.validateCompletion(task,
				"""
				美团：https://i.meituan.com/awp/hfe/travel-merchant/index.html
				携程：https://piao.ctrip.com/dest/d-China-60979/s-tickets/
				飞猪：https://btrip.alitrip.com/piao/
				暂时不能给到您具体的购票页面，麻烦您进入平台自主购票。
				"""));
	}
}
