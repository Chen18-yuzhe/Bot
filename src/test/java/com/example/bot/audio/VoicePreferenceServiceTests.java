package com.example.bot.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import com.fasterxml.jackson.databind.ObjectMapper;

class VoicePreferenceServiceTests {
	@TempDir Path tempDir;

	@Test
	void shouldSelectAndPersistVoicePerUser() {
		Path file = tempDir.resolve("voices.json");
		VoicePreferenceService first = new VoicePreferenceService("longxiaochun_v2", file.toString(), new ObjectMapper());
		assertEquals("longxiaochun_v2", first.current("user-a").id());
		assertEquals("longxiaoxia_v2", first.select("user-a", "龙小夏").id());
		assertNull(first.select("user-a", "不存在"));

		VoicePreferenceService restored = new VoicePreferenceService("longxiaochun_v2", file.toString(), new ObjectMapper());
		assertEquals("longxiaoxia_v2", restored.current("user-a").id());
		assertEquals("longxiaochun_v2", restored.current("user-b").id());
	}
}
