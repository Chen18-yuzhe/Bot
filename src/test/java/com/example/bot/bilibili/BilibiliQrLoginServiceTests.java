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

		var waiting = service.start();

		assertThat(waiting.state()).isEqualTo(BilibiliQrLoginService.State.WAITING_FOR_SCAN);
		assertThat(waiting.qrAvailable()).isTrue();
		assertThat(service.qrContent()).isEqualTo("https://passport.bilibili.com/qr/example");

		pending.get().run();

		assertThat(service.status().state()).isEqualTo(BilibiliQrLoginService.State.AUTHENTICATED);
		assertThat(service.status().qrAvailable()).isFalse();
		assertThat(service.status().toString()).doesNotContain("private", "csrf", "123");
		verify(favorites).useRuntimeCookie("SESSDATA=private; bili_jct=csrf; DedeUserID=123");
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

		var status = service.start();

		assertThat(status.state()).isEqualTo(BilibiliQrLoginService.State.FAILED);
		assertThat(status.qrAvailable()).isFalse();
		assertThat(status.message()).contains("过期");
	}
}
