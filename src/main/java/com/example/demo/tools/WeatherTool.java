package com.example.demo.tools;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

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
			.description("查询一个或批量查询最多5个中国城市的实时天气或未来三天天气预报；比较多个城市时使用cities一次获取完整数据。用户未提供城市时不要调用，应先询问城市。")
			.parameters(JsonParser.parseString("""
					{"type":"object","properties":{"city":{"type":"string","description":"查询单个城市时使用，例如北京"},"cities":{"type":"array","items":{"type":"string"},"minItems":2,"maxItems":5,"description":"比较或批量查询时使用，例如[北京,杭州]；不要与city同时传入"},"queryType":{"type":"string","enum":["current","forecast"],"description":"current为实时天气，forecast为未来三天预报"}},"required":["queryType"],"additionalProperties":false}
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
		String type = requiredText(arguments, "queryType");
		List<String> cities = cities(arguments);
		if (cities.size() == 1) return ToolResult.text(serialize(query(cities.get(0), type)));

		var response = objectMapper.createObjectNode();
		response.put("queryType", type);
		var results = response.putArray("results");
		int successCount = 0;
		for (String city : cities) {
			var item = results.addObject();
			item.put("requestedCity", city);
			try {
				item.put("success", true);
				item.set("data", objectMapper.valueToTree(query(city, type)));
				successCount++;
			} catch (Exception exception) {
				item.put("success", false);
				item.put("error", safeMessage(exception));
			}
		}
		response.put("success", successCount == cities.size());
		response.put("successCount", successCount);
		response.put("failureCount", cities.size() - successCount);
		return successCount == 0 ? ToolResult.noResult(response.toString()) : ToolResult.text(response.toString());
	}

	private Object query(String city, String type) {
		Object result = switch (type) {
			case "current" -> weatherService.getCurrentWeather(city);
			case "forecast" -> weatherService.getThreeDayForecast(city);
			default -> throw new IllegalArgumentException("queryType 只能是 current 或 forecast");
		};
		return result;
	}

	private String serialize(Object result) {
		try {
			return objectMapper.writeValueAsString(result);
		} catch (Exception exception) {
			throw new IllegalStateException("天气结果序列化失败", exception);
		}
	}

	private static List<String> cities(JsonNode arguments) {
		String city = arguments.path("city").asText("").trim();
		JsonNode cityList = arguments.path("cities");
		boolean hasCity = !city.isEmpty();
		boolean hasCities = cityList.isArray() && !cityList.isEmpty();
		if (hasCity && hasCities) throw new IllegalArgumentException("city 和 cities 不能同时提供");
		if (!hasCity && !hasCities) throw new IllegalArgumentException("缺少必填参数：city 或 cities");
		if (hasCity) return List.of(city);
		if (cityList.size() > 5) throw new IllegalArgumentException("一次最多查询 5 个城市");
		LinkedHashSet<String> unique = new LinkedHashSet<>();
		for (JsonNode value : cityList) {
			String name = value.asText("").trim();
			if (name.isEmpty()) throw new IllegalArgumentException("城市名称不能为空");
			unique.add(name);
		}
		if (unique.size() < 2) throw new IllegalArgumentException("批量天气查询至少需要 2 个不同城市");
		return new ArrayList<>(unique);
	}

	private static String safeMessage(Exception exception) {
		String message = exception.getMessage();
		return message == null || message.isBlank() ? "天气查询失败" : message;
	}

	private static String requiredText(JsonNode arguments, String name) {
		String value = arguments.path(name).asText("").trim();
		if (value.isEmpty()) throw new IllegalArgumentException("缺少必填参数：" + name);
		return value;
	}
}
