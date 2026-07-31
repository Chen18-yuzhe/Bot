package com.example.bot.bilibili;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Live, read-only Bilibili API access that never exposes the configured cookie. */
@Component
public class BilibiliLiveApi {
	private static final String API_BASE = "https://api.bilibili.com";
	private static final String REFERER = "https://www.bilibili.com/";
	private static final String USER_AGENT = "Mozilla/5.0";
	private static final Pattern USER_ID = Pattern.compile("(?:^|;\\s*)DedeUserID=(\\d+)(?:;|$)");
	private static final int PAGE_SIZE = 20;
	private static final int MAX_PAGES = 100;

	private final HttpClient httpClient;
	private final ObjectMapper objectMapper;

	@Autowired
	public BilibiliLiveApi(ObjectMapper objectMapper) {
		this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), objectMapper);
	}

	BilibiliLiveApi(HttpClient httpClient, ObjectMapper objectMapper) {
		this.httpClient = httpClient;
		this.objectMapper = objectMapper;
	}

	public Map<Long, String> listFolders(String cookie) throws Exception {
		long userId = userId(cookie);
		JsonNode data = get("/x/v3/fav/folder/created/list-all?up_mid=" + userId, cookie);
		Map<Long, String> result = new LinkedHashMap<>();
		for (JsonNode item : data.path("list")) {
			long id = item.path("id").asLong();
			if (id > 0) result.put(id, item.path("title").asText(""));
		}
		return Map.copyOf(result);
	}

	public List<LiveVideo> listFolderVideos(String cookie, long folderId) throws Exception {
		List<LiveVideo> result = new ArrayList<>();
		for (int page = 1; page <= MAX_PAGES; page++) {
			String path = "/x/v3/fav/resource/list?media_id=" + folderId
					+ "&pn=" + page + "&ps=" + PAGE_SIZE + "&platform=web";
			JsonNode data = get(path, cookie);
			JsonNode medias = data.path("medias");
			if (!medias.isArray() || medias.isEmpty()) break;
			for (JsonNode item : medias) {
				JsonNode upper = item.path("upper");
				result.add(new LiveVideo(
						item.path("bvid").asText(""),
						item.path("title").asText(""),
						item.path("intro").asText(""),
						upper.path("name").asText(""),
						item.path("duration").isNumber() ? item.path("duration").asInt() : null,
						folderId));
			}
			if (!data.path("has_more").asBoolean(false)) break;
		}
		return List.copyOf(result);
	}

	private JsonNode get(String path, String cookie) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create(API_BASE + path))
				.timeout(Duration.ofSeconds(30))
				.header("User-Agent", USER_AGENT)
				.header("Referer", REFERER)
				.header("Cookie", cookie)
				.GET().build();
		HttpResponse<String> response = httpClient.send(
				request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			throw new IllegalStateException("Bilibili API 返回 HTTP " + response.statusCode());
		}
		JsonNode root = objectMapper.readTree(response.body());
		int code = root.path("code").asInt(-1);
		if (code != 0) {
			throw new IllegalStateException("Bilibili API 返回 code=" + code
					+ "，message=" + root.path("message").asText("unknown"));
		}
		return root.path("data");
	}

	private static long userId(String cookie) {
		Matcher matcher = USER_ID.matcher(cookie == null ? "" : cookie);
		if (!matcher.find()) throw new IllegalArgumentException("Bilibili Cookie 缺少 DedeUserID");
		return Long.parseLong(matcher.group(1));
	}

	public record LiveVideo(String bvId, String title, String description, String upName,
			Integer durationSeconds, long folderId) { }
}
