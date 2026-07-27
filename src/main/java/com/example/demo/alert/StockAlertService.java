package com.example.demo.alert;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.example.demo.ApplicationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Client for StockTrace's owner-scoped internal API (Bearer STOCK_INTERNAL_API_TOKEN).
 *
 * The public reverse proxy deliberately 404s /internal/api/v1/*, so the
 * configured base URL must reach the StockTrace app over the container/private
 * network (e.g. http://app:3000). Covers alert rule CRUD, the pending
 * alert-event pull/receipt loop and the lightweight quote contract.
 */
@Service
public class StockAlertService {

	static final Pattern STOCK_CODE = Pattern.compile("^\\d{6}$");
	private static final List<String> RULE_TYPES = List.of("PRICE_ABOVE", "PRICE_BELOW", "RETURN_ABOVE",
			"LOSS_ABOVE", "DAILY_CHANGE_ABOVE", "TAKE_PROFIT", "STOP_LOSS");
	private static final List<String> OPERATORS = List.of("GT", "GTE", "LT", "LTE");

	private final String apiUrl;
	private final String apiToken;
	private final Duration requestTimeout;
	private final ObjectMapper objectMapper;
	private final HttpClient httpClient;

	@Autowired
	public StockAlertService(@Value("${stock-trace.internal-api-url:}") String apiUrl,
			@Value("${stock-trace.internal-api-token:}") String apiToken,
			@Value("${stock-trace.internal-request-timeout-seconds:15}") long requestTimeoutSeconds,
			ObjectMapper objectMapper) {
		this(apiUrl, apiToken, requestTimeoutSeconds, objectMapper, HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(5))
				.version(HttpClient.Version.HTTP_1_1)
				.build());
	}

	StockAlertService(String apiUrl, String apiToken, long requestTimeoutSeconds, ObjectMapper objectMapper,
			HttpClient httpClient) {
		this.apiUrl = apiUrl == null ? "" : apiUrl.trim().replaceAll("/+$", "");
		this.apiToken = apiToken == null ? "" : apiToken.trim();
		this.requestTimeout = Duration.ofSeconds(Math.max(3, requestTimeoutSeconds));
		this.objectMapper = objectMapper;
		this.httpClient = httpClient;
	}

	public boolean isConfigured() {
		return !apiUrl.isBlank() && !apiToken.isBlank();
	}

	public List<AlertRule> listAlerts() {
		JsonNode data = call("GET", "/internal/api/v1/alerts", null);
		List<AlertRule> rules = new ArrayList<>();
		for (JsonNode item : data) rules.add(readRule(item));
		return List.copyOf(rules);
	}

	public AlertRule createAlert(String stockCode, String ruleType, String operator, String threshold,
			int cooldownMinutes) {
		String code = normalizeStockCode(stockCode);
		String type = normalizeRuleType(ruleType);
		String comparison = operator == null || operator.isBlank()
				? defaultOperator(type)
				: normalizeOperator(operator);
		if (threshold == null || threshold.isBlank()) throw new IllegalArgumentException("预警阈值不能为空");
		try {
			Double.parseDouble(threshold.trim());
		} catch (NumberFormatException error) {
			throw new IllegalArgumentException("预警阈值必须是数字：" + threshold);
		}
		Map<String, Object> body = Map.of("stockCode", code, "ruleType", type, "operator", comparison,
				"threshold", threshold.trim(), "enabled", true,
				"cooldownMinutes", Math.max(0, Math.min(525_600, cooldownMinutes)));
		return readRule(call("POST", "/internal/api/v1/alerts", body));
	}

	public AlertRule setEnabled(String alertId, boolean enabled) {
		return readRule(call("PUT", "/internal/api/v1/alerts/" + requireId(alertId), Map.of("enabled", enabled)));
	}

	public void deleteAlert(String alertId) {
		call("DELETE", "/internal/api/v1/alerts/" + requireId(alertId), null);
	}

	/** Undelivered trigger events, oldest first. */
	public List<AlertEvent> pendingEvents() {
		JsonNode data = call("GET", "/internal/api/v1/alert-events/pending", null);
		List<AlertEvent> events = new ArrayList<>();
		for (JsonNode item : data) {
			events.add(new AlertEvent(item.path("id").asText(""), item.path("alert_id").asText(""),
					item.path("observed_value").asText(""), item.path("threshold").asText(""),
					item.path("triggered_at").asText(""), item.path("payload")));
		}
		return List.copyOf(events);
	}

	/**
	 * Marks one event delivered. Returns false when the event no longer exists
	 * or was already receipted (another poller won the race) — not an error.
	 */
	public boolean markDelivered(String eventId) {
		try {
			call("POST", "/internal/api/v1/alert-events/" + requireId(eventId) + "/delivered", Map.of());
			return true;
		} catch (ApplicationException exception) {
			if (exception.getStatus() == HttpStatus.NOT_FOUND) return false;
			throw exception;
		}
	}

	/** Lightweight quote for watchlists and briefings (no LLM analysis involved). */
	public StockQuote quote(String stockCode) {
		String code = normalizeStockCode(stockCode);
		JsonNode data = call("GET", "/internal/api/v1/stocks/" + code + "/quote", null);
		return new StockQuote(data.path("stockCode").asText(code), data.path("latestPrice").asText(""),
				data.path("changeAmount").asText(""), data.path("changePercent").asText(""),
				data.path("quoteTime").asText(""), data.path("status").asText(""));
	}

	static String normalizeStockCode(String stockCode) {
		String value = stockCode == null ? "" : stockCode.trim();
		// Accept the exchange-suffixed style users already send elsewhere.
		int dot = value.indexOf('.');
		if (dot > 0) value = value.substring(0, dot);
		if (!STOCK_CODE.matcher(value).matches()) {
			throw new IllegalArgumentException("股票代码必须是 6 位 A 股代码，例如 600519");
		}
		return value;
	}

	static String normalizeRuleType(String ruleType) {
		String value = ruleType == null ? "" : ruleType.trim().toUpperCase(Locale.ROOT);
		if (!RULE_TYPES.contains(value)) {
			throw new IllegalArgumentException("ruleType 只能是 " + String.join("、", RULE_TYPES));
		}
		return value;
	}

	static String normalizeOperator(String operator) {
		String value = operator.trim().toUpperCase(Locale.ROOT);
		if (!OPERATORS.contains(value)) {
			throw new IllegalArgumentException("operator 只能是 GT、GTE、LT 或 LTE");
		}
		return value;
	}

	/** Sensible comparison direction when the model omits the operator. */
	static String defaultOperator(String ruleType) {
		return switch (ruleType) {
			case "PRICE_BELOW", "STOP_LOSS" -> "LTE";
			default -> "GTE";
		};
	}

	private static String requireId(String id) {
		String value = id == null ? "" : id.trim();
		if (value.isBlank() || !value.matches("[A-Za-z0-9-]{8,64}")) {
			throw new IllegalArgumentException("预警 ID 无效");
		}
		return value;
	}

	private AlertRule readRule(JsonNode item) {
		return new AlertRule(item.path("id").asText(""), item.path("stock_code").asText(""),
				item.path("rule_type").asText(""), item.path("operator").asText(""),
				item.path("threshold").asText(""), item.path("enabled").asBoolean(true),
				item.path("cooldown_minutes").asInt(60), item.path("created_at").asText(""));
	}

	private JsonNode call(String method, String path, Map<String, Object> body) {
		if (!isConfigured()) {
			throw new ApplicationException(HttpStatus.SERVICE_UNAVAILABLE,
					"股票预警服务尚未配置，请设置 STOCK_TRACE_INTERNAL_API_URL 和 STOCK_TRACE_INTERNAL_API_TOKEN");
		}
		try {
			HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(apiUrl + path))
					.timeout(requestTimeout)
					.header("Authorization", "Bearer " + apiToken)
					.header("Content-Type", "application/json");
			HttpRequest.BodyPublisher publisher = body == null
					? HttpRequest.BodyPublishers.noBody()
					: HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body));
			HttpRequest request = builder.method(method, publisher).build();
			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			JsonNode payload = objectMapper.readTree(response.body() == null || response.body().isBlank()
					? "{}" : response.body());
			int status = response.statusCode();
			if (status == 401 || status == 403) {
				throw new ApplicationException(HttpStatus.SERVICE_UNAVAILABLE,
						"股票预警服务鉴权失败，请检查 STOCK_TRACE_INTERNAL_API_TOKEN");
			}
			if (status == 404) {
				throw new ApplicationException(HttpStatus.NOT_FOUND, errorMessage(payload, "预警或事件不存在"));
			}
			if (status == 422 || status == 400) {
				throw new IllegalArgumentException(errorMessage(payload, "预警参数不合法"));
			}
			if (status < 200 || status >= 300) {
				throw new ApplicationException(HttpStatus.SERVICE_UNAVAILABLE,
						errorMessage(payload, "股票预警服务暂时不可用，请稍后再试"));
			}
			return payload.path("data");
		} catch (ApplicationException | IllegalArgumentException exception) {
			throw exception;
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new ApplicationException(HttpStatus.BAD_GATEWAY, "股票预警服务请求被中断", exception);
		} catch (IOException exception) {
			throw new ApplicationException(HttpStatus.BAD_GATEWAY, "无法连接股票预警服务，请稍后再试", exception);
		}
	}

	private static String errorMessage(JsonNode payload, String fallback) {
		String message = payload.path("error").path("message").asText("").trim();
		return message.isBlank() ? fallback : message;
	}

	public record AlertRule(String id, String stockCode, String ruleType, String operator, String threshold,
			boolean enabled, int cooldownMinutes, String createdAt) { }

	public record AlertEvent(String id, String alertId, String observedValue, String threshold,
			String triggeredAt, JsonNode payload) { }

	public record StockQuote(String stockCode, String latestPrice, String changeAmount, String changePercent,
			String quoteTime, String status) { }
}
