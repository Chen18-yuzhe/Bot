package com.example.bot.model;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 本地 Mock 模型供应商：不发起任何网络请求，直接回显最后一条用户消息。
 *
 * 用于无 API Key 的演示、联调与集成测试，验证模型供应商抽象层本身可用：
 * 通过 {@code app.model.provider=mock} 切换后，聊天与 Agent Loop 均可在本地跑通，
 * 无需任何外部服务。接入新供应商时同样只需新增一个实现 + 一行配置。
 */
@Component
@ConditionalOnProperty(name = "app.model.provider", havingValue = "mock")
public class MockChatModel implements ChatModel {
	private static final Logger log = LoggerFactory.getLogger(MockChatModel.class);

	@Override
	public String name() {
		return "mock";
	}

	@Override
	public ChatResponse chat(ChatRequest request) {
		String lastUser = "";
		for (ChatMessage message : request.messages()) {
			if (message.role() == ChatRole.USER) lastUser = message.content() == null ? "" : message.content();
		}
		String text = "（Mock 模型演示模式，未连接真实大模型）我收到了你的消息：" + lastUser;
		log.info("Mock 模型推理：messages={}，tools={}", request.messages().size(), request.tools().size());
		return new ChatResponse(text, List.of(), 0, 0, "mock-model");
	}
}
