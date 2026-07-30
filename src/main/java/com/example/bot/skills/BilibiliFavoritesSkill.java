package com.example.bot.skills;

import java.util.Locale;

import org.springframework.stereotype.Component;

import com.example.bot.bilibili.BilibiliFavoritesService;

/**
 * Adds matching videos from an allowlisted user's already-synchronized
 * Bilibili collection without performing network synchronization in chat.
 */
@Component
public class BilibiliFavoritesSkill implements Skill {
	private final BilibiliFavoritesService favorites;

	public BilibiliFavoritesSkill(BilibiliFavoritesService favorites) {
		this.favorites = favorites;
	}

	@Override public String id() { return "bilibili-favorites"; }
	@Override public int order() { return 90; }

	@Override
	public boolean supports(SkillContext context) {
		if (!favorites.authorized(context.sessionId()) || !favorites.authenticated()
				|| context.question() == null) return false;
		String value = context.question().toLowerCase(Locale.ROOT);
		return value.contains("b站") || value.contains("bilibili") || value.contains("收藏夹")
				|| value.contains("视频讲解") || value.contains("视频字幕");
	}

	@Override
	public SkillContribution apply(SkillContext context) {
		var videos = favorites.listVideos(context.question(), 3);
		if (videos.isEmpty()) return SkillContribution.empty(id());
		StringBuilder result = new StringBuilder("""
				[Bilibili 收藏夹召回]
				以下结果来自当前获准用户已经同步到本地的私人收藏夹。若内容与问题相关，请告诉用户：
				“您的收藏夹中有对应的视频讲解”，并给出标题、BV号和 UP 主；不要声称已重新联网验证。
				""".trim());
		for (var video : videos) {
			result.append("\n\n- ").append(video.title()).append(" [").append(video.bvId()).append("]")
					.append("，UP主：").append(video.upName());
			if (!video.subtitleExcerpt().isBlank()) {
				result.append("\n  字幕片段：").append(video.subtitleExcerpt());
			}
		}
		return new SkillContribution(id(), result.toString());
	}
}
