package com.example.demo.tools;

import org.springframework.stereotype.Component;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.example.demo.weather.WeatherService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonParser;

@Component
public class WeatherTool implements AssistantTool {
	private static final FunctionDefinition DEFINITION = FunctionDefinition.builder()
			.name("query_weather")
			.description("查询中国城市的实时天气或未来三天天气预报。用户未提供城市时不要调用，应先询问城市。")
			.parameters(JsonParser.parseString("""
					{"type":"object","properties":{"city":{"type":"string","description":"城市名称，例如北京、杭州"},"queryType":{"type":"string","enum":["current","forecast"],"description":"current为实时天气，forecast为未来三天预报"}},"required":["city","queryType"],"additionalProperties":false}
					""").getAsJsonObject())
			.build();

	private final WeatherService weatherService;
	private final ObjectMapper objectMapper;

	public WeatherTool(WeatherService weatherService, ObjectMapper objectMapper) {
		this.weatherService = weatherService;
		this.objectMapper = objectMapper;
	}

	@Override public FunctionDefinition definition() { return DEFINITION; }

	@Override
	public ToolResult execute(JsonNode arguments, ToolContext context) {
		String city = requiredText(arguments, "city");
		String type = requiredText(arguments, "queryType");
		Object result = switch (type) {
			case "current" -> weatherService.getCurrentWeather(city);
			case "forecast" -> weatherService.getThreeDayForecast(city);
			default -> throw new IllegalArgumentException("queryType 只能是 current 或 forecast");
		};
		try {
			return ToolResult.text(objectMapper.writeValueAsString(result));
		} catch (Exception exception) {
			throw new IllegalStateException("天气结果序列化失败", exception);
		}
	}

	private static String requiredText(JsonNode arguments, String name) {
		String value = arguments.path(name).asText("").trim();
		if (value.isEmpty()) throw new IllegalArgumentException("缺少必填参数：" + name);
		return value;
	}
}
