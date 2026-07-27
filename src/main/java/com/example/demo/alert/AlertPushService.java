package com.example.demo.alert;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Closes the alert loop: polls StockTrace's undelivered alert events, pushes
 * them to WeChat and receipts each one only after the send succeeded, so a
 * failed push is retried on the next poll (at-least-once delivery).
 *
 * StockTrace is a single-owner system, so all events go to one push target:
 * the WeChat session that most recently managed alerts. The target survives
 * restarts in Redis and can be pre-seeded via configuration.
 */
@Service
public class AlertPushService {
	private static final Logger log = LoggerFactory.getLogger(AlertPushService.class);
	private static final DateTimeFormatter TRIGGER_TIME =
			DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"));
	private static final int MAX_EVENTS_PER_POLL = 20;

	private final StockAlertService alertService;
	private final StringRedisTemplate redis;
	private final String targetKey;
	private final AtomicBoolean redisWarningLogged = new AtomicBoolean();
	private volatile String localTarget;
	private volatile PushSender sender = (sessionId, text) -> false;

	public AlertPushService(StockAlertService alertService, ObjectProvider<StringRedisTemplate> redisProvider,
			@Value("${assistant.alerts.push-session-id:}") String configuredTarget,
			@Value("${assistant.alerts.redis-key-prefix:demo-assistant:alert:}") String prefix) {
		this.alertService = alertService;
		this.redis = redisProvider.getIfAvailable();
		this.targetKey = (prefix == null || prefix.isBlank() ? "demo-assistant:alert:" : prefix.trim())
				+ "push-session";
		this.localTarget = configuredTarget == null || configuredTarget.isBlank() ? null : configuredTarget.trim();
	}

	public void setSender(PushSender sender) {
		this.sender = sender == null ? (sessionId, text) -> false : sender;
	}

	/** Called on every successful alert-tool action: that user owns future pushes. */
	public void registerPushTarget(String sessionId) {
		if (sessionId == null || sessionId.isBlank()) return;
		localTarget = sessionId;
		if (redis == null) return;
		try {
			redis.opsForValue().set(targetKey, sessionId);
			redisWarningLogged.set(false);
		} catch (Exception error) {
			warnRedis("保存预警推送目标", error);
		}
	}

	String pushTarget() {
		if (redis != null) {
			try {
				String stored = redis.opsForValue().get(targetKey);
				redisWarningLogged.set(false);
				if (stored != null && !stored.isBlank()) return stored;
			} catch (Exception error) {
				warnRedis("读取预警推送目标", error);
			}
		}
		return localTarget;
	}

	@Scheduled(fixedDelayString = "${stock-trace.alert-poll-ms:60000}")
	public void deliverPendingAlerts() {
		if (!alertService.isConfigured()) return;
		String target = pushTarget();
		if (target == null || target.isBlank()) return;
		List<StockAlertService.AlertEvent> events;
		try {
			events = alertService.pendingEvents();
		} catch (RuntimeException error) {
			log.warn("拉取待推送预警事件失败：{}", error.getMessage());
			return;
		}
		if (events.isEmpty()) return;
		Map<String, StockAlertService.AlertRule> rules = ruleIndex();
		int delivered = 0;
		for (StockAlertService.AlertEvent event : events.subList(0, Math.min(events.size(), MAX_EVENTS_PER_POLL))) {
			String message = renderEvent(event, rules.get(event.alertId()));
			boolean sent;
			try {
				sent = sender.push(target, message);
			} catch (RuntimeException error) {
				sent = false;
				log.warn("预警推送异常：事件={}，原因={}", event.id(), error.getMessage());
			}
			if (!sent) {
				// Keep the event pending; the next poll retries it. Later
				// events stay queued too so the owner never misses a trigger.
				log.warn("预警推送失败，等待下轮重试：事件={}", event.id());
				break;
			}
			try {
				alertService.markDelivered(event.id());
			} catch (RuntimeException error) {
				// The user already saw the message; a receipt failure means one
				// possible duplicate next round, which beats losing the alert.
				log.warn("预警回执失败，可能重复推送一次：事件={}，原因={}", event.id(), error.getMessage());
			}
			delivered++;
		}
		if (delivered > 0) log.info("预警推送完成：本轮送达 {} 条，目标会话={}", delivered, target);
	}

	private Map<String, StockAlertService.AlertRule> ruleIndex() {
		try {
			Map<String, StockAlertService.AlertRule> index = new HashMap<>();
			for (StockAlertService.AlertRule rule : alertService.listAlerts()) index.put(rule.id(), rule);
			return index;
		} catch (RuntimeException error) {
			log.warn("读取预警规则失败，将使用事件内的基础信息推送：{}", error.getMessage());
			return Map.of();
		}
	}

	static String renderEvent(StockAlertService.AlertEvent event, StockAlertService.AlertRule rule) {
		String stockCode = rule != null ? rule.stockCode() : event.payload().path("stock_code").asText("");
		String subject = stockCode.isBlank() ? "自选标的" : stockCode;
		String condition = rule != null
				? ruleLabel(rule.ruleType()) + " " + trimNumber(rule.threshold())
				: "触发阈值 " + trimNumber(event.threshold());
		StringBuilder text = new StringBuilder("🔔 股价预警：").append(subject).append(' ').append(condition);
		if (!event.observedValue().isBlank()) {
			text.append("（当前 ").append(trimNumber(event.observedValue())).append("）");
		}
		if (!event.triggeredAt().isBlank()) {
			try {
				text.append("\n触发时间：").append(TRIGGER_TIME.format(Instant.parse(event.triggeredAt())));
			} catch (RuntimeException ignored) {
				text.append("\n触发时间：").append(event.triggeredAt());
			}
		}
		text.append("\n仅供研究参考，不构成投资建议。");
		return text.toString();
	}

	public static String ruleLabel(String ruleType) {
		return switch (ruleType) {
			case "PRICE_ABOVE" -> "股价上穿";
			case "PRICE_BELOW" -> "股价下破";
			case "RETURN_ABOVE" -> "持仓收益率达到";
			case "LOSS_ABOVE" -> "持仓亏损达到";
			case "DAILY_CHANGE_ABOVE" -> "当日涨跌幅达到";
			case "TAKE_PROFIT" -> "止盈价触达";
			case "STOP_LOSS" -> "止损价触达";
			default -> "预警条件触发";
		};
	}

	/** "1400.000000" from numeric(20,6) columns reads better as "1400". */
	public static String trimNumber(String value) {
		if (value == null || value.isBlank() || !value.contains(".")) return value == null ? "" : value;
		String trimmed = value.replaceAll("0+$", "").replaceAll("\\.$", "");
		return trimmed.isBlank() ? "0" : trimmed;
	}

	private void warnRedis(String action, Exception error) {
		if (redisWarningLogged.compareAndSet(false, true)) {
			log.warn("Redis {}失败，预警推送目标暂时只保存在内存：{}", action, error.getMessage());
		}
	}

	@FunctionalInterface
	public interface PushSender {
		boolean push(String sessionId, String text);
	}
}
