package com.example.demo.tools;

import java.util.List;

import com.example.demo.chat.MediaContent;
import com.example.demo.chat.MediaContent.MediaType;

/** Resources available to one tool-call round. Attachment bytes never enter JSON arguments. */
public record ToolContext(String sessionId, String userQuestion, List<MediaContent> media) {
	public ToolContext {
		media = media == null ? List.of() : List.copyOf(media);
	}

	public ToolContext(String sessionId, String userQuestion) {
		this(sessionId, userQuestion, List.of());
	}

	public List<MediaContent> images() {
		return media.stream().filter(item -> item.type() == MediaType.IMAGE).toList();
	}

	public List<MediaContent> documents() {
		return media.stream().filter(item -> item.type() == MediaType.DOCUMENT).toList();
	}

	public List<MediaContent> audio() {
		return media.stream().filter(item -> item.type() == MediaType.AUDIO).toList();
	}
}
