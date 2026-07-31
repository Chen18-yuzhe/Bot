package com.example.bot.bilibili;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.example.demo.model.BiliVideo;
import com.example.demo.service.FavService;
import com.example.bot.ApplicationException;
import com.example.bot.knowledge.KnowledgeBaseService;

import jakarta.annotation.PreDestroy;

/**
 * Thread-safe adapter around the local java-fav SDK.
 *
 * <p>The Bilibili cookie is never returned or logged. An empty cookie keeps the
 * integration available for QR login without opening the SDK database.</p>
 */
@Service
public class BilibiliFavoritesService {
	private static final int MAX_QUERY_LENGTH = 160;
	private static final int MAX_KNOWLEDGE_CHARS = 200_000;

	private final Function<String, FavService> clientFactory;
	private final BilibiliLiveApi liveApi;
	private final KnowledgeBaseService knowledgeBase;
	private final String configuredCookie;
	private final Set<String> allowedSessionIds;
	private String runtimeCookie = "";
	private FavService client;

	@Autowired
	public BilibiliFavoritesService(@Value("${bilibili.cookie:}") String configuredCookie,
			@Value("${bilibili.allowed-session-ids:}") String allowedSessionIds,
			KnowledgeBaseService knowledgeBase, BilibiliLiveApi liveApi) {
		this(configuredCookie, allowedSessionIds, knowledgeBase, FavService::new, liveApi);
	}

	BilibiliFavoritesService(String configuredCookie, String allowedSessionIds,
			KnowledgeBaseService knowledgeBase,
			Function<String, FavService> clientFactory, BilibiliLiveApi liveApi) {
		this.configuredCookie = clean(configuredCookie);
		this.allowedSessionIds = parseAllowedSessions(allowedSessionIds);
		this.knowledgeBase = knowledgeBase;
		this.clientFactory = clientFactory;
		this.liveApi = liveApi;
	}

	public synchronized boolean authenticated() {
		return validCookie(activeCookie());
	}

	public synchronized Status status() {
		String source = !configuredCookie.isBlank() ? "environment"
				: (!runtimeCookie.isBlank() ? "runtime" : "none");
		return new Status(authenticated(), source,
				client == null ? 0 : client.countWithSubtitle());
	}

	public boolean authorized(String sessionId) {
		return sessionId != null && allowedSessionIds.contains(sessionId.trim());
	}

	public void requireAuthorized(String sessionId) {
		if (!authorized(sessionId)) {
			throw new ApplicationException(HttpStatus.FORBIDDEN,
					"当前会话未获准访问私人 Bilibili 收藏夹；请由管理员配置 BILI_ALLOWED_SESSION_IDS");
		}
	}

	public synchronized void useRuntimeCookie(String cookie) {
		String value = clean(cookie);
		if (!validCookie(value)) throw new IllegalArgumentException("Bilibili 登录结果缺少 DedeUserID");
		closeClient();
		runtimeCookie = value;
	}

	public synchronized List<Folder> listFolders() {
		try {
			return liveApi.listFolders(activeCookie()).entrySet().stream()
					.map(entry -> new Folder(entry.getKey(), entry.getValue()))
					.sorted(Comparator.comparing(Folder::name, String.CASE_INSENSITIVE_ORDER))
					.toList();
		} catch (Exception error) {
			throw upstream("读取 Bilibili 收藏夹失败", error);
		}
	}

	public synchronized SyncResult syncFolder(long folderId, boolean includeSubtitles) {
		if (folderId <= 0) throw new IllegalArgumentException("folderId 必须为正整数");
		try {
			requireOwnedFolder(folderId);
			int synced = includeSubtitles
					? client().fetchFavWithSubtitle(folderId)
					: client().fetchFavMeta(folderId);
			return new SyncResult(folderId, synced, client().countWithSubtitle(), includeSubtitles);
		} catch (Exception error) {
			throw upstream("同步 Bilibili 收藏夹失败", error);
		}
	}

	public synchronized List<Video> listVideos(String query, int requestedLimit) {
		try {
			List<Video> result = new ArrayList<>();
			for (Folder folder : listFolders()) {
				appendLiveVideos(result, folder.id(), query, requestedLimit);
				if (result.size() >= boundedLimit(requestedLimit)) break;
			}
			return List.copyOf(result);
		} catch (Exception error) {
			throw upstream("读取 Bilibili 视频列表失败", error);
		}
	}

	public synchronized List<Video> listVideos(long folderId, String query, int requestedLimit) {
		if (folderId <= 0) throw new IllegalArgumentException("folderId 必须为正整数");
		try {
			requireOwnedFolder(folderId);
			List<Video> result = new ArrayList<>();
			appendLiveVideos(result, folderId, query, requestedLimit);
			return List.copyOf(result);
		} catch (Exception error) {
			throw upstream("读取 Bilibili 收藏夹视频失败", error);
		}
	}

	private void appendLiveVideos(List<Video> result, long folderId, String query, int requestedLimit)
			throws Exception {
		String keyword = normalizeQuery(query);
		int limit = boundedLimit(requestedLimit);
		for (BilibiliLiveApi.LiveVideo video : liveApi.listFolderVideos(activeCookie(), folderId)) {
			String subtitle = cachedSubtitle(video.bvId());
			if (!matches(video, subtitle, keyword)) continue;
			result.add(toVideo(video, subtitle));
			if (result.size() >= limit) break;
		}
	}

	private String cachedSubtitle(String bvId) {
		if (client == null || bvId == null || bvId.isBlank()) return "";
		String subtitle = client.getSubtitleText(bvId);
		return subtitle == null ? "" : subtitle;
	}

	public synchronized String subtitle(String bvId) {
		String id = clean(bvId);
		if (!id.matches("(?i)BV[0-9A-Za-z]{8,20}")) throw new IllegalArgumentException("BV 号格式无效");
		String subtitle = client().getSubtitleText(id);
		return subtitle == null ? "" : subtitle;
	}

	public synchronized IngestResult syncAndIngest(long folderId, String sessionId) {
		SyncResult sync = syncFolder(folderId, true);
		Set<String> liveVideoIds;
		try {
			liveVideoIds = liveApi.listFolderVideos(activeCookie(), folderId).stream()
					.map(BilibiliLiveApi.LiveVideo::bvId)
					.filter(id -> !id.isBlank())
					.collect(Collectors.toUnmodifiableSet());
		} catch (Exception error) {
			throw upstream("核对 Bilibili 收藏夹实时内容失败", error);
		}
		int imported = 0;
		for (BiliVideo video : client().listAllVideos()) {
			if (video.getFavId() == null || video.getFavId().longValue() != folderId) continue;
			if (!liveVideoIds.contains(video.getBvId())) continue;
			String subtitle = client().getSubtitleText(video.getBvId());
			if (subtitle == null || subtitle.isBlank()) continue;
			String content = knowledgeDocument(video, subtitle);
			knowledgeBase.upsertForSession(sessionId, "bilibili-" + video.getBvId() + ".md", content);
			imported++;
		}
		return new IngestResult(folderId, sync.syncedVideos(), imported);
	}

	@PreDestroy
	public synchronized void close() {
		closeClient();
	}

	private FavService client() {
		if (client != null) return client;
		String cookie = activeCookie();
		if (!validCookie(cookie)) {
			throw new IllegalStateException("尚未登录 Bilibili；请配置 BILI_COOKIE 或在私有管理接口完成扫码登录");
		}
		client = clientFactory.apply(cookie);
		return client;
	}

	private String activeCookie() {
		return runtimeCookie.isBlank() ? configuredCookie : runtimeCookie;
	}

	private void requireOwnedFolder(long folderId) throws Exception {
		if (!liveApi.listFolders(activeCookie()).containsKey(folderId)) {
			throw new IllegalArgumentException("当前 Bilibili 账号不存在收藏夹 " + folderId);
		}
	}

	private void closeClient() {
		if (client == null) return;
		client.close();
		client = null;
	}

	private static boolean validCookie(String value) {
		return value != null && value.matches("(?s).*\\bDedeUserID=\\d+.*");
	}

	private static boolean matches(BiliVideo video, String subtitle, String keyword) {
		if (keyword.isBlank()) return true;
		String haystack = String.join("\n",
				clean(video.getTitle()), clean(video.getDescription()), clean(video.getUpName()),
				clean(video.getTname()), clean(video.getBvId()), clean(subtitle)).toLowerCase(Locale.ROOT);
		for (String token : keyword.split("\\s+")) {
			if (!token.isBlank() && haystack.contains(token)) return true;
		}
		return false;
	}

	private static boolean matches(BilibiliLiveApi.LiveVideo video, String subtitle, String keyword) {
		if (keyword.isBlank()) return true;
		String haystack = String.join("\n",
				clean(video.title()), clean(video.description()), clean(video.upName()),
				clean(video.bvId()), clean(subtitle)).toLowerCase(Locale.ROOT);
		for (String token : keyword.split("\\s+")) {
			if (!token.isBlank() && haystack.contains(token)) return true;
		}
		return false;
	}

	private static Video toVideo(BiliVideo video, String subtitle) {
		String text = clean(subtitle);
		String excerpt = text.length() <= 500 ? text : text.substring(0, 500) + "…";
		return new Video(video.getBvId(), video.getTitle(), video.getDescription(), video.getUpName(),
				video.getDuration(), video.getFavId(), !text.isBlank(), excerpt);
	}

	private static Video toVideo(BilibiliLiveApi.LiveVideo video, String subtitle) {
		String text = clean(subtitle);
		String excerpt = text.length() <= 500 ? text : text.substring(0, 500) + "…";
		return new Video(video.bvId(), video.title(), video.description(), video.upName(),
				video.durationSeconds(), video.folderId(), !text.isBlank(), excerpt);
	}

	private static int boundedLimit(int requestedLimit) {
		return Math.max(1, Math.min(50, requestedLimit));
	}

	private static String knowledgeDocument(BiliVideo video, String subtitle) {
		String text = subtitle.length() <= MAX_KNOWLEDGE_CHARS
				? subtitle : subtitle.substring(0, MAX_KNOWLEDGE_CHARS);
		return """
				# %s

				- BV号：%s
				- UP主：%s
				- 收藏夹ID：%s
				- 分区：%s
				- 时长：%s 秒

				## 简介

				%s

				## 字幕

				%s
				""".formatted(clean(video.getTitle()), clean(video.getBvId()), clean(video.getUpName()),
				video.getFavId(), clean(video.getTname()), video.getDuration(),
				clean(video.getDescription()), text);
	}

	private static String normalizeQuery(String query) {
		String value = clean(query).toLowerCase(Locale.ROOT);
		if (value.length() > MAX_QUERY_LENGTH) throw new IllegalArgumentException("query 最多 160 个字符");
		return value;
	}

	private static Set<String> parseAllowedSessions(String value) {
		if (value == null || value.isBlank()) return Set.of();
		return java.util.Arrays.stream(value.split(","))
				.map(String::trim)
				.filter(item -> !item.isBlank())
				.collect(Collectors.toUnmodifiableSet());
	}

	private static IllegalStateException upstream(String message, Exception error) {
		return new IllegalStateException(message + "：" + clean(error.getMessage()), error);
	}

	private static String clean(String value) {
		return value == null ? "" : value.trim();
	}

	public record Status(boolean authenticated, String credentialSource, int localVideosWithSubtitle) { }
	public record Folder(long id, String name) { }
	public record Video(String bvId, String title, String description, String upName,
			Integer durationSeconds, Long folderId, boolean hasSubtitle, String subtitleExcerpt) { }
	public record SyncResult(long folderId, int syncedVideos, int localVideosWithSubtitle,
			boolean subtitlesRequested) { }
	public record IngestResult(long folderId, int syncedVideos, int importedKnowledgeDocuments) { }
}
