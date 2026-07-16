package com.example.demo.chat;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.example.demo.weather.WeatherCallException;
import com.example.demo.weather.WeatherConfigurationException;
import com.example.demo.weather.WeatherNotFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<ErrorResponse> handleBadRequest(IllegalArgumentException exception) {
		return ResponseEntity.badRequest().body(new ErrorResponse(exception.getMessage()));
	}

	@ExceptionHandler(MissingApiKeyException.class)
	public ResponseEntity<ErrorResponse> handleMissingApiKey(MissingApiKeyException exception) {
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
				.body(new ErrorResponse(exception.getMessage()));
	}

	@ExceptionHandler(QwenCallException.class)
	public ResponseEntity<ErrorResponse> handleQwenError(QwenCallException exception) {
		return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
				.body(new ErrorResponse(exception.getMessage()));
	}

	@ExceptionHandler(WeatherConfigurationException.class)
	public ResponseEntity<ErrorResponse> handleWeatherConfiguration(WeatherConfigurationException exception) {
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
				.body(new ErrorResponse(exception.getMessage()));
	}

	@ExceptionHandler(WeatherNotFoundException.class)
	public ResponseEntity<ErrorResponse> handleWeatherNotFound(WeatherNotFoundException exception) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(new ErrorResponse(exception.getMessage()));
	}

	@ExceptionHandler(WeatherCallException.class)
	public ResponseEntity<ErrorResponse> handleWeatherError(WeatherCallException exception) {
		return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
				.body(new ErrorResponse(exception.getMessage()));
	}

	public record ErrorResponse(String message) {
	}
}
