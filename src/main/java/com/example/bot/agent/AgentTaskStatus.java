package com.example.bot.agent;

/**
 * Agent 任务状态机：
 * PENDING → RUNNING → COMPLETED / FAILED / CANCELLED
 *     ↑↓ WAITING_HUMAN（人工接管挂起）、PAUSED（用户暂停，断点保留）
 */
public enum AgentTaskStatus {
	/** 待运行；wakeAtMs 到期后由调度器派发。 */
	PENDING,
	/** 循环执行中。 */
	RUNNING,
	/** 等待人工介入（登录授权 / 审批 / 补充信息）。 */
	WAITING_HUMAN,
	/** 用户主动暂停，断点已持久化，可随时恢复。 */
	PAUSED,
	/** 目标达成（含监控触发）。 */
	COMPLETED,
	/** 失败终止（步数 / token 预算 / 超时 / 不可恢复错误）。 */
	FAILED,
	/** 用户取消。 */
	CANCELLED;

	public boolean isTerminal() {
		return this == COMPLETED || this == FAILED || this == CANCELLED;
	}
}
