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
import com.fasterxml.jackson.databind.ObjectMapper;

class BilibiliFavoritesToolTests {
	private final BilibiliFavoritesService favorites = mock(BilibiliFavoritesService.class);
	private final ObjectMapper mapper = new ObjectMapper();
	private final BilibiliFavoritesTool tool = new BilibiliFavoritesTool(favorites, mapper);

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
		when(favorites.syncFolder(42L, true)).thenReturn(
				new BilibiliFavoritesService.SyncResult(42L, 3, 2, true));

		ToolResult result = tool.execute(mapper.createObjectNode()
				.put("action", "sync_folder").put("folderId", 42).put("includeSubtitles", true),
				new ToolContext("account::owner", "同步收藏夹"));

		assertThat(result.content()).contains("\"syncedVideos\":3");
		verify(favorites).requireAuthorized("account::owner");
	}

	@Test
	void requiresAndPassesTheLiveFolderIdWhenListingVideos() {
		when(favorites.listVideos(42L, "", 10)).thenReturn(java.util.List.of());

		tool.execute(mapper.createObjectNode()
				.put("action", "list_videos").put("folderId", 42),
				new ToolContext("account::owner", "查看默认收藏夹视频"));

		verify(favorites).listVideos(42L, "", 10);
	}

	@Test
	void refusesToListVideosWithoutAFolderId() {
		assertThatThrownBy(() -> tool.execute(mapper.createObjectNode()
				.put("action", "list_videos"),
				new ToolContext("account::owner", "查看收藏夹视频")))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("folderId");
	}
}
