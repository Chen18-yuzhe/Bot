package com.example.bot.chat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.example.bot.tools.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;

class ToolChainGuardTests {
	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void shouldBlockEquivalentCallAfterItAlreadySucceeded() throws Exception {
		ToolChainGuard guard = new ToolChainGuard();
		var first = guard.beforeCall("query_weather",
				objectMapper.readTree("{\"city\":\" 北京 \",\"queryType\":\"CURRENT\"}"));
		guard.success("query_weather", first.fingerprint(), ToolResult.text("晴，30℃"));

		var duplicate = guard.beforeCall("query_weather",
				objectMapper.readTree("{\"queryType\":\"current\",\"city\":\"北京\"}"));

		assertFalse(duplicate.allowed());
		assertTrue(guard.halted());
	}

	@Test
	void shouldStopAfterSecondUnproductiveAttemptWithChangedArguments() throws Exception {
		ToolChainGuard guard = new ToolChainGuard();
		var first = guard.beforeCall("search_web", objectMapper.readTree("{\"query\":\"问题 A\"}"));
		guard.success("search_web", first.fingerprint(), ToolResult.noResult("没有结果"));
		assertFalse(guard.halted());

		var second = guard.beforeCall("search_web", objectMapper.readTree("{\"query\":\"问题 A 最新\"}"));
		guard.success("search_web", second.fingerprint(), ToolResult.noResult("仍然没有结果"));

		assertTrue(guard.halted());
	}

	@Test
	void shouldStopAfterSecondFailureEvenWhenParametersChange() throws Exception {
		ToolChainGuard guard = new ToolChainGuard();
		guard.beforeCall("query_weather", objectMapper.readTree("{\"city\":\"杭州市\"}"));
		guard.failure("query_weather");
		guard.beforeCall("query_weather", objectMapper.readTree("{\"city\":\"杭州\"}"));
		guard.failure("query_weather");

		assertTrue(guard.halted());
	}

	@Test
	void shouldAllowUsefulCallsWithDifferentArguments() throws Exception {
		ToolChainGuard guard = new ToolChainGuard();
		var beijing = guard.beforeCall("query_weather", objectMapper.readTree("{\"city\":\"北京\"}"));
		guard.success("query_weather", beijing.fingerprint(), ToolResult.text("北京天气"));
		var hangzhou = guard.beforeCall("query_weather", objectMapper.readTree("{\"city\":\"杭州\"}"));
		guard.success("query_weather", hangzhou.fingerprint(), ToolResult.text("杭州天气"));

		assertTrue(hangzhou.allowed());
		assertFalse(guard.halted());
	}
}
