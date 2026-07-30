package com.example.bot.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SensitiveDataConverterTests {

	@Test
	void shouldMaskApiKeysTokensAndPhones() {
		String masked = SensitiveDataConverter.mask(
				"调用失败 api-key=sk-abc123DEF456 Authorization: Bearer eyJhbGciOi.payload 用户 13812345678");
		assertFalse(masked.contains("sk-abc123DEF456"));
		assertFalse(masked.contains("eyJhbGciOi.payload"));
		assertFalse(masked.contains("13812345678"));
		assertTrue(masked.contains("sk-****"));
		assertTrue(masked.contains("Bearer ****"));
		assertTrue(masked.contains("138******78"));
	}

	@Test
	void shouldLeaveOrdinaryMessagesUntouched() {
		String message = "提醒投递成功：id=3919fa44，用户=default::user-1，耗时=120毫秒";
		assertEquals(message, SensitiveDataConverter.mask(message));
	}

	@Test
	void shouldMaskBilibiliCookies() {
		String masked = SensitiveDataConverter.mask(
				"BILI_COOKIE=SESSDATA=secret-session; bili_jct=csrf-secret; DedeUserID=123456; sid=abc12345");
		assertFalse(masked.contains("secret-session"));
		assertFalse(masked.contains("csrf-secret"));
		assertFalse(masked.contains("123456"));
		assertFalse(masked.contains("abc12345"));
		assertTrue(masked.contains("BILI_COOKIE=****"));
	}
}
