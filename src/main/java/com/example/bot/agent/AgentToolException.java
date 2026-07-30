package com.example.bot.agent;

/**
 * 工具显式声明错误类别的异常。Agent Loop 按类别分发恢复策略，
 * 不再把原始错误原封不动回抛给模型或用户。
 *
 * 典型用法：
 * - transientError：网络请求失败，框架自动退避重试（不消耗 LLM token）。
 * - authRequired：未登录，任务挂起等待人工完成鉴权。
 * - degraded：写入失败但已自动降级（如发布失败改为存入草稿箱），循环携带降级结果继续。
 */
public class AgentToolException extends RuntimeException {
	private final ErrorKind kind;
	private final String degradedContent;
	private final HumanRequest.Kind humanKind;
	private final String humanPrompt;

	private AgentToolException(ErrorKind kind, String message, String degradedContent,
			HumanRequest.Kind humanKind, String humanPrompt, Throwable cause) {
		super(message, cause);
		this.kind = kind;
		this.degradedContent = degradedContent;
		this.humanKind = humanKind;
		this.humanPrompt = humanPrompt;
	}

	/** 瞬时错误：框架自动重试，不经过 LLM。 */
	public static AgentToolException transientError(String message, Throwable cause) {
		return new AgentToolException(ErrorKind.TRANSIENT, message, null, null, null, cause);
	}

	/** 未登录 / 授权失效：挂起任务，等待人工完成鉴权。 */
	public static AgentToolException authRequired(String message, String humanPrompt) {
		return new AgentToolException(ErrorKind.AUTH_REQUIRED, message, null, HumanRequest.Kind.LOGIN,
				humanPrompt, null);
	}

	/** 权限不足：转人工审核（默认 APPROVAL）。 */
	public static AgentToolException permissionDenied(String message, String humanPrompt) {
		return new AgentToolException(ErrorKind.PERMISSION_DENIED, message, null, HumanRequest.Kind.APPROVAL,
				humanPrompt, null);
	}

	/** 参数无效：交还模型修正参数。 */
	public static AgentToolException validation(String message) {
		return new AgentToolException(ErrorKind.VALIDATION, message, null, null, null, null);
	}

	/**
	 * 动作未达成但已自动降级。例如发布 CSDN 失败时改为保存草稿：
	 * degradedContent 是交给模型的降级结果说明，循环继续进行。
	 */
	public static AgentToolException degraded(String message, String degradedContent) {
		return new AgentToolException(ErrorKind.UNKNOWN, message, degradedContent, null, null, null);
	}

	public ErrorKind kind() { return kind; }
	/** 非空表示本次失败有可用的降级产物。 */
	public String degradedContent() { return degradedContent; }
	public HumanRequest.Kind humanKind() { return humanKind; }
	public String humanPrompt() { return humanPrompt; }
}
