package com.example.bot.agent;

/** Agent Loop 的终止条件；每个循环都必须有明确退出原因，便于审计。 */
public enum TerminationReason {
	/** 模型给出最终回答，目标达成。 */
	GOAL_ACHIEVED,
	/** 监控任务感知到触发条件。 */
	MONITOR_TRIGGERED,
	/** 达到最大推理步数。 */
	MAX_STEPS,
	/** token 预算耗尽。 */
	TOKEN_BUDGET_EXHAUSTED,
	/** 最终回答连续两次未通过任务类型安全校验。 */
	FINAL_VALIDATION_FAILED,
	/** 超过任务截止时间（超时熔断）。 */
	TIMEOUT,
	/** 用户主动取消。 */
	CANCELLED,
	/** 不可恢复错误。 */
	FATAL_ERROR
}
