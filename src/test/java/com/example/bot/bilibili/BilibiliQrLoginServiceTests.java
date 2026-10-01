package com.example.bot.bilibili;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.example.demo.BiliQrLogin;
import com.fasterxml.jackson.databind.ObjectMapper;

class BilibiliQrLoginServiceTests {
	private final ObjectMapper mapper = new ObjectMapper();
	private static final String PROJECT = "12345678-1234-1234-1234-123456789012";

	@Test
	void exposesQrContentThenStoresCookieOnlyInMemory() throws Exception {
		BilibiliFavoritesService favorites = mock(BilibiliFavoritesService.class);
		BiliQrLogin sdkLogin = mock(BiliQrLogin.class);
		var payload = mapper.readTree("""
				{"code":0,"data":{"url":"https://passport.bilibili.com/qr/example","qrcode_key":"key"}}
				""");
		when(sdkLogin.getUrl()).thenReturn(payload);
		when(sdkLogin.login(180, payload))
				.thenReturn("SESSDATA=private; bili_jct=csrf; DedeUserID=123");
		AtomicReference<Runnable> pending = new AtomicReference<>();
		BilibiliQrLoginService service = new BilibiliQrLoginService(
				favorites, 180, () -> sdkLogin, pending::set);

		var waiting = service.start(PROJECT);

		assertThat(waiting.state()).isEqualTo(BilibiliQrLoginService.State.WAITING_FOR_SCAN);
		assertThat(waiting.qrAvailable()).isTrue();
		assertThat(service.qrContent(PROJECT)).isEqualTo("https://passport.bilibili.com/qr/example");

		pending.get().run();

		assertThat(service.status(PROJECT).state()).isEqualTo(BilibiliQrLoginService.State.AUTHENTICATED);
		assertThat(service.status(PROJECT).qrAvailable()).isFalse();
		assertThat(service.status(PROJECT).toString()).doesNotContain("private", "csrf", "123");
		verify(favorites).useRuntimeCookie(PROJECT, "SESSDATA=private; bili_jct=csrf; DedeUserID=123");
	}

	@Test
	void reportsPollingFailureWithoutExposingQrContent() throws Exception {
		BilibiliFavoritesService favorites = mock(BilibiliFavoritesService.class);
		BiliQrLogin sdkLogin = mock(BiliQrLogin.class);
		var payload = mapper.readTree("""
				{"code":0,"data":{"url":"https://passport.bilibili.com/qr/example","qrcode_key":"key"}}
				""");
		when(sdkLogin.getUrl()).thenReturn(payload);
		when(sdkLogin.login(180, payload)).thenThrow(new RuntimeException("二维码已过期"));
		BilibiliQrLoginService service = new BilibiliQrLoginService(
				favorites, 180, () -> sdkLogin, Runnable::run);

		var status = service.start(PROJECT);

		assertThat(status.state()).isEqualTo(BilibiliQrLoginService.State.FAILED);
		assertThat(status.qrAvailable()).isFalse();
		assertThat(status.message()).contains("过期");
	}

	@Test
	void permitsTheDefaultProjectToUseQrLoginInsteadOfAnEnvironmentCookie() throws Exception {
		BilibiliFavoritesService favorites = mock(BilibiliFavoritesService.class);
		BiliQrLogin sdkLogin = mock(BiliQrLogin.class);
		var payload = mapper.readTree("""
				{"code":0,"data":{"url":"https://passport.bilibili.com/qr/default","qrcode_key":"key"}}
				""");
		when(sdkLogin.getUrl()).thenReturn(payload);
		BilibiliQrLoginService service = new BilibiliQrLoginService(
				favorites, 180, () -> sdkLogin, command -> { });

		var waiting = service.start("default");

		assertThat(waiting.state()).isEqualTo(BilibiliQrLoginService.State.WAITING_FOR_SCAN);
		assertThat(service.qrContent("default")).isEqualTo("https://passport.bilibili.com/qr/default");
	}
}
