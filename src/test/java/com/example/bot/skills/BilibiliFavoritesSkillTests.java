package com.example.bot.skills;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.bot.bilibili.BilibiliFavoritesService;

class BilibiliFavoritesSkillTests {
	private final BilibiliFavoritesService favorites = mock(BilibiliFavoritesService.class);
	private final BilibiliFavoritesSkill skill = new BilibiliFavoritesSkill(favorites);

	@Test
	void neverRunsForAConversationOutsideTheAllowlist() {
		when(favorites.authenticated()).thenReturn(true);
		when(favorites.authorized("other")).thenReturn(false);

		assertThat(skill.supports(new SkillContext("other", "我的B站收藏夹里有 Java 教程吗",
				List.of(), List.of()))).isFalse();
	}

	@Test
	void contributesPrivateVideoMatchesForTheAllowlistedConversation() {
		when(favorites.authenticated()).thenReturn(true);
		when(favorites.authorized("account::owner")).thenReturn(true);
		when(favorites.listVideos(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(3)))
				.thenReturn(List.of(new BilibiliFavoritesService.Video(
						"BV1234567890", "Java Agent 教程", "desc", "张三", 120, 8L, true, "字幕片段")));
		SkillContext context = new SkillContext("account::owner", "我的B站收藏夹里有 Agent 教程吗",
				List.of(), List.of());

		assertThat(skill.supports(context)).isTrue();
		assertThat(skill.apply(context).context())
				.contains("您的收藏夹中有对应的视频讲解", "Java Agent 教程", "BV1234567890");
	}
}
