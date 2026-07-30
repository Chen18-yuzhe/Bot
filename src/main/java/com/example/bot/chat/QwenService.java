package com.example.bot.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;

import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.common.MultiModalMessage;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.utils.Constants;
import com.example.bot.ApplicationException;

@Service
public class QwenService {

	private static final Logger log = LoggerFactory.getLogger(QwenService.class);
	private static final String SYSTEM_PROMPT = "你是一个运行在微信中的AI助手，请用简洁、友好的中文回答用户问题。";

	private final String apiKey;
	private final String baseUrl;
	private final DashScopeModelFallback modelFallback;

	@Autowired
	public QwenService(@Value("${aliyun.dashscope.api-key:}") String apiKey,
			@Value("${aliyun.dashscope.model:qwen3.7-plus}") String model,
			@Value("${aliyun.dashscope.base-url:}") String baseUrl,
			DashScopeModelFallback modelFallback) {
		this.apiKey = apiKey;
		this.baseUrl = baseUrl;
		this.modelFallback = modelFallback;
	}

	QwenService(String apiKey, String model, String baseUrl) {
		this(apiKey, model, baseUrl, new DashScopeModelFallback(model, "", java.time.Duration.ofMinutes(5)));
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
		if (apiKey == null || apiKey.isBlank()) {
			throw new ApplicationException(HttpStatus.SERVICE_UNAVAILABLE, "请先配置环境变量 DASHSCOPE_API_KEY");
		}

		List<Object> messages = new ArrayList<>();
		messages.add(message(Role.SYSTEM, SYSTEM_PROMPT));
		if (context != null && !context.isBlank()) {
			messages.add(message(Role.SYSTEM, context));
		}
		if (history != null) {
			for (ConversationTurn turn : history) {
				if (turn != null && turn.userMessage() != null && turn.assistantMessage() != null) {
					messages.add(message(Role.USER, turn.userMessage()));
					messages.add(message(Role.ASSISTANT, turn.assistantMessage()));
				}
			}
		}
		messages.add(message(Role.USER, question));

		configureEndpoint();
		long startedAt = System.nanoTime();
		try {
			MultiModalConversationResult response = modelFallback.call("文本对话", model -> {
				log.info("正在调用千问模型：{}", model);
				MultiModalConversationParam request = MultiModalConversationParam.builder()
						.apiKey(apiKey).model(model).messages(messages).maxTokens(1600).build();
				return new MultiModalConversation().call(request);
			});
			Object answerValue = response.getOutput().getChoices().get(0).getMessage().getContent().stream()
					.filter(part -> part.containsKey("text"))
					.map(part -> part.get("text"))
					.findFirst()
					.orElse(null);
			if (answerValue == null || answerValue.toString().isBlank()) {
				throw new IllegalStateException("千问没有返回文本内容");
			}
			String answer = answerValue.toString().trim();
			log.info("千问模型调用成功，耗时={}毫秒", (System.nanoTime() - startedAt) / 1_000_000);
			return answer;
		} catch (ApplicationException exception) {
			throw exception;
		} catch (Exception exception) {
			log.error("千问 SDK 调用失败：{}", exception.getMessage(), exception);
			throw new ApplicationException(HttpStatus.BAD_GATEWAY,
					"无法连接千问模型，请检查网络和服务地址", exception);
		}
	}

	private static MultiModalMessage message(Role role, String content) {
		return MultiModalMessage.builder()
				.role(role.getValue())
				.content(List.of(Map.of("text", content)))
				.build();
	}

	private void configureEndpoint() {
		if (baseUrl != null && !baseUrl.isBlank()) {
			String endpoint = baseUrl.trim().replace("/compatible-mode/v1", "/api/v1");
			Constants.baseHttpApiUrl = endpoint.endsWith("/") ? endpoint : endpoint + "/";
		}
	}
}
