package com.example.bot.tools;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.example.bot.agent.AgentToolException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonParser;

/**
 * Produces a truthful purchase-entry card. It deliberately does not create an
 * order or manufacture a Mini Program URL; it only formats a verified entry
 * discovered by another tool into instructions the user can follow in WeChat.
 */
@Component
public class TravelPurchaseEntryTool implements AssistantTool {
	private static final FunctionDefinition DEFINITION = FunctionDefinition.builder()
			.name("travel_purchase_entry")
			.description("生成单个景点购票入口。只接受查询结果原样提供的真实 HTTPS URL Link 或第三方商品详情 URL；没有真实可点击链接时必须返回待核验，绝不能生成小程序名称、菜单路径或看似可点击的占位入口。多个平台链接请使用 travel_purchase_links。此工具不创建订单、不锁库存、不代填实名信息或付款。")
			.parameters(JsonParser.parseString("""
					{
					  "type":"object",
					  "properties":{
				    "entryType":{"type":"string","enum":["official_miniprogram_link","third_party_product_page"]},
					    "provider":{"type":"string","description":"官方景区/小程序名称或第三方平台名称"},
					    "attractionName":{"type":"string"},
					    "visitDate":{"type":"string","description":"YYYY-MM-DD"},
					    "productName":{"type":"string","description":"已核验的商品标题或推荐商品方向"},
					    "ticketType":{"type":"string","description":"建议在页面选择的票种，最终以页面为准"},
					    "quantity":{"type":"integer","minimum":1,"maximum":50},
					    "entryUrl":{"type":"string","description":"仅填写查询工具原样返回的 HTTPS 入口；官方小程序直达必须是原始 URL Link，第三方必须是商品详情页"},
				    "miniProgramName":{"type":"string","description":"仅作已验证 URL Link 的展示名称；不能替代可点击 URL"},
				    "menuPath":{"type":"array","items":{"type":"string"},"maxItems":6,"description":"已废弃：不能以菜单路径替代可点击购票入口"},
					    "avoid":{"type":"array","items":{"type":"string"},"maxItems":8,"description":"应排除的套票或不匹配选项"},
					    "selectionReason":{"type":"string"},
					    "sourceCheckedAt":{"type":"string"}
					  },
					  "required":["entryType","provider","attractionName","visitDate","productName","ticketType","quantity","selectionReason","sourceCheckedAt"],
					  "additionalProperties":false
					}
					""").getAsJsonObject())
			.build();

	private final ObjectMapper objectMapper;

	public TravelPurchaseEntryTool(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public FunctionDefinition definition() {
		return DEFINITION;
	}

	@Override
	public ToolResult execute(JsonNode arguments, ToolContext context) {
		String entryType = required(arguments, "entryType");
		String provider = required(arguments, "provider");
		String attraction = required(arguments, "attractionName");
		String visitDate = required(arguments, "visitDate");
		String product = required(arguments, "productName");
		String ticketType = required(arguments, "ticketType");
		int quantity = arguments.path("quantity").asInt(0);
		if (quantity < 1 || quantity > 50) throw AgentToolException.validation("quantity 必须在 1 到 50 之间");
		String reason = required(arguments, "selectionReason");
		String checkedAt = required(arguments, "sourceCheckedAt");
		String url = arguments.path("entryUrl").asText("").trim();
		String miniProgramName = arguments.path("miniProgramName").asText("").trim();
		List<String> menuPath = textList(arguments.path("menuPath"));
		List<String> avoid = textList(arguments.path("avoid"));

		switch (entryType) {
			case "official_miniprogram_link" -> {
				validateHttps(url, "官方小程序直达入口");
				if (!menuPath.isEmpty()) {
					throw AgentToolException.validation("官方小程序直达入口不应同时伪造菜单路径；只能保留查询结果提供的真实 URL Link");
				}
			}
			case "third_party_product_page" -> validateHttps(url, "第三方商品详情入口");
			default -> throw AgentToolException.validation("不支持的入口类型：" + entryType);
		}

		try {
			var result = objectMapper.createObjectNode();
			result.put("entryType", entryType.toUpperCase());
			result.put("sourceCheckedAt", checkedAt);
			result.put("generatedAt", Instant.now().toString());
			result.put("provider", provider);
			result.put("attractionName", attraction);
			result.put("visitDate", visitDate);
			result.put("productName", product);
			result.put("ticketType", ticketType);
			result.put("quantity", quantity);
			result.set("avoid", objectMapper.valueToTree(avoid));
			result.put("selectionReason", reason);
			result.put("requiresFinalSelection", true);
			result.put("paymentHandledExternally", true);
			if ("official_miniprogram_link".equals(entryType)) {
				result.put("entryUrl", url);
				result.put("openInstruction", "打开已核验的官方小程序 URL Link；页面内仍需确认日期、票种、数量、价格和库存。");
			} else {
				result.put("entryUrl", url);
				result.put("openInstruction", "打开已核验的第三方商品详情页；这不是已预选 SKU 的付款页，仍需在页面确认全部订单信息。");
			}
			return ToolResult.text(result.toString());
		} catch (Exception exception) {
			throw AgentToolException.transientError("购票入口卡片暂时无法生成", exception);
		}
	}

	private static void validateHttps(String value, String label) {
		if (value.isBlank()) throw AgentToolException.validation(label + "必须使用查询结果提供的 HTTPS 地址");
		try {
			URI uri = URI.create(value);
			if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) throw new IllegalArgumentException();
		} catch (Exception exception) {
			throw AgentToolException.validation(label + "必须是有效 HTTPS 地址");
		}
	}

	private static String required(JsonNode arguments, String field) {
		String value = arguments.path(field).asText("").trim();
		if (value.isBlank()) throw AgentToolException.validation("缺少必填参数：" + field);
		return value;
	}

	private static List<String> textList(JsonNode node) {
		if (node == null || !node.isArray()) return List.of();
		List<String> values = new ArrayList<>();
		for (JsonNode item : node) {
			String value = item.asText("").trim();
			if (!value.isBlank()) values.add(value);
		}
		return List.copyOf(values);
	}
}
