package com.example.demo.ticket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

import com.example.demo.tools.ToolContext;
import com.fasterxml.jackson.databind.ObjectMapper;

class TicketPlatformEntryToolTests {
	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void returnsExactlyThreeFixedEntriesWithoutSearchParameters() throws Exception {
		TicketPlatformEntryTool tool = new TicketPlatformEntryTool(objectMapper);
		var result = objectMapper.readTree(tool.execute(objectMapper.createObjectNode(),
				new ToolContext("wx-user", "给我购票链接")).content());

		assertEquals(3, result.path("entries").size());
		assertEquals(TicketPlatformEntryTool.MEITUAN_URL,
				result.path("entries").path(0).path("url").asText());
		assertEquals("暂时不能给到您具体的购票页面，麻烦您进入平台自主购票。",
				result.path("notice").asText());
		for (var entry : result.path("entries")) {
			String url = entry.path("url").asText();
			assertFalse(url.contains("?"));
			assertFalse(url.contains("search"));
			assertFalse(url.contains("keyword"));
		}
	}
}
