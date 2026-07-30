package com.example.bot.logging;

import java.util.regex.Pattern;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Logback converter that masks obvious secrets and personal identifiers
 * before a message reaches the console/journal. Registered as %maskedMsg in
 * logback-spring.xml — every log line goes through it, so a tool echoing an
 * upstream error can never leak an API key or a full phone number.
 */
public class SensitiveDataConverter extends MessageConverter {
	/** DashScope/OpenAI 风格密钥、Bearer 令牌与常见"key=value"形式的机密。 */
	private static final Pattern API_KEY = Pattern.compile("sk-[A-Za-z0-9]{6,}");
	private static final Pattern BEARER = Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]{8,}");
	private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
			"(?i)(api[-_]?key|token|password|secret|bili[-_]?cookie)([\"'=:：\\s]+)"
					+ "(?![^\\s,;\"']*\\*{4})[^\\s,;\"']{6,}");
	private static final Pattern BILIBILI_COOKIE_FIELD = Pattern.compile(
			"(?i)(SESSDATA|bili_jct|DedeUserID|DedeUserID__ckMd5|sid)(=)[^;\\s,]+");
	/** 中国大陆手机号：保留前三位和后两位，足够排查又不还原号码。 */
	private static final Pattern PHONE = Pattern.compile("(?<!\\d)(1[3-9]\\d)(\\d{6})(\\d{2})(?!\\d)");

	@Override
	public String convert(ILoggingEvent event) {
		return mask(super.convert(event));
	}

	static String mask(String message) {
		if (message == null || message.isBlank()) return message;
		String masked = API_KEY.matcher(message).replaceAll("sk-****");
		masked = BEARER.matcher(masked).replaceAll("Bearer ****");
		masked = SECRET_ASSIGNMENT.matcher(masked).replaceAll("$1$2****");
		masked = BILIBILI_COOKIE_FIELD.matcher(masked).replaceAll("$1$2****");
		masked = PHONE.matcher(masked).replaceAll("$1******$3");
		return masked;
	}
}
