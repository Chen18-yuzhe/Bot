package com.example.bot.weather;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/weather")
public class WeatherController {

	private final WeatherService weatherService;

	public WeatherController(WeatherService weatherService) {
		this.weatherService = weatherService;
	}

	@GetMapping
	public WeatherResponse getWeather(@RequestParam String city) {
		if (city.isBlank()) {
			throw new IllegalArgumentException("城市名称不能为空");
		}
		return weatherService.getCurrentWeather(city.trim());
	}
}
