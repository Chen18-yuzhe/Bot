package com.example.bot.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class BilibiliFavoritesTaskTypeTests {
	private final BilibiliFavoritesTaskType type = new BilibiliFavoritesTaskType();

	@Test
	void matchesPrivateFavoritesAndAllowsOnlyTheBilibiliTool() {
		assertThat(type.supports("列出我的B站收藏夹")).isTrue();
		assertThat(type.supports("查询普通收藏夹")).isFalse();
		assertThat(type.allowedTools()).containsExactly("manage_bilibili_favorites");
		assertThat(type.autoStart("列出我的B站收藏夹")).isTrue();
	}

	@Test
	void rejectsAnswersThatInventVideosOutsideTheToolResult() {
		AgentTask task = new AgentTask();
		task.setTranscript(List.of(AgentTranscript.Entry.tool(
				"call-1", "manage_bilibili_favorites",
				"[{\"bvId\":\"BV1REAL12345\",\"title\":\"真实收藏视频\"}]")));

		assertThat(type.validateCompletion(task, "1. BV1REAL12345 真实收藏视频")).isNull();
		assertThat(type.validateCompletion(task, "1. BV1FAKE12345 编造视频"))
				.contains("不存在的 BV 号");
	}
}
