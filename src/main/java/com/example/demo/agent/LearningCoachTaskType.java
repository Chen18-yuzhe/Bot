package com.example.demo.agent;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

/** Produces a personalized learn-practice-assess loop from RAG and web evidence. */
@Component
public class LearningCoachTaskType implements AgentTaskType {
	public static final String NAME = "learning";
	private static final Set<String> TOOLS = Set.of(
			"search_knowledge_base", "search_web", "scientific_calculator", "manage_reminder");

	@Override public String name() { return NAME; }
	@Override public int routingPriority() { return 60; }
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
		return containsAny(value, "学习计划", "复习计划", "备考计划", "学习路线", "学习路径",
				"知识体系", "训练计划", "带我学习", "课程计划", "study plan", "learning roadmap");
	}

	@Override
	public String validateCompletion(AgentTask task, String answer) {
		String text = answer == null ? "" : answer;
		if (!containsAny(text, "学习目标", "目标")) return "学习方案缺少可验收的学习目标。";
		if (!containsAny(text, "练习", "实战", "测验")) return "学习方案缺少主动练习或测验。";
		if (!containsAny(text, "验收", "检查", "评估", "掌握标准")) return "学习方案缺少阶段验收标准。";
		return null;
	}

	@Override
	public String systemPrompt(AgentTask task) {
		return """
				你是一个以掌握为目标的学习教练 Agent。你要设计“学习 → 练习 → 反馈 → 复习”的闭环，而不是只给资料清单。
				执行规则：
				1. 从目标、当前水平、可用时间和截止日期提取约束；缺少关键约束时先做合理假设并醒目标注。
				2. 优先从个人知识库检索用户已有资料，再用联网搜索补齐时效性内容。
				3. 把主题拆为循序渐进的知识树，每阶段包含核心概念、最小练习、常见误区和掌握标准。
				4. 使用费曼复述、间隔复习和检索练习；至少给出一次可立即作答的小测验。
				5. 只有用户明确要求时才创建提醒；创建前说明频率与内容。
				6. 最终输出：学习目标、路线图、本周行动、练习与测验、验收标准、资源依据和风险调整。
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
