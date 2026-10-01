package com.example.bot.agent;

import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;

import com.example.bot.chat.ConversationTurn;

/**
 * Capability-based Agent task router. Task types self-describe their matching
 * rules and priority; the router contains no scenario-specific branches.
 */
@Component
public class AgentTaskRouter {
	private final List<AgentTaskType> taskTypes;
	private final AgentTaskType fallback;

	public AgentTaskRouter(List<AgentTaskType> taskTypes) {
		this.taskTypes = taskTypes.stream()
				.sorted(Comparator.comparingInt(AgentTaskType::routingPriority).reversed()
						.thenComparing(AgentTaskType::name))
				.toList();
		this.fallback = this.taskTypes.stream()
				.filter(type -> GenericTaskType.NAME.equals(type.name()))
				.findFirst()
				.orElseThrow(() -> new IllegalStateException("缺少 generic AgentTaskType"));
	}

	public AgentTaskType route(String command, List<ConversationTurn> history) {
		String context = requestContext(command, latestTurn(history));
		return taskTypes.stream()
				.filter(type -> !GenericTaskType.NAME.equals(type.name()))
				.filter(type -> type.supports(context))
				.findFirst()
				.orElse(fallback);
	}

	public boolean shouldAutoStart(String command, List<ConversationTurn> history) {
		String currentRequest = requestContext(command, List.of());
		var explicitType = taskTypes.stream()
				.filter(type -> !GenericTaskType.NAME.equals(type.name()))
				.filter(type -> type.supports(currentRequest))
				.findFirst();
		if (explicitType.isPresent()) {
			return explicitType.get().autoStart(currentRequest);
		}
		String context = requestContext(command, latestTurn(history));
		return taskTypes.stream().anyMatch(type -> type.supports(context) && type.autoStart(context));
	}

	private static List<ConversationTurn> latestTurn(List<ConversationTurn> history) {
		if (history == null || history.isEmpty()) return List.of();
		return List.of(history.get(history.size() - 1));
	}

	static String requestContext(String command, List<ConversationTurn> history) {
		StringBuilder context = new StringBuilder(command == null ? "" : command);
		if (history != null) {
			history.stream().skip(Math.max(0, history.size() - 4L)).forEach(turn ->
					context.append('\n').append(turn.userMessage()).append('\n')
							.append(turn.assistantMessage()));
		}
		return context.toString();
	}
}
