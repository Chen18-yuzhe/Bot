package com.example.bot.tools;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.example.bot.agent.AgentToolException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonParser;

/** Searches inter-city rail and flight offers without ever inventing availability or fares. */
@Component
public class TravelTransportTool implements AssistantTool {
	private static final FunctionDefinition DEFINITION = FunctionDefinition.builder()
			.name("travel_transport")
			.description("""
					查询跨城市火车或航班方案。rail 使用中文出发站/到达站；
					flight 使用 IATA 城市或机场代码。结果用于比较时间和价格，
					只有供应商返回的结算地址才可作为购票入口。
					""")
			.parameters(JsonParser.parseString("""
					{
					  "type":"object",
					  "properties":{
					    "mode":{"type":"string","enum":["rail","flight"]},
					    "origin":{"type":"string","description":"火车出发站中文名"},
					    "destination":{"type":"string","description":"火车到达站中文名"},
					    "originCode":{"type":"string","description":"航班出发城市/机场 IATA 代码"},
					    "destinationCode":{"type":"string","description":"航班到达城市/机场 IATA 代码"},
					    "departureDate":{"type":"string","pattern":"^[0-9]{4}-[0-9]{2}-[0-9]{2}$"},
					    "travelers":{"type":"integer","minimum":1,"maximum":9},
					    "nonStop":{"type":"boolean"}
					  },
					  "required":["mode","departureDate","travelers"],
					  "additionalProperties":false
					}
					""").getAsJsonObject())
			.build();

	private final String railApiUrl;
	private final String railApiKey;
	private final String amadeusBaseUrl;
	private final String amadeusClientId;
	private final String amadeusClientSecret;
	private final ObjectMapper objectMapper;
	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10)).build();

	public TravelTransportTool(
			@Value("${travel.rail.api-url:}") String railApiUrl,
			@Value("${travel.rail.api-key:}") String railApiKey,
			@Value("${travel.flight.amadeus.base-url:https://test.api.amadeus.com}") String amadeusBaseUrl,
			@Value("${travel.flight.amadeus.client-id:}") String amadeusClientId,
			@Value("${travel.flight.amadeus.client-secret:}") String amadeusClientSecret,
			ObjectMapper objectMapper) {
		this.railApiUrl = railApiUrl;
		this.railApiKey = railApiKey;
		this.amadeusBaseUrl = amadeusBaseUrl;
		this.amadeusClientId = amadeusClientId;
		this.amadeusClientSecret = amadeusClientSecret;
		this.objectMapper = objectMapper;
	}

	@Override
	public FunctionDefinition definition() {
		return DEFINITION;
	}

	@Override
	public ToolResult execute(JsonNode arguments, ToolContext context) {
		try {
			return switch (required(arguments, "mode")) {
				case "rail" -> rail(arguments);
				case "flight" -> flight(arguments);
				default -> throw new IllegalArgumentException("mode 只能是 rail 或 flight");
			};
		} catch (AgentToolException | IllegalArgumentException exception) {
			throw exception;
		} catch (Exception exception) {
			throw AgentToolException.transientError("跨城交通查询暂时不可用", exception);
		}
	}

	private ToolResult rail(JsonNode arguments) throws Exception {
		if (blank(railApiUrl) || blank(railApiKey)) {
			throw AgentToolException.degraded("火车数据 API 尚未配置",
					"{\"source\":\"UNCONFIGURED\",\"live\":false,"
							+ "\"officialEntry\":\"https://www.12306.cn/\","
							+ "\"notice\":\"只能引导用户到铁路12306核验，不能声称有实时余票或生成预填订单。\"}");
		}
		String separator = railApiUrl.contains("?") ? "&" : "?";
		String url = railApiUrl + separator + "key=" + enc(railApiKey)
				+ "&search_type=1&departure_station=" + enc(required(arguments, "origin"))
				+ "&arrival_station=" + enc(required(arguments, "destination"))
				+ "&date=" + enc(required(arguments, "departureDate"))
				+ "&enable_booking=1";
		HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(20)).GET().build(),
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		requireSuccess(response, "火车");
		JsonNode root = objectMapper.readTree(response.body());
		var result = objectMapper.createObjectNode();
		result.put("source", "CONFIGURED_RAIL_PROVIDER");
		result.put("live", true);
		result.put("queriedAt", java.time.Instant.now().toString());
		result.set("data", root.path("result").isMissingNode() ? root : root.path("result"));
		result.put("bookingNotice", "余票与价格必须在结算前再次确认；12306购票由其官方实名页面完成。");
		return ToolResult.text(limit(result.toString(), 12_000));
	}

	private ToolResult flight(JsonNode arguments) throws Exception {
		if (blank(amadeusClientId) || blank(amadeusClientSecret)) {
			throw AgentToolException.degraded("航班报价 API 尚未配置",
					"{\"source\":\"UNCONFIGURED\",\"live\":false,"
							+ "\"notice\":\"无法提供实时航班价格或库存；不得伪造航班和购票链接。\"}");
		}
		String tokenBody = "grant_type=client_credentials&client_id=" + enc(amadeusClientId)
				+ "&client_secret=" + enc(amadeusClientSecret);
		HttpResponse<String> tokenResponse = http.send(HttpRequest.newBuilder(
				URI.create(trimSlash(amadeusBaseUrl) + "/v1/security/oauth2/token"))
				.timeout(Duration.ofSeconds(20))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(tokenBody)).build(),
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		requireSuccess(tokenResponse, "航班鉴权");
		String token = objectMapper.readTree(tokenResponse.body()).path("access_token").asText("");
		if (token.isBlank()) throw new IllegalStateException("航班供应商未返回 access_token");
		String url = trimSlash(amadeusBaseUrl) + "/v2/shopping/flight-offers"
				+ "?originLocationCode=" + enc(required(arguments, "originCode").toUpperCase())
				+ "&destinationLocationCode=" + enc(required(arguments, "destinationCode").toUpperCase())
				+ "&departureDate=" + enc(required(arguments, "departureDate"))
				+ "&adults=" + arguments.path("travelers").asInt()
				+ "&currencyCode=CNY&max=8&nonStop=" + arguments.path("nonStop").asBoolean(false);
		HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(25))
				.header("Authorization", "Bearer " + token).GET().build(),
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		requireSuccess(response, "航班");
		JsonNode root = objectMapper.readTree(response.body());
		var result = objectMapper.createObjectNode();
		result.put("source", "AMADEUS");
		result.put("live", !amadeusBaseUrl.contains("test.api"));
		result.put("queriedAt", java.time.Instant.now().toString());
		result.set("offers", root.path("data"));
		result.put("bookingNotice", "报价需用 Flight Offers Price 再确认；Self-Service 不提供供应商托管付款页。");
		return ToolResult.text(limit(result.toString(), 16_000));
	}

	private static void requireSuccess(HttpResponse<String> response, String provider) {
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			throw AgentToolException.transientError(provider + "供应商返回 HTTP " + response.statusCode(), null);
		}
	}

	private static String required(JsonNode node, String field) {
		String value = node.path(field).asText("").trim();
		if (value.isBlank()) throw new IllegalArgumentException("缺少必填参数：" + field);
		return value;
	}

	private static String enc(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private static String trimSlash(String value) {
		return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
	}

	private static boolean blank(String value) {
		return value == null || value.isBlank();
	}

	private static String limit(String value, int max) {
		return value.length() <= max ? value : value.substring(0, max) + "…";
	}
}
