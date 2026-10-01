package com.example.bot.monitoring;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AdminPagesTests {
	@Test
	void shouldRenderExecutableLoginScript() {
		String page = AdminPages.app();
		assertTrue(page.contains("id=\"loginSubmit\""));
		assertTrue(page.contains("const b=q('#loginSubmit')"));
		assertTrue(page.contains("const isLoginRequest=url===api+'/session'"));
		assertTrue(page.contains("adminToken=submittedToken"));
		assertTrue(page.contains("headers['X-Admin-Token']=adminToken"));
		assertTrue(page.contains("data-action=\"rename\""));
		assertTrue(page.contains("data-page=\"usernames\""));
		assertTrue(page.contains("设置永久称呼"));
		assertTrue(page.contains("阅读历史内容时自动刷新不会打断你"));
		assertTrue(page.contains("contextsRefreshTimer=window.setInterval"));
		assertTrue(page.contains("slice().reverse().flatMap"));
		assertTrue(page.contains("有新的会话消息；点击“刷新”即可查看。"));
		assertTrue(page.contains("adminToken='';authUI(false)"));
		assertTrue(page.contains("无法连接管理服务。若刚完成部署，请等待服务启动后刷新页面。"));
		// The rendered JavaScript must retain the escape sequence, not a raw newline in its string literal.
		assertTrue(page.contains("join('\\n')"));
	}
}
