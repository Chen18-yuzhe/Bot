package com.example.bot.travel;

import static com.example.bot.travel.TravelModels.*;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.example.bot.ApplicationException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class TravelOrderService {
	private final Map<String, Order> orders = new ConcurrentHashMap<>();
	private final TravelPlanService planService;
	private final TravelIntegrationGateway integrations;
	private final TravelAuditService audit;
	private final ObjectMapper objectMapper;
	private final TravelStateStore stateStore;

	@Autowired
	public TravelOrderService(TravelPlanService planService, TravelIntegrationGateway integrations,
			TravelAuditService audit, ObjectMapper objectMapper, TravelStateStore stateStore) {
		this.planService = planService;
		this.integrations = integrations;
		this.audit = audit;
		this.objectMapper = objectMapper;
		this.stateStore = stateStore;
	}

	public TravelOrderService(TravelPlanService planService, TravelIntegrationGateway integrations,
			TravelAuditService audit) {
		this(planService, integrations, audit, new ObjectMapper(), null);
	}

	/** Existing direct-payment path, retained for a future commercial deployment. */
	public OrderResponse confirm(ConfirmOrderRequest request) {
		if (request == null || blank(request.userId()) || blank(request.planId())
				|| blank(request.optionId()) || blank(request.quoteId())) {
			throw new IllegalArgumentException("userId、planId、optionId 和 quoteId 为必填项");
		}
		if (request.ticketOfferIds() == null || request.ticketOfferIds().isEmpty()) {
			throw new IllegalArgumentException("请至少选择一张已报价的门票");
		}
		integrations.assertIdentityVerified(request.identityVerificationToken());
		requireOption(request.planId(), request.optionId(), request.userId());
		long amount = integrations.validateQuoteAndCalculateAmount(
				request.quoteId(), request.ticketOfferIds());
		String orderId = newId("trip_");
		PaymentInstruction payment = integrations.createWechatPayment(orderId, amount);
		Order order = new Order(orderId, request.userId(), amount,
				request.ticketOfferIds(), OrderMode.DIRECT_WECHAT_PAY);
		order.payment = payment;
		orders.put(orderId, order);
		persist(order);
		audit.record("ORDER_CREATED", request.userId(), orderId, "AWAITING_PAYMENT");
		return response(order, payment, "订单已创建，请在微信中完成支付。未支付订单不会出票。");
	}

	/**
	 * Creates an external product-navigation record. A provider-hosted checkout is
	 * retained only for a future commercial integration and is not exposed to the
	 * personal travel Agent.
	 */
	public OrderResponse createExternalCheckout(ExternalCheckoutRequest request) {
		validateExternalRequest(request);
		requireOption(request.planId(), request.optionId(), request.userId());
		String checkoutUrl = safe(request.checkoutUrl());
		boolean providerHostedCheckout = checkoutUrl.isBlank();
		if (checkoutUrl.isBlank()) checkoutUrl = integrations.createHostedCheckout(request);
		validateHttpsUrl(checkoutUrl);

		long unitPrice = optionalMoney(request.unitPriceCents());
		long expectedAmount = request.expectedAmountCents() == null
				? unitPrice * request.quantity()
				: optionalMoney(request.expectedAmountCents());
		if (unitPrice > 0 && expectedAmount > 0
				&& expectedAmount != unitPrice * request.quantity()) {
			throw new IllegalArgumentException("门票单价 × 数量与预计总价不一致，请重新核对");
		}
		List<String> bundleContents = cleanList(request.bundleContents());
		if (request.bundle() && bundleContents.isEmpty()) {
			throw new IllegalArgumentException("套票必须明确列出包含项目，不能只写“套票”");
		}
		if (!request.bundle() && !bundleContents.isEmpty()) {
			throw new IllegalArgumentException("非套票不应填写套票包含项目");
		}

		ExternalCheckoutInstruction checkout = new ExternalCheckoutInstruction(
				request.provider().trim(), checkoutUrl,
				request.attractionName().trim(), request.visitDate().trim(),
				request.productName().trim(), request.ticketType().trim(),
				request.quantity(), unitPrice, expectedAmount, request.bundle(),
				bundleContents, safe(request.refundRule()), request.selectionReason().trim(),
				request.sourceCheckedAt().trim(),
				providerHostedCheckout ? "HOSTED_CHECKOUT" : "PRODUCT_DETAIL",
				providerHostedCheckout, providerHostedCheckout, !providerHostedCheckout,
				providerHostedCheckout
						? List.of(
								"打开供应商为所选票种创建的托管结算页",
								"核对景点、日期、票种、数量、套票内容和退改规则",
								"由你本人填写实名信息并确认付款",
								"付款后把平台订单号或电子票发给 Agent 保存")
						: List.of(
								"打开已匹配商品方向的供应商详情页",
								"在页面内最终确认游玩日期、票种、数量和库存",
								"确认页面展示的完整商品名与 Agent 选择一致后再填写实名信息并付款",
								"如果页面默认展示套票或其它票种，不要付款并返回告知 Agent"));

		String orderId = newId("external_");
		Order order = new Order(orderId, request.userId(), expectedAmount,
				List.of(), OrderMode.EXTERNAL_CHECKOUT);
		order.status = OrderStatus.AWAITING_EXTERNAL_PAYMENT;
		order.checkout = checkout;
		orders.put(orderId, order);
		persist(order);
		audit.record("EXTERNAL_CHECKOUT_CREATED", request.userId(), orderId,
				request.provider() + "|" + request.productName());
		return response(order, null, providerHostedCheckout
				? "已按所选票种创建供应商托管的实名付款入口，请核对后完成付款。"
				: "已生成所选商品方向的供应商详情入口；该渠道未提供 SKU 预选能力，"
						+ "仍需你在页面内确认日期、票种和库存后再实名付款。");
	}

	public OrderResponse get(String orderId, String userId) {
		Order order = owned(orderId, userId);
		synchronized (order) {
			return response(order, order.payment, "订单查询成功");
		}
	}

	public OrderResponse recordExternalPurchase(String orderId,
			ExternalPurchaseRecordRequest request) {
		if (request == null || blank(request.userId()) || blank(request.providerOrderId())) {
			throw new IllegalArgumentException("userId 和票务平台订单号为必填项");
		}
		Order order = owned(orderId, request.userId());
		synchronized (order) {
			requireExternal(order);
			if (order.status == OrderStatus.ISSUED
					|| order.status == OrderStatus.EXTERNALLY_PAID) {
				return response(order, null, "该外部购票结果已经记录");
			}
			if (order.status != OrderStatus.AWAITING_EXTERNAL_PAYMENT) {
				throw new ApplicationException(HttpStatus.CONFLICT,
						"当前订单状态不能登记付款：" + order.status);
			}
			order.providerOrderId = request.providerOrderId().trim();
			if (request.actualAmountCents() != null) {
				order.amountCents = optionalMoney(request.actualAmountCents());
			}
			order.transactionId = "external:" + order.providerOrderId;
			order.tickets = sanitizeTickets(request.tickets());
			order.status = order.tickets.isEmpty()
					? OrderStatus.EXTERNALLY_PAID : OrderStatus.ISSUED;
			persist(order);
			audit.record("EXTERNAL_PURCHASE_RECORDED", order.userId, order.orderId,
					"status=" + order.status);
			return response(order, null, order.tickets.isEmpty()
					? "已记录外部平台订单。收到电子票后可继续发给 Agent 保存。"
					: "外部订单和电子票已保存到行程。");
		}
	}

	public OrderResponse recordExternalRefund(String orderId,
			ExternalRefundRecordRequest request) {
		if (request == null || blank(request.userId())) {
			throw new IllegalArgumentException("userId 为必填项");
		}
		Order order = owned(orderId, request.userId());
		synchronized (order) {
			requireExternal(order);
			if (order.status == OrderStatus.REFUNDED) {
				return response(order, null, "退款结果已经记录");
			}
			if (order.status != OrderStatus.AWAITING_EXTERNAL_REFUND) {
				throw new ApplicationException(HttpStatus.CONFLICT,
						"请先在原购票渠道申请取消或退款");
			}
			order.status = OrderStatus.REFUNDED;
			order.refundReference = safe(request.refundReference());
			persist(order);
			audit.record("EXTERNAL_REFUND_RECORDED", order.userId, order.orderId,
					order.refundReference);
			return response(order, null, "已根据你的确认记录为退款完成。");
		}
	}

	public OrderResponse paid(PaymentNotification notification, String signature) {
		if (notification == null || blank(notification.orderId())
				|| blank(notification.transactionId())) {
			throw new IllegalArgumentException("支付回调字段不完整");
		}
		integrations.verifyPaymentNotification(signature);
		Order order = orders.get(notification.orderId());
		if (order == null) {
			throw new ApplicationException(HttpStatus.NOT_FOUND, "支付订单不存在");
		}
		synchronized (order) {
			if (order.mode != OrderMode.DIRECT_WECHAT_PAY) {
				throw new ApplicationException(HttpStatus.CONFLICT,
						"外部购票订单不接受微信支付回调");
			}
			if (order.status == OrderStatus.ISSUED) {
				return response(order, null, "支付回调已处理");
			}
			if (order.status != OrderStatus.AWAITING_PAYMENT) {
				throw new ApplicationException(HttpStatus.CONFLICT,
						"订单当前状态不能确认支付：" + order.status);
			}
			if (order.amountCents != notification.amountCents()) {
				throw new ApplicationException(HttpStatus.BAD_REQUEST,
						"支付金额与订单金额不一致");
			}
			order.status = OrderStatus.PAID;
			order.transactionId = notification.transactionId();
			persist(order);
			order.status = OrderStatus.ISSUING;
			persist(order);
			order.tickets = integrations.issue(order.orderId, order.offerIds);
			order.status = OrderStatus.ISSUED;
			persist(order);
			audit.record("PAYMENT_CONFIRMED_AND_ISSUED", order.userId,
					order.orderId, "ISSUED");
			return response(order, null, "支付确认成功，电子票券已生成。");
		}
	}

	public OrderResponse cancel(String orderId, CancelOrderRequest request) {
		if (request == null || blank(request.userId())) {
			throw new IllegalArgumentException("userId 为必填项");
		}
		Order order = owned(orderId, request.userId());
		synchronized (order) {
			if (order.status == OrderStatus.CANCELLED
					|| order.status == OrderStatus.REFUNDED) {
				return response(order, null, "取消请求已处理");
			}
			if (order.mode == OrderMode.EXTERNAL_CHECKOUT) {
				return cancelExternal(order, request);
			}
			if (order.status == OrderStatus.AWAITING_PAYMENT) {
				order.status = OrderStatus.CANCELLED;
				persist(order);
				audit.record("ORDER_CANCELLED", order.userId, order.orderId, "UNPAID");
				return response(order, null, "未支付订单已取消");
			}
			if (order.status != OrderStatus.ISSUED
					&& order.status != OrderStatus.PAID) {
				throw new ApplicationException(HttpStatus.CONFLICT,
						"订单当前不能取消：" + order.status);
			}
			order.status = OrderStatus.REFUNDING;
			persist(order);
			integrations.cancelAndRefund(order.orderId);
			order.status = OrderStatus.REFUNDED;
			persist(order);
			audit.record("REFUND_COMPLETED", order.userId, order.orderId, "REFUNDED");
			return response(order, null, "退款已受理并完成；实际到账时间以支付渠道为准。");
		}
	}

	private OrderResponse cancelExternal(Order order, CancelOrderRequest request) {
		if (order.status == OrderStatus.AWAITING_EXTERNAL_PAYMENT) {
			order.status = OrderStatus.CANCELLED;
			persist(order);
			audit.record("EXTERNAL_CHECKOUT_CANCELLED", order.userId,
					order.orderId, "UNPAID");
			return response(order, null, "未付款的半自动购票清单已取消。");
		}
		if (order.status == OrderStatus.EXTERNALLY_PAID
				|| order.status == OrderStatus.ISSUED) {
			order.status = OrderStatus.AWAITING_EXTERNAL_REFUND;
			persist(order);
			audit.record("EXTERNAL_REFUND_REQUIRED", order.userId, order.orderId,
					safe(request.reason()));
			return response(order, null, "该票由外部渠道收款，系统不能代为退款。"
					+ "请打开原购票入口申请退款，成功后把退款结果发给 Agent。");
		}
		if (order.status == OrderStatus.AWAITING_EXTERNAL_REFUND) {
			return response(order, null, "正在等待你到原购票渠道完成退款。");
		}
		throw new ApplicationException(HttpStatus.CONFLICT,
				"当前外部订单状态不能取消：" + order.status);
	}

	private void requireOption(String planId, String optionId, String userId) {
		PlanResponse plan = planService.getPlan(planId, userId);
		plan.options().stream().filter(item -> item.optionId().equals(optionId)).findFirst()
				.orElseThrow(() -> new ApplicationException(
						HttpStatus.BAD_REQUEST, "方案不属于该行程"));
	}

	private Order owned(String orderId, String userId) {
		Order order = orders.get(orderId);
		if (order == null && stateStore != null) {
			order = stateStore.get(TravelStateStore.ORDER, orderId, userId)
					.map(this::deserializeOrder).orElse(null);
			if (order != null) orders.put(orderId, order);
		}
		if (order == null || !order.userId.equals(userId)) {
			throw new ApplicationException(HttpStatus.NOT_FOUND, "订单不存在");
		}
		return order;
	}

	private void persist(Order order) {
		if (stateStore == null) return;
		StoredOrder stored = new StoredOrder(order.orderId, order.userId,
				order.amountCents, order.offerIds, order.mode, order.status,
				order.payment, order.checkout, order.providerOrderId,
				order.transactionId, order.refundReference, order.tickets);
		try {
			stateStore.put(TravelStateStore.ORDER, order.orderId, order.userId,
					objectMapper.writeValueAsString(stored),
					stateStore.defaultExpiry(TravelStateStore.ORDER));
		} catch (ApplicationException exception) {
			throw exception;
		} catch (Exception exception) {
			throw new ApplicationException(HttpStatus.INTERNAL_SERVER_ERROR,
					"无法保存订单状态", exception);
		}
	}

	private Order deserializeOrder(String json) {
		try {
			StoredOrder stored = objectMapper.readValue(json, StoredOrder.class);
			Order order = new Order(stored.orderId(), stored.userId(),
					stored.amountCents(), stored.offerIds(),
					stored.mode() == null
							? OrderMode.DIRECT_WECHAT_PAY : stored.mode());
			order.status = stored.status();
			order.payment = stored.payment();
			order.checkout = stored.checkout();
			order.providerOrderId = stored.providerOrderId();
			order.transactionId = stored.transactionId();
			order.refundReference = stored.refundReference();
			order.tickets = stored.tickets() == null
					? new ArrayList<>() : new ArrayList<>(stored.tickets());
			return order;
		} catch (Exception exception) {
			throw new ApplicationException(HttpStatus.INTERNAL_SERVER_ERROR,
					"无法读取订单状态", exception);
		}
	}

	private static OrderResponse response(Order order, PaymentInstruction payment,
			String message) {
		return new OrderResponse(order.orderId, order.status, order.amountCents,
				order.mode, payment, order.checkout, order.providerOrderId,
				order.tickets == null ? List.of() : List.copyOf(order.tickets), message);
	}

	private static void validateExternalRequest(ExternalCheckoutRequest request) {
		if (request == null || blank(request.userId()) || blank(request.planId())
				|| blank(request.optionId()) || blank(request.attractionId())
				|| blank(request.attractionName()) || blank(request.visitDate())
				|| blank(request.provider()) || blank(request.productName())
				|| blank(request.ticketType()) || blank(request.selectionReason())
				|| blank(request.sourceCheckedAt())) {
			throw new IllegalArgumentException(
					"购票必须明确景点、日期、渠道、具体商品、票种、选择理由和核验时间");
		}
		if (request.quantity() < 1 || request.quantity() > 50) {
			throw new IllegalArgumentException("购票数量必须在 1 到 50 之间");
		}
		String lowerUrl = safe(request.checkoutUrl()).toLowerCase();
		String lowerProduct = request.productName().toLowerCase();
		if (lowerUrl.isBlank()
				&& (blank(request.providerProductId()) || blank(request.providerSkuId()))) {
			throw new IllegalArgumentException(
					"创建直达实名付款会话必须提供交易供应商返回的 productId 和精确 skuId");
		}
		if (lowerUrl.contains("/search") || lowerUrl.contains("keyword=")
				|| lowerUrl.contains("query=") || lowerProduct.equals("门票")
				|| lowerProduct.equals("景区门票")) {
			throw new IllegalArgumentException(
					"当前链接或商品名称过于宽泛，必须选择具体票种的商品直达页");
		}
	}

	private static void validateHttpsUrl(String value) {
		try {
			URI uri = URI.create(value.trim());
			if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
				throw new IllegalArgumentException();
			}
		} catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("购票入口必须是有效的 HTTPS 地址", exception);
		}
	}

	private static void requireExternal(Order order) {
		if (order.mode != OrderMode.EXTERNAL_CHECKOUT) {
			throw new ApplicationException(HttpStatus.CONFLICT,
					"该订单不是半自动购票订单");
		}
	}

	private static List<ElectronicTicket> sanitizeTickets(List<ElectronicTicket> tickets) {
		if (tickets == null) return new ArrayList<>();
		return tickets.stream()
				.filter(item -> item != null && !blank(item.ticketNo()))
				.map(item -> new ElectronicTicket(item.ticketNo().trim(),
						safe(item.attractionName()), safe(item.code()),
						blank(item.status())
								? "EXTERNAL_ISSUED" : item.status().trim()))
				.toList();
	}

	private static List<String> cleanList(List<String> values) {
		if (values == null) return List.of();
		return values.stream().filter(value -> !blank(value))
				.map(String::trim).distinct().toList();
	}

	private static long optionalMoney(Long value) {
		if (value == null) return 0L;
		if (value < 0) throw new IllegalArgumentException("金额不能为负数");
		return value;
	}

	private static String safe(String value) {
		return value == null ? "" : value.trim();
	}

	private static boolean blank(String value) {
		return value == null || value.isBlank();
	}

	private static String newId(String prefix) {
		return prefix + UUID.randomUUID().toString()
				.replace("-", "").substring(0, 16);
	}

	private static final class Order {
		private final String orderId;
		private final String userId;
		private volatile long amountCents;
		private final List<String> offerIds;
		private final OrderMode mode;
		private volatile OrderStatus status = OrderStatus.AWAITING_PAYMENT;
		private volatile PaymentInstruction payment;
		private volatile ExternalCheckoutInstruction checkout;
		private volatile String providerOrderId;
		private volatile String transactionId;
		private volatile String refundReference;
		private volatile List<ElectronicTicket> tickets = new ArrayList<>();

		private Order(String orderId, String userId, long amountCents,
				List<String> offerIds, OrderMode mode) {
			this.orderId = orderId;
			this.userId = userId;
			this.amountCents = amountCents;
			this.offerIds = List.copyOf(offerIds);
			this.mode = mode;
		}
	}

	private record StoredOrder(String orderId, String userId, long amountCents,
			List<String> offerIds, OrderMode mode, OrderStatus status,
			PaymentInstruction payment, ExternalCheckoutInstruction checkout,
			String providerOrderId, String transactionId, String refundReference,
			List<ElectronicTicket> tickets) { }
}
