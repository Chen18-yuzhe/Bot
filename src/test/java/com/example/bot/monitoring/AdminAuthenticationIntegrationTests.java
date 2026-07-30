package com.example.bot.monitoring;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = "assistant.admin.token=test-admin-token")
class AdminAuthenticationIntegrationTests {

	@Autowired WebApplicationContext context;

	@Test
	void authenticatedBrowserSessionCanAccessProtectedOperationsWithoutTokenHeader() throws Exception {
		MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
		var login = mockMvc.perform(post("/api/admin/ilink/session")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"token\":\"test-admin-token\"}"))
				.andExpect(status().isNoContent())
				.andReturn();
		Cookie session = login.getResponse().getCookie("admin_session");

		mockMvc.perform(get("/api/admin/status").cookie(session))
				.andExpect(status().isOk());
	}
}
