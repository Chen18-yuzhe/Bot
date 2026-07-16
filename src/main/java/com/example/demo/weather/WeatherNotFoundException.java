package com.example.demo.weather;

public class WeatherNotFoundException extends RuntimeException {

	public WeatherNotFoundException(String message) {
		super(message);
	}
}
