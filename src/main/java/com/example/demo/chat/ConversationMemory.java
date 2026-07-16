package com.example.demo.chat;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ConversationMemory {

	private final Map<String, SessionMemory> sessions = new ConcurrentHashMap<>();
	private final int maxTurns;
	private final long ttlMillis;

	public ConversationMemory(
			@Value("${assistant.conversation.max-turns:8}") int maxTurns,
			@Value("${assistant.conversation.ttl-minutes:30}") long ttlMinutes) {
		this.maxTurns = Math.max(1, maxTurns);
		this.ttlMillis = Duration.ofMinutes(Math.max(1, ttlMinutes)).toMillis();
	}

	public List<ConversationTurn> history(String sessionId) {
		if (sessionId == null || sessionId.isBlank()) {
			return List.of();
		}
		SessionMemory memory = sessions.get(sessionId);
		if (memory == null) {
			return List.of();
		}
		synchronized (memory) {
			if (expired(memory)) {
				sessions.remove(sessionId, memory);
				return List.of();
			}
			memory.lastAccessAt = System.currentTimeMillis();
			return new ArrayList<>(memory.turns);
		}
	}

	public void remember(String sessionId, String question, String answer) {
		if (sessionId == null || sessionId.isBlank() || question == null || answer == null) {
			return;
		}
		SessionMemory memory = sessions.computeIfAbsent(sessionId, ignored -> new SessionMemory());
		synchronized (memory) {
			if (expired(memory)) {
				memory.turns.clear();
			}
			memory.turns.addLast(new ConversationTurn(question, answer));
			while (memory.turns.size() > maxTurns) {
				memory.turns.removeFirst();
			}
			memory.lastAccessAt = System.currentTimeMillis();
		}
	}

	public void clear(String sessionId) {
		if (sessionId != null) {
			sessions.remove(sessionId);
		}
	}

	private boolean expired(SessionMemory memory) {
		return System.currentTimeMillis() - memory.lastAccessAt > ttlMillis;
	}

	private static final class SessionMemory {
		private final ArrayDeque<ConversationTurn> turns = new ArrayDeque<>();
		private long lastAccessAt = System.currentTimeMillis();
	}
}
