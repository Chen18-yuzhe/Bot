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
	private final VisualUnderstandingService visualUnderstandingService = mock(VisualUnderstandingService.class);
	private final ImageGenerationService imageGenerationService = mock(ImageGenerationService.class);
	private final AssistantService assistantService = new AssistantService(
			qwenService, weatherService, new WeatherIntentParser(), new ConversationMemory(8, 30),
			visualUnderstandingService, new ImageGenerationIntentParser(), imageGenerationService);

	@Test
	void shouldRouteExplicitImageRequestToWanModel() {
		String question = "给我画一只在月球上的橘猫";
		GeneratedImage generated = new GeneratedImage(new byte[] { 1, 2 }, "image/png", "wan.png");
		when(qwenService.chat(question)).thenReturn("[IMAGE:一只橘猫穿着宇航服站在月球表面，电影感光影]");
		when(imageGenerationService.generate("一只橘猫穿着宇航服站在月球表面，电影感光影"))
				.thenReturn(generated);

		AssistantReply reply = assistantService.reply("wechat-user-image", question);

		assertEquals("图片生成好了 🎨", reply.text());
		assertEquals(generated, reply.image());
	}

	@Test
	void shouldPolishVisualAnalysisBeforeReplying() {
		List<MediaContent> media = List.of(new MediaContent(MediaContent.MediaType.IMAGE,
				new byte[] { 1, 2, 3 }, "image/jpeg"));
		when(visualUnderstandingService.analyze(media, "")).thenReturn("海滩上，一个人与一只狗面对面坐着。");
		when(qwenService.chatWithContext(eq("请自然地告诉我你从刚才的图片或视频里看到了什么。"),
				contains("不要机械复述"))).thenReturn("看起来像是海边的一次温馨互动，人和狗正面对面坐着呢。");

		String answer = assistantService.understandMedia("wechat-user-1", "", media);

		assertEquals("看起来像是海边的一次温馨互动，人和狗正面对面坐着呢。", answer);
	}

	@Test
	void shouldRouteImageAndTextGenerationRequestWithoutDependingOnHistory() {
		List<MediaContent> media = List.of(new MediaContent(MediaContent.MediaType.IMAGE,
				new byte[] { 1, 2, 3 }, "image/png"));
		String question = "生成一张类似的图片，把豆包放最上边";
		String prompt = "暗色史诗场景，豆包标志位于画面最上方";
		GeneratedImage generated = new GeneratedImage(new byte[] { 4, 5 }, "image/png", "wan.png");
		when(visualUnderstandingService.analyze(media, question)).thenReturn("[IMAGE:" + prompt + "]");
		when(imageGenerationService.generate(prompt)).thenReturn(generated);

		AssistantReply reply = assistantService.replyToMedia("new-session", question, media);

		assertEquals(generated, reply.image());
		verify(qwenService, never()).chatWithContext(org.mockito.ArgumentMatchers.anyString(),
				org.mockito.ArgumentMatchers.anyString());
	}

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
		when(qwenService.chat(question)).thenReturn("[WEATHER:CURRENT:北京]");
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
