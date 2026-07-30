package com.example.demo.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class TravelFliggySearchToolTests {
	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void preservesH5JumpUrlFromKeywordSearchInfoObject() throws Exception {
		TravelFliggySearchTool tool = new TravelFliggySearchTool(true, "flyai", "", 35, objectMapper);
		var response = objectMapper.readTree("""
				{"status":0,"data":{"itemList":[{"info":{
					"title":"灵隐飞来峰门票", "jumpUrl":"https://router.feizhu.com/product/verified",
					"price":"45"}}]}}
				""");

		var candidate = tool.summarize("keyword", response).path("candidates").path(0);

		assertEquals("灵隐飞来峰门票", candidate.path("name").asText());
		assertEquals("https://router.feizhu.com/product/verified", candidate.path("jumpUrl").asText());
		assertEquals("45", candidate.path("price").asText());
	}
}
