package com.example.bot.tools;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.example.bot.agent.AgentToolException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.gson.JsonParser;

/**
 * Turns externally discovered ticket detail URLs into a small, safe list of
 * clickable links. A platform name is never a substitute for a real URL.
 */
@Component
public class TravelPurchaseLinksTool implements AssistantTool {
	// One exact candidate per supported platform keeps chat replies responsive.
	private static final int MAX_ENTRIES = 2;
	private static final int MAX_PAGE_CHARS = 64 * 1024;
	private static final Map<String, List<String>> PLATFORM_HOSTS = Map.of(
			"MEITUAN", List.of("meituan.com", "dianping.com"),
			"CTRIP", List.of("ctrip.com"));
	private static final Map<String, String> PLATFORM_LABELS = Map.of(
			"MEITUAN", "美团/大众点评", "CTRIP", "携程");
	private static final FunctionDefinition DEFINITION = FunctionDefinition.builder()
			.name("travel_purchase_links")
			.description("把已检索到的美团/大众点评或携程精准购票页汇总为可点击链接。景区票只接受 meituan.com、dianping.com 或 ctrip.com 的 HTTPS 景区/商品详情页；火车票只接受 ctrip.com，且页面必须能核验出发地、到达地、日期和车次。每个 entryUrl 必须逐字来自 search_web 的结果。工具会验证平台域名、HTTP 可达性和目标匹配；失败项不会输出。不得用其它平台、搜索页、首页、小程序名称、菜单路径或自行拼接的 URL 代替精准详情页。")
			.parameters(JsonParser.parseString("""
					{
					  "type":"object",
					  "properties":{
					    "targetType":{"type":"string","enum":["ATTRACTION","TRAIN"]},
					    "attractionName":{"type":"string","maxLength":120},
					    "origin":{"type":"string","maxLength":80},
					    "destination":{"type":"string","maxLength":80},
					    "departureDate":{"type":"string","pattern":"^[0-9]{4}-[0-9]{2}-[0-9]{2}$"},
					    "trainNumber":{"type":"string","maxLength":20},
					    "sourceCheckedAt":{"type":"string"},
					    "entries":{"type":"array","minItems":1,"maxItems":4,"items":{
					      "type":"object","properties":{
					        "platform":{"type":"string","enum":["MEITUAN","CTRIP"]},
					        "entryUrl":{"type":"string"},
					        "productName":{"type":"string","maxLength":240}
					      },"required":["platform","entryUrl","productName"],"additionalProperties":false
					    }}
					  },
					  "required":["targetType","sourceCheckedAt","entries"],
					  "additionalProperties":false
					}
					""").getAsJsonObject()).build();

	private final ObjectMapper objectMapper;
	private final LinkProbe linkProbe;

	@Autowired
	public TravelPurchaseLinksTool(ObjectMapper objectMapper) {
		this(objectMapper, new HttpLinkProbe());
	}

	TravelPurchaseLinksTool(ObjectMapper objectMapper, LinkProbe linkProbe) {
		this.objectMapper = objectMapper;
		this.linkProbe = linkProbe;
	}

	@Override public FunctionDefinition definition() { return DEFINITION; }

	@Override
	public ToolResult execute(JsonNode arguments, ToolContext context) {
		String targetType = required(arguments, "targetType");
		if (!"ATTRACTION".equals(targetType) && !"TRAIN".equals(targetType)) {
			throw AgentToolException.validation("targetType 只支持 ATTRACTION 或 TRAIN");
		}
		String attraction = "ATTRACTION".equals(targetType) ? required(arguments, "attractionName") : "";
		String origin = "TRAIN".equals(targetType) ? required(arguments, "origin") : "";
		String destination = "TRAIN".equals(targetType) ? required(arguments, "destination") : "";
		String departureDate = "TRAIN".equals(targetType) ? required(arguments, "departureDate") : "";
		String trainNumber = "TRAIN".equals(targetType) ? required(arguments, "trainNumber") : "";
		String checkedAt = required(arguments, "sourceCheckedAt");
		JsonNode requested = arguments.path("entries");
		if (!requested.isArray() || requested.isEmpty() || requested.size() > MAX_ENTRIES) {
			throw AgentToolException.validation("entries 必须包含 1 到 " + MAX_ENTRIES + " 条平台链接");
		}
		ObjectNode result = objectMapper.createObjectNode();
		result.put("targetType", targetType);
		if (!attraction.isBlank()) result.put("attractionName", attraction);
		if (!origin.isBlank()) result.put("origin", origin);
		if (!destination.isBlank()) result.put("destination", destination);
		if (!departureDate.isBlank()) result.put("departureDate", departureDate);
		if (!trainNumber.isBlank()) result.put("trainNumber", trainNumber);
		result.put("sourceCheckedAt", checkedAt);
		result.put("generatedAt", Instant.now().toString());
		result.put("paymentHandledExternally", true);
		result.put("requiresFinalSelection", true);
		ArrayNode verified = result.putArray("links");
		ArrayNode rejected = result.putArray("rejected");
		for (JsonNode entry : requested) {
			String platform = entry.path("platform").asText("").trim().toUpperCase(Locale.ROOT);
			String url = entry.path("entryUrl").asText("").trim();
			String product = entry.path("productName").asText("").trim();
			try {
				URI requestedUri = validatePlatformUrl(platform, url, targetType);
				if (product.isBlank()) throw AgentToolException.validation("商品名称不能为空");
				ProbeResult probe = linkProbe.probe(requestedUri);
				if (probe.statusCode() < 200 || probe.statusCode() >= 400) {
					throw AgentToolException.validation("平台页面返回 HTTP " + probe.statusCode());
				}
				validatePlatformUrl(platform, probe.finalUri().toString(), targetType);
				if ("ATTRACTION".equals(targetType) && !mentionsAttraction(probe.pageText(), attraction)) {
					throw AgentToolException.validation("页面未能核验目标景区名称，不能作为精准购票页");
				}
				if ("TRAIN".equals(targetType)
						&& !mentionsTrain(probe.pageText(), origin, destination, departureDate, trainNumber)) {
					throw AgentToolException.validation("页面未能同时核验出发地、到达地、日期和车次，不能作为精准列车页");
				}
				ObjectNode item = verified.addObject();
				item.put("platform", platform);
				item.put("provider", PLATFORM_LABELS.get(platform));
				item.put("productName", product);
				item.put("entryUrl", probe.finalUri().toString());
				item.put("verification", "平台域名、HTTP 可达性、页面景区名称均已校验");
			} catch (AgentToolException exception) {
				rejection(rejected, platform, exception.getMessage());
			} catch (Exception exception) {
				rejection(rejected, platform, "页面暂时无法校验");
			}
		}
		result.put("verifiedCount", verified.size());
		result.put("notice", verified.isEmpty()
				? "没有可验证的精准购票页；未输出任何链接，不能用平台名称或搜索页替代。"
				: "仅点击 links 中的链接；价格、日期、库存与付款均以平台页面为准。");
		return verified.isEmpty() ? ToolResult.noResult(result.toString()) : ToolResult.text(result.toString());
	}

	private static void rejection(ArrayNode rejected, String platform, String reason) {
		ObjectNode item = rejected.addObject();
		item.put("platform", platform.isBlank() ? "UNKNOWN" : platform);
		item.put("reason", reason == null || reason.isBlank() ? "无法校验" : reason);
	}

	private static URI validatePlatformUrl(String platform, String value, String targetType) {
		List<String> domains = PLATFORM_HOSTS.get(platform);
		if (domains == null) throw AgentToolException.validation("不支持的平台：" + platform);
		if ("TRAIN".equals(targetType) && !"CTRIP".equals(platform)) {
			throw AgentToolException.validation("火车票精准链接只支持携程");
		}
		try {
			URI uri = URI.create(value);
			String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
			if (!"https".equalsIgnoreCase(uri.getScheme()) || !matchesAnyDomain(host, domains)) {
				throw AgentToolException.validation("链接不是 " + PLATFORM_LABELS.get(platform) + " 的 HTTPS 页面");
			}
			if ("ATTRACTION".equals(targetType) && isSearchHomeOrChannelPage(uri)) {
				throw AgentToolException.validation("链接是搜索页、首页或频道页，不是景点/门票详情直达页");
			}
			return uri;
		} catch (IllegalArgumentException exception) {
			throw AgentToolException.validation("链接不是有效 HTTPS 地址");
		}
	}

	private static boolean matchesAnyDomain(String host, List<String> domains) {
		return domains.stream().anyMatch(domain -> host.equals(domain) || host.endsWith("." + domain));
	}

	static boolean isSearchHomeOrChannelPage(URI uri) {
		String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
		String query = uri.getRawQuery() == null ? "" : uri.getRawQuery().toLowerCase(Locale.ROOT);
		if (path.isBlank() || "/".equals(path)) return true;
		if (path.contains("/search") || path.endsWith("/search")
				|| "/s".equals(path) || path.startsWith("/s/")) return true;
		return query.contains("keyword=") || query.contains("query=") || query.contains("search=");
	}

	static boolean mentionsAttraction(String pageText, String attraction) {
		String page = normalize(pageText);
		String target = normalize(attraction).replace("风景区", "").replace("景区", "")
				.replace("旅游区", "").replace("景点", "");
		if (target.length() < 2) return false;
		if (page.contains(target)) return true;
		int matched = 0;
		for (String token : meaningfulTokens(target)) if (page.contains(token)) matched++;
		return matched >= Math.min(2, meaningfulTokens(target).size());
	}

	static boolean mentionsTrain(String pageText, String origin, String destination,
			String departureDate, String trainNumber) {
		String page = normalize(pageText);
		String date = departureDate == null ? "" : departureDate.trim();
		String[] parts = date.split("-");
		String chineseDate = parts.length == 3
				? Integer.parseInt(parts[1]) + "月" + Integer.parseInt(parts[2]) + "日" : "";
		boolean dateMatches = page.contains(normalize(date)) || page.contains(normalize(date.replace("-", "")))
				|| (!chineseDate.isBlank() && page.contains(normalize(chineseDate)));
		return page.contains(normalize(origin)) && page.contains(normalize(destination))
				&& page.contains(normalize(trainNumber)) && dateMatches;
	}

	private static List<String> meaningfulTokens(String value) {
		List<String> tokens = new ArrayList<>();
		for (int index = 0; index + 1 < value.length(); index++) {
			String token = value.substring(index, index + 2);
			if (!tokens.contains(token)) tokens.add(token);
		}
		return tokens;
	}

	private static String normalize(String value) {
		return value == null ? "" : value.replaceAll("[\\s\\p{Punct}　]", "").toLowerCase(Locale.ROOT);
	}

	private static String required(JsonNode node, String field) {
		String value = node.path(field).asText("").trim();
		if (value.isBlank()) throw AgentToolException.validation("缺少必填参数：" + field);
		return value;
	}

	interface LinkProbe { ProbeResult probe(URI uri) throws IOException, InterruptedException; }
	record ProbeResult(URI finalUri, int statusCode, String pageText) { }

	private static final class HttpLinkProbe implements LinkProbe {
		private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4))
				.followRedirects(HttpClient.Redirect.NORMAL).build();

		@Override public ProbeResult probe(URI uri) throws IOException, InterruptedException {
			HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(6))
				.header("User-Agent", "Mozilla/5.0 (compatible; BotAssistant/1.0)")
				.header("Accept-Encoding", "identity").GET().build();
			HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
			try (InputStream body = response.body()) {
				byte[] bytes = body.readNBytes(MAX_PAGE_CHARS);
				return new ProbeResult(response.uri(), response.statusCode(),
						new String(bytes, StandardCharsets.UTF_8));
			}
		}
	}
}
