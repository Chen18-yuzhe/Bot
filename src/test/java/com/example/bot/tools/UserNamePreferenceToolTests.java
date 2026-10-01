package com.example.bot.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.example.bot.ilink.UserNameService;
import com.fasterxml.jackson.databind.ObjectMapper;

class UserNamePreferenceToolTests {
	@TempDir
	Path temporaryDirectory;

	@Test
	void modelToolCanSetUpdateAndClearPermanentProfile() throws Exception {
		ObjectMapper objectMapper = new ObjectMapper();
		UserNameService names = new UserNameService(objectMapper,
				temporaryDirectory.resolve("credentials.json").toString());
		UserProfileMemoryTool tool = new UserProfileMemoryTool(names);
		ToolContext context = new ToolContext("bot-a::user-1", "我有高血压，以后饮食建议注意一下");

		ToolResult saved = tool.execute(
				objectMapper.readTree("{\"action\":\"set\",\"category\":\"health\",\"key\":\"高血压\",\"value\":\"用户明确自述有高血压\"}"), context);
		assertThat(saved.content()).contains("永久保存");
		assertThat(names.memories(context.sessionId())).containsKey("health.高血压");

		tool.execute(objectMapper.readTree("{\"action\":\"clear\",\"category\":\"health\",\"key\":\"高血压\"}"), context);
		assertThat(names.memories(context.sessionId())).isEmpty();
	}

	@Test
	void modelToolRejectsMissingNameAndUnknownAction() throws Exception {
		ObjectMapper objectMapper = new ObjectMapper();
		UserProfileMemoryTool tool = new UserProfileMemoryTool(
				new UserNameService(objectMapper,
						temporaryDirectory.resolve("credentials.json").toString()));
		ToolContext context = new ToolContext("user-1", "test");

		assertThatThrownBy(() -> tool.execute(
				objectMapper.readTree("{\"action\":\"set\",\"category\":\"health\",\"key\":\"高血压\"}"), context))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> tool.execute(
				objectMapper.readTree("{\"action\":\"set\",\"category\":\"secret\",\"key\":\"密码\",\"value\":\"123\"}"), context))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
