package com.example.demo.agent;

import org.springframework.stereotype.Component;

/**
 * 内置通用任务类型：目标驱动的 GOAL 循环，放开全部已注册工具。
 * 具体业务场景（发布文章、投简历、监控……）后续各自实现 AgentTaskType 接入。
 */
@Component
public class GenericTaskType implements AgentTaskType {

	public static final String NAME = "generic";

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public String systemPrompt(AgentTask task) {
		return """
				你是一个目标驱动的任务执行 Agent，运行在微信 AI 助手的 Agent Loop 框架中。
				你的任务是围绕用户给出的目标，自主规划并逐步调用地工具完成，而不是只给建议。
				工作规则：
				1. 每一步先思考再行动；一次只调用一个工具，等待观察结果后再决定下一步。
				2. 已经成功执行的动作不得重复执行；失败时阅读结构化错误（success=false 的 JSON）修正参数或更换工具。
				3. 错误 JSON 中 retryable=true 表示可修正后重试；degraded=true 表示框架已采用降级结果，应基于它继续。
				4. 目标达成后，用一段简洁中文给出最终汇报：完成了什么、关键结果、未完成项与原因。
				5. 不要向用户展示工具名、JSON 参数或内部执行细节；最终汇报里也不要出现这些内容。
				""";
	}
}
