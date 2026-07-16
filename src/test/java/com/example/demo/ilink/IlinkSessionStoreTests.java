package com.example.demo.ilink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.wechat.ilink.sdk.core.context.ContextKey;
import com.github.wechat.ilink.sdk.core.context.ConversationContext;
import com.github.wechat.ilink.sdk.core.context.ResumeContext;
import com.github.wechat.ilink.sdk.core.login.LoginContext;

class IlinkSessionStoreTests {

	@TempDir
	Path temporaryDirectory;

	@Test
	void shouldMigrateLegacyCredentials() throws Exception {
		Path file = temporaryDirectory.resolve("credentials.json");
		Files.writeString(file, """
				{"botToken":"token","baseUrl":"https://example.test","botId":"bot","userId":"user"}
				""");

		ResumeContext restored = new IlinkSessionStore(file.toString()).load();

		assertNotNull(restored);
		assertEquals("token", restored.getLoginContext().getBotToken());
		assertEquals("bot", restored.getLoginContext().getBotId());
	}

	@Test
	void shouldPersistCursorAndConversationContext() {
		Path file = temporaryDirectory.resolve("credentials.json");
		IlinkSessionStore store = new IlinkSessionStore(file.toString());
		LoginContext login = new LoginContext("token", "owner", "bot", "https://example.test");
		ConversationContext conversation = new ConversationContext(new ContextKey("bot", "friend"));
		conversation.setLatestContextToken("context-token");
		conversation.setTypingTicket("typing-ticket");
		ResumeContext resume = ResumeContext.builder(login)
				.updatesCursor("cursor")
				.conversationContexts(Map.of("friend", conversation))
				.build();

		store.save(resume);
		ResumeContext restored = store.load();

		assertEquals("cursor", restored.getUpdatesCursor());
		assertEquals("context-token",
				restored.getConversationContextMap().get("friend").getLatestContextToken());
		assertEquals("typing-ticket",
				restored.getConversationContextMap().get("friend").getTypingTicket());
	}
}
