package com.example.demo.ilink;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.ilink.sdk.core.context.ContextKey;
import com.github.wechat.ilink.sdk.core.context.ConversationContext;
import com.github.wechat.ilink.sdk.core.context.ResumeContext;
import com.github.wechat.ilink.sdk.core.login.LoginContext;

@Component
public class IlinkSessionStore {

	private static final Logger log = LoggerFactory.getLogger(IlinkSessionStore.class);

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final Path path;

	public IlinkSessionStore(
			@Value("${assistant.ilink.credentials-file:${user.home}/.demo-ilink/credentials.json}") String path) {
		this.path = Path.of(path).toAbsolutePath().normalize();
	}

	public ResumeContext load() {
		if (!Files.isRegularFile(path)) {
			return null;
		}
		try {
			JsonNode root = objectMapper.readTree(Files.readString(path, StandardCharsets.UTF_8));
			if (root.hasNonNull("botToken")) {
				return migrateLegacySession(root);
			}
			StoredSession stored = objectMapper.treeToValue(root, StoredSession.class);
			return toResumeContext(stored);
		} catch (Exception exception) {
			log.warn("无法读取本地 iLink 会话，将要求重新扫码：{}", exception.getMessage());
			return null;
		}
	}

	public synchronized void save(ResumeContext resumeContext) {
		if (resumeContext == null || resumeContext.getLoginContext() == null) {
			return;
		}
		try {
			Files.createDirectories(path.getParent());
			Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
			String json = objectMapper.writerWithDefaultPrettyPrinter()
					.writeValueAsString(fromResumeContext(resumeContext));
			Files.writeString(temporary, json, StandardCharsets.UTF_8);
			try {
				Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE,
						StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException exception) {
				Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException exception) {
			throw new IllegalStateException("无法保存 iLink 会话到 " + path, exception);
		}
	}

	public Path path() {
		return path;
	}

	private ResumeContext migrateLegacySession(JsonNode root) {
		LoginContext login = new LoginContext(
				text(root, "botToken"),
				text(root, "userId"),
				text(root, "botId"),
				text(root, "baseUrl"));
		log.info("已将原有 iLink 登录凭据迁移到 SDK 会话格式");
		return ResumeContext.of(login);
	}

	private ResumeContext toResumeContext(StoredSession stored) {
		if (stored == null || stored.login() == null || blank(stored.login().botToken())) {
			return null;
		}
		StoredLogin value = stored.login();
		LoginContext login = new LoginContext(
				value.botToken(), value.userId(), value.botId(), value.baseUrl());
		Map<String, ConversationContext> contexts = new HashMap<>();
		if (stored.conversations() != null) {
			for (StoredConversation saved : stored.conversations()) {
				if (saved == null || blank(saved.userId())) {
					continue;
				}
				ConversationContext context = new ConversationContext(
						new ContextKey(login.getBotId(), saved.userId()));
				context.setLatestContextToken(saved.contextToken());
				context.setTypingTicket(saved.typingTicket());
				contexts.put(saved.userId(), context);
			}
		}
		return ResumeContext.builder(login)
				.updatesCursor(stored.updatesCursor())
				.conversationContexts(contexts)
				.build();
	}

	private StoredSession fromResumeContext(ResumeContext resumeContext) {
		LoginContext login = resumeContext.getLoginContext();
		List<StoredConversation> conversations = new ArrayList<>();
		for (Map.Entry<String, ConversationContext> entry
				: resumeContext.getConversationContextMap().entrySet()) {
			ConversationContext context = entry.getValue();
			if (context != null) {
				conversations.add(new StoredConversation(
						entry.getKey(), context.getLatestContextToken(), context.getTypingTicket()));
			}
		}
		return new StoredSession(
				1,
				new StoredLogin(login.getBotToken(), login.getUserId(), login.getBotId(), login.getBaseUrl()),
				resumeContext.getUpdatesCursor(),
				conversations);
	}

	private static String text(JsonNode root, String name) {
		JsonNode value = root.get(name);
		return value == null || value.isNull() ? null : value.asText();
	}

	private static boolean blank(String value) {
		return value == null || value.isBlank();
	}

	public record StoredSession(int version, StoredLogin login, String updatesCursor,
			List<StoredConversation> conversations) {
	}

	public record StoredLogin(String botToken, String userId, String botId, String baseUrl) {
	}

	public record StoredConversation(String userId, String contextToken, String typingTicket) {
	}
}
