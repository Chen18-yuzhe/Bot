package com.example.bot.ilink;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Permanent, account-scoped user names used to address every iLink reply.
 *
 * <p>The iLink message API exposes a peer user id but no nickname. Operators
 * can therefore set a name in the console, or users can set their own name in
 * chat. The JSON file lives beside the durable iLink credentials.</p>
 */
@Component
public class UserNameService {
	private static final int MAX_NAME_LENGTH = 32;
	private static final Pattern EXPLICIT_NAME = Pattern.compile(
			"^(?:以后|今后|从今以后)?(?:叫我|称呼我|请叫我|喊我)\\s*[：:，,]?\\s*(.+?)\\s*[。！!，,]?$",
			Pattern.CASE_INSENSITIVE);
	private static final Pattern SELF_INTRODUCE = Pattern.compile(
			"^(?:我的名字是|我叫)\\s*[：:，,]?\\s*(.+?)\\s*[。！!，,]?$",
			Pattern.CASE_INSENSITIVE);
	private final ObjectMapper objectMapper;
	private final Path path;
	private final Map<String, String> names = new LinkedHashMap<>();
	private final Map<String, LinkedHashMap<String, String>> memories = new LinkedHashMap<>();
	private static final int MAX_MEMORY_ITEMS = 32;
	private static final int MAX_MEMORY_KEY_LENGTH = 64;
	private static final int MAX_MEMORY_VALUE_LENGTH = 500;

	public UserNameService(ObjectMapper objectMapper,
			@Value("${assistant.ilink.credentials-file:${user.home}/.bot-ilink/credentials.json}")
			String credentialsFile) {
		this.objectMapper = objectMapper;
		Path credentials = Path.of(credentialsFile).toAbsolutePath().normalize();
		this.path = credentials.resolveSibling("user-profiles.json");
		load();
	}

	public synchronized String configuredName(String sessionId) {
		return names.getOrDefault(normalizeSessionId(sessionId), "");
	}

	/**
	 * Returns every account-scoped session with a persisted name or long-term
	 * profile, including sessions whose conversation history has expired.
	 */
	public synchronized List<String> profileSessionIds(String accountId) {
		String account = accountId == null || accountId.isBlank() ? "default" : accountId.trim();
		LinkedHashSet<String> sessions = new LinkedHashSet<>();
		names.keySet().stream().filter(sessionId -> belongsToAccount(sessionId, account)).forEach(sessions::add);
		memories.keySet().stream().filter(sessionId -> belongsToAccount(sessionId, account)).forEach(sessions::add);
		return List.copyOf(sessions);
	}

	public synchronized String replyName(String sessionId) {
		String configured = configuredName(sessionId);
		if (!configured.isBlank()) return configured;
		String userId = userId(sessionId);
		String compact = userId.replaceAll("[^\\p{L}\\p{N}]", "");
		if (compact.isBlank()) return "朋友";
		int start = Math.max(0, compact.length() - 4);
		return "用户" + compact.substring(start);
	}

	public synchronized String update(String sessionId, String displayName) {
		String key = normalizeSessionId(sessionId);
		String value = normalizeName(displayName);
		if (value.isBlank()) names.remove(key);
		else names.put(key, value);
		save();
		return value;
	}

	public synchronized Map<String, String> memories(String sessionId) {
		Map<String, String> stored = memories.get(normalizeSessionId(sessionId));
		return stored == null ? Map.of() : Map.copyOf(stored);
	}

	public synchronized String updateMemory(String sessionId, String key, String value) {
		String session = normalizeSessionId(sessionId);
		String normalizedKey = normalizeMemoryKey(key);
		String normalizedValue = normalizeMemoryValue(value);
		LinkedHashMap<String, String> stored = memories.computeIfAbsent(session, ignored -> new LinkedHashMap<>());
		if (normalizedValue.isBlank()) {
			stored.remove(normalizedKey);
			if (stored.isEmpty()) memories.remove(session);
		} else {
			if (!stored.containsKey(normalizedKey) && stored.size() >= MAX_MEMORY_ITEMS) {
				throw new IllegalArgumentException("每个用户最多保存 " + MAX_MEMORY_ITEMS + " 条长期记忆");
			}
			stored.put(normalizedKey, normalizedValue);
		}
		save();
		return normalizedValue;
	}

	/** Safe model context; only explicitly persisted facts are returned. */
	public synchronized String modelContext(String sessionId) {
		Map<String, String> stored = memories(sessionId);
		String name = configuredName(sessionId);
		if (name.isBlank() && stored.isEmpty()) return "";
		StringBuilder context = new StringBuilder("当前用户的长期资料（仅在与本次请求相关时使用，不要机械复述）：\n");
		if (!name.isBlank()) context.append("- 称呼：").append(name).append('\n');
		stored.forEach((key, value) -> context.append("- ").append(key).append("：").append(value).append('\n'));
		return context.toString().trim();
	}

	public synchronized int deleteByAccount(String accountId) {
		String account = accountId == null || accountId.isBlank() ? "default" : accountId.trim();
		int before = names.size();
		if ("default".equals(account)) {
			names.keySet().removeIf(key -> !key.contains("::"));
		} else {
			names.keySet().removeIf(key -> key.startsWith(account + "::"));
		}
		int memoryBefore = memories.size();
		if ("default".equals(account)) {
			memories.keySet().removeIf(key -> !key.contains("::"));
		} else {
			memories.keySet().removeIf(key -> key.startsWith(account + "::"));
		}
		int deleted = before - names.size() + memoryBefore - memories.size();
		if (deleted > 0) save();
		return deleted;
	}

	/**
	 * Explicit command fallback. Natural-language preferences are intentionally
	 * left to the conversation model and its remember_user_profile tool.
	 */
	public synchronized String handleSelfServiceCommand(String sessionId, String input) {
		String command = input == null ? "" : input.trim();
		if (command.equalsIgnoreCase("/name") || command.equals("我的名字")
				|| command.equals("我的称呼")) {
			String current = configuredName(sessionId);
			return current.isBlank()
					? "当前还没有设置永久称呼。你可以直接用自然语言告诉我希望怎么称呼你。"
					: "当前永久称呼是“" + current + "”。";
		}
		if (command.matches("(?i)^/name\\s+(?:clear|reset|清除|重置)$")) {
			update(sessionId, "");
			return "永久称呼已清除。";
		}
		Matcher matcher = EXPLICIT_NAME.matcher(command);
		if (!matcher.matches()) matcher = SELF_INTRODUCE.matcher(command);
		if (matcher.matches()) {
			String saved = update(sessionId, matcher.group(1));
			return "好的，已将“" + saved + "”保存为永久称呼。";
		}
		return null;
	}

	public String decorateReply(String sessionId, String reply) {
		String name = replyName(sessionId);
		String value = reply == null ? "" : reply.trim();
		if (value.startsWith(name + "，") || value.startsWith(name + ",")
				|| value.startsWith(name + "：") || value.startsWith(name + ":")) {
			return value;
		}
		return name + "，" + value;
	}

	private void load() {
		if (!Files.isRegularFile(path)) return;
		try {
			Profiles profiles = objectMapper.readValue(path.toFile(), Profiles.class);
			if (profiles.names() != null) profiles.names().forEach((sessionId, value) -> {
				try {
					String key = normalizeSessionId(sessionId);
					String name = normalizeName(value);
					if (!name.isBlank()) names.put(key, name);
				} catch (IllegalArgumentException ignored) {
					// Ignore malformed legacy entries without blocking startup.
				}
			});
			if (profiles.memories() != null) profiles.memories().forEach((sessionId, entries) -> {
				if (entries == null) return;
				try {
					String session = normalizeSessionId(sessionId);
					LinkedHashMap<String, String> valid = new LinkedHashMap<>();
					entries.forEach((key, value) -> {
						try {
							String normalizedValue = normalizeMemoryValue(value);
							if (!normalizedValue.isBlank() && valid.size() < MAX_MEMORY_ITEMS) {
								valid.put(normalizeMemoryKey(key), normalizedValue);
							}
						} catch (IllegalArgumentException ignored) { }
					});
					if (!valid.isEmpty()) memories.put(session, valid);
				} catch (IllegalArgumentException ignored) { }
			});
		} catch (Exception error) {
			throw new IllegalStateException("无法读取用户永久称呼配置", error);
		}
	}

	private void save() {
		try {
			Files.createDirectories(path.getParent());
			Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
			Map<String, Map<String, String>> memoryCopy = new LinkedHashMap<>();
			memories.forEach((session, entries) -> memoryCopy.put(session, new LinkedHashMap<>(entries)));
			objectMapper.writeValue(temporary.toFile(), new Profiles(new LinkedHashMap<>(names), memoryCopy));
			try {
				Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE,
						StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException ignored) {
				Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (Exception error) {
			throw new IllegalStateException("无法保存用户永久称呼配置", error);
		}
	}

	private static String normalizeSessionId(String value) {
		String sessionId = value == null ? "" : value.trim();
		if (sessionId.isBlank() || sessionId.length() > 200
				|| !sessionId.matches("[^\\p{Cntrl}]+")) {
			throw new IllegalArgumentException("无效的用户会话 ID");
		}
		return sessionId;
	}

	private static String normalizeName(String value) {
		String name = value == null ? "" : value.trim().replaceAll("\\s+", " ");
		if (name.length() > MAX_NAME_LENGTH) {
			throw new IllegalArgumentException("用户名称不能超过 " + MAX_NAME_LENGTH + " 个字符");
		}
		if (name.matches(".*[，,：:。！？!?\\r\\n].*")) {
			throw new IllegalArgumentException("用户名称不能包含标点或换行");
		}
		return name;
	}

	private static String normalizeMemoryKey(String value) {
		String key = value == null ? "" : value.trim().replaceAll("\\s+", " ");
		if (key.isBlank() || key.length() > MAX_MEMORY_KEY_LENGTH || key.matches(".*[\\r\\n：:].*")) {
			throw new IllegalArgumentException("长期记忆的分类键无效");
		}
		return key;
	}

	private static String normalizeMemoryValue(String value) {
		String memory = value == null ? "" : value.trim().replaceAll("\\s+", " ");
		if (memory.length() > MAX_MEMORY_VALUE_LENGTH) {
			throw new IllegalArgumentException("单条长期记忆不能超过 " + MAX_MEMORY_VALUE_LENGTH + " 个字符");
		}
		return memory;
	}

	private static String userId(String sessionId) {
		String value = normalizeSessionId(sessionId);
		int marker = value.indexOf("::");
		return marker > 0 ? value.substring(marker + 2) : value;
	}

	private static boolean belongsToAccount(String sessionId, String accountId) {
		return "default".equals(accountId)
				? !sessionId.contains("::")
				: sessionId.startsWith(accountId + "::");
	}

	public Path path() { return path; }
	public record Profiles(Map<String, String> names, Map<String, Map<String, String>> memories) { }
}
