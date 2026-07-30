package com.example.bot.ilink;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Persists operator-defined account labels separately from iLink credentials. */
@Component
public class IlinkAccountProfileStore {
	private static final int MAX_DISPLAY_NAME_LENGTH = 40;
	private final ObjectMapper objectMapper = new ObjectMapper();
	private final Path path;
	private final Map<String, String> displayNames = new LinkedHashMap<>();

	public IlinkAccountProfileStore(
			@Value("${assistant.ilink.credentials-file:${user.home}/.bot-ilink/credentials.json}") String credentialsFile) {
		Path credentials = Path.of(credentialsFile).toAbsolutePath().normalize();
		this.path = credentials.resolveSibling("account-profiles.json");
		load();
	}

	public synchronized String displayName(String accountId) {
		return displayNames.getOrDefault(accountId, "");
	}

	public synchronized String updateDisplayName(String accountId, String displayName) {
		String value = normalize(displayName);
		if (value.isEmpty()) displayNames.remove(accountId);
		else displayNames.put(accountId, value);
		save();
		return value;
	}

	public synchronized void delete(String accountId) {
		if (displayNames.remove(accountId) != null) save();
	}

	private void load() {
		if (!Files.isRegularFile(path)) return;
		try {
			Profiles profiles = objectMapper.readValue(path.toFile(), Profiles.class);
			if (profiles.displayNames() != null) {
				profiles.displayNames().forEach((accountId, value) -> {
					if (validAccountId(accountId)) {
						String normalized = normalize(value);
						if (!normalized.isEmpty()) displayNames.put(accountId, normalized);
					}
				});
			}
		} catch (Exception error) {
			throw new IllegalStateException("无法读取机器人账号名称配置", error);
		}
	}

	private void save() {
		try {
			Files.createDirectories(path.getParent());
			Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
			objectMapper.writeValue(temporary.toFile(), new Profiles(new LinkedHashMap<>(displayNames)));
			try {
				Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException ignored) {
				Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (Exception error) {
			throw new IllegalStateException("无法保存机器人账号名称配置", error);
		}
	}

	private static String normalize(String value) {
		if (value == null) return "";
		String normalized = value.trim().replaceAll("\\s+", " ");
		if (normalized.length() > MAX_DISPLAY_NAME_LENGTH) {
			throw new IllegalArgumentException("账号名称不能超过 " + MAX_DISPLAY_NAME_LENGTH + " 个字符");
		}
		return normalized;
	}

	private static boolean validAccountId(String value) {
		return value != null && value.matches("[A-Za-z0-9_-]{1,80}");
	}

	public Path path() { return path; }
	public record Profiles(Map<String, String> displayNames) { }
}
