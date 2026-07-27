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
		assertEquals("query_time", FunctionCallingService.forcedTool("现在几点了", null, List.of()));
		assertEquals("query_time", FunctionCallingService.forcedTool("纽约当地时间是什么时间", null, List.of()));
		assertEquals("manage_reminder", FunctionCallingService.forcedTool("明天三点提醒我开会", null, List.of()));
		assertEquals("query_stock_data", FunctionCallingService.forcedTool("帮我分析 600519", null, List.of()));
	}

	@Test
	void priceConditionRemindersMustRouteToStockAlertNotReminder() {
		assertEquals("manage_stock_alert", FunctionCallingService.forcedTool("600519跌破1400提醒我", null, List.of()));
		assertEquals("manage_stock_alert", FunctionCallingService.forcedTool("茅台涨到1700通知我", null, List.of()));
		assertEquals("manage_stock_alert", FunctionCallingService.forcedTool("查看预警列表", null, List.of()));
		// 天气预警不是股价预警。
		assertEquals(null, FunctionCallingService.forcedTool("今天有没有暴雨预警", null, List.of()));
		// 普通定时提醒仍然走提醒工具。
		assertEquals("manage_reminder", FunctionCallingService.forcedTool("明天三点提醒我开会", null, List.of()));
	}

	@Test
	void briefingAndWatchlistRequestsRouteToTheirTools() {
		assertEquals("manage_briefing", FunctionCallingService.forcedTool("每天8点给我发早报", null, List.of()));
		assertEquals("manage_briefing", FunctionCallingService.forcedTool("退订每日简报", null, List.of()));
		assertEquals("manage_watchlist", FunctionCallingService.forcedTool("把600519加入自选", null, List.of()));
		assertEquals("manage_watchlist", FunctionCallingService.forcedTool("我的自选今天表现如何", null, List.of()));
	}

	@Test
	void shouldExtractDistinctStockCodesForDirectStockDataRequests() {
		assertEquals(List.of("600519", "000001.SZ"),
				FunctionCallingService.stockSymbols("对比 600519、000001.sz 和 600519 的走势"));
	}

	@Test
	void shouldResolveTheLatestStockCodeFromConversationContext() {
		List<ConversationTurn> history = List.of(
				new ConversationTurn("分析 600519", "已返回贵州茅台摘要"),
				new ConversationTurn("再看 000001.SZ", "已返回平安银行摘要"));
		assertTrue(FunctionCallingService.isStockIntent("值得买吗"));
		assertEquals(List.of("000001.SZ"), FunctionCallingService.latestStockSymbols(history));
	}

	@Test
	void shouldOnlyTreatExplicitStockRequestsAsStockQueries() {
		// Bare code or suffixed code or intent words → stock path.
		assertTrue(FunctionCallingService.isExplicitStockRequest("600519"));
		assertTrue(FunctionCallingService.isExplicitStockRequest("600519 000858"));
		assertTrue(FunctionCallingService.isExplicitStockRequest("000001.SZ 最近如何？帮我分析"));
		assertTrue(FunctionCallingService.isExplicitStockRequest("600519 走势怎么样"));
		// Verification codes, order ids and amounts must not be hijacked.
		assertFalse(FunctionCallingService.isExplicitStockRequest("验证码 123456 帮我记一下"));
		assertFalse(FunctionCallingService.isExplicitStockRequest("帮我算一下 123456 加 789"));
		assertFalse(FunctionCallingService.isExplicitStockRequest("快递单号 654321 到哪了"));
		assertFalse(FunctionCallingService.isExplicitStockRequest("普通问题没有数字"));
	}

	@Test
	void shouldNotForceStockToolForIncidentalSixDigitNumbers() {
		assertEquals(null, FunctionCallingService.forcedTool("订单 123456 状态查一下发货了吗", null, List.of()));
	}

	@Test
	void shouldBoundToolErrorMessages() {
		assertEquals("外部能力暂时不可用",
				FunctionCallingService.safeToolErrorMessage(new IllegalStateException()));
		assertEquals("参数错误",
				FunctionCallingService.safeToolErrorMessage(new IllegalArgumentException("参数错误")));
		assertTrue(FunctionCallingService.safeToolErrorMessage(
				new IllegalArgumentException("x".repeat(400))).length() <= 301);
	}
}
