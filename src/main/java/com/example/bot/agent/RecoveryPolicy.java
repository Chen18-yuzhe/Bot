package com.example.bot.agent;

/**
 * 错误恢复策略。任务类型可以覆盖默认值，实现“每种任务类型对应不同错误恢复策略”。
 *
 * @param transientMaxAttempts 瞬时错误在框架内的最大自动重试次数（不经过 LLM）
 * @param transientBackoffMs   瞬时重试的初始退避毫秒数，之后指数加倍
 * @param allowDegrade         是否允许采用工具声明的降级产物继续循环
 */
public record RecoveryPolicy(int transientMaxAttempts, long transientBackoffMs, boolean allowDegrade) {

	public static RecoveryPolicy defaults(int transientMaxAttempts, long transientBackoffMs) {
		return new RecoveryPolicy(Math.max(1, transientMaxAttempts), Math.max(100, transientBackoffMs), true);
	}

	/**
	 * 对一次已分类的失败给出处置决策。
	 * TRANSIENT 的重试由执行器在调用本方法之前完成；走到这里说明重试已耗尽。
	 */
	public RecoveryAction decide(ErrorKind kind) {
		return switch (kind) {
			case AUTH_REQUIRED, PERMISSION_DENIED -> RecoveryAction.HUMAN_HANDOFF;
			case TRANSIENT, VALIDATION, UNKNOWN -> RecoveryAction.RETURN_TO_MODEL;
		};
	}
}
