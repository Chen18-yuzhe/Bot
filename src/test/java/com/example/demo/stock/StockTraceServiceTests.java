package com.example.demo.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

import com.example.demo.ApplicationException;
import com.fasterxml.jackson.databind.ObjectMapper;

class StockTraceServiceTests {

	@SuppressWarnings("unchecked")
	private static HttpResponse<String> response(int status, String body) {
		HttpResponse<String> response = mock(HttpResponse.class);
		when(response.statusCode()).thenReturn(status);
		when(response.body()).thenReturn(body);
		return response;
	}

	private static StockTraceService service(HttpClient httpClient, long cacheTtlSeconds) {
		return new StockTraceService("https://stocktrace.test", "token", 45, 1, 0, cacheTtlSeconds,
				1800, new ObjectMapper(), httpClient);
	}

	@Test
	void shouldServeRepeatedQuestionsFromShortCache() throws Exception {
		HttpClient httpClient = mock(HttpClient.class);
		HttpResponse<String> ok = response(200, "{\"reply\":\"摘要内容\",\"meta\":{}}");
		when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(ok);
		StockTraceService service = service(httpClient, 60);

		assertEquals("摘要内容", service.summary("600519").reply());
		assertEquals("摘要内容", service.summary("600519").reply());
		verify(httpClient, times(1)).send(any(), any(HttpResponse.BodyHandler.class));
	}

	@Test
	void shouldFallBackToRecentSummaryWhenUpstreamFails() throws Exception {
		HttpClient httpClient = mock(HttpClient.class);
		HttpResponse<String> ok = response(200, "{\"reply\":\"旧摘要\",\"meta\":{}}");
		HttpResponse<String> failed = response(500, "{}");
		when(httpClient.send(any(), any(HttpResponse.BodyHandler.class)))
				.thenReturn(ok)
				.thenReturn(failed);
		// 1s fresh window: after it passes, the failing refresh must fall back
		// to the still-valid stale copy instead of surfacing an error.
		StockTraceService service = service(httpClient, 1);

		assertEquals("旧摘要", service.summary("600519").reply());
		Thread.sleep(1_200);
		assertEquals("旧摘要", service.summary("600519").reply());
		verify(httpClient, times(2)).send(any(), any(HttpResponse.BodyHandler.class));
	}

	@Test
	void shouldPropagateFailuresWhenCachingDisabled() throws Exception {
		HttpClient httpClient = mock(HttpClient.class);
		HttpResponse<String> ok = response(200, "{\"reply\":\"旧摘要\",\"meta\":{}}");
		HttpResponse<String> failed = response(500, "{}");
		when(httpClient.send(any(), any(HttpResponse.BodyHandler.class)))
				.thenReturn(ok)
				.thenReturn(failed);
		StockTraceService service = service(httpClient, 0);

		assertEquals("旧摘要", service.summary("600519").reply());
		assertThrows(ApplicationException.class, () -> service.summary("600519"));
	}

	@Test
	void shouldReportConfigurationState() {
		HttpClient httpClient = mock(HttpClient.class);
		assertTrue(service(httpClient, 60).isConfigured());
		assertFalse(new StockTraceService("", "", 45, 3, 800, 60, 1800, new ObjectMapper(), httpClient)
				.isConfigured());
	}

	@Test
	void shouldNormalizeSupportedAShareSymbols() {
		assertEquals("600519", StockTraceService.normalizeSymbol(" 600519 "));
		assertEquals("000001.SZ", StockTraceService.normalizeSymbol("000001.sz"));
	}

	@Test
	void shouldRejectUnsupportedSymbolsAndAddresses() {
		assertThrows(IllegalArgumentException.class, () -> StockTraceService.normalizeSymbol("贵州茅台"));
		assertThrows(IllegalArgumentException.class, () -> StockTraceService.normalizeSymbol("AAPL"));
		assertThrows(IllegalArgumentException.class, () -> StockTraceService.endpoint("ftp://example.test"));
	}

	@Test
	void shouldBuildBotEndpointFromConfiguredBaseUrl() {
		assertEquals(URI.create("http://127.0.0.1/api/bot/stock-summary"),
				StockTraceService.endpoint("http://127.0.0.1/"));
	}

	@Test
	void shouldRetryOnlyTransientUpstreamStatuses() {
		assertTrue(StockTraceService.shouldRetry(429));
		assertTrue(StockTraceService.shouldRetry(502));
		assertTrue(StockTraceService.shouldRetry(503));
		assertTrue(StockTraceService.shouldRetry(504));
		assertEquals(false, StockTraceService.shouldRetry(401));
	}
}
