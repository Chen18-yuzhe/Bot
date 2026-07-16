package com.example.demo.weather;

import java.util.List;

/** 和风天气未来多日预报的业务结果。 */
public record WeatherForecastResponse(
		String city,
		String province,
		String updateTime,
		List<DailyWeatherForecast> daily) {
}

