package com.example.bot.travel;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.example.bot.agent.AgentToolException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Minimal stdio MCP client for the pinned, third-party {@code 12306-mcp} package.
 *
 * <p>Each lookup gets its own short-lived process. This avoids sharing MCP sessions
 * or 12306 cookies between chat users, and this client deliberately exposes only
 * the read-only {@code get-tickets} tool.</p>
 */
@Component
public class RailMcpClient {
	private static final String MCP_PROTOCOL_VERSION = "2025-03-26";
	private static final int MAX_RESPONSE_CHARS = 12_000;

	private final boolean enabled;
	private final String command;
	private final String packageName;
	private final Duration timeout;
	private final ObjectMapper objectMapper;

	public RailMcpClient(@Value("${travel.rail.mcp.enabled:false}") boolean enabled,
			@Value("${travel.rail.mcp.command:npx}") String command,
			@Value("${travel.rail.mcp.package:12306-mcp@0.3.9}") String packageName,
			@Value("${travel.rail.mcp.timeout-seconds:45}") long timeoutSeconds,
			ObjectMapper objectMapper) {
		this.enabled = enabled;
		this.command = clean(command);
		this.packageName = clean(packageName);
		this.timeout = Duration.ofSeconds(Math.max(10, Math.min(timeoutSeconds, 90)));
		this.objectMapper = objectMapper;
	}

	public boolean enabled() {
		return enabled && !command.isBlank() && !packageName.isBlank();
	}

	/** Queries the MCP's documented read-only {@code get-tickets} tool. */
	public String tickets(String origin, String destination, String date, int limit) {
		if (!enabled()) throw unavailable("12306 MCP 尚未启用");
		Process process = null;
		try {
			process = new ProcessBuilder(executable(), "-y", packageName).start();
			try (BufferedWriter input = new BufferedWriter(new OutputStreamWriter(
					process.getOutputStream(), StandardCharsets.UTF_8));
					BufferedReader output = new BufferedReader(new InputStreamReader(
							process.getInputStream(), StandardCharsets.UTF_8))) {
				write(input, request(1, "initialize", initializeParams()));
				await(output, 1);
				write(input, notification("notifications/initialized", objectMapper.createObjectNode()));

				ObjectNode arguments = objectMapper.createObjectNode();
				arguments.put("fromStation", origin);
				arguments.put("toStation", destination);
				arguments.put("date", date);
				arguments.put("limitedNum", Math.max(1, Math.min(limit, 20)));
				arguments.put("format", "json");
				ObjectNode params = objectMapper.createObjectNode();
				params.put("name", "get-tickets");
				params.set("arguments", arguments);
				JsonNode response = await(output, 2, request(2, "tools/call", params), input);
				if (response.path("error").isObject()) {
					throw unavailable("12306 MCP 返回错误：" + response.path("error").path("message").asText("未知错误"));
				}
				if (response.path("result").path("isError").asBoolean(false)) {
					throw unavailable("12306 MCP 查询失败：" + response.path("result").path("content").toString());
				}
				String text = response.path("result").path("content").path(0).path("text").asText("");
				if (text.isBlank()) throw unavailable("12306 MCP 未返回车次数据");
				return compact(text);
			}
		} catch (AgentToolException exception) {
			throw exception;
		} catch (Exception exception) {
			throw unavailable("无法调用 12306 MCP：" + safeMessage(exception));
		} finally {
			if (process != null) {
				process.destroy();
				try { process.waitFor(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {
					Thread.currentThread().interrupt();
				}
				if (process.isAlive()) process.destroyForcibly();
			}
		}
	}

	private JsonNode await(BufferedReader output, int expectedId) throws Exception {
		return await(output, expectedId, null, null);
	}

	private JsonNode await(BufferedReader output, int expectedId, JsonNode next, BufferedWriter input) throws Exception {
		if (next != null) write(input, next);
		long deadline = System.nanoTime() + timeout.toNanos();
		while (System.nanoTime() < deadline) {
			if (!output.ready()) {
				Thread.sleep(20);
				continue;
			}
			String line = output.readLine();
			if (line == null) throw new IllegalStateException("12306 MCP 已提前退出");
			JsonNode response = objectMapper.readTree(line);
			if (response.path("id").asInt(-1) == expectedId) return response;
		}
		throw new IllegalStateException("等待 12306 MCP 响应超时");
	}

	private ObjectNode initializeParams() {
		ObjectNode params = objectMapper.createObjectNode();
		params.put("protocolVersion", MCP_PROTOCOL_VERSION);
		params.set("capabilities", objectMapper.createObjectNode());
		params.set("clientInfo", objectMapper.valueToTree(Map.of("name", "wechat-agent-platform", "version", "1.0.0")));
		return params;
	}

	private ObjectNode request(int id, String method, JsonNode params) {
		ObjectNode request = objectMapper.createObjectNode();
		request.put("jsonrpc", "2.0");
		request.put("id", id);
		request.put("method", method);
		request.set("params", params);
		return request;
	}

	private ObjectNode notification(String method, JsonNode params) {
		ObjectNode request = objectMapper.createObjectNode();
		request.put("jsonrpc", "2.0");
		request.put("method", method);
		request.set("params", params);
		return request;
	}

	private void write(BufferedWriter input, JsonNode request) throws Exception {
		input.write(objectMapper.writeValueAsString(request));
		input.newLine();
		input.flush();
	}

	private AgentToolException unavailable(String message) {
		return AgentToolException.degraded(message,
				"{\"source\":\"12306_MCP\",\"live\":false,\"readOnly\":true,"
						+ "\"officialEntry\":\"https://www.12306.cn/\","
						+ "\"notice\":\"12306 MCP 当前不可用；请到铁路12306官方页面核验余票、实名并完成购票。\"}");
	}

	private static String safeMessage(Exception error) {
		String message = error.getMessage();
		return message == null || message.isBlank() ? error.getClass().getSimpleName() : compact(message);
	}

	private static String compact(String value) {
		String safe = value == null ? "" : value.replace('\u0000', ' ').trim();
		return safe.length() <= MAX_RESPONSE_CHARS ? safe : safe.substring(0, MAX_RESPONSE_CHARS) + "…";
	}

	private static String clean(String value) { return value == null ? "" : value.trim(); }

	private String executable() {
		return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
				&& "npx".equalsIgnoreCase(command) ? "npx.cmd" : command;
	}
}
