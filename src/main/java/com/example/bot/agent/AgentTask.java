package com.example.bot.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Agent 任务实体：一个明确目标 + 完整运行断点。
 * 每一步循环结束后持久化，用户中途暂停 / 取消 / 服务重启都不会丢失进度。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentTask {

	public enum ControlRequest { NONE, PAUSE, CANCEL }

	private String id;
	/** Stable, user-facing sequence number within one WeChat session. */
	private int displayNumber;
	private String sessionId;
	private String taskType;
	private String goal;
	/*
	 * These two values are changed by the control API while a virtual-thread
	 * worker is running.  Visibility matters here: a pause/cancel must be seen
	 * at the next loop boundary even when the worker is blocked in a model/tool
	 * call and does not re-enter a synchronized block first.
	 */
	private volatile AgentTaskStatus status = AgentTaskStatus.PENDING;
	private volatile ControlRequest controlRequest = ControlRequest.NONE;

	private long createdAtMs;
	private long updatedAtMs;
	/** 超时熔断时间；到期后任务以 TIMEOUT 终止。 */
	private long deadlineAtMs;
	/** 下次唤醒时间：延时任务到点执行、监控任务周期休眠都靠它。 */
	private long wakeAtMs;

	private int maxSteps;
	private int stepCount;
	private long tokenBudget;
	private long tokensUsed;

	/** 任务级工作区：中间产物、草稿、监控最近一次判定等。 */
	private Map<String, String> workingState = new LinkedHashMap<>();
	/** 工作对话记录：恢复任务时据此重建模型上下文。 */
	private List<AgentTranscript.Entry> transcript = new ArrayList<>();

	/** 非空表示任务正等待人工介入。 */
	private HumanRequest pendingHuman;

	private String finalAnswer;
	private TerminationReason terminationReason;
	private String errorSummary;
	/** 监控模式已完成的感知周期数。 */
	private long monitorCycles;

	public String getId() { return id; }
	public void setId(String id) { this.id = id; }
	public int getDisplayNumber() { return displayNumber; }
	public void setDisplayNumber(int displayNumber) { this.displayNumber = displayNumber; }
	public String getSessionId() { return sessionId; }
	public void setSessionId(String sessionId) { this.sessionId = sessionId; }
	public String getTaskType() { return taskType; }
	public void setTaskType(String taskType) { this.taskType = taskType; }
	public String getGoal() { return goal; }
	public void setGoal(String goal) { this.goal = goal; }
	public AgentTaskStatus getStatus() { return status; }
	public void setStatus(AgentTaskStatus status) { this.status = status; }
	public ControlRequest getControlRequest() { return controlRequest; }
	public void setControlRequest(ControlRequest controlRequest) { this.controlRequest = controlRequest; }
	public long getCreatedAtMs() { return createdAtMs; }
	public void setCreatedAtMs(long createdAtMs) { this.createdAtMs = createdAtMs; }
	public long getUpdatedAtMs() { return updatedAtMs; }
	public void setUpdatedAtMs(long updatedAtMs) { this.updatedAtMs = updatedAtMs; }
	public long getDeadlineAtMs() { return deadlineAtMs; }
	public void setDeadlineAtMs(long deadlineAtMs) { this.deadlineAtMs = deadlineAtMs; }
	public long getWakeAtMs() { return wakeAtMs; }
	public void setWakeAtMs(long wakeAtMs) { this.wakeAtMs = wakeAtMs; }
	public int getMaxSteps() { return maxSteps; }
	public void setMaxSteps(int maxSteps) { this.maxSteps = maxSteps; }
	public int getStepCount() { return stepCount; }
	public void setStepCount(int stepCount) { this.stepCount = stepCount; }
	public long getTokenBudget() { return tokenBudget; }
	public void setTokenBudget(long tokenBudget) { this.tokenBudget = tokenBudget; }
	public long getTokensUsed() { return tokensUsed; }
	public void setTokensUsed(long tokensUsed) { this.tokensUsed = tokensUsed; }
	public Map<String, String> getWorkingState() { return workingState; }
	public void setWorkingState(Map<String, String> workingState) {
		this.workingState = workingState == null ? new LinkedHashMap<>() : workingState;
	}
	public List<AgentTranscript.Entry> getTranscript() { return transcript; }
	public void setTranscript(List<AgentTranscript.Entry> transcript) {
		this.transcript = transcript == null ? new ArrayList<>() : transcript;
	}
	public HumanRequest getPendingHuman() { return pendingHuman; }
	public void setPendingHuman(HumanRequest pendingHuman) { this.pendingHuman = pendingHuman; }
	public String getFinalAnswer() { return finalAnswer; }
	public void setFinalAnswer(String finalAnswer) { this.finalAnswer = finalAnswer; }
	public TerminationReason getTerminationReason() { return terminationReason; }
	public void setTerminationReason(TerminationReason reason) { this.terminationReason = reason; }
	public String getErrorSummary() { return errorSummary; }
	public void setErrorSummary(String errorSummary) { this.errorSummary = errorSummary; }
	public long getMonitorCycles() { return monitorCycles; }
	public void setMonitorCycles(long monitorCycles) { this.monitorCycles = monitorCycles; }

	@JsonIgnore
	public boolean waitingHuman() {
		return status == AgentTaskStatus.WAITING_HUMAN;
	}
}
