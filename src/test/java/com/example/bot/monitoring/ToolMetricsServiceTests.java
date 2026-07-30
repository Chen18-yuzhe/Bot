package com.example.bot.monitoring;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class ToolMetricsServiceTests {
	@Test
	void shouldSerializeSystemSnapshotWithPlainProjectObjectMapper() throws Exception {
		ToolMetricsService metrics = new ToolMetricsService();

		String json = new ObjectMapper().writeValueAsString(metrics.systemSnapshot());

		assertTrue(json.contains("\"timestamp\":\""));
		assertTrue(json.contains("\"uptimeMs\":"));
	}
}
