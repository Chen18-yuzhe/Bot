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
import com.example.bot.ApplicationException;
import com.example.bot.knowledge.KnowledgeBaseService;

class BilibiliFavoritesServiceTests {
	private final FavService sdk = mock(FavService.class);
	private final KnowledgeBaseService knowledge = mock(KnowledgeBaseService.class);
	private final BilibiliFavoritesService service = new BilibiliFavoritesService(
			"SESSDATA=secret; DedeUserID=123", "account::owner", knowledge, cookie -> sdk);

	@Test
	void accessIsDeniedUnlessTheExactSessionIsAllowlisted() {
		assertThat(service.authorized("account::owner")).isTrue();
		assertThat(service.authorized("account::other")).isFalse();
		assertThatThrownBy(() -> service.requireAuthorized("account::other"))
				.isInstanceOf(ApplicationException.class)
				.hasMessageContaining("未获准");
	}

	@Test
	void listsFoldersWithoutReturningTheCookie() throws Exception {
		when(sdk.listFavFolders()).thenReturn(Map.of(2L, "稍后看", 1L, "Java"));

		var folders = service.listFolders();

		assertThat(folders).extracting(BilibiliFavoritesService.Folder::name)
				.containsExactly("Java", "稍后看");
		assertThat(folders.toString()).doesNotContain("SESSDATA", "secret");
	}

	@Test
	void searchesMetadataAndSubtitleLocally() {
		BiliVideo video = video("BV1234567890", "Spring Agent", "张三", 8L);
		when(sdk.listAllVideos()).thenReturn(List.of(video));
		when(sdk.getSubtitleText("BV1234567890")).thenReturn("这里讲解 Function Calling");

		var result = service.listVideos("Function Calling", 10);

		assertThat(result).hasSize(1);
		assertThat(result.getFirst().hasSubtitle()).isTrue();
		assertThat(result.getFirst().subtitleExcerpt()).contains("Function Calling");
	}

	@Test
	void syncsSubtitlesAndUpsertsKnowledgeDocuments() throws Exception {
		BiliVideo video = video("BV1234567890", "Java 教程", "张三", 8L);
		when(sdk.fetchFavWithSubtitle(8L)).thenReturn(1);
		when(sdk.countWithSubtitle()).thenReturn(1);
		when(sdk.listAllVideos()).thenReturn(List.of(video));
		when(sdk.getSubtitleText("BV1234567890")).thenReturn("字幕正文");

		var result = service.syncAndIngest(8L, "account::owner");

		assertThat(result.importedKnowledgeDocuments()).isEqualTo(1);
		verify(knowledge).upsertForSession(
				org.mockito.ArgumentMatchers.eq("account::owner"),
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
}
