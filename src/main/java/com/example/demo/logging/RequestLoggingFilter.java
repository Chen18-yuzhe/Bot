package com.example.demo.logging;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class RequestLoggingFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		long startedAt = System.nanoTime();
		try {
			filterChain.doFilter(request, response);
		} catch (IOException | ServletException | RuntimeException | Error exception) {
			long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
			log.error("请求处理异常：{} {}，耗时={}毫秒", request.getMethod(),
					request.getRequestURI(), durationMs, exception);
			throw exception;
		}

		long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
		if (response.getStatus() >= 400) {
			log.warn("请求完成：{} {}，状态码={}，耗时={}毫秒", request.getMethod(),
					request.getRequestURI(), response.getStatus(), durationMs);
		} else {
			log.info("请求完成：{} {}，状态码={}，耗时={}毫秒", request.getMethod(),
					request.getRequestURI(), response.getStatus(), durationMs);
		}
	}
}
