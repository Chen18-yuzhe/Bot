package com.example.bot;

import org.springframework.http.HttpStatus;

public class ApplicationException extends RuntimeException {

	private final HttpStatus status;

	public ApplicationException(HttpStatus status, String message) {
		this(status, message, null);
	}

	public ApplicationException(HttpStatus status, String message, Throwable cause) {
		super(message, cause);
		this.status = status;
	}

	public HttpStatus getStatus() {
		return status;
	}
}
