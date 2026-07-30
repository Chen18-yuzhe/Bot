package com.example.demo.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class TravelPurchaseLinksToolTests {
	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void keepsOnlyAReachablePlatformPageThatMentionsTheAttraction() throws Exception {
		TravelPurchaseLinksTool tool = new TravelPurchaseLinksTool(objectMapper,
				uri -> new TravelPurchaseLinksTool.ProbeResult(uri, 200,
						"<title>Hangzhou Lingyin Feilai Peak ticket</title>"));
		ToolResult result = tool.execute(objectMapper.readTree("""
				{"targetType":"ATTRACTION","attractionName":"Lingyin Feilai Peak","sourceCheckedAt":"2026-07-29T10:00:00Z",
				 "entries":[{"platform":"MEITUAN","entryUrl":"https://i.meituan.com/ticket/31307",
				 "productName":"Lingyin Feilai Peak ticket"}]}
				"""), new ToolContext("wx-user", "ticket link"));

		assertTrue(result.useful());
		var json = objectMapper.readTree(result.content());
		assertEquals(1, json.path("verifiedCount").asInt());
		assertEquals("https://i.meituan.com/ticket/31307",
				json.path("links").path(0).path("entryUrl").asText());
	}

	@Test
	void rejectsWrongPlatformOrUnmatchedPageWithoutReturningTheUrl() throws Exception {
		TravelPurchaseLinksTool tool = new TravelPurchaseLinksTool(objectMapper,
				uri -> new TravelPurchaseLinksTool.ProbeResult(URI.create("https://m.meituan.com/ticket"), 200,
						"another attraction"));
		ToolResult result = tool.execute(objectMapper.readTree("""
				{"targetType":"ATTRACTION","attractionName":"Lingyin","sourceCheckedAt":"2026-07-29T10:00:00Z",
				 "entries":[{"platform":"MEITUAN","entryUrl":"https://m.meituan.com/ticket",
				 "productName":"Lingyin ticket"}]}
				"""), new ToolContext("wx-user", "ticket link"));

		assertFalse(result.useful());
		var json = objectMapper.readTree(result.content());
		assertEquals(0, json.path("verifiedCount").asInt());
		assertFalse(result.content().contains("https://m.meituan.com/ticket"));
	}

	@Test
	void attractionMatchingRequiresMoreThanOneShortFragment() {
		assertTrue(TravelPurchaseLinksTool.mentionsAttraction("灵隐飞来峰景区门票", "灵隐飞来峰景区"));
		assertFalse(TravelPurchaseLinksTool.mentionsAttraction("灵隐寺观光", "灵隐飞来峰景区"));
	}

	@Test
	void rejectsSearchAndHomePagesEvenWhenTheyMentionTheAttraction() throws Exception {
		TravelPurchaseLinksTool tool = new TravelPurchaseLinksTool(objectMapper,
				uri -> new TravelPurchaseLinksTool.ProbeResult(uri, 200, "故宫博物院门票"));
		ToolResult result = tool.execute(objectMapper.readTree("""
				{"targetType":"ATTRACTION","attractionName":"故宫博物院","sourceCheckedAt":"2026-07-29T10:00:00Z",
				 "entries":[{"platform":"CTRIP",
				 "entryUrl":"https://m.ctrip.com/webapp/you/search?keyword=%E6%95%85%E5%AE%AB",
				 "productName":"故宫博物院"}]}
				"""), new ToolContext("wx-user", "给我故宫直达页"));

		assertFalse(result.useful());
		assertEquals(0, objectMapper.readTree(result.content()).path("verifiedCount").asInt());
		assertFalse(result.content().contains("https://"));
		assertTrue(TravelPurchaseLinksTool.isSearchHomeOrChannelPage(URI.create("https://i.meituan.com/")));
		assertFalse(TravelPurchaseLinksTool.isSearchHomeOrChannelPage(
				URI.create("https://you.ctrip.com/sight/beijing1/230.html")));
	}

	@Test
	void trainLinksRequireCtripAndEveryTrainDetail() throws Exception {
		TravelPurchaseLinksTool tool = new TravelPurchaseLinksTool(objectMapper,
				uri -> new TravelPurchaseLinksTool.ProbeResult(uri, 200,
						"Shanghai Hongqiao to Hangzhou East G1234 2026-08-10"));
		ToolResult result = tool.execute(objectMapper.readTree("""
				{"targetType":"TRAIN","origin":"Shanghai Hongqiao","destination":"Hangzhou East",
				 "departureDate":"2026-08-10","trainNumber":"G1234","sourceCheckedAt":"2026-07-29T10:00:00Z",
				 "entries":[{"platform":"CTRIP","entryUrl":"https://trains.ctrip.com/train/G1234",
				 "productName":"G1234 Shanghai Hongqiao to Hangzhou East"}]}
				"""), new ToolContext("wx-user", "train link"));

		assertTrue(result.useful());
		assertEquals("CTRIP", objectMapper.readTree(result.content()).path("links").path(0).path("platform").asText());
	}
}
