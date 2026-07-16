package com.example.demo.chat;

import java.util.List;
import java.util.ArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Service
public class QwenService {

	private static final Logger log = LoggerFactory.getLogger(QwenService.class);
	private static final String SYSTEM_PROMPT = "你是一个运行在微信中的AI助手，请用简洁、友好的中文回答用户问题。";
	private static final String ROUTING_SYSTEM_PROMPT = """
			你是一个运行在微信中的AI助手，请用简洁、友好的中文回答用户问题。
			当且仅当用户明确要求查询某个城市的天气，而且问题中能确定城市时，不要回答天气内容，必须严格只返回以下标记之一：
			实时天气：[[WEATHER:CURRENT:城市名]]
			明天、后天或未来几天的天气：[[WEATHER:FORECAST:城市名]]
			例如，北京今天天气怎么样，只返回 [[WEATHER:CURRENT:北京]]。
			用户只是询问为什么不能查询天气、是否支持天气查询、怎样使用天气功能，或者只是讨论天气查询能力时，请正常回答，绝对不要返回标记。
			如果用户确实想查天气但没有说明城市，请直接追问城市，绝对不要返回标记。
			""";

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
			throw new MissingApiKeyException("请先配置环境变量 DASHSCOPE_API_KEY");
		}
		if (baseUrl == null || baseUrl.isBlank()) {
			throw new QwenCallException("请先配置环境变量 DASHSCOPE_BASE_URL", null);
		}

		List<ChatMessage> messages = new ArrayList<>();
		messages.add(new ChatMessage("system", context == null || context.isBlank()
				? ROUTING_SYSTEM_PROMPT
				: SYSTEM_PROMPT));
		if (context != null && !context.isBlank()) {
			messages.add(new ChatMessage("system", context));
		}
		if (history != null) {
			for (ConversationTurn turn : history) {
				if (turn == null || turn.userMessage() == null || turn.assistantMessage() == null) {
					continue;
				}
				messages.add(new ChatMessage("user", turn.userMessage()));
				messages.add(new ChatMessage("assistant", turn.assistantMessage()));
			}
		}
		messages.add(new ChatMessage("user", question));

		ChatCompletionRequest request = new ChatCompletionRequest(
				model,
				messages,
				false);

		log.info("正在调用千问模型：{}", model);
		long startedAt = System.nanoTime();
		try {
			ChatCompletionResponse response = createClient().post()
					.uri("chat/completions")
					.body(request)
					.retrieve()
					.body(ChatCompletionResponse.class);
			String answer = extractAnswer(response);
			long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
			log.info("千问模型调用成功，耗时={}毫秒", durationMs);
			return answer;
		} catch (RestClientResponseException exception) {
			log.error("千问模型调用失败，状态码={}：{}", exception.getStatusCode().value(),
					exception.getResponseBodyAsString());
			throw new QwenCallException("千问模型调用失败，状态码=" + exception.getStatusCode().value(), exception);
		} catch (RestClientException exception) {
			log.error("连接千问模型失败：{}", exception.getMessage());
			throw new QwenCallException("无法连接千问模型，请检查网络和服务地址", exception);
		}
	}

	private RestClient createClient() {
		String normalizedBaseUrl = baseUrl.trim();
		if (!normalizedBaseUrl.endsWith("/")) {
			normalizedBaseUrl += "/";
		}
		return RestClient.builder()
				.baseUrl(normalizedBaseUrl)
				.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
				.build();
	}

	private String extractAnswer(ChatCompletionResponse response) {
		return response.choices().get(0).message().content();
	}

	private record ChatCompletionRequest(String model, List<ChatMessage> messages, boolean stream) {
	}

	private record ChatMessage(String role, String content) {
	}

	private record ChatCompletionResponse(List<Choice> choices) {
	}

	private record Choice(ChatMessage message) {
	}
}
