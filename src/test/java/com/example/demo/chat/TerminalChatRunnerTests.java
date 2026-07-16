package com.example.demo.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

class TerminalChatRunnerTests {

	private final TerminalChatRunner runner = new TerminalChatRunner(mock(AssistantService.class));

	@Test
	void shouldShowHelpCommand() {
		String result = runner.handleCommand("/help");

		assertTrue(result.contains("/version"));
		assertTrue(result.contains("/status"));
	}

	@Test
	void shouldShowVersionCommand() {
		assertEquals("当前版本：1.1", runner.handleCommand("/version"));
	}

	@Test
	void shouldNotInterceptNormalQuestion() {
		assertEquals(null, runner.handleCommand("北京今天天气怎么样？"));
	}
	@Test
	void shouldReportUnknownCommand() {
		assertTrue(runner.handleCommand("/unknown").contains("未知命令"));
	}
}
