package com.example.demo.tools;

import org.springframework.stereotype.Component;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.example.demo.agent.AgentToolException;
import com.example.demo.travel.TravelIntegrationGateway;
import com.example.demo.travel.TravelModels.TicketQuote;
import com.example.demo.travel.TravelPlanService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonParser;

/** Real-time ticket quote boundary. Missing suppliers become an explicit degraded result. */
@Component
public class TravelTicketTool implements AssistantTool {
	private static final FunctionDefinition DEFINITION = FunctionDefinition.builder()
			.name("travel_ticket")
			.description("查询某个已生成行程中景点的实时票价、库存、退款规则和报价有效期。供应商未接入时会明确返回降级结果，不能把估算称为实时库存。")
			.parameters(JsonParser.parseString("""
					{
					  "type":"object",
					  "properties":{
					    "planId":{"type":"string"},
					    "attractionId":{"type":"string"}
					  },
					  "required":["planId","attractionId"],
					  "additionalProperties":false
					}
					""").getAsJsonObject())
			.build();

	private final TravelPlanService plans;
	private final TravelIntegrationGateway integrations;
	private final ObjectMapper objectMapper;

	public TravelTicketTool(TravelPlanService plans, TravelIntegrationGateway integrations,
			ObjectMapper objectMapper) {
		this.plans = plans;
		this.integrations = integrations;
		this.objectMapper = objectMapper;
	}

	@Override
	public FunctionDefinition definition() {
		return DEFINITION;
	}

	@Override
	public ToolResult execute(JsonNode arguments, ToolContext context) {
		String planId = requiredText(arguments, "planId");
		String attractionId = requiredText(arguments, "attractionId");
		plans.getPlan(planId, context.sessionId());
		TicketQuote quote = integrations.quote(attractionId);
		String json = serialize(quote);
		if (!quote.live() || quote.offers().isEmpty()) {
			throw AgentToolException.degraded("实时票务供应商尚未接入", json);
		}
		return ToolResult.text(json);
	}

	private String serialize(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (Exception exception) {
			throw new IllegalStateException("无法序列化票务报价", exception);
		}
	}

	private static String requiredText(JsonNode arguments, String name) {
		String value = arguments.path(name).asText("").trim();
		if (value.isBlank()) throw new IllegalArgumentException("缺少必填参数：" + name);
		return value;
	}
}
