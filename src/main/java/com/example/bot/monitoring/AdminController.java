package com.example.bot.monitoring;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ResponseCookie;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import jakarta.servlet.http.Cookie;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.example.bot.ApplicationException;
import com.example.bot.agent.AgentLoopService;
import com.example.bot.agent.AgentTask;
import com.example.bot.agent.AgentTraceEvent;
import com.example.bot.chat.ConversationMemory;
import com.example.bot.chat.SqliteConversationStore;
import com.example.bot.chat.MediaContent;
import com.example.bot.document.DocumentSummaryService;
import com.example.bot.knowledge.KnowledgeBaseService;
import com.example.bot.ilink.IlinkChatRunner;
import com.example.bot.ilink.QrCodeSvgService;
import com.example.bot.ilink.UserNameService;
import com.example.bot.reminder.ReminderService;
import com.example.bot.tools.AssistantToolRegistry;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
	private static final Logger log = LoggerFactory.getLogger(AdminController.class);
	private static final int MAX_LOG_RESPONSE_BYTES = 2_000_000;
	private final Path applicationLogFile;
	private final AssistantToolRegistry registry;
	private final ToolMetricsService metrics;
	private final ConversationMemory memory;
	private final ReminderService reminders;
	private final KnowledgeBaseService knowledgeBase;
	private final StringRedisTemplate redis;
	private final ObjectProvider<IlinkChatRunner> ilinkRunner;
	private final QrCodeSvgService qrCodeSvgService;
	private final DocumentSummaryService documents;
	private final AdminSessionService sessions;
	private final ConversationAuditService audit;
	private final SqliteConversationStore sqliteConversations;
	private final AgentLoopService agentLoop;
	private final UserNameService userNames;

	public AdminController(
			@Value("${assistant.logs.file:/var/lib/wechat-agent-platform/logs/application.log}") String applicationLogFile,
			AssistantToolRegistry registry, ToolMetricsService metrics, ConversationMemory memory,
			ReminderService reminders, KnowledgeBaseService knowledgeBase,
			ObjectProvider<StringRedisTemplate> redisProvider,
			ObjectProvider<IlinkChatRunner> ilinkRunner,
			QrCodeSvgService qrCodeSvgService, DocumentSummaryService documents, AdminSessionService sessions,
			ConversationAuditService audit, SqliteConversationStore sqliteConversations,
			AgentLoopService agentLoop, UserNameService userNames) {
		this.applicationLogFile = Path.of(applicationLogFile).toAbsolutePath().normalize();
		this.registry = registry;
		this.metrics = metrics;
		this.memory = memory;
		this.reminders = reminders;
		this.knowledgeBase = knowledgeBase;
		this.redis = redisProvider.getIfAvailable();
		this.ilinkRunner = ilinkRunner;
		this.qrCodeSvgService = qrCodeSvgService;
		this.documents = documents;
		this.sessions = sessions;
		this.audit = audit;
		this.sqliteConversations = sqliteConversations;
		this.agentLoop = agentLoop;
		this.userNames = userNames;
	}

	@GetMapping(value = "/ilink/console", produces = MediaType.TEXT_HTML_VALUE)
	public ResponseEntity<String> ilinkConsolePage() {
		return ResponseEntity.ok().header("Cache-Control", "no-store").body(AdminPages.app());
	}

	@GetMapping(value = "/ilink/knowledge", produces = MediaType.TEXT_HTML_VALUE)
	public ResponseEntity<String> knowledgePage() {
		return ResponseEntity.ok().header("Cache-Control", "no-store").body(AdminPages.app());
	}

	@GetMapping(value = "/ilink/logs-page", produces = MediaType.TEXT_HTML_VALUE)
	public ResponseEntity<String> logsPage() {
		return ResponseEntity.ok().header("Cache-Control", "no-store").body(AdminPages.app());
	}

	@GetMapping("/ilink/accounts/{accountId}/knowledge")
	public List<KnowledgeBaseService.KnowledgeFile> accountKnowledge(
			@RequestHeader(name = "X-Admin-Token", required = false) String providedToken, @PathVariable String accountId) {
		authorizeIlink(providedToken); requiredIlinkRunner().accountView(accountId, false);
		return knowledgeBase.list(accountId);
	}

	@PostMapping(value = "/ilink/accounts/{accountId}/knowledge", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public KnowledgeBaseService.KnowledgeFile uploadAccountKnowledge(
			@RequestHeader(name = "X-Admin-Token", required = false) String providedToken, @PathVariable String accountId,
			@RequestPart("file") MultipartFile file) throws java.io.IOException {
		authorizeIlink(providedToken); requiredIlinkRunner().accountView(accountId, false);
		if (file.isEmpty() || file.getSize() > 20L * 1024 * 1024) throw new IllegalArgumentException("请选择不超过 20MB 的文件");
		String name = file.getOriginalFilename() == null ? "knowledge.txt" : file.getOriginalFilename();
		String mimeType = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
		String text = documents.extractText(new MediaContent(MediaContent.MediaType.DOCUMENT, file.getBytes(), mimeType, name));
		var saved = knowledgeBase.addForAccount(accountId, name, text);
		return knowledgeBase.list(accountId).stream().filter(item -> item.name().equals(saved.getFileName().toString())).findFirst()
				.orElse(new KnowledgeBaseService.KnowledgeFile(saved.getFileName().toString(), file.getSize(), System.currentTimeMillis()));
	}

	@DeleteMapping("/ilink/accounts/{accountId}/knowledge/{fileName}")
	public Map<String, Boolean> deleteAccountKnowledge(
			@RequestHeader(name = "X-Admin-Token", required = false) String providedToken, @PathVariable String accountId,
			@PathVariable String fileName) {
		authorizeIlink(providedToken); requiredIlinkRunner().accountView(accountId, false);
		knowledgeBase.delete(accountId, fileName); return Map.of("deleted", true);
	}

	@GetMapping(value = "/ilink/logs", produces = MediaType.TEXT_PLAIN_VALUE)
	public ResponseEntity<String> recentLogs(@RequestHeader(name = "X-Admin-Token", required = false) String providedToken,
			@RequestParam(defaultValue = "24") int hours) {
		authorizeIlink(providedToken);
		try {
			return ResponseEntity.ok().header("Content-Disposition", "inline; filename=bot-all-bots.log")
					.body(readApplicationLogTail());
		} catch (Exception error) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "无法读取应用日志：" + error.getMessage());
		}
	}

	private String readApplicationLogTail() throws java.io.IOException {
		if (!Files.isRegularFile(applicationLogFile)) {
			return "暂时没有应用日志；请确认服务已重启并产生请求。";
		}
		try (FileChannel channel = FileChannel.open(applicationLogFile, StandardOpenOption.READ)) {
			long size = channel.size();
			int length = (int) Math.min(size, MAX_LOG_RESPONSE_BYTES);
			channel.position(Math.max(0, size - length));
			ByteBuffer buffer = ByteBuffer.allocate(length);
			while (buffer.hasRemaining() && channel.read(buffer) != -1) {
				// Continue until the requested tail has been read.
			}
			return StandardCharsets.UTF_8.decode((ByteBuffer) buffer.flip()).toString();
		}
	}

	@GetMapping(value = "/ilink/login", produces = MediaType.TEXT_HTML_VALUE)
	public ResponseEntity<String> ilinkLoginPage() {
		return ResponseEntity.ok()
				.header("Cache-Control", "no-store")
				.header("X-Frame-Options", "DENY")
				.header("Referrer-Policy", "no-referrer")
				.header("Content-Security-Policy",
						"default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; "
						+ "img-src 'self' blob:; connect-src 'self'; frame-ancestors 'none'; "
						+ "base-uri 'none'; form-action 'none'")
				.body(AdminPages.app());
	}

	@GetMapping("/ilink/session")
	public Map<String, Boolean> ilinkSession() {
		return Map.of("authenticated", sessions.isActive(currentSessionId()));
	}

	@PostMapping("/ilink/session")
	public ResponseEntity<Void> createIlinkSession(@org.springframework.web.bind.annotation.RequestBody LoginRequest request) {
		if (!sessions.isConfigured()) {
			throw new ApplicationException(HttpStatus.SERVICE_UNAVAILABLE, "服务端尚未配置 ADMIN_API_TOKEN");
		}
		String sessionId = sessions.create(request == null ? null : request.token());
		if (sessionId == null) throw new ApplicationException(HttpStatus.UNAUTHORIZED, "API Token 不正确，请检查后重试");
		ResponseCookie cookie = ResponseCookie.from("admin_session", sessionId).httpOnly(true).sameSite("Strict")
				.path("/api/admin").maxAge(AdminSessionService.SESSION_TTL).build();
		return ResponseEntity.noContent().header("Set-Cookie", cookie.toString()).build();
	}

	@DeleteMapping("/ilink/session")
	public ResponseEntity<Void> deleteIlinkSession() {
		sessions.revoke(currentSessionId());
		ResponseCookie cookie = ResponseCookie.from("admin_session", "").httpOnly(true).sameSite("Strict")
				.path("/api/admin").maxAge(0).build();
		return ResponseEntity.noContent().header("Set-Cookie", cookie.toString()).build();
	}

	public record LoginRequest(String token) { }
	public record AccountCreateRequest(String displayName) { }
	public record AccountNameUpdate(String displayName) { }
	@GetMapping("/ilink/accounts")
	public List<IlinkChatRunner.AccountView> ilinkAccounts(
			@RequestHeader(name = "X-Admin-Token", required = false) String providedToken) {
		authorizeIlink(providedToken);
		return requiredIlinkRunner().accountViews();
	}

	@PostMapping("/ilink/accounts")
	public IlinkChatRunner.AccountView createIlinkAccount(
			@RequestHeader(name = "X-Admin-Token", required = false) String providedToken,
			@org.springframework.web.bind.annotation.RequestBody(required = false) AccountCreateRequest request) {
		authorizeIlink(providedToken);
		return requiredIlinkRunner().createAccount(request == null ? null : request.displayName());
	}

	@PatchMapping("/ilink/accounts/{accountId}")
	public IlinkChatRunner.AccountView updateIlinkAccountName(
			@RequestHeader(name = "X-Admin-Token", required = false) String providedToken,
			@PathVariable String accountId,
			@org.springframework.web.bind.annotation.RequestBody AccountNameUpdate request) {
		authorizeIlink(providedToken);
		return requiredIlinkRunner().updateAccountDisplayName(accountId, request == null ? null : request.displayName());
	}

	@GetMapping("/ilink/accounts/{accountId}")
	public IlinkChatRunner.AccountView ilinkAccount(
			@RequestHeader(name = "X-Admin-Token", required = false) String providedToken,
			@PathVariable String accountId) {
		authorizeIlink(providedToken);
		return requiredIlinkRunner().accountView(accountId, true);
	}

	@GetMapping(value = "/ilink/accounts/{accountId}/qrcode.svg", produces = "image/svg+xml")
	public ResponseEntity<String> ilinkAccountQrCode(
			@RequestHeader(name = "X-Admin-Token", required = false) String providedToken,
			@PathVariable String accountId) {
		authorizeIlink(providedToken);
		IlinkChatRunner.AccountView account = requiredIlinkRunner().accountView(accountId, true);
		if (account.qrCode() == null || account.qrCode().isBlank()) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					account.status() == IlinkChatRunner.AccountStatus.ONLINE
							? "该机器人已经登录，无需扫码"
							: "二维码尚未生成或已经失效，请重新生成");
		}
		return ResponseEntity.ok()
				.header("Cache-Control", "no-store")
				.contentType(MediaType.parseMediaType("image/svg+xml"))
				.body(qrCodeSvgService.render(account.qrCode()));
	}

	@DeleteMapping("/ilink/accounts/{accountId}")
	public Map<String, Object> deleteIlinkAccount(
			@RequestHeader(name = "X-Admin-Token", required = false) String providedToken,
			@PathVariable String accountId) {
		authorizeIlink(providedToken);
		requiredIlinkRunner().deleteAccount(accountId);
		return Map.of("deleted", true, "accountId", accountId);
	}

	@PostMapping("/ilink/accounts/{accountId}/restart")
	public IlinkChatRunner.AccountView restartIlinkAccount(
			@RequestHeader(name = "X-Admin-Token", required = false) String providedToken,
			@PathVariable String accountId) {
		authorizeIlink(providedToken);
		return requiredIlinkRunner().restartAccount(accountId);
	}

	@PostMapping("/ilink/accounts/{accountId}/reauthenticate")
	public IlinkChatRunner.AccountView reauthenticateIlinkAccount(
			@RequestHeader(name = "X-Admin-Token", required = false) String providedToken,
			@PathVariable String accountId) {
		authorizeIlink(providedToken);
		return requiredIlinkRunner().reauthenticateAccount(accountId);
	}

	private IlinkChatRunner requiredIlinkRunner() {
		IlinkChatRunner runner = ilinkRunner.getIfAvailable();
		if (runner == null) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "iLink 功能未启用");
		}
		return runner;
	}

	private void authorizeIlink(String provided) {
		if (!sessions.isConfigured()) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
					"管理微信账户前必须配置 ADMIN_API_TOKEN");
		}
		if (sessions.isActive(currentSessionId())) return;
		authorize(provided);
	}

	private String currentSessionId() {
		var attributes = RequestContextHolder.getRequestAttributes();
		if (!(attributes instanceof ServletRequestAttributes servletAttributes)) return null;
		Cookie[] cookies = servletAttributes.getRequest().getCookies();
		if (cookies == null) return null;
		for (Cookie cookie : cookies) if ("admin_session".equals(cookie.getName())) return cookie.getValue();
		return null;
	}

	/** 每次工具调用的耗时、结果和错误摘要（内存环形缓冲，最多 500 条）。 */
	@GetMapping("/ops/invocations")
	public List<ToolMetricsService.InvocationRecord> toolInvocations(
			@RequestHeader(name = "X-Admin-Token", required = false) String providedToken,
			@RequestParam(defaultValue = "100") int limit) {
		authorizeIlink(providedToken);
		return metrics.recentInvocations(limit);
	}

	/** 近 N 天各工具用量（来自 Redis 日桶，重启不丢）。 */
	@GetMapping("/ops/usage")
	public List<ToolMetricsService.DailyUsage> dailyUsage(
			@RequestHeader(name = "X-Admin-Token", required = false) String providedToken,
			@RequestParam(defaultValue = "7") int days) {
		authorizeIlink(providedToken);
		return metrics.dailyUsage(days);
	}

	@GetMapping("/ops/audit")
	public AuditView auditView(@RequestHeader(name = "X-Admin-Token", required = false) String providedToken,
			@RequestParam(defaultValue = "100") int limit) {
		authorizeIlink(providedToken);
		return new AuditView(audit.isEnabled(), audit.recent(limit));
	}

	/** 对话审计涉及隐私，默认关闭；此开关只影响当前进程，重启后回到配置默认值。 */
	@PostMapping("/ops/audit")
	public Map<String, Boolean> setAuditEnabled(
			@RequestHeader(name = "X-Admin-Token", required = false) String providedToken,
			@org.springframework.web.bind.annotation.RequestBody AuditToggle request) {
		authorizeIlink(providedToken);
		audit.setEnabled(request != null && Boolean.TRUE.equals(request.enabled()));
		return Map.of("enabled", audit.isEnabled());
	}

	public record AuditView(boolean enabled, List<ConversationAuditService.AuditEntry> entries) { }
	public record AuditToggle(Boolean enabled) { }

	/** All Agent tasks are admin-visible and include the originating WeChat session/user identifier. */
	@GetMapping("/ops/agent-tasks")
	public List<AgentTask> agentTasks(@RequestHeader(name = "X-Admin-Token", required = false) String providedToken) {
		authorizeIlink(providedToken);
		return agentLoop.list(null);
	}

	@GetMapping("/ops/agent-tasks/{taskId}/trace")
	public List<AgentTraceEvent> agentTaskTrace(
			@RequestHeader(name = "X-Admin-Token", required = false) String providedToken,
			@PathVariable String taskId) {
		authorizeIlink(providedToken);
		return agentLoop.trace(taskId);
	}

	@PostMapping("/ops/agent-tasks/{taskId}/{action:pause|resume|cancel|retry}")
	public AgentTask controlAgentTask(
			@RequestHeader(name = "X-Admin-Token", required = false) String providedToken,
			@PathVariable String taskId, @PathVariable String action) {
		authorizeIlink(providedToken);
		return switch (action) {
			case "pause" -> agentLoop.pause(taskId);
			case "resume" -> agentLoop.resume(taskId);
			case "cancel" -> agentLoop.cancel(taskId);
			case "retry" -> agentLoop.retry(taskId);
			default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知 Agent 操作");
		};
	}

	@DeleteMapping("/ops/agent-tasks/{taskId}")
	public Map<String, Boolean> deleteAgentTask(
			@RequestHeader(name = "X-Admin-Token", required = false) String providedToken,
			@PathVariable String taskId) {
		authorizeIlink(providedToken);
		agentLoop.delete(taskId);
		return Map.of("deleted", true);
	}

	@PostMapping("/ops/agent-tasks/{taskId}/human-input")
	public AgentTask submitAgentHumanInput(
			@RequestHeader(name = "X-Admin-Token", required = false) String providedToken,
			@PathVariable String taskId,
			@org.springframework.web.bind.annotation.RequestBody AgentHumanInput request) {
		authorizeIlink(providedToken);
		return agentLoop.submitHumanInput(taskId, request == null ? null : request.input());
	}

	public record AgentHumanInput(String input) { }

	@GetMapping("/ops/conversations")
	public List<SqliteConversationStore.ConversationView> conversationContexts(
			@RequestHeader(name = "X-Admin-Token", required = false) String providedToken,
			@RequestParam String accountId, @RequestParam(defaultValue = "100") int limit) {
		authorizeIlink(providedToken);
		requiredIlinkRunner().accountView(accountId, false);
		return sqliteConversations.listByAccount(accountId, limit, memory.retentionCutoffMillis());
	}

	@DeleteMapping("/ops/conversations")
	public Map<String, Object> deleteConversationContext(
			@RequestHeader(name = "X-Admin-Token", required = false) String providedToken,
			@RequestParam String sessionId) {
		authorizeIlink(providedToken);
		memory.clear(sessionId);
		return Map.of("deleted", true, "sessionId", sessionId);
	}

	@PatchMapping("/ops/conversations")
	public Map<String, Object> updateConversationContext(
			@RequestHeader(name = "X-Admin-Token", required = false) String providedToken,
			@org.springframework.web.bind.annotation.RequestBody ConversationSummaryUpdate request) {
		authorizeIlink(providedToken);
		if (request == null || request.sessionId() == null || request.sessionId().isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sessionId is required");
		}
		if (!memory.updateSummary(request.sessionId(), request.summary())) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在，或 SQLite 当前不可用");
		}
		return Map.of("updated", true, "sessionId", request.sessionId());
	}

	@DeleteMapping("/ops/accounts/{accountId}/conversations")
	public Map<String, Object> deleteAccountConversationContexts(
			@RequestHeader(name = "X-Admin-Token", required = false) String providedToken,
			@PathVariable String accountId) {
		authorizeIlink(providedToken);
		requiredIlinkRunner().accountView(accountId, false);
		int deleted = memory.clearByAccount(accountId);
		return Map.of("deleted", true, "accountId", accountId, "count", deleted);
	}

	public record ConversationSummaryUpdate(String sessionId, String summary) { }

	@GetMapping("/ops/user-names")
	public List<UserNameView> userNames(
			@RequestHeader(name = "X-Admin-Token", required = false) String providedToken,
			@RequestParam String accountId, @RequestParam(defaultValue = "100") int limit) {
		authorizeIlink(providedToken);
		requiredIlinkRunner().accountView(accountId, false);
		LinkedHashSet<String> sessionIds = new LinkedHashSet<>();
		sqliteConversations.listByAccount(accountId, limit, memory.retentionCutoffMillis())
				.forEach(item -> sessionIds.add(item.sessionId()));
		// A permanent profile outlives short-lived conversation history. Include it
		// here so a refresh cannot make a successfully saved setting disappear.
		sessionIds.addAll(userNames.profileSessionIds(accountId));
		return sessionIds.stream()
				.limit(Math.max(1, Math.min(200, limit)))
				.map(sessionId -> {
					String configured = userNames.configuredName(sessionId);
					return new UserNameView(sessionId, userId(sessionId), configured,
							userNames.replyName(sessionId), !configured.isBlank());
				})
				.toList();
	}

	@PatchMapping("/ops/user-names")
	public UserNameView updateUserName(
			@RequestHeader(name = "X-Admin-Token", required = false) String providedToken,
			@org.springframework.web.bind.annotation.RequestBody UserNameUpdate request) {
		authorizeIlink(providedToken);
		if (request == null || request.sessionId() == null || request.sessionId().isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sessionId is required");
		}
		String accountId = accountId(request.sessionId());
		requiredIlinkRunner().accountView(accountId, false);
		String configured = userNames.update(request.sessionId(), request.displayName());
		return new UserNameView(request.sessionId(), userId(request.sessionId()), configured,
				userNames.replyName(request.sessionId()), !configured.isBlank());
	}

	private static String accountId(String sessionId) {
		int marker = sessionId.indexOf("::");
		return marker > 0 ? sessionId.substring(0, marker) : "default";
	}

	private static String userId(String sessionId) {
		int marker = sessionId.indexOf("::");
		return marker > 0 ? sessionId.substring(marker + 2) : sessionId;
	}

	public record UserNameUpdate(String sessionId, String displayName) { }
	public record UserNameView(String sessionId, String userId, String displayName,
			String effectiveName, boolean customized) { }

	@GetMapping("/ops/user-memories")
	public Map<String, String> userMemories(
			@RequestHeader(name = "X-Admin-Token", required = false) String providedToken,
			@RequestParam String sessionId) {
		authorizeIlink(providedToken);
		requiredIlinkRunner().accountView(accountId(sessionId), false);
		return userNames.memories(sessionId);
	}

	@PatchMapping("/ops/user-memories")
	public Map<String, Object> updateUserMemory(
			@RequestHeader(name = "X-Admin-Token", required = false) String providedToken,
			@org.springframework.web.bind.annotation.RequestBody UserMemoryUpdate request) {
		authorizeIlink(providedToken);
		if (request == null || request.sessionId() == null || request.sessionId().isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sessionId is required");
		}
		requiredIlinkRunner().accountView(accountId(request.sessionId()), false);
		String saved = userNames.updateMemory(request.sessionId(), request.key(), request.value());
		return Map.of("saved", !saved.isBlank(), "key", request.key(), "value", saved);
	}

	public record UserMemoryUpdate(String sessionId, String key, String value) { }


	@GetMapping("/status")
	public Status status(@RequestHeader(name = "X-Admin-Token", required = false) String providedToken) {
		authorize(providedToken);
		Status status = new Status(metrics.systemSnapshot(), registry.names(), metrics.snapshot(),
				memory.activeSessionCount(), reminders.pendingCount(), redisStatus(), knowledgeBase.root().toString());
		log.info("管理状态查询完成：工具数量={}，活跃会话={}，待执行提醒={}",
				status.tools().size(), status.activeConversationSessions(), status.pendingReminders());
		return status;
	}

	private void authorize(String provided) {
		if (!sessions.isConfigured()) return;
		if (sessions.isActive(currentSessionId())) return;
		if (!sessions.isValidToken(provided)) {
			log.warn("管理接口鉴权失败");
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "管理令牌无效");
		}
	}

	private String redisStatus() {
		if (redis == null || redis.getConnectionFactory() == null) return "unavailable";
		try (var connection = redis.getConnectionFactory().getConnection()) {
			String pong = connection.ping();
			return pong == null ? "unknown" : pong.toLowerCase(java.util.Locale.ROOT);
		} catch (Exception error) {
			return "down";
		}
	}

	public record Status(ToolMetricsService.SystemMetric system, List<String> tools,
			Map<String, ToolMetricsService.ToolMetric> toolMetrics, int activeConversationSessions,
			int pendingReminders, String redis, String knowledgeBasePath) { }
}
