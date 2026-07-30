package com.example.bot.bilibili;

import java.util.concurrent.CompletableFuture;
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

	private State state = State.IDLE;
	private String qrContent = "";
	private String message = "尚未开始扫码登录";

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

	public synchronized LoginStatus start() {
		if (state == State.WAITING_FOR_SCAN) return status();
		if (favorites.authenticated()) {
			state = State.AUTHENTICATED;
			message = "Bilibili 已登录";
			return status();
		}
		try {
			BiliQrLogin login = loginFactory.get();
			JsonNode payload = login.getUrl();
			qrContent = payload.path("data").path("url").asText("");
			if (qrContent.isBlank()) throw new IllegalStateException("Bilibili 未返回二维码内容");
			state = State.WAITING_FOR_SCAN;
			message = "请使用 Bilibili App 扫码并确认登录";
			CompletableFuture.supplyAsync(() -> poll(login, payload), executor)
					.whenComplete(this::complete);
			return status();
		} catch (Exception error) {
			state = State.FAILED;
			message = safeMessage(error);
			throw new IllegalStateException("申请 Bilibili 登录二维码失败：" + message, error);
		}
	}

	public synchronized LoginStatus status() {
		if (favorites.authenticated() && state != State.WAITING_FOR_SCAN) {
			state = State.AUTHENTICATED;
			message = "Bilibili 已登录";
		}
		return new LoginStatus(state, !qrContent.isBlank() && state == State.WAITING_FOR_SCAN, message);
	}

	public synchronized String qrContent() {
		if (state != State.WAITING_FOR_SCAN || qrContent.isBlank()) {
			throw new IllegalStateException("当前没有待扫描的 Bilibili 登录二维码");
		}
		return qrContent;
	}

	private String poll(BiliQrLogin login, JsonNode payload) {
		try {
			return login.login(timeoutSeconds, payload);
		} catch (Exception error) {
			throw new IllegalStateException(error);
		}
	}

	private synchronized void complete(String cookie, Throwable error) {
		qrContent = "";
		if (error != null) {
			state = State.FAILED;
			message = safeMessage(error);
			return;
		}
		try {
			favorites.useRuntimeCookie(cookie);
			state = State.AUTHENTICATED;
			message = "Bilibili 扫码登录成功；Cookie 仅保存在当前进程内存中";
		} catch (RuntimeException validationError) {
			state = State.FAILED;
			message = safeMessage(validationError);
		}
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
}
