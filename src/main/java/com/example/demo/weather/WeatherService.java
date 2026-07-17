package com.example.demo.weather;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.example.demo.ApplicationException;
import tools.jackson.databind.ObjectMapper;

@Service
public class WeatherService {

	private static final Logger log = LoggerFactory.getLogger(WeatherService.class);
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

	private final String apiHost;
	private final String apiKey;
	private final HttpClient httpClient;
	private final ObjectMapper objectMapper;

	public WeatherService(@Value("${qweather.api-host:}") String apiHost,
			@Value("${qweather.api-key:}") String apiKey,
			ObjectMapper objectMapper) {
		this.apiHost = normalizeApiHost(apiHost);
		this.apiKey = apiKey;
		this.objectMapper = objectMapper;
		this.httpClient = HttpClient.newBuilder()
				.connectTimeout(CONNECT_TIMEOUT)
				.build();
	}

	public WeatherResponse getCurrentWeather(String city) {
		validateConfiguration();
		log.info("正在查询天气：{}", city);

		Location location = lookupLocation(city);
		WeatherApiResponse weather = queryCurrentWeather(location.id());
		CurrentWeather now = weather.now();

		log.info("天气查询成功：{}，{}，{}℃", location.name(), now.text(), now.temp());
		return new WeatherResponse(
				location.name(),
				location.adm1(),
				now.text(),
				now.temp(),
				now.feelsLike(),
				now.humidity(),
				now.windDir(),
				now.windScale(),
				now.obsTime(),
				weather.updateTime());
	}

	/**
	 * 查询未来三天的天气预报（今天、明天和后天）。
	 */
	public WeatherForecastResponse getThreeDayForecast(String city) {
		validateConfiguration();
		log.info("正在查询未来三天天气：{}", city);

		Location location = lookupLocation(city);
		ForecastApiResponse forecast = queryForecast(location.id());
		if (forecast.daily() == null || forecast.daily().isEmpty()) {
			throw new ApplicationException(HttpStatus.BAD_GATEWAY, "和风天气没有返回未来天气数据");
		}

		List<DailyWeatherForecast> daily = forecast.daily().stream()
				.map(item -> new DailyWeatherForecast(
						item.fxDate(), item.textDay(), item.textNight(), item.tempMin(), item.tempMax(),
						item.humidity(), item.windDirDay(), item.windScaleDay(), item.precip()))
				.toList();
		log.info("未来天气查询成功：{}，返回{}天", location.name(), daily.size());
		return new WeatherForecastResponse(location.name(), location.adm1(), forecast.updateTime(), daily);
	}

	private Location lookupLocation(String city) {
		String encodedCity = URLEncoder.encode(city, StandardCharsets.UTF_8);
		URI uri = URI.create(apiHost + "/geo/v2/city/lookup?location=" + encodedCity
				+ "&range=cn&number=1&lang=zh");
		CityLookupResponse response = readJson(sendGet(uri), CityLookupResponse.class);
		if (!"200".equals(response.code())) {
			throw new ApplicationException(HttpStatus.BAD_GATEWAY,
					"城市查询失败，和风天气状态码=" + response.code());
		}
		if (response.location() == null || response.location().isEmpty()) {
			throw new ApplicationException(HttpStatus.NOT_FOUND, "没有找到城市：" + city);
		}
		return response.location().get(0);
	}

	private WeatherApiResponse queryCurrentWeather(String locationId) {
		URI uri = URI.create(apiHost + "/v7/weather/now?location=" + locationId + "&lang=zh&unit=m");
		WeatherApiResponse response = readJson(sendGet(uri), WeatherApiResponse.class);
		if (!"200".equals(response.code()) || response.now() == null) {
			throw new ApplicationException(HttpStatus.BAD_GATEWAY,
					"和风天气返回错误，状态码=" + response.code());
		}
		return response;
	}

	private ForecastApiResponse queryForecast(String locationId) {
		URI uri = URI.create(apiHost + "/v7/weather/3d?location=" + locationId + "&lang=zh&unit=m");
		ForecastApiResponse response = readJson(sendGet(uri), ForecastApiResponse.class);
		if (!"200".equals(response.code())) {
			throw new ApplicationException(HttpStatus.BAD_GATEWAY,
					"和风天气预报返回错误，状态码=" + response.code());
		}
		return response;
	}

	private String sendGet(URI uri) {
		HttpRequest request = HttpRequest.newBuilder(uri)
				.timeout(REQUEST_TIMEOUT)
				.header("Accept", "application/json")
				.header("X-QW-Api-Key", apiKey)
				.GET()
				.build();
		try {
			HttpResponse<byte[]> response = httpClient.send(request,
					HttpResponse.BodyHandlers.ofByteArray());
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				if (response.statusCode() == 403) {
					throw new ApplicationException(HttpStatus.BAD_GATEWAY,
							"和风天气拒绝请求（HTTP 403）。请将 QWEATHER_API_HOST 改为控制台中的专属 API Host，并检查 API Key 权限、余额和安全限制");
				}
				if (response.statusCode() == 401) {
					throw new ApplicationException(HttpStatus.BAD_GATEWAY,
							"和风天气鉴权失败（HTTP 401）。请检查 QWEATHER_API_KEY 是否为 Web API 的有效凭据");
				}
				throw new ApplicationException(HttpStatus.BAD_GATEWAY,
						"和风天气请求失败，HTTP状态码=" + response.statusCode());
			}
			String contentEncoding = response.headers().firstValue("Content-Encoding").orElse("");
			return decodeResponseBody(response.body(), contentEncoding);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new ApplicationException(HttpStatus.BAD_GATEWAY, "天气查询被中断", exception);
		} catch (IOException exception) {
			throw new ApplicationException(HttpStatus.BAD_GATEWAY,
					"无法连接和风天气，请检查网络和 API Host", exception);
		}
	}

	static String decodeResponseBody(byte[] body, String contentEncoding) {
		boolean gzipHeader = contentEncoding != null
				&& contentEncoding.toLowerCase(Locale.ROOT).contains("gzip");
		boolean gzipMagic = body.length >= 2
				&& (body[0] & 0xff) == 0x1f
				&& (body[1] & 0xff) == 0x8b;

		if (!gzipHeader && !gzipMagic) {
			return new String(body, StandardCharsets.UTF_8);
		}
		if (!gzipMagic) {
			throw new ApplicationException(HttpStatus.BAD_GATEWAY,
					"和风天气响应声明为Gzip，但数据格式不正确");
		}

		try (GZIPInputStream gzipInputStream = new GZIPInputStream(new ByteArrayInputStream(body))) {
			return new String(gzipInputStream.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException exception) {
			throw new ApplicationException(HttpStatus.BAD_GATEWAY,
					"无法解压和风天气返回的Gzip数据", exception);
		}
	}

	private <T> T readJson(String json, Class<T> responseType) {
		try {
			return objectMapper.readValue(json, responseType);
		} catch (Exception exception) {
			throw new ApplicationException(HttpStatus.BAD_GATEWAY,
					"无法解析和风天气返回的数据", exception);
		}
	}

	private void validateConfiguration() {
		if (apiHost.isBlank()) {
			throw new ApplicationException(HttpStatus.SERVICE_UNAVAILABLE,
					"请先配置环境变量 QWEATHER_API_HOST");
		}
		if (apiKey == null || apiKey.isBlank()) {
			throw new ApplicationException(HttpStatus.SERVICE_UNAVAILABLE,
					"请先配置环境变量 QWEATHER_API_KEY");
		}
	}

	private static String normalizeApiHost(String apiHost) {
		if (apiHost == null || apiHost.isBlank()) {
			return "";
		}
		String normalized = apiHost.trim();
		if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
			normalized = "https://" + normalized;
		}
		while (normalized.endsWith("/")) {
			normalized = normalized.substring(0, normalized.length() - 1);
		}
		return normalized;
	}

	private record CityLookupResponse(String code, List<Location> location) {
	}

	private record Location(String name, String id, String adm1) {
	}

	private record WeatherApiResponse(String code, String updateTime, CurrentWeather now) {
	}

	private record ForecastApiResponse(String code, String updateTime, List<DailyForecast> daily) {
	}

	private record DailyForecast(
			String fxDate,
			String tempMax,
			String tempMin,
			String textDay,
			String textNight,
			String windDirDay,
			String windScaleDay,
			String humidity,
			String precip) {
	}

	private record CurrentWeather(
			String obsTime,
			String temp,
			String feelsLike,
			String text,
			String windDir,
			String windScale,
			String humidity) {
	}
}
