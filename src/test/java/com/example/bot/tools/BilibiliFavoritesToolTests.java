package com.example.bot.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.example.bot.ApplicationException;
import com.example.bot.bilibili.BilibiliFavoritesService;
import com.example.bot.bilibili.BilibiliQrLoginService;
import com.fasterxml.jackson.databind.ObjectMapper;

class BilibiliFavoritesToolTests {
	private final BilibiliFavoritesService favorites = mock(BilibiliFavoritesService.class);
	private final BilibiliQrLoginService qrLogin = mock(BilibiliQrLoginService.class);
	private final ObjectMapper mapper = new ObjectMapper();
	private final BilibiliFavoritesTool tool = new BilibiliFavoritesTool(favorites, qrLogin, mapper);

	@Test
	void checksSessionAuthorizationBeforeReadingPrivateData() {
		org.mockito.Mockito.doThrow(new ApplicationException(HttpStatus.FORBIDDEN, "not allowed"))
				.when(favorites).requireAuthorized("other");

		assertThatThrownBy(() -> tool.execute(
				mapper.createObjectNode().put("action", "list_folders"),
				new ToolContext("other", "列出B站收藏夹")))
				.isInstanceOf(ApplicationException.class);
		verify(favorites).requireAuthorized("other");
	}

	@Test
	void delegatesFolderSynchronization() {
		when(favorites.authenticated("account::owner")).thenReturn(true);
		when(favorites.syncFolder("account::owner", 42L, true)).thenReturn(
				new BilibiliFavoritesService.SyncResult(42L, 3, 2, true));

		ToolResult result = tool.execute(mapper.createObjectNode()
				.put("action", "sync_folder").put("folderId", 42).put("includeSubtitles", true),
				new ToolContext("account::owner", "同步收藏夹"));

		assertThat(result.content()).contains("\"syncedVideos\":3");
		verify(favorites).requireAuthorized("account::owner");
		verify(favorites).syncFolder("account::owner", 42L, true);
	}

	@Test
	void requiresAndPassesTheLiveFolderIdWhenListingVideos() {
		when(favorites.authenticated("account::owner")).thenReturn(true);
		when(favorites.listVideos("account::owner", 42L, "", 10))
				.thenReturn(java.util.List.of());

		tool.execute(mapper.createObjectNode()
				.put("action", "list_videos").put("folderId", 42),
				new ToolContext("account::owner", "查看默认收藏夹视频"));

		verify(favorites).listVideos("account::owner", 42L, "", 10);
	}

	@Test
	void refusesToListVideosWithoutAFolderId() {
		assertThatThrownBy(() -> tool.execute(mapper.createObjectNode()
				.put("action", "list_videos"),
				new ToolContext("account::owner", "查看收藏夹视频")))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("folderId");
	}

	@Test
	void generatesQrAndHandsOffWhenTheAccountIsNotLoggedIn() {
		when(qrLogin.start("account::owner")).thenReturn(new BilibiliQrLoginService.LoginStatus(
				BilibiliQrLoginService.State.WAITING_FOR_SCAN, true, "请扫码"));

		assertThatThrownBy(() -> tool.execute(mapper.createObjectNode().put("action", "start_login"),
				new ToolContext("account::owner", "列出B站收藏夹")))
				.isInstanceOf(com.example.bot.agent.AgentToolException.class)
				.hasMessageContaining("等待扫码");
		verify(qrLogin).start("account::owner");
	}

	@Test
	void returnsDanmakuForTheRequestedRealBvId() {
		when(favorites.authenticated("account::owner")).thenReturn(true);
		when(favorites.danmaku("account::owner", "BV1234567890"))
				.thenReturn(new com.example.bot.bilibili.BilibiliLiveApi.DanmakuSnapshot(
						"BV1234567890", 1, java.util.List.of("有帮助")));

		ToolResult result = tool.execute(mapper.createObjectNode()
				.put("action", "get_danmaku").put("bvId", "BV1234567890"),
				new ToolContext("account::owner", "总结视频"));

		assertThat(result.content()).contains("有帮助");
		verify(favorites).danmaku("account::owner", "BV1234567890");
	}
}
