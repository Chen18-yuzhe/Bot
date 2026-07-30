package com.example.demo.skills;

import java.util.List;

import com.example.demo.chat.ConversationTurn;
import com.example.demo.chat.MediaContent;

/** Immutable input shared by all skills for one incoming message. */
public record SkillContext(String sessionId, String question, List<ConversationTurn> history,
		List<MediaContent> media) {
	public SkillContext {
		history = history == null ? List.of() : List.copyOf(history);
		media = media == null ? List.of() : List.copyOf(media);
	}
}
