package com.example.demo.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.example.demo.stock.StockTraceService;
import com.fasterxml.jackson.databind.ObjectMapper;

class StockDataToolTests {
	private final StockTraceService stockTraceService = mock(StockTraceService.class);
	private final ObjectMapper objectMapper = new ObjectMapper();
	private final StockDataTool tool = new StockDataTool(stockTraceService, objectMapper);

	@Test
	void shouldReturnStockTraceSummaryAsStructuredToolResult() throws Exception {
		when(stockTraceService.summary("600519")).thenReturn(new StockTraceService.StockSummary("600519",
				"贵州茅台数据摘要：趋势中性，完整度较高。", objectMapper.readTree("{\"dataAsOf\":\"2026-07-25\"}")));

		ToolResult result = tool.execute(objectMapper.readTree("{\"symbol\":\"600519\"}"),
				new ToolContext("user", "分析 600519"));

		assertEquals("600519", objectMapper.readTree(result.content()).path("symbol").asText());
		assertEquals("贵州茅台数据摘要：趋势中性，完整度较高。", objectMapper.readTree(result.content()).path("summary").asText());
		verify(stockTraceService).summary("600519");
	}

	@Test
	void shouldRejectMissingSymbol() throws Exception {
		assertThrows(IllegalArgumentException.class,
				() -> tool.execute(objectMapper.readTree("{}"), new ToolContext("user", "分析股票")));
	}
}
