package com.example.bot.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class ScientificCalculatorToolTests {
	private final ObjectMapper objectMapper = new ObjectMapper();
	private final ScientificCalculatorTool tool = new ScientificCalculatorTool(objectMapper);

	@Test
	void shouldCalculateScientificExpressionInDegreesByDefault() throws Exception {
		ToolResult result = tool.execute(objectMapper.readTree("{\"expression\":\"sqrt(81)+sin(30)+2^3\"}"),
				new ToolContext("user", "计算"));
		var json = objectMapper.readTree(result.content());

		assertEquals("17.5", json.get("formatted").asText());
	}

	@Test
	void shouldSupportRadianAndFunctionsWithTwoArguments() throws Exception {
		ToolResult result = tool.execute(objectMapper.readTree(
				"{\"expression\":\"max(2,3)+sin(pi/2)\",\"angleUnit\":\"radian\"}"), new ToolContext("user", "计算"));

		assertEquals("4", objectMapper.readTree(result.content()).get("formatted").asText());
	}

	@Test
	void shouldRejectInvalidExpression() throws Exception {
		assertThrows(IllegalArgumentException.class, () -> tool.execute(
				objectMapper.readTree("{\"expression\":\"1/0\"}"), new ToolContext("user", "计算")));
	}
}
