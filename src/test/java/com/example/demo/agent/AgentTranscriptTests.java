package com.example.demo.agent;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.alibaba.dashscope.common.MultiModalMessage;

class AgentTranscriptTests {

	@Test
	void shouldKeepEmptyContentForAssistantToolOnlyTurn() {
		var entry = AgentTranscript.Entry.assistant(null,
				List.of(new AgentTranscript.ToolCallRecord("call-1", "query_weather", "{\"city\":\"北京\"}")));

		List<Object> messages = AgentTranscript.toSdkMessages(List.of(entry));
		MultiModalMessage message = (MultiModalMessage) messages.getFirst();

		assertNotNull(message.getContent());
	}
}
