package com.example.bot.bilibili;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.example.demo.BiliQrLogin;
import com.fasterxml.jackson.databind.JsonNode;

/** Manages one short-lived Bilibili QR login without ever exposing the cookie. */
@Service
public class BilibiliQrLoginService {
	private final BilibiliFavoritesService favorites;
	private final Supplier<BiliQrLogin> loginFactory;
	private final Executor executor;
	private final int timeoutSeconds;

	private final ConcurrentHashMap<String, LoginAttempt> attempts = new ConcurrentHashMap<>();

	@Autowired
	public BilibiliQrLoginService(BilibiliFavoritesService favorites,
			@Value("${bilibili.login-timeout-seconds:180}") int timeoutSeconds) {
		this(favorites, timeoutSeconds, BiliQrLogin::new, ForkJoinPool.commonPool());
	}

	BilibiliQrLoginService(BilibiliFavoritesService favorites, int timeoutSeconds,
			Supplier<BiliQrLogin> loginFactory, Executor executor) {
		this.favorites = favorites;
		this.timeoutSeconds = Math.max(30, Math.min(300, timeoutSeconds));
		this.loginFactory = loginFactory;
		this.executor = executor;
	}

	public synchronized LoginStatus start(String projectId) {
		String project = credentialId(projectId);
		LoginAttempt attempt = attempts.computeIfAbsent(project, ignored -> new LoginAttempt());
		if (attempt.state == State.WAITING_FOR_SCAN) return status(project);
		if (favorites.authenticated(project)) {
			attempt.state = State.AUTHENTICATED;
			attempt.message = "Bilibili 已登录";
			return status(project);
		}
		try {
			BiliQrLogin login = loginFactory.get();
			JsonNode payload = login.getUrl();
			attempt.qrContent = payload.path("data").path("url").asText("");
			if (attempt.qrContent.isBlank()) throw new IllegalStateException("Bilibili 未返回二维码内容");
			attempt.state = State.WAITING_FOR_SCAN;
			attempt.message = "请使用 Bilibili App 扫码并确认登录";
			CompletableFuture.supplyAsync(() -> poll(login, payload), executor)
					.whenComplete((cookie, error) -> complete(project, cookie, error));
			return status(project);
		} catch (Exception error) {
			attempt.state = State.FAILED;
			attempt.message = safeMessage(error);
			throw new IllegalStateException("申请 Bilibili 登录二维码失败：" + attempt.message, error);
		}
	}

	public synchronized LoginStatus status(String projectId) {
		String project = credentialId(projectId);
		LoginAttempt attempt = attempts.computeIfAbsent(project, ignored -> new LoginAttempt());
		if (favorites.authenticated(project) && attempt.state != State.WAITING_FOR_SCAN) {
			attempt.state = State.AUTHENTICATED;
			attempt.message = "Bilibili 已登录";
		}
		return new LoginStatus(attempt.state,
				!attempt.qrContent.isBlank() && attempt.state == State.WAITING_FOR_SCAN,
				attempt.message);
	}

	public synchronized String qrContent(String projectId) {
		String project = credentialId(projectId);
		LoginAttempt attempt = attempts.get(project);
		if (attempt == null || attempt.state != State.WAITING_FOR_SCAN || attempt.qrContent.isBlank()) {
			throw new IllegalStateException("当前没有待扫描的 Bilibili 登录二维码");
		}
		return attempt.qrContent;
	}

	private String poll(BiliQrLogin login, JsonNode payload) {
		try {
			return login.login(timeoutSeconds, payload);
		} catch (Exception error) {
			throw new IllegalStateException(error);
		}
	}

	private synchronized void complete(String projectId, String cookie, Throwable error) {
		LoginAttempt attempt = attempts.computeIfAbsent(projectId, ignored -> new LoginAttempt());
		attempt.qrContent = "";
		if (error != null) {
			attempt.state = State.FAILED;
			attempt.message = safeMessage(error);
			return;
		}
		try {
			favorites.useRuntimeCookie(projectId, cookie);
			attempt.state = State.AUTHENTICATED;
			attempt.message = "Bilibili 扫码登录成功；凭据仅属于当前机器人项目并保存在进程内存中";
		} catch (RuntimeException validationError) {
			attempt.state = State.FAILED;
			attempt.message = safeMessage(validationError);
		}
	}

	private static String credentialId(String sessionId) {
		return BilibiliFavoritesService.credentialId(sessionId);
	}

	private static String safeMessage(Throwable error) {
		Throwable current = error;
		while (current.getCause() != null) current = current.getCause();
		String value = current.getMessage();
		if (value == null || value.isBlank()) return "Bilibili 登录失败";
		return value.length() <= 200 ? value : value.substring(0, 200);
	}

	public enum State { IDLE, WAITING_FOR_SCAN, AUTHENTICATED, FAILED }
	public record LoginStatus(State state, boolean qrAvailable, String message) { }

	private static final class LoginAttempt {
		private State state = State.IDLE;
		private String qrContent = "";
		private String message = "尚未开始扫码登录";
	}
}
