package com.example.bot.agent;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

/** Plans sustainable routines while treating persisted health facts as constraints, not diagnoses. */
@Component
public class HealthLifestylePlanTaskType implements AgentTaskType {
	public static final String NAME = "health-lifestyle-plan";
	private static final Set<String> TOOLS = Set.of(
			"lookup_daily_info", "manage_reminder", "remember_user_profile", "scientific_calculator", "search_web");

	@Override public String name() { return NAME; }
	@Override public int routingPriority() { return 90; }
	@Override public Set<String> allowedTools() { return TOOLS; }
	@Override public boolean autoStart(String requestContext) { return supports(requestContext); }
	@Override public int maxSteps(int configuredDefault) { return Math.max(8, configuredDefault); }
	@Override public long tokenBudget(long configuredDefault) { return Math.max(30_000, configuredDefault); }
	@Override public Duration timeout(Duration configuredDefault) {
		return configuredDefault.compareTo(Duration.ofMinutes(5)) >= 0 ? configuredDefault : Duration.ofMinutes(5);
	}

	@Override
	public boolean supports(String requestContext) {
		String value = requestContext == null ? "" : requestContext.toLowerCase(Locale.ROOT);
		boolean plan = containsAny(value, "计划", "方案", "安排", "plan", "routine");
		boolean lifestyle = containsAny(value, "高血压", "血压", "健康", "饮食", "营养", "运动", "健身", "睡眠", "作息",
				"减脂", "减重", "慢病", "hypertension", "blood pressure", "diet", "exercise", "sleep");
		return plan && lifestyle;
	}

	@Override
	public String systemPrompt(AgentTask task) {
		return """
				你是健康生活计划 Agent。你要将用户已经明确保存的长期资料（如健康状况、过敏、作息、偏好和限制）视为计划约束；
				这些资料会随任务目标一并提供。只使用与本计划相关的资料，不要复述无关的个人信息，也不要把资料当作医学诊断。

				执行规则：
				1. 先提取目标、可用时间、既有作息和已保存的健康/过敏限制。高血压、用药、孕期、急性症状或其他慢病等关键信息不完整时，明确列出假设并建议与医生或药师确认。
				2. 输出可执行、低风险的日常计划：作息时间块、饮食原则、适量活动、逐步调整方式、每周复盘和需要就医的警示。不得诊断、开药、替代医疗建议或承诺疗效。
				3. 涉及慢病的具体医学指标、禁忌或药物相互作用时，必须先用 search_web 查询权威来源并保留来源；不得仅凭记忆给出结论。
				4. 用户要求特定食物营养或菜谱时，用 lookup_daily_info 核验；创建提醒前必须由用户明确授权，不得默认创建。
				5. 最终答复要说明哪些长期资料被纳入计划，以及还缺少哪些关键资料；避免暴露内部工具或原始资料格式。
				""";
	}

	private static boolean containsAny(String value, String... keywords) {
		for (String keyword : keywords) if (value.contains(keyword)) return true;
		return false;
	}
}
