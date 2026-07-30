package com.example.bot.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.example.bot.travel.TravelModels.ConfirmOrderRequest;
import com.example.bot.travel.TravelModels.ExternalCheckoutRequest;
import com.example.bot.travel.TravelModels.OrderMode;
import com.example.bot.travel.TravelModels.OrderStatus;
import com.example.bot.travel.TravelModels.PlanRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

class TravelStateStoreTests {
	@TempDir
	Path tempDirectory;

	@Test
	void plansAndOrdersCanBeRecoveredAfterServiceRestart() {
		ObjectMapper objectMapper = new ObjectMapper();
		TravelStateStore store = new TravelStateStore(
				tempDirectory.resolve("travel.db").toString(), true, 30, 365);
		TravelPlanService firstPlans = new TravelPlanService(objectMapper, store);
		var plan = firstPlans.createPlan(new PlanRequest("wx-user", "杭州",
				LocalDate.now().plusDays(30).toString(), 3, 2, 300_000L,
				List.of("历史", "美食")));
		TravelIntegrationGateway gateway = new TravelIntegrationGateway(true, "verified",
				"", "", "", "", objectMapper, store);
		var quote = gateway.quote(plan.options().get(0).itinerary().get(0).attractions().get(0).id());
		TravelOrderService firstOrders = new TravelOrderService(firstPlans, gateway,
				new TravelAuditService(), objectMapper, store);
		var order = firstOrders.confirm(new ConfirmOrderRequest("wx-user", plan.planId(),
				plan.options().get(0).optionId(), quote.quoteId(),
				List.of(quote.offers().get(0).offerId()), "verified"));

		TravelPlanService restartedPlans = new TravelPlanService(objectMapper, store);
		TravelOrderService restartedOrders = new TravelOrderService(restartedPlans, gateway,
				new TravelAuditService(), objectMapper, store);

		assertEquals(plan.planId(), restartedPlans.getPlan(plan.planId(), "wx-user").planId());
		assertEquals(plan.planId(), restartedPlans.getLatestPlan("wx-user").planId());
		assertEquals(plan.planId(), restartedPlans.getLatestPlan("wx-user").planId());
		assertEquals(OrderStatus.AWAITING_PAYMENT,
				restartedOrders.get(order.orderId(), "wx-user").status());
	}

	@Test
	void externalCheckoutCanBeRecoveredWithoutPaymentCredentials() {
		ObjectMapper objectMapper = new ObjectMapper();
		TravelStateStore store = new TravelStateStore(
				tempDirectory.resolve("external-travel.db").toString(), true, 30, 365);
		TravelPlanService plans = new TravelPlanService(objectMapper, store);
		var plan = plans.createPlan(new PlanRequest("wx-user", "杭州",
				LocalDate.now().plusDays(30).toString(), 3, 2, 300_000L,
				List.of("历史", "美食")));
		TravelIntegrationGateway gateway = new TravelIntegrationGateway(false, "",
				"", "", "", "", objectMapper, store);
		TravelOrderService firstOrders = new TravelOrderService(plans, gateway,
				new TravelAuditService(), objectMapper, store);
		var checkout = firstOrders.createExternalCheckout(new ExternalCheckoutRequest(
				"wx-user", plan.planId(), plan.options().get(0).optionId(),
				"west-lake-cruise", "西湖游船",
				LocalDate.now().plusDays(30).toString(), "景区官方渠道",
				"", "",
				"西湖环湖游成人票", "成人票", 2, 5_500L, 11_000L,
				false, List.of(), "使用前按页面规则退改",
				"只选环湖游，不购买包含演出和餐饮的套票",
				"https://tickets.example.com/products/west-lake-cruise-adult",
				"2026-07-28T12:00:00+08:00"));

		TravelOrderService restartedOrders = new TravelOrderService(
				new TravelPlanService(objectMapper, store), gateway,
				new TravelAuditService(), objectMapper, store);
		var restored = restartedOrders.get(checkout.orderId(), "wx-user");

		assertEquals(OrderMode.EXTERNAL_CHECKOUT, restored.mode());
		assertEquals(OrderStatus.AWAITING_EXTERNAL_PAYMENT, restored.status());
		assertEquals("西湖环湖游成人票", restored.checkout().productName());
	}
}
