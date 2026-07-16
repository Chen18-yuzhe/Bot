package com.example.demo.chat;

public class MissingApiKeyException extends RuntimeException {

	public MissingApiKeyException(String message) {
		super(message);
	}
}
