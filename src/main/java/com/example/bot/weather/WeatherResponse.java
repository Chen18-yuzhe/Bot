package com.example.bot.weather;

public record WeatherResponse(
		String city,
		String province,
		String weather,
		String temperature,
		String feelsLike,
		String humidity,
		String windDirection,
		String windScale,
		String observationTime,
		String updateTime) {
}
