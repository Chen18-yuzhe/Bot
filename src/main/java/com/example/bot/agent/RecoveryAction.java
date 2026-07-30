package com.example.bot.agent;

/** 框架对一次失败的处置决策。 */
public enum RecoveryAction {
	/** 瞬时重试：框架内退避重试，不消耗 LLM token。 */
	RETRY_TRANSIENT,
	/** 把结构化失败交还模型，由模型修正参数或更换工具。 */
	RETURN_TO_MODEL,
	/** 采用降级产物作为本步观察结果，循环继续。 */
	DEGRADE,
	/** 挂起任务，等待人工介入（登录 / 审批 / 补充信息）。 */
	HUMAN_HANDOFF,
	/** 不可恢复，终止任务。 */
	ABORT
}
