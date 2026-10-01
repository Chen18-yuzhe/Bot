package com.example.bot.ilink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class IlinkReplySplitTests {

	@Test
	void shouldSplitLongReplyAtParagraphBoundaryWithoutLosingText() {
		String answer = "第一段内容足够长，用于验证会优先在段落边界拆分。\n\n第二段内容也必须完整送达。";

		List<String> parts = IlinkChatRunner.splitReply(answer, 30);

		assertEquals(List.of("第一段内容足够长，用于验证会优先在段落边界拆分。", "第二段内容也必须完整送达。"), parts);
		assertTrue(parts.stream().allMatch(part -> part.length() <= 30));
	}

	@Test
	void shouldHardSplitAContinuousLongReplyWithoutTruncationMarker() {
		String answer = "abcdefghijklmnopqrstuvwxyz";

		List<String> parts = IlinkChatRunner.splitReply(answer, 10);

		assertEquals("abcdefghijklmnopqrstuvwxyz", String.join("", parts));
		assertTrue(parts.stream().allMatch(part -> part.length() <= 10));
	}

	@Test
	void shouldRenderCommonLatexAsPlainWechatText() {
		String answer = "BMI 指数：$70 \\div (1.82)^2 \\approx 21.1$；分数是 $\\frac{1}{2}$。";

		assertEquals("BMI 指数：70 ÷ (1.82)^2 ≈ 21.1；分数是 (1) ÷ (2)。",
				IlinkChatRunner.plainTextForWechat(answer));
	}
}
