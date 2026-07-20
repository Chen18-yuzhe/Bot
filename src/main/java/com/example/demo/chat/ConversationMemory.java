package com.example.demo.chat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;

@Service
public class ConversationMemory {
	private static final Logger log = LoggerFactory.getLogger(ConversationMemory.class);
	private final Map<String, SessionMemory> sessions = new ConcurrentHashMap<>();
	private final int maxTurns;
	private final long ttlMillis;
	private final int maxSessions;
	private final int maxMessageChars;
	private final int summaryMaxChars;
	private final Path storageFile;
	private final ObjectMapper objectMapper;
	private final AtomicBoolean saveScheduled = new AtomicBoolean();
	private final ScheduledExecutorService persistenceExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread thread = new Thread(r, "conversation-store");
		thread.setDaemon(true);
		return thread;
	});

	@Autowired
	public ConversationMemory(@Value("${assistant.conversation.max-turns:8}") int maxTurns,
			@Value("${assistant.conversation.ttl-minutes:1440}") long ttlMinutes,
			@Value("${assistant.conversation.max-sessions:1000}") int maxSessions,
			@Value("${assistant.conversation.max-message-chars:4000}") int maxMessageChars,
			@Value("${assistant.conversation.summary-max-chars:6000}") int summaryMaxChars,
			@Value("${assistant.conversation.storage-file:}") String storageFile,
			ObjectMapper objectMapper) {
		this.maxTurns = Math.max(1, maxTurns);
		this.ttlMillis = Duration.ofMinutes(Math.max(1, ttlMinutes)).toMillis();
		this.maxSessions = Math.max(10, maxSessions);
		this.maxMessageChars = Math.max(200, maxMessageChars);
		this.summaryMaxChars = Math.max(500, summaryMaxChars);
		this.storageFile = storageFile == null || storageFile.isBlank() ? null : Path.of(storageFile).toAbsolutePath();
		this.objectMapper = objectMapper;
		load();
	}

	ConversationMemory(int maxTurns, long ttlMinutes) {
		this(maxTurns, ttlMinutes, 1000, 4000, 6000, "", new ObjectMapper());
	}

	public List<ConversationTurn> history(String sessionId) {
		if (sessionId == null || sessionId.isBlank()) return List.of();
		SessionMemory memory = sessions.get(sessionId);
		if (memory == null) return List.of();
		synchronized (memory) {
			if (expired(memory)) {
				sessions.remove(sessionId, memory);
				scheduleSave();
				return List.of();
			}
			memory.lastAccessAt = System.currentTimeMillis();
			List<ConversationTurn> result = new ArrayList<>();
			if (!memory.summary.isBlank()) {
				result.add(new ConversationTurn("Earlier conversation summary", memory.summary));
			}
			result.addAll(memory.turns);
			return result;
		}
	}

	public void remember(String sessionId, String question, String answer) {
		if (sessionId == null || sessionId.isBlank() || question == null || answer == null) return;
		SessionMemory memory = sessions.computeIfAbsent(sessionId, ignored -> new SessionMemory());
		synchronized (memory) {
			if (expired(memory)) memory.turns.clear();
			memory.turns.addLast(new ConversationTurn(limit(question), limit(answer)));
			while (memory.turns.size() > maxTurns) {
				ConversationTurn old = memory.turns.removeFirst();
				appendSummary(memory, old);
			}
			memory.lastAccessAt = System.currentTimeMillis();
		}
		prune();
		scheduleSave();
	}

	public void clear(String sessionId) {
		if (sessionId != null && sessions.remove(sessionId) != null) scheduleSave();
	}

	private String limit(String value) {
		return value.length() <= maxMessageChars ? value : value.substring(0, maxMessageChars);
	}

	private void appendSummary(SessionMemory memory, ConversationTurn turn) {
		String addition = "用户：" + turn.userMessage() + "\n助手：" + turn.assistantMessage() + "\n";
		String combined = memory.summary.isBlank() ? addition : memory.summary + addition;
		if (combined.length() > summaryMaxChars) {
			combined = combined.substring(combined.length() - summaryMaxChars);
			int boundary = combined.indexOf('\n');
			if (boundary >= 0 && boundary < combined.length() - 1) combined = combined.substring(boundary + 1);
		}
		memory.summary = combined;
	}

	private void prune() {
		long now = System.currentTimeMillis();
		sessions.entrySet().removeIf(entry -> now - entry.getValue().lastAccessAt > ttlMillis);
		int excess = sessions.size() - maxSessions;
		if (excess <= 0) return;
		sessions.entrySet().stream().sorted(Comparator.comparingLong(e -> e.getValue().lastAccessAt))
				.limit(excess).map(Map.Entry::getKey).toList().forEach(sessions::remove);
	}

	private boolean expired(SessionMemory memory) {
		return System.currentTimeMillis() - memory.lastAccessAt > ttlMillis;
	}

	private void scheduleSave() {
		if (storageFile == null || !saveScheduled.compareAndSet(false, true)) return;
		persistenceExecutor.schedule(() -> {
			try { save(); } finally { saveScheduled.set(false); }
		}, 1, TimeUnit.SECONDS);
	}

	private void load() {
		if (storageFile == null || !Files.isRegularFile(storageFile)) return;
		try {
			StoredState state = objectMapper.readValue(storageFile.toFile(), StoredState.class);
			long now = System.currentTimeMillis();
			for (StoredSession stored : state.sessions()) {
				if (now - stored.lastAccessAt() > ttlMillis) continue;
				SessionMemory memory = new SessionMemory();
				memory.summary = stored.summary() == null ? "" : stored.summary();
				stored.turns().stream().skip(Math.max(0, stored.turns().size() - maxTurns)).forEach(memory.turns::addLast);
				memory.lastAccessAt = stored.lastAccessAt();
				sessions.put(stored.sessionId(), memory);
			}
			prune();
			log.info("已恢复 {} 个持久化对话会话", sessions.size());
		} catch (Exception error) {
			log.warn("读取对话存储失败，将使用空会话：{}", error.getMessage());
		}
	}

	private void save() {
		if (storageFile == null) return;
		try {
			prune();
			List<StoredSession> stored = new ArrayList<>();
			for (Map.Entry<String, SessionMemory> entry : sessions.entrySet()) {
				SessionMemory memory = entry.getValue();
				synchronized (memory) {
					stored.add(new StoredSession(entry.getKey(), memory.lastAccessAt, memory.summary, new ArrayList<>(memory.turns)));
				}
			}
			Files.createDirectories(storageFile.getParent());
			Path temporary = storageFile.resolveSibling(storageFile.getFileName() + ".tmp");
			objectMapper.writeValue(temporary.toFile(), new StoredState(stored));
			Files.move(temporary, storageFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (Exception error) {
			log.warn("保存对话存储失败：{}", error.getMessage());
		}
	}

	@PreDestroy
	void close() {
		persistenceExecutor.shutdownNow();
		save();
	}

	private static final class SessionMemory {
		private final ArrayDeque<ConversationTurn> turns = new ArrayDeque<>();
		private String summary = "";
		private long lastAccessAt = System.currentTimeMillis();
	}

	private record StoredState(List<StoredSession> sessions) { }
	private record StoredSession(String sessionId, long lastAccessAt, String summary, List<ConversationTurn> turns) { }
}
