package com.example.demo.tools;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.example.demo.agent.AgentToolException;
import com.example.demo.travel.TravelModels.Attraction;
import com.example.demo.travel.TravelPlanService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonParser;

/** Attraction discovery uses a curated fallback; route calculation requires a map API. */
@Component
public class TravelDiscoveryTool implements AssistantTool {
	private static final FunctionDefinition DEFINITION = FunctionDefinition.builder()
			.name("travel_discovery")
			.description("发现目的地景点候选，或规划多个地点之间的路线。景点可使用内置可信目录降级；路线在地图API未配置时只保留站点顺序并明确标注待确认。")
			.parameters(JsonParser.parseString("""
					{
					  "type":"object",
					  "properties":{
					    "action":{"type":"string","enum":["attractions","route"]},
					    "destination":{"type":"string"},
					    "interests":{"type":"array","items":{"type":"string"},"maxItems":8},
					    "origin":{"type":"string"},
					    "waypoints":{"type":"array","items":{"type":"string"},"minItems":1,"maxItems":12},
					    "transportMode":{"type":"string","enum":["walk","transit","drive"]}
					  },
					  "required":["action"],
					  "additionalProperties":false
					}
					""").getAsJsonObject())
			.build();

	private final TravelPlanService plans;
	private final ObjectMapper objectMapper;

	public TravelDiscoveryTool(TravelPlanService plans, ObjectMapper objectMapper) {
		this.plans = plans;
		this.objectMapper = objectMapper;
	}

	@Override
	public FunctionDefinition definition() {
		return DEFINITION;
	}

	@Override
	public ToolResult execute(JsonNode arguments, ToolContext context) {
		String action = requiredText(arguments, "action");
		try {
			if ("attractions".equals(action)) {
				String destination = requiredText(arguments, "destination");
				List<Attraction> attractions = plans.discoverAttractions(destination,
						textList(arguments.path("interests")));
				var result = objectMapper.createObjectNode();
				result.put("destination", destination);
				result.put("source", "CURATED_FALLBACK");
				result.put("live", false);
				result.set("attractions", objectMapper.valueToTree(attractions));
				result.put("notice", "营业时间、临时闭馆、预约和地理距离仍需通过 POI/地图 API 实时确认。");
				return ToolResult.text(result.toString());
			}
			if ("route".equals(action)) {
				List<String> waypoints = textList(arguments.path("waypoints"));
				if (waypoints.isEmpty()) throw new IllegalArgumentException("route 至少需要一个 waypoint");
				var result = objectMapper.createObjectNode();
				result.put("source", "UNCONFIGURED_MAP_PROVIDER");
				result.put("live", false);
				result.put("origin", arguments.path("origin").asText("住宿地"));
				result.put("transportMode", arguments.path("transportMode").asText("transit"));
				result.set("waypoints", objectMapper.valueToTree(waypoints));
				result.put("orderedRoute", String.join(" → ", waypoints));
				result.put("notice", "尚未配置地图 API，无法提供真实距离、耗时、路况或导航链接。");
				throw AgentToolException.degraded("地图路线 API 尚未接入", result.toString());
			}
			throw new IllegalArgumentException("action 只能是 attractions 或 route");
		} catch (AgentToolException | IllegalArgumentException exception) {
			throw exception;
		} catch (Exception exception) {
			throw AgentToolException.transientError("景点或地图服务暂时不可用", exception);
		}
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

	private static String requiredText(JsonNode arguments, String name) {
		String value = arguments.path(name).asText("").trim();
		if (value.isBlank()) throw new IllegalArgumentException("缺少必填参数：" + name);
		return value;
	}
}
