package com.example.bot.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationUsage;
import com.alibaba.dashscope.common.MultiModalMessage;
import com.alibaba.dashscope.tools.FunctionDefinition;
import com.alibaba.dashscope.tools.ToolBase;
import com.alibaba.dashscope.tools.ToolCallBase;
import com.alibaba.dashscope.tools.ToolCallFunction;
import com.alibaba.dashscope.tools.ToolFunction;
import com.alibaba.dashscope.utils.Constants;
import com.example.bot.ApplicationException;
import com.example.bot.chat.DashScopeModelFallback;
import com.google.gson.JsonParser;

/**
 * DashScope（通义千问）模型供应商实现。
 *
 * 负责把供应商无关的 {@link ChatRequest} 翻译为 DashScope SDK 调用，
 * 并把 SDK 响应翻译回 {@link ChatResponse}；复用项目原有的模型额度降级机制
 * （{@link DashScopeModelFallback}）与免费额度冷却。
 *
 * 通过 {@code app.model.provider=dashscope}（默认）启用。
 */
@Component
@ConditionalOnProperty(name = "app.model.provider", havingValue = "dashscope", matchIfMissing = true)
public class DashScopeChatModel implements ChatModel {
	private static final Logger log = LoggerFactory.getLogger(DashScopeChatModel.class);

	private final String apiKey;
	private final String baseUrl;
	private final DashScopeModelFallback modelFallback;

	public DashScopeChatModel(@Value("${aliyun.dashscope.api-key:}") String key,
			@Value("${aliyun.dashscope.base-url:}") String baseUrl,
			DashScopeModelFallback modelFallback) {
		this.apiKey = key;
		this.baseUrl = baseUrl;
		this.modelFallback = modelFallback;
	}

	@Override
	public String name() {
		return "dashscope";
	}

	@Override
	public ChatResponse chat(ChatRequest request) throws Exception {
		if (apiKey==null || apiKey.isBlank()) {
			throw new ApplicationException(HttpStatus.SERVICE_UNAVAILABLE, "请先配置环境变量 DASHSCOPE_API_KEY");
		}
		configureEndpoint();
		long startedAt = System.nanoTime();
		StringBuilder usedModel = new StringBuilder();
		MultiModalConversationResult response = modelFallback.call("模型推理", model -> {
			usedModel.setLength(0);
			usedModel.append(model);
			var requestBuilder = MultiModalConversationParam.builder()
					.apiKey(apiKey).model(model).messages(toSdkMessages(request.messages()))
					.parallelToolCalls(false).enableThinking(false).maxTokens(request.maxTokens());
			if (!request.tools().isEmpty()) {
				requestBuilder.tools(toSdkTools(request.tools()))
						.toolChoice(toSdkToolChoice(request.toolChoice(), request.tools()));
			}
			return new MultiModalConversation().call(requestBuilder.build());
		});
		MultiModalMessage assistant = response.getOutput().getChoices().get(0).getMessage();
		String text = assistant.getContent() == null ? "" : assistant.getContent().stream()
				.filter(part -> part.containsKey("text"))
				.map(part -> String.valueOf(part.get("text"))).findFirst().orElse("");
		List<ToolCall> toolCalls = new ArrayList<>();
		if (assistant.getToolCalls() != null) {
			for (ToolCallBase base : assistant.getToolCalls()) {
				if (base instanceof ToolCallFunction call) {
					toolCalls.add(new ToolCall(call.getId(), call.getFunction().getName(),
							call.getFunction().getArguments()));
				}
			}
		}
		int inputTokens = 0;
		int outputTokens = 0;
		MultiModalConversationUsage usage = response.getUsage();
		if (usage != null) {
			inputTokens = usage.getInputTokens() == null ? 0 : usage.getInputTokens();
			outputTokens = usage.getOutputTokens() == null ? 0 : usage.getOutputTokens();
		}
		log.info("模型推理完成：provider={}，model={}，输入token={}，输出token={}，工具调用数={}，耗时={}毫秒",
				name(), usedModel, inputTokens, outputTokens, toolCalls.size(),
				(System.nanoTime() - startedAt) / 1_000_000);
		return new ChatResponse(text, toolCalls, inputTokens, outputTokens, usedModel.toString());
	}

	// ---- 请求翻译：ChatMessage -> DashScope MultiModalMessage ----

	private static List<MultiModalMessage> toSdkMessages(List<ChatMessage> messages) {
		List<MultiModalMessage> result = new ArrayList<>(messages.size());
		for (ChatMessage message : messages) {
			switch (message.role()) {
				case SYSTEM, USER -> result.add(MultiModalMessage.builder().role(message.role().name().toLowerCase())
						.content(List.of(Map.of("text", message.content() == null ? "" : message.content()))).build());
				case ASSISTANT -> {
					var builder = MultiModalMessage.builder().role("assistant");
					/*
					 * DashScope's next-turn serializer iterates assistant.content even
					 * when the assistant turn contains only tool calls, so the content
					 * list must always be present (an empty text part is safe).
					 */
					builder.content(List.of(Map.of("text", message.content() == null ? "" : message.content())));
					if (!message.toolCalls().isEmpty()) {
						List<ToolCallBase> calls = new ArrayList<>();
						for (ToolCall call : message.toolCalls()) calls.add(toSdkToolCall(call));
						builder.toolCalls(calls);
					}
					result.add(builder.build());
				}
				case TOOL -> result.add(MultiModalMessage.builder().role("tool")
						.toolCallId(message.toolCallId()).name(message.name())
						.content(List.of(Map.of("text", message.content() == null ? "" : message.content()))).build());
			}
		}
		return result;
	}

	private static ToolCallFunction toSdkToolCall(ToolCall call) {
		ToolCallFunction sdk = new ToolCallFunction();
		sdk.setId(call.id());
		ToolCallFunction.CallFunction function = sdk.new CallFunction();
		function.setName(call.name());
		function.setArguments(call.argumentsJson());
		sdk.setFunction(function);
		return sdk;
	}

	// ---- 请求翻译：ToolDefinition -> DashScope ToolFunction ----

	private static List<ToolBase> toSdkTools(List<ToolDefinition> tools) {
		List<ToolBase> result = new ArrayList<>(tools.size());
		for (ToolDefinition tool : tools) {
			result.add(ToolFunction.builder().function(toSdkFunctionDefinition(tool)).build());
		}
		return result;
	}

	private static FunctionDefinition toSdkFunctionDefinition(ToolDefinition tool) {
		return FunctionDefinition.builder()
				.name(tool.name())
				.description(tool.description())
				.parameters(JsonParser.parseString(tool.parametersJson() == null ? "{}" : tool.parametersJson())
						.getAsJsonObject())
				.build();
	}

	private static Object toSdkToolChoice(ChatRequest.ToolChoice choice, List<ToolDefinition> tools) {
		return switch (choice.mode()) {
			case "none" -> "none";
			case "forced" -> {
				String name = choice.toolName();
				ToolDefinition target = tools.stream().filter(tool -> tool.name().equals(name)).findFirst()
						.orElseThrow(() -> new IllegalArgumentException("强制调用未注册的工具：" + name));
				yield ToolFunction.builder().function(toSdkFunctionDefinition(target)).build();
			}
			default -> "auto";
		};
	}

	private void configureEndpoint() {
		if (baseUrl != null && !baseUrl.isBlank()) {
			String endpoint = baseUrl.trim().replace("/compatible-mode/v1", "/api/v1");
			Constants.baseHttpApiUrl = endpoint.endsWith("/") ? endpoint : endpoint + "/";
		}
	}
}
