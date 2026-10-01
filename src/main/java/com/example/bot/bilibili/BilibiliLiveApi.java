package com.example.bot.bilibili;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
	private static final int MAX_DANMAKU_ITEMS = 240;
	private static final int MAX_DANMAKU_TEXT_CHARS = 160;
	private static final Pattern DANMAKU_ITEM = Pattern.compile("<d(?:\\s[^>]*)?>(.*?)</d>", Pattern.DOTALL);

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

	/**
	 * Reads the current video's public on-screen comments.  Danmaku is not a transcript,
	 * so callers must identify it as a supplementary source when presenting a summary.
	 */
	public DanmakuSnapshot danmaku(String cookie, String bvId) throws Exception {
		String id = bvId == null ? "" : bvId.trim();
		if (!id.matches("(?i)BV[0-9A-Za-z]{8,20}")) {
			throw new IllegalArgumentException("BV 号格式无效");
		}
		JsonNode view = get("/x/web-interface/view?bvid=" + id, cookie);
		long cid = view.path("cid").asLong();
		if (cid <= 0) return new DanmakuSnapshot(id, 0, List.of());
		String xml = getRaw("/x/v1/dm/list.so?oid=" + cid, cookie);
		List<String> all = new ArrayList<>();
		Matcher matcher = DANMAKU_ITEM.matcher(xml);
		while (matcher.find()) {
			String text = cleanDanmaku(matcher.group(1));
			if (!text.isBlank()) all.add(text);
		}
		return new DanmakuSnapshot(id, all.size(), sampleDanmaku(all));
	}

	private JsonNode get(String path, String cookie) throws Exception {
		HttpResponse<String> response = send(path, cookie);
		JsonNode root = objectMapper.readTree(response.body());
		int code = root.path("code").asInt(-1);
		if (code != 0) {
			throw new IllegalStateException("Bilibili API 返回 code=" + code
					+ "，message=" + root.path("message").asText("unknown"));
		}
		return root.path("data");
	}

	private String getRaw(String path, String cookie) throws Exception {
		return send(path, cookie).body();
	}

	private HttpResponse<String> send(String path, String cookie) throws Exception {
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
		return response;
	}

	private static List<String> sampleDanmaku(List<String> all) {
		Set<String> unique = new LinkedHashSet<>(all);
		List<String> values = new ArrayList<>(unique);
		if (values.size() <= MAX_DANMAKU_ITEMS) return List.copyOf(values);
		List<String> sampled = new ArrayList<>(MAX_DANMAKU_ITEMS);
		for (int index = 0; index < MAX_DANMAKU_ITEMS; index++) {
			int sourceIndex = (int) ((long) index * values.size() / MAX_DANMAKU_ITEMS);
			sampled.add(values.get(sourceIndex));
		}
		return List.copyOf(sampled);
	}

	private static String cleanDanmaku(String value) {
		String result = value == null ? "" : value
				.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
				.replace("&quot;", "\"").replace("&#39;", "'").trim();
		return result.length() <= MAX_DANMAKU_TEXT_CHARS
				? result : result.substring(0, MAX_DANMAKU_TEXT_CHARS) + "…";
	}

	private static long userId(String cookie) {
		Matcher matcher = USER_ID.matcher(cookie == null ? "" : cookie);
		if (!matcher.find()) throw new IllegalArgumentException("Bilibili Cookie 缺少 DedeUserID");
		return Long.parseLong(matcher.group(1));
	}

	public record LiveVideo(String bvId, String title, String description, String upName,
			Integer durationSeconds, long folderId) { }
	public record DanmakuSnapshot(String bvId, int totalComments, List<String> samples) { }
}
