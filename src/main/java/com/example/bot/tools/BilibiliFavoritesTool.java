package com.example.bot.tools;

import org.springframework.stereotype.Component;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.example.bot.agent.AgentToolException;
import com.example.bot.bilibili.BilibiliFavoritesService;
import com.example.bot.bilibili.BilibiliQrLoginService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonParser;

/** Model-callable tool set backed by the local java-fav SDK. */
@Component
public class BilibiliFavoritesTool implements AssistantTool {
	private static final FunctionDefinition DEFINITION = FunctionDefinition.builder()
			.name("manage_bilibili_favorites")
			.description("""
					管理当前获准会话对应的私人 Bilibili 收藏夹和本地字幕库。可检查登录、列出收藏夹、
					同步视频元数据或字幕、实时列出收藏夹视频、读取单个 BV 号字幕，以及把一个收藏夹的字幕
					导入当前机器人的个人知识库。不得编造收藏夹或字幕。
					""".trim())
			.parameters(JsonParser.parseString("""
					{"type":"object","properties":{
					  "action":{"type":"string","enum":["status","start_login","login_status","list_folders","sync_folder","list_videos","get_subtitle","get_danmaku","ingest_folder"],"description":"要执行的操作"},
					  "folderId":{"type":"integer","minimum":1,"description":"收藏夹 ID；列视频、同步和导入时必填。必须先从 list_folders 的真实结果取得，禁止猜测"},
					  "includeSubtitles":{"type":"boolean","description":"sync_folder 是否同时抓取字幕，默认 false"},
					  "query":{"type":"string","maxLength":160,"description":"可选的视频标题、UP主、简介或字幕关键词；列出全部时不要传"},
					  "limit":{"type":"integer","minimum":1,"maximum":50,"description":"最多返回的视频数，默认 10"},
					  "bvId":{"type":"string","description":"需要读取字幕的 BV 号"}
					},"required":["action"],"additionalProperties":false}
					""").getAsJsonObject()).build();

	private final BilibiliFavoritesService favorites;
	private final BilibiliQrLoginService qrLogin;
	private final ObjectMapper objectMapper;

	public BilibiliFavoritesTool(BilibiliFavoritesService favorites, BilibiliQrLoginService qrLogin,
			ObjectMapper objectMapper) {
		this.favorites = favorites;
		this.qrLogin = qrLogin;
		this.objectMapper = objectMapper;
	}

	@Override public FunctionDefinition definition() { return DEFINITION; }

	@Override
	public ToolResult execute(JsonNode arguments, ToolContext context) {
		favorites.requireAuthorized(context.sessionId());
		String action = arguments.path("action").asText("").trim();
		if ("sync_folder".equals(action) || "list_videos".equals(action)
				|| "ingest_folder".equals(action)) {
			requiredFolderId(arguments);
		}
		if ("start_login".equals(action)) {
			return startQrLogin(context);
		}
		if (!"status".equals(action) && !"login_status".equals(action)
				&& !favorites.authenticated(context.sessionId())) {
			startQrLogin(context);
		}
		Object result = switch (action) {
			case "status" -> favorites.status(context.sessionId());
			case "login_status" -> qrLogin.status(BilibiliFavoritesService.credentialId(context.sessionId()));
			case "list_folders" -> favorites.listFolders(context.sessionId());
			case "sync_folder" -> favorites.syncFolder(
					context.sessionId(), requiredFolderId(arguments),
					arguments.path("includeSubtitles").asBoolean(false));
			case "list_videos" -> favorites.listVideos(
					context.sessionId(), requiredFolderId(arguments), arguments.path("query").asText(""),
					arguments.path("limit").asInt(10));
			case "get_subtitle" -> {
				String subtitle = favorites.subtitle(context.sessionId(), arguments.path("bvId").asText(""));
				if (subtitle.isBlank()) yield new EmptySubtitle(arguments.path("bvId").asText(""));
				yield new Subtitle(arguments.path("bvId").asText(""), subtitle);
			}
			case "get_danmaku" -> favorites.danmaku(context.sessionId(), arguments.path("bvId").asText(""));
			case "ingest_folder" -> favorites.syncAndIngest(requiredFolderId(arguments), context.sessionId());
			default -> throw new IllegalArgumentException("action 不支持：" + action);
		};
		String json = json(result);
		if (result instanceof EmptySubtitle) return ToolResult.noResult(json);
		return ToolResult.text(json);
	}

	/** Starts (or reuses) a QR attempt and deliberately yields the agent to the account owner. */
	private ToolResult startQrLogin(ToolContext context) {
		String credentialId = BilibiliFavoritesService.credentialId(context.sessionId());
		BilibiliQrLoginService.LoginStatus status = qrLogin.start(credentialId);
		if (status.state() == BilibiliQrLoginService.State.AUTHENTICATED) {
			return ToolResult.text(json(status));
		}
		if (status.state() == BilibiliQrLoginService.State.WAITING_FOR_SCAN) {
			throw AgentToolException.authRequired("Bilibili 登录等待扫码",
					"Bilibili 官方登录二维码已生成。请在管理控制台的“Agent 任务”中打开当前任务，点击“B站扫码登录”并用该用户的 Bilibili App 确认；登录成功后恢复此任务，Agent 会从登录检查继续。二维码和凭据均不会发送到聊天内容。");
		}
		throw AgentToolException.authRequired("Bilibili 登录未完成", status.message());
	}

	private static long requiredFolderId(JsonNode arguments) {
		long id = arguments.path("folderId").asLong(0);
		if (id <= 0) throw new IllegalArgumentException("该操作需要有效的 folderId");
		return id;
	}

	private String json(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (JsonProcessingException error) {
			throw new IllegalStateException("无法序列化 Bilibili 收藏夹结果", error);
		}
	}

	private record Subtitle(String bvId, String text) { }
	private record EmptySubtitle(String bvId) { }
}
