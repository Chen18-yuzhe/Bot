package com.example.bot.tools;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.example.bot.agent.AgentToolException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonParser;

/** Transaction-grade SKU, live price and inventory lookup boundary. */
@Component
public class TravelTicketMarketTool implements AssistantTool {
	private static final FunctionDefinition DEFINITION = FunctionDefinition.builder()
			.name("travel_ticket_market")
			.description("""
					向票务交易供应商查询指定景点、日期和人数的精确 SKU、实时价格、库存和退改规则。
					只有本工具返回 providerProductId 与 providerSkuId 后，Agent 才能请求创建
					按 SKU 预选的供应商托管实名付款会话。FlyAI 的 jumpUrl 不能替代这些字段。
					""")
			.parameters(JsonParser.parseString("""
					{
					  "type":"object",
					  "properties":{
					    "destination":{"type":"string","maxLength":80},
					    "attractionName":{"type":"string","maxLength":120},
					    "visitDate":{"type":"string","pattern":"^[0-9]{4}-[0-9]{2}-[0-9]{2}$"},
					    "quantity":{"type":"integer","minimum":1,"maximum":50},
					    "ticketType":{"type":"string","maxLength":80},
					    "productHint":{"type":"string","maxLength":180}
					  },
					  "required":["destination","attractionName","visitDate","quantity"],
					  "additionalProperties":false
					}
					""").getAsJsonObject())
			.build();

	private final String apiUrl;
	private final String apiKey;
	private final ObjectMapper objectMapper;
	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10)).build();

	public TravelTicketMarketTool(
			@Value("${travel.ticket-market.api-url:}") String apiUrl,
			@Value("${travel.ticket-market.api-key:}") String apiKey,
			ObjectMapper objectMapper) {
		this.apiUrl = apiUrl == null ? "" : apiUrl.trim();
		this.apiKey = apiKey == null ? "" : apiKey.trim();
		this.objectMapper = objectMapper;
	}

	@Override
	public FunctionDefinition definition() {
		return DEFINITION;
	}

	@Override
	public ToolResult execute(JsonNode arguments, ToolContext context) {
		if (apiUrl.isBlank() || apiKey.isBlank()) {
			throw AgentToolException.degraded("票务交易数据源尚未配置",
					"{\"source\":\"UNCONFIGURED_TICKET_MARKET\",\"live\":false,"
							+ "\"notice\":\"当前只能使用 FlyAI 做商品发现，不能提供实时价格、库存、"
							+ "providerProductId 或 providerSkuId，也不能创建预选 SKU 的结算会话。\"}");
		}
		validateHttps(apiUrl);
		var payload = objectMapper.createObjectNode();
		payload.put("destination", required(arguments, "destination"));
		payload.put("attractionName", required(arguments, "attractionName"));
		payload.put("visitDate", required(arguments, "visitDate"));
		payload.put("quantity", arguments.path("quantity").asInt());
		putOptional(payload, "ticketType", arguments);
		putOptional(payload, "productHint", arguments);
		payload.put("channel", "WECHAT_AGENT");
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(apiUrl))
					.timeout(Duration.ofSeconds(25))
					.header("Authorization", "Bearer " + apiKey)
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(
							objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
					.build();
			HttpResponse<String> response = http.send(request,
					HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (response.statusCode() == 401 || response.statusCode() == 403) {
				throw AgentToolException.authRequired("票务交易供应商鉴权失败",
						"请检查 TRAVEL_TICKET_MARKET_API_KEY 及供应商账户权限后恢复任务。");
			}
			if (response.statusCode() == 429 || response.statusCode() >= 500) {
				throw AgentToolException.transientError(
						"票务交易供应商暂时不可用，HTTP " + response.statusCode(), null);
			}
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				throw AgentToolException.validation(
						"票务交易供应商拒绝了查询参数，HTTP " + response.statusCode());
			}
			JsonNode root = objectMapper.readTree(response.body());
			JsonNode offers = root.path("offers");
			if (!offers.isArray()) offers = root.path("data").path("offers");
			if (!offers.isArray()) {
				throw AgentToolException.transientError("票务交易供应商响应缺少 offers 数组", null);
			}
			var normalized = objectMapper.createArrayNode();
			for (JsonNode offer : offers) {
				String productId = text(offer, "providerProductId", "productId");
				String skuId = text(offer, "providerSkuId", "skuId");
				String productName = text(offer, "productName", "name");
				String ticketType = text(offer, "ticketType", "skuName");
				if (productId.isBlank() || skuId.isBlank() || productName.isBlank()
						|| ticketType.isBlank() || !offer.path("unitPriceCents").canConvertToLong()) {
					continue;
				}
				var item = objectMapper.createObjectNode();
				item.put("providerProductId", productId);
				item.put("providerSkuId", skuId);
				item.put("productName", productName);
				item.put("ticketType", ticketType);
				item.put("unitPriceCents", offer.path("unitPriceCents").asLong());
				item.put("inventoryStatus", text(offer, "inventoryStatus", "inventory"));
				if (offer.path("remaining").canConvertToInt()) {
					item.put("remaining", offer.path("remaining").asInt());
				}
				item.put("refundRule", offer.path("refundRule").asText(""));
				item.put("bundle", offer.path("bundle").asBoolean(false));
				item.set("bundleContents", offer.path("bundleContents").isArray()
						? offer.path("bundleContents") : objectMapper.createArrayNode());
				item.put("hostedCheckoutSupported",
						offer.path("hostedCheckoutSupported").asBoolean(false));
				String detailUrl = offer.path("detailUrl").asText("");
				if (!detailUrl.isBlank() && isHttps(detailUrl)) item.put("detailUrl", detailUrl);
				normalized.add(item);
			}
			var result = objectMapper.createObjectNode();
			result.put("source", root.path("source").asText("CONFIGURED_TICKET_MARKET"));
			result.put("live", true);
			result.put("queriedAt", Instant.now().toString());
			result.put("quoteExpiresAt", root.path("quoteExpiresAt").asText(""));
			result.set("offers", normalized);
			result.put("notice", normalized.isEmpty()
					? "供应商未返回同时包含产品ID、SKU ID、票种和价格的可用报价。"
					: "价格和库存只在报价有效期内有效；创建结算会话前必须再次复核。");
			return normalized.isEmpty()
					? ToolResult.noResult(result.toString()) : ToolResult.text(result.toString());
		} catch (AgentToolException exception) {
			throw exception;
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw AgentToolException.transientError("票务交易查询被中断", exception);
		} catch (Exception exception) {
			throw AgentToolException.transientError("票务交易查询暂时失败", exception);
		}
	}

	private static void putOptional(com.fasterxml.jackson.databind.node.ObjectNode payload,
			String field, JsonNode arguments) {
		String value = arguments.path(field).asText("").trim();
		if (!value.isBlank()) payload.put(field, value);
	}

	private static String required(JsonNode node, String field) {
		String value = node.path(field).asText("").trim();
		if (value.isBlank()) throw AgentToolException.validation("缺少必填参数：" + field);
		return value;
	}

	private static String text(JsonNode node, String first, String second) {
		String value = node.path(first).asText("").trim();
		return value.isBlank() ? node.path(second).asText("").trim() : value;
	}

	private static void validateHttps(String value) {
		if (!isHttps(value)) {
			throw AgentToolException.validation("票务交易接口必须使用有效的 HTTPS 地址");
		}
	}

	private static boolean isHttps(String value) {
		try {
			URI uri = URI.create(value);
			return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null;
		} catch (IllegalArgumentException exception) {
			return false;
		}
	}
}
