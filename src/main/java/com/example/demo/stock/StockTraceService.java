package com.example.demo.stock;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.demo.ApplicationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Client for StockTrace's token-protected WeChat summary endpoint.
 *
 * A short per-symbol cache absorbs repeated questions about the same stock,
 * and the last good summary is served (clearly relevant only seconds/minutes
 * old) when the upstream briefly fails. All HTTP tuning is configurable via
 * stock-trace.* properties; defaults preserve the original behavior.
 */
@Service
public class StockTraceService {

	private static final Logger log = LoggerFactory.getLogger(StockTraceService.class);
	private static final Pattern A_SHARE_SYMBOL = Pattern.compile("^\\d{6}(?:\\.(?:SH|SZ|BJ))?$",
			Pattern.CASE_INSENSITIVE);
	private static final int MAX_CACHE_ENTRIES = 100;

	private final String apiUrl;
	private final String botToken;
	private final ObjectMapper objectMapper;
	private final HttpClient httpClient;
	private final Duration requestTimeout;
	private final int maxAttempts;
	private final long retryDelayMillis;
	private final long cacheTtlMillis;
	private final long staleTtlMillis;
	private final ConcurrentHashMap<String, CachedSummary> cache = new ConcurrentHashMap<>();

	@Autowired
	public StockTraceService(@Value("${stock-trace.api-url:}") String apiUrl,
			@Value("${stock-trace.bot-token:}") String botToken,
			@Value("${stock-trace.request-timeout-seconds:60}") long requestTimeoutSeconds,
			@Value("${stock-trace.max-attempts:3}") int maxAttempts,
			@Value("${stock-trace.retry-delay-millis:800}") long retryDelayMillis,
			@Value("${stock-trace.cache-ttl-seconds:60}") long cacheTtlSeconds,
			@Value("${stock-trace.stale-ttl-seconds:1800}") long staleTtlSeconds,
			ObjectMapper objectMapper) {
		this(apiUrl, botToken, requestTimeoutSeconds, maxAttempts, retryDelayMillis, cacheTtlSeconds,
				staleTtlSeconds, objectMapper, HttpClient.newBuilder()
						.connectTimeout(Duration.ofSeconds(10))
						.version(HttpClient.Version.HTTP_1_1)
						.build());
	}

	StockTraceService(String apiUrl, String botToken, long requestTimeoutSeconds, int maxAttempts,
			long retryDelayMillis, long cacheTtlSeconds, long staleTtlSeconds, ObjectMapper objectMapper,
			HttpClient httpClient) {
		this.apiUrl = apiUrl == null ? "" : apiUrl.trim();
		this.botToken = botToken == null ? "" : botToken.trim();
		this.requestTimeout = Duration.ofSeconds(Math.max(5, requestTimeoutSeconds));
		this.maxAttempts = Math.max(1, maxAttempts);
		this.retryDelayMillis = Math.max(0, retryDelayMillis);
		this.cacheTtlMillis = Math.max(0, cacheTtlSeconds) * 1000;
		this.staleTtlMillis = Math.max(cacheTtlSeconds, staleTtlSeconds) * 1000;
		this.objectMapper = objectMapper;
		this.httpClient = httpClient;
	}

	/** True when both the endpoint URL and the bot token are configured. */
	public boolean isConfigured() {
		return !apiUrl.isBlank() && !botToken.isBlank();
	}

	public StockSummary summary(String symbol) {
		String normalizedSymbol = normalizeSymbol(symbol);
		if (!isConfigured()) {
			throw new ApplicationException(HttpStatus.SERVICE_UNAVAILABLE,
					"股票数据服务尚未配置，请设置 STOCK_TRACE_API_URL 和 STOCK_TRACE_BOT_TOKEN");
		}
		long now = System.currentTimeMillis();
		CachedSummary cached = cache.get(normalizedSymbol);
		if (cached != null && cached.freshUntilMillis() > now) {
			return cached.value();
		}
		try {
			StockSummary fetched = fetchSummary(normalizedSymbol);
			// Anchor the TTL to fetch completion: a slow cold-start fetch must
			// not produce an entry that is already expired on arrival.
			storeInCache(normalizedSymbol, fetched, System.currentTimeMillis());
			return fetched;
		} catch (ApplicationException exception) {
			// Upstream hiccup: a minutes-old verdict is far more useful to the
			// user than an error, and it says when it was generated.
			if (cached != null && cached.staleUntilMillis() > now) {
				log.warn("股票数据服务暂时失败，返回 {} 的近期缓存摘要：{}", normalizedSymbol,
						exception.getMessage());
				return cached.value();
			}
			throw exception;
		}
	}

	private StockSummary fetchSummary(String normalizedSymbol) {
		try {
			String body = objectMapper.writeValueAsString(Map.of("symbol", normalizedSymbol));
			HttpRequest request = HttpRequest.newBuilder(apiEndpoint())
					.timeout(requestTimeout)
					.header("Authorization", "Bearer " + botToken)
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(body))
					.build();
			HttpResponse<String> response = sendWithRetries(request);
			JsonNode payload = readPayload(response.body());
			if (response.statusCode() == 401 || response.statusCode() == 403) {
				throw new ApplicationException(HttpStatus.SERVICE_UNAVAILABLE,
						"股票数据服务鉴权失败，请检查 STOCK_TRACE_BOT_TOKEN");
			}
			if (response.statusCode() == 400) {
				throw new IllegalArgumentException(errorMessage(payload, "股票代码不合法"));
			}
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				throw new ApplicationException(HttpStatus.SERVICE_UNAVAILABLE,
						errorMessage(payload, "股票数据或分析服务暂时不可用，请稍后再试"));
			}
			String reply = payload.path("reply").asText("").trim();
			if (reply.isBlank()) throw new ApplicationException(HttpStatus.BAD_GATEWAY, "股票数据服务没有返回有效摘要");
			return new StockSummary(normalizedSymbol, reply, payload.path("meta"));
		} catch (ApplicationException | IllegalArgumentException exception) {
			throw exception;
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new ApplicationException(HttpStatus.BAD_GATEWAY, "股票数据服务请求被中断", exception);
		} catch (IOException exception) {
			throw new ApplicationException(HttpStatus.BAD_GATEWAY, "无法连接股票数据服务，请稍后再试", exception);
		} catch (Exception exception) {
			throw new ApplicationException(HttpStatus.BAD_GATEWAY, "股票数据服务响应异常，请稍后再试", exception);
		}
	}

	private void storeInCache(String symbol, StockSummary summary, long now) {
		if (cacheTtlMillis <= 0) return;
		cache.put(symbol, new CachedSummary(summary, now + cacheTtlMillis, now + staleTtlMillis));
		if (cache.size() <= MAX_CACHE_ENTRIES) return;
		cache.entrySet().removeIf(entry -> entry.getValue().staleUntilMillis() <= now);
		// Still over capacity: evict oldest-inserted entries one by one (the
		// smallest staleUntil is the oldest write; the fresh entry has the
		// largest and is never evicted). Never clear the whole map — that
		// would also destroy every stale-fallback copy.
		while (cache.size() > MAX_CACHE_ENTRIES) {
			Map.Entry<String, CachedSummary> oldest = null;
			for (Map.Entry<String, CachedSummary> entry : cache.entrySet()) {
				if (oldest == null
						|| entry.getValue().staleUntilMillis() < oldest.getValue().staleUntilMillis()) {
					oldest = entry;
				}
			}
			if (oldest == null) break;
			cache.remove(oldest.getKey(), oldest.getValue());
		}
	}

	private HttpResponse<String> sendWithRetries(HttpRequest request) throws IOException, InterruptedException {
		HttpResponse<String> latestResponse = null;
		IOException latestFailure = null;
		for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			try {
				latestResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
				if (!shouldRetry(latestResponse.statusCode()) || attempt == maxAttempts) return latestResponse;
				log.warn("股票数据服务返回 {}，将进行第 {}/{} 次请求", latestResponse.statusCode(), attempt + 1,
						maxAttempts);
			} catch (IOException exception) {
				latestFailure = exception;
				if (attempt == maxAttempts) throw exception;
				log.warn("股票数据服务连接失败，将进行第 {}/{} 次请求", attempt + 1, maxAttempts);
			}
			Thread.sleep(retryDelayMillis * attempt);
		}
		if (latestResponse != null) return latestResponse;
		throw latestFailure == null ? new IOException("股票数据服务连接失败") : latestFailure;
	}

	static boolean shouldRetry(int statusCode) {
		return statusCode == 429 || statusCode == 502 || statusCode == 503 || statusCode == 504;
	}

	static String normalizeSymbol(String symbol) {
		String value = symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
		if (!A_SHARE_SYMBOL.matcher(value).matches()) {
			throw new IllegalArgumentException("股票代码必须是 6 位 A 股代码，例如 600519 或 600519.SH");
		}
		return value;
	}

	static URI endpoint(String baseUrl) {
		String value = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
		if (value.isBlank()) throw new IllegalArgumentException("股票数据服务地址未配置");
		URI base = URI.create(value);
		String scheme = base.getScheme();
		if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
			throw new IllegalArgumentException("股票数据服务地址必须使用 http 或 https");
		}
		return URI.create(value + "/api/bot/stock-summary");
	}

	private URI apiEndpoint() {
		return endpoint(apiUrl);
	}

	private JsonNode readPayload(String body) throws IOException {
		return objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
	}

	private static String errorMessage(JsonNode payload, String fallback) {
		String message = payload.path("error").path("message").asText("").trim();
		return message.isBlank() ? fallback : message;
	}

	public record StockSummary(String symbol, String reply, JsonNode meta) { }

	private record CachedSummary(StockSummary value, long freshUntilMillis, long staleUntilMillis) { }
}
