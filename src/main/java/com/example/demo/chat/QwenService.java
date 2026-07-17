package com.example.demo.chat;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.utils.Constants;
import com.example.demo.ApplicationException;

@Service
public class QwenService {

	private static final Logger log = LoggerFactory.getLogger(QwenService.class);
	private static final String SYSTEM_PROMPT = "你是一个运行在微信中的AI助手，请用简洁、友好的中文回答用户问题。";
	private static final String ROUTING_SYSTEM_PROMPT = SYSTEM_PROMPT + "\n"
			+ "当且仅当用户明确要求查询某个城市天气且城市明确时，只返回 [WEATHER:CURRENT:城市名] 或 [WEATHER:FORECAST:城市名]。"
			+ "其他问题正常回答；用户没有说城市时直接追问城市。";

	private final String apiKey;
	private final String model;
	private final String baseUrl;

	public QwenService(@Value("${aliyun.dashscope.api-key:}") String apiKey,
			@Value("${aliyun.dashscope.model:qwen3.7-plus}") String model,
			@Value("${aliyun.dashscope.base-url:}") String baseUrl) {
		this.apiKey = apiKey;
		this.model = model;
		this.baseUrl = baseUrl;
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

		List<Message> messages = new ArrayList<>();
		messages.add(message(Role.SYSTEM, context == null || context.isBlank() ? ROUTING_SYSTEM_PROMPT : SYSTEM_PROMPT));
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
		log.info("正在调用千问模型：{}", model);
		long startedAt = System.nanoTime();
		try {
			GenerationParam request = GenerationParam.builder()
					.apiKey(apiKey)
					.model(model)
					.messages(messages)
					.resultFormat(GenerationParam.ResultFormat.MESSAGE)
					.build();
			GenerationResult response = new Generation().call(request);
			String answer = response.getOutput().getChoices().get(0).getMessage().getContent();
			log.info("千问模型调用成功，耗时={}毫秒", (System.nanoTime() - startedAt) / 1_000_000);
			return answer;
		} catch (Exception exception) {
			log.error("千问 SDK 调用失败：{}", exception.getMessage(), exception);
			throw new ApplicationException(HttpStatus.BAD_GATEWAY,
					"无法连接千问模型，请检查网络和服务地址", exception);
		}
	}

	private static Message message(Role role, String content) {
		return Message.builder().role(role.getValue()).content(content).build();
	}

	private void configureEndpoint() {
		if (baseUrl != null && !baseUrl.isBlank()) {
			String endpoint = baseUrl.trim().replace("/compatible-mode/v1", "/api/v1");
			Constants.baseHttpApiUrl = endpoint.endsWith("/") ? endpoint : endpoint + "/";
		}
	}
}
