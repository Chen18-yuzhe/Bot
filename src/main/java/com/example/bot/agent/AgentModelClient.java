package com.example.bot.agent;

import java.util.List;

import com.alibaba.dashscope.tools.ToolBase;
import com.alibaba.dashscope.tools.ToolCallFunction;

/**
 * Agent Loop 的模型推理入口。抽象为接口便于测试替换（引擎单测使用假模型）。
 */
public interface AgentModelClient {

	/**
	 * 执行一轮推理。
	 *
	 * @param messages  系统提示 + 工作对话记录重建的 SDK 消息
	 * @param tools     本轮允许模型选择的工具定义
	 * @param maxTokens 单次回复的最大输出 token
	 * @return 模型回复：文本、工具调用与本轮 token 用量
	 */
	AgentModelTurn call(List<Object> messages, List<ToolBase> tools, int maxTokens) throws Exception;

	/**
	 * @param text          模型本轮的文本输出（可能为空，表示只请求了工具）
	 * @param toolCalls     模型请求的工具调用（空列表表示给出最终回答）
	 * @param inputTokens   本轮输入 token（未知时为 0）
	 * @param outputTokens  本轮输出 token（未知时为 0）
	 * @param model         实际使用的模型名
	 */
	record AgentModelTurn(String text, List<ToolCallFunction> toolCalls, int inputTokens, int outputTokens,
			String model) {
		public AgentModelTurn {
			toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
		}

		public int totalTokens() {
			return inputTokens + outputTokens;
		}

		public boolean requestsTools() {
			return !toolCalls.isEmpty();
		}
	}
}
