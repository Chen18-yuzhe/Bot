package com.example.demo.travel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Deliberately records references and hashes, never identity tokens, payment data or ticket codes. */
@Service
public class TravelAuditService {
	private static final Logger log = LoggerFactory.getLogger(TravelAuditService.class);

	public void record(String action, String userId, String orderId, String outcome) {
		log.info("travel_audit action={} subject={} order={} outcome={}", action, shortHash(userId),
				orderId == null ? "-" : orderId, outcome);
	}

	private static String shortHash(String value) {
		if (value == null || value.isBlank()) return "anonymous";
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder valueBuilder = new StringBuilder();
			for (int i = 0; i < 8; i++) valueBuilder.append(String.format("%02x", digest[i]));
			return valueBuilder.toString();
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}
}
