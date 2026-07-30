package com.example.demo.monitoring;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AdminSessionServiceTests {
	@Test
	void createsAndRevokesOnlyValidSessions() {
		AdminSessionService service = new AdminSessionService("test-token");
		assertNull(service.create("wrong"));
		String sessionId = service.create("test-token");
		assertNotNull(sessionId);
		assertTrue(service.isActive(sessionId));
		assertNotNull(service.create("  ADMIN_API_TOKEN='test-token'  "));
		assertNotNull(service.create("export ADMIN_API_TOKEN=test-token"));
		assertNotNull(service.create("test\u200B-token\uFEFF"));
		assertTrue(service.isValidToken("ADMIN_API_TOKEN=test-token"));
		assertFalse(service.isValidToken("wrong"));
		service.revoke(sessionId);
		assertFalse(service.isActive(sessionId));
	}
}
