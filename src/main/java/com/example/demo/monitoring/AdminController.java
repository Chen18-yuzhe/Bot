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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.example.demo.chat.ConversationMemory;
import com.example.demo.knowledge.KnowledgeBaseService;
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

	public AdminController(@Value("${assistant.admin.token:}") String token,
			AssistantToolRegistry registry, ToolMetricsService metrics, ConversationMemory memory,
			ReminderService reminders, KnowledgeBaseService knowledgeBase,
			ObjectProvider<StringRedisTemplate> redisProvider) {
		this.token = token == null ? "" : token;
		this.registry = registry;
		this.metrics = metrics;
		this.memory = memory;
		this.reminders = reminders;
		this.knowledgeBase = knowledgeBase;
		this.redis = redisProvider.getIfAvailable();
	}

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
