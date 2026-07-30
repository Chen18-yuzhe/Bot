package com.example.bot.agent;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;

import org.springframework.stereotype.Component;

/**
 * 把工具 / 上游抛出的异常归类为 ErrorKind。
 * 工具抛出 AgentToolException 时以显式声明为准；否则按异常类型和消息启发式判断。
 * 只读取异常类型与消息中的状态码 / 关键词，不记录任何凭据。
 */
@Component
public class ErrorClassifier {

	public ErrorKind classify(Throwable error) {
		for (Throwable current = error; current != null; current = current.getCause()) {
			if (current instanceof AgentToolException declared) return declared.kind();
			if (current instanceof IllegalArgumentException) return ErrorKind.VALIDATION;
		}
		for (Throwable current = error; current != null; current = current.getCause()) {
			if (current instanceof SocketTimeoutException || current instanceof ConnectException
					|| current instanceof UnknownHostException || current instanceof java.io.IOException) {
				return ErrorKind.TRANSIENT;
			}
		}
		String message = chainMessages(error);
		if (message.isEmpty()) return ErrorKind.UNKNOWN;
		if (containsAny(message, "401", "未登录", "登录已过期", "请先登录", "需要登录", "unauthorized",
				"authentication", "invalid token", "token expired", "登录失效", "扫码登录")) {
			return ErrorKind.AUTH_REQUIRED;
		}
		if (containsAny(message, "403", "forbidden", "permission denied", "没有权限", "无权限", "权限不足",
				"access denied", "不允许的操作")) {
			return ErrorKind.PERMISSION_DENIED;
		}
		if (containsAny(message, "timeout", "timed out", "超时", "429", "too many requests", "限流",
				"rate limit", "502", "503", "504", "connection reset", "连接重置", "网络错误", "暂时不可用",
				"service unavailable", "稍后再试")) {
			return ErrorKind.TRANSIENT;
		}
		if (containsAny(message, "400", "bad request", "invalid", "参数错误", "参数无效", "缺少参数", "格式不正确",
				"illegal argument")) {
			return ErrorKind.VALIDATION;
		}
		return ErrorKind.UNKNOWN;
	}

	private static String chainMessages(Throwable error) {
		StringBuilder builder = new StringBuilder();
		for (Throwable current = error; current != null; current = current.getCause()) {
			if (current.getMessage() != null) builder.append(current.getMessage()).append(' ');
		}
		return builder.toString().toLowerCase(Locale.ROOT);
	}

	private static boolean containsAny(String message, String... keywords) {
		for (String keyword : keywords) {
			if (message.contains(keyword)) return true;
		}
		return false;
	}
}
