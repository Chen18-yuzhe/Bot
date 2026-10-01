package com.example.bot.ilink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

class UserNameServiceTests {
	@TempDir
	Path temporaryDirectory;

	@Test
	void consoleNamePersistsAndDecoratesRepliesPerBotAndUser() {
		Path credentials = temporaryDirectory.resolve("credentials.json");
		UserNameService service = new UserNameService(new ObjectMapper(), credentials.toString());

		service.update("bot-a::user-1", "小陈");

		UserNameService restored = new UserNameService(new ObjectMapper(), credentials.toString());
		assertThat(restored.configuredName("bot-a::user-1")).isEqualTo("小陈");
		assertThat(restored.decorateReply("bot-a::user-1", "任务完成了。"))
				.isEqualTo("小陈，任务完成了。");
		assertThat(restored.configuredName("bot-b::user-1")).isBlank();
	}

	@Test
	void persistedProfilesRemainDiscoverableAfterConversationExpiry() {
		Path credentials = temporaryDirectory.resolve("credentials.json");
		UserNameService service = new UserNameService(new ObjectMapper(), credentials.toString());
		service.update("bot-a::user-1", "小陈");
		service.updateMemory("bot-a::user-2", "preference.称呼方式", "使用您");
		service.update("bot-b::user-1", "小王");

		UserNameService restored = new UserNameService(new ObjectMapper(), credentials.toString());
		assertThat(restored.profileSessionIds("bot-a"))
				.containsExactlyInAnyOrder("bot-a::user-1", "bot-a::user-2");
		assertThat(restored.modelContext("bot-a::user-2")).contains("使用您");
		assertThat(restored.profileSessionIds("bot-b")).isEqualTo(List.of("bot-b::user-1"));
	}

	@Test
	void explicitNaturalLanguageNameIsSavedBeforeReplyDecoration() {
		UserNameService service = service();

		assertThat(service.handleSelfServiceCommand("user-1", "以后叫我胖仔"))
				.contains("胖仔");
		assertThat(service.decorateReply("user-1", "好的，已保存。"))
				.isEqualTo("胖仔，好的，已保存。");
		assertThat(service.handleSelfServiceCommand("user-1", "/name"))
				.contains("胖仔");
		assertThat(service.handleSelfServiceCommand("user-1", "/name clear"))
				.contains("已清除");
		assertThat(service.configuredName("user-1")).isBlank();
	}

	@Test
	void unsetNameUsesMaskedUserIdentifierAndDecorationIsIdempotent() {
		UserNameService service = service();

		assertThat(service.replyName("bot-a::wxid_123456")).isEqualTo("用户3456");
		assertThat(service.decorateReply("bot-a::wxid_123456", "你好"))
				.isEqualTo("用户3456，你好");
		service.update("bot-a::wxid_123456", "小王");
		assertThat(service.decorateReply("bot-a::wxid_123456", "小王，已经完成。"))
				.isEqualTo("小王，已经完成。");
	}

	@Test
	void rejectsUnsafeOrOverlongNames() {
		UserNameService service = service();
		assertThatThrownBy(() -> service.update("user-1", "坏，名字"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.update("user-1", "a".repeat(33)))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private UserNameService service() {
		return new UserNameService(new ObjectMapper(),
				temporaryDirectory.resolve("credentials.json").toString());
	}
}
