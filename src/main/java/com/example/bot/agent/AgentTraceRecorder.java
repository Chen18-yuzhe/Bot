package com.example.bot.agent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * trace 持久化：优先写入 Redis 列表（封顶保留最近 N 条，7 天过期），
 * Redis 不可用时降级到进程内存，与项目现有的降级模式一致。
 */
@Component
public class AgentTraceRecorder {
	private static final Logger log = LoggerFactory.getLogger(AgentTraceRecorder.class);
	private static final Duration TRACE_TTL = Duration.ofDays(7);

	private final ObjectMapper objectMapper;
	private final StringRedisTemplate redis;
	private final String prefix;
	private final int maxEvents;
	private final ConcurrentHashMap<String, CopyOnWriteArrayList<AgentTraceEvent>> local = new ConcurrentHashMap<>();
	private final AtomicBoolean redisWarningLogged = new AtomicBoolean();

	public AgentTraceRecorder(ObjectMapper objectMapper, ObjectProvider<StringRedisTemplate> redisProvider,
			@Value("${assistant.agent.redis-key-prefix:bot:agent:}") String prefix,
			@Value("${assistant.agent.trace-max-events:500}") int maxEvents) {
		this.objectMapper = objectMapper;
		this.redis = redisProvider.getIfAvailable();
		this.prefix = prefix == null || prefix.isBlank() ? "bot:agent:" : prefix.trim();
		this.maxEvents = Math.max(50, maxEvents);
	}

	public void record(AgentTraceEvent event) {
		local.computeIfAbsent(event.taskId(), key -> new CopyOnWriteArrayList<>()).add(event);
		CopyOnWriteArrayList<AgentTraceEvent> events = local.get(event.taskId());
		while (events.size() > maxEvents) events.remove(0);
		if (redis == null) return;
		try {
			String key = traceKey(event.taskId());
			redis.opsForList().rightPush(key, objectMapper.writeValueAsString(event));
			redis.opsForList().trim(key, -maxEvents, -1);
			redis.expire(key, TRACE_TTL);
			redisWarningLogged.set(false);
		} catch (Exception error) {
			warnRedis("写入 trace", error);
		}
	}

	public List<AgentTraceEvent> trace(String taskId) {
		if (redis != null) {
			try {
				List<String> jsonList = redis.opsForList().range(traceKey(taskId), 0, -1);
				redisWarningLogged.set(false);
				if (jsonList != null && !jsonList.isEmpty()) {
					List<AgentTraceEvent> events = new ArrayList<>();
					for (String json : jsonList) {
						events.add(objectMapper.readValue(json, AgentTraceEvent.class));
					}
					return events;
				}
			} catch (Exception error) {
				warnRedis("读取 trace", error);
			}
		}
		return List.copyOf(local.getOrDefault(taskId, new CopyOnWriteArrayList<>()));
	}

	public void clear(String taskId) {
		local.remove(taskId);
		if (redis != null) {
			try {
				redis.delete(traceKey(taskId));
				redisWarningLogged.set(false);
			} catch (Exception error) {
				warnRedis("删除 trace", error);
			}
		}
	}

	private String traceKey(String taskId) {
		return prefix + "trace:" + taskId;
	}

	private void warnRedis(String action, Exception error) {
		if (redisWarningLogged.compareAndSet(false, true)) {
			log.warn("Redis {}失败，Agent trace 暂时只保留在内存：{}", action, error.getMessage());
		}
	}
}
