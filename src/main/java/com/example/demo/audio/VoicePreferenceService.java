package com.example.demo.audio;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class VoicePreferenceService {
	private static final Logger log = LoggerFactory.getLogger(VoicePreferenceService.class);
	private static final Map<String, VoiceOption> VOICES = voices();
	private final Map<String, String> preferences = new java.util.concurrent.ConcurrentHashMap<>();
	private final String defaultVoice;
	private final Path storageFile;
	private final ObjectMapper objectMapper;

	public VoicePreferenceService(@Value("${aliyun.dashscope.audio.voice:longxiaochun_v2}") String defaultVoice,
			@Value("${assistant.voice-preferences.storage-file:/var/lib/demo-assistant/voice-preferences.json}") String storageFile,
			ObjectMapper objectMapper) {
		this.defaultVoice = VOICES.containsKey(defaultVoice) ? defaultVoice : "longxiaochun_v2";
		this.storageFile = Path.of(storageFile).toAbsolutePath();
		this.objectMapper = objectMapper;
		load();
	}

	public VoiceOption current(String userId) {
		return VOICES.getOrDefault(preferences.getOrDefault(userId, defaultVoice), VOICES.get("longxiaochun_v2"));
	}

	public VoiceOption select(String userId, String requested) {
		if (userId == null || userId.isBlank() || requested == null) return null;
		String normalized = requested.trim().toLowerCase(Locale.ROOT).replace(" ", "");
		VoiceOption selected = VOICES.values().stream()
				.filter(option -> option.id().equalsIgnoreCase(normalized) || option.name().equalsIgnoreCase(normalized))
				.findFirst().orElse(null);
		if (selected != null) {
			preferences.put(userId, selected.id());
			save();
		}
		return selected;
	}

	public String describeAll() {
		StringBuilder text = new StringBuilder("可用音色：\n");
		VOICES.values().forEach(option -> text.append(option.name()).append("（").append(option.id()).append("）\n"));
		text.append("发送“切换音色：龙小霞”即可切换。");
		return text.toString();
	}

	private void load() {
		if (!Files.isRegularFile(storageFile)) return;
		try {
			Map<String, String> loaded = objectMapper.readValue(storageFile.toFile(), new TypeReference<>() {});
			loaded.forEach((user, voice) -> { if (VOICES.containsKey(voice)) preferences.put(user, voice); });
		} catch (Exception error) {
			log.warn("读取音色偏好失败：{}", error.getMessage());
		}
	}

	private synchronized void save() {
		try {
			Files.createDirectories(storageFile.getParent());
			Path temporary = storageFile.resolveSibling(storageFile.getFileName() + ".tmp");
			objectMapper.writeValue(temporary.toFile(), preferences);
			Files.move(temporary, storageFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (Exception error) {
			log.warn("保存音色偏好失败：{}", error.getMessage());
		}
	}

	private static Map<String, VoiceOption> voices() {
		Map<String, VoiceOption> values = new LinkedHashMap<>();
		add(values, "longxiaochun_v2", "龙小淳");
		add(values, "longxiaoxia_v2", "龙小夏");
		add(values, "longyumi_v2", "YUMI");
		add(values, "longanlang", "龙安朗");
		add(values, "longanwen", "龙安雯");
		add(values, "longanyun", "龙安韵");
		add(values, "longyichen", "龙逸尘");
		return Map.copyOf(values);
	}

	private static void add(Map<String, VoiceOption> values, String id, String name) {
		values.put(id, new VoiceOption(id, name));
	}

	public record VoiceOption(String id, String name) { }
}
