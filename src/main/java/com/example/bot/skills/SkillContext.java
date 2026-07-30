package com.example.bot.skills;

import java.util.List;

import com.example.bot.chat.ConversationTurn;
import com.example.bot.chat.MediaContent;

/** Immutable input shared by all skills for one incoming message. */
public record SkillContext(String sessionId, String question, List<ConversationTurn> history,
		List<MediaContent> media) {
	public SkillContext {
		history = history == null ? List.of() : List.copyOf(history);
		media = media == null ? List.of() : List.copyOf(media);
	}
}
