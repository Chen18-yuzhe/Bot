package com.example.bot.chat;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.example.bot.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;

/** Keeps a model-driven tool chain bounded and stops retries that make no progress. */
final class ToolChainGuard {
	static final int MAX_TOTAL_CALLS = 8;
	static final int MAX_CALLS_PER_TOOL = 5;
	static final int MAX_UNPRODUCTIVE_PER_TOOL = 2;
	static final int MAX_CONSECUTIVE_UNPRODUCTIVE = 3;

	private final Map<String, Integer> callsByTool = new HashMap<>();
	private final Map<String, Integer> unproductiveByTool = new HashMap<>();
	private final Set<String> successfulCalls = new HashSet<>();
	private int totalCalls;
	private int consecutiveUnproductive;
	private boolean halted;
	private String haltReason;

	Decision beforeCall(String tool, JsonNode arguments) {
		if (halted) return Decision.blocked("CHAIN_HALTED", haltReason);
		String fingerprint = fingerprint(tool, arguments);
		if (successfulCalls.contains(fingerprint)) {
			return halt("DUPLICATE_SUCCESSFUL_CALL",
					"相同工具和参数已经成功执行，禁止重复执行；请使用已有结果");
		}
		if (totalCalls >= MAX_TOTAL_CALLS) {
			return halt("TOTAL_CALL_LIMIT", "工具链已达到总调用上限，请根据已有结果回答或请用户缩小问题范围");
		}
		int toolCalls = callsByTool.getOrDefault(tool, 0);
		if (toolCalls >= MAX_CALLS_PER_TOOL) {
			return halt("TOOL_CALL_LIMIT", "同一工具调用次数过多，请停止猜测并请用户补充更明确的信息");
		}
		totalCalls++;
		callsByTool.put(tool, toolCalls + 1);
		return Decision.allowed(fingerprint, totalCalls);
	}

	void success(String tool, String fingerprint, ToolResult result) {
		if (result.useful()) {
			successfulCalls.add(fingerprint);
			consecutiveUnproductive = 0;
			unproductiveByTool.remove(tool);
			return;
		}
		unproductive(tool, "工具没有返回可用于回答的信息");
	}

	void failure(String tool) {
		unproductive(tool, "工具连续执行失败");
	}

	private void unproductive(String tool, String reason) {
		consecutiveUnproductive++;
		int toolFailures = unproductiveByTool.merge(tool, 1, Integer::sum);
		if (toolFailures >= MAX_UNPRODUCTIVE_PER_TOOL
				|| consecutiveUnproductive >= MAX_CONSECUTIVE_UNPRODUCTIVE) {
			halted = true;
			haltReason = reason + "，已停止继续调用。请说明缺少的信息并向用户提出一个简短的澄清问题";
		}
	}

	private Decision halt(String code, String reason) {
		halted = true;
		haltReason = reason;
		return Decision.blocked(code, reason);
	}

	boolean halted() {
		return halted;
	}

	String haltReason() {
		return haltReason == null ? "工具链没有取得有效进展，请向用户询问必要信息" : haltReason;
	}

	int totalCalls() {
		return totalCalls;
	}

	static String fingerprint(String tool, JsonNode arguments) {
		return tool + ":" + canonical(arguments);
	}

	private static String canonical(JsonNode node) {
		if (node == null || node.isNull()) return "null";
		if (node.isTextual()) {
			return '"' + node.asText().trim().replaceAll("\\s+", " ")
					.toLowerCase(Locale.ROOT) + '"';
		}
		if (node.isArray()) {
			StringBuilder value = new StringBuilder("[");
			for (JsonNode item : node) {
				if (value.length() > 1) value.append(',');
				value.append(canonical(item));
			}
			return value.append(']').toString();
		}
		if (node.isObject()) {
			Map<String, JsonNode> sorted = new TreeMap<>();
			node.properties().forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
			StringBuilder value = new StringBuilder("{");
			for (var entry : sorted.entrySet()) {
				if (value.length() > 1) value.append(',');
				value.append(entry.getKey()).append(':').append(canonical(entry.getValue()));
			}
			return value.append('}').toString();
		}
		return node.toString();
	}

	record Decision(boolean allowed, String fingerprint, int callNumber, String code, String message) {
		static Decision allowed(String fingerprint, int callNumber) {
			return new Decision(true, fingerprint, callNumber, null, null);
		}

		static Decision blocked(String code, String message) {
			return new Decision(false, null, 0, code, message);
		}
	}
}
