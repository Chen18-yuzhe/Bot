package com.example.demo.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.demo.weather.DailyWeatherForecast;
import com.example.demo.weather.WeatherForecastResponse;
import com.example.demo.weather.WeatherResponse;
import com.example.demo.weather.WeatherService;

class AssistantServiceTests {

	private final QwenService qwenService = mock(QwenService.class);
	private final WeatherService weatherService = mock(WeatherService.class);
	private final AssistantService assistantService = new AssistantService(
			qwenService, weatherService, new WeatherIntentParser(), new ConversationMemory(8, 30));

	@Test
	void shouldUseQwenDirectlyForNormalQuestion() {
		when(qwenService.chat("你是谁？")).thenReturn("我是AI助手");

		String answer = assistantService.chat("你是谁？");

		assertEquals("我是AI助手", answer);
		verify(weatherService, never()).getCurrentWeather(org.mockito.ArgumentMatchers.anyString());
		verify(weatherService, never()).getThreeDayForecast(org.mockito.ArgumentMatchers.anyString());
	}

	@Test
	void shouldQueryCurrentWeatherAndGiveItToQwen() {
		String question = "北京今天天气怎么样？";
		WeatherResponse weather = new WeatherResponse(
				"北京", "北京市", "晴", "30", "31", "45", "东南风", "2",
				"2026-07-16T10:00+08:00", "2026-07-16T10:05+08:00");
		when(qwenService.chat(question)).thenReturn("[[WEATHER:CURRENT:北京]]");
		when(weatherService.getCurrentWeather("北京")).thenReturn(weather);
		when(qwenService.chatWithContext(eq(question), contains("温度：30℃")))
				.thenReturn("北京现在晴，30℃。");

		String answer = assistantService.chat(question);

		assertEquals("北京现在晴，30℃。", answer);
		verify(weatherService).getCurrentWeather("北京");
		verify(weatherService, never()).getThreeDayForecast(org.mockito.ArgumentMatchers.anyString());
	}

	@Test
	void shouldQueryForecastAndGiveItToQwen() {
		String question = "北京未来几天天气怎么样？";
		WeatherForecastResponse forecast = new WeatherForecastResponse(
				"北京", "北京市", "2026-07-16T10:05+08:00", List.of(
						new DailyWeatherForecast("2026-07-16", "晴", "多云", "24", "32", "45", "东南风", "2", "0.0"),
						new DailyWeatherForecast("2026-07-17", "多云", "小雨", "23", "30", "60", "南风", "3", "2.1")));
		when(qwenService.chat(question)).thenReturn("[[WEATHER:FORECAST:北京]]");
		when(weatherService.getThreeDayForecast("北京")).thenReturn(forecast);
		when(qwenService.chatWithContext(eq(question), contains("2026-07-17")))
				.thenReturn("未来几天以晴到多云为主。");

		String answer = assistantService.chat(question);

		assertEquals("未来几天以晴到多云为主。", answer);
		verify(weatherService).getThreeDayForecast("北京");
		verify(weatherService, never()).getCurrentWeather(org.mockito.ArgumentMatchers.anyString());
	}

	@Test
	void shouldReturnQwenFollowUpWhenWeatherCityIsMissing() {
		when(qwenService.chat("今天天气怎么样？"))
				.thenReturn("请告诉我你想查询哪个城市的天气。");

		String answer = assistantService.chat("今天天气怎么样？");

		assertEquals("请告诉我你想查询哪个城市的天气。", answer);
		verify(weatherService, never()).getCurrentWeather(org.mockito.ArgumentMatchers.anyString());
		verify(weatherService, never()).getThreeDayForecast(org.mockito.ArgumentMatchers.anyString());
	}

	@Test
	void shouldNotCallWeatherForQuestionAboutWeatherCapability() {
		String question = "你为啥不能查天气";
		when(qwenService.chat(question)).thenReturn("我可以帮你查询天气，请告诉我城市。");

		String answer = assistantService.chat(question);

		assertEquals("我可以帮你查询天气，请告诉我城市。", answer);
		verify(weatherService, never()).getCurrentWeather(org.mockito.ArgumentMatchers.anyString());
		verify(weatherService, never()).getThreeDayForecast(org.mockito.ArgumentMatchers.anyString());
	}

	@Test
	void shouldPassPreviousTurnsForTheSameWechatUser() {
		when(qwenService.chat("我叫小陈")).thenReturn("你好，小陈。");
		when(qwenService.chat("我叫什么？", List.of(new ConversationTurn("我叫小陈", "你好，小陈。"))))
				.thenReturn("你叫小陈。");

		assistantService.chat("wechat-user-1", "我叫小陈");
		String answer = assistantService.chat("wechat-user-1", "我叫什么？");

		assertEquals("你叫小陈。", answer);
	}
}
