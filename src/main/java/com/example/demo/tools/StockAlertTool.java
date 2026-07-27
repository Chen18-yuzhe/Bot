package com.example.demo.tools;

import java.util.List;

import org.springframework.stereotype.Component;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.example.demo.alert.AlertPushService;
import com.example.demo.alert.StockAlertService;
import com.example.demo.alert.StockAlertService.AlertRule;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.gson.JsonParser;

@Component
public class StockAlertTool implements AssistantTool {
	private static final FunctionDefinition DEFINITION = FunctionDefinition.builder()
			.name("manage_stock_alert")
			.description("创建、查看、启停或删除 A 股股价预警。预警触发后会自动推送微信消息，无需用户轮询。"
					+ "用户说“跌破/低于某价提醒我”用 PRICE_BELOW，“涨到/突破某价提醒我”用 PRICE_ABOVE，"
					+ "“当日涨跌超过百分比”用 DAILY_CHANGE_ABOVE，止盈/止损用 TAKE_PROFIT/STOP_LOSS，"
					+ "持仓收益率/亏损用 RETURN_ABOVE/LOSS_ABOVE。必须提供 6 位股票代码；"
					+ "用户只说公司名时先询问代码。删除或启停前先用 list 查到预警 ID。")
			.parameters(JsonParser.parseString("""
					{"type":"object","properties":{"action":{"type":"string","enum":["create","list","delete","enable","disable"]},"stockCode":{"type":"string","pattern":"^[0-9]{6}([.](SH|SZ|BJ))?$","description":"create 时的 6 位 A 股代码，例如 600519"},"ruleType":{"type":"string","enum":["PRICE_ABOVE","PRICE_BELOW","RETURN_ABOVE","LOSS_ABOVE","DAILY_CHANGE_ABOVE","TAKE_PROFIT","STOP_LOSS"],"description":"create 时的预警类型"},"threshold":{"type":"number","description":"create 时的阈值：价格类填价格，百分比类填百分比数值，例如 5 表示 5%"},"operator":{"type":"string","enum":["GT","GTE","LT","LTE"],"description":"比较方向，通常省略，由类型自动选择"},"cooldownMinutes":{"type":"integer","minimum":0,"maximum":525600,"description":"两次触发的最小间隔分钟数，默认 60"},"id":{"type":"string","description":"delete/enable/disable 时的预警 ID"}},"required":["action"],"additionalProperties":false}
					""").getAsJsonObject()).build();

	private final StockAlertService alertService;
	private final AlertPushService pushService;

	public StockAlertTool(StockAlertService alertService, AlertPushService pushService) {
		this.alertService = alertService;
		this.pushService = pushService;
	}

	@Override public FunctionDefinition definition() { return DEFINITION; }

	@Override
	public ToolResult execute(JsonNode arguments, ToolContext context) {
		String action = arguments.path("action").asText("").trim();
		ToolResult result = switch (action) {
			case "create" -> create(arguments);
			case "list" -> list();
			case "delete" -> delete(arguments);
			case "enable" -> setEnabled(arguments, true);
			case "disable" -> setEnabled(arguments, false);
			default -> throw new IllegalArgumentException("action 只能是 create、list、delete、enable 或 disable");
		};
		// Whoever manages alerts is where the triggered pushes should go.
		pushService.registerPushTarget(context.sessionId());
		return result;
	}

	private ToolResult create(JsonNode arguments) {
		String threshold = arguments.path("threshold").asText("").trim();
		AlertRule rule = alertService.createAlert(arguments.path("stockCode").asText(""),
				arguments.path("ruleType").asText(""), arguments.path("operator").asText(""),
				threshold, arguments.path("cooldownMinutes").asInt(60));
		return ToolResult.text("预警已创建，触发后会主动推送微信消息。\n" + describe(rule));
	}

	private ToolResult list() {
		List<AlertRule> rules = alertService.listAlerts();
		if (rules.isEmpty()) return ToolResult.noResult("当前没有任何股价预警。");
		StringBuilder text = new StringBuilder("当前预警：\n");
		for (AlertRule rule : rules) text.append("- ").append(describe(rule)).append('\n');
		return ToolResult.text(text.toString().trim());
	}

	private ToolResult delete(JsonNode arguments) {
		alertService.deleteAlert(requiredId(arguments));
		return ToolResult.text("预警已删除。");
	}

	private ToolResult setEnabled(JsonNode arguments, boolean enabled) {
		AlertRule rule = alertService.setEnabled(requiredId(arguments), enabled);
		return ToolResult.text((enabled ? "预警已启用。\n" : "预警已停用。\n") + describe(rule));
	}

	private static String requiredId(JsonNode arguments) {
		String id = arguments.path("id").asText("").trim();
		if (id.isBlank()) throw new IllegalArgumentException("请先用 list 查到预警 ID，再执行该操作");
		return id;
	}

	private static String describe(AlertRule rule) {
		return "ID=" + rule.id() + "，" + rule.stockCode() + " " + AlertPushService.ruleLabel(rule.ruleType())
				+ " " + AlertPushService.trimNumber(rule.threshold())
				+ (rule.enabled() ? "" : "（已停用）") + "，冷却 " + rule.cooldownMinutes() + " 分钟";
	}
}
