package com.example.demo.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.demo.monitoring.ToolMetricsService;
import com.example.demo.tools.AssistantTool;
import com.example.demo.tools.AssistantToolRegistry;
import com.example.demo.tools.ToolContext;
import com.example.demo.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Agent Loop 的工具执行器：调用已注册的 AssistantTool，
 * 把成功与失败都收敛为结构化结果，并记录工具指标（与现有 Function Calling 一致）。
 */
@Component
public class AgentToolExecutor {
	private static final Logger log = LoggerFactory.getLogger(AgentToolExecutor.class);

	private final AssistantToolRegistry registry;
	private final ObjectMapper objectMapper;
	private final ErrorClassifier classifier;
	private final ToolMetricsService metrics;

	public AgentToolExecutor(AssistantToolRegistry registry, ObjectMapper objectMapper,
			ErrorClassifier classifier, ToolMetricsService metrics) {
		this.registry = registry;
		this.objectMapper = objectMapper;
		this.classifier = classifier;
		this.metrics = metrics;
	}

	/** 工具调用结果：成功、失败（已分类，可能带降级产物）。 */
	public record ToolOutcome(boolean success, String content, ErrorKind errorKind, String degradedContent,
			String humanPrompt, HumanRequest.Kind humanKind) {

		public static ToolOutcome ok(String content) {
			return new ToolOutcome(true, content, null, null, null, null);
		}

		public static ToolOutcome failed(String content, ErrorKind kind, AgentToolException declared) {
			String degraded = declared == null ? null : declared.degradedContent();
			String humanPrompt = declared == null ? null : declared.humanPrompt();
			HumanRequest.Kind humanKind = declared == null ? null : declared.humanKind();
			return new ToolOutcome(false, content, kind, degraded, humanPrompt, humanKind);
		}

		public boolean hasDegradedContent() {
			return degradedContent != null && !degradedContent.isBlank();
		}
	}

	/**
	 * 执行一次工具调用。所有异常都在此归一化：
	 * AgentToolException 以显式声明为准，其余异常由 ErrorClassifier 启发式分类。
	 */
	public ToolOutcome execute(String toolName, String argumentsJson, AgentTask task) {
		ToolMetricsService.Invocation invocation = metrics.start(toolName, task.getSessionId());
		try {
			AssistantTool tool = registry.require(toolName);
			JsonNode arguments = argumentsJson == null || argumentsJson.isBlank()
					? objectMapper.createObjectNode() : objectMapper.readTree(argumentsJson);
			if ("travel_purchase_entry".equals(toolName)) validatePurchaseEntryEvidence(arguments, task);
			if ("travel_purchase_links".equals(toolName)) validatePurchaseLinksEvidence(arguments, task);
			ToolResult result = tool.execute(arguments,
					new ToolContext(task.getSessionId(), task.getGoal()));
			metrics.success(invocation);
			return ToolOutcome.ok(result.content());
		} catch (Exception error) {
			metrics.failure(invocation, error.getMessage());
			AgentToolException declared = findDeclared(error);
			ErrorKind kind = declared != null ? declared.kind() : classifier.classify(error);
			String message = safeMessage(error);
			log.warn("Agent 工具执行失败：工具={}，类别={}，原因={}", toolName, kind, message);
			return ToolOutcome.failed(message, kind, declared);
		}
	}

	/**
	 * A purchase-entry card is user-facing evidence, not a model suggestion.
	 * Require the fields that make it actionable to have appeared verbatim in a
	 * prior external lookup. This prevents invented mini-program names, menus,
	 * ticket types, prices, and deep links from reaching the user.
	 */
	static void validatePurchaseEntryEvidence(JsonNode arguments, AgentTask task) {
		String entryType = arguments.path("entryType").asText("").trim();
		String externalEvidence = task.getTranscript().stream()
				.filter(entry -> "tool".equals(entry.role()))
				.filter(entry -> "search_web".equals(entry.name()) || "travel_fliggy_search".equals(entry.name()))
				.map(AgentTranscript.Entry::text).filter(text -> text != null && !text.isBlank())
				.reduce("", (left, right) -> left + "\n" + right);
		String officialEvidence = task.getTranscript().stream()
				.filter(entry -> "tool".equals(entry.role()) && "search_web".equals(entry.name()))
				.map(AgentTranscript.Entry::text).filter(text -> text != null && !text.isBlank())
				.reduce("", (left, right) -> left + "\n" + right);

		if ("official_miniprogram_guide".equals(entryType)) {
			requireEvidence(officialEvidence, arguments.path("miniProgramName").asText(""), "小程序名称");
			JsonNode menuPath = arguments.path("menuPath");
			if (!menuPath.isArray() || menuPath.isEmpty()) {
				throw AgentToolException.validation("官方小程序指引缺少经搜索结果核验的菜单路径");
			}
			for (JsonNode part : menuPath) requireEvidence(officialEvidence, part.asText(""), "小程序菜单路径");
		} else if ("official_miniprogram_link".equals(entryType) || "third_party_product_page".equals(entryType)) {
			requireEvidence(externalEvidence, arguments.path("entryUrl").asText(""), "入口 URL");
		} else {
			return; // Let the tool report an unsupported entry type itself.
		}

		requireEvidence(externalEvidence, arguments.path("productName").asText(""), "商品名称");
		requireEvidence(externalEvidence, arguments.path("ticketType").asText(""), "票种");
	}

	/**
	 * A multi-platform list is even easier to hallucinate than one card. Every
	 * URL, attraction and product label must therefore already be present in a
	 * read-only lookup before the HTTP verifier is allowed to inspect it.
	 */
	static void validatePurchaseLinksEvidence(JsonNode arguments, AgentTask task) {
		String externalEvidence = task.getTranscript().stream()
				.filter(entry -> "tool".equals(entry.role()))
				.filter(entry -> "search_web".equals(entry.name()))
				.map(AgentTranscript.Entry::text).filter(text -> text != null && !text.isBlank())
				.reduce("", (left, right) -> left + "\n" + right);
		String targetType = arguments.path("targetType").asText("").trim();
		if ("ATTRACTION".equals(targetType)) {
			requireEvidence(externalEvidence, arguments.path("attractionName").asText(""), "景区名称");
		} else if ("TRAIN".equals(targetType)) {
			requireEvidence(externalEvidence, arguments.path("origin").asText(""), "出发地");
			requireEvidence(externalEvidence, arguments.path("destination").asText(""), "到达地");
			requireEvidence(externalEvidence, arguments.path("departureDate").asText(""), "出发日期");
			requireEvidence(externalEvidence, arguments.path("trainNumber").asText(""), "车次");
		} else {
			throw AgentToolException.validation("购票链接缺少有效 targetType");
		}
		JsonNode entries = arguments.path("entries");
		if (!entries.isArray() || entries.isEmpty()) {
			throw AgentToolException.validation("多平台购票链接缺少 entries");
		}
		for (JsonNode entry : entries) {
			requireEvidence(externalEvidence, entry.path("entryUrl").asText(""), "入口 URL");
			requireEvidence(externalEvidence, entry.path("productName").asText(""), "商品名称");
		}
	}

	private static void requireEvidence(String evidence, String value, String label) {
		String normalized = value == null ? "" : value.trim();
		if (normalized.isBlank() || evidence == null || !evidence.contains(normalized)) {
			throw AgentToolException.validation(label + "未在已查询的外部来源中核验，不能生成购票入口。请改为‘待核验’或继续查询官方来源。");
		}
	}

	private static AgentToolException findDeclared(Throwable error) {
		for (Throwable current = error; current != null; current = current.getCause()) {
			if (current instanceof AgentToolException declared) return declared;
		}
		return null;
	}

	/** 对外可见的安全错误信息：不含堆栈与敏感细节，与现有 Function Calling 规则一致。 */
	static String safeMessage(Exception error) {
		if (!(error instanceof com.example.demo.ApplicationException)
				&& !(error instanceof IllegalArgumentException) && findDeclared(error) == null) {
			return "外部能力暂时不可用";
		}
		String message = error.getMessage();
		if (message == null || message.isBlank()) return "外部能力暂时不可用";
		return message.length() > 300 ? message.substring(0, 300) + "…" : message;
	}
}
