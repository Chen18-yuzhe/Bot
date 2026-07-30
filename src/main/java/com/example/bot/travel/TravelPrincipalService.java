package com.example.bot.travel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.example.bot.ApplicationException;

/** Validates a short-lived token issued only by the server-side WeChat OAuth adapter. */
@Service
public class TravelPrincipalService {
	private final boolean demoEnabled;
	private final String demoToken;
	private final String signingKey;

	public TravelPrincipalService(@Value("${travel.integration.demo-enabled:false}") boolean demoEnabled,
			@Value("${travel.auth.demo-token:}") String demoToken,
			@Value("${travel.auth.signing-key:}") String signingKey) {
		this.demoEnabled = demoEnabled;
		this.demoToken = demoToken;
		this.signingKey = signingKey;
	}

	public String requireSubject(String authorization) {
		String token = bearer(authorization);
		if (demoEnabled && demoToken != null && !demoToken.isBlank() && constantTimeEquals(token, demoToken)) return "demo-wechat-user";
		if (signingKey == null || signingKey.length() < 32) {
			throw new ApplicationException(HttpStatus.SERVICE_UNAVAILABLE,
					"订单 API 尚未接入微信 OAuth 身份认证；请配置服务器端签发的访问令牌验签密钥。");
		}
		String[] parts = token.split("\\.", 3);
		if (parts.length != 3 || !"v1".equals(parts[0])) throw unauthorized();
		try {
			byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
			byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
			if (!MessageDigest.isEqual(signature, hmac(payload))) throw unauthorized();
			String[] fields = new String(payload, StandardCharsets.UTF_8).split("\\|", 2);
			if (fields.length != 2 || fields[0].isBlank() || fields[0].length() > 128 || Instant.now().getEpochSecond() >= Long.parseLong(fields[1])) throw unauthorized();
			return fields[0];
		} catch (RuntimeException exception) {
			if (exception instanceof ApplicationException applicationException) throw applicationException;
			throw unauthorized();
		}
	}

	private byte[] hmac(byte[] data) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(signingKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return mac.doFinal(data);
		} catch (Exception exception) { throw new IllegalStateException("无法验证访问令牌", exception); }
	}
	private static String bearer(String authorization) {
		if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() < 10) throw unauthorized();
		return authorization.substring(7).trim();
	}
	private static ApplicationException unauthorized() { return new ApplicationException(HttpStatus.UNAUTHORIZED, "无效或过期的旅行服务访问令牌"); }
	private static boolean constantTimeEquals(String first, String second) {
		return MessageDigest.isEqual(first.getBytes(StandardCharsets.UTF_8), second.getBytes(StandardCharsets.UTF_8));
	}
}
