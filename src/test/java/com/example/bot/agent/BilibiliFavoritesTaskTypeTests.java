package com.example.bot.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BilibiliFavoritesTaskTypeTests {
	private final BilibiliFavoritesTaskType type = new BilibiliFavoritesTaskType();

	@Test
	void matchesPrivateFavoritesAndAllowsOnlyTheBilibiliTool() {
		assertThat(type.supports("列出我的B站收藏夹")).isTrue();
		assertThat(type.supports("查询普通收藏夹")).isFalse();
		assertThat(type.allowedTools()).containsExactly("manage_bilibili_favorites");
		assertThat(type.autoStart("列出我的B站收藏夹")).isFalse();
	}
}
