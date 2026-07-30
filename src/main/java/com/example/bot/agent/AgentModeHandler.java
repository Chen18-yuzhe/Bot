package com.example.bot.agent;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.example.bot.chat.ConversationMemory;
import com.example.bot.chat.ConversationTurn;

import jakarta.annotation.PreDestroy;

/** Routes a conversation between direct chat and autonomous task execution. */
@Component
public class AgentModeHandler {
	public enum Mode { CHAT, AGENT }

	private final AgentLoopService agentLoop;
	private final ConversationMemory conversationMemory;
	private final AgentTaskRouter taskRouter;
	private final Map<String, Mode> modes = new ConcurrentHashMap<>();
	private final Map<String, String> originalGoals = new ConcurrentHashMap<>();
	private final java.util.Set<String> watchingTasks = ConcurrentHashMap.newKeySet();
	private final ExecutorService completionWatchers = Executors.newVirtualThreadPerTaskExecutor();
	private volatile BiConsumer<String, String> completionNotifier;

	@Autowired
	public AgentModeHandler(AgentLoopService agentLoop, ConversationMemory conversationMemory,
			AgentTaskRouter taskRouter) {
		this.agentLoop = agentLoop;
		this.conversationMemory = conversationMemory;
		this.taskRouter = taskRouter;
	}

	AgentModeHandler(AgentLoopService agentLoop) {
		this(agentLoop, null, defaultRouter());
	}

	AgentModeHandler(AgentLoopService agentLoop, ConversationMemory conversationMemory) {
		this(agentLoop, conversationMemory, defaultRouter());
	}

	/** Returns null when the message should continue through the normal chat path. */
	public String handle(String sessionId, String input) {
		if (input == null || input.isBlank()) return null;
		String command = input.trim();
		Mode selected = parseModeCommand(command);
		if (selected != null) {
			modes.put(normalizeSession(sessionId), selected);
			return selected == Mode.AGENT
					? "已切换到 Agent 模式。直接说目标即可；发送“停”或“暂停”可保存进度并暂停，发送 /chat 切回聊天。"
					: "已切换到聊天模式。发送 /agent 可切到自动执行模式。";
		}
		// WeChat commonly sends "/agent" and the goal in one message (often on
		// separate lines). Route it through this handler so it gets the correct
		// task type and the completion push notification.
		String inlineGoal = inlineAgentGoal(command);
		if (inlineGoal != null) {
			String normalizedSession = normalizeSession(sessionId);
			modes.put(normalizedSession, Mode.AGENT);
			return submitGoal(normalizedSession, inlineGoal);
		}
		// A request for a ticket H5 / booking entry needs verified external data.
		// Route it to the bounded Travel Agent even when the conversation normally
		// stays in chat mode; otherwise the general chat model can only describe a
		// purchase path and never invokes the FlyAI lookup or entry guard.
		List<ConversationTurn> currentHistory = conversationMemory == null
				? List.of() : conversationMemory.history(normalizeSession(sessionId));
		if (taskRouter.shouldAutoStart(command, currentHistory)) {
			return submitGoal(normalizeSession(sessionId), command);
		}
		if (modeOf(sessionId) != Mode.AGENT || command.startsWith("/")) return null;
		String normalizedSession = normalizeSession(sessionId);
		String controlReply = handleNaturalControl(normalizedSession, command);
		if (controlReply != null) return controlReply;
		return submitGoal(normalizedSession, command);
	}

	private String submitGoal(String normalizedSession, String command) {
		List<ConversationTurn> history = conversationMemory == null
				? List.of() : conversationMemory.history(normalizedSession);
		String taskType = taskRouter.route(command, history).name();
		String contextualGoal = contextualGoal(command, history, taskType);
		if (conversationMemory != null) conversationMemory.rememberIncoming(normalizedSession, command);
		AgentTask task = agentLoop.submit(normalizedSession, taskType, contextualGoal, Duration.ZERO);
		originalGoals.put(task.getId(), command);
		watchCompletion(task);
		return "好的，我正在处理。完成后会直接把结果发给你；你可以随时说“停”或“暂停”。";
	}

	/** Returns an inline goal for `/agent <goal>`, excluding task-management commands. */
	private static String inlineAgentGoal(String command) {
		if (!command.regionMatches(true, 0, "/agent", 0, 6)
				|| (command.length() > 6 && !Character.isWhitespace(command.charAt(6)))) return null;
		if (command.length() == 6) return null;
		String argument = command.substring(6).trim();
		if (argument.isBlank() || isAgentManagementArgument(argument)) return null;
		return argument;
	}

	private static boolean isAgentManagementArgument(String argument) {
		String lower = argument.toLowerCase(Locale.ROOT);
		if (lower.equals("list") || lower.equals("ls") || lower.equals("help") || lower.equals("?")
				|| argument.equals("列表") || argument.equals("帮助") || argument.equals("全部删除")
				|| argument.equals("确认全部删除")) return true;
		if (argument.matches("(?is)^(?:#?[0-9a-f-]{4,36}|\\d+|当前|最新|latest|current)(?:\\s+.*)?$")) return true;
		return argument.matches("(?is)^(?:暂停|恢复|继续|取消|重试|删除|详情|trace|人工|pause|resume|cancel|retry|delete|detail|human)\\s+.*$");
	}

	public Mode modeOf(String sessionId) { return modes.getOrDefault(normalizeSession(sessionId), Mode.CHAT); }

	/** The WeChat transport registers its safe push function after it is online. */
	public void setCompletionNotifier(BiConsumer<String, String> completionNotifier) {
		this.completionNotifier = completionNotifier;
	}

	private void watchCompletion(AgentTask submitted) {
		if (completionNotifier == null) return;
		if (!watchingTasks.add(submitted.getId())) return;
		completionWatchers.submit(() -> {
			try {
				long deadline = Math.max(System.currentTimeMillis() + 1_000, submitted.getDeadlineAtMs() + 5_000);
				while (System.currentTimeMillis() < deadline) {
					AgentTask current = agentLoop.get(submitted.getId());
					if (current.getStatus().isTerminal()) {
						String result = completionMessage(current);
						String originalGoal = originalGoals.remove(current.getId());
						if (conversationMemory != null && originalGoal != null) {
							conversationMemory.remember(current.getSessionId(), originalGoal, result);
						}
						BiConsumer<String, String> notifier = completionNotifier;
						if (notifier != null) notifier.accept(current.getSessionId(), result);
						return;
					}
					Thread.sleep(750);
				}
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
			} catch (RuntimeException exception) {
				// A deleted task must not produce a later unsolicited notification.
			} finally {
				watchingTasks.remove(submitted.getId());
			}
		});
	}

	private String handleNaturalControl(String sessionId, String command) {
		if (matches(command, "停", "暂停", "停止", "停一下", "先停", "先暂停", "别执行了", "先别做了",
				"停止任务", "暂停任务", "先停一下", "暂停一下")) {
			AgentTask task = latest(sessionId, false);
			if (task == null) return "当前没有可暂停的 Agent 任务。";
			AgentTask paused = agentLoop.pause(task.getId());
			return "好的，正在暂停，当前进度会自动保存。之后发送“继续”即可恢复。";
		}
		if (matches(command, "继续", "恢复", "接着做", "恢复任务", "继续任务", "继续执行")) {
			AgentTask task = latest(sessionId, true);
			if (task == null) return "当前没有已暂停的 Agent 任务。";
			if (task.getStatus() == AgentTaskStatus.WAITING_HUMAN) {
				return "任务 #" + displayId(task) + " 正在等待人工处理：" +
						(task.getPendingHuman() == null ? "请先完成所需授权。" : task.getPendingHuman().prompt()) +
						"\n完成后请使用 /agent " + displayId(task) + " 人工 <处理结果> 提交结果并继续。";
			}
			AgentTask resumed = agentLoop.resume(task.getId());
			watchCompletion(resumed);
			return "好的，我从上次保存的位置继续处理。";
		}
		if (matches(command, "任务进度", "查看任务", "现在做到哪了")) {
			AgentTask task = latestAny(sessionId);
			if (task == null) return "当前没有 Agent 任务。";
			return "任务 #" + displayId(task) + "：" + task.getStatus() + "，步骤 "
					+ task.getStepCount() + "/" + task.getMaxSteps() + "。";
		}
		return null;
	}

	private AgentTask latest(String sessionId, boolean resumable) {
		return agentLoop.list(sessionId).stream()
				.filter(task -> resumable
						? task.getStatus() == AgentTaskStatus.PAUSED || task.getStatus() == AgentTaskStatus.WAITING_HUMAN
						: !task.getStatus().isTerminal() && task.getStatus() != AgentTaskStatus.PAUSED)
				.findFirst().orElse(null);
	}

	private AgentTask latestAny(String sessionId) {
		return agentLoop.list(sessionId).stream().findFirst().orElse(null);
	}

	private static boolean matches(String value, String... commands) {
		for (String command : commands) if (command.equalsIgnoreCase(value)) return true;
		return false;
	}

	static boolean isTravelIntent(String command, List<ConversationTurn> history) {
		StringBuilder context = new StringBuilder(command == null ? "" : command);
		if (history != null) {
			history.stream().skip(Math.max(0, history.size() - 4L))
					.forEach(turn -> context.append('\n').append(turn.userMessage()).append('\n')
							.append(turn.assistantMessage()));
		}
		String value = context.toString().toLowerCase(Locale.ROOT);
		return containsAny(value, "旅游", "旅行", "攻略", "行程", "景点", "路线", "门票", "票价", "查票",
				"订票", "购票", "退票", "酒店", "目的地", "游玩", "出游", "自由行", "周末游",
				"trip", "travel", "itinerary")
				|| value.matches("(?s).*(去|到).{1,24}(玩|游|逛).*(天|日|预算|方案).*");
	}

	private static boolean isBookingLinkIntent(String command) {
		if (!isTravelIntent(command, List.of())) return false;
		String value = command == null ? "" : command.toLowerCase(Locale.ROOT);
		return containsAny(value, "h5", "链接", "鏈接", "跳转", "入口", "购票页", "购票页面");
	}

	private static String contextualGoal(String command, List<ConversationTurn> history,
			String taskType) {
		if (history == null || history.isEmpty()) return command;
		// A booking-link request names its target in the current message. Pulling
		// previous assistant replies into it can turn a short attraction name into
		// a multi-kilobyte search query and produce unusable fallback URLs.
		if (TravelTaskType.NAME.equals(taskType)
				&& (isStandaloneTravelGoal(command) || isBookingLinkIntent(command))) {
			return command;
		}
		StringBuilder builder = new StringBuilder("当前用户目标：").append(command)
				.append("\n\n以下是同一微信会话的近期上下文，仅用于理解指代和已确认的信息：\n");
		history.stream().skip(Math.max(0, history.size() - 3L)).forEach(turn -> {
			builder.append("用户：").append(limit(turn.userMessage(), 350)).append('\n');
			builder.append("助手：").append(limit(turn.assistantMessage(), 500)).append('\n');
		});
		return limit(builder.toString(), 1_600);
	}

	private static boolean isStandaloneTravelGoal(String command) {
		if (command == null || command.isBlank()) return false;
		String value = command.replaceAll("\\s+", "");
		boolean hasTripShape = value.matches("(?s).*(从.{1,16})?(去|到).{1,24}(玩|游|逛).*(天|日).*");
		boolean hasDate = value.matches("(?s).*(\\d{1,2}月\\d{1,2}日|\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}).*");
		boolean hasPeople = value.matches("(?s).*\\d+个?人.*");
		boolean hasBudget = value.contains("预算");
		boolean asksPlan = value.contains("方案") || value.contains("攻略") || value.contains("行程");
		return hasTripShape && hasDate && hasPeople && hasBudget && asksPlan;
	}

	private static boolean containsAny(String value, String... words) {
		for (String word : words) if (value.contains(word)) return true;
		return false;
	}

	private static String limit(String value, int maxChars) {
		if (value == null) return "";
		return value.length() <= maxChars ? value : value.substring(0, maxChars) + "…";
	}

	private static String completionMessage(AgentTask task) {
		String text = task.getFinalAnswer();
		if (text == null || text.isBlank()) text = task.getErrorSummary();
		if (text == null || text.isBlank()) text = "这次处理没有得到有效结果，请换一种说法再试。";
		if (task.getStatus() == AgentTaskStatus.COMPLETED) return text;
		return "这次没有处理完成：" + text;
	}

	private static Mode parseModeCommand(String value) {
		String lower = value.toLowerCase(Locale.ROOT);
		if ("/agent".equals(lower) || "/agent-mode".equals(lower) || "/mode agent".equals(lower) || "切换到agent模式".equals(value)
				|| "切换到智能体模式".equals(value) || "开启agent模式".equals(value)) return Mode.AGENT;
		if ("/chat".equals(lower) || "/mode chat".equals(lower) || "切换到聊天模式".equals(value)
				|| "关闭agent模式".equals(value)) return Mode.CHAT;
		return null;
	}

	private static String normalizeSession(String sessionId) {
		return sessionId == null || sessionId.isBlank() ? "assistant" : sessionId.trim();
	}

	private static AgentTaskRouter defaultRouter() {
		return new AgentTaskRouter(List.of(
				new TravelTaskType(),
				new DeepResearchTaskType(),
				new LearningCoachTaskType(),
				new DecisionTaskType(),
				new GenericTaskType()));
	}

	private static String displayId(AgentTask task) {
		if (task.getDisplayNumber() > 0) return Integer.toString(task.getDisplayNumber());
		return task.getId() == null ? "?" : task.getId().substring(0, Math.min(8, task.getId().length()));
	}

	@PreDestroy
	void shutdown() { completionWatchers.shutdownNow(); }
}
