package com.example.bot.bilibili;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.example.demo.model.BiliVideo;
import com.example.demo.service.FavService;
import com.example.bot.knowledge.KnowledgeBaseService;

class BilibiliFavoritesServiceTests {
	private final FavService sdk = mock(FavService.class);
	private final BilibiliLiveApi liveApi = mock(BilibiliLiveApi.class);
	private final KnowledgeBaseService knowledge = mock(KnowledgeBaseService.class);
	private final BilibiliFavoritesService service = new BilibiliFavoritesService(
			knowledge, cookie -> sdk, liveApi);

	@Test
	void everyNonBlankSessionCanUseItsOwnQrLoginButBlankSessionsAreRejected() {
		assertThat(service.authorized("account::owner")).isTrue();
		assertThat(service.authorized("account::other")).isTrue();
		assertThat(service.authorized("unlisted-default-user")).isTrue();
		assertThat(service.authorized(" ")).isFalse();
		assertThatThrownBy(() -> service.requireAuthorized(" "))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("会话无效");
	}

	@Test
	void listsFoldersWithoutReturningTheCookie() throws Exception {
		login("default");
		when(liveApi.listFolders("SESSDATA=qr; DedeUserID=123"))
				.thenReturn(Map.of(2L, "稍后看", 1L, "Java"));

		var folders = service.listFolders("default");

		assertThat(folders).extracting(BilibiliFavoritesService.Folder::name)
				.containsExactly("Java", "稍后看");
		assertThat(folders.toString()).doesNotContain("SESSDATA", "secret");
	}

	@Test
	void qrCookieBelongsOnlyToTheExactConversation() throws Exception {
		String project = "12345678-1234-1234-1234-123456789012";
		assertThat(service.authenticated("default-user")).isFalse();
		assertThat(service.authenticated(project + "::owner")).isFalse();

		service.useRuntimeCookie(project + "::owner", "SESSDATA=project; DedeUserID=456");

		assertThat(service.status("default-user").credentialSource()).isEqualTo("none");
		assertThat(service.status(project + "::owner").credentialSource()).isEqualTo("qr-login");
		assertThat(service.authenticated(project + "::owner")).isTrue();
		assertThat(service.authenticated(project + "::other")).isFalse();
	}

	@Test
	void qrCookieIsReportedAsTheOnlyCredentialSource() {
		service.useRuntimeCookie("default", "SESSDATA=qr; DedeUserID=456");

		assertThat(service.status("default").credentialSource()).isEqualTo("qr-login");
		assertThat(service.authenticated("default")).isTrue();
	}

	@Test
	void listsOnlyVideosReturnedByTheOwnedLiveFolder() throws Exception {
		login("default");
		when(liveApi.listFolders("SESSDATA=qr; DedeUserID=123"))
				.thenReturn(Map.of(8L, "默认收藏夹"));
		when(liveApi.listFolderVideos("SESSDATA=qr; DedeUserID=123", 8L))
				.thenReturn(List.of(new BilibiliLiveApi.LiveVideo(
						"BV1234567890", "Spring Agent", "description", "张三", 120, 8L)));
		when(sdk.getSubtitleText("BV1234567890")).thenReturn("这里讲解 Function Calling");

		var result = service.listVideos(8L, "", 10);

		assertThat(result).hasSize(1);
		assertThat(result.getFirst().title()).isEqualTo("Spring Agent");
		assertThat(result.getFirst().hasSubtitle()).isFalse();
	}

	@Test
	void rejectsFolderIdsThatDoNotBelongToTheCurrentCookie() throws Exception {
		login("default");
		when(liveApi.listFolders("SESSDATA=qr; DedeUserID=123"))
				.thenReturn(Map.of(8L, "默认收藏夹"));

		assertThatThrownBy(() -> service.listVideos(999L, "", 10))
				.hasMessageContaining("当前 Bilibili 账号不存在收藏夹 999");
	}

	@Test
	void readsDanmakuUsingOnlyTheCurrentSessionsQrCredential() throws Exception {
		login("owner");
		when(liveApi.danmaku("SESSDATA=qr; DedeUserID=123", "BV1234567890"))
				.thenReturn(new BilibiliLiveApi.DanmakuSnapshot("BV1234567890", 2, List.of("好课")));

		var result = service.danmaku("owner", "BV1234567890");

		assertThat(result.samples()).containsExactly("好课");
		verify(liveApi).danmaku("SESSDATA=qr; DedeUserID=123", "BV1234567890");
	}

	@Test
	void syncsSubtitlesAndUpsertsKnowledgeDocuments() throws Exception {
		BiliVideo video = video("BV1234567890", "Java 教程", "张三", 8L);
		login("owner");
		when(liveApi.listFolders("SESSDATA=qr; DedeUserID=123"))
				.thenReturn(Map.of(8L, "默认收藏夹"));
		when(liveApi.listFolderVideos("SESSDATA=qr; DedeUserID=123", 8L))
				.thenReturn(List.of(new BilibiliLiveApi.LiveVideo(
						"BV1234567890", "Java 教程", "description", "张三", 120, 8L)));
		when(sdk.fetchFavWithSubtitle(8L)).thenReturn(1);
		when(sdk.countWithSubtitle()).thenReturn(1);
		when(sdk.listAllVideos()).thenReturn(List.of(video));
		when(sdk.getSubtitleText("BV1234567890")).thenReturn("字幕正文");

		var result = service.syncAndIngest(8L, "owner");

		assertThat(result.importedKnowledgeDocuments()).isEqualTo(1);
		verify(knowledge).upsertForSession(
				org.mockito.ArgumentMatchers.eq("owner"),
				org.mockito.ArgumentMatchers.eq("bilibili-BV1234567890.md"),
				org.mockito.ArgumentMatchers.contains("字幕正文"));
	}

	private static BiliVideo video(String bvId, String title, String upName, long folderId) {
		BiliVideo video = new BiliVideo();
		video.setBvId(bvId);
		video.setTitle(title);
		video.setDescription("description");
		video.setUpName(upName);
		video.setDuration(120);
		video.setFavId(folderId);
		video.setFetchedAt(LocalDateTime.now());
		return video;
	}

	private void login(String sessionId) {
		service.useRuntimeCookie(sessionId, "SESSDATA=qr; DedeUserID=123");
	}
}
