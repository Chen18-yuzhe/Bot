package com.example.bot.ilink;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.UUID;
import java.util.ArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.example.bot.agent.AgentModeHandler;
import com.example.bot.chat.AssistantService;
import com.example.bot.chat.AssistantReply;
import com.example.bot.chat.ImageReplyPolicy;
import com.example.bot.chat.MediaContent;
import com.example.bot.chat.MediaContent.MediaType;
import com.example.bot.audio.GeneratedAudio;
import com.example.bot.audio.VoicePreferenceService;
import com.example.bot.ApplicationException;
import com.example.bot.document.GeneratedDocument;
import com.example.bot.document.DocumentReplyPolicy;
import com.example.bot.reminder.ReminderService;
import com.example.bot.guard.UserRateLimiter;
import com.example.bot.logging.RequestIds;
import com.example.bot.monitoring.ConversationAuditService;
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
	private static final String SESSION_SEPARATOR = "::";
	private static final String RESET_REPLY = "已清除本次对话记忆，我们可以重新开始。";
	private final AssistantService assistantService;
	private final IlinkSessionStore sessionStore;
	private final WechatCommandHandler commandHandler;
	private final AgentModeHandler agentModeHandler;
	private final ReminderService reminderService;
	private final VoicePreferenceService voicePreferenceService;
	private final UserRateLimiter rateLimiter;
	private final ConversationAuditService auditService;
	private final long minReplyDelayMs;
	private final long maxReplyDelayMs;
	private final int maxReplyChars;
	private final long maxMediaBytes;
	private final long mediaBatchWindowMs;
	private final long recentMediaTtlMs;
	private final long recentDocumentTtlMs;
	private final int maxAccounts;
	private final ScheduledExecutorService batchExecutor = Executors.newScheduledThreadPool(4, runnable -> {
		Thread thread = new Thread(runnable, "ilink-media-batch");
		thread.setDaemon(true);
		return thread;
	});
	private final Map<String, PendingBatch> pendingBatches = new ConcurrentHashMap<>();
	private final Map<String, RecentMedia> recentMediaByUser = new ConcurrentHashMap<>();
	private final Map<String, RecentMedia> recentDocumentsByUser = new ConcurrentHashMap<>();
	private final Map<String, Long> awaitingImageInstructionByUser = new ConcurrentHashMap<>();
	private final Map<String, Long> awaitingDocumentInstructionByUser = new ConcurrentHashMap<>();
	private final Map<String, AtomicLong> userMessageVersions = new ConcurrentHashMap<>();
	private final long acceptMessagesAfterMs = System.currentTimeMillis() - 5_000;
	private final Map<String, Boolean> seenMessageIds = new LinkedHashMap<>(128, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
			return size() > MAX_SEEN_MESSAGE_IDS;
		}
	};

	private final Map<String, AccountRuntime> accounts = new ConcurrentHashMap<>();

	public IlinkChatRunner(AssistantService assistantService, IlinkSessionStore sessionStore,
			WechatCommandHandler commandHandler, AgentModeHandler agentModeHandler,
			ReminderService reminderService,
			VoicePreferenceService voicePreferenceService,
			UserRateLimiter rateLimiter, ConversationAuditService auditService,
			@Value("${assistant.ilink.min-reply-delay-ms:13000}") long minReplyDelayMs,
			@Value("${assistant.ilink.max-reply-delay-ms:17000}") long maxReplyDelayMs,
			@Value("${assistant.ilink.max-reply-chars:1800}") int maxReplyChars,
			@Value("${assistant.ilink.max-media-bytes:104857600}") long maxMediaBytes,
			@Value("${assistant.ilink.batch-window-ms:3000}") long mediaBatchWindowMs,
			@Value("${assistant.ilink.recent-media-ttl-seconds:600}") long recentMediaTtlSeconds,
			@Value("${assistant.ilink.recent-document-ttl-seconds:600}") long recentDocumentTtlSeconds,
			@Value("${assistant.ilink.max-accounts:3}") int maxAccounts) {
		this.assistantService = assistantService;
		this.sessionStore = sessionStore;
		this.commandHandler = commandHandler;
		this.agentModeHandler = agentModeHandler;
		this.reminderService = reminderService;
		this.voicePreferenceService = voicePreferenceService;
		this.rateLimiter = rateLimiter;
		this.auditService = auditService;
		this.minReplyDelayMs = Math.max(0, minReplyDelayMs);
		this.maxReplyDelayMs = Math.max(this.minReplyDelayMs, maxReplyDelayMs);
		this.maxReplyChars = Math.max(100, maxReplyChars);
		this.maxMediaBytes = Math.max(1_048_576, maxMediaBytes);
		this.mediaBatchWindowMs = Math.max(1_000, Math.min(20_000, mediaBatchWindowMs));
		this.recentMediaTtlMs = TimeUnit.SECONDS.toMillis(Math.max(10, Math.min(600, recentMediaTtlSeconds)));
		this.recentDocumentTtlMs = TimeUnit.SECONDS.toMillis(
				Math.max(30, Math.min(3_600, recentDocumentTtlSeconds)));
		this.maxAccounts = Math.max(1, Math.min(20, maxAccounts));
	}

	@EventListener(ApplicationReadyEvent.class)
	public void start() {
		reminderService.setNotifier(this::deliverReminder);
		agentModeHandler.setCompletionNotifier((sessionId, text) -> pushText(sessionId, text));
		Map<String, ResumeContext> saved = sessionStore.loadAll();
		if (saved.isEmpty()) {
			startAccount("default", null, true);
		} else {
			saved.forEach((accountId, context) -> startAccount(accountId, context, false));
		}
	}

	@PreDestroy
	public void stop() {
		reminderService.setNotifier(null);
		batchExecutor.shutdownNow();
		new ArrayList<>(accounts.values()).forEach(this::closeAccount);
	}

	private void startAccount(String accountId, ResumeContext resumeContext, boolean printQr) {
		AccountRuntime runtime = new AccountRuntime(accountId, resumeContext, printQr);
		AccountRuntime previous = accounts.putIfAbsent(accountId, runtime);
		if (previous != null) {
			throw new IllegalStateException("iLink 账户已存在：" + accountId);
		}
		runtime.worker = Thread.ofPlatform().name("ilink-" + accountId)
				.start(() -> runSafely(runtime));
	}

	private void runSafely(AccountRuntime runtime) {
		try {
			runLoop(runtime);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			log.info("iLink 监听线程已停止：账户={}", runtime.accountId);
		} catch (Exception exception) {
			runtime.status = AccountStatus.ERROR;
			runtime.error = exception.getMessage();
			log.error("iLink SDK 客户端已停止：账户={}，原因={}",
					runtime.accountId, exception.getMessage(), exception);
		} finally {
			closeAccount(runtime);
		}
	}

	private void runLoop(AccountRuntime runtime) throws Exception {
		ResumeContext savedSession = runtime.resumeContext;
		ILinkClientBuilder builder = ILinkClient.builder().config(safeConfig());
		if (savedSession != null) {
			builder.resumeContext(savedSession);
		}
		ILinkClient client = builder.build();
		runtime.client = client;

		if (!client.isLoggedIn()) {
			runtime.status = AccountStatus.WAITING_FOR_SCAN;
			String qrCodeContent = client.executeLogin();
			runtime.qrCode = qrCodeContent;
			if (runtime.printQr) {
				System.out.println();
				System.out.println("========== iLink SDK 微信个人测试登录 ==========");
				System.out.println("请使用手机微信扫描下面的二维码内容：");
				System.out.println(qrCodeContent);
				System.out.println("扫码确认后程序会自动开始监听消息。二维码有效期约 5 分钟。\n");
			}
			client.getLoginFuture().get(6, TimeUnit.MINUTES);
			runtime.qrCode = null;
			runtime.status = AccountStatus.ONLINE;
			saveSession(runtime);
			log.info("iLink SDK 登录成功：账户={}，会话文件={}",
					runtime.accountId, sessionStore.accountPath(runtime.accountId));
		} else {
			runtime.status = AccountStatus.ONLINE;
			log.info("iLink SDK 已恢复本地登录会话：账户={}", runtime.accountId);
		}

		int consecutiveFailures = 0;
		while (!Thread.currentThread().isInterrupted()) {
			try {
				List<WeixinMessage> messages = client.getUpdates();
				consecutiveFailures = 0;
				saveSession(runtime);
				processMessages(runtime, messages);
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

	private void processMessages(AccountRuntime runtime, List<WeixinMessage> messages) throws InterruptedException {
		if (messages == null || messages.isEmpty()) {
			return;
		}
		for (WeixinMessage message : messages) {
			if (message != null) {
				log.info("iLink 原始消息：id={}，类型={}，发送方={}，消息项数量={}", message.getMessage_id(),
						message.getMessage_type(), message.getFrom_user_id(),
						message.getItem_list() == null ? 0 : message.getItem_list().size());
			}
			if (!isNewUserMessage(runtime.accountId, message)) {
				continue;
			}
			String text = extractText(message);
			MediaExtraction extraction = extractMedia(runtime.client, message);
			List<MediaContent> media = extraction.media();
			if (extraction.mediaItemFound() && media.isEmpty() && extraction.failed()) {
				sendMediaFailureReply(runtime.client, message.getFrom_user_id());
				continue;
			}
			if ((text == null || text.isBlank()) && media.isEmpty()) {
				continue;
			}
			queueQuestion(runtime, message.getFrom_user_id(), text == null ? "" : text.trim(), media);
		}
	}

	private void queueQuestion(AccountRuntime runtime, String peerUserId, String question, List<MediaContent> media) {
		String sessionId = sessionId(runtime.accountId, peerUserId);
		agentModeHandler.onUserMessageReceived(sessionId, question);
		long messageVersion = userMessageVersions.computeIfAbsent(sessionId, ignored -> new AtomicLong())
				.incrementAndGet();
		PendingBatch batch = pendingBatches.computeIfAbsent(sessionId,
				ignored -> new PendingBatch(runtime.accountId, peerUserId));
		ScheduledFuture<?> previous;
		int totalMedia;
		boolean hasText;
		String receiptReply = null;
		long flushDelayMs;
		synchronized (batch) {
			boolean hadMedia = !batch.media.isEmpty();
			if (!question.isBlank()) {
				if (!batch.question.isBlank()) {
					batch.question += "\n" + question;
				} else {
					batch.question = question;
				}
			}
			batch.media.addAll(media);
			if (!batch.acknowledged && batch.question.isBlank() && !batch.media.isEmpty()) {
				boolean onlyImages = batch.media.stream().allMatch(item -> item.type() == MediaType.IMAGE);
				boolean onlyDocuments = batch.media.stream().allMatch(item -> item.type() == MediaType.DOCUMENT);
				if (onlyImages) {
					batch.acknowledged = true;
					awaitingImageInstructionByUser.put(sessionId, System.currentTimeMillis());
					awaitingDocumentInstructionByUser.remove(sessionId);
					receiptReply = batch.media.size() > 1
							? "我已经接收到你的图片了。你想让我比较、分析还是修改这些图片呢？如果暂时不回复，我会在 3 秒后自动识别。"
							: "我已经接收到你的图片了。你想让我做什么呢？如果暂时不回复，我会在 3 秒后自动识别。";
				} else if (onlyDocuments) {
					batch.acknowledged = true;
					awaitingDocumentInstructionByUser.put(sessionId, System.currentTimeMillis());
					awaitingImageInstructionByUser.remove(sessionId);
					receiptReply = batch.media.size() > 1
							? "我已经接收到你的文件了。你想让我做什么呢？如果暂时不回复，我会在 3 秒后自动总结。"
							: "我已经接收到你的文件了。你想让我做什么呢？如果暂时不回复，我会在 3 秒后自动总结。";
				}
			}
			batch.messageVersion = messageVersion;
			if (batch.acknowledged && batch.question.isBlank()) {
				if (batch.media.stream().allMatch(item -> item.type() == MediaType.IMAGE)) {
					awaitingImageInstructionByUser.put(sessionId, messageVersion);
				} else if (batch.media.stream().allMatch(item -> item.type() == MediaType.DOCUMENT)) {
					awaitingDocumentInstructionByUser.put(sessionId, messageVersion);
				}
			}
			totalMedia = batch.media.size();
			hasText = !batch.question.isBlank();
			flushDelayMs = !media.isEmpty() || hadMedia ? mediaBatchWindowMs : 700L;
			if (hadMedia && !question.isBlank()) {
				flushDelayMs = Math.min(flushDelayMs, 900L);
			}
			List<MediaContent> images = batch.media.stream()
					.filter(item -> item.type() == MediaType.IMAGE).toList();
			if (!images.isEmpty()) {
				recentMediaByUser.put(sessionId,
						new RecentMedia(images, System.currentTimeMillis()));
			}
			List<MediaContent> documents = batch.media.stream()
					.filter(item -> item.type() == MediaType.DOCUMENT).toList();
			if (!documents.isEmpty()) {
				recentDocumentsByUser.put(sessionId,
						new RecentMedia(documents, System.currentTimeMillis()));
			}
			previous = batch.flushTask;
			batch.flushTask = batchExecutor.schedule(() -> flushQuestion(sessionId, batch),
					flushDelayMs, TimeUnit.MILLISECONDS);
		}
		if (previous != null) {
			previous.cancel(false);
		}
		if (receiptReply != null) {
			sendReceiptReply(runtime.client, peerUserId, receiptReply);
		}
		log.info("已暂存用户消息，等待 {} 毫秒收集同一轮文本和媒体：用户={}，本批媒体总数={}，已有文字={}",
				flushDelayMs, sessionId, totalMedia, hasText);
	}

	private void flushQuestion(String sessionId, PendingBatch batch) {
		String question;
		List<MediaContent> media;
		long messageVersion;
		synchronized (batch) {
			question = batch.question;
			media = List.copyOf(batch.media);
			messageVersion = batch.messageVersion;
			pendingBatches.remove(sessionId, batch);
		}
		boolean onlyDocuments = !media.isEmpty()
				&& media.stream().allMatch(item -> item.type() == MediaType.DOCUMENT);
		boolean onlyImages = !media.isEmpty()
				&& media.stream().allMatch(item -> item.type() == MediaType.IMAGE);
		boolean defaultMediaTask = question.isBlank() && (onlyDocuments || onlyImages);
		if (defaultMediaTask) {
			question = onlyDocuments
					? "用户已经成功上传了这个文件。请直接总结内容，保留关键结论、重要数字、日期和待办事项。"
					: "用户已经成功上传了图片。请直接识别并自然描述图片内容，同时留意画面中的文字和重要细节。";
			log.info("等待窗口结束，开始执行默认{}任务：用户={}，附件数量={}",
					onlyDocuments ? "文件总结" : "图片识别", sessionId, media.size());
		}
		if (media.isEmpty() && !question.startsWith("/")) {
			if (awaitingDocumentInstructionByUser.remove(sessionId) != null) {
				List<MediaContent> recentDocuments = recentDocuments(sessionId);
				if (!recentDocuments.isEmpty()) {
					media = recentDocuments;
					log.info("已将下一条用户需求与待处理文件合并：会话={}，文件数量={}", sessionId, media.size());
				}
			} else if (awaitingImageInstructionByUser.remove(sessionId) != null) {
				List<MediaContent> recentImages = recentMedia(sessionId);
				if (!recentImages.isEmpty()) {
					media = recentImages;
					log.info("已将下一条用户需求与待处理图片合并：会话={}，图片数量={}", sessionId, media.size());
				}
			}
		}
		if (!defaultMediaTask && onlyDocuments && !question.isBlank()) awaitingDocumentInstructionByUser.remove(sessionId);
		if (!defaultMediaTask && onlyImages && !question.isBlank()) awaitingImageInstructionByUser.remove(sessionId);
		if (media.isEmpty() && ImageReplyPolicy.referencesUploadedImage(question)) {
			List<MediaContent> recentMedia = recentMedia(sessionId);
			if (!recentMedia.isEmpty()) {
				media = recentMedia;
				log.info("文字消息引用了近期媒体，已自动合并：会话={}，媒体数量={}", sessionId, media.size());
			}
		}
		if (media.isEmpty() && DocumentReplyPolicy.referencesUploadedFile(question)) {
			List<MediaContent> recentDocuments = recentDocuments(sessionId);
			if (!recentDocuments.isEmpty()) {
				media = recentDocuments;
				log.info("文字消息引用了近期上传文件，已自动合并：会话={}，文件数量={}", sessionId, media.size());
			}
		}
		try {
			AccountRuntime runtime = accounts.get(batch.accountId);
			if (runtime == null || runtime.client == null) return;
			handleQuestion(runtime.client, sessionId, batch.peerUserId, question, media, messageVersion);
			saveSession(runtime);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			log.warn("批量消息处理被中断：会话={}", sessionId);
		} finally {
			if (defaultMediaTask) {
				awaitingImageInstructionByUser.remove(sessionId, messageVersion);
				awaitingDocumentInstructionByUser.remove(sessionId, messageVersion);
			}
		}
	}

	private void sendReceiptReply(ILinkClient client, String userId, String reply) {
		try {
			client.sendText(userId, reply);
			log.info("已发送附件接收提示：用户={}", userId);
		} catch (IOException | RuntimeException exception) {
			log.warn("附件接收提示发送失败，仍将继续处理附件：用户={}，原因={}", userId, exception.getMessage());
		}
	}

	private List<MediaContent> recentMedia(String userId) {
		RecentMedia recent = recentMediaByUser.get(userId);
		if (recent == null) {
			return List.of();
		}
		if (System.currentTimeMillis() - recent.savedAtMs() > recentMediaTtlMs) {
			recentMediaByUser.remove(userId, recent);
			return List.of();
		}
		return recent.media();
	}

	private List<MediaContent> recentDocuments(String userId) {
		RecentMedia recent = recentDocumentsByUser.get(userId);
		if (recent == null) return List.of();
		if (System.currentTimeMillis() - recent.savedAtMs() > recentDocumentTtlMs) {
			recentDocumentsByUser.remove(userId, recent);
			return List.of();
		}
		return recent.media();
	}

	private boolean isNewUserMessage(String accountId, WeixinMessage message) {
		if (message == null || message.getFrom_user_id() == null || message.getFrom_user_id().isBlank()) {
			return false;
		}
		boolean normalMessage = Integer.valueOf(1).equals(message.getMessage_type());
		boolean hasMessageItems = message.getItem_list() != null && !message.getItem_list().isEmpty();
		if (!normalMessage && !hasMessageItems) {
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
		String deduplicationKey = accountId + ":" + messageId;
		synchronized (seenMessageIds) {
			if (seenMessageIds.containsKey(deduplicationKey)) {
				return false;
			}
			seenMessageIds.put(deduplicationKey, Boolean.TRUE);
		}
		return true;
	}

	private void handleQuestion(ILinkClient client, String sessionId, String userId,
			String question, List<MediaContent> media,
			long messageVersion) throws InterruptedException {
		long receivedAt = System.currentTimeMillis();
		long targetDelayMs = randomDelayMs();
		// 同一个 requestId 贯穿这条微信消息的全部处理日志。
		org.slf4j.MDC.put(RequestIds.MDC_KEY, RequestIds.newId());
		try {
			handleQuestionInternal(client, sessionId, userId, question, media, messageVersion,
					receivedAt, targetDelayMs);
		} finally {
			org.slf4j.MDC.remove(RequestIds.MDC_KEY);
		}
	}

	private void handleQuestionInternal(ILinkClient client, String sessionId, String userId,
			String question, List<MediaContent> media, long messageVersion,
			long receivedAt, long targetDelayMs) throws InterruptedException {
		log.info("收到 iLink 消息：文本字符数={}，媒体数量={}", question.length(), media.size());

		boolean typingStarted = startTyping(client, userId);
		try {
			String answer;
			GeneratedDocument generatedDocument = null;
			GeneratedAudio generatedAudio = null;
			AssistantReply assistantReply = null;
			String commandReply = media.isEmpty() ? commandHandler.handle(question) : null;
			if (commandReply != null) {
				answer = commandReply;
			} else if (media.isEmpty()
					&& (commandReply = agentModeHandler.handle(sessionId, question)) != null) {
				answer = commandReply;
			} else if (!isResetCommand(question) && !rateLimiter.tryAcquire(sessionId)) {
				// 计费上游（生图/搜索/大模型）经不起单用户刷屏；命令与重置不占额度。
				log.warn("用户消息触发限流：会话={}，限额={}/分钟", sessionId, rateLimiter.perMinute());
				answer = "你发得有点快啦，为了保证服务稳定，每分钟最多处理 "
						+ rateLimiter.perMinute() + " 条消息，请稍等一下再发。";
			} else if (isResetCommand(question)) {
				assistantService.clearConversation(sessionId);
				recentMediaByUser.remove(sessionId);
				recentDocumentsByUser.remove(sessionId);
				awaitingImageInstructionByUser.remove(sessionId);
				awaitingDocumentInstructionByUser.remove(sessionId);
				answer = RESET_REPLY;
			} else if (!media.isEmpty() && media.get(0).type() == MediaType.AUDIO
					&& "audio/silk".equals(media.get(0).mimeType())) {
				answer = "抱歉，暂不支持微信语音气泡格式，请将音频以 WAV 或 MP3 文件发送。";
			} else if (!media.isEmpty()) {
				assistantReply = assistantService.replyToMedia(sessionId, question, media);
				answer = assistantReply.text();
			} else {
				assistantReply = assistantService.reply(sessionId, question);
				answer = assistantReply.text();
			}
			if (assistantReply != null) {
				generatedDocument = assistantReply.document();
				generatedAudio = assistantReply.audio();
			}
			answer = safeReply(answer);
			// 对话审计默认关闭；开启后记录问答对，供控制台排查。
			auditService.record(sessionId, question, answer);
			if (isSuperseded(sessionId, messageVersion)) {
				log.info("检测到用户已有更新消息，取消发送过期回复：用户={}，旧版本={}，当前版本={}",
						sessionId, messageVersion, currentMessageVersion(sessionId));
				return;
			}
			long remainingDelay = receivedAt + targetDelayMs - System.currentTimeMillis();
			if (remainingDelay > 0) {
				sleep(remainingDelay);
			}
			if (isSuperseded(sessionId, messageVersion)) {
				log.info("等待发送期间收到更新消息，取消发送过期回复：用户={}，旧版本={}，当前版本={}",
						sessionId, messageVersion, currentMessageVersion(sessionId));
				return;
			}
			boolean unsupportedVoice = !media.isEmpty() && media.get(0).type() == MediaType.AUDIO
					&& "audio/silk".equals(media.get(0).mimeType());
			if (generatedDocument == null && generatedAudio == null && !unsupportedVoice) {
				answer = commandHandler.addWelcomeForFirstContact(sessionId, answer);
			}
			answer = safeReply(answer);
			log.info("准备通过 iLink SDK 发送回复，字数={}，等待={}毫秒", answer.length(), targetDelayMs);
			if (generatedDocument != null) {
				client.sendFile(userId, generatedDocument.bytes(), generatedDocument.fileName(), answer);
			} else if (generatedAudio != null) {
				client.sendFile(userId, generatedAudio.bytes(), generatedAudio.fileName(), answer);
			} else if (assistantReply != null && assistantReply.image() != null) {
				client.sendImage(userId, assistantReply.image().bytes(), assistantReply.image().fileName(), answer);
			} else {
				client.sendText(userId, answer);
			}
			log.info("iLink SDK 回复发送成功");
		} catch (ApplicationException exception) {
			log.warn("iLink 请求处理失败：{}", exception.getMessage());
			try {
				client.sendText(userId, safeReply(exception.getMessage()));
			} catch (IOException sendException) {
				log.error("向微信发送错误提示失败：{}", sendException.getMessage(), sendException);
			}
		} catch (IOException | RuntimeException exception) {
			log.error("iLink 消息处理或回复失败，本条消息已跳过，监听继续：{}",
					exception.getMessage(), exception);
			try {
				String message = exception.getMessage();
				client.sendText(userId, safeReply(message == null || message.isBlank()
						? "请求处理失败，请稍后重试。"
						: "请求处理失败：" + message));
			} catch (IOException sendException) {
				log.error("向微信发送处理失败提示失败：{}", sendException.getMessage(), sendException);
			}
		} finally {
			if (typingStarted) {
				stopTyping(client, userId);
			}
		}
	}

	private boolean deliverReminder(ReminderService.Reminder reminder) {
		String text = "⏰ 提醒：" + reminder.content();
		boolean delivered = pushText(reminder.userId(), text);
		if (delivered) {
			log.info("已通过 iLink 发送定时提醒：id={}，用户={}", reminder.id(), reminder.userId());
		}
		return delivered;
	}
	/** 主动推送入口：定时提醒使用。 */
	private boolean pushText(String sessionId, String text) {
		SessionAddress address = parseSessionId(sessionId);
		AccountRuntime runtime = accounts.get(address.accountId());
		ILinkClient current = runtime == null ? null : runtime.client;
		if (current == null || !current.isLoggedIn()) return false;
		try {
			current.sendText(address.peerUserId(), safeReply(text));
			return true;
		} catch (IOException | RuntimeException error) {
			log.warn("通过 iLink 主动推送失败：账户={}，原因={}", address.accountId(), error.getMessage());
			return false;
		}
	}

	private long currentMessageVersion(String userId) {
		AtomicLong version = userMessageVersions.get(userId);
		return version == null ? 0 : version.get();
	}

	private boolean isSuperseded(String userId, long messageVersion) {
		return currentMessageVersion(userId) > messageVersion;
	}

	private MediaExtraction extractMedia(ILinkClient client, WeixinMessage message) {
		if (message.getItem_list() == null) {
			return new MediaExtraction(List.of(), false, false);
		}
		List<MediaContent> result = new java.util.ArrayList<>();
		boolean mediaItemFound = false;
		boolean failed = false;
		for (MessageItem item : message.getItem_list()) {
			if (item == null) {
				continue;
			}
			log.info("iLink 消息项：类型={}，文本={}，图片={}，视频={}，语音={}，文件={}", item.getType(),
					item.getText_item() != null, item.getImage_item() != null, item.getVideo_item() != null,
					item.getVoice_item() != null, item.getFile_item() != null);
			try {
				if (item.getImage_item() != null) {
					mediaItemFound = true;
					byte[] bytes = client.downloadImageFromMessageItem(item);
					validateMediaSize(bytes);
					result.add(new MediaContent(MediaType.IMAGE, bytes, detectImageMimeType(bytes)));
				} else if (item.getVideo_item() != null) {
					mediaItemFound = true;
					byte[] bytes = client.downloadVideoFromMessageItem(item);
					validateMediaSize(bytes);
					result.add(new MediaContent(MediaType.VIDEO, bytes, "video/mp4"));
				} else if (item.getVoice_item() != null) {
					mediaItemFound = true;
					result.add(new MediaContent(MediaType.AUDIO, new byte[0], "audio/silk"));
				} else if (item.getFile_item() != null && isAudioFile(item.getFile_item().getFile_name())) {
					mediaItemFound = true;
					String fileName = item.getFile_item().getFile_name();
					byte[] bytes = client.downloadFileFromMessageItem(item);
					validateMediaSize(bytes);
					result.add(new MediaContent(MediaType.AUDIO, bytes, audioMimeType(fileName), fileName));
					log.info("已下载微信音频文件：文件名={}，字节数={}", fileName, bytes.length);
				} else if (item.getFile_item() != null && isSupportedDocument(item.getFile_item().getFile_name())) {
					mediaItemFound = true;
					String fileName = item.getFile_item().getFile_name();
					byte[] bytes = client.downloadFileFromMessageItem(item);
					validateMediaSize(bytes);
					result.add(new MediaContent(MediaType.DOCUMENT, bytes, documentMimeType(fileName), fileName));
					log.info("已下载微信文档文件：文件名={}，字节数={}", fileName, bytes.length);
				}
			} catch (IOException | IllegalArgumentException exception) {
				failed = true;
				log.warn("下载或校验微信图片/视频失败：{}", exception.getMessage());
			}
		}
		return new MediaExtraction(result, mediaItemFound, failed);
	}

	private static boolean isAudioFile(String fileName) {
		if (fileName == null) return false;
		String value = fileName.toLowerCase(java.util.Locale.ROOT);
		return value.endsWith(".wav") || value.endsWith(".mp3") || value.endsWith(".m4a")
				|| value.endsWith(".flac") || value.endsWith(".ogg") || value.endsWith(".opus")
				|| value.endsWith(".aac") || value.endsWith(".amr") || value.endsWith(".wma");
	}

	private static String audioMimeType(String fileName) {
		String value = fileName.toLowerCase(java.util.Locale.ROOT);
		if (value.endsWith(".wav")) return "audio/wav";
		if (value.endsWith(".mp3")) return "audio/mpeg";
		if (value.endsWith(".m4a")) return "audio/mp4";
		if (value.endsWith(".flac")) return "audio/flac";
		if (value.endsWith(".ogg")) return "audio/ogg";
		if (value.endsWith(".opus")) return "audio/opus";
		if (value.endsWith(".aac")) return "audio/aac";
		if (value.endsWith(".amr")) return "audio/amr";
		return "audio/x-ms-wma";
	}

	private void sendMediaFailureReply(ILinkClient client, String userId) throws InterruptedException {
		boolean typingStarted = startTyping(client, userId);
		try {
			sleep(randomDelayMs());
			client.sendText(userId, "这张图片或视频下载失败了，可能文件过大或格式暂不支持，请换一个文件再试。");
		} catch (IOException exception) {
			log.error("发送媒体失败提示时出错：{}", exception.getMessage(), exception);
		} finally {
			if (typingStarted) {
				stopTyping(client, userId);
			}
		}
	}

	private void validateMediaSize(byte[] bytes) {
		if (bytes == null || bytes.length == 0) {
			throw new IllegalArgumentException("微信媒体内容为空");
		}
		if (bytes.length > maxMediaBytes) {
			throw new IllegalArgumentException("微信媒体超过大小限制：" + maxMediaBytes + " bytes");
		}
	}

	private static String detectImageMimeType(byte[] bytes) {
		if (bytes.length >= 8 && bytes[0] == (byte) 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4e && bytes[3] == 0x47) {
			return "image/png";
		}
		if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
				&& bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
			return "image/webp";
		}
		if (bytes.length >= 2 && bytes[0] == 'B' && bytes[1] == 'M') {
			return "image/bmp";
		}
		return "image/jpeg";
	}

	private boolean startTyping(ILinkClient client, String userId) {
		try {
			client.startTyping(userId);
			log.info("已向微信发送“正在输入”状态");
			return true;
		} catch (IOException | RuntimeException exception) {
			log.warn("无法发送“正在输入”状态，将继续生成回答：{}", exception.getMessage());
			return false;
		}
	}

	private void stopTyping(ILinkClient client, String userId) {
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

	private void saveSession(AccountRuntime runtime) {
		ILinkClient current = runtime.client;
		if (current != null && current.isLoggedIn()) {
			sessionStore.save(runtime.accountId, current.exportResumeContext());
		}
	}

	private void closeAccount(AccountRuntime runtime) {
		ILinkClient current = runtime.client;
		if (current == null) {
			return;
		}
		runtime.client = null;
		Thread worker = runtime.worker;
		if (worker != null && worker != Thread.currentThread()) worker.interrupt();
		try {
			if (current.isLoggedIn()) {
				sessionStore.save(runtime.accountId, current.exportResumeContext());
			}
		} catch (RuntimeException exception) {
			log.warn("关闭前保存 iLink 会话失败：账户={}，原因={}",
					runtime.accountId, exception.getMessage());
		} finally {
			current.close();
			if (runtime.status != AccountStatus.DELETED && runtime.status != AccountStatus.ERROR) {
				runtime.status = AccountStatus.OFFLINE;
			}
		}
	}

	public synchronized AccountView createAccount() {
		if (accounts.size() >= maxAccounts) {
			throw new IllegalStateException("iLink 账户数量已达到上限：" + maxAccounts);
		}
		String accountId = UUID.randomUUID().toString();
		startAccount(accountId, null, false);
		AccountRuntime runtime = accounts.get(accountId);
		long deadline = System.currentTimeMillis() + 10_000;
		while (runtime.qrCode == null && runtime.status != AccountStatus.ERROR
				&& System.currentTimeMillis() < deadline) {
			try {
				Thread.sleep(50);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		return runtime.view(true);
	}

	/** Creates an account while preserving the optional admin display name. */
	public synchronized AccountView createAccount(String displayName) {
		AccountView created = createAccount();
		return updateAccountDisplayName(created.accountId(), displayName);
	}

	public synchronized AccountView updateAccountDisplayName(String accountId, String displayName) {
		AccountRuntime runtime = accounts.get(accountId);
		if (runtime == null) throw new IllegalArgumentException("iLink 账户不存在：" + accountId);
		runtime.displayName = displayName == null || displayName.isBlank() ? null : displayName.trim();
		return runtime.view(false);
	}

	public List<AccountView> accountViews() {
		return accounts.values().stream()
				.map(runtime -> runtime.view(false))
				.sorted(java.util.Comparator.comparing(AccountView::accountId))
				.toList();
	}

	public AccountView accountView(String accountId, boolean includeQrCode) {
		AccountRuntime runtime = accounts.get(accountId);
		if (runtime == null) throw new IllegalArgumentException("iLink 账户不存在：" + accountId);
		return runtime.view(includeQrCode);
	}

	public synchronized void deleteAccount(String accountId) {
		if ("default".equals(accountId)) {
			throw new IllegalStateException("默认机器人暂不支持直接删除，请使用重新扫码或单独清除对话功能");
		}
		AccountRuntime runtime = accounts.remove(accountId);
		if (runtime == null) throw new IllegalArgumentException("iLink 账户不存在：" + accountId);
		runtime.status = AccountStatus.DELETED;
		closeAccount(runtime);
		sessionStore.delete(accountId);
		int conversations = clearAccountData(accountId);
		log.info("iLink 账户及其关联数据已删除：账户={}，对话数={}", accountId, conversations);
	}

	private int clearAccountData(String accountId) {
		String prefix = accountId + SESSION_SEPARATOR;
		for (Map.Entry<String, PendingBatch> entry : pendingBatches.entrySet()) {
			if (entry.getKey().startsWith(prefix) && pendingBatches.remove(entry.getKey(), entry.getValue())) {
				ScheduledFuture<?> task = entry.getValue().flushTask;
				if (task != null) task.cancel(false);
			}
		}
		recentMediaByUser.keySet().removeIf(sessionId -> sessionId.startsWith(prefix));
		recentDocumentsByUser.keySet().removeIf(sessionId -> sessionId.startsWith(prefix));
		awaitingImageInstructionByUser.keySet().removeIf(sessionId -> sessionId.startsWith(prefix));
		awaitingDocumentInstructionByUser.keySet().removeIf(sessionId -> sessionId.startsWith(prefix));
		userMessageVersions.keySet().removeIf(sessionId -> sessionId.startsWith(prefix));
		synchronized (seenMessageIds) {
			seenMessageIds.keySet().removeIf(key -> key.startsWith(accountId + ":"));
		}
		int conversations = assistantService.clearConversationsByPrefix(prefix);
		int reminders = reminderService.deleteByUserIdPrefix(prefix);
		int voicePreferences = voicePreferenceService.clearByUserIdPrefix(prefix);
		log.info("已清理机器人账户数据：账户={}，对话={}，提醒={}，音色偏好={}",
				accountId, conversations, reminders, voicePreferences);
		return conversations;
	}

	public synchronized AccountView restartAccount(String accountId) {
		AccountRuntime previous = accounts.remove(accountId);
		if (previous == null) throw new IllegalArgumentException("iLink 账户不存在：" + accountId);
		closeAccount(previous);
		ResumeContext saved = sessionStore.loadAll().get(accountId);
		startAccount(accountId, saved, false);
		AccountRuntime restarted = accounts.get(accountId);
		long deadline = System.currentTimeMillis() + 10_000;
		while (restarted.status == AccountStatus.STARTING && System.currentTimeMillis() < deadline) {
			try {
				Thread.sleep(50);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		log.info("iLink 账户已重新启动：账户={}", accountId);
		return restarted.view(true);
	}

	/**
	 * 重新走微信扫码登录，但复用同一个逻辑账户 ID。
	 * 聊天记忆、提醒和附件关联均以该 ID 为边界，因此不会因重新扫码而丢失。
	 */
	public synchronized AccountView reauthenticateAccount(String accountId) {
		AccountRuntime previous = accounts.remove(accountId);
		if (previous == null) throw new IllegalArgumentException("iLink 账户不存在：" + accountId);
		closeAccount(previous);
		// 仅删除 SDK 登录凭据；不要删除以 accountId 隔离的对话和提醒数据。
		sessionStore.delete(accountId);
		startAccount(accountId, null, false);
		AccountRuntime reauthenticated = accounts.get(accountId);
		long deadline = System.currentTimeMillis() + 10_000;
		while (reauthenticated.qrCode == null && reauthenticated.status != AccountStatus.ERROR
				&& System.currentTimeMillis() < deadline) {
			try {
				Thread.sleep(50);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		log.info("iLink 账户已进入重新扫码状态，保留原有对话数据：账户={}", accountId);
		return reauthenticated.view(true);
	}

	static String sessionId(String accountId, String peerUserId) {
		if ("default".equals(accountId)) {
			return peerUserId;
		}
		return accountId + SESSION_SEPARATOR + peerUserId;
	}

	private static SessionAddress parseSessionId(String value) {
		int separator = value == null ? -1 : value.indexOf(SESSION_SEPARATOR);
		if (separator <= 0) {
			return new SessionAddress("default", value);
		}
		return new SessionAddress(value.substring(0, separator),
				value.substring(separator + SESSION_SEPARATOR.length()));
	}

	private static String extractText(WeixinMessage message) {
		if (message.getItem_list() == null) {
			return null;
		}
		String extractedText = message.getItem_list().stream()
				.filter(item -> item != null && item.getText_item() != null)
				.map(MessageItem::getText_item)
				.map(textItem -> textItem.getText())
				.filter(text -> text != null && !text.isBlank())
				.findFirst()
				.orElse(null);
		if (extractedText != null) {
			return extractedText;
		}
		return null;
	}

	private static boolean isResetCommand(String question) {
		String value = question.trim();
		return "/reset".equalsIgnoreCase(value) || "清除对话".equals(value) || "重新开始".equals(value);
	}

	private static boolean isSupportedDocument(String fileName) {
		if (fileName == null) return false;
		String value = fileName.toLowerCase(java.util.Locale.ROOT);
		return value.endsWith(".docx") || value.endsWith(".doc") || value.endsWith(".pdf")
				|| value.endsWith(".txt") || value.endsWith(".md") || value.endsWith(".csv")
				|| value.endsWith(".xlsx") || value.endsWith(".xls")
				|| value.endsWith(".pptx") || value.endsWith(".ppt");
	}

	private static String documentMimeType(String fileName) {
		String value = fileName.toLowerCase(java.util.Locale.ROOT);
		if (value.endsWith(".pdf")) return "application/pdf";
		if (value.endsWith(".txt")) return "text/plain";
		if (value.endsWith(".md")) return "text/markdown";
		if (value.endsWith(".csv")) return "text/csv";
		if (value.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
		if (value.endsWith(".doc")) return "application/msword";
		if (value.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
		if (value.endsWith(".xls")) return "application/vnd.ms-excel";
		if (value.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
		return "application/vnd.ms-powerpoint";
	}

	private static void sleep(long millis) throws InterruptedException {
		TimeUnit.MILLISECONDS.sleep(Math.max(0, millis));
	}

	private record MediaExtraction(List<MediaContent> media, boolean mediaItemFound, boolean failed) {
	}

	private static final class PendingBatch {
		private final String accountId;
		private final String peerUserId;
		private String question = "";
		private final java.util.ArrayList<MediaContent> media = new java.util.ArrayList<>();
		private ScheduledFuture<?> flushTask;
		private long messageVersion;
		private boolean acknowledged;

		private PendingBatch(String accountId, String peerUserId) {
			this.accountId = accountId;
			this.peerUserId = peerUserId;
		}
	}

	public enum AccountStatus { STARTING, WAITING_FOR_SCAN, ONLINE, OFFLINE, ERROR, DELETED }

	public record AccountView(String accountId, AccountStatus status, String botId,
			String ownerUserId, String qrCode, String error, String displayName) { }

	private record SessionAddress(String accountId, String peerUserId) { }

	private static final class AccountRuntime {
		private final String accountId;
		private final ResumeContext resumeContext;
		private final boolean printQr;
		private volatile ILinkClient client;
		private volatile Thread worker;
		private volatile AccountStatus status = AccountStatus.STARTING;
		private volatile String qrCode;
		private volatile String error;
		private volatile String displayName;

		private AccountRuntime(String accountId, ResumeContext resumeContext, boolean printQr) {
			this.accountId = accountId;
			this.resumeContext = resumeContext;
			this.printQr = printQr;
		}

		private AccountView view(boolean includeQrCode) {
			ResumeContext exported = null;
			try {
				if (client != null && client.isLoggedIn()) exported = client.exportResumeContext();
			} catch (RuntimeException ignored) {
				// 状态接口不能因为 SDK 临时状态失败。
			}
			ResumeContext context = exported == null ? resumeContext : exported;
			String botId = context == null || context.getLoginContext() == null
					? null : context.getLoginContext().getBotId();
			String userId = context == null || context.getLoginContext() == null
					? null : context.getLoginContext().getUserId();
			return new AccountView(accountId, status, botId, userId,
					includeQrCode ? qrCode : null, error, displayName);
		}
	}

	private record RecentMedia(List<MediaContent> media, long savedAtMs) {
	}
}
