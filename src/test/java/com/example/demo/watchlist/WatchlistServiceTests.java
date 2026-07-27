package com.example.demo.watchlist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

class WatchlistServiceTests {

	@SuppressWarnings("unchecked")
	private static WatchlistService service(int maxSymbols) {
		ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(null);
		return new WatchlistService(provider, "test:watchlist:", maxSymbols);
	}

	@Test
	void shouldAddListAndRemoveSymbols() {
		WatchlistService service = service(20);
		assertTrue(service.add("default::user-1", "600519"));
		assertFalse(service.add("default::user-1", "600519.SH"));
		assertTrue(service.add("default::user-1", "000001"));

		assertEquals(List.of("000001", "600519"), service.list("default::user-1"));
		assertTrue(service.remove("default::user-1", "600519"));
		assertFalse(service.remove("default::user-1", "600519"));
		assertEquals(List.of("000001"), service.list("default::user-1"));
	}

	@Test
	void watchlistsAreIsolatedPerSession() {
		WatchlistService service = service(20);
		service.add("default::user-1", "600519");
		assertTrue(service.list("default::user-2").isEmpty());
	}

	@Test
	void shouldEnforceCapAndValidateSymbols() {
		WatchlistService service = service(1);
		service.add("default::user-1", "600519");
		assertThrows(IllegalArgumentException.class, () -> service.add("default::user-1", "000001"));
		assertThrows(IllegalArgumentException.class, () -> service.add("default::user-1", "茅台"));
		assertThrows(IllegalArgumentException.class, () -> service.add("default::user-1", "12345"));
	}
}
