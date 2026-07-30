package com.example.demo.reminder;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class ReminderService {
	private static final Logger log = LoggerFactory.getLogger(ReminderService.class);
	private static final int DELIVERY_BATCH = 50;
	private final ConcurrentHashMap<String, Reminder> local = new ConcurrentHashMap<>();
	private final ObjectMapper objectMapper;
	private final StringRedisTemplate redis;
	private final boolean enabled;
	private final String prefix;
	private final AtomicBoolean redisWarningLogged = new AtomicBoolean();
	private volatile ReminderNotifier notifier = reminder -> false;

	public ReminderService(ObjectMapper objectMapper, ObjectProvider<StringRedisTemplate> redisProvider,
			@Value("${assistant.reminders.enabled:true}") boolean enabled,
			@Value("${assistant.reminders.redis-key-prefix:demo-assistant:reminder:}") String prefix) {
		this.objectMapper = objectMapper;
		this.redis = redisProvider.getIfAvailable();
		this.enabled = enabled;
		this.prefix = prefix == null || prefix.isBlank() ? "demo-assistant:reminder:" : prefix.trim();
	}

	public void setNotifier(ReminderNotifier notifier) {
		this.notifier = notifier == null ? reminder -> false : notifier;
	}

	public Reminder create(String userId, String content, ZonedDateTime triggerAt, Recurrence recurrence) {
		return create(userId, content, triggerAt, recurrence, null);
	}

	public Reminder create(String userId, String content, ZonedDateTime triggerAt, Recurrence recurrence,
			String type) {
		if (!enabled) throw new IllegalStateException("提醒功能未启用");
		if (userId == null || userId.isBlank()) throw new IllegalArgumentException("无法识别提醒所属用户");
		if (content == null || content.isBlank()) throw new IllegalArgumentException("提醒内容不能为空");
		if (triggerAt == null || triggerAt.toInstant().isBefore(Instant.now().minusSeconds(5))) {
			throw new IllegalArgumentException("提醒时间必须晚于当前时间");
		}
		Reminder reminder = new Reminder(UUID.randomUUID().toString(), userId, content.trim(),
				triggerAt.toInstant().toEpochMilli(), recurrence == null ? Recurrence.NONE : recurrence,
				System.currentTimeMillis(), triggerAt.toInstant().toEpochMilli(), type);
		local.put(reminder.id(), reminder);
		writeRedis(reminder, reminder.triggerAtMs());
		log.info("提醒已创建：id={}，用户={}，触发时间={}，重复={}", reminder.id(), userId,
				triggerAt, reminder.recurrence());
		return reminder;
	}

	public boolean cancel(String userId, String id) {
		Reminder reminder = find(id);
		if (reminder == null || !reminder.userId().equals(userId)) return false;
		local.remove(id);
		deleteRedis(id);
		log.info("提醒已取消：id={}，用户={}", id, userId);
		return true;
	}

	public List<Reminder> list(String userId) {
		loadRedisItems();
		return local.values().stream().filter(item -> item.userId().equals(userId))
				.sorted(Comparator.comparingLong(Reminder::triggerAtMs)).toList();
	}

	public int pendingCount() {
		loadRedisItems();
		return local.size();
	}

	/** Deletes all reminders associated with an account-scoped conversation id. */
	public int deleteByUserIdPrefix(String sessionPrefix) {
		if (sessionPrefix == null || sessionPrefix.isBlank()) return 0;
		loadRedisItems();
		List<String> ids = local.values().stream()
				.filter(item -> item.userId().startsWith(sessionPrefix))
				.map(Reminder::id)
				.toList();
		ids.forEach(id -> {
			local.remove(id);
			deleteRedis(id);
		});
		if (!ids.isEmpty()) {
			log.info("已删除账户关联提醒：数量={}", ids.size());
		}
		return ids.size();
	}

	@Scheduled(fixedDelayString = "${assistant.reminders.poll-ms:10000}")
	public void deliverDue() {
		if (!enabled) return;
		long now = System.currentTimeMillis();
		for (String id : dueIds(now)) {
			Reminder reminder = find(id);
			if (reminder == null) {
				deleteRedis(id);
				continue;
			}
			boolean delivered;
			try {
				delivered = notifier.deliver(reminder);
			} catch (RuntimeException error) {
				delivered = false;
				log.warn("提醒投递异常：id={}，原因={}", id, error.getMessage());
			}
			if (!delivered) {
				long retryAt = now + 60_000L;
				Reminder retry = new Reminder(reminder.id(), reminder.userId(), reminder.content(),
						reminder.triggerAtMs(), reminder.recurrence(), reminder.createdAtMs(), retryAt,
						reminder.type());
				local.put(id, retry);
				writeRedis(retry, retryAt);
				log.warn("提醒投递失败，60 秒后重试：id={}，用户={}", id, reminder.userId());
				continue;
			}
			if (reminder.recurrence() == Recurrence.NONE) {
				local.remove(id);
				deleteRedis(id);
			} else {
				ZonedDateTime previous = Instant.ofEpochMilli(reminder.triggerAtMs()).atZone(ZoneId.of("Asia/Shanghai"));
				ZonedDateTime next = reminder.recurrence() == Recurrence.DAILY ? previous.plusDays(1) : previous.plusWeeks(1);
				while (next.toInstant().toEpochMilli() <= now) {
					next = reminder.recurrence() == Recurrence.DAILY ? next.plusDays(1) : next.plusWeeks(1);
				}
				Reminder updated = new Reminder(reminder.id(), reminder.userId(), reminder.content(),
						next.toInstant().toEpochMilli(), reminder.recurrence(), reminder.createdAtMs(),
						next.toInstant().toEpochMilli(), reminder.type());
				local.put(id, updated);
				writeRedis(updated, updated.triggerAtMs());
			}
			log.info("提醒投递成功：id={}，用户={}，重复={}", id, reminder.userId(), reminder.recurrence());
		}
	}

	private Set<String> dueIds(long now) {
		Set<String> combined = new java.util.LinkedHashSet<>();
		if (redis != null) {
			try {
				Set<String> ids = redis.opsForZSet().rangeByScore(dueKey(), 0, now, 0, DELIVERY_BATCH);
				redisWarningLogged.set(false);
				if (ids != null) combined.addAll(ids);
			} catch (Exception error) {
				warnRedis("查询到期提醒", error);
			}
		}
		local.values().stream().filter(item -> item.nextAttemptAtMs() <= now).limit(DELIVERY_BATCH)
				.map(Reminder::id).forEach(combined::add);
		return combined;
	}

	private Reminder find(String id) {
		if (id == null || id.isBlank()) return null;
		Reminder reminder = local.get(id);
		if (reminder != null || redis == null) return reminder;
		try {
			String json = redis.opsForValue().get(itemKey(id));
			if (json == null) return null;
			Reminder loaded = objectMapper.readValue(json, Reminder.class);
			local.put(id, loaded);
			redisWarningLogged.set(false);
			return loaded;
		} catch (Exception error) {
			warnRedis("读取提醒", error);
			return null;
		}
	}

	private void loadRedisItems() {
		if (redis == null) return;
		try {
			Set<String> ids = redis.opsForZSet().range(dueKey(), 0, -1);
			if (ids != null) ids.forEach(this::find);
			redisWarningLogged.set(false);
		} catch (Exception error) {
			warnRedis("读取提醒列表", error);
		}
	}

	private void writeRedis(Reminder reminder, long score) {
		if (redis == null) return;
		try {
			redis.opsForValue().set(itemKey(reminder.id()), objectMapper.writeValueAsString(reminder));
			redis.opsForZSet().add(dueKey(), reminder.id(), score);
			redisWarningLogged.set(false);
		} catch (Exception error) {
			warnRedis("保存提醒", error);
		}
	}

	private void deleteRedis(String id) {
		if (redis == null) return;
		try {
			redis.opsForZSet().remove(dueKey(), id);
			redis.delete(itemKey(id));
			redisWarningLogged.set(false);
		} catch (Exception error) {
			warnRedis("删除提醒", error);
		}
	}

	private String dueKey() { return prefix + "due"; }
	private String itemKey(String id) { return prefix + "item:" + id; }
	private void warnRedis(String action, Exception error) {
		if (redisWarningLogged.compareAndSet(false, true)) {
			log.warn("Redis {}失败，提醒功能暂时使用内存：{}", action, error.getMessage());
		}
	}

	public enum Recurrence { NONE, DAILY, WEEKLY }
	/** type 为 null 表示普通文字提醒；历史 Redis 数据缺少该字段时反序列化为 null。 */
	public record Reminder(String id, String userId, String content, long triggerAtMs,
			Recurrence recurrence, long createdAtMs, long nextAttemptAtMs, String type) {
	}
	@FunctionalInterface public interface ReminderNotifier { boolean deliver(Reminder reminder); }
}
