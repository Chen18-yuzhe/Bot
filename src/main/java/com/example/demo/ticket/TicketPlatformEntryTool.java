package com.example.demo.ticket;

import java.time.Instant;

import org.springframework.stereotype.Component;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.example.demo.tools.AssistantTool;
import com.example.demo.tools.ToolContext;
import com.example.demo.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.gson.JsonParser;

/**
 * Returns stable ticket-channel landing pages without constructing a search URL
 * or claiming that a specific attraction/product has been selected.
 */
@Component
public class TicketPlatformEntryTool implements AssistantTool {
	public static final String MEITUAN_URL =
			"https://i.meituan.com/awp/hfe/travel-merchant/index.html";
	public static final String CTRIP_URL =
			"https://piao.ctrip.com/dest/d-China-60979/s-tickets/";
	public static final String FLIGGY_URL =
			"https://btrip.alitrip.com/piao/";

	private static final FunctionDefinition DEFINITION = FunctionDefinition.builder()
			.name("travel_ticket_platform_entries")
			.description("""
					返回美团、携程、飞猪三个固定的景点门票网页入口。所有 URL 都不拼接景点名、
					日期或搜索参数，也不表示已选中具体景点、票种、价格或库存。用户询问景点购票
					链接时直接调用本工具；不要先调用 search_web，也不要改写工具返回的 URL。
					""")
			.parameters(JsonParser.parseString("""
					{
					  "type":"object",
					  "properties":{},
					  "additionalProperties":false
					}
					""").getAsJsonObject())
			.build();

	private final ObjectMapper objectMapper;

	public TicketPlatformEntryTool(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public FunctionDefinition definition() {
		return DEFINITION;
	}

	@Override
	public ToolResult execute(JsonNode arguments, ToolContext context) {
		ObjectNode result = objectMapper.createObjectNode();
		result.put("generatedAt", Instant.now().toString());
		result.put("fixedPlatformEntries", true);
		result.put("containsSearchParameters", false);
		ArrayNode entries = result.putArray("entries");
		add(entries, "美团门票", MEITUAN_URL);
		add(entries, "携程门票", CTRIP_URL);
		add(entries, "飞猪门票", FLIGGY_URL);
		result.put("notice", "暂时不能给到您具体的购票页面，麻烦您进入平台自主购票。");
		return ToolResult.text(result.toString());
	}

	private static void add(ArrayNode entries, String platform, String url) {
		ObjectNode entry = entries.addObject();
		entry.put("platform", platform);
		entry.put("url", url);
	}
}
