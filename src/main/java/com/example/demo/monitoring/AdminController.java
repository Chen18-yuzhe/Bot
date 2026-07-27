package com.example.demo.monitoring;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.example.demo.ApplicationException;
import com.example.demo.chat.ConversationMemory;
import com.example.demo.chat.MediaContent;
import com.example.demo.document.DocumentSummaryService;
import com.example.demo.knowledge.KnowledgeBaseService;
import com.example.demo.ilink.IlinkChatRunner;
import com.example.demo.ilink.QrCodeSvgService;
import com.example.demo.reminder.ReminderService;
import com.example.demo.tools.AssistantToolRegistry;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
	private static final Logger log = LoggerFactory.getLogger(AdminController.class);
	private final String token;
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

	public AdminController(@Value("${assistant.admin.token:}") String token,
			AssistantToolRegistry registry, ToolMetricsService metrics, ConversationMemory memory,
			ReminderService reminders, KnowledgeBaseService knowledgeBase,
			ObjectProvider<StringRedisTemplate> redisProvider,
			ObjectProvider<IlinkChatRunner> ilinkRunner,
			QrCodeSvgService qrCodeSvgService, DocumentSummaryService documents, AdminSessionService sessions,
			ConversationAuditService audit) {
		this.token = token == null ? "" : token;
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
		int boundedHours = Math.max(1, Math.min(24, hours));
		try {
			Process process = new ProcessBuilder("journalctl", "-u", "demo-assistant", "--since", boundedHours + " hours ago",
					"--no-pager", "-o", "short-iso").redirectErrorStream(true).start();
			byte[] bytes = process.getInputStream().readNBytes(2_000_000);
			if (!process.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly();
			return ResponseEntity.ok().header("Content-Disposition", "inline; filename=demo-assistant-last-24h.log")
					.body(new String(bytes, StandardCharsets.UTF_8));
		} catch (Exception error) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "无法读取服务日志：" + error.getMessage());
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
	@GetMapping("/ilink/accounts")
	public List<IlinkChatRunner.AccountView> ilinkAccounts(
			@RequestHeader(name = "X-Admin-Token", required = false) String providedToken) {
		authorizeIlink(providedToken);
		return requiredIlinkRunner().accountViews();
	}

	@PostMapping("/ilink/accounts")
	public IlinkChatRunner.AccountView createIlinkAccount(
			@RequestHeader(name = "X-Admin-Token", required = false) String providedToken) {
		authorizeIlink(providedToken);
		return requiredIlinkRunner().createAccount();
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
		if (token.isBlank()) {
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
		if (token.isBlank()) return;
		byte[] expected = token.getBytes(StandardCharsets.UTF_8);
		byte[] actual = (provided == null ? "" : provided).getBytes(StandardCharsets.UTF_8);
		if (!MessageDigest.isEqual(expected, actual)) {
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
