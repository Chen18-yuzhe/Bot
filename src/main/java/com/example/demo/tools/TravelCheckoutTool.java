package com.example.demo.tools;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.example.demo.ApplicationException;
import com.example.demo.agent.AgentToolException;
import com.example.demo.travel.IdempotencyService;
import com.example.demo.travel.TravelModels.CancelOrderRequest;
import com.example.demo.travel.TravelModels.ElectronicTicket;
import com.example.demo.travel.TravelModels.ExternalCheckoutRequest;
import com.example.demo.travel.TravelModels.ExternalPurchaseRecordRequest;
import com.example.demo.travel.TravelModels.ExternalRefundRecordRequest;
import com.example.demo.travel.TravelModels.OrderResponse;
import com.example.demo.travel.TravelOrderService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonParser;

/** Model-callable purchase navigation that stops before final SKU selection and payment. */
@Component
public class TravelCheckoutTool implements AssistantTool {
	private static final FunctionDefinition DEFINITION = FunctionDefinition.builder()
			.name("travel_checkout")
			.description("""
					管理个人使用的景区购票导航与外部购买记录。create 必须使用查询工具返回的
					商品详情 jumpUrl，保存推荐的商品方向、应选票种、数量、套票取舍和核验时间；
					不要将 checkoutUrl 留空，不要宣称已经锁定 SKU、实时价格或库存。
					禁止使用搜索页或泛称“景区门票”。日期、最终票种、价格、库存、实名信息和
					付款均由用户在外部平台确认。付款后可登记平台订单号和电子票；退款完成后可登记结果。
					""")
			.parameters(JsonParser.parseString("""
					{
					  "type":"object",
					  "properties":{
					    "action":{"type":"string","enum":[
					      "create","record_purchase","get","cancel","record_refund"
					    ]},
					    "confirmed":{"type":"boolean"},
					    "planId":{"type":"string"},
					    "optionId":{"type":"string"},
					    "orderId":{"type":"string"},
					    "attractionId":{"type":"string"},
					    "attractionName":{"type":"string"},
					    "visitDate":{"type":"string","description":"YYYY-MM-DD"},
					    "provider":{"type":"string"},
					    "providerProductId":{"type":"string","description":"未来交易供应商接入预留；个人模式不要猜测或填写"},
					    "providerSkuId":{"type":"string","description":"未来交易供应商接入预留；个人模式不要猜测或填写"},
					    "productName":{"type":"string","description":"查询结果中可验证的商品标题或商品方向"},
					    "ticketType":{"type":"string","description":"建议用户在页面选择的成人票、学生票、夜场票等；仍需页面确认"},
					    "quantity":{"type":"integer","minimum":1,"maximum":50},
					    "unitPriceCents":{"type":"integer","minimum":0,"description":"仅填写来源明确的参考价，否则省略"},
					    "expectedAmountCents":{"type":"integer","minimum":0,"description":"仅填写来源明确的参考总价，否则省略"},
					    "bundle":{"type":"boolean"},
					    "bundleContents":{"type":"array","items":{"type":"string"}},
					    "refundRule":{"type":"string"},
					    "selectionReason":{"type":"string"},
					    "checkoutUrl":{"type":"string","description":"个人模式必填，且必须逐字使用查询工具返回的商品详情 jumpUrl；它是 PRODUCT_DETAIL，仍需用户在页面选择日期和最终票种"},
					    "sourceCheckedAt":{"type":"string"},
					    "providerOrderId":{"type":"string"},
					    "actualAmountCents":{"type":"integer","minimum":0},
					    "paidAt":{"type":"string"},
					    "tickets":{"type":"array","items":{
					      "type":"object",
					      "properties":{
					        "ticketNo":{"type":"string"},
					        "attractionName":{"type":"string"},
					        "code":{"type":"string"},
					        "status":{"type":"string"}
					      },
					      "required":["ticketNo"],
					      "additionalProperties":false
					    }},
					    "reason":{"type":"string"},
					    "refundReference":{"type":"string"},
					    "refundedAt":{"type":"string"}
					  },
					  "required":["action"],
					  "additionalProperties":false
					}
					""").getAsJsonObject())
			.build();

	private final TravelOrderService orders;
	private final IdempotencyService idempotency;
	private final ObjectMapper objectMapper;

	public TravelCheckoutTool(TravelOrderService orders, IdempotencyService idempotency,
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
				case "record_purchase" -> recordPurchase(arguments, context);
				case "get" -> orders.get(requiredText(arguments, "orderId"), context.sessionId());
				case "cancel" -> cancel(arguments, context);
				case "record_refund" -> recordRefund(arguments, context);
				default -> throw new IllegalArgumentException("不支持的半自动购票动作：" + action);
			};
			return ToolResult.text(objectMapper.writeValueAsString(response));
		} catch (ApplicationException exception) {
			throw classifyForAgent(exception);
		} catch (AgentToolException | IllegalArgumentException exception) {
			throw exception;
		} catch (Exception exception) {
			throw AgentToolException.transientError("半自动购票服务暂时不可用", exception);
		}
	}

	private OrderResponse create(JsonNode arguments, ToolContext context) {
		requireConsent(arguments, context, "生成半自动购票清单");
		ExternalCheckoutRequest request = new ExternalCheckoutRequest(
				context.sessionId(), requiredText(arguments, "planId"),
				requiredText(arguments, "optionId"), requiredText(arguments, "attractionId"),
				requiredText(arguments, "attractionName"), requiredText(arguments, "visitDate"),
				requiredText(arguments, "provider"),
				arguments.path("providerProductId").asText("").trim(),
				arguments.path("providerSkuId").asText("").trim(),
				requiredText(arguments, "productName"),
				requiredText(arguments, "ticketType"), positiveInt(arguments, "quantity"),
				optionalLong(arguments, "unitPriceCents"),
				optionalLong(arguments, "expectedAmountCents"),
				arguments.path("bundle").asBoolean(false),
				stringList(arguments, "bundleContents"),
				arguments.path("refundRule").asText("").trim(),
				requiredText(arguments, "selectionReason"),
				arguments.path("checkoutUrl").asText("").trim(),
				requiredText(arguments, "sourceCheckedAt"));
		String fingerprint = request.planId() + "|" + request.optionId() + "|"
				+ request.attractionId() + "|" + request.visitDate() + "|"
				+ request.provider() + "|" + request.productName() + "|"
				+ request.ticketType() + "|" + request.quantity() + "|"
				+ request.checkoutUrl();
		return idempotency.execute("agent-external-checkout", context.sessionId(),
				"agent-" + digest(fingerprint), fingerprint,
				() -> orders.createExternalCheckout(request));
	}

	private OrderResponse recordPurchase(JsonNode arguments, ToolContext context) {
		requireConsent(arguments, context, "登记已完成的外部购票");
		String orderId = requiredText(arguments, "orderId");
		ExternalPurchaseRecordRequest request = new ExternalPurchaseRecordRequest(
				context.sessionId(), requiredText(arguments, "providerOrderId"),
				optionalLong(arguments, "actualAmountCents"),
				arguments.path("paidAt").asText("").trim(),
				parseTickets(arguments.path("tickets")));
		String fingerprint = orderId + "|" + request.providerOrderId() + "|"
				+ request.actualAmountCents();
		return idempotency.execute("agent-external-purchase-record", context.sessionId(),
				"agent-" + digest(fingerprint), fingerprint,
				() -> orders.recordExternalPurchase(orderId, request));
	}

	private OrderResponse cancel(JsonNode arguments, ToolContext context) {
		requireConsent(arguments, context, "取消购票清单或发起外部退款指引");
		String orderId = requiredText(arguments, "orderId");
		String reason = arguments.path("reason").asText("用户明确要求取消").trim();
		CancelOrderRequest request = new CancelOrderRequest(context.sessionId(), reason);
		String fingerprint = orderId + "|" + reason;
		return idempotency.execute("agent-external-cancel", context.sessionId(),
				"agent-" + digest(fingerprint), fingerprint,
				() -> orders.cancel(orderId, request));
	}

	private OrderResponse recordRefund(JsonNode arguments, ToolContext context) {
		requireConsent(arguments, context, "登记退款结果");
		String orderId = requiredText(arguments, "orderId");
		ExternalRefundRecordRequest request = new ExternalRefundRecordRequest(
				context.sessionId(),
				arguments.path("refundReference").asText("").trim(),
				arguments.path("refundedAt").asText("").trim());
		String fingerprint = orderId + "|" + request.refundReference() + "|"
				+ request.refundedAt();
		return idempotency.execute("agent-external-refund-record", context.sessionId(),
				"agent-" + digest(fingerprint), fingerprint,
				() -> orders.recordExternalRefund(orderId, request));
	}

	private static void requireConsent(JsonNode arguments, ToolContext context, String action) {
		if (!arguments.path("confirmed").asBoolean(false)
				|| !explicitConsent(context.userQuestion())) {
			throw AgentToolException.validation(
					"用户尚未明确确认" + action + "，只能继续比较或说明");
		}
	}

	private static boolean explicitConsent(String goal) {
		String value = goal == null ? "" : goal.toLowerCase(Locale.ROOT);
		return containsAny(value, "确认购买", "确认购票", "确认下单", "立即购买",
				"购票", "就这个", "生成购票清单", "跳转购票", "我已付款",
				"已经买了", "记录订单", "保存电子票", "确认取消", "取消订单",
				"申请退款", "退款完成", "confirm purchase", "buy ticket", "paid");
	}

	private static RuntimeException classifyForAgent(ApplicationException exception) {
		if (exception.getMessage() != null
				&& exception.getMessage().contains("票务供应商结算接口尚未配置")) {
			return AgentToolException.authRequired(exception.getMessage(),
					"请管理员配置 TRAVEL_SUPPLIER_API_URL 和 TRAVEL_SUPPLIER_API_KEY。"
							+ "配置完成后恢复任务，系统会为已选票种创建供应商托管的实名付款页面。");
		}
		if (exception.getStatus() == HttpStatus.UNAUTHORIZED
				|| exception.getStatus() == HttpStatus.FORBIDDEN) {
			return AgentToolException.authRequired(exception.getMessage(),
					"请先完成当前会话登录。不要在聊天中发送身份证号、银行卡号或支付密码。");
		}
		if (exception.getStatus() == HttpStatus.BAD_REQUEST
				|| exception.getStatus() == HttpStatus.NOT_FOUND
				|| exception.getStatus() == HttpStatus.CONFLICT) {
			return AgentToolException.validation(exception.getMessage());
		}
		return AgentToolException.transientError("半自动购票服务暂时不可用", exception);
	}

	private static List<ElectronicTicket> parseTickets(JsonNode node) {
		if (!node.isArray()) return List.of();
		List<ElectronicTicket> result = new ArrayList<>();
		for (JsonNode item : node) {
			String ticketNo = item.path("ticketNo").asText("").trim();
			if (ticketNo.isBlank()) continue;
			result.add(new ElectronicTicket(ticketNo,
					item.path("attractionName").asText("").trim(),
					item.path("code").asText("").trim(),
					item.path("status").asText("EXTERNAL_ISSUED").trim()));
		}
		return List.copyOf(result);
	}

	private static List<String> stringList(JsonNode arguments, String name) {
		JsonNode node = arguments.path(name);
		if (!node.isArray()) return List.of();
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

	private static int positiveInt(JsonNode arguments, String name) {
		int value = arguments.path(name).asInt(0);
		if (value < 1) throw new IllegalArgumentException(name + " 必须大于 0");
		return value;
	}

	private static Long optionalLong(JsonNode arguments, String name) {
		JsonNode value = arguments.get(name);
		return value == null || value.isNull() ? null : value.asLong();
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
