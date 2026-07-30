package com.example.bot.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

class AgentTaskStoreTests {

	@TempDir Path tempDir;

	@Test
	void restoresPersistedTaskAfterProcessRestart() {
		Path file = tempDir.resolve("agent-tasks.json");
		AgentTaskStore firstProcess = store(file);
		AgentTask original = task("restore-me", AgentTaskStatus.PENDING, 0);
		firstProcess.save(original);

		AgentTask restored = store(file).find(original.getId());

		assertEquals(original.getGoal(), restored.getGoal());
		assertEquals(AgentTaskStatus.PENDING, restored.getStatus());
		assertTrue(restored.getUpdatedAtMs() > 0);
	}

	@Test
	void findsDueAndInterruptedTasksFromLocalFallback() {
		AgentTaskStore store = store(tempDir.resolve("agent-tasks.json"));
		store.save(task("due", AgentTaskStatus.PENDING, System.currentTimeMillis() - 1));
		store.save(task("later", AgentTaskStatus.PENDING, System.currentTimeMillis() + 60_000));
		store.save(task("interrupted", AgentTaskStatus.RUNNING, 0));

		assertEquals("due", store.listDuePending(System.currentTimeMillis()).getFirst().getId());
		assertEquals("interrupted", store.listInterrupted().getFirst().getId());
	}

	@Test
	void deleteRemovesTaskFromFallbackFile() {
		Path file = tempDir.resolve("agent-tasks.json");
		AgentTaskStore store = store(file);
		store.save(task("delete-me", AgentTaskStatus.COMPLETED, 0));

		store.delete("delete-me");

		assertNull(store.find("delete-me"));
		assertNull(store(file).find("delete-me"));
	}

	@SuppressWarnings("unchecked")
	private static AgentTaskStore store(Path file) {
		ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(null);
		return new AgentTaskStore(new ObjectMapper(), provider, "test:agent:", file.toString());
	}

	private static AgentTask task(String id, AgentTaskStatus status, long wakeAtMs) {
		AgentTask task = new AgentTask();
		task.setId(id);
		task.setSessionId("session");
		task.setTaskType(GenericTaskType.NAME);
		task.setGoal("可恢复任务 " + id);
		task.setStatus(status);
		task.setWakeAtMs(wakeAtMs);
		task.setDeadlineAtMs(System.currentTimeMillis() + 60_000);
		task.setMaxSteps(4);
		task.setTokenBudget(100);
		return task;
	}
}
