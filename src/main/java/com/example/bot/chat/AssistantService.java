package com.example.bot.chat;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.example.bot.skills.SkillContext;
import com.example.bot.skills.SkillManager;

@Service
public class AssistantService {

	private final FunctionCallingService functionCallingService;
	private final ConversationMemory conversationMemory;
	private final VisualUnderstandingService visualUnderstandingService;
	private final SkillManager skillManager;

	@Autowired
	public AssistantService(FunctionCallingService functionCallingService,
			ConversationMemory conversationMemory,
			VisualUnderstandingService visualUnderstandingService,
			SkillManager skillManager) {
		this.functionCallingService = functionCallingService;
		this.conversationMemory = conversationMemory;
		this.visualUnderstandingService = visualUnderstandingService;
		this.skillManager = skillManager;
	}

	/** Kept for focused unit tests that do not need the enrichment pipeline. */
	AssistantService(FunctionCallingService functionCallingService,
			ConversationMemory conversationMemory,
			VisualUnderstandingService visualUnderstandingService) {
		this(functionCallingService, conversationMemory, visualUnderstandingService, new SkillManager(List.of()));
	}

	public String understandMedia(String sessionId, String question, List<MediaContent> media) {
		return replyToMedia(sessionId, question, media).text();
	}

	public AssistantReply replyToMedia(String sessionId, String question, List<MediaContent> media) {
		boolean hasDocumentOrAudio = media != null && media.stream().anyMatch(item ->
				item.type() == MediaContent.MediaType.DOCUMENT || item.type() == MediaContent.MediaType.AUDIO);
		if (hasDocumentOrAudio) {
			String request = question == null || question.isBlank() ? "请处理我刚上传的附件" : question.trim();
			String turnQuestion = "[用户发送了附件] " + request;
			String context = "用户本轮已经上传附件。附件字节只能由工具通过 ToolContext 读取，不得声称没有收到附件。";
			List<ConversationTurn> history = conversationMemory.history(sessionId);
			context = mergeContexts(context, enrich(sessionId, request, history, media));
			conversationMemory.rememberIncoming(sessionId, turnQuestion);
			try {
				AssistantReply reply = functionCallingService.reply(sessionId, request, history, context, media);
				conversationMemory.remember(sessionId, turnQuestion, reply.text());
				return reply;
			} catch (RuntimeException error) {
				conversationMemory.remember(sessionId, turnQuestion, "（回复生成失败，请稍后重试）");
				throw error;
			}
		}
		String userRequest = question == null || question.isBlank()
				? "请自然地告诉我你从刚才的图片或视频里看到了什么。"
				: question.trim();
		String turnQuestion = "[用户发送了图片或视频] " + userRequest;
		List<ConversationTurn> history = conversationMemory.history(sessionId);
		conversationMemory.rememberIncoming(sessionId, turnQuestion);
		try {
			String mediaFacts = visualUnderstandingService.analyze(media, question);
			if (isDefaultMediaRecognition(userRequest)) {
				conversationMemory.remember(sessionId, turnQuestion, mediaFacts);
				return AssistantReply.text(mediaFacts);
			}
			String context = """
					以下是视觉模型从用户刚发送的图片或视频中提取的内容。请把它当作事实依据，结合用户的问题作答。
					用户的媒体已经成功上传并被视觉模型读取。不得声称用户没有发送、忘记发送、未上传图片或文件，也不得声称你看不到附件。
					直接完成用户要求；如果用户没有提出额外要求，就自然描述媒体内容，不要反问用户是否需要重新发送。
					不要机械复述，不要使用教程腔；语气要像微信聊天一样自然、友好，可根据画面氛围适度表达感受，但不要编造。
					如果画面是旅游商品、票种选择、订单或电子票页面，请结合近期对话中的行程选择：
					只根据画面可见内容核对商品名、日期、适用人群、数量、价格、套票内容和退改规则，
					指出应选择的可见选项和应排除的选项。不得声称已经代用户点击、锁票、登录或付款；
					不得完整复述身份证号、手机号、订单号、二维码内容等敏感信息，必要时仅保留末四位。

					媒体内容：
					%s
			""".formatted(mediaFacts);
			context = mergeContexts(context, enrich(sessionId, userRequest, history, media));
			AssistantReply reply = functionCallingService.reply(sessionId, userRequest, history, context, media);
			conversationMemory.remember(sessionId, turnQuestion, reply.text());
			return reply;
		} catch (RuntimeException error) {
			conversationMemory.remember(sessionId, turnQuestion, "（回复生成失败，请稍后重试）");
			throw error;
		}
	}

	private static boolean isDefaultMediaRecognition(String request) {
		return request.equals("请自然地告诉我你从刚才的图片或视频里看到了什么。")
				|| request.startsWith("用户已经成功上传了图片。请直接识别并自然描述图片内容")
				|| request.startsWith("请识别并自然描述图片内容");
	}

	public String chat(String question) {
		return reply("assistant", question).text();
	}

	public String chat(String sessionId, String question) {
		return reply(sessionId, question).text();
	}

	public AssistantReply reply(String sessionId, String question) {
		List<ConversationTurn> history = conversationMemory.history(sessionId);
		conversationMemory.rememberIncoming(sessionId, question);
		try {
			String ragContext = enrich(sessionId, question, history, List.of());
			AssistantReply reply = ragContext.isBlank()
					? functionCallingService.reply(sessionId, question, history)
					: functionCallingService.reply(sessionId, question, history, ragContext);
			conversationMemory.remember(sessionId, question, reply.text());
			return reply;
		} catch (RuntimeException error) {
			conversationMemory.remember(sessionId, question, "（回复生成失败，请稍后重试）");
			throw error;
		}
	}

	public void clearConversation(String sessionId) {
		conversationMemory.clear(sessionId);
	}

	public int clearConversationsByPrefix(String sessionPrefix) {
		return conversationMemory.clearBySessionPrefix(sessionPrefix);
	}

	private String enrich(String sessionId, String question, List<ConversationTurn> history,
			List<MediaContent> media) {
		return skillManager.enrich(new SkillContext(sessionId, question, history, media));
	}

	private static String mergeContexts(String... contexts) {
		return java.util.Arrays.stream(contexts)
				.filter(value -> value != null && !value.isBlank())
				.collect(java.util.stream.Collectors.joining("\n\n"));
	}

}
