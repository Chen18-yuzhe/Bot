package com.example.demo.ilink;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.example.demo.chat.AssistantService;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.ILinkClientBuilder;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.context.ResumeContext;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;

import jakarta.annotation.PreDestroy;

@Component
@ConditionalOnProperty(name = "assistant.ilink-enabled", havingValue = "true")
public class IlinkChatRunner {

	private static final Logger log = LoggerFactory.getLogger(IlinkChatRunner.class);
	private static final int MAX_SEEN_MESSAGE_IDS = 500;
	private static final String RESET_REPLY = "已清除本次对话记忆，我们可以重新开始。";

	private final AssistantService assistantService;
	private final IlinkSessionStore sessionStore;
	private final WechatCommandHandler commandHandler;
	private final long minReplyDelayMs;
	private final long maxReplyDelayMs;
	private final int maxReplyChars;
	private final long acceptMessagesAfterMs = System.currentTimeMillis() - 5_000;
	private final Map<Long, Boolean> seenMessageIds = new LinkedHashMap<>(128, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
			return size() > MAX_SEEN_MESSAGE_IDS;
		}
	};

	private volatile ILinkClient client;
	private volatile Thread worker;

	public IlinkChatRunner(AssistantService assistantService, IlinkSessionStore sessionStore,
			WechatCommandHandler commandHandler,
			@Value("${assistant.ilink.min-reply-delay-ms:13000}") long minReplyDelayMs,
			@Value("${assistant.ilink.max-reply-delay-ms:17000}") long maxReplyDelayMs,
			@Value("${assistant.ilink.max-reply-chars:1800}") int maxReplyChars) {
		this.assistantService = assistantService;
		this.sessionStore = sessionStore;
		this.commandHandler = commandHandler;
		this.minReplyDelayMs = Math.max(0, minReplyDelayMs);
		this.maxReplyDelayMs = Math.max(this.minReplyDelayMs, maxReplyDelayMs);
		this.maxReplyChars = Math.max(100, maxReplyChars);
	}

	@EventListener(ApplicationReadyEvent.class)
	public void start() {
		worker = Thread.ofPlatform().name("ilink-chat").start(this::runSafely);
	}

	@PreDestroy
	public void stop() {
		Thread currentWorker = worker;
		if (currentWorker != null) {
			currentWorker.interrupt();
		}
		closeClient();
	}

	private void runSafely() {
		try {
			runLoop();
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			log.info("iLink 监听线程已停止");
		} catch (Exception exception) {
			log.error("iLink SDK 客户端已停止：{}", exception.getMessage(), exception);
		} finally {
			closeClient();
		}
	}

	private void runLoop() throws Exception {
		ResumeContext savedSession = sessionStore.load();
		ILinkClientBuilder builder = ILinkClient.builder().config(safeConfig());
		if (savedSession != null) {
			builder.resumeContext(savedSession);
		}
		client = builder.build();

		if (!client.isLoggedIn()) {
			String qrCodeContent = client.executeLogin();
			System.out.println();
			System.out.println("========== iLink SDK 微信个人测试登录 ==========");
			System.out.println("请使用手机微信扫描下面的二维码内容：");
			System.out.println(qrCodeContent);
			System.out.println("扫码确认后程序会自动开始监听消息。二维码有效期约 5 分钟。\n");
			client.getLoginFuture().get(6, TimeUnit.MINUTES);
			saveSession();
			log.info("iLink SDK 登录成功，会话已安全保存到：{}", sessionStore.path());
		} else {
			log.info("iLink SDK 已恢复本地登录会话");
		}

		int consecutiveFailures = 0;
		while (!Thread.currentThread().isInterrupted()) {
			try {
				List<WeixinMessage> messages = client.getUpdates();
				consecutiveFailures = 0;
				saveSession();
				processMessages(messages);
			} catch (IOException | RuntimeException exception) {
				consecutiveFailures++;
				long backoffMs = Math.min(60_000L, 5_000L * (1L << Math.min(3, consecutiveFailures - 1)));
				log.warn("iLink 拉取消息失败，第 {} 次，{} 毫秒后重试：{}",
						consecutiveFailures, backoffMs, exception.getMessage());
				sleep(backoffMs);
			}
		}
	}

	private ILinkConfig safeConfig() {
		return ILinkConfig.builder()
				.connectTimeoutMs(15_000)
				.readTimeoutMs(45_000)
				.writeTimeoutMs(15_000)
				.httpMaxRetries(2)
				.retryBaseDelayMs(1_500)
				.retryMaxDelayMs(8_000)
				.retryJitterEnabled(true)
				.heartbeatEnabled(false)
				.autoReconnectEnabled(false)
				.channelVersion("1.0.0")
				.build();
	}

	private void processMessages(List<WeixinMessage> messages) throws InterruptedException {
		if (messages == null || messages.isEmpty()) {
			return;
		}
		Map<String, StringBuilder> questionsByUser = new LinkedHashMap<>();
		for (WeixinMessage message : messages) {
			if (!isNewUserMessage(message)) {
				continue;
			}
			String text = extractText(message);
			if (text == null || text.isBlank()) {
				continue;
			}
			questionsByUser.computeIfAbsent(message.getFrom_user_id(), ignored -> new StringBuilder())
					.append(text.trim()).append('\n');
		}

		for (Map.Entry<String, StringBuilder> entry : questionsByUser.entrySet()) {
			String question = entry.getValue().toString().trim();
			if (!question.isEmpty()) {
				handleQuestion(entry.getKey(), question);
				saveSession();
			}
		}
	}

	private boolean isNewUserMessage(WeixinMessage message) {
		if (message == null || !Integer.valueOf(1).equals(message.getMessage_type())
				|| message.getFrom_user_id() == null || message.getFrom_user_id().isBlank()) {
			return false;
		}
		if (message.getCreate_time_ms() != null && message.getCreate_time_ms() < acceptMessagesAfterMs) {
			log.info("跳过程序启动前的积压 iLink 消息，避免重复回复");
			return false;
		}
		Long messageId = message.getMessage_id();
		if (messageId == null) {
			return true;
		}
		if (seenMessageIds.containsKey(messageId)) {
			return false;
		}
		seenMessageIds.put(messageId, Boolean.TRUE);
		return true;
	}

	private void handleQuestion(String userId, String question) throws InterruptedException {
		long receivedAt = System.currentTimeMillis();
		long targetDelayMs = randomDelayMs();
		log.info("收到 iLink 消息：{}", question);

		boolean typingStarted = startTyping(userId);
		try {
			String answer;
			String commandReply = commandHandler.handle(question);
			if (commandReply != null) {
				answer = commandReply;
			} else if (isResetCommand(question)) {
				assistantService.clearConversation(userId);
				answer = RESET_REPLY;
			} else {
				answer = assistantService.chat(userId, question);
			}
			answer = commandHandler.addWelcomeForFirstContact(userId, answer);
			answer = safeReply(answer);
			long remainingDelay = receivedAt + targetDelayMs - System.currentTimeMillis();
			if (remainingDelay > 0) {
				sleep(remainingDelay);
			}
			log.info("准备通过 iLink SDK 发送回复，字数={}，等待={}毫秒", answer.length(), targetDelayMs);
			client.sendText(userId, answer);
			log.info("iLink SDK 回复发送成功");
		} catch (IOException | RuntimeException exception) {
			log.error("iLink 消息处理或回复失败，本条消息已跳过，监听继续：{}",
					exception.getMessage(), exception);
		} finally {
			if (typingStarted) {
				stopTyping(userId);
			}
		}
	}

	private boolean startTyping(String userId) {
		try {
			client.startTyping(userId);
			log.info("已向微信发送“正在输入”状态");
			return true;
		} catch (IOException | RuntimeException exception) {
			log.warn("无法发送“正在输入”状态，将继续生成回答：{}", exception.getMessage());
			return false;
		}
	}

	private void stopTyping(String userId) {
		try {
			client.stopTyping(userId);
		} catch (IOException | RuntimeException exception) {
			log.debug("停止输入状态失败：{}", exception.getMessage());
		}
	}

	private String safeReply(String answer) {
		String value = answer == null || answer.isBlank() ? "抱歉，我暂时无法生成回答，请稍后再试。" : answer.trim();
		if (value.length() <= maxReplyChars) {
			return value;
		}
		return value.substring(0, maxReplyChars - 8) + "……（已截断）";
	}

	private long randomDelayMs() {
		if (minReplyDelayMs == maxReplyDelayMs) {
			return minReplyDelayMs;
		}
		return ThreadLocalRandom.current().nextLong(minReplyDelayMs, maxReplyDelayMs + 1);
	}

	private void saveSession() {
		ILinkClient current = client;
		if (current != null && current.isLoggedIn()) {
			sessionStore.save(current.exportResumeContext());
		}
	}

	private void closeClient() {
		ILinkClient current = client;
		if (current == null) {
			return;
		}
		client = null;
		try {
			if (current.isLoggedIn()) {
				sessionStore.save(current.exportResumeContext());
			}
		} catch (RuntimeException exception) {
			log.warn("关闭前保存 iLink 会话失败：{}", exception.getMessage());
		} finally {
			current.close();
		}
	}

	private static String extractText(WeixinMessage message) {
		if (message.getItem_list() == null) {
			return null;
		}
		return message.getItem_list().stream()
				.filter(item -> item != null && item.getText_item() != null)
				.map(MessageItem::getText_item)
				.map(textItem -> textItem.getText())
				.filter(text -> text != null && !text.isBlank())
				.findFirst()
				.orElse(null);
	}

	private static boolean isResetCommand(String question) {
		String value = question.trim();
		return "/reset".equalsIgnoreCase(value) || "清除对话".equals(value) || "重新开始".equals(value);
	}

	private static void sleep(long millis) throws InterruptedException {
		TimeUnit.MILLISECONDS.sleep(Math.max(0, millis));
	}
}
