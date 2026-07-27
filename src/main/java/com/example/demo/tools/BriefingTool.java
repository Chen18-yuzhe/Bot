package com.example.demo.tools;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.springframework.stereotype.Component;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.example.demo.reminder.ReminderService;
import com.example.demo.reminder.ReminderService.Recurrence;
import com.example.demo.reminder.ReminderService.Reminder;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.gson.JsonParser;

/**
 * Subscription briefings are daily reminders of type "briefing": the reminder
 * stores only the delivery time and the weather city, and the content is
 * composed fresh (weather + watchlist quotes + trending) at delivery time.
 */
@Component
public class BriefingTool implements AssistantTool {
	private static final ZoneId BEIJING_ZONE = ZoneId.of("Asia/Shanghai");
	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("H:mm");

	private static final FunctionDefinition DEFINITION = FunctionDefinition.builder()
			.name("manage_briefing")
			.description("订阅、退订或查看每日简报（早报/收盘报）。简报在每天固定时间自动推送，内容为投递时"
					+ "现场生成的天气、自选股行情和热搜。用户说“每天8点给我发早报”用 subscribe 并把时间转成"
					+ "HH:mm；“取消早报/退订简报”用 unsubscribe；“我的简报/订阅了什么”用 list。"
					+ "时间未说明时早报默认 08:00，收盘报默认 15:10。")
			.parameters(JsonParser.parseString("""
					{"type":"object","properties":{"action":{"type":"string","enum":["subscribe","unsubscribe","list"]},"time":{"type":"string","pattern":"^\\\\d{1,2}:\\\\d{2}$","description":"subscribe 时的每日推送时间，24 小时制 HH:mm"},"city":{"type":"string","description":"天气城市，默认北京"}},"required":["action"],"additionalProperties":false}
					""").getAsJsonObject()).build();

	private final ReminderService reminderService;

	public BriefingTool(ReminderService reminderService) {
		this.reminderService = reminderService;
	}

	@Override public FunctionDefinition definition() { return DEFINITION; }

	@Override
	public ToolResult execute(JsonNode arguments, ToolContext context) {
		String action = arguments.path("action").asText("").trim();
		return switch (action) {
			case "subscribe" -> subscribe(arguments, context);
			case "unsubscribe" -> unsubscribe(context);
			case "list" -> list(context);
			default -> throw new IllegalArgumentException("action 只能是 subscribe、unsubscribe 或 list");
		};
	}

	private ToolResult subscribe(JsonNode arguments, ToolContext context) {
		LocalTime time = parseTime(arguments.path("time").asText("08:00"));
		String city = arguments.path("city").asText("").trim();
		if (city.isBlank()) city = "北京";
		if (city.length() > 20) throw new IllegalArgumentException("城市名过长");
		// 同一会话重复订阅时先退掉旧的，保持一个用户一份简报。
		cancelBriefings(context.sessionId());
		ZonedDateTime next = ZonedDateTime.now(BEIJING_ZONE).with(time).withSecond(0).withNano(0);
		if (!next.isAfter(ZonedDateTime.now(BEIJING_ZONE))) next = next.plusDays(1);
		Reminder reminder = reminderService.create(context.sessionId(), city, next, Recurrence.DAILY, "briefing");
		return ToolResult.text("每日简报已订阅：每天 " + time.format(DateTimeFormatter.ofPattern("HH:mm"))
				+ " 推送，天气城市=" + city + "，内容为投递时实时生成的天气、自选股行情和热搜。ID=" + reminder.id());
	}

	private ToolResult unsubscribe(ToolContext context) {
		int cancelled = cancelBriefings(context.sessionId());
		return cancelled > 0 ? ToolResult.text("每日简报已退订。")
				: ToolResult.noResult("当前没有订阅每日简报。");
	}

	private ToolResult list(ToolContext context) {
		List<Reminder> briefings = briefings(context.sessionId());
		if (briefings.isEmpty()) return ToolResult.noResult("当前没有订阅每日简报。可以说“每天8点给我发早报”。");
		StringBuilder text = new StringBuilder("已订阅的简报：\n");
		for (Reminder item : briefings) {
			text.append("- 每天 ").append(java.time.Instant.ofEpochMilli(item.triggerAtMs())
							.atZone(BEIJING_ZONE).format(DateTimeFormatter.ofPattern("HH:mm")))
					.append("，天气城市=").append(item.content())
					.append("，ID=").append(item.id()).append('\n');
		}
		return ToolResult.text(text.toString().trim());
	}

	private List<Reminder> briefings(String sessionId) {
		return reminderService.list(sessionId).stream().filter(Reminder::isBriefing).toList();
	}

	private int cancelBriefings(String sessionId) {
		List<Reminder> briefings = briefings(sessionId);
		briefings.forEach(item -> reminderService.cancel(sessionId, item.id()));
		return briefings.size();
	}

	private static LocalTime parseTime(String value) {
		try {
			return LocalTime.parse(value == null || value.isBlank() ? "08:00" : value.trim(), TIME_FORMAT);
		} catch (DateTimeParseException error) {
			throw new IllegalArgumentException("time 必须是 24 小时制 HH:mm，例如 08:00 或 15:10");
		}
	}
}
