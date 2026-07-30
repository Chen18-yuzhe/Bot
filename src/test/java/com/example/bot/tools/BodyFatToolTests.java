package com.example.bot.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class BodyFatToolTests {
	private final ObjectMapper objectMapper = new ObjectMapper();
	private final BodyFatTool tool = new BodyFatTool(objectMapper);

	@Test
	void shouldEstimateMaleBodyFatWithNavyMethod() throws Exception {
		ToolResult result = tool.execute(objectMapper.readTree("""
				{"gender":"male","heightCm":175,"waistCm":82,"neckCm":38}
				"""), new ToolContext("user", "体脂率"));
		var json = objectMapper.readTree(result.content());

		assertEquals("US Navy circumference estimate", json.get("method").asText());
		assertEquals(true, json.get("bodyFatPercent").asDouble() > 8d);
		assertEquals(true, json.get("bodyFatPercent").asDouble() < 9d);
		assertEquals(true, json.get("disclaimer").asText().contains("估算"));
	}

	@Test
	void shouldRequireHipMeasurementForFemaleEstimate() throws Exception {
		assertThrows(IllegalArgumentException.class, () -> tool.execute(objectMapper.readTree(
				"{\"gender\":\"female\",\"heightCm\":165,\"waistCm\":70,\"neckCm\":32}"),
				new ToolContext("user", "体脂率")));
	}
}
