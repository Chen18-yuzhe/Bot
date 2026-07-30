package com.example.bot.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class WorldTimeToolTests {
	private final ObjectMapper objectMapper = new ObjectMapper();
	private final Clock clock = Clock.fixed(Instant.parse("2026-07-23T00:00:00Z"), ZoneOffset.UTC);
	private final WorldTimeTool tool = new WorldTimeTool(objectMapper, clock);

	@Test
	void shouldDefaultToBeijingTime() throws Exception {
		ToolResult result = tool.execute(objectMapper.readTree("{}"), new ToolContext("user", "现在几点"));
		var first = objectMapper.readTree(result.content()).get("results").get(0);

		assertEquals("北京", first.get("location").asText());
		assertEquals("Asia/Shanghai", first.get("timeZone").asText());
		assertEquals("08:00", first.get("time").asText());
		assertEquals("+08:00", first.get("utcOffset").asText());
	}

	@Test
	void shouldResolveCommonLocationAndDaylightSavingTime() throws Exception {
		ToolResult result = tool.execute(objectMapper.readTree("{\"location\":\"纽约\"}"),
				new ToolContext("user", "纽约现在几点"));
		var first = objectMapper.readTree(result.content()).get("results").get(0);

		assertEquals("America/New_York", first.get("timeZone").asText());
		assertEquals("-04:00", first.get("utcOffset").asText());
		assertEquals("20:00", first.get("time").asText());
	}

	@Test
	void shouldCompareMultipleRegionsAtSameInstant() throws Exception {
		ToolResult result = tool.execute(objectMapper.readTree("""
				{"locations":[{"location":"北京"},{"location":"伦敦"},{"location":"东京"}]}
				"""), new ToolContext("user", "比较时间"));
		var results = objectMapper.readTree(result.content()).get("results");

		assertEquals(3, results.size());
		assertEquals("08:00", results.get(0).get("time").asText());
		assertEquals("01:00", results.get(1).get("time").asText());
		assertEquals("09:00", results.get(2).get("time").asText());
	}

	@Test
	void shouldAcceptExplicitIanaZoneAndRejectUnknownLocation() throws Exception {
		ToolResult result = tool.execute(objectMapper.readTree(
				"{\"location\":\"檀香山\",\"timeZone\":\"Pacific/Honolulu\"}"),
				new ToolContext("user", "檀香山时间"));
		assertEquals("Pacific/Honolulu",
				objectMapper.readTree(result.content()).get("results").get(0).get("timeZone").asText());

		assertThrows(IllegalArgumentException.class, () -> tool.execute(
				objectMapper.readTree("{\"location\":\"未知地区\"}"), new ToolContext("user", "时间")));
	}
}
