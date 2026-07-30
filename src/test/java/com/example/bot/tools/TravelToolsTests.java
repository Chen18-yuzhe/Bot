package com.example.bot.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.bot.agent.AgentToolException;
import com.example.bot.agent.ErrorKind;
import com.example.bot.travel.IdempotencyService;
import com.example.bot.travel.TravelAuditService;
import com.example.bot.travel.TravelIntegrationGateway;
import com.example.bot.travel.TravelModels.PlanRequest;
import com.example.bot.travel.TravelModels.PlanResponse;
import com.example.bot.travel.TravelModels.OrderResponse;
import com.example.bot.travel.TravelModels.OrderStatus;
import com.example.bot.travel.TravelModels.TicketQuote;
import com.example.bot.travel.TravelOrderService;
import com.example.bot.travel.TravelPlanService;
import com.fasterxml.jackson.databind.ObjectMapper;

class TravelToolsTests {
	private final ObjectMapper objectMapper = new ObjectMapper();
	private final TravelPlanService plans = new TravelPlanService();

	@Test
	void planToolCreatesThreeComparablePlansForCurrentSession() throws Exception {
		TravelPlanTool tool = new TravelPlanTool(plans, objectMapper);
		String arguments = """
				{"action":"create","destination":"杭州","departureDate":"%s","days":3,
				 "travelers":2,"budgetCents":300000,"interests":["历史","美食"]}
				""".formatted(LocalDate.now().plusDays(20));

		ToolResult result = tool.execute(objectMapper.readTree(arguments),
				new ToolContext("wx-user", "做一份杭州三日攻略"));
		PlanResponse response = objectMapper.readValue(result.content(), PlanResponse.class);

		assertEquals(3, response.options().size());
		assertEquals("杭州", response.request().destination());
		assertFalse(response.options().get(0).itinerary().isEmpty());

		ToolResult latest = tool.execute(objectMapper.readTree("{\"action\":\"latest\"}"),
				new ToolContext("wx-user", "第二个方案"));
		assertEquals(response.planId(),
				objectMapper.readValue(latest.content(), PlanResponse.class).planId());
	}

	@Test
	void ticketToolReturnsFrameworkDegradationWhenSupplierIsMissing() throws Exception {
		PlanResponse plan = plans.createPlan(request("wx-user"));
		TravelIntegrationGateway gateway = new TravelIntegrationGateway(false, "", "", "", "", "");
		TravelTicketTool tool = new TravelTicketTool(plans, gateway, objectMapper);
		String arguments = "{\"planId\":\"" + plan.planId() + "\",\"attractionId\":\"poi-1\"}";

		AgentToolException exception = assertThrows(AgentToolException.class,
				() -> tool.execute(objectMapper.readTree(arguments),
						new ToolContext("wx-user", "查一下门票")));

		assertNotNull(exception.degradedContent());
		assertFalse(exception.degradedContent().isBlank());
	}

	@Test
	void transportToolDegradesInsteadOfInventingRailOrFlightOffers() throws Exception {
		TravelTransportTool tool = new TravelTransportTool("", "", "https://test.api.amadeus.com",
				"", "", objectMapper);

		AgentToolException rail = assertThrows(AgentToolException.class,
				() -> tool.execute(objectMapper.readTree("""
						{"mode":"rail","origin":"北京","destination":"杭州",
						 "departureDate":"2026-08-10","travelers":2}
						"""), new ToolContext("wx-user", "查火车")));
		AgentToolException flight = assertThrows(AgentToolException.class,
				() -> tool.execute(objectMapper.readTree("""
						{"mode":"flight","originCode":"BJS","destinationCode":"HGH",
						 "departureDate":"2026-08-10","travelers":2}
						"""), new ToolContext("wx-user", "查航班")));

		assertNotNull(rail.degradedContent());
		assertNotNull(flight.degradedContent());
		assertTrue(rail.degradedContent().contains("12306"));
		assertTrue(flight.degradedContent().contains("不得伪造"));
	}

	@Test
	void fliggyToolDegradesCleanlyWhenReadOnlySourceIsDisabled() throws Exception {
		TravelFliggySearchTool tool = new TravelFliggySearchTool(false, "flyai", "", 10, objectMapper);

		AgentToolException exception = assertThrows(AgentToolException.class,
				() -> tool.execute(objectMapper.readTree("""
						{"action":"poi","city":"杭州","keyword":"西湖"}
						"""), new ToolContext("wx-user", "查一下西湖门票")));

		assertNotNull(exception.degradedContent());
		assertTrue(exception.degradedContent().contains("FLIGGY_FLYAI"));
		assertTrue(exception.degradedContent().contains("\"readOnly\":true"));
	}

	@Test
	void ticketMarketToolDegradesWithoutInventingSkuPriceOrInventory() throws Exception {
		TravelTicketMarketTool tool = new TravelTicketMarketTool("", "", objectMapper);

		AgentToolException exception = assertThrows(AgentToolException.class,
				() -> tool.execute(objectMapper.readTree("""
						{"destination":"杭州","attractionName":"西湖游船",
						 "visitDate":"2026-08-10","quantity":2}
						"""), new ToolContext("wx-user", "查成人票价格库存")));

		assertNotNull(exception.degradedContent());
		assertTrue(exception.degradedContent().contains("UNCONFIGURED_TICKET_MARKET"));
		assertTrue(exception.degradedContent().contains("providerSkuId"));
	}

	@Test
	void orderToolHandsOffWhenIdentityOrPaymentAuthorizationIsMissing() throws Exception {
		PlanResponse plan = plans.createPlan(request("wx-user"));
		TravelIntegrationGateway gateway = new TravelIntegrationGateway(true, "verified-token", "", "", "", "");
		TicketQuote quote = gateway.quote(plan.options().get(0).itinerary().get(0).attractions().get(0).id());
		TravelOrderService orderService = new TravelOrderService(plans, gateway, new TravelAuditService());
		TravelOrderTool tool = new TravelOrderTool(orderService, new IdempotencyService(), objectMapper);
		String arguments = """
				{"action":"create","confirmed":true,"planId":"%s","optionId":"%s",
				 "quoteId":"%s","ticketOfferIds":["%s"]}
				""".formatted(plan.planId(), plan.options().get(0).optionId(), quote.quoteId(),
				quote.offers().get(0).offerId());

		AgentToolException exception = assertThrows(AgentToolException.class,
				() -> tool.execute(objectMapper.readTree(arguments),
						new ToolContext("wx-user", "我确认购票，请帮我下单")));

		assertEquals(ErrorKind.AUTH_REQUIRED, exception.kind());
		assertNotNull(exception.humanPrompt());
	}

	@Test
	void checkoutToolCreatesProductDirectionChecklistWithoutClaimingSkuSelection() throws Exception {
		PlanResponse plan = plans.createPlan(request("wx-user"));
		TravelIntegrationGateway gateway = new TravelIntegrationGateway(false, "", "", "", "", "");
		TravelOrderService orderService = new TravelOrderService(
				plans, gateway, new TravelAuditService());
		TravelCheckoutTool tool = new TravelCheckoutTool(
				orderService, new IdempotencyService(), objectMapper);
		String arguments = """
				{
				  "action":"create",
				  "confirmed":true,
				  "planId":"%s",
				  "optionId":"%s",
				  "attractionId":"west-lake-cruise",
				  "attractionName":"西湖游船",
				  "visitDate":"%s",
				  "provider":"景区官方渠道",
				  "productName":"西湖环湖游成人票",
				  "ticketType":"成人票",
				  "quantity":2,
				  "unitPriceCents":5500,
				  "expectedAmountCents":11000,
				  "bundle":false,
				  "bundleContents":[],
				  "refundRule":"使用前按渠道页面规则退改",
				  "selectionReason":"只需要环湖游，不选择含演出或餐饮的组合套票",
				  "checkoutUrl":"https://tickets.example.com/products/west-lake-cruise-adult",
				  "sourceCheckedAt":"2026-07-28T12:00:00+08:00"
				}
				""".formatted(plan.planId(), plan.options().get(0).optionId(),
				LocalDate.now().plusDays(20));

		ToolResult result = tool.execute(objectMapper.readTree(arguments),
				new ToolContext("wx-user", "就这个，给我生成购票清单并跳转购票"));
		OrderResponse response = objectMapper.readValue(result.content(), OrderResponse.class);

		assertEquals(OrderStatus.AWAITING_EXTERNAL_PAYMENT, response.status());
		assertFalse(response.checkout().exactProductMatched());
		assertEquals("西湖环湖游成人票", response.checkout().productName());
		assertEquals(2, response.checkout().quantity());
		assertFalse(response.checkout().bundle());
		assertEquals("PRODUCT_DETAIL", response.checkout().entryType());
		assertFalse(response.checkout().directPaymentEntry());
		assertTrue(response.checkout().requiresFinalSelection());
	}

	@Test
	void checkoutToolRejectsGenericSearchPages() throws Exception {
		PlanResponse plan = plans.createPlan(request("wx-user"));
		TravelOrderService orderService = new TravelOrderService(plans,
				new TravelIntegrationGateway(false, "", "", "", "", ""),
				new TravelAuditService());
		TravelCheckoutTool tool = new TravelCheckoutTool(
				orderService, new IdempotencyService(), objectMapper);
		String arguments = """
				{
				  "action":"create","confirmed":true,
				  "planId":"%s","optionId":"%s",
				  "attractionId":"poi-1","attractionName":"西湖游船",
				  "visitDate":"%s","provider":"某平台",
				  "productName":"景区门票","ticketType":"成人票","quantity":1,
				  "bundle":false,"bundleContents":[],
				  "selectionReason":"价格低",
				  "checkoutUrl":"https://tickets.example.com/search?query=west-lake",
				  "sourceCheckedAt":"2026-07-28T12:00:00+08:00"
				}
				""".formatted(plan.planId(), plan.options().get(0).optionId(),
				LocalDate.now().plusDays(20));

		assertThrows(IllegalArgumentException.class,
				() -> tool.execute(objectMapper.readTree(arguments),
						new ToolContext("wx-user", "我要购票")));
	}

	private static PlanRequest request(String userId) {
		return new PlanRequest(userId, "杭州", LocalDate.now().plusDays(20).toString(),
				3, 2, 300_000L, List.of("历史", "美食"));
	}
}
