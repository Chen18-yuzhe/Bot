package com.example.demo.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.example.demo.weather.WeatherResponse;
import com.example.demo.weather.WeatherService;
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
}
