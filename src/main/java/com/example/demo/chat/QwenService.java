package com.example.demo.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;

import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.common.MultiModalMessage;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.utils.Constants;
import com.example.demo.ApplicationException;

@Service
public class QwenService {

	private static final Logger log = LoggerFactory.getLogger(QwenService.class);
	private static final String SYSTEM_PROMPT = "你是一个运行在微信中的AI助手，请用简洁、友好的中文回答用户问题。";
	private static final String ROUTING_SYSTEM_PROMPT = SYSTEM_PROMPT + "\n"
			+ "当且仅当用户明确要求查询某个城市天气且城市明确时，只返回 [WEATHER:CURRENT:城市名] 或 [WEATHER:FORECAST:城市名]。"
			+ "当且仅当用户明确要求生成、绘制、画一张图片时，只返回 [IMAGE:适合文生图模型的完整中文提示词]。"
			+ "整理文生图提示词时：人物写真必须明确为成年人；不要保留真实导演、艺术家或摄影师姓名，改写成可观察的构图、色彩、光线和镜头语言；不要添加用户未要求的敏感内容。"
			+ "仅仅讨论图片、询问图片知识或发送已有图片不属于文生图请求。"
			+ "天气和文生图标记不能同时返回。其他问题正常回答；天气问题没有城市时直接追问城市。";

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

		List<Object> messages = new ArrayList<>();
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
			MultiModalConversationParam request = MultiModalConversationParam.builder()
					.apiKey(apiKey)
					.model(model)
					.messages(messages)
					.maxTokens(1600)
					.build();
			MultiModalConversationResult response = new MultiModalConversation().call(request);
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
