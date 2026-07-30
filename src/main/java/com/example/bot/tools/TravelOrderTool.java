package com.example.bot.tools;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.example.bot.ApplicationException;
import com.example.bot.agent.AgentToolException;
import com.example.bot.travel.IdempotencyService;
import com.example.bot.travel.TravelModels.CancelOrderRequest;
import com.example.bot.travel.TravelModels.ConfirmOrderRequest;
import com.example.bot.travel.TravelModels.OrderResponse;
import com.example.bot.travel.TravelOrderService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonParser;

/** Order actions with explicit consent, ownership checks and deterministic idempotency keys. */
@Component
public class TravelOrderTool implements AssistantTool {
	private static final FunctionDefinition DEFINITION = FunctionDefinition.builder()
			.name("travel_order")
			.description("创建、查询或取消旅游票务订单。create/cancel只有在当前用户明确确认后才能执行；缺少实名、登录或支付授权时任务会暂停并转人工接入。")
			.parameters(JsonParser.parseString("""
					{
					  "type":"object",
					  "properties":{
					    "action":{"type":"string","enum":["create","get","cancel"]},
					    "confirmed":{"type":"boolean","description":"create/cancel必须为true，且当前用户目标中必须有明确授权"},
					    "planId":{"type":"string"},
					    "optionId":{"type":"string"},
					    "quoteId":{"type":"string"},
					    "ticketOfferIds":{"type":"array","items":{"type":"string"},"minItems":1},
					    "orderId":{"type":"string"},
					    "reason":{"type":"string"}
					  },
					  "required":["action"],
					  "additionalProperties":false
					}
					""").getAsJsonObject())
			.build();

	private final TravelOrderService orders;
	private final IdempotencyService idempotency;
	private final ObjectMapper objectMapper;

	public TravelOrderTool(TravelOrderService orders, IdempotencyService idempotency,
			ObjectMapper objectMapper) {
		this.orders = orders;
		this.idempotency = idempotency;
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
			OrderResponse response = switch (action) {
				case "create" -> create(arguments, context);
				case "get" -> orders.get(requiredText(arguments, "orderId"), context.sessionId());
				case "cancel" -> cancel(arguments, context);
				default -> throw new IllegalArgumentException("action 只能是 create、get 或 cancel");
			};
			return ToolResult.text(objectMapper.writeValueAsString(response));
		} catch (ApplicationException exception) {
			throw classifyForAgent(exception, action);
		} catch (AgentToolException | IllegalArgumentException exception) {
			throw exception;
		} catch (Exception exception) {
			throw AgentToolException.transientError("订单服务暂时不可用", exception);
		}
	}

	private OrderResponse create(JsonNode arguments, ToolContext context) {
		requireConsent(arguments, context, "购票");
		String planId = requiredText(arguments, "planId");
		String optionId = requiredText(arguments, "optionId");
		String quoteId = requiredText(arguments, "quoteId");
		List<String> offerIds = requiredList(arguments, "ticketOfferIds");
		ConfirmOrderRequest request = new ConfirmOrderRequest(context.sessionId(), planId, optionId,
				quoteId, offerIds, "");
		String fingerprint = planId + "|" + optionId + "|" + quoteId + "|" + offerIds;
		return idempotency.execute("agent-order-create", context.sessionId(),
				"agent-" + digest(fingerprint), fingerprint, () -> orders.confirm(request));
	}

	private OrderResponse cancel(JsonNode arguments, ToolContext context) {
		requireConsent(arguments, context, "取消或退款");
		String orderId = requiredText(arguments, "orderId");
		String reason = arguments.path("reason").asText("用户通过 Agent 明确取消").trim();
		String fingerprint = orderId + "|" + reason;
		CancelOrderRequest request = new CancelOrderRequest(context.sessionId(), reason);
		return idempotency.execute("agent-order-cancel", context.sessionId(),
				"agent-" + digest(fingerprint), fingerprint, () -> orders.cancel(orderId, request));
	}

	private static void requireConsent(JsonNode arguments, ToolContext context, String action) {
		if (!arguments.path("confirmed").asBoolean(false) || !explicitConsent(context.userQuestion())) {
			throw AgentToolException.validation("用户尚未明确确认" + action + "，只能继续比较或询价");
		}
	}

	private static boolean explicitConsent(String goal) {
		String value = goal == null ? "" : goal.toLowerCase(Locale.ROOT);
		return containsAny(value, "确认购买", "确认购票", "确认下单", "立即购买", "帮我买", "帮我订",
				"买票", "购票", "确认取消", "取消订单", "申请退款", "confirm purchase", "buy ticket");
	}

	private static RuntimeException classifyForAgent(ApplicationException exception, String action) {
		if (exception.getStatus() == HttpStatus.UNAUTHORIZED || exception.getStatus() == HttpStatus.FORBIDDEN
				|| exception.getStatus() == HttpStatus.SERVICE_UNAVAILABLE) {
			return AgentToolException.authRequired(exception.getMessage(),
					"请用户在微信安全页面完成登录、实名核验或支付授权；完成后再恢复本任务继续" + action + "。不要在聊天中发送身份证号或支付密钥。");
		}
		if (exception.getStatus() == HttpStatus.BAD_REQUEST || exception.getStatus() == HttpStatus.NOT_FOUND
				|| exception.getStatus() == HttpStatus.CONFLICT) {
			return AgentToolException.validation(exception.getMessage());
		}
		return AgentToolException.transientError("订单服务暂时不可用", exception);
	}

	private static List<String> requiredList(JsonNode arguments, String name) {
		JsonNode node = arguments.path(name);
		if (!node.isArray() || node.isEmpty()) throw new IllegalArgumentException("缺少必填参数：" + name);
		List<String> values = new ArrayList<>();
		for (JsonNode item : node) {
			String value = item.asText("").trim();
			if (!value.isBlank()) values.add(value);
		}
		if (values.isEmpty()) throw new IllegalArgumentException(name + " 不能为空");
		return List.copyOf(values);
	}

	private static String requiredText(JsonNode arguments, String name) {
		String value = arguments.path(name).asText("").trim();
		if (value.isBlank()) throw new IllegalArgumentException("缺少必填参数：" + name);
		return value;
	}

	private static String digest(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 32);
		} catch (Exception exception) {
			throw new IllegalStateException("无法生成幂等键", exception);
		}
	}

	private static boolean containsAny(String value, String... words) {
		for (String word : words) if (value.contains(word)) return true;
		return false;
	}
}
