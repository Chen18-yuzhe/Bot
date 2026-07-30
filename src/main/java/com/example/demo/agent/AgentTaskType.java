package com.example.demo.agent;

import java.time.Duration;
import java.util.Set;

/**
 * Agent 任务类型 SPI。每种任务场景（发布文章、投递简历、监控信息、生成代码……）
 * 实现为一个 Spring Bean，一个 Agent 即可同时接入多种任务类型；
 * 每种类型声明自己的 Loop 模式、可用工具、预算与错误恢复策略。
 */
public interface AgentTaskType {

	/** 类型名，提交任务时按名引用（如 generic / monitor）。 */
	String name();

	/**
	 * Whether this task type can handle the current request and recent context.
	 * Implementations are discovered as Spring beans, so adding a scenario does not
	 * require changing a central if/else router.
	 */
	default boolean supports(String requestContext) {
		return false;
	}

	/** Higher-priority task types win when several types match the same request. */
	default int routingPriority() {
		return 0;
	}

	/**
	 * Whether a matching request may enter the Agent loop from normal chat mode.
	 * Keep this false for expensive tasks unless the intent is explicit.
	 */
	default boolean autoStart(String requestContext) {
		return false;
	}

	/** 该类型使用哪种 Loop 模式运行。 */
	default LoopMode loopMode() {
		return LoopMode.GOAL;
	}

	/** 任务级系统提示词：描述场景目标、可用工具用法与输出要求。 */
	String systemPrompt(AgentTask task);

	/**
	 * Final-answer guard. Returning a non-blank reason rejects the answer and
	 * sends the reason back into the loop for correction.
	 */
	default String validateCompletion(AgentTask task, String answer) {
		return null;
	}

	/** 允许模型调用的工具白名单；空集合表示放开全部已注册工具。 */
	default Set<String> allowedTools() {
		return Set.of();
	}

	/** 最大推理步数；返回默认值表示沿用全局配置。 */
	default int maxSteps(int configuredDefault) {
		return configuredDefault;
	}

	/** token 预算（输入+输出累计）。 */
	default long tokenBudget(long configuredDefault) {
		return configuredDefault;
	}

	/** 任务整体超时；到期熔断。 */
	default Duration timeout(Duration configuredDefault) {
		return configuredDefault;
	}

	/** MONITOR 模式：相邻两个感知周期的间隔。 */
	default Duration monitorInterval() {
		return Duration.ofMinutes(5);
	}

	/**
	 * SCHEDULED 模式：完成一次执行后的重复周期（如每周周报返回 7 天）；
	 * Duration.ZERO 表示一次性任务。
	 */
	default Duration repeatInterval() {
		return Duration.ZERO;
	}

	/** 错误恢复策略；默认沿用全局配置。 */
	default RecoveryPolicy recoveryPolicy(RecoveryPolicy configuredDefault) {
		return configuredDefault;
	}
}
