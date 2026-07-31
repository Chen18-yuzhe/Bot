package com.example.bot.chat;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Decides when recent dialogue is still useful for the current request.
 *
 * <p>An unfinished or failed operation is not an implicit conversation lock.
 * A new standalone request starts with a clean context unless the user clearly
 * refers back to the previous turn.</p>
 */
public final class ConversationContextPolicy {
	private static final Pattern EXPLICIT_FOLLOW_UP = Pattern.compile(
			".*(?:继续|接着|刚才|上次|上个|上面|前面|这个|那个|它|其|第[一二三四五六七八九十\\d]+个|"
			+ "再试|重试|恢复|按之前|还是刚才|为什么|怎么办|详细说|展开说).*",
			Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
	private static final Pattern EXPLICIT_NEW_TOPIC = Pattern.compile(
			"^(?:换个问题|换一个问题|另一个问题|另外|对了|说点别的|不说这个了)(?:[，,:：\\s]|$).*",
			Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
	private static final Pattern UNFINISHED_REPLY = Pattern.compile(
			".*(?:未完成|没有处理完成|未获准|未授权|请配置|等待授权|等待人工|需要授权|"
			+ "暂时无法|调用失败|执行失败|处理失败|请稍后再试|缺少必要信息).*",
			Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

	private ConversationContextPolicy() {
	}

	public static List<ConversationTurn> relevantHistory(
			String question, List<ConversationTurn> history) {
		if (history == null || history.isEmpty()) return List.of();
		String current = question == null ? "" : question.trim();
		if (current.isEmpty()) return history;
		if (EXPLICIT_NEW_TOPIC.matcher(current).matches()) return List.of();
		if (EXPLICIT_FOLLOW_UP.matcher(current).matches()) return history;

		ConversationTurn last = history.get(history.size() - 1);
		String previousAnswer = last.assistantMessage() == null
				? "" : last.assistantMessage().toLowerCase(Locale.ROOT);
		return UNFINISHED_REPLY.matcher(previousAnswer).matches() ? List.of() : history;
	}
}
