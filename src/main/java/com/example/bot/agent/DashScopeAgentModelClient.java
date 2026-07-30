package com.example.bot.agent;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationUsage;
import com.alibaba.dashscope.common.MultiModalMessage;
import com.alibaba.dashscope.tools.ToolBase;
import com.alibaba.dashscope.tools.ToolCallBase;
import com.alibaba.dashscope.tools.ToolCallFunction;
import com.alibaba.dashscope.utils.Constants;
import com.example.bot.ApplicationException;
import com.example.bot.chat.DashScopeModelFallback;

/**
 * 基于 DashScope 的模型推理实现，复用项目的模型额度降级机制。
 */
@Component
public class DashScopeAgentModelClient implements AgentModelClient {
	private static final Logger log = LoggerFactory.getLogger(DashScopeAgentModelClient.class);

	private final String apiKey;
	private final String baseUrl;
	private final DashScopeModelFallback modelFallback;

	public DashScopeAgentModelClient(@Value("${aliyun.dashscope.api-key:}") String apiKey,
			@Value("${aliyun.dashscope.base-url:}") String baseUrl,
			DashScopeModelFallback modelFallback) {
		this.apiKey = apiKey;
		this.baseUrl = baseUrl;
		this.modelFallback = modelFallback;
	}

	@Override
	public AgentModelTurn call(List<Object> messages, List<ToolBase> tools, int maxTokens) throws Exception {
		if (apiKey == null || apiKey.isBlank()) {
			throw new ApplicationException(HttpStatus.SERVICE_UNAVAILABLE, "请先配置环境变量 DASHSCOPE_API_KEY");
		}
		configureEndpoint();
		long startedAt = System.nanoTime();
		StringBuilder usedModel = new StringBuilder();
		MultiModalConversationResult response = modelFallback.call("Agent Loop", model -> {
			usedModel.setLength(0);
			usedModel.append(model);
			var requestBuilder = MultiModalConversationParam.builder()
					.apiKey(apiKey).model(model).messages(messages)
					.parallelToolCalls(false).enableThinking(false).maxTokens(maxTokens);
			if (tools != null && !tools.isEmpty()) {
				requestBuilder.tools(tools).toolChoice("auto");
			}
			return new MultiModalConversation().call(requestBuilder.build());
		});
		MultiModalMessage assistant = response.getOutput().getChoices().get(0).getMessage();
		String text = assistant.getContent() == null ? "" : assistant.getContent().stream()
				.filter(part -> part.containsKey("text"))
				.map(part -> String.valueOf(part.get("text"))).findFirst().orElse("");
		List<ToolCallFunction> toolCalls = new ArrayList<>();
		if (assistant.getToolCalls() != null) {
			for (ToolCallBase base : assistant.getToolCalls()) {
				if (base instanceof ToolCallFunction call) toolCalls.add(call);
			}
		}
		int inputTokens = 0;
		int outputTokens = 0;
		MultiModalConversationUsage usage = response.getUsage();
		if (usage != null) {
			inputTokens = usage.getInputTokens() == null ? 0 : usage.getInputTokens();
			outputTokens = usage.getOutputTokens() == null ? 0 : usage.getOutputTokens();
		}
		log.info("Agent 推理完成：模型={}，输入token={}，输出token={}，工具调用数={}，耗时={}毫秒",
				usedModel, inputTokens, outputTokens, toolCalls.size(),
				(System.nanoTime() - startedAt) / 1_000_000);
		return new AgentModelTurn(text, toolCalls, inputTokens, outputTokens, usedModel.toString());
	}

	private void configureEndpoint() {
		if (baseUrl != null && !baseUrl.isBlank()) {
			String endpoint = baseUrl.trim().replace("/compatible-mode/v1", "/api/v1");
			Constants.baseHttpApiUrl = endpoint.endsWith("/") ? endpoint : endpoint + "/";
		}
	}
}
