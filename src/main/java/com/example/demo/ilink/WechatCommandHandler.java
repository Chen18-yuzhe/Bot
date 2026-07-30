package com.example.demo.ilink;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class WechatCommandHandler {

	static final String VERSION = "1.1";

	private static final String WELCOME = """
			你好，我是微信 AI 助手。
			你可以直接向我提问，也可以发送以下命令：
			/help 查看帮助
			/status 查看服务状态
			/version 查看当前版本
			/reset 清除当前对话记忆
			/agent 切换到自主任务模式
			/chat 切回即时聊天模式
			""".strip();

	private final String qwenModel;
	private final String qwenApiKey;
	private final String qweatherApiKey;
	private final Set<String> welcomedUsers = ConcurrentHashMap.newKeySet();

	public WechatCommandHandler(
			@Value("${aliyun.dashscope.model:qwen3.7-plus}") String qwenModel,
			@Value("${aliyun.dashscope.api-key:}") String qwenApiKey,
			@Value("${qweather.api-key:}") String qweatherApiKey) {
		this.qwenModel = qwenModel;
		this.qwenApiKey = qwenApiKey;
		this.qweatherApiKey = qweatherApiKey;
	}

	String handle(String input) {
		return switch (input.trim().toLowerCase()) {
			case "/help" -> WELCOME;
			case "/version" -> "当前版本：" + VERSION;
			case "/status" -> buildStatus();
			default -> null;
		};
	}

	String addWelcomeForFirstContact(String userId, String reply) {
		if (userId != null && welcomedUsers.add(userId)) {
			if (WELCOME.equals(reply)) {
				return reply;
			}
			return WELCOME + "\n\n" + reply;
		}
		return reply;
	}

	private String buildStatus() {
		return """
			服务状态：运行中
			微信接入：已启用
			版本：%s
			千问模型：%s
			千问 API Key：%s
			和风天气 API Key：%s
			""".formatted(
			VERSION,
			qwenModel,
			isConfigured(qwenApiKey) ? "已配置" : "未配置",
			isConfigured(qweatherApiKey) ? "已配置" : "未配置").strip();
	}

	private static boolean isConfigured(String value) {
		return value != null && !value.isBlank();
	}
}
