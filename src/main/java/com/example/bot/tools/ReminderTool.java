package com.example.bot.tools;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;

import org.springframework.stereotype.Component;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.example.bot.reminder.ReminderService;
import com.example.bot.reminder.ReminderService.Recurrence;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.gson.JsonParser;

@Component
public class ReminderTool implements AssistantTool {
	private static final FunctionDefinition DEFINITION = FunctionDefinition.builder()
			.name("manage_reminder")
			.description("创建、查看或取消当前用户的提醒和待办。创建时把自然语言时间转换成带时区的 ISO-8601 时间。")
			.parameters(JsonParser.parseString("""
					{"type":"object","properties":{"action":{"type":"string","enum":["create","list","cancel"]},"content":{"type":"string","description":"create 时的提醒内容"},"triggerAt":{"type":"string","description":"create 时的 ISO-8601 时间，例如 2026-07-23T15:00:00+08:00"},"recurrence":{"type":"string","enum":["none","daily","weekly"],"description":"重复规则，默认 none"},"id":{"type":"string","description":"cancel 时的提醒 ID"}},"required":["action"],"additionalProperties":false}
					""").getAsJsonObject()).build();
	private final ReminderService service;

	public ReminderTool(ReminderService service) { this.service = service; }
	@Override public FunctionDefinition definition() { return DEFINITION; }

	@Override
	public ToolResult execute(JsonNode arguments, ToolContext context) {
		String action = arguments.path("action").asText("").trim();
		return switch (action) {
			case "create" -> create(arguments, context);
			case "list" -> list(context);
			case "cancel" -> cancel(arguments, context);
			default -> throw new IllegalArgumentException("action 只能是 create、list 或 cancel");
		};
	}

	private ToolResult create(JsonNode arguments, ToolContext context) {
		String content = required(arguments, "content");
		ZonedDateTime triggerAt = parseTime(required(arguments, "triggerAt"));
		Recurrence recurrence;
		try { recurrence = Recurrence.valueOf(arguments.path("recurrence").asText("none").toUpperCase()); }
		catch (IllegalArgumentException error) { throw new IllegalArgumentException("recurrence 只能是 none、daily 或 weekly"); }
		var reminder = service.create(context.sessionId(), content, triggerAt, recurrence);
		return ToolResult.text("提醒已创建。ID=" + reminder.id() + "，时间=" + triggerAt + "，重复=" + recurrence.name().toLowerCase());
	}

	private ToolResult list(ToolContext context) {
		var reminders = service.list(context.sessionId());
		if (reminders.isEmpty()) return ToolResult.noResult("当前没有待执行提醒。");
		StringBuilder text = new StringBuilder("待执行提醒：\n");
		for (var item : reminders) {
			text.append("- ID=").append(item.id()).append("，时间=")
					.append(java.time.Instant.ofEpochMilli(item.triggerAtMs()).atZone(ZoneId.of("Asia/Shanghai")))
					.append("，内容=").append(item.content()).append("，重复=")
					.append(item.recurrence().name().toLowerCase()).append('\n');
		}
		return ToolResult.text(text.toString().trim());
	}

	private ToolResult cancel(JsonNode arguments, ToolContext context) {
		String id = required(arguments, "id");
		return service.cancel(context.sessionId(), id) ? ToolResult.text("提醒已取消。")
				: ToolResult.noResult("没有找到该提醒，或它不属于当前用户。");
	}

	private static ZonedDateTime parseTime(String value) {
		try { return ZonedDateTime.parse(value); }
		catch (DateTimeParseException ignored) { }
		try { return OffsetDateTime.parse(value).toZonedDateTime(); }
		catch (DateTimeParseException ignored) { }
		try { return LocalDateTime.parse(value).atZone(ZoneId.of("Asia/Shanghai")); }
		catch (DateTimeParseException error) { throw new IllegalArgumentException("triggerAt 必须是 ISO-8601 时间"); }
	}

	private static String required(JsonNode arguments, String name) {
		String value = arguments.path(name).asText("").trim();
		if (value.isBlank()) throw new IllegalArgumentException("缺少必填参数：" + name);
		return value;
	}
}
