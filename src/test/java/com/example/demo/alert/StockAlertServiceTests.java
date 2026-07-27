package com.example.demo.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

import com.example.demo.ApplicationException;
import com.fasterxml.jackson.databind.ObjectMapper;

class StockAlertServiceTests {

	@SuppressWarnings("unchecked")
	private static HttpResponse<String> response(int status, String body) {
		HttpResponse<String> response = mock(HttpResponse.class);
		when(response.statusCode()).thenReturn(status);
		when(response.body()).thenReturn(body);
		return response;
	}

	private static StockAlertService service(HttpClient httpClient) {
		return new StockAlertService("http://app:3000", "internal-token", 15, new ObjectMapper(), httpClient);
	}

	@Test
	void shouldRejectWhenNotConfigured() {
		StockAlertService service = new StockAlertService("", "", 15, new ObjectMapper(), mock(HttpClient.class));
		assertFalse(service.isConfigured());
		assertThrows(ApplicationException.class, service::pendingEvents);
	}

	@Test
	void shouldParsePendingEvents() throws Exception {
		HttpClient httpClient = mock(HttpClient.class);
		HttpResponse<String> ok = response(200, """
				{"data":[{"id":"event-1","alert_id":"alert-1","observed_value":"1398.500000",
				"threshold":"1400.000000","triggered_at":"2026-07-26T02:30:00Z","payload":{}}]}
				""");
		when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(ok);
		var events = service(httpClient).pendingEvents();

		assertEquals(1, events.size());
		assertEquals("event-1", events.get(0).id());
		assertEquals("alert-1", events.get(0).alertId());
	}

	@Test
	void markDeliveredShouldTreatMissingEventAsAlreadyReceipted() throws Exception {
		HttpClient httpClient = mock(HttpClient.class);
		HttpResponse<String> notFound = response(404, "{\"error\":{\"message\":\"待发送事件不存在\"}}");
		when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(notFound);
		assertFalse(service(httpClient).markDelivered("event-404"));
	}

	@Test
	void createShouldValidateInputsBeforeCallingUpstream() {
		StockAlertService service = service(mock(HttpClient.class));
		assertThrows(IllegalArgumentException.class,
				() -> service.createAlert("茅台", "PRICE_BELOW", null, "1400", 60));
		assertThrows(IllegalArgumentException.class,
				() -> service.createAlert("600519", "WHEN_MOON", null, "1400", 60));
		assertThrows(IllegalArgumentException.class,
				() -> service.createAlert("600519", "PRICE_BELOW", null, "很便宜", 60));
	}

	@Test
	void shouldDefaultOperatorByRuleType() {
		assertEquals("LTE", StockAlertService.defaultOperator("PRICE_BELOW"));
		assertEquals("LTE", StockAlertService.defaultOperator("STOP_LOSS"));
		assertEquals("GTE", StockAlertService.defaultOperator("PRICE_ABOVE"));
		assertEquals("GTE", StockAlertService.defaultOperator("DAILY_CHANGE_ABOVE"));
	}

	@Test
	void shouldAcceptExchangeSuffixedCode() {
		assertEquals("600519", StockAlertService.normalizeStockCode("600519.SH"));
		assertTrue(StockAlertService.STOCK_CODE.matcher(StockAlertService.normalizeStockCode("000001")).matches());
	}
}
