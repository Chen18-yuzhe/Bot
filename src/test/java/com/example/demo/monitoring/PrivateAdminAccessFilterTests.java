package com.example.demo.monitoring;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.FilterChain;

class PrivateAdminAccessFilterTests {

	private final PrivateAdminAccessFilter filter = new PrivateAdminAccessFilter(true);

	@Test
	void shouldAllowAdminRequestFromLoopback() throws Exception {
		MockHttpServletRequest request = request("/api/admin/ilink/login", "127.0.0.1");
		MockHttpServletResponse response = new MockHttpServletResponse();
		AtomicBoolean continued = new AtomicBoolean();

		filter.doFilter(request, response, chain(continued));

		assertEquals(true, continued.get());
		assertEquals(200, response.getStatus());
	}

	@Test
	void shouldHideAdminRequestFromRemoteAddress() throws Exception {
		MockHttpServletRequest request = request("/api/admin/ilink/login", "203.0.113.8");
		MockHttpServletResponse response = new MockHttpServletResponse();
		AtomicBoolean continued = new AtomicBoolean();

		filter.doFilter(request, response, chain(continued));

		assertEquals(false, continued.get());
		assertEquals(404, response.getStatus());
	}

	@Test
	void shouldRejectReverseProxyForwardedAdminRequest() throws Exception {
		MockHttpServletRequest request = request("/api/admin/status", "127.0.0.1");
		request.addHeader("X-Forwarded-For", "203.0.113.8");
		MockHttpServletResponse response = new MockHttpServletResponse();
		AtomicBoolean continued = new AtomicBoolean();

		filter.doFilter(request, response, chain(continued));

		assertEquals(false, continued.get());
		assertEquals(404, response.getStatus());
	}

	@Test
	void shouldNotRestrictNonAdminRoutes() throws Exception {
		MockHttpServletRequest request = request("/api/chat", "203.0.113.8");
		MockHttpServletResponse response = new MockHttpServletResponse();
		AtomicBoolean continued = new AtomicBoolean();

		filter.doFilter(request, response, chain(continued));

		assertEquals(true, continued.get());
	}

	private static MockHttpServletRequest request(String uri, String remoteAddress) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
		request.setRemoteAddr(remoteAddress);
		return request;
	}

	private static FilterChain chain(AtomicBoolean continued) {
		return (request, response) -> continued.set(true);
	}
}
