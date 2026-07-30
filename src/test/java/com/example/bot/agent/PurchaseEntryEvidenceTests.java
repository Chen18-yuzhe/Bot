package com.example.bot.agent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class PurchaseEntryEvidenceTests {
	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void rejectsInventedMiniProgramGuideAndAcceptsSourcedThirdPartyEntry() throws Exception {
		AgentTask task = new AgentTask();
		task.getTranscript().add(AgentTranscript.Entry.tool("call-1", "travel_fliggy_search",
				"{\"name\":\"灵隐寺\",\"ticketType\":\"成人票\",\"jumpUrl\":\"https://example.test/product/1\"}"));

		assertThrows(AgentToolException.class, () -> AgentToolExecutor.validatePurchaseEntryEvidence(
				objectMapper.readTree("""
					{"entryType":"official_miniprogram_guide","miniProgramName":"西湖旅游",
					 "menuPath":["门票预订"],"productName":"灵隐寺","ticketType":"成人票"}
					"""), task));
		assertDoesNotThrow(() -> AgentToolExecutor.validatePurchaseEntryEvidence(
				objectMapper.readTree("""
					{"entryType":"third_party_product_page","entryUrl":"https://example.test/product/1",
					 "productName":"灵隐寺","ticketType":"成人票"}
					"""), task));
	}
}
