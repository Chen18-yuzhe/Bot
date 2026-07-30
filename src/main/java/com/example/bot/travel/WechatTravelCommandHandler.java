package com.example.bot.travel;

import static com.example.bot.travel.TravelModels.*;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * A deterministic WeChat entry point. It avoids sending identity details in chat: payment and
 * identity verification are intentionally performed by the secure order API / Mini Program.
 */
@Component
public class WechatTravelCommandHandler {
	private final TravelPlanService plans;
	private final TravelIntegrationGateway integrations;

	public WechatTravelCommandHandler(TravelPlanService plans, TravelIntegrationGateway integrations) {
		this.plans = plans;
		this.integrations = integrations;
	}

	public String handle(String sessionId, String input) {
		if (input == null || !input.trim().startsWith("/trip")) return null;
		String command = input.trim();
		if (command.equalsIgnoreCase("/trip") || command.equalsIgnoreCase("/trip help")) return help();
		String[] parts = command.split("\\s+", 3);
		if (parts.length == 3 && "quote".equalsIgnoreCase(parts[1])) return quote(sessionId, parts[2]);
		if (parts.length < 2) return help();
		return plan(sessionId, command.substring(5).trim());
	}

	private String plan(String sessionId, String payload) {
		// /trip 杭州 2026-08-20 3 2 3000 历史,美食  (预算单位：元)
		String[] fields = payload.split("\\s+", 6);
		if (fields.length < 5) return "信息不完整。" + help();
		try {
			long budgetCents = Math.multiplyExact(Long.parseLong(fields[4]), 100L);
			List<String> interests = fields.length == 6 ? Arrays.stream(fields[5].split("[,，、]")).map(String::trim).filter(v -> !v.isBlank()).toList() : List.of();
			PlanResponse response = plans.createPlan(new PlanRequest(sessionId, fields[0], fields[1],
					Integer.valueOf(fields[2]), Integer.valueOf(fields[3]), budgetCents, interests));
			StringBuilder reply = new StringBuilder("已生成 ").append(response.request().destination()).append(" ").append(response.request().days()).append(" 日攻略：\n");
			for (PlanOption option : response.options()) {
				reply.append(option.optionId()).append("｜").append(option.title()).append("｜预估 ")
						.append(option.estimatedCostCents() / 100).append(" 元\n");
			}
			reply.append("方案编号：").append(response.planId()).append("\n").append(response.providers().note())
					.append("\n查询票务：/trip quote ").append(response.planId()).append(" <景点ID>");
			return reply.toString();
		} catch (RuntimeException exception) {
			return "无法生成攻略：" + exception.getMessage() + "\n" + help();
		}
	}

	private String quote(String sessionId, String payload) {
		String[] fields = payload.split("\\s+");
		if (fields.length != 2) return "用法：/trip quote <方案编号> <景点ID>";
		// Command chat is scoped by iLink session; quote only accepts a plan generated in the same session.
		plans.getPlan(fields[0], sessionId);
		TicketQuote result = integrations.quote(fields[1]);
		if (result.offers().isEmpty()) return result.notice();
		StringBuilder reply = new StringBuilder("报价 ").append(result.quoteId()).append("（有效至 ").append(result.expiresAt()).append("）\n");
		for (TicketOffer offer : result.offers()) reply.append(offer.offerId()).append("｜").append(offer.name()).append("｜").append(offer.unitPriceCents() / 100).append(" 元｜").append(offer.inventory()).append("\n");
		return reply.append(result.notice()).append("\n身份核验、支付及出票请通过安全订单接口/小程序完成，切勿在聊天中发送证件号。 ").toString();
	}

	private static String help() {
		return "旅游攻略用法：/trip <目的地> <出发日期yyyy-MM-dd> <天数> <人数> <预算元> <兴趣逗号分隔>\n例如：/trip 杭州 "
				+ LocalDate.now().plusDays(30) + " 3 2 3000 历史,美食\n票务查询：/trip quote <方案编号> <景点ID>";
	}
}
