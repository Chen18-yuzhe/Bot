package com.example.bot.chat;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.example.bot.ApplicationException;
import com.example.bot.model.ChatMessage;
import com.example.bot.model.ChatModel;
import com.example.bot.model.ChatRequest;
import com.example.bot.model.ChatResponse;

/**
 * 普通对话入口。只依赖模型供应商抽象层 {@link ChatModel}，
 * 不直接依赖任何供应商 SDK；通过 app.model.provider 切换供应商。
 */
@Service
public class QwenService {

	private static final Logger log = LoggerFactory.getLogger(QwenService.class);
	private static final String SYSTEM_PROMPT = "你是一个运行在微信中的AI助手，请用简洁、友好的中文回答用户问题。";

	private final ChatModel chatModel;

	public QwenService(ChatModel chatModel) {
		this.chatModel = chatModel;
	}

	public String chat(String question) {
		return chatWithContext(question, null, List.of());
	}

	public String chat(String question, List<ConversationTurn> history) {
		return chatWithContext(question, null, history);
	}

	public String chatWithContext(String question, String context) {
		return chatWithContext(question, context, List.of());
	}

	public String chatWithContext(String question, String context, List<ConversationTurn> history) {
		List<ChatMessage> messages = new ArrayList<>();
		messages.add(ChatMessage.system(SYSTEM_PROMPT));
		if (context != null && !context.isBlank()) {
			messages.add(ChatMessage.system(context));
		}
		if (history != null) {
			for (ConversationTurn turn : history) {
				if (turn != null && turn.userMessage() != null && turn.assistantMessage() != null) {
					messages.add(ChatMessage.user(turn.userMessage()));
					messages.add(ChatMessage.assistant(turn.assistantMessage(), List.of()));
				}
			}
		}
		messages.add(ChatMessage.user(question));

		long startedAt = System.nanoTime();
		try {
			ChatResponse response = chatModel.chat(
					new ChatRequest(messages, List.of(), 1600, ChatRequest.ToolChoice.none()));
			if (response.text() == null || response.text().isBlank()) {
				throw new IllegalStateException("模型没有返回文本内容");
			}
			String answer = response.text().trim();
			log.info("模型调用成功，provider={}，model={}，耗时={}毫秒",
					chatModel.name(), response.model(), (System.nanoTime() - startedAt) / 1_000_000);
			return answer;
		} catch (ApplicationException exception) {
			throw exception;
		} catch (Exception exception) {
			log.error("模型调用失败：{}", exception.getMessage(), exception);
			throw new ApplicationException(HttpStatus.BAD_GATEWAY,
					"无法连接模型服务，请检查网络和服务地址", exception);
		}
	}
}
