package com.example.bot.agent;

import java.time.Duration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/** Evidence-first research task that combines public web sources and private RAG. */
@Component
public class DeepResearchTaskType implements AgentTaskType {
	public static final String NAME = "research";
	private static final Pattern URL = Pattern.compile("https?://[^\\s<>\\]\\[\"']+",
			Pattern.CASE_INSENSITIVE);
	private static final Set<String> TOOLS = Set.of(
			"search_web", "search_knowledge_base", "manage_bilibili_favorites",
			"scientific_calculator", "process_document");

	@Override public String name() { return NAME; }
	@Override public int routingPriority() { return 70; }
	@Override public Set<String> allowedTools() { return TOOLS; }
	@Override public int maxSteps(int configuredDefault) { return Math.max(12, configuredDefault); }
	@Override public long tokenBudget(long configuredDefault) { return Math.max(80_000, configuredDefault); }
	@Override public Duration timeout(Duration configuredDefault) {
		return configuredDefault.compareTo(Duration.ofMinutes(8)) >= 0
				? configuredDefault : Duration.ofMinutes(8);
	}

	@Override
	public boolean supports(String requestContext) {
		String value = normalize(requestContext);
		return containsAny(value, "深度调研", "研究报告", "行业报告", "竞品分析", "市场分析",
				"资料调研", "技术调研", "可行性分析", "research", "competitive analysis");
	}

	@Override
	public String validateCompletion(AgentTask task, String answer) {
		if (answer == null || answer.isBlank()) return "调研报告不能为空。";
		Set<String> sourced = new HashSet<>();
		for (AgentTranscript.Entry entry : task.getTranscript()) {
			if (!"tool".equals(entry.role()) || entry.text() == null) continue;
			var matcher = URL.matcher(entry.text());
			while (matcher.find()) sourced.add(normalizeUrl(matcher.group()));
		}
		var answerUrls = URL.matcher(answer);
		while (answerUrls.find()) {
			String url = normalizeUrl(answerUrls.group());
			if (!sourced.contains(url)) {
				return "报告包含未经工具结果验证的链接：" + url + "。删除该链接或重新检索来源。";
			}
		}
		return null;
	}

	@Override
	public String systemPrompt(AgentTask task) {
		return """
				你是一个证据优先的深度调研 Agent。目标是交付可供决策的研究报告，而不是罗列搜索结果。
				执行规则：
				1. 先把问题拆成 3 至 6 个互斥且完整的子问题，再逐项检索。
				2. 项目内部事实优先检索个人知识库；时效性事实、市场信息和外部观点使用联网搜索。
				3. 关键结论至少交叉验证两个独立来源；找不到第二来源时明确标为“单一来源，待核验”。
				4. 区分事实、来源观点与自己的推断。冲突信息并列展示，不要静默选择。
				5. 不得编造 URL、数字、发布日期或引用。最终报告中的链接必须逐字来自工具结果。
				6. 信息足够后立即停止搜索，避免重复检索。
				7. 最终输出：执行摘要、关键发现、证据表、风险与未知项、建议行动；每项关键事实注明来源与查询日期。
				""";
	}

	private static String normalize(String value) {
		return value == null ? "" : value.toLowerCase(Locale.ROOT);
	}
	private static boolean containsAny(String value, String... words) {
		for (String word : words) if (value.contains(word)) return true;
		return false;
	}
	private static String normalizeUrl(String value) {
		String url = value;
		while (url.endsWith(".") || url.endsWith(",") || url.endsWith("。") || url.endsWith("，")
				|| url.endsWith(")") || url.endsWith("）")) {
			url = url.substring(0, url.length() - 1);
		}
		return url;
	}
}
