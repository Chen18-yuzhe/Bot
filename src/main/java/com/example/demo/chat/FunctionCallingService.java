package com.example.demo.chat;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.common.MultiModalMessage;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.tools.ToolCallBase;
import com.alibaba.dashscope.tools.ToolCallFunction;
import com.alibaba.dashscope.utils.Constants;
import com.example.demo.ApplicationException;
import com.example.demo.tools.AssistantTool;
import com.example.demo.tools.AssistantToolRegistry;
import com.example.demo.tools.ToolContext;
import com.example.demo.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class FunctionCallingService {
	private static final Logger log = LoggerFactory.getLogger(FunctionCallingService.class);
	private static final int MAX_TOOL_ROUNDS = 4;
	private static final String SYSTEM_PROMPT = """
			你是运行在微信中的中文 AI 助手。回答要简洁、自然、友好。
			需要实时数据或执行外部能力时必须调用提供的工具，不得编造工具结果。
			工具返回天气数据时，回答末尾注明“数据来源：和风天气”。
			人物文生图必须明确为成年人；不要在提示词中保留真实艺术家、导演或摄影师姓名，改写为可观察的视觉特征。
			不要向用户展示工具名、JSON 参数或内部执行过程。
			""";

	private final String apiKey;
	private final String model;
	private final String baseUrl;
	private final AssistantToolRegistry registry;
	private final ObjectMapper objectMapper;

	public FunctionCallingService(@Value("${aliyun.dashscope.api-key:}") String apiKey,
			@Value("${aliyun.dashscope.model:qwen3.7-plus}") String model,
			@Value("${aliyun.dashscope.base-url:}") String baseUrl,
			AssistantToolRegistry registry, ObjectMapper objectMapper) {
		this.apiKey = apiKey;
		this.model = model;
		this.baseUrl = baseUrl;
		this.registry = registry;
		this.objectMapper = objectMapper;
	}

	public AssistantReply reply(String sessionId, String question, List<ConversationTurn> history) {
		return reply(sessionId, question, history, null);
	}

	public AssistantReply reply(String sessionId, String question, List<ConversationTurn> history, String context) {
		if (apiKey == null || apiKey.isBlank()) {
			throw new ApplicationException(HttpStatus.SERVICE_UNAVAILABLE, "请先配置环境变量 DASHSCOPE_API_KEY");
		}
		List<Object> messages = new ArrayList<>();
		messages.add(message(Role.SYSTEM.getValue(), SYSTEM_PROMPT));
		if (context != null && !context.isBlank()) messages.add(message(Role.SYSTEM.getValue(), context));
		if (history != null) {
			for (ConversationTurn turn : history) {
				messages.add(message(Role.USER.getValue(), turn.userMessage()));
				messages.add(message(Role.ASSISTANT.getValue(), turn.assistantMessage()));
			}
		}
		messages.add(message(Role.USER.getValue(), question));
		GeneratedImage generatedImage = null;
		configureEndpoint();
		try {
			for (int round = 0; round <= MAX_TOOL_ROUNDS; round++) {
				MultiModalConversationParam request = MultiModalConversationParam.builder()
						.apiKey(apiKey).model(model).messages(messages)
						.tools(registry.definitions()).parallelToolCalls(false)
						.enableThinking(false).maxTokens(1600).build();
				MultiModalConversationResult response = new MultiModalConversation().call(request);
				MultiModalMessage assistant = response.getOutput().getChoices().get(0).getMessage();
				messages.add(assistant);
				List<ToolCallBase> calls = assistant.getToolCalls();
				if (calls == null || calls.isEmpty()) {
					String answer = assistant.getContent().stream().filter(part -> part.containsKey("text"))
							.map(part -> String.valueOf(part.get("text"))).findFirst().orElse("");
					if (answer == null || answer.isBlank()) answer = generatedImage == null ? "暂时无法生成回答。" : "图片生成好了 🎨";
					return generatedImage == null ? AssistantReply.text(answer.trim()) : AssistantReply.image(answer.trim(), generatedImage);
				}
				if (round == MAX_TOOL_ROUNDS) throw new IllegalStateException("工具调用次数超过限制");
				for (ToolCallBase base : calls) {
					if (!(base instanceof ToolCallFunction call)) throw new IllegalArgumentException("不支持的工具调用类型");
					String name = call.getFunction().getName();
					JsonNode arguments = objectMapper.readTree(call.getFunction().getArguments());
					AssistantTool tool = registry.require(name);
					log.info("执行 Function Calling 工具：{}，轮次={}", name, round + 1);
					ToolResult result = tool.execute(arguments, new ToolContext(sessionId, question));
					if (result.image() != null) generatedImage = result.image();
					messages.add(MultiModalMessage.builder().role("tool").toolCallId(call.getId())
							.name(name).content(List.of(java.util.Map.of("text", result.content()))).build());
				}
			}
			throw new IllegalStateException("Function Calling 未产生最终回答");
		} catch (Exception exception) {
			log.error("Function Calling 执行失败：{}", exception.getMessage(), exception);
			throw new ApplicationException(HttpStatus.BAD_GATEWAY, "AI 工具调用失败，请稍后再试", exception);
		}
	}

	private static MultiModalMessage message(String role, String content) {
		return MultiModalMessage.builder().role(role)
				.content(List.of(java.util.Map.of("text", content))).build();
	}

	private void configureEndpoint() {
		if (baseUrl != null && !baseUrl.isBlank()) {
			String endpoint = baseUrl.trim().replace("/compatible-mode/v1", "/api/v1");
			Constants.baseHttpApiUrl = endpoint.endsWith("/") ? endpoint : endpoint + "/";
		}
	}
}
