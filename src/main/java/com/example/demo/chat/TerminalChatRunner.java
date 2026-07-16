package com.example.demo.chat;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Scanner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "assistant.console-enabled", havingValue = "true", matchIfMissing = true)
public class TerminalChatRunner {

	public static final String VERSION = "1.1";

	private static final Logger log = LoggerFactory.getLogger(TerminalChatRunner.class);

	private final AssistantService assistantService;

	@Value("${aliyun.dashscope.api-key:}")
	private String qwenApiKey;

	@Value("${aliyun.dashscope.model:qwen3.7-plus}")
	private String qwenModel;

	@Value("${qweather.api-key:}")
	private String qweatherApiKey;

	public TerminalChatRunner(AssistantService assistantService) {
		this.assistantService = assistantService;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void startTerminalChat() {
		Thread.ofPlatform()
				.name("terminal-chat")
				.start(this::runChatLoop);
	}

	private void runChatLoop() {
		System.out.println();
		System.out.println("========== 微信 AI 助手终端测试 ==========");
		System.out.println("请输入问题；输入 /help 查看帮助，输入 exit 或 quit 退出。");

		try (Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8)) {
			while (true) {
				System.out.print("\n你：");
				System.out.flush();

				String question;
				try {
					question = scanner.nextLine().trim();
				} catch (NoSuchElementException exception) {
					break;
				}

				if (isExitCommand(question)) {
					break;
				}
				if (question.isBlank()) {
					continue;
				}

				String commandResult = handleCommand(question);
				if (commandResult != null) {
					System.out.println(commandResult);
					continue;
				}

				try {
					String answer = assistantService.chat(question);
					System.out.println("AI：" + answer);
				} catch (RuntimeException exception) {
					log.warn("回答失败：{}", exception.getMessage());
					System.out.println("AI：抱歉，本次回答失败，请检查配置后重试。");
				}
			}
		}

		System.out.println("终端问答已结束。");
	}

	/** 处理终端内置命令；普通问题返回 null，交给 AssistantService。 */
	String handleCommand(String input) {
		return switch (input.toLowerCase(Locale.ROOT)) {
			case "/help" -> """
					可用命令：
					/help     显示帮助信息
					/version  显示程序版本
					/status   显示程序状态和配置状态
					exit      退出终端问答
					直接输入问题即可调用千问；天气问题会自动查询和风天气。
					""";
			case "/version" -> "当前版本：" + VERSION;
			case "/status" -> buildStatus();
			default -> input.startsWith("/") ? "未知命令：" + input + "，请输入 /help 查看帮助。" : null;
		};
	}

	private String buildStatus() {
		return """
				程序状态：运行中
				版本：%s
				千问模型：%s
				千问 API Key：%s
				和风天气 API Key：%s
				终端问答：已启用
				""".formatted(
				VERSION,
				qwenModel,
				isConfigured(qwenApiKey) ? "已配置" : "未配置",
				isConfigured(qweatherApiKey) ? "已配置" : "未配置");
	}

	private boolean isConfigured(String value) {
		return value != null && !value.isBlank();
	}

	private boolean isExitCommand(String input) {
		return "exit".equalsIgnoreCase(input)
				|| "quit".equalsIgnoreCase(input)
				|| "/exit".equalsIgnoreCase(input)
				|| "退出".equals(input);
	}
}
