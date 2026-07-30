package com.example.demo.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.demo.tools.AssistantToolRegistry;

class AgentLoopServiceTests {

	@Test
	void selfChecksAfterRecoverableModelFailureBeforeReturningTheTask() throws Exception {
		AgentModelClient model = mock(AgentModelClient.class);
		when(model.call(any(), any(), any(Integer.class)))
				.thenThrow(new IllegalArgumentException("bad request"))
				.thenReturn(new AgentModelClient.AgentModelTurn("已完成", List.of(), 1, 1, "test-model"));
		AgentLoopService loop = loop(model, List.of(new GenericTaskType()));
		AgentTask task = runnableTask();

		run(loop, task);

		assertEquals(AgentTaskStatus.COMPLETED, task.getStatus(), task.getErrorSummary());
		assertTrue(task.getTranscript().stream()
				.anyMatch(entry -> "user".equals(entry.role()) && entry.text().startsWith("框架自检：模型调用失败")));
		verify(model, times(2)).call(any(), any(), any(Integer.class));
	}

	@Test
	void stopsBeforeCallingModelWhenDeadlineHasExpired() throws Exception {
		AgentModelClient model = mock(AgentModelClient.class);
		AgentTask task = runnableTask();
		task.setDeadlineAtMs(System.currentTimeMillis() - 1);

		run(loop(model, List.of(new GenericTaskType())), task);

		assertEquals(AgentTaskStatus.FAILED, task.getStatus());
		assertEquals(TerminationReason.TIMEOUT, task.getTerminationReason());
		verify(model, never()).call(any(), any(), any(Integer.class));
	}

	@Test
	void stopsAtConfiguredMaximumStepBoundary() throws Exception {
		AgentModelClient model = mock(AgentModelClient.class);
		AgentTask task = runnableTask();
		task.setStepCount(task.getMaxSteps());

		run(loop(model, List.of(new GenericTaskType())), task);

		assertEquals(AgentTaskStatus.FAILED, task.getStatus());
		assertEquals(TerminationReason.MAX_STEPS, task.getTerminationReason());
		verify(model, never()).call(any(), any(), any(Integer.class));
	}

	@Test
	void stopsWhenPersistedTokenBudgetIsAlreadyExhausted() throws Exception {
		AgentModelClient model = mock(AgentModelClient.class);
		AgentTask task = runnableTask();
		task.setTokensUsed(task.getTokenBudget());

		run(loop(model, List.of(new GenericTaskType())), task);

		assertEquals(AgentTaskStatus.FAILED, task.getStatus());
		assertEquals(TerminationReason.TOKEN_BUDGET_EXHAUSTED, task.getTerminationReason());
		verify(model, never()).call(any(), any(), any(Integer.class));
	}

	@Test
	void appliesPersistedPauseRequestAtNextBoundary() throws Exception {
		AgentModelClient model = mock(AgentModelClient.class);
		AgentTask task = runnableTask();
		task.setControlRequest(AgentTask.ControlRequest.PAUSE);

		run(loop(model, List.of(new GenericTaskType())), task);

		assertEquals(AgentTaskStatus.PAUSED, task.getStatus());
		assertEquals(AgentTask.ControlRequest.NONE, task.getControlRequest());
		verify(model, never()).call(any(), any(), any(Integer.class));
	}

	@Test
	void failsAfterFinalAnswerIsRejectedTwice() throws Exception {
		AgentTaskType strict = new AgentTaskType() {
			@Override public String name() { return "strict"; }
			@Override public String systemPrompt(AgentTask task) { return "Return a verifiable answer."; }
			@Override public String validateCompletion(AgentTask task, String answer) { return "缺少证据"; }
		};
		AgentModelClient model = mock(AgentModelClient.class);
		when(model.call(any(), any(), any(Integer.class)))
				.thenReturn(new AgentModelClient.AgentModelTurn("没有证据的答案", List.of(), 1, 1, "test-model"));
		AgentTask task = runnableTask();
		task.setTaskType("strict");

		run(loop(model, List.of(strict)), task);

		assertEquals(AgentTaskStatus.FAILED, task.getStatus());
		assertEquals(TerminationReason.FINAL_VALIDATION_FAILED, task.getTerminationReason());
		verify(model, times(2)).call(any(), any(), any(Integer.class));
	}

	private static AgentLoopService loop(AgentModelClient model, List<AgentTaskType> taskTypes) {
		AgentTaskStore store = mock(AgentTaskStore.class);
		AgentTraceRecorder trace = mock(AgentTraceRecorder.class);
		AgentToolExecutor tools = mock(AgentToolExecutor.class);
		AssistantToolRegistry registry = mock(AssistantToolRegistry.class);
		when(registry.definitions()).thenReturn(List.of());
		return new AgentLoopService(store, trace, model, tools, new ErrorClassifier(), registry,
				taskTypes, 4, 100, 60, 2, 1, 2);
	}

	private static void run(AgentLoopService loop, AgentTask task) throws Exception {
		Method run = AgentLoopService.class.getDeclaredMethod("run", AgentTask.class);
		run.setAccessible(true);
		run.invoke(loop, task);
	}

	private static AgentTask runnableTask() {
		long now = System.currentTimeMillis();
		AgentTask task = new AgentTask();
		task.setId("agent-loop-test");
		task.setSessionId("test-user");
		task.setTaskType(GenericTaskType.NAME);
		task.setGoal("完成测试任务");
		task.setWakeAtMs(now);
		task.setDeadlineAtMs(now + 60_000);
		task.setMaxSteps(4);
		task.setTokenBudget(100);
		task.getTranscript().add(AgentTranscript.Entry.user("任务目标：完成测试任务"));
		return task;
	}
}
