package com.example.demo.chat;

public record WeatherIntent(boolean weatherQuestion, String city, WeatherQueryType queryType) {

	public enum WeatherQueryType {
		CURRENT,
		FORECAST
	}

	public static WeatherIntent notWeather() {
		return new WeatherIntent(false, null, WeatherQueryType.CURRENT);
	}

	public static WeatherIntent weather(String city) {
		return weather(city, WeatherQueryType.CURRENT);
	}

	public static WeatherIntent weather(String city, WeatherQueryType queryType) {
		return new WeatherIntent(true, city, queryType);
	}
}
