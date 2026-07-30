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

import com.example.bot.chat.ConversationMemory;
import com.example.bot.chat.ConversationTurn;

class AgentModeHandlerTests {
	@Test
	void agentModeSubmitsPlainMessagesAsTasks() {
		AgentLoopService loop = mock(AgentLoopService.class);
		AgentTask task = new AgentTask();
		task.setId("12345678-1234-1234-1234-123456789012");
		when(loop.submit(eq("wx-user"), eq(TravelTaskType.NAME), eq("帮我比较三家酒店"), any())).thenReturn(task);
		AgentModeHandler handler = new AgentModeHandler(loop);

		assertTrue(handler.handle("wx-user", "/agent-mode").contains("Agent 模式"));
		String reply = handler.handle("wx-user", "帮我比较三家酒店");

		assertTrue(reply.contains("正在处理"));
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

		assertTrue(reply.contains("正在处理"));
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

		assertTrue(reply.contains("正在处理"));
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

		assertTrue(reply.contains("正在处理"));
		verify(loop).submit(eq("wx-user"), eq(TravelTaskType.NAME),
				eq("帮我找灵隐飞来峰门票 H5 链接"), any());
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
