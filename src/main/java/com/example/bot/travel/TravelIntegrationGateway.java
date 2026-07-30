package com.example.bot.travel;

import static com.example.bot.travel.TravelModels.*;

import java.time.Instant;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.example.bot.ApplicationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Boundary for ticketing, identity, payment and WeChat delivery.  A production installation
 * should replace this class with partner-specific adapters; this baseline never marks a
 * transaction as real when credentials have not been configured.
 */
@Service
public class TravelIntegrationGateway {
	private final boolean demoEnabled;
	private final String identityDemoToken;
	private final String supplierApiUrl;
	private final String supplierApiKey;
	private final String paymentMerchantId;
	private final String paymentCallbackSecret;
	private final Map<String, TicketQuote> quotes = new ConcurrentHashMap<>();
	private final ObjectMapper objectMapper;
	private final TravelStateStore stateStore;

	@Autowired
	public TravelIntegrationGateway(@Value("${travel.integration.demo-enabled:false}") boolean demoEnabled,
			@Value("${travel.identity.demo-token:}") String identityDemoToken,
			@Value("${travel.supplier.api-url:}") String supplierApiUrl,
			@Value("${travel.supplier.api-key:}") String supplierApiKey,
			@Value("${travel.wechat-pay.merchant-id:}") String paymentMerchantId,
			@Value("${travel.wechat-pay.callback-secret:}") String paymentCallbackSecret,
			ObjectMapper objectMapper, TravelStateStore stateStore) {
		this.demoEnabled = demoEnabled;
		this.identityDemoToken = identityDemoToken;
		this.supplierApiUrl = supplierApiUrl;
		this.supplierApiKey = supplierApiKey;
		this.paymentMerchantId = paymentMerchantId;
		this.paymentCallbackSecret = paymentCallbackSecret;
		this.objectMapper = objectMapper;
		this.stateStore = stateStore;
	}

	public TravelIntegrationGateway(boolean demoEnabled, String identityDemoToken,
			String supplierApiUrl, String supplierApiKey, String paymentMerchantId,
			String paymentCallbackSecret) {
		this(demoEnabled, identityDemoToken, supplierApiUrl, supplierApiKey, paymentMerchantId,
				paymentCallbackSecret, new ObjectMapper(), null);
	}

	public TicketQuote quote(String attractionId) {
		if (!demoEnabled) {
			return new TicketQuote("", attractionId, List.of(), "UNCONFIGURED", false, "",
					"尚未配置票务供应商。请设置 TRAVEL_SUPPLIER_API_URL、TRAVEL_SUPPLIER_API_KEY 并实现供应商适配器后再查询实时票价和库存。");
		}
		String quoteId = "quote_" + compactId();
		TicketQuote quote = new TicketQuote(quoteId, attractionId, List.of(
				new TicketOffer("offer_" + compactId(), "成人全价票（演示）", 12_000, "DEMO_AVAILABLE", "演示环境可退款"),
				new TicketOffer("offer_" + compactId(), "优待票（演示）", 6_000, "DEMO_AVAILABLE", "演示环境可退款")),
				"DEMO", false, Instant.now().plus(10, ChronoUnit.MINUTES).toString(),
				"这是演示报价，不能用于真实购票。关闭 TRAVEL_INTEGRATION_DEMO_ENABLED 后系统只接受已接入的真实供应商结果。");
		quotes.put(quoteId, quote);
		if (stateStore != null) {
			stateStore.put(TravelStateStore.QUOTE, quoteId, "", serialize(quote),
					Instant.parse(quote.expiresAt()).toEpochMilli());
		}
		return quote;
	}

	public long validateQuoteAndCalculateAmount(String quoteId, List<String> offerIds) {
		TicketQuote quote = quotes.get(quoteId);
		if (quote == null && stateStore != null) {
			quote = stateStore.getUnscoped(TravelStateStore.QUOTE, quoteId)
					.map(json -> deserialize(json, TicketQuote.class)).orElse(null);
			if (quote != null) quotes.put(quoteId, quote);
		}
		if (quote == null) throw new ApplicationException(HttpStatus.BAD_REQUEST, "报价不存在或已失效，请重新查询票价");
		if (Instant.parse(quote.expiresAt()).isBefore(Instant.now())) {
			quotes.remove(quoteId);
			throw new ApplicationException(HttpStatus.BAD_REQUEST, "报价已过期，请重新查询票价");
		}
		long total = 0;
		for (String offerId : offerIds) {
			TicketOffer offer = quote.offers().stream().filter(item -> item.offerId().equals(offerId)).findFirst()
					.orElseThrow(() -> new ApplicationException(HttpStatus.BAD_REQUEST, "所选票种不属于该报价"));
			total += offer.unitPriceCents();
		}
		return total;
	}

	public void assertIdentityVerified(String token) {
		if (!demoEnabled || identityDemoToken == null || identityDemoToken.isBlank()) {
			throw new ApplicationException(HttpStatus.SERVICE_UNAVAILABLE,
					"身份核验服务尚未配置；真实购票前必须接入实名核验服务。身份令牌不会被持久化。");
		}
		if (!identityDemoToken.equals(token)) throw new ApplicationException(HttpStatus.FORBIDDEN, "身份核验未通过");
	}

	/**
	 * Requests a provider-hosted checkout session for one already selected SKU.
	 * The returned page owns login, real-name collection and payment; this service
	 * never receives identity document numbers or payment credentials.
	 */
	public String createHostedCheckout(ExternalCheckoutRequest selection) {
		if (!configured(supplierApiUrl, supplierApiKey)) {
			throw new ApplicationException(HttpStatus.SERVICE_UNAVAILABLE,
					"票务供应商结算接口尚未配置，无法生成已选票种的实名与付款页面");
		}
		try {
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("planId", selection.planId());
			payload.put("optionId", selection.optionId());
			payload.put("attractionId", selection.attractionId());
			payload.put("attractionName", selection.attractionName());
			payload.put("visitDate", selection.visitDate());
			payload.put("provider", selection.provider());
			payload.put("providerProductId", selection.providerProductId());
			payload.put("providerSkuId", selection.providerSkuId());
			payload.put("productName", selection.productName());
			payload.put("ticketType", selection.ticketType());
			payload.put("quantity", selection.quantity());
			payload.put("unitPriceCents", selection.unitPriceCents());
			payload.put("expectedAmountCents", selection.expectedAmountCents());
			payload.put("bundle", selection.bundle());
			payload.put("bundleContents", selection.bundleContents());
			payload.put("refundRule", selection.refundRule());
			payload.put("returnContext", Map.of("channel", "WECHAT_AGENT"));
			HttpRequest request = HttpRequest.newBuilder(URI.create(supplierApiUrl.trim()))
					.timeout(Duration.ofSeconds(20))
					.header("Authorization", "Bearer " + supplierApiKey.trim())
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(
							objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
					.build();
			HttpResponse<String> response = HttpClient.newBuilder()
					.connectTimeout(Duration.ofSeconds(10)).build()
					.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				throw new ApplicationException(HttpStatus.BAD_GATEWAY,
						"票务供应商创建结算会话失败，HTTP " + response.statusCode());
			}
			JsonNode root = objectMapper.readTree(response.body());
			String checkoutUrl = root.path("checkoutUrl").asText("");
			if (checkoutUrl.isBlank()) checkoutUrl = root.path("data").path("checkoutUrl").asText("");
			validateHostedCheckoutUrl(checkoutUrl);
			return checkoutUrl;
		} catch (ApplicationException exception) {
			throw exception;
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new ApplicationException(HttpStatus.BAD_GATEWAY, "票务供应商结算请求被中断", exception);
		} catch (Exception exception) {
			throw new ApplicationException(HttpStatus.BAD_GATEWAY, "票务供应商结算接口暂时不可用", exception);
		}
	}

	public PaymentInstruction createWechatPayment(String orderId, long amountCents) {
		if (!demoEnabled) {
			throw new ApplicationException(HttpStatus.SERVICE_UNAVAILABLE,
					"微信支付尚未配置；请接入微信支付 V3（商户号、证书、APIv3 密钥和回调验签）后再发起支付。");
		}
		return new PaymentInstruction("WECHAT_JSAPI", "demo_prepay_" + compactId(), "prepay_id=demo",
				Instant.now().plus(15, ChronoUnit.MINUTES).toString(), true);
	}

	public void verifyPaymentNotification(String signature) {
		if (demoEnabled && "demo-signature".equals(signature)) return;
		if (paymentCallbackSecret == null || paymentCallbackSecret.isBlank()) {
			throw new ApplicationException(HttpStatus.SERVICE_UNAVAILABLE, "支付回调验签尚未配置");
		}
		throw new ApplicationException(HttpStatus.UNAUTHORIZED,
				"当前基础实现不接受未接入微信支付 V3 平台证书验签的回调");
	}

	public List<ElectronicTicket> issue(String orderId, List<String> offerIds) {
		if (!demoEnabled) throw new ApplicationException(HttpStatus.SERVICE_UNAVAILABLE, "出票供应商尚未配置");
		return offerIds.stream().map(offerId -> {
			String deterministic = UUID.nameUUIDFromBytes((orderId + "|" + offerId)
					.getBytes(StandardCharsets.UTF_8)).toString().replace("-", "").substring(0, 16);
			return new ElectronicTicket("DEMO-" + deterministic, "演示景点",
					"DEMO-" + deterministic, "ISSUED_DEMO");
		}).toList();
	}

	public void cancelAndRefund(String orderId) {
		if (!demoEnabled) throw new ApplicationException(HttpStatus.SERVICE_UNAVAILABLE, "退款供应商和支付退款接口尚未配置");
	}

	public String configurationSummary() {
		return "demo=" + demoEnabled + ", supplierConfigured=" + configured(supplierApiUrl, supplierApiKey)
				+ ", paymentConfigured=" + configured(paymentMerchantId, paymentCallbackSecret);
	}

	private static boolean configured(String first, String second) {
		return first != null && !first.isBlank() && second != null && !second.isBlank();
	}
	private static void validateHostedCheckoutUrl(String value) {
		try {
			URI uri = URI.create(value == null ? "" : value.trim());
			String lower = value == null ? "" : value.toLowerCase();
			if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
					|| lower.contains("xxxx") || lower.contains("example.com")
					|| lower.contains("placeholder")) {
				throw new IllegalArgumentException();
			}
		} catch (IllegalArgumentException exception) {
			throw new ApplicationException(HttpStatus.BAD_GATEWAY,
					"票务供应商未返回有效的 HTTPS 实名付款页面");
		}
	}
	private String serialize(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (Exception exception) {
			throw new ApplicationException(HttpStatus.INTERNAL_SERVER_ERROR, "无法保存票务报价", exception);
		}
	}
	private <T> T deserialize(String json, Class<T> type) {
		try {
			return objectMapper.readValue(json, type);
		} catch (Exception exception) {
			throw new ApplicationException(HttpStatus.INTERNAL_SERVER_ERROR, "无法读取票务报价", exception);
		}
	}
	private static String compactId() { return UUID.randomUUID().toString().replace("-", "").substring(0, 16); }
}
