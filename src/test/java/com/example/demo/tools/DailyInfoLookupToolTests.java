package com.example.demo.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import com.example.demo.search.WebSearchService;
import com.fasterxml.jackson.databind.ObjectMapper;

class DailyInfoLookupToolTests {
	private final ObjectMapper objectMapper = new ObjectMapper();
	private final WebSearchService webSearch = mock(WebSearchService.class);
	private final DailyInfoLookupTool tool = new DailyInfoLookupTool(webSearch, objectMapper,
			Clock.fixed(Instant.parse("2026-07-24T01:00:00Z"), ZoneId.of("Asia/Shanghai")));

	@Test
	void shouldBuildAirQualitySearchForCity() throws Exception {
		when(webSearch.search(contains("北京 实时空气质量"))).thenReturn("AQI 42\n来源：https://example.test");

		ToolResult result = tool.execute(objectMapper.readTree(
				"{\"category\":\"air_quality\",\"query\":\"北京\"}"), new ToolContext("user", "北京空气质量"));

		assertEquals("air_quality", objectMapper.readTree(result.content()).get("category").asText());
		verify(webSearch).search(contains("北京 实时空气质量"));
	}

	@Test
	void shouldBuildNutritionSearchWithRequestedAmount() throws Exception {
		when(webSearch.search(contains("鸡胸肉 营养成分表 每 200 克"))).thenReturn("营养数据");

		tool.execute(objectMapper.readTree(
				"{\"category\":\"nutrition\",\"query\":\"鸡胸肉\",\"amountGrams\":200}"), new ToolContext("user", "鸡胸肉营养"));

		verify(webSearch).search(contains("每 200 克"));
	}

	@Test
	void shouldRequireQueryForRecipeNutritionAndAirQuality() throws Exception {
		assertThrows(IllegalArgumentException.class, () -> tool.execute(
				objectMapper.readTree("{\"category\":\"recipe\"}"), new ToolContext("user", "菜谱")));
	}
}
