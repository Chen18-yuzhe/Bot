package com.example.bot.bilibili;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.example.demo.model.BiliVideo;
import com.example.demo.service.FavService;
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
	private final ConcurrentHashMap<String, AccountState> accountStates = new ConcurrentHashMap<>();

	@Autowired
	public BilibiliFavoritesService(KnowledgeBaseService knowledgeBase, BilibiliLiveApi liveApi) {
		this(knowledgeBase, FavService::new, liveApi);
	}

	BilibiliFavoritesService(KnowledgeBaseService knowledgeBase,
			Function<String, FavService> clientFactory, BilibiliLiveApi liveApi) {
		this.knowledgeBase = knowledgeBase;
		this.clientFactory = clientFactory;
		this.liveApi = liveApi;
	}

	public synchronized boolean authenticated() {
		return authenticated("default");
	}

	public synchronized Status status() {
		return status("default");
	}

	public synchronized boolean authenticated(String sessionOrProjectId) {
		return validCookie(activeCookie(credentialId(sessionOrProjectId)));
	}

	public synchronized Status status(String sessionOrProjectId) {
		String credentialId = credentialId(sessionOrProjectId);
		AccountState state = accountStates.get(credentialId);
		String source = state != null && !state.runtimeCookie.isBlank() ? "qr-login" : "none";
		return new Status(authenticated(credentialId), source,
				state == null || state.client == null ? 0 : state.client.countWithSubtitle());
	}

	public boolean authorized(String sessionId) {
		if (sessionId == null || sessionId.isBlank()) return false;
		return true;
	}

	public void requireAuthorized(String sessionId) {
		if (!authorized(sessionId)) {
			throw new IllegalArgumentException("Bilibili 会话无效");
		}
	}

	public synchronized void useRuntimeCookie(String projectId, String cookie) {
		String project = credentialId(projectId);
		String value = clean(cookie);
		if (!validCookie(value)) throw new IllegalArgumentException("Bilibili 登录结果缺少 DedeUserID");
		AccountState state = accountStates.computeIfAbsent(project, ignored -> new AccountState());
		closeClient(state);
		state.runtimeCookie = value;
	}

	public synchronized List<Folder> listFolders() {
		return listFolders("default");
	}

	public synchronized List<Folder> listFolders(String sessionId) {
		String projectId = credentialId(sessionId);
		try {
			return liveApi.listFolders(activeCookie(projectId)).entrySet().stream()
					.map(entry -> new Folder(entry.getKey(), entry.getValue()))
					.sorted(Comparator.comparing(Folder::name, String.CASE_INSENSITIVE_ORDER))
					.toList();
		} catch (Exception error) {
			throw upstream("读取 Bilibili 收藏夹失败", error);
		}
	}

	public synchronized SyncResult syncFolder(long folderId, boolean includeSubtitles) {
		return syncFolder("default", folderId, includeSubtitles);
	}

	public synchronized SyncResult syncFolder(String sessionId, long folderId, boolean includeSubtitles) {
		String projectId = credentialId(sessionId);
		if (folderId <= 0) throw new IllegalArgumentException("folderId 必须为正整数");
		try {
			requireOwnedFolder(projectId, folderId);
			int synced = includeSubtitles
					? client(projectId).fetchFavWithSubtitle(folderId)
					: client(projectId).fetchFavMeta(folderId);
			return new SyncResult(folderId, synced, client(projectId).countWithSubtitle(), includeSubtitles);
		} catch (Exception error) {
			throw upstream("同步 Bilibili 收藏夹失败", error);
		}
	}

	public synchronized List<Video> listVideos(String query, int requestedLimit) {
		return listVideos("default", query, requestedLimit);
	}

	public synchronized List<Video> listVideos(String sessionId, String query, int requestedLimit) {
		String projectId = credentialId(sessionId);
		try {
			List<Video> result = new ArrayList<>();
			for (Folder folder : listFolders(projectId)) {
				appendLiveVideos(projectId, result, folder.id(), query, requestedLimit);
				if (result.size() >= boundedLimit(requestedLimit)) break;
			}
			return List.copyOf(result);
		} catch (Exception error) {
			throw upstream("读取 Bilibili 视频列表失败", error);
		}
	}

	public synchronized List<Video> listVideos(long folderId, String query, int requestedLimit) {
		return listVideos("default", folderId, query, requestedLimit);
	}

	public synchronized List<Video> listVideos(String sessionId, long folderId, String query, int requestedLimit) {
		String projectId = credentialId(sessionId);
		if (folderId <= 0) throw new IllegalArgumentException("folderId 必须为正整数");
		try {
			requireOwnedFolder(projectId, folderId);
			List<Video> result = new ArrayList<>();
			appendLiveVideos(projectId, result, folderId, query, requestedLimit);
			return List.copyOf(result);
		} catch (Exception error) {
			throw upstream("读取 Bilibili 收藏夹视频失败", error);
		}
	}

	private void appendLiveVideos(String projectId, List<Video> result, long folderId, String query, int requestedLimit)
			throws Exception {
		String keyword = normalizeQuery(query);
		int limit = boundedLimit(requestedLimit);
		for (BilibiliLiveApi.LiveVideo video : liveApi.listFolderVideos(activeCookie(projectId), folderId)) {
			String subtitle = cachedSubtitle(projectId, video.bvId());
			if (!matches(video, subtitle, keyword)) continue;
			result.add(toVideo(video, subtitle));
			if (result.size() >= limit) break;
		}
	}

	private String cachedSubtitle(String projectId, String bvId) {
		AccountState state = accountStates.get(projectId);
		if (state == null || state.client == null || bvId == null || bvId.isBlank()) return "";
		String subtitle = state.client.getSubtitleText(bvId);
		return subtitle == null ? "" : subtitle;
	}

	public synchronized String subtitle(String bvId) {
		return subtitle("default", bvId);
	}

	public synchronized String subtitle(String sessionId, String bvId) {
		String projectId = credentialId(sessionId);
		String id = clean(bvId);
		if (!id.matches("(?i)BV[0-9A-Za-z]{8,20}")) throw new IllegalArgumentException("BV 号格式无效");
		String subtitle = client(projectId).getSubtitleText(id);
		return subtitle == null ? "" : subtitle;
	}

	public synchronized BilibiliLiveApi.DanmakuSnapshot danmaku(String sessionId, String bvId) {
		String projectId = credentialId(sessionId);
		try {
			return liveApi.danmaku(activeCookie(projectId), bvId);
		} catch (Exception error) {
			throw upstream("读取 Bilibili 弹幕失败", error);
		}
	}

	public synchronized IngestResult syncAndIngest(long folderId, String sessionId) {
		String projectId = credentialId(sessionId);
		SyncResult sync = syncFolder(projectId, folderId, true);
		Set<String> liveVideoIds;
		try {
			liveVideoIds = liveApi.listFolderVideos(activeCookie(projectId), folderId).stream()
					.map(BilibiliLiveApi.LiveVideo::bvId)
					.filter(id -> !id.isBlank())
					.collect(Collectors.toUnmodifiableSet());
		} catch (Exception error) {
			throw upstream("核对 Bilibili 收藏夹实时内容失败", error);
		}
		int imported = 0;
		for (BiliVideo video : client(projectId).listAllVideos()) {
			if (video.getFavId() == null || video.getFavId().longValue() != folderId) continue;
			if (!liveVideoIds.contains(video.getBvId())) continue;
			String subtitle = client(projectId).getSubtitleText(video.getBvId());
			if (subtitle == null || subtitle.isBlank()) continue;
			String content = knowledgeDocument(video, subtitle);
			knowledgeBase.upsertForSession(sessionId, "bilibili-" + video.getBvId() + ".md", content);
			imported++;
		}
		return new IngestResult(folderId, sync.syncedVideos(), imported);
	}

	@PreDestroy
	public synchronized void close() {
		accountStates.values().forEach(this::closeClient);
		accountStates.clear();
	}

	private FavService client(String projectId) {
		AccountState state = accountStates.computeIfAbsent(projectId, ignored -> new AccountState());
		if (state.client != null) return state.client;
		String cookie = activeCookie(projectId);
		if (!validCookie(cookie)) {
			String guidance = "请在控制台为项目 " + projectId + " 完成 Bilibili 扫码登录";
			throw new IllegalStateException("尚未登录 Bilibili；" + guidance);
		}
		state.client = clientFactory.apply(cookie);
		return state.client;
	}

	private String activeCookie(String projectId) {
		AccountState state = accountStates.get(projectId);
		if (state != null && !state.runtimeCookie.isBlank()) return state.runtimeCookie;
		return "";
	}

	private void requireOwnedFolder(String projectId, long folderId) throws Exception {
		if (!liveApi.listFolders(activeCookie(projectId)).containsKey(folderId)) {
			throw new IllegalArgumentException("当前 Bilibili 账号不存在收藏夹 " + folderId);
		}
	}

	private void closeClient(AccountState state) {
		if (state == null || state.client == null) return;
		state.client.close();
		state.client = null;
	}

	public static String projectId(String sessionOrProjectId) {
		String value = clean(sessionOrProjectId);
		if (value.isBlank() || "default".equals(value)) return "default";
		int marker = value.indexOf("::");
		if (marker > 0) return value.substring(0, marker);
		return value.matches("(?i)[0-9a-f]{8}-[0-9a-f-]{27,}") ? value : "default";
	}

	/** Exact session scope for a QR credential; never collapse it to a project. */
	public static String credentialId(String sessionId) {
		String value = clean(sessionId);
		return value.isBlank() ? "default" : value;
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

	private static final class AccountState {
		private String runtimeCookie = "";
		private FavService client;
	}
}
