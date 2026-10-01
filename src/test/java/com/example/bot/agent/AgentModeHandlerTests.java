package com.example.bot.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import com.example.bot.ilink.UserNameService;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.example.bot.chat.ConversationMemory;
import com.example.bot.chat.ConversationTurn;

class AgentModeHandlerTests {
	@TempDir Path temporaryDirectory;

	@Test
	void healthPlanAutomaticallyIncludesPersistedHealthAndRoutineConstraints() {
		AgentLoopService loop = mock(AgentLoopService.class);
		AgentTask task = new AgentTask();
		task.setId("health-plan-task");
		when(loop.submit(eq("wx-user"), eq(HealthLifestylePlanTaskType.NAME), any(), any())).thenReturn(task);
		UserNameService profiles = new UserNameService(new ObjectMapper(),
				temporaryDirectory.resolve("credentials.json").toString());
		profiles.updateMemory("wx-user", "health.高血压", "用户明确自述有高血压");
		profiles.updateMemory("wx-user", "constraint.作息", "通常 00:30 入睡，07:00 起床，工作日久坐");
		AgentTaskRouter router = new AgentTaskRouter(java.util.List.of(
				new GenericTaskType(), new HealthLifestylePlanTaskType()));
		AgentModeHandler handler = new AgentModeHandler(loop, mock(ConversationMemory.class), router, profiles);

		handler.handle("wx-user", "帮我制定饮食和运动计划");

		verify(loop).submit(eq("wx-user"), eq(HealthLifestylePlanTaskType.NAME),
				org.mockito.ArgumentMatchers.argThat(goal -> goal.contains("health.高血压")
						&& goal.contains("constraint.作息")), any());
	}

	@Test
	void agentModeSubmitsPlainMessagesAsTasks() {
		AgentLoopService loop = mock(AgentLoopService.class);
		AgentTask task = new AgentTask();
		task.setId("12345678-1234-1234-1234-123456789012");
		when(loop.submit(eq("wx-user"), eq(TravelTaskType.NAME), eq("帮我比较三家酒店"), any())).thenReturn(task);
		AgentModeHandler handler = new AgentModeHandler(loop);

		assertTrue(handler.handle("wx-user", "/agent-mode").contains("Agent 模式"));
		String reply = handler.handle("wx-user", "帮我比较三家酒店");

		assertEquals("任务已受理，可在控制台「Agent 任务」查看进度。", reply);
		assertTrue(!reply.contains("#12345678"));
		verify(loop).submit(eq("wx-user"), eq(TravelTaskType.NAME), eq("帮我比较三家酒店"), any());
	}

	@Test
	void shortAgentCommandSwitchesModeAndNaturalTravelRequestUsesTravelAgent() {
		AgentLoopService loop = mock(AgentLoopService.class);
		AgentTask task = new AgentTask();
		task.setId("12345678-1234-1234-1234-123456789012");
		task.setDisplayNumber(1);
		when(loop.submit(eq("wx-user"), eq(TravelTaskType.NAME), any(), any())).thenReturn(task);
		AgentModeHandler handler = new AgentModeHandler(loop);

		assertTrue(handler.handle("wx-user", "/agent").contains("Agent 模式"));
		String reply = handler.handle("wx-user", "8月10日去杭州玩2天，2个人，预算2000元，给我几种方案");

		assertEquals("任务已受理，可在控制台「Agent 任务」查看进度。", reply);
		assertTrue(!reply.contains("任务 #"));
		verify(loop).submit(eq("wx-user"), eq(TravelTaskType.NAME), any(), any());
	}

	@Test
	void inlineAgentGoalUsesTravelRoutingAndEntersAgentMode() {
		AgentLoopService loop = mock(AgentLoopService.class);
		AgentTask task = new AgentTask();
		task.setId("12345678-1234-1234-1234-123456789012");
		when(loop.submit(eq("wx-user"), eq(TravelTaskType.NAME), any(), any())).thenReturn(task);
		AgentModeHandler handler = new AgentModeHandler(loop);

		String reply = handler.handle("wx-user", "/agent\n8月10日从上海去杭州玩2天，2个人，预算2000元，给我三种方案");

		assertEquals("任务已受理，可在控制台「Agent 任务」查看进度。", reply);
		assertEquals(AgentModeHandler.Mode.AGENT, handler.modeOf("wx-user"));
		verify(loop).submit(eq("wx-user"), eq(TravelTaskType.NAME), any(), any());
	}

	@Test
	void chatModeKeepsPlainMessagesOnChatPath() {
		AgentModeHandler handler = new AgentModeHandler(mock(AgentLoopService.class));
		assertNull(handler.handle("wx-user", "你好"));
		handler.handle("wx-user", "/agent-mode");
		handler.handle("wx-user", "/chat");
		assertEquals(AgentModeHandler.Mode.CHAT, handler.modeOf("wx-user"));
		assertNull(handler.handle("wx-user", "继续聊刚才的话题"));
	}

	@Test
	void bookingH5RequestUsesTravelAgentWithoutManualModeSwitch() {
		AgentLoopService loop = mock(AgentLoopService.class);
		AgentTask task = new AgentTask();
		task.setId("h5-link-task");
		when(loop.submit(eq("wx-user"), eq(TravelTaskType.NAME),
				eq("帮我找灵隐飞来峰门票 H5 链接"), any())).thenReturn(task);
		AgentModeHandler handler = new AgentModeHandler(loop);

		String reply = handler.handle("wx-user", "帮我找灵隐飞来峰门票 H5 链接");

		assertEquals("任务已受理，可在控制台「Agent 任务」查看进度。", reply);
		verify(loop).submit(eq("wx-user"), eq(TravelTaskType.NAME),
				eq("帮我找灵隐飞来峰门票 H5 链接"), any());
	}

	@Test
	void newStandaloneChatQuestionCancelsThePreviousActiveAgentTask() {
		AgentLoopService loop = mock(AgentLoopService.class);
		AgentTask active = new AgentTask();
		active.setId("old-active-task");
		active.setSessionId("wx-user");
		active.setStatus(AgentTaskStatus.RUNNING);
		when(loop.list("wx-user")).thenReturn(java.util.List.of(active));
		AgentModeHandler handler = new AgentModeHandler(loop);

		assertNull(handler.handle("wx-user", "会话定位0731"));

		verify(loop).cancel("old-active-task");
	}

	@Test
	void followUpUsesRecentConversationToChooseTravelAgent() {
		AgentLoopService loop = mock(AgentLoopService.class);
		ConversationMemory memory = mock(ConversationMemory.class);
		when(memory.history("wx-user")).thenReturn(java.util.List.of(
				new ConversationTurn("帮我做杭州三日攻略", "已给出均衡、轻松和文化三个方案。")));
		AgentTask task = new AgentTask();
		task.setId("abcdef12-1234-1234-1234-123456789012");
		when(loop.submit(eq("wx-user"), eq(TravelTaskType.NAME), any(), any())).thenReturn(task);
		AgentModeHandler handler = new AgentModeHandler(loop, memory);
		handler.handle("wx-user", "/agent-mode");

		handler.handle("wx-user", "第二个方案怎么样");

		verify(loop).submit(eq("wx-user"), eq(TravelTaskType.NAME),
				org.mockito.ArgumentMatchers.argThat(goal -> goal.contains("杭州三日攻略")
						&& goal.contains("第二个方案怎么样")), any());
		verify(memory).rememberIncoming("wx-user", "第二个方案怎么样");
	}

	@Test
	void shortTravelChoiceDoesNotInheritOlderBilibiliTopic() {
		AgentLoopService loop = mock(AgentLoopService.class);
		ConversationMemory memory = mock(ConversationMemory.class);
		when(memory.history("wx-user")).thenReturn(java.util.List.of(
				new ConversationTurn("列出我的B站收藏夹", "当前有一个默认收藏夹。"),
				new ConversationTurn("给我三个杭州方案", "第一种是杭州自然历史方案，包含西湖景点和交通安排。")));
		AgentTask task = new AgentTask();
		task.setId("travel-follow-up");
		when(loop.submit(eq("wx-user"), eq(TravelTaskType.NAME), any(), any())).thenReturn(task);
		AgentModeHandler handler = new AgentModeHandler(loop, memory);
		handler.handle("wx-user", "/agent");

		handler.handle("wx-user", "第一个");

		verify(loop).submit(eq("wx-user"), eq(TravelTaskType.NAME),
				org.mockito.ArgumentMatchers.argThat(goal -> goal.contains("杭州")
						&& !goal.contains("B站") && !goal.contains("收藏夹")), any());
	}

	@Test
	void standaloneTravelGoalDoesNotInheritStaleTicketAnswers() {
		AgentLoopService loop = mock(AgentLoopService.class);
		ConversationMemory memory = mock(ConversationMemory.class);
		when(memory.history("wx-user")).thenReturn(java.util.List.of(
				new ConversationTurn("帮我买旧方案门票",
						"旧回复包含很长的购票清单和无效示例链接。")));
		AgentTask task = new AgentTask();
		task.setId("new-trip-task");
		String goal = "8月10日从上海去杭州玩2天，2个人，预算2000，喜欢自然和历史，给我三种方案";
		when(loop.submit(eq("wx-user"), eq(TravelTaskType.NAME), eq(goal), any()))
				.thenReturn(task);
		AgentModeHandler handler = new AgentModeHandler(loop, memory);
		handler.handle("wx-user", "/agent");

		handler.handle("wx-user", goal);

		verify(loop).submit(eq("wx-user"), eq(TravelTaskType.NAME), eq(goal), any());
	}

	@Test
	void standaloneAgentGoalDoesNotInheritACompletedPreviousTask() {
		AgentLoopService loop = mock(AgentLoopService.class);
		ConversationMemory memory = mock(ConversationMemory.class);
		when(memory.history("wx-user")).thenReturn(java.util.List.of(
				new ConversationTurn("整理上周销售数据", "已完成销售周报。")));
		AgentTask task = new AgentTask();
		task.setId("fresh-task");
		when(loop.submit(eq("wx-user"), eq(GenericTaskType.NAME),
				eq("写一封项目延期通知"), any())).thenReturn(task);
		AgentModeHandler handler = new AgentModeHandler(loop, memory);
		handler.handle("wx-user", "/agent");

		handler.handle("wx-user", "写一封项目延期通知");

		verify(loop).submit(eq("wx-user"), eq(GenericTaskType.NAME),
				eq("写一封项目延期通知"), any());
	}

	@Test
	void naturalStopPausesLatestTaskInsteadOfCreatingANewOne() {
		AgentLoopService loop = mock(AgentLoopService.class);
		AgentTask running = new AgentTask();
		running.setId("99887766-1234-1234-1234-123456789012");
		running.setStatus(AgentTaskStatus.RUNNING);
		when(loop.list("wx-user")).thenReturn(java.util.List.of(running));
		when(loop.pause(running.getId())).thenReturn(running);
		AgentModeHandler handler = new AgentModeHandler(loop);
		handler.handle("wx-user", "/agent-mode");

		String reply = handler.handle("wx-user", "停");

		assertTrue(reply.contains("自动保存"));
		verify(loop).pause(running.getId());
		verify(loop, org.mockito.Mockito.never()).submit(any(), any(), any(), any());
	}
}
