package com.example.bot.travel;

import java.util.List;

/** API models for the travel agent. Amounts are expressed in fen to avoid floating point errors. */
public final class TravelModels {
	private TravelModels() { }

	public record PlanRequest(String userId, String destination, String departureDate, Integer days,
			Integer travelers, Long budgetCents, List<String> interests) { }

	public record PlanResponse(String planId, RequestSummary request, List<PlanOption> options,
			ProviderStatus providers, String generatedAt) { }

	public record RequestSummary(String destination, String departureDate, int days, int travelers,
			long budgetCents, List<String> interests) { }

	public record PlanOption(String optionId, String title, String positioning, long estimatedCostCents,
			long remainingBudgetCents, List<DayItinerary> itinerary, List<String> cautions) { }

	public record DayItinerary(int day, String date, String route, List<Attraction> attractions,
			String weatherAdvice, long estimatedCostCents) { }

	public record Attraction(String id, String name, String introduction, String visitDuration,
			String transportTip) { }

	public record ProviderStatus(String map, String attractions, String weather, String tickets,
			String note) { }

	public record TicketQuoteRequest(String planId, String attractionId) { }

	public record TicketQuote(String quoteId, String attractionId, List<TicketOffer> offers,
			String source, boolean live, String expiresAt, String notice) { }

	public record TicketOffer(String offerId, String name, long unitPriceCents, String inventory,
			String refundRule) { }

	public record ConfirmOrderRequest(String userId, String planId, String optionId, String quoteId,
			List<String> ticketOfferIds, String identityVerificationToken) { }

	public record ExternalCheckoutRequest(String userId, String planId, String optionId,
			String attractionId, String attractionName, String visitDate, String provider,
			String providerProductId, String providerSkuId,
			String productName, String ticketType, int quantity, Long unitPriceCents,
			Long expectedAmountCents, boolean bundle, List<String> bundleContents,
			String refundRule, String selectionReason, String checkoutUrl,
			String sourceCheckedAt) { }

	public record ExternalPurchaseRecordRequest(String userId, String providerOrderId,
			Long actualAmountCents, String paidAt, List<ElectronicTicket> tickets) { }

	public record ExternalRefundRecordRequest(String userId, String refundReference,
			String refundedAt) { }

	public record OrderResponse(String orderId, OrderStatus status, long amountCents,
			OrderMode mode, PaymentInstruction payment, ExternalCheckoutInstruction checkout,
			String providerOrderId, List<ElectronicTicket> tickets, String message) { }

	public record PaymentInstruction(String channel, String prepayId, String packageValue,
			String expiresAt, boolean simulated) { }

	public record ExternalCheckoutInstruction(String provider, String checkoutUrl,
			String attractionName, String visitDate, String productName, String ticketType,
			int quantity, long unitPriceCents, long expectedAmountCents, boolean bundle,
			List<String> bundleContents, String refundRule, String selectionReason,
			String sourceCheckedAt, String entryType, boolean exactProductMatched,
			boolean directPaymentEntry, boolean requiresFinalSelection,
			List<String> steps) { }

	public record PaymentNotification(String orderId, String transactionId, long amountCents, String paidAt) { }

	public record CancelOrderRequest(String userId, String reason) { }

	public record ElectronicTicket(String ticketNo, String attractionName, String code, String status) { }

	public enum OrderMode { DIRECT_WECHAT_PAY, EXTERNAL_CHECKOUT }

	public enum OrderStatus {
		AWAITING_PAYMENT, AWAITING_EXTERNAL_PAYMENT, PAID, EXTERNALLY_PAID,
		ISSUING, ISSUED, CANCELLED, REFUNDING, AWAITING_EXTERNAL_REFUND, REFUNDED
	}
}
