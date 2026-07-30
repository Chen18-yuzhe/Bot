package com.example.bot.ilink;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class QrCodeSvgServiceTests {

	private final QrCodeSvgService service = new QrCodeSvgService();

	@Test
	void shouldRenderQrCodeAsSelfContainedSvg() {
		String svg = service.render("https://liteapp.weixin.qq.com/q/example");

		assertTrue(svg.startsWith("<svg"));
		assertTrue(svg.contains("viewBox=\"0 0 360 360\""));
		assertTrue(svg.contains("<path d=\""));
		assertTrue(svg.endsWith("</svg>\n"));
	}

	@Test
	void shouldRejectBlankContent() {
		assertThrows(IllegalArgumentException.class, () -> service.render(" "));
	}
}
