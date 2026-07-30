package com.example.bot.bilibili;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.example.bot.ilink.QrCodeSvgService;
import com.example.bot.monitoring.AdminSessionService;

/** Private, loopback-only endpoints for Bilibili QR authentication. */
@RestController
@RequestMapping("/api/admin/bilibili")
public class BilibiliAdminController {
	private final BilibiliQrLoginService login;
	private final QrCodeSvgService qrCodes;
	private final AdminSessionService sessions;

	public BilibiliAdminController(BilibiliQrLoginService login, QrCodeSvgService qrCodes,
			AdminSessionService sessions) {
		this.login = login;
		this.qrCodes = qrCodes;
		this.sessions = sessions;
	}

	@PostMapping("/login")
	public BilibiliQrLoginService.LoginStatus start(
			@RequestHeader(name = "X-Admin-Token", required = false) String token,
			@CookieValue(name = "admin_session", required = false) String sessionId) {
		authorize(token, sessionId);
		return login.start();
	}

	@GetMapping("/status")
	public BilibiliQrLoginService.LoginStatus status(
			@RequestHeader(name = "X-Admin-Token", required = false) String token,
			@CookieValue(name = "admin_session", required = false) String sessionId) {
		authorize(token, sessionId);
		return login.status();
	}

	@GetMapping(value = "/qrcode.svg", produces = "image/svg+xml")
	public ResponseEntity<String> qrCode(
			@RequestHeader(name = "X-Admin-Token", required = false) String token,
			@CookieValue(name = "admin_session", required = false) String sessionId) {
		authorize(token, sessionId);
		return ResponseEntity.ok().contentType(MediaType.valueOf("image/svg+xml"))
				.header("Cache-Control", "no-store")
				.body(qrCodes.render(login.qrContent()));
	}

	private void authorize(String token, String sessionId) {
		if (!sessions.isConfigured()) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
					"扫码登录前必须配置 ADMIN_API_TOKEN");
		}
		if (sessions.isActive(sessionId) || sessions.isValidToken(token)) return;
		throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "管理令牌无效");
	}
}
