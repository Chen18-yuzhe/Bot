package com.example.demo.agent;

/** 工具 / 上游调用失败的分类，决定框架的恢复策略。 */
public enum ErrorKind {
	/** 瞬时错误：网络抖动、超时、限流、5xx。框架在不过 LLM 的情况下自动退避重试，节省 token。 */
	TRANSIENT,
	/** 需要登录 / 授权：挂起任务等待人工完成鉴权后继续。 */
	AUTH_REQUIRED,
	/** 权限不足（如数据库读写权限异常）：转人工审核。 */
	PERMISSION_DENIED,
	/** 参数或请求内容无效：把失败结构化交还模型修正。 */
	VALIDATION,
	/** 无法归类：默认交还模型，由模型决定换参数或换工具。 */
	UNKNOWN
}
