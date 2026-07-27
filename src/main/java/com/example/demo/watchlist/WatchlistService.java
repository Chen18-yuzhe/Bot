package com.example.demo.watchlist;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Per-WeChat-user watchlist. Redis is the source of truth so the list survives
 * restarts; the in-memory copy doubles as a same-process cache and as the
 * degraded store while Redis is down (same pattern as ReminderService).
 */
@Service
public class WatchlistService {
	private static final Logger log = LoggerFactory.getLogger(WatchlistService.class);
	private static final Pattern STOCK_CODE = Pattern.compile("^\\d{6}$");

	private final StringRedisTemplate redis;
	private final String prefix;
	private final int maxSymbols;
	private final ConcurrentHashMap<String, Set<String>> local = new ConcurrentHashMap<>();
	private final AtomicBoolean redisWarningLogged = new AtomicBoolean();

	public WatchlistService(ObjectProvider<StringRedisTemplate> redisProvider,
			@Value("${assistant.watchlist.redis-key-prefix:demo-assistant:watchlist:}") String prefix,
			@Value("${assistant.watchlist.max-symbols:20}") int maxSymbols) {
		this.redis = redisProvider.getIfAvailable();
		this.prefix = prefix == null || prefix.isBlank() ? "demo-assistant:watchlist:" : prefix.trim();
		this.maxSymbols = Math.max(1, Math.min(100, maxSymbols));
	}

	/** Adds one symbol; returns false when it was already present. */
	public boolean add(String sessionId, String symbol) {
		String code = normalize(symbol);
		Set<String> symbols = load(sessionId);
		if (symbols.contains(code)) return false;
		if (symbols.size() >= maxSymbols) {
			throw new IllegalArgumentException("自选股最多保存 " + maxSymbols + " 只，请先移除一些再添加");
		}
		symbols.add(code);
		store(sessionId, symbols);
		return true;
	}

	/** Removes one symbol; returns false when it was not on the list. */
	public boolean remove(String sessionId, String symbol) {
		String code = normalize(symbol);
		Set<String> symbols = load(sessionId);
		if (!symbols.remove(code)) return false;
		store(sessionId, symbols);
		return true;
	}

	public List<String> list(String sessionId) {
		return load(sessionId).stream().sorted().toList();
	}

	static String normalize(String symbol) {
		String value = symbol == null ? "" : symbol.trim();
		int dot = value.indexOf('.');
		if (dot > 0) value = value.substring(0, dot);
		if (!STOCK_CODE.matcher(value).matches()) {
			throw new IllegalArgumentException("股票代码必须是 6 位 A 股代码，例如 600519");
		}
		return value;
	}

	private Set<String> load(String sessionId) {
		if (sessionId == null || sessionId.isBlank()) {
			throw new IllegalArgumentException("无法识别自选股所属用户");
		}
		Set<String> symbols = local.computeIfAbsent(sessionId, ignored -> ConcurrentHashMap.newKeySet());
		if (redis != null) {
			try {
				Set<String> stored = redis.opsForSet().members(key(sessionId));
				redisWarningLogged.set(false);
				if (stored != null) {
					// Redis is the source of truth once reachable again.
					symbols.clear();
					symbols.addAll(stored);
				}
			} catch (Exception error) {
				warnRedis("读取自选股", error);
			}
		}
		return symbols;
	}

	private void store(String sessionId, Set<String> symbols) {
		if (redis == null) return;
		try {
			String key = key(sessionId);
			redis.delete(key);
			if (!symbols.isEmpty()) {
				redis.opsForSet().add(key, symbols.toArray(String[]::new));
			}
			redisWarningLogged.set(false);
		} catch (Exception error) {
			warnRedis("保存自选股", error);
		}
	}

	private String key(String sessionId) {
		return prefix + sessionId;
	}

	private void warnRedis(String action, Exception error) {
		if (redisWarningLogged.compareAndSet(false, true)) {
			log.warn("Redis {}失败，自选股暂时使用内存：{}", action, error.getMessage());
		}
	}
}
