package com.example.demo.chat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
public class WeatherIntentParser {

	private static final Pattern WEATHER_TOOL_PATTERN = Pattern.compile(
			"\\[\\[WEATHER:(CURRENT|FORECAST):([\\p{IsHan}]{2,24})\\]\\]");
	private static final Pattern INVALID_CITY_PATTERN = Pattern.compile(
			"为什么|为啥|不能|无法|不会|查询|怎么|如何|你|我");

	/**
	 * 只解析千问明确返回的天气工具标记，不再从用户原始问题中猜测天气意图或城市。
	 */
	public WeatherIntent parse(String qwenAnswer) {
		if (qwenAnswer == null || qwenAnswer.isBlank()) {
			return WeatherIntent.notWeather();
		}

		Matcher matcher = WEATHER_TOOL_PATTERN.matcher(qwenAnswer);
		if (!matcher.find()) {
			return WeatherIntent.notWeather();
		}

		String city = matcher.group(2);
		if (INVALID_CITY_PATTERN.matcher(city).find()) {
			return WeatherIntent.notWeather();
		}

		WeatherIntent.WeatherQueryType queryType = WeatherIntent.WeatherQueryType.valueOf(matcher.group(1));
		return WeatherIntent.weather(city, queryType);
	}
}
