package com.example.demo.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WeatherIntentParserTests {

	private final WeatherIntentParser parser = new WeatherIntentParser();

	@Test
	void shouldParseCurrentWeatherToolMarker() {
		WeatherIntent intent = parser.parse("[WEATHER:CURRENT:北京]");

		assertTrue(intent.weatherQuestion());
		assertEquals("北京", intent.city());
		assertEquals(WeatherIntent.WeatherQueryType.CURRENT, intent.queryType());
	}

	@Test
	void shouldRemainCompatibleWithDoubleBracketMarker() {
		WeatherIntent intent = parser.parse("[[WEATHER:CURRENT:北京]]");

		assertTrue(intent.weatherQuestion());
		assertEquals("北京", intent.city());
	}

	@Test
	void shouldParseMarkerSurroundedByMarkdownAndWhitespace() {
		WeatherIntent intent = parser.parse("`[ WEATHER : forecast : 杭州市 ]`");

		assertTrue(intent.weatherQuestion());
		assertEquals("杭州市", intent.city());
		assertEquals(WeatherIntent.WeatherQueryType.FORECAST, intent.queryType());
	}

	@Test
	void shouldParseForecastWeatherToolMarker() {
		WeatherIntent intent = parser.parse("[[WEATHER:FORECAST:杭州市]]");

		assertTrue(intent.weatherQuestion());
		assertEquals("杭州市", intent.city());
		assertEquals(WeatherIntent.WeatherQueryType.FORECAST, intent.queryType());
	}

	@Test
	void shouldIgnoreNaturalLanguageWithoutToolMarker() {
		WeatherIntent intent = parser.parse("我暂时无法直接查询实时天气，请告诉我城市。");

		assertFalse(intent.weatherQuestion());
	}

	@Test
	void shouldIgnoreInvalidToolMarker() {
		WeatherIntent intent = parser.parse("[WEATHER:CURRENT:你为啥不能查]");

		assertFalse(intent.weatherQuestion());
	}
}
