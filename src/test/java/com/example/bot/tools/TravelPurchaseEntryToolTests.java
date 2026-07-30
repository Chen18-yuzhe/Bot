package com.example.bot.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.example.bot.agent.AgentToolException;
import com.fasterxml.jackson.databind.ObjectMapper;

class TravelPurchaseEntryToolTests {
	private final ObjectMapper objectMapper = new ObjectMapper();
	private final TravelPurchaseEntryTool tool = new TravelPurchaseEntryTool(objectMapper);

	@Test
	void createsAnOfficialMiniProgramGuideWithoutInventingALink() throws Exception {
		String arguments = """
				{
				  "entryType":"official_miniprogram_guide",
				  "provider":"西湖旅游服务","attractionName":"飞来峰景区",
				  "visitDate":"2026-08-10","productName":"飞来峰景区成人票",
				  "ticketType":"成人票","quantity":2,
				  "miniProgramName":"西湖旅游服务","menuPath":["门票预订","飞来峰景区"],
				  "avoid":["含演出套票","含餐饮套票"],"selectionReason":"仅游览飞来峰",
				  "sourceCheckedAt":"2026-07-29T10:00:00+08:00"
				}
				""";

		assertThrows(AgentToolException.class,
				() -> tool.execute(objectMapper.readTree(arguments), new ToolContext("wx-user", "给我购票入口")));
	}

	@Test
	void rejectsAnInventedLinkOnAMiniProgramGuide() throws Exception {
		String arguments = """
				{"entryType":"official_miniprogram_guide","provider":"官方","attractionName":"景区",
				"visitDate":"2026-08-10","productName":"成人票","ticketType":"成人票","quantity":1,
				"entryUrl":"https://wxaurl.cn/not-verified","miniProgramName":"官方小程序","menuPath":["购票"],
				"selectionReason":"匹配","sourceCheckedAt":"2026-07-29T10:00:00+08:00"}
				""";

		assertThrows(AgentToolException.class,
				() -> tool.execute(objectMapper.readTree(arguments), new ToolContext("wx-user", "购票")));
	}
}
