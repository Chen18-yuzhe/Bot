package com.example.demo.agent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.alibaba.dashscope.common.MultiModalMessage;
import com.alibaba.dashscope.tools.ToolBase;
import com.alibaba.dashscope.tools.ToolCallFunction;
import com.example.demo.tools.AssistantToolRegistry;

/**
 * The durable Agent Loop runtime.  It deliberately owns the state machine,
 * retry policy and stop conditions instead of leaving those decisions to the
 * language model.  A task is saved at every loop boundary, so it can be
 * paused, resumed after a restart, or handed to a human without losing work.
 */
@Service
public class AgentLoopService {
	private static final Logger log = LoggerFactory.getLogger(AgentLoopService.class);
	private static final int MODEL_MAX_TOKENS = 1_600;
	private static final int TRANSCRIPT_MAX_ENTRIES = 80;

	private final AgentTaskStore store;
	private final AgentTraceRecorder trace;
	private final AgentModelClient model;
	private final AgentToolExecutor tools;
	private final ErrorClassifier errorClassifier;
	private final AssistantToolRegistry registry;
	private final Map<String, AgentTaskType> taskTypes;
	private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
	private final Set<String> activeTasks = ConcurrentHashMap.newKeySet();
	private final Object taskNumberLock = new Object();
	private final int defaultMaxSteps;
	private final long defaultTokenBudget;
	private final Duration defaultTimeout;
	private final RecoveryPolicy defaultRecovery;
	private final int modelRecoveryMaxAttempts;

	public AgentLoopService(AgentTaskStore store, AgentTraceRecorder trace, AgentModelClient model,
			AgentToolExecutor tools, ErrorClassifier errorClassifier, AssistantToolRegistry registry, List<AgentTaskType> registeredTaskTypes,
			@Value("${assistant.agent.default-max-steps:12}") int defaultMaxSteps,
			@Value("${assistant.agent.default-token-budget:12000}") long defaultTokenBudget,
			@Value("${assistant.agent.default-timeout-seconds:600}") long defaultTimeoutSeconds,
			@Value("${assistant.agent.transient-max-attempts:3}") int transientMaxAttempts,
			@Value("${assistant.agent.transient-backoff-ms:500}") long transientBackoffMs,
			@Value("${assistant.agent.model-recovery-max-attempts:2}") int modelRecoveryMaxAttempts) {
		this.store = store;
		this.trace = trace;
		this.model = model;
		this.tools = tools;
		this.errorClassifier = errorClassifier;
		this.registry = registry;
		this.defaultMaxSteps = Math.max(1, defaultMaxSteps);
		this.defaultTokenBudget = Math.max(1, defaultTokenBudget);
		this.defaultTimeout = Duration.ofSeconds(Math.max(1, defaultTimeoutSeconds));
		this.defaultRecovery = RecoveryPolicy.defaults(transientMaxAttempts, transientBackoffMs);
		this.modelRecoveryMaxAttempts = Math.max(0, modelRecoveryMaxAttempts);
		Map<String, AgentTaskType> indexed = new LinkedHashMap<>();
		for (AgentTaskType type : registeredTaskTypes) {
			if (indexed.putIfAbsent(type.name(), type) != null) {
				throw new IllegalStateException("重复的 Agent 任务类型: " + type.name());
			}
		}
		this.taskTypes = Map.copyOf(indexed);
	}

	/** Creates and asynchronously dispatches a goal.  A delay is used by scheduled task types. */
	public AgentTask submit(String sessionId, String taskType, String goal, Duration delay) {
		if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId 不能为空");
		if (goal == null || goal.isBlank()) throw new IllegalArgumentException("任务目标不能为空");
		AgentTaskType type = requireTaskType(taskType);
		long now = System.currentTimeMillis();
		AgentTask task = new AgentTask();
		task.setId(UUID.randomUUID().toString());
		task.setSessionId(sessionId.trim());
		task.setTaskType(type.name());
		task.setGoal(goal.trim());
		task.setCreatedAtMs(now);
		task.setDeadlineAtMs(now + type.timeout(defaultTimeout).toMillis());
		task.setWakeAtMs(now + Math.max(0, delay == null ? 0 : delay.toMillis()));
		task.setMaxSteps(Math.max(1, type.maxSteps(defaultMaxSteps)));
		task.setTokenBudget(Math.max(1, type.tokenBudget(defaultTokenBudget)));
		task.getTranscript().add(AgentTranscript.Entry.user("任务目标：" + task.getGoal()));
		synchronized (taskNumberLock) {
			assignMissingDisplayNumbers(task.getSessionId());
			int nextNumber = store.listBySession(task.getSessionId()).stream()
					.mapToInt(AgentTask::getDisplayNumber).max().orElse(0) + 1;
			task.setDisplayNumber(nextNumber);
			store.save(task);
		}
		record(task, 0, AgentTraceEvent.Phase.DECISION, "TASK_CREATED", "已创建任务", type.name());
		if (task.getWakeAtMs() <= now) dispatch(task);
		return task;
	}

	public AgentTask pause(String taskId) { return requestControl(taskId, AgentTask.ControlRequest.PAUSE); }
	public AgentTask cancel(String taskId) { return requestControl(taskId, AgentTask.ControlRequest.CANCEL); }

	/**
	 * Creates a fresh execution from a finished task.  The original trace remains
	 * immutable so a failed run can still be audited after the user retries it.
	 */
	public AgentTask retry(String taskId) {
		AgentTask previous = requireTask(taskId);
		synchronized (previous) {
			if (!previous.getStatus().isTerminal()) {
				throw new IllegalStateException("任务仍在执行中；请先暂停或取消后再重试");
			}
			return submit(previous.getSessionId(), previous.getTaskType(), previous.getGoal(), Duration.ZERO);
		}
	}

	/** Permanently removes a finished task together with its trace. */
	public void delete(String taskId) {
		AgentTask task = requireTask(taskId);
		synchronized (task) {
			if (!task.getStatus().isTerminal()) {
				throw new IllegalStateException("运行中的任务不能删除；请先取消，断点会被保留");
			}
			store.delete(taskId);
			trace.clear(taskId);
		}
	}

	/** Deletes only terminal tasks belonging to one user/session; active work is never bulk-deleted. */
	public int deleteFinishedForSession(String sessionId) {
		if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId 不能为空");
		List<AgentTask> removable = list(sessionId).stream()
				.filter(task -> task.getStatus().isTerminal()).toList();
		for (AgentTask task : removable) delete(task.getId());
		return removable.size();
	}
	public AgentTask resume(String taskId) {
		AgentTask task = requireTask(taskId);
		synchronized (task) {
			if (task.getStatus().isTerminal()) throw new IllegalStateException("已结束的任务不能恢复");
			if (task.getStatus() != AgentTaskStatus.PAUSED && task.getStatus() != AgentTaskStatus.WAITING_HUMAN) {
				throw new IllegalStateException("当前任务无需恢复");
			}
			task.setPendingHuman(null);
			task.setControlRequest(AgentTask.ControlRequest.NONE);
			task.setStatus(AgentTaskStatus.PENDING);
			task.setWakeAtMs(System.currentTimeMillis());
			store.save(task);
		}
		record(task, task.getStepCount(), AgentTraceEvent.Phase.CONTROL, "RESUMED", "任务已恢复", null);
		dispatch(task);
		return task;
	}

	/** Human input is retained in the transcript before resuming so the next reasoning step can use it. */
	public AgentTask submitHumanInput(String taskId, String input) {
		if (input == null || input.isBlank()) throw new IllegalArgumentException("人工处理结果不能为空");
		AgentTask task = requireTask(taskId);
		synchronized (task) {
			if (task.getStatus() != AgentTaskStatus.WAITING_HUMAN) {
				throw new IllegalStateException("任务当前未等待人工介入");
			}
			task.getTranscript().add(AgentTranscript.Entry.user("人工处理结果：" + input.trim()));
			task.setPendingHuman(null);
			task.setStatus(AgentTaskStatus.PENDING);
			task.setWakeAtMs(System.currentTimeMillis());
			store.save(task);
		}
		record(task, task.getStepCount(), AgentTraceEvent.Phase.CONTROL, "HUMAN_INPUT", "已收到人工处理结果", null);
		dispatch(task);
		return task;
	}

	public AgentTask get(String taskId) { return requireTask(taskId); }
	public List<AgentTask> list(String sessionId) {
		if (sessionId == null || sessionId.isBlank()) return store.list();
		synchronized (taskNumberLock) {
			assignMissingDisplayNumbers(sessionId);
			return store.listBySession(sessionId);
		}
	}
	public List<AgentTraceEvent> trace(String taskId) { requireTask(taskId); return trace.trace(taskId); }

	private void assignMissingDisplayNumbers(String sessionId) {
		List<AgentTask> tasks = new ArrayList<>(store.listBySession(sessionId));
		if (tasks.stream().noneMatch(task -> task.getDisplayNumber() <= 0)) return;
		tasks.sort(java.util.Comparator.comparingLong(AgentTask::getCreatedAtMs));
		Set<Integer> used = new java.util.HashSet<>();
		tasks.stream().mapToInt(AgentTask::getDisplayNumber).filter(number -> number > 0).forEach(used::add);
		int candidate = 1;
		for (AgentTask task : tasks) {
			if (task.getDisplayNumber() > 0) continue;
			while (used.contains(candidate)) candidate++;
			task.setDisplayNumber(candidate);
			used.add(candidate++);
			store.save(task);
		}
	}

	/** Picks up due scheduled/monitor tasks and interrupted workers after a process restart. */
	@Scheduled(fixedDelayString = "${assistant.agent.dispatch-poll-ms:1000}")
	public void dispatchDueTasks() {
		long now = System.currentTimeMillis();
		for (AgentTask task : store.listDuePending(now)) dispatch(task);
		for (AgentTask task : store.listInterrupted()) {
			synchronized (task) {
				if (task.getStatus() == AgentTaskStatus.RUNNING) task.setStatus(AgentTaskStatus.PENDING);
				store.save(task);
			}
			record(task, task.getStepCount(), AgentTraceEvent.Phase.CONTROL, "RECOVERED_AFTER_RESTART",
					"检测到中断任务，准备从断点继续", null);
			dispatch(task);
		}
	}

	private AgentTask requestControl(String taskId, AgentTask.ControlRequest request) {
		AgentTask task = requireTask(taskId);
		synchronized (task) {
			if (task.getStatus().isTerminal()) return task;
			task.setControlRequest(request);
			if (task.getStatus() != AgentTaskStatus.RUNNING) applyControl(task);
			store.save(task);
		}
		record(task, task.getStepCount(), AgentTraceEvent.Phase.CONTROL, request.name(),
				request == AgentTask.ControlRequest.PAUSE ? "已请求暂停任务" : "已请求取消任务", null);
		return task;
	}

	private void dispatch(AgentTask task) {
		if (!activeTasks.add(task.getId())) return;
		workers.submit(() -> {
			try { run(task); }
			finally { activeTasks.remove(task.getId()); }
		});
	}

	private void run(AgentTask task) {
		try {
			synchronized (task) {
				if (task.getStatus().isTerminal() || task.getStatus() == AgentTaskStatus.WAITING_HUMAN) return;
				if (task.getWakeAtMs() > System.currentTimeMillis()) return;
				if (applyControl(task)) { store.save(task); return; }
				task.setStatus(AgentTaskStatus.RUNNING);
				store.save(task);
			}
			AgentTaskType type = requireTaskType(task.getTaskType());
			while (true) {
				if (stopAtBoundary(task)) return;
				int step;
				synchronized (task) { step = task.getStepCount() + 1; task.setStepCount(step); store.save(task); }
				record(task, step, AgentTraceEvent.Phase.PERCEIVE, "STATE", "读取任务状态与上一步结果", null);
				record(task, step, AgentTraceEvent.Phase.REASON, "MODEL_REQUEST", "请求模型决定下一步", null);
				ModelCallOutcome modelOutcome = callModelWithRetry(task, type, step);
				if (!modelOutcome.success()) {
					if (handleModelFailure(task, step, modelOutcome)) return;
					continue;
				}
				AgentModelClient.AgentModelTurn turn = modelOutcome.turn();
				synchronized (task) {
					task.setTokensUsed(task.getTokensUsed() + Math.max(0, turn.totalTokens()));
					task.getTranscript().add(AgentTranscript.Entry.assistant(turn.text(), toRecords(turn.toolCalls())));
					compact(task);
					store.save(task);
				}
				record(task, step, AgentTraceEvent.Phase.REASON, "MODEL_RESPONSE", "模型已返回下一步",
						"model=" + turn.model() + ", toolCalls=" + turn.toolCalls().size());
				if (!turn.requestsTools()) {
					String validationError = type.validateCompletion(task, turn.text());
					if (validationError != null && !validationError.isBlank()) {
						long previousRejections = task.getTranscript().stream()
								.filter(entry -> "user".equals(entry.role())
										&& entry.text() != null
										&& entry.text().startsWith("框架最终结果校验未通过："))
								.count();
						if (previousRejections >= 1) {
							fail(task, TerminationReason.FINAL_VALIDATION_FAILED,
									"最终回答连续两次未通过安全校验：" + validationError);
							return;
						}
						synchronized (task) {
							task.getTranscript().add(AgentTranscript.Entry.user(
									"框架最终结果校验未通过：" + validationError
											+ "\n请立即修正最终回答，不要重复已经完成的查询。"));
							compact(task);
							store.save(task);
						}
						record(task, step, AgentTraceEvent.Phase.DECISION, "FINAL_ANSWER_REJECTED",
								"最终回答未通过任务类型校验", validationError);
						continue;
					}
					if (type.loopMode() == LoopMode.MONITOR && !isMonitorTriggered(turn.text())) {
						sleepMonitor(task, type, step); return;
					}
					complete(task, type, type.loopMode() == LoopMode.MONITOR ? TerminationReason.MONITOR_TRIGGERED : TerminationReason.GOAL_ACHIEVED,
							turn.text());
					return;
				}
				if (task.getTokensUsed() >= task.getTokenBudget()) {
					fail(task, TerminationReason.TOKEN_BUDGET_EXHAUSTED, "已达到 token 预算"); return;
				}
				for (ToolCallFunction call : turn.toolCalls()) {
					if (stopAtBoundary(task)) return;
					String name = call.getFunction().getName();
					if (!isAllowed(type, name)) {
						observe(task, step, call, failureJson(name, ErrorKind.PERMISSION_DENIED, "该任务类型不允许调用此工具", false));
						continue;
					}
					record(task, step, AgentTraceEvent.Phase.ACT, "TOOL_CALL", "调用工具: " + name, null);
					AgentToolExecutor.ToolOutcome outcome = executeWithRetry(name, call.getFunction().getArguments(), task, type);
					boolean handedOff = !outcome.success() && handoffOrObserve(task, type, step, call, name, outcome);
					String content = outcome.success() ? outcome.content()
							: outcome.hasDegradedContent() ? degradedJson(name, outcome.degradedContent())
							: failureJson(name, outcome.errorKind(), outcome.content(), true);
					observe(task, step, call, content);
					if (!outcome.success() && !handedOff) appendToolRecoveryChecklist(task, name, outcome);
					if (handedOff) return;
				}
			}
		} catch (RuntimeException error) {
			log.error("Agent Loop 运行异常: task={}", task.getId(), error);
			fail(task, TerminationReason.FATAL_ERROR, safeMessage(error));
		}
	}

	/** Retries transport/rate-limit model failures before involving the model again. */
	private ModelCallOutcome callModelWithRetry(AgentTask task, AgentTaskType type, int step) {
		RecoveryPolicy policy = type.recoveryPolicy(defaultRecovery);
		Exception lastError = null;
		ErrorKind lastKind = ErrorKind.UNKNOWN;
		for (int attempt = 1; attempt <= policy.transientMaxAttempts(); attempt++) {
			try {
				return ModelCallOutcome.success(model.call(messages(type, task), allowedToolDefinitions(type), MODEL_MAX_TOKENS));
			} catch (Exception error) {
				lastError = error;
				lastKind = errorClassifier.classify(error);
				if (lastKind != ErrorKind.TRANSIENT || attempt == policy.transientMaxAttempts()) break;
				record(task, step, AgentTraceEvent.Phase.DECISION, "MODEL_TRANSIENT_RETRY",
						"模型服务暂时不可用，框架将自动重试", "attempt=" + attempt);
				sleepQuietly(policy.transientBackoffMs() * (1L << (attempt - 1)));
			}
		}
		return ModelCallOutcome.failure(lastKind, lastError);
	}

	/**
	 * Model failures used to end a task immediately.  Recoverable failures now
	 * leave a durable self-check instruction in the task context, so a later
	 * model call must revisit the plan and verify its final result.
	 */
	private boolean handleModelFailure(AgentTask task, int step, ModelCallOutcome outcome) {
		ErrorKind kind = outcome.errorKind();
		String detail = "kind=" + kind + ", message=" + safeMessage(outcome.error());
		if (kind == ErrorKind.AUTH_REQUIRED || kind == ErrorKind.PERMISSION_DENIED) {
			HumanRequest.Kind requestKind = kind == ErrorKind.AUTH_REQUIRED
					? HumanRequest.Kind.LOGIN : HumanRequest.Kind.APPROVAL;
			synchronized (task) {
				task.setPendingHuman(new HumanRequest(requestKind,
						"模型服务需要人工检查授权或访问权限后才能继续。完成后请提交处理结果。",
						System.currentTimeMillis()));
				task.setStatus(AgentTaskStatus.WAITING_HUMAN);
				store.save(task);
			}
			record(task, step, AgentTraceEvent.Phase.DECISION, "MODEL_HUMAN_HANDOFF", "模型调用需要人工处理", detail);
			return true;
		}
		long previousRecoveries = task.getTranscript().stream()
				.filter(entry -> "user".equals(entry.role()) && entry.text() != null
						&& entry.text().startsWith("框架自检：模型调用失败"))
				.count();
		if (previousRecoveries >= modelRecoveryMaxAttempts) {
			fail(task, TerminationReason.FATAL_ERROR, "模型调用在自检恢复后仍失败: " + safeMessage(outcome.error()));
			return true;
		}
		synchronized (task) {
			task.getTranscript().add(AgentTranscript.Entry.user("框架自检：模型调用失败（" + kind + "）。"
					+ "请先检查已完成步骤和最近的失败信息，修正计划或参数；不要重复未改变的操作。"
					+ "再次给出最终答复前，必须核对目标、证据和未完成项。"));
			compact(task);
			store.save(task);
		}
		record(task, step, AgentTraceEvent.Phase.DECISION, "MODEL_SELF_CHECK", "模型调用失败，已注入自检恢复指令", detail);
		return false;
	}

	private void appendToolRecoveryChecklist(AgentTask task, String tool, AgentToolExecutor.ToolOutcome outcome) {
		synchronized (task) {
			task.getTranscript().add(AgentTranscript.Entry.user("框架自检：工具 " + tool + " 执行失败（"
					+ outcome.errorKind() + "）。请阅读刚返回的结构化错误，诊断根因后选择修正参数、"
					+ "改用工具、采用降级结果或明确说明阻塞原因；不得用完全相同的参数重复失败调用。"
					+ "最终答复前请核对所有结论是否有已验证的证据。"));
			compact(task);
			store.save(task);
		}
		record(task, task.getStepCount(), AgentTraceEvent.Phase.EVALUATE, "TOOL_SELF_CHECK",
				"工具失败，已要求模型诊断并验证后续方案", "tool=" + tool + ", kind=" + outcome.errorKind());
	}

	private record ModelCallOutcome(AgentModelClient.AgentModelTurn turn, ErrorKind errorKind, Exception error) {
		static ModelCallOutcome success(AgentModelClient.AgentModelTurn turn) {
			return new ModelCallOutcome(turn, null, null);
		}
		static ModelCallOutcome failure(ErrorKind errorKind, Exception error) {
			return new ModelCallOutcome(null, errorKind == null ? ErrorKind.UNKNOWN : errorKind, error);
		}
		boolean success() { return turn != null; }
	}

	private AgentToolExecutor.ToolOutcome executeWithRetry(String name, String arguments, AgentTask task, AgentTaskType type) {
		RecoveryPolicy policy = type.recoveryPolicy(defaultRecovery);
		AgentToolExecutor.ToolOutcome outcome = null;
		for (int attempt = 1; attempt <= policy.transientMaxAttempts(); attempt++) {
			outcome = tools.execute(name, arguments, task);
			if (outcome.success() || outcome.errorKind() != ErrorKind.TRANSIENT) return outcome;
			record(task, task.getStepCount(), AgentTraceEvent.Phase.DECISION, "TRANSIENT_RETRY",
					"瞬时错误，框架自动重试", "tool=" + name + ", attempt=" + attempt);
			if (attempt < policy.transientMaxAttempts()) sleepQuietly(policy.transientBackoffMs() * (1L << (attempt - 1)));
		}
		return outcome;
	}

	private boolean handoffOrObserve(AgentTask task, AgentTaskType type, int step, ToolCallFunction call,
			String name, AgentToolExecutor.ToolOutcome outcome) {
		RecoveryPolicy policy = type.recoveryPolicy(defaultRecovery);
		if (outcome.hasDegradedContent() && policy.allowDegrade()) {
			record(task, step, AgentTraceEvent.Phase.DECISION, "DEGRADED", "工具已降级，继续执行", name);
			return false;
		}
		if (policy.decide(outcome.errorKind()) != RecoveryAction.HUMAN_HANDOFF) return false;
		synchronized (task) {
			HumanRequest.Kind kind = outcome.humanKind() == null ? HumanRequest.Kind.REVIEW : outcome.humanKind();
			String prompt = outcome.humanPrompt() == null || outcome.humanPrompt().isBlank()
					? "任务需要人工处理后才能继续：" + outcome.content() : outcome.humanPrompt();
			task.setPendingHuman(new HumanRequest(kind, prompt, System.currentTimeMillis()));
			task.setStatus(AgentTaskStatus.WAITING_HUMAN);
			task.setControlRequest(AgentTask.ControlRequest.NONE);
			store.save(task);
		}
		record(task, step, AgentTraceEvent.Phase.DECISION, "HUMAN_HANDOFF", "任务等待人工介入", name);
		return true;
	}

	private boolean stopAtBoundary(AgentTask task) {
		synchronized (task) {
			if (applyControl(task)) { store.save(task); return true; }
			if (System.currentTimeMillis() >= task.getDeadlineAtMs()) { fail(task, TerminationReason.TIMEOUT, "任务执行超时"); return true; }
			if (task.getStepCount() >= task.getMaxSteps()) { fail(task, TerminationReason.MAX_STEPS, "达到最大循环步数"); return true; }
			if (task.getTokensUsed() >= task.getTokenBudget()) { fail(task, TerminationReason.TOKEN_BUDGET_EXHAUSTED, "已达到 token 预算"); return true; }
			return false;
		}
	}

	private boolean applyControl(AgentTask task) {
		if (task.getControlRequest() == AgentTask.ControlRequest.PAUSE) {
			task.setControlRequest(AgentTask.ControlRequest.NONE); task.setStatus(AgentTaskStatus.PAUSED);
			record(task, task.getStepCount(), AgentTraceEvent.Phase.TERMINATE, "PAUSED", "任务已暂停，断点已保存", null); return true;
		}
		if (task.getControlRequest() == AgentTask.ControlRequest.CANCEL) {
			task.setControlRequest(AgentTask.ControlRequest.NONE); task.setStatus(AgentTaskStatus.CANCELLED);
			task.setTerminationReason(TerminationReason.CANCELLED); task.setFinalAnswer("任务已取消，已保留执行记录。");
			record(task, task.getStepCount(), AgentTraceEvent.Phase.TERMINATE, "CANCELLED", "任务已取消", null); return true;
		}
		return false;
	}

	private void sleepMonitor(AgentTask task, AgentTaskType type, int step) {
		synchronized (task) {
			task.setMonitorCycles(task.getMonitorCycles() + 1);
			task.setStatus(AgentTaskStatus.PENDING);
			task.setWakeAtMs(System.currentTimeMillis() + type.monitorInterval().toMillis());
			store.save(task);
		}
		record(task, step, AgentTraceEvent.Phase.EVALUATE, "MONITOR_CLEAR", "监控条件未触发，等待下一周期", null);
	}

	private void complete(AgentTask task, AgentTaskType type, TerminationReason reason, String answer) {
		synchronized (task) {
			task.setFinalAnswer(answer == null || answer.isBlank() ? "任务已完成。" : answer.trim());
			if (type.loopMode() == LoopMode.SCHEDULED && !type.repeatInterval().isZero()
					&& !type.repeatInterval().isNegative()) {
				long now = System.currentTimeMillis();
				task.setStatus(AgentTaskStatus.PENDING);
				task.setWakeAtMs(now + type.repeatInterval().toMillis());
				task.setDeadlineAtMs(now + type.timeout(defaultTimeout).toMillis());
				task.setStepCount(0);
				task.setTokensUsed(0);
				task.setTranscript(new ArrayList<>(List.of(AgentTranscript.Entry.user(
						"任务目标：" + task.getGoal() + "\n上一周期已完成；请执行新的周期。"))));
				store.save(task);
				record(task, 0, AgentTraceEvent.Phase.EVALUATE, "SCHEDULED_NEXT", "本周期完成，已安排下一周期", null);
				return;
			}
			task.setTerminationReason(reason);
			task.setStatus(AgentTaskStatus.COMPLETED);
			store.save(task);
		}
		record(task, task.getStepCount(), AgentTraceEvent.Phase.TERMINATE, reason.name(), "任务完成", null);
	}

	private void fail(AgentTask task, TerminationReason reason, String summary) {
		synchronized (task) {
			if (task.getStatus().isTerminal()) return;
			task.setStatus(AgentTaskStatus.FAILED); task.setTerminationReason(reason); task.setErrorSummary(summary); store.save(task);
		}
		record(task, task.getStepCount(), AgentTraceEvent.Phase.TERMINATE, reason.name(), "任务终止", summary);
	}

	private List<Object> messages(AgentTaskType type, AgentTask task) {
		List<Object> messages = new ArrayList<>();
		messages.add(MultiModalMessage.builder().role("system")
				.content(List.of(Map.of("text", type.systemPrompt(task)))).build());
		String budgetConstraint = "Task constraint: remaining steps=" + Math.max(0, task.getMaxSteps() - task.getStepCount())
				+ ", remaining token budget=" + Math.max(0, task.getTokenBudget() - task.getTokensUsed())
				+ ". If the goal is complete, return the final report without another tool call.";
		messages.add(MultiModalMessage.builder().role("system")
				.content(List.of(Map.of("text", budgetConstraint))).build());
		messages.addAll(AgentTranscript.toSdkMessages(task.getTranscript()));
		return messages;
	}

	private List<ToolBase> allowedToolDefinitions(AgentTaskType type) {
		Set<String> allowed = type.allowedTools();
		if (allowed == null || allowed.isEmpty()) return registry.definitions();
		return allowed.stream().sorted().map(registry::definition).toList();
	}
	private boolean isAllowed(AgentTaskType type, String toolName) {
		Set<String> allowed = type.allowedTools(); return allowed == null || allowed.isEmpty() || allowed.contains(toolName);
	}
	private static boolean isMonitorTriggered(String text) { return text != null && text.trim().toUpperCase().startsWith("TRIGGERED"); }
	private static List<AgentTranscript.ToolCallRecord> toRecords(Collection<ToolCallFunction> calls) {
		return calls.stream().map(call -> new AgentTranscript.ToolCallRecord(call.getId(), call.getFunction().getName(),
				call.getFunction().getArguments())).toList();
	}
	private void observe(AgentTask task, int step, ToolCallFunction call, String content) {
		synchronized (task) { task.getTranscript().add(AgentTranscript.Entry.tool(call.getId(), call.getFunction().getName(), content)); compact(task); store.save(task); }
		record(task, step, AgentTraceEvent.Phase.OBSERVE, "TOOL_RESULT", "已观察工具结果: " + call.getFunction().getName(), null);
	}
	private static String failureJson(String tool, ErrorKind kind, String message, boolean retryable) {
		return "{\"success\":false,\"tool\":\"" + escape(tool) + "\",\"errorKind\":\"" + kind
				+ "\",\"message\":\"" + escape(message) + "\",\"retryable\":" + retryable + "}";
	}
	private static String degradedJson(String tool, String content) { return "{\"success\":true,\"tool\":\"" + escape(tool) + "\",\"degraded\":true,\"content\":\"" + escape(content) + "\"}"; }
	private static String escape(String value) { return (value == null ? "" : value).replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n"); }
	private static String safeMessage(Throwable error) { String message = error.getMessage(); return message == null || message.isBlank() ? "未知错误" : message.substring(0, Math.min(300, message.length())); }
	private static void sleepQuietly(long milliseconds) { try { Thread.sleep(Math.max(1, milliseconds)); } catch (InterruptedException error) { Thread.currentThread().interrupt(); } }
	private void compact(AgentTask task) { task.setTranscript(new ArrayList<>(AgentTranscript.compact(task.getTranscript(), TRANSCRIPT_MAX_ENTRIES))); }
	private AgentTask requireTask(String taskId) { AgentTask task = store.find(taskId); if (task == null) throw new IllegalArgumentException("任务不存在: " + taskId); return task; }
	private AgentTaskType requireTaskType(String name) { AgentTaskType type = taskTypes.get(name == null || name.isBlank() ? GenericTaskType.NAME : name); if (type == null) throw new IllegalArgumentException("未知任务类型: " + name); return type; }
	private void record(AgentTask task, int step, AgentTraceEvent.Phase phase, String kind, String summary, String detail) { trace.record(AgentTraceEvent.of(task.getId(), step, phase, kind, summary, detail, task.getTokensUsed())); }
}
