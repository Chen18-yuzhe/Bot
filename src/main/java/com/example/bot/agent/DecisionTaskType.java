package com.example.bot.agent;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

/** Structured option comparison with explicit criteria, uncertainty and trade-offs. */
@Component
public class DecisionTaskType implements AgentTaskType {
	public static final String NAME = "decision";
	private static final Set<String> TOOLS = Set.of(
			"search_web", "search_knowledge_base", "scientific_calculator");

	@Override public String name() { return NAME; }
	@Override public int routingPriority() { return 50; }
	@Override public Set<String> allowedTools() { return TOOLS; }
	@Override public int maxSteps(int configuredDefault) { return Math.max(10, configuredDefault); }
	@Override public long tokenBudget(long configuredDefault) { return Math.max(60_000, configuredDefault); }
	@Override public Duration timeout(Duration configuredDefault) {
		return configuredDefault.compareTo(Duration.ofMinutes(6)) >= 0
				? configuredDefault : Duration.ofMinutes(6);
	}

	@Override
	public boolean supports(String requestContext) {
		String value = normalize(requestContext);
		return containsAny(value, "帮我选型", "技术选型", "方案选型", "决策分析", "决策矩阵", "采购对比",
				"候选方案对比", "应该选哪个", "帮我做决策", "decision matrix", "technology selection");
	}

	@Override
	public String validateCompletion(AgentTask task, String answer) {
		String text = answer == null ? "" : answer;
		if (!containsAny(text, "评价标准", "决策标准", "权重", "维度")) return "决策报告缺少明确的评价标准。";
		if (!containsAny(text, "推荐", "建议选择", "结论")) return "决策报告缺少明确结论。";
		if (!containsAny(text, "风险", "代价", "不确定")) return "决策报告缺少风险或不确定性说明。";
		return null;
	}

	@Override
	public String systemPrompt(AgentTask task) {
		return """
				你是一个结构化决策 Agent。目标是让用户看清取舍，并给出可追溯的推荐。
				执行规则：
				1. 明确候选项、硬约束、评价维度和权重；缺少信息时采用可解释的默认权重并标注。
				2. 内部约束和历史方案从个人知识库检索；价格、版本、生态和外部事实通过联网搜索核验。
				3. 对每个候选项使用同一套标准评分，不得先有结论再选择证据。
				4. 数值计算使用计算工具；事实、假设和推断分开标注。
				5. 做敏感性分析：指出哪个权重或假设变化会导致推荐翻转。
				6. 最终输出：约束、决策矩阵、推荐结论、适用边界、风险、不确定项和下一步验证清单。
				""";
	}

	private static String normalize(String value) {
		return value == null ? "" : value.toLowerCase(Locale.ROOT);
	}
	private static boolean containsAny(String value, String... words) {
		for (String word : words) if (value.contains(word)) return true;
		return false;
	}
}
