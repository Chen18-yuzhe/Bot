package com.example.demo.weather;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;

import com.example.demo.ApplicationException;
import com.fasterxml.jackson.databind.ObjectMapper;

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

	@Test
	void shouldIgnoreAdditionalFieldsFromOfficialWeatherResponse() {
		String json = """
				{
				  "code":"200",
				  "updateTime":"2026-07-21T11:00+08:00",
				  "fxLink":"https://www.qweather.com/weather/hangzhou.html",
				  "now":{
				    "obsTime":"2026-07-21T10:50+08:00",
				    "temp":"31","feelsLike":"35","text":"多云",
				    "windDir":"东南风","windScale":"2","humidity":"70",
				    "icon":"101","wind360":"135","windSpeed":"8",
				    "precip":"0.0","pressure":"1002","vis":"16","cloud":"40","dew":"24"
				  },
				  "refer":{"sources":["QWeather"],"license":["QWeather Developers License"]}
				}
				""";
		WeatherService service = new WeatherService("weather.example.com", "test-key", new ObjectMapper());

		WeatherService.WeatherApiResponse response = service.readJson(
				json, WeatherService.WeatherApiResponse.class);

		assertEquals("200", response.code());
		assertEquals("31", response.now().temp());
		assertEquals("多云", response.now().text());
	}
}
