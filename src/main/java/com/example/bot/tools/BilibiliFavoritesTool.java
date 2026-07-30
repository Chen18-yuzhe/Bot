package com.example.bot.tools;

import org.springframework.stereotype.Component;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.example.bot.bilibili.BilibiliFavoritesService;
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
					同步视频元数据或字幕、检索已同步视频、读取单个 BV 号字幕，以及把一个收藏夹的字幕
					导入当前机器人的个人知识库。不得编造收藏夹或字幕。
					""".trim())
			.parameters(JsonParser.parseString("""
					{"type":"object","properties":{
					  "action":{"type":"string","enum":["status","list_folders","sync_folder","list_videos","get_subtitle","ingest_folder"],"description":"要执行的操作"},
					  "folderId":{"type":"integer","minimum":1,"description":"收藏夹 ID；同步和导入时必填"},
					  "includeSubtitles":{"type":"boolean","description":"sync_folder 是否同时抓取字幕，默认 false"},
					  "query":{"type":"string","maxLength":160,"description":"本地视频标题、UP主、简介或字幕关键词"},
					  "limit":{"type":"integer","minimum":1,"maximum":50,"description":"最多返回的视频数，默认 10"},
					  "bvId":{"type":"string","description":"需要读取字幕的 BV 号"}
					},"required":["action"],"additionalProperties":false}
					""").getAsJsonObject()).build();

	private final BilibiliFavoritesService favorites;
	private final ObjectMapper objectMapper;

	public BilibiliFavoritesTool(BilibiliFavoritesService favorites, ObjectMapper objectMapper) {
		this.favorites = favorites;
		this.objectMapper = objectMapper;
	}

	@Override public FunctionDefinition definition() { return DEFINITION; }

	@Override
	public ToolResult execute(JsonNode arguments, ToolContext context) {
		favorites.requireAuthorized(context.sessionId());
		String action = arguments.path("action").asText("").trim();
		Object result = switch (action) {
			case "status" -> favorites.status();
			case "list_folders" -> favorites.listFolders();
			case "sync_folder" -> favorites.syncFolder(
					requiredFolderId(arguments), arguments.path("includeSubtitles").asBoolean(false));
			case "list_videos" -> favorites.listVideos(
					arguments.path("query").asText(""), arguments.path("limit").asInt(10));
			case "get_subtitle" -> {
				String subtitle = favorites.subtitle(arguments.path("bvId").asText(""));
				if (subtitle.isBlank()) yield new EmptySubtitle(arguments.path("bvId").asText(""));
				yield new Subtitle(arguments.path("bvId").asText(""), subtitle);
			}
			case "ingest_folder" -> favorites.syncAndIngest(requiredFolderId(arguments), context.sessionId());
			default -> throw new IllegalArgumentException("action 不支持：" + action);
		};
		String json = json(result);
		if (result instanceof EmptySubtitle) return ToolResult.noResult(json);
		return ToolResult.text(json);
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
