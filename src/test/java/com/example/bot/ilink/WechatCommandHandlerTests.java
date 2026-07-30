package com.example.bot.ilink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WechatCommandHandlerTests {

	private final WechatCommandHandler handler = new WechatCommandHandler(
			"qwen-test", "configured-key", "");

	@Test
	void shouldHandleWechatCommands() {
		assertTrue(handler.handle("/help").contains("/status"));
		assertEquals("当前版本：1.1", handler.handle("/version"));
		assertTrue(handler.handle("/status").contains("千问 API Key：已配置"));
		assertTrue(handler.handle("/status").contains("和风天气 API Key：未配置"));
		assertNull(handler.handle("普通问题"));
	}

	@Test
	void shouldAddWelcomeOnlyForFirstContactInCurrentProcess() {
		String firstReply = handler.addWelcomeForFirstContact("user-1", "回答");
		String secondReply = handler.addWelcomeForFirstContact("user-1", "第二次回答");

		assertTrue(firstReply.contains("你好，我是微信 AI 助手"));
		assertTrue(firstReply.endsWith("回答"));
		assertEquals("第二次回答", secondReply);
	}

	@Test
	void shouldNotRepeatWelcomeWhenHelpIsFirstMessage() {
		String help = handler.handle("/help");

		assertEquals(help, handler.addWelcomeForFirstContact("user-2", help));
	}
}
