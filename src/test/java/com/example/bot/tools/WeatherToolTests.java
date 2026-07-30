package com.example.bot.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.example.bot.weather.WeatherResponse;
import com.example.bot.weather.WeatherService;
import com.fasterxml.jackson.databind.ObjectMapper;

class WeatherToolTests {
	private final WeatherService weatherService = mock(WeatherService.class);
	private final ObjectMapper objectMapper = new ObjectMapper();
	private final WeatherTool tool = new WeatherTool(weatherService, objectMapper);

	@Test
	void shouldExposeStrictDashScopeFunctionDefinition() {
		assertEquals("query_weather", tool.definition().getName());
		assertEquals(false, tool.definition().getParameters().get("additionalProperties").getAsBoolean());
	}

	@Test
	void shouldExecuteCurrentWeatherWithStructuredArguments() throws Exception {
		WeatherResponse weather = new WeatherResponse("北京", "北京市", "晴", "30", "31", "45", "东南风", "2",
				"2026-07-16T10:00+08:00", "2026-07-16T10:05+08:00");
		when(weatherService.getCurrentWeather("北京")).thenReturn(weather);

		ToolResult result = tool.execute(objectMapper.readTree("{\"city\":\"北京\",\"queryType\":\"current\"}"),
				new ToolContext("user", "北京天气"));

		assertEquals("北京", objectMapper.readTree(result.content()).get("city").asText());
	}

	@Test
	void shouldRejectMissingRequiredArguments() throws Exception {
		assertThrows(IllegalArgumentException.class,
				() -> tool.execute(objectMapper.readTree("{}"), new ToolContext("user", "天气")));
	}

	@Test
	void shouldQueryMultipleCitiesForComparison() throws Exception {
		when(weatherService.getCurrentWeather("北京")).thenReturn(weather("北京", "晴", "30"));
		when(weatherService.getCurrentWeather("杭州")).thenReturn(weather("杭州", "多云", "35"));

		ToolResult result = tool.execute(objectMapper.readTree(
				"{\"cities\":[\"北京\",\"杭州\"],\"queryType\":\"current\"}"),
				new ToolContext("user", "比较北京和杭州天气"));
		var json = objectMapper.readTree(result.content());

		assertEquals(2, json.get("results").size());
		assertEquals("北京", json.get("results").get(0).get("data").get("city").asText());
		assertEquals("杭州", json.get("results").get(1).get("data").get("city").asText());
	}

	@Test
	void shouldKeepSuccessfulCitiesWhenOneCityFails() throws Exception {
		when(weatherService.getCurrentWeather("北京")).thenReturn(weather("北京", "晴", "30"));
		when(weatherService.getCurrentWeather("不存在")).thenThrow(new IllegalArgumentException("没有找到城市"));

		ToolResult result = tool.execute(objectMapper.readTree(
				"{\"cities\":[\"北京\",\"不存在\"],\"queryType\":\"current\"}"),
				new ToolContext("user", "比较天气"));
		var json = objectMapper.readTree(result.content());

		assertEquals(true, json.get("results").get(0).get("success").asBoolean());
		assertEquals(false, json.get("results").get(1).get("success").asBoolean());
		assertEquals(false, json.get("success").asBoolean());
		assertEquals(1, json.get("successCount").asInt());
	}

	@Test
	void shouldRejectAmbiguousOrOversizedCityArguments() throws Exception {
		assertThrows(IllegalArgumentException.class, () -> tool.execute(objectMapper.readTree(
				"{\"city\":\"北京\",\"cities\":[\"北京\",\"杭州\"],\"queryType\":\"current\"}"),
				new ToolContext("user", "天气")));
		assertThrows(IllegalArgumentException.class, () -> tool.execute(objectMapper.readTree(
				"{\"cities\":[\"北京\",\"上海\",\"广州\",\"深圳\",\"杭州\",\"南京\"],\"queryType\":\"current\"}"),
				new ToolContext("user", "天气")));
	}

	private static WeatherResponse weather(String city, String text, String temperature) {
		return new WeatherResponse(city, city + "市", text, temperature, temperature, "45", "东南风", "2",
				"2026-07-22T10:00+08:00", "2026-07-22T10:05+08:00");
	}
}
