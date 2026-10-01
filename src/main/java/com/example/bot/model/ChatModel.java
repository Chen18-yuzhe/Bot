package com.example.bot.model;

/**
 * 模型供应商抽象层：所有 LLM 推理（普通对话、Function Calling、Agent Loop）
 * 只依赖本接口与 model 包内的纯数据类型，不依赖任何供应商 SDK。
 *
 * 接入新供应商只需实现本接口并注册为 Bean：
 * <pre>
 * &#64;Component
 * &#64;ConditionalOnProperty(name = "app.model.provider", havingValue = "openai")
 * public class OpenAiChatModel implements ChatModel { ... }
 * </pre>
 * 运行时通过 {@code app.model.provider}（或环境变量 {@code APP_MODEL_PROVIDER}）切换。
 */
public interface ChatModel {

	/** 供应商标识，用于日志与指标，例如 dashscope / mock。 */
	String name();

	/** 执行一轮推理。供应商实现负责把请求翻译为自身 SDK 调用，并把响应翻译回 ChatResponse。 */
	ChatResponse chat(ChatRequest request) throws Exception;
}
