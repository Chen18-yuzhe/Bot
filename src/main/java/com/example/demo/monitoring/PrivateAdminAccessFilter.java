package com.example.demo.monitoring;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class PrivateAdminAccessFilter extends OncePerRequestFilter {

	private final boolean privateOnly;

	public PrivateAdminAccessFilter(
			@Value("${assistant.admin.private-only:true}") boolean privateOnly) {
		this.privateOnly = privateOnly;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !request.getRequestURI().startsWith("/api/admin/");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		if (privateOnly && (!isLoopback(request.getRemoteAddr()) || isForwarded(request))) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}
		filterChain.doFilter(request, response);
	}

	private static boolean isLoopback(String address) {
		return "127.0.0.1".equals(address)
				|| "0:0:0:0:0:0:0:1".equals(address)
				|| "::1".equals(address);
	}

	private static boolean isForwarded(HttpServletRequest request) {
		return present(request.getHeader("Forwarded"))
				|| present(request.getHeader("X-Forwarded-For"))
				|| present(request.getHeader("X-Real-IP"));
	}

	private static boolean present(String value) {
		return value != null && !value.isBlank();
	}
}
