package com.example.demo.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 任务持久化：Redis 优先，本地 JSON 文件兜底，进程内存做写穿缓存。
 * 与 ReminderService 相同的降级模式：Redis 故障时任务不丢、重启可恢复。
 */
@Component
public class AgentTaskStore {
	private static final Logger log = LoggerFactory.getLogger(AgentTaskStore.class);
	private static final int DISPATCH_BATCH = 20;

	private final ObjectMapper objectMapper;
	private final StringRedisTemplate redis;
	private final String prefix;
	private final Path storageFile;
	private final ConcurrentHashMap<String, AgentTask> cache = new ConcurrentHashMap<>();
	private final AtomicBoolean redisWarningLogged = new AtomicBoolean();

	public AgentTaskStore(ObjectMapper objectMapper, ObjectProvider<StringRedisTemplate> redisProvider,
			@Value("${assistant.agent.redis-key-prefix:demo-assistant:agent:}") String prefix,
			@Value("${assistant.agent.storage-file:/var/lib/wechat-agent-platform/agent-tasks.json}") String storageFile) {
		this.objectMapper = objectMapper;
		this.redis = redisProvider.getIfAvailable();
		this.prefix = prefix == null || prefix.isBlank() ? "demo-assistant:agent:" : prefix.trim();
		this.storageFile = Path.of(storageFile);
		loadFile();
	}

	public synchronized void save(AgentTask task) {
		task.setUpdatedAtMs(System.currentTimeMillis());
		cache.put(task.getId(), task);
		writeRedis(task);
		writeFile();
	}

	public AgentTask find(String id) {
		if (id == null || id.isBlank()) return null;
		AgentTask cached = cache.get(id);
		if (cached != null) return cached;
		if (redis != null) {
			try {
				String json = redis.opsForValue().get(taskKey(id));
				redisWarningLogged.set(false);
				if (json != null) {
					AgentTask loaded = objectMapper.readValue(json, AgentTask.class);
					cache.put(id, loaded);
					return loaded;
				}
			} catch (Exception error) {
				warnRedis("读取任务", error);
			}
		}
		return cache.get(id);
	}

	public List<AgentTask> list() {
		loadRedisIndex();
		return cache.values().stream()
				.sorted(Comparator.comparingLong(AgentTask::getUpdatedAtMs).reversed())
				.toList();
	}

	public List<AgentTask> listBySession(String sessionId) {
		return list().stream().filter(task -> sessionId.equals(task.getSessionId())).toList();
	}

	/** 到期的 PENDING 任务：调度器据此派发执行。 */
	public List<AgentTask> listDuePending(long nowMs) {
		List<AgentTask> due = new ArrayList<>();
		Set<String> ids = new java.util.LinkedHashSet<>();
		if (redis != null) {
			try {
				Set<String> redisIds = redis.opsForZSet().rangeByScore(pendingKey(), 0, nowMs, 0, DISPATCH_BATCH);
				redisWarningLogged.set(false);
				if (redisIds != null) ids.addAll(redisIds);
			} catch (Exception error) {
				warnRedis("查询到期任务", error);
			}
		}
		cache.values().stream()
				.filter(task -> task.getStatus() == AgentTaskStatus.PENDING && task.getWakeAtMs() <= nowMs)
				.limit(DISPATCH_BATCH)
				.forEach(task -> ids.add(task.getId()));
		for (String id : ids) {
			AgentTask task = find(id);
			if (task != null && task.getStatus() == AgentTaskStatus.PENDING && task.getWakeAtMs() <= nowMs) {
				due.add(task);
			}
		}
		return due;
	}

	/** 服务重启后找回中断的任务（RUNNING 但已没有执行线程）。 */
	public List<AgentTask> listInterrupted() {
		loadRedisIndex();
		return cache.values().stream().filter(task -> task.getStatus() == AgentTaskStatus.RUNNING).toList();
	}

	public synchronized void delete(String id) {
		cache.remove(id);
		if (redis != null) {
			try {
				redis.delete(taskKey(id));
				redis.opsForZSet().remove(pendingKey(), id);
				redis.opsForZSet().remove(indexKey(), id);
				redisWarningLogged.set(false);
			} catch (Exception error) {
				warnRedis("删除任务", error);
			}
		}
		writeFile();
	}

	private void writeRedis(AgentTask task) {
		if (redis == null) return;
		try {
			redis.opsForValue().set(taskKey(task.getId()), objectMapper.writeValueAsString(task));
			redis.opsForZSet().add(indexKey(), task.getId(), task.getUpdatedAtMs());
			if (task.getStatus() == AgentTaskStatus.PENDING) {
				redis.opsForZSet().add(pendingKey(), task.getId(), task.getWakeAtMs());
			} else {
				redis.opsForZSet().remove(pendingKey(), task.getId());
			}
			redisWarningLogged.set(false);
		} catch (Exception error) {
			warnRedis("保存任务", error);
		}
	}

	private void loadRedisIndex() {
		if (redis == null) return;
		try {
			Set<String> ids = redis.opsForZSet().range(indexKey(), 0, -1);
			redisWarningLogged.set(false);
			if (ids == null) return;
			for (String id : ids) {
				if (!cache.containsKey(id)) find(id);
			}
		} catch (Exception error) {
			warnRedis("读取任务索引", error);
		}
	}

	private void loadFile() {
		if (!Files.exists(storageFile)) return;
		try {
			String json = Files.readString(storageFile, StandardCharsets.UTF_8);
			if (json.isBlank()) return;
			Map<String, AgentTask> loaded = objectMapper.readValue(json,
					new TypeReference<LinkedHashMap<String, AgentTask>>() {
					});
			cache.putAll(loaded);
			log.info("已从本地文件恢复 Agent 任务：数量={}", loaded.size());
		} catch (Exception error) {
			log.warn("读取 Agent 任务文件失败，忽略本地缓存：{}", error.getMessage());
		}
	}

	private void writeFile() {
		try {
			Path parent = storageFile.getParent();
			if (parent != null) Files.createDirectories(parent);
			objectMapper.writerWithDefaultPrettyPrinter().writeValue(storageFile.toFile(), cache);
		} catch (IOException error) {
			log.warn("写入 Agent 任务文件失败：{}", error.getMessage());
		}
	}

	private String taskKey(String id) { return prefix + "task:" + id; }
	private String pendingKey() { return prefix + "pending"; }
	private String indexKey() { return prefix + "index"; }

	private void warnRedis(String action, Exception error) {
		if (redisWarningLogged.compareAndSet(false, true)) {
			log.warn("Redis {}失败，Agent 任务暂时使用内存与本地文件：{}", action, error.getMessage());
		}
	}
}
