package com.example.demo.tools;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.example.demo.travel.TravelModels.PlanRequest;
import com.example.demo.travel.TravelModels.PlanResponse;
import com.example.demo.travel.TravelPlanService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonParser;

/** Creates and retrieves multi-option travel plans for the current WeChat session. */
@Component
public class TravelPlanTool implements AssistantTool {
	private static final FunctionDefinition DEFINITION = FunctionDefinition.builder()
			.name("travel_plan")
			.description("生成三个可比较的多日旅游方案，按planId读取方案，或读取当前微信会话最近方案。用户说“第二个方案”等指代时优先使用latest。金额单位是分。")
			.parameters(JsonParser.parseString("""
					{
					  "type":"object",
					  "properties":{
					    "action":{"type":"string","enum":["create","get","latest"]},
					    "planId":{"type":"string","description":"action=get时必填"},
					    "destination":{"type":"string"},
					    "departureDate":{"type":"string","pattern":"^[0-9]{4}-[0-9]{2}-[0-9]{2}$"},
					    "days":{"type":"integer","minimum":1,"maximum":14},
					    "travelers":{"type":"integer","minimum":1,"maximum":12},
					    "budgetCents":{"type":"integer","minimum":10000},
					    "interests":{"type":"array","items":{"type":"string"},"maxItems":8}
					  },
					  "required":["action"],
					  "additionalProperties":false
					}
					""").getAsJsonObject())
			.build();

	private final TravelPlanService plans;
	private final ObjectMapper objectMapper;

	public TravelPlanTool(TravelPlanService plans, ObjectMapper objectMapper) {
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
		PlanResponse result = switch (action) {
			case "create" -> plans.createPlan(new PlanRequest(
					context.sessionId(),
					requiredText(arguments, "destination"),
					requiredText(arguments, "departureDate"),
					requiredInt(arguments, "days"),
					requiredInt(arguments, "travelers"),
					requiredLong(arguments, "budgetCents"),
					textList(arguments.path("interests"))));
			case "get" -> plans.getPlan(requiredText(arguments, "planId"), context.sessionId());
			case "latest" -> plans.getLatestPlan(context.sessionId());
			default -> throw new IllegalArgumentException("action 只能是 create、get 或 latest");
		};
		return ToolResult.text(serialize(result));
	}

	private String serialize(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (Exception exception) {
			throw new IllegalStateException("无法序列化旅游方案", exception);
		}
	}

	private static List<String> textList(JsonNode node) {
		if (node == null || !node.isArray()) return List.of();
		List<String> result = new ArrayList<>();
		for (JsonNode item : node) {
			String value = item.asText("").trim();
			if (!value.isBlank()) result.add(value);
		}
		return result.stream().limit(8).toList();
	}

	private static String requiredText(JsonNode arguments, String name) {
		String value = arguments.path(name).asText("").trim();
		if (value.isBlank()) throw new IllegalArgumentException("缺少必填参数：" + name);
		return value;
	}

	private static int requiredInt(JsonNode arguments, String name) {
		if (!arguments.has(name) || !arguments.path(name).canConvertToInt()) {
			throw new IllegalArgumentException("缺少或无效的整数参数：" + name);
		}
		return arguments.path(name).asInt();
	}

	private static long requiredLong(JsonNode arguments, String name) {
		if (!arguments.has(name) || !arguments.path(name).canConvertToLong()) {
			throw new IllegalArgumentException("缺少或无效的整数参数：" + name);
		}
		return arguments.path(name).asLong();
	}
}
