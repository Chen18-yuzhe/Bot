package com.example.bot.agent;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/** Keeps explicit private-favorites requests isolated from unrelated task history. */
@Component
public class BilibiliFavoritesTaskType implements AgentTaskType {
	public static final String NAME = "bilibili-favorites";
	private static final Set<String> TOOLS = Set.of("manage_bilibili_favorites");
	private static final Pattern BV_ID = Pattern.compile("(?i)BV[0-9A-Za-z]{8,20}");

	@Override public String name() { return NAME; }
	@Override public int routingPriority() { return 200; }
	@Override public Set<String> allowedTools() { return TOOLS; }

	@Override
	public boolean supports(String requestContext) {
		String value = requestContext == null ? "" : requestContext.toLowerCase(Locale.ROOT);
		return value.contains("b站") || value.contains("bilibili")
				|| value.contains("哔哩哔哩")
				|| (value.contains("收藏夹")
						&& (value.contains("视频") || value.contains("字幕") || value.contains("bv")));
	}

	@Override
	public String systemPrompt(AgentTask task) {
		return """
				你是私人 Bilibili 收藏夹助手。本任务只能使用收藏夹工具，禁止调用旅游、购票或联网搜索工具。
				根据用户原话选择操作：列出收藏夹使用 list_folders；查看收藏夹视频时先 list_folders，
				再把返回的真实 folderId 传给 list_videos，视频必须以实时接口结果为准；
				同步收藏夹使用 sync_folder；导入知识库使用 ingest_folder；读取字幕使用 get_subtitle。
				缺少 folderId 或 BV 号时直接向用户说明需要什么，不要猜测。不得编造收藏夹、视频、字幕或链接。
				工具返回后简洁转述真实结果；遇到未授权、未登录或上游错误时原样说明解决办法。
				""";
	}

	@Override
	public String validateCompletion(AgentTask task, String answer) {
		String evidence = task.getTranscript().stream()
				.filter(entry -> "tool".equals(entry.role())
						&& "manage_bilibili_favorites".equals(entry.name()))
				.map(AgentTranscript.Entry::text)
				.filter(text -> text != null)
				.reduce("", (left, right) -> left + "\n" + right);
		if (evidence.isBlank()) return "必须先调用收藏夹工具获取当前账号的实时数据";
		var matcher = BV_ID.matcher(answer == null ? "" : answer);
		while (matcher.find()) {
			if (!evidence.toLowerCase(Locale.ROOT)
					.contains(matcher.group().toLowerCase(Locale.ROOT))) {
				return "回答包含工具结果中不存在的 BV 号：" + matcher.group();
			}
		}
		return null;
	}
}
