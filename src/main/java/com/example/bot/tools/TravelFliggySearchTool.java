package com.example.bot.tools;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.example.bot.agent.AgentToolException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.gson.JsonParser;

/**
 * Read-only adapter for Alibaba FlyAI. It searches Fliggy inventory but never
 * creates an order, collects identity data, or handles payment.
 */
@Component
public class TravelFliggySearchTool implements AssistantTool {
	private static final int MAX_OUTPUT_CHARS = 4_500;
	private static final int MAX_CANDIDATES = 4;
	private static final FunctionDefinition DEFINITION = FunctionDefinition.builder()
			.name("travel_fliggy_search")
			.description("""
					只读查询飞猪 FlyAI 信息源，支持综合、语义、火车、航班、酒店和景点搜索。
					返回的价格、产品及 jumpUrl 只能用于方案比较和引导用户到飞猪详情页复核，
					不能据此宣称本系统已锁库存、创建订单或完成付款。
					""")
			.parameters(JsonParser.parseString("""
					{
					  "type":"object",
					  "properties":{
					    "action":{"type":"string","enum":["keyword","ai","flight","train","hotel","poi"]},
					    "query":{"type":"string","maxLength":400},
					    "origin":{"type":"string","maxLength":60},
					    "destination":{"type":"string","maxLength":60},
					    "departureDate":{"type":"string","pattern":"^[0-9]{4}-[0-9]{2}-[0-9]{2}$"},
					    "returnDate":{"type":"string","pattern":"^[0-9]{4}-[0-9]{2}-[0-9]{2}$"},
					    "city":{"type":"string","maxLength":60},
					    "keyword":{"type":"string","maxLength":100},
					    "checkInDate":{"type":"string","pattern":"^[0-9]{4}-[0-9]{2}-[0-9]{2}$"},
					    "checkOutDate":{"type":"string","pattern":"^[0-9]{4}-[0-9]{2}-[0-9]{2}$"},
					    "maxPrice":{"type":"integer","minimum":1,"maximum":100000},
					    "directOnly":{"type":"boolean"}
					  },
					  "required":["action"],
					  "additionalProperties":false
					}
					""").getAsJsonObject())
			.build();

	private final boolean enabled;
	private final String command;
	private final String apiKey;
	private final Duration timeout;
	private final ObjectMapper objectMapper;

	public TravelFliggySearchTool(
			@Value("${travel.fliggy.enabled:true}") boolean enabled,
			@Value("${travel.fliggy.command:flyai}") String command,
			@Value("${travel.fliggy.api-key:}") String apiKey,
			@Value("${travel.fliggy.timeout-seconds:35}") long timeoutSeconds,
			ObjectMapper objectMapper) {
		this.enabled = enabled;
		this.command = command == null ? "" : command.trim();
		this.apiKey = apiKey == null ? "" : apiKey.trim();
		this.timeout = Duration.ofSeconds(Math.max(5, Math.min(timeoutSeconds, 90)));
		this.objectMapper = objectMapper;
	}

	@Override
	public FunctionDefinition definition() {
		return DEFINITION;
	}

	@Override
	public ToolResult execute(JsonNode arguments, ToolContext context) {
		if (!enabled || command.isBlank()) {
			throw unavailable("飞猪 FlyAI 只读信息源尚未启用");
		}
		List<String> invocation = buildInvocation(arguments);
		try {
			ProcessBuilder builder = new ProcessBuilder(invocation);
			var environment = builder.environment();
			String path = environment.getOrDefault("PATH", "/usr/local/bin:/usr/bin:/bin");
			environment.clear();
			environment.put("PATH", path);
			environment.put("HOME", System.getProperty("java.io.tmpdir", "/tmp"));
			environment.put("LANG", "C.UTF-8");
			if (!apiKey.isBlank()) environment.put("FLYAI_API_KEY", apiKey);
			Process process = builder.start();
			var stdoutValue = new AtomicReference<>("");
			var stderrValue = new AtomicReference<>("");
			var stdout = Thread.ofVirtual().start(
					() -> stdoutValue.set(read(process.getInputStream())));
			var stderr = Thread.ofVirtual().start(
					() -> stderrValue.set(read(process.getErrorStream())));
			if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
				process.destroyForcibly();
				throw AgentToolException.transientError("飞猪信息查询超时", null);
			}
			stdout.join();
			stderr.join();
			String output = stdoutValue.get();
			String error = stderrValue.get();
			if (process.exitValue() != 0) {
				String message = compact(error.isBlank() ? output : error, 500);
				throw AgentToolException.transientError("飞猪信息查询失败：" + message, null);
			}
			JsonNode data = parseJsonOutput(output);
			var result = objectMapper.createObjectNode();
			result.put("source", "FLIGGY_FLYAI");
			result.put("readOnly", true);
			result.put("queriedAt", Instant.now().toString());
			result.put("live", false);
			result.put("priceAndInventoryConfirmed", false);
			result.put("transactionHandledExternally", true);
			result.put("notice",
					"仅用于信息查询。价格和库存可能变化；以 jumpUrl 打开的飞猪详情页为准，实名、下单和付款均在飞猪页面完成。");
			// FlyAI can return dozens of POIs with full descriptions and itinerary
			// details. Keep a few comparable candidates rather than retaining the
			// raw payload in every later model turn.
			result.set("data", summarize(required(arguments, "action"), data));
			return ToolResult.text(compact(result.toString(), MAX_OUTPUT_CHARS));
		} catch (AgentToolException exception) {
			throw exception;
		} catch (IOException exception) {
			throw unavailable("服务器未安装或无法启动 FlyAI CLI");
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw AgentToolException.transientError("飞猪信息查询被中断", exception);
		} catch (Exception exception) {
			throw AgentToolException.transientError("飞猪返回结果暂时无法解析", exception);
		}
	}

	private List<String> buildInvocation(JsonNode arguments) {
		String action = required(arguments, "action");
		List<String> args = new ArrayList<>();
		args.add(command);
		switch (action) {
			case "keyword" -> {
				args.add("keyword-search");
				add(args, "--query", required(arguments, "query"));
			}
			case "ai" -> {
				args.add("ai-search");
				add(args, "--query", required(arguments, "query"));
			}
			case "flight", "train" -> {
				args.add("flight".equals(action) ? "search-flight" : "search-train");
				add(args, "--origin", required(arguments, "origin"));
				add(args, "--destination", required(arguments, "destination"));
				add(args, "--dep-date", required(arguments, "departureDate"));
				optional(args, "--back-date", arguments, "returnDate");
				if (arguments.path("directOnly").asBoolean(false)) add(args, "--journey-type", "1");
				optional(args, "--max-price", arguments, "maxPrice");
				add(args, "--sort-type", "3");
			}
			case "hotel" -> {
				args.add("search-hotel");
				add(args, "--dest-name", required(arguments, "destination"));
				optional(args, "--key-words", arguments, "keyword");
				optional(args, "--check-in-date", arguments, "checkInDate");
				optional(args, "--check-out-date", arguments, "checkOutDate");
				optional(args, "--max-price", arguments, "maxPrice");
				add(args, "--sort", "rate_desc");
			}
			case "poi" -> {
				args.add("search-poi");
				add(args, "--city-name", required(arguments, "city"));
				optional(args, "--keyword", arguments, "keyword");
			}
			default -> throw AgentToolException.validation("action 不受支持：" + action);
		}
		return List.copyOf(args);
	}

	JsonNode summarize(String action, JsonNode data) {
		ObjectNode summary = objectMapper.createObjectNode();
		summary.put("action", action);
		summary.put("providerStatus", data.path("status").asInt(-1));
		copyText(summary, "providerMessage", data, "message", "systemMessage");
		JsonNode payload = data.path("data");
		JsonNode items = payload.path("itemList");
		if (!items.isArray()) items = payload.path("items");
		if (!items.isArray()) {
			summary.put("candidateCount", 0);
			if (!payload.isMissingNode() && !payload.isNull()) {
				summary.put("rawPreview", compact(payload.toString(), 900));
			}
			return summary;
		}
		summary.put("candidateCount", items.size());
		ArrayNode candidates = summary.putArray("candidates");
		for (int index = 0; index < Math.min(MAX_CANDIDATES, items.size()); index++) {
			JsonNode item = items.get(index);
			// Keyword search returns each sellable item inside `info`, while POI,
			// flight and hotel searches put fields at the top level. Normalize both
			// layouts so a verified H5 jumpUrl reaches the Agent unchanged.
			JsonNode source = item.path("info").isObject() ? item.path("info") : item;
			ObjectNode candidate = candidates.addObject();
			copyText(candidate, "name", source, "name", "hotelName", "title");
			copyText(candidate, "price", source, "price", "ticketPrice", "salePrice", "minPrice");
			copyText(candidate, "jumpUrl", source, "jumpUrl");
			copyText(candidate, "address", source, "address");
			String description = text(source, "description", "brief", "subtitle");
			if (!description.isBlank()) candidate.put("description", compact(description, 220));
			addJourneySummary(candidate, item);
		}
		return summary;
	}

	private static void addJourneySummary(ObjectNode candidate, JsonNode item) {
		JsonNode journey = item.path("journeys").path(0);
		if (journey.isMissingNode()) return;
		copyText(candidate, "journeyType", journey, "journeyType");
		copyText(candidate, "totalDurationMinutes", journey, "totalDuration");
		JsonNode segments = journey.path("segments");
		if (!segments.isArray() || segments.isEmpty()) return;
		ArrayNode routes = candidate.putArray("segments");
		for (int index = 0; index < Math.min(2, segments.size()); index++) {
			JsonNode segment = segments.get(index);
			ObjectNode route = routes.addObject();
			copyText(route, "transport", segment, "transportType");
			copyText(route, "number", segment, "marketingTransportNo");
			copyText(route, "carrier", segment, "marketingTransportName");
			copyText(route, "seat", segment, "seatClassName");
			copyText(route, "departure", segment, "depDateTime");
			copyText(route, "arrival", segment, "arrDateTime");
		}
	}

	private static void copyText(ObjectNode target, String field, JsonNode source, String... names) {
		String value = text(source, names);
		if (!value.isBlank()) target.put(field, value);
	}

	private static String text(JsonNode source, String... names) {
		for (String name : names) {
			String value = source.path(name).asText("").trim();
			if (!value.isBlank() && !"null".equalsIgnoreCase(value)) return value;
		}
		return "";
	}

	private JsonNode parseJsonOutput(String output) throws IOException {
		String trimmed = output == null ? "" : output.trim();
		if (trimmed.isBlank()) throw new IOException("FlyAI returned empty output");
		try {
			return objectMapper.readTree(trimmed);
		} catch (IOException first) {
			String[] lines = trimmed.split("\\R");
			for (int index = lines.length - 1; index >= 0; index--) {
				String line = lines[index].trim();
				if (line.startsWith("{") || line.startsWith("[")) {
					try {
						return objectMapper.readTree(line);
					} catch (IOException ignored) {
						// Keep looking for the CLI's single-line JSON payload.
					}
				}
			}
			throw first;
		}
	}

	private AgentToolException unavailable(String reason) {
		return AgentToolException.degraded(reason,
				"{\"source\":\"FLIGGY_FLYAI\",\"live\":false,\"readOnly\":true,"
						+ "\"notice\":\"飞猪信息源当前不可用，继续使用已有地图、天气或其它已配置来源；"
						+ "不得虚构飞猪价格、库存或链接。\"}");
	}

	private static String read(java.io.InputStream stream) {
		try (stream) {
			int retained = 0;
			byte[] buffer = new byte[8192];
			var output = new ByteArrayOutputStream();
			for (int count; (count = stream.read(buffer)) >= 0;) {
				int keep = Math.min(count, MAX_OUTPUT_CHARS * 2 - retained);
				if (keep > 0) {
					output.write(buffer, 0, keep);
					retained += keep;
				}
			}
			return output.toString(StandardCharsets.UTF_8);
		} catch (IOException exception) {
			return "";
		}
	}

	private static void optional(List<String> args, String flag, JsonNode node, String field) {
		JsonNode value = node.path(field);
		if (value.isMissingNode() || value.isNull()) return;
		String text = value.asText("").trim();
		if (!text.isBlank()) add(args, flag, text);
	}

	private static void add(List<String> args, String flag, String value) {
		args.add(flag);
		args.add(value);
	}

	private static String required(JsonNode node, String field) {
		String value = node.path(field).asText("").trim();
		if (value.isBlank()) throw AgentToolException.validation("缺少必填参数：" + field);
		return value;
	}

	private static String compact(String value, int maxChars) {
		if (value == null) return "";
		String safe = value.replace('\u0000', ' ').trim();
		return safe.length() <= maxChars ? safe : safe.substring(0, maxChars) + "…";
	}
}
