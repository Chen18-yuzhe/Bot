package com.example.demo.weather;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;

import com.example.demo.ApplicationException;
import tools.jackson.databind.ObjectMapper;

class WeatherServiceTests {

	@Test
	void shouldGiveClearMessageWhenApiHostIsMissing() {
		WeatherService service = new WeatherService("", "test-key", new ObjectMapper());

		ApplicationException exception = assertThrows(ApplicationException.class,
				() -> service.getCurrentWeather("北京"));

		assertEquals("请先配置环境变量 QWEATHER_API_HOST", exception.getMessage());
	}

	@Test
	void shouldGiveClearMessageWhenApiKeyIsMissing() {
		WeatherService service = new WeatherService("weather.example.com", "", new ObjectMapper());

		ApplicationException exception = assertThrows(ApplicationException.class,
				() -> service.getCurrentWeather("北京"));

		assertEquals("请先配置环境变量 QWEATHER_API_KEY", exception.getMessage());
	}

	@Test
	void shouldDecodeGzipResponse() throws Exception {
		String json = "{\"code\":\"200\"}";
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
			gzip.write(json.getBytes(StandardCharsets.UTF_8));
		}

		String decoded = WeatherService.decodeResponseBody(output.toByteArray(), "gzip");

		assertEquals(json, decoded);
	}

	@Test
	void shouldKeepPlainJsonResponse() {
		String json = "{\"code\":\"200\"}";

		String decoded = WeatherService.decodeResponseBody(
				json.getBytes(StandardCharsets.UTF_8), "");

		assertEquals(json, decoded);
	}
}
