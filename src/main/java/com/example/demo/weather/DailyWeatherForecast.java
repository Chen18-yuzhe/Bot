package com.example.demo.weather;

/** 一天的天气预报。 */
public record DailyWeatherForecast(
		String date,
		String weatherDay,
		String weatherNight,
		String tempMin,
		String tempMax,
		String humidity,
		String windDirection,
		String windScale,
		String precipitation) {
}

