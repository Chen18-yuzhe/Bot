package com.example.demo.monitoring;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Short-lived browser sessions for the private admin UI. The admin token is never stored. */
@Service
public class AdminSessionService {
	public static final Duration SESSION_TTL = Duration.ofHours(8);
	private final String adminToken;
	private final SecureRandom random = new SecureRandom();
	private final Map<String, Long> sessions = new ConcurrentHashMap<>();

	public AdminSessionService(@Value("${assistant.admin.token:}") String adminToken) {
		this.adminToken = adminToken == null ? "" : adminToken;
	}

	public String create(String suppliedToken) {
		String expected = adminToken.trim();
		String supplied = normalizeCopiedToken(suppliedToken);
		if (expected.isBlank() || !matches(supplied, expected)) return null;
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		String id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		sessions.put(id, System.currentTimeMillis() + SESSION_TTL.toMillis());
		return id;
	}

	public boolean isConfigured() {
		return !adminToken.trim().isBlank();
	}

	public boolean isActive(String sessionId) {
		if (sessionId == null || sessionId.isBlank()) return false;
		Long expiresAt = sessions.get(sessionId);
		if (expiresAt == null) return false;
		if (expiresAt < System.currentTimeMillis()) {
			sessions.remove(sessionId, expiresAt);
			return false;
		}
		return true;
	}

	public void revoke(String sessionId) {
		if (sessionId != null) sessions.remove(sessionId);
	}

	private static boolean matches(String actual, String expected) {
		return MessageDigest.isEqual((actual == null ? "" : actual).getBytes(StandardCharsets.UTF_8),
				expected.getBytes(StandardCharsets.UTF_8));
	}

	/** Accept the value itself or a line pasted directly from an environment file. */
	private static String normalizeCopiedToken(String rawToken) {
		String value = rawToken == null ? "" : rawToken.trim();
		if (value.startsWith("export ")) value = value.substring("export ".length()).trim();
		int separator = value.indexOf('=');
		if (separator >= 0 && "ADMIN_API_TOKEN".equals(value.substring(0, separator).trim())) {
			value = value.substring(separator + 1).trim();
		}
		if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
				|| (value.startsWith("'") && value.endsWith("'")))) {
			value = value.substring(1, value.length() - 1);
		}
		return value.trim();
	}
}
