package com.example.demo.chat;

import java.util.List;

import org.springframework.stereotype.Service;

@Service
public class AssistantService {

	private final FunctionCallingService functionCallingService;
	private final ConversationMemory conversationMemory;
	private final VisualUnderstandingService visualUnderstandingService;

	public AssistantService(FunctionCallingService functionCallingService,
			ConversationMemory conversationMemory,
			VisualUnderstandingService visualUnderstandingService) {
		this.functionCallingService = functionCallingService;
		this.conversationMemory = conversationMemory;
		this.visualUnderstandingService = visualUnderstandingService;
	}

	public String understandMedia(String sessionId, String question, List<MediaContent> media) {
		return replyToMedia(sessionId, question, media).text();
	}

	public AssistantReply replyToMedia(String sessionId, String question, List<MediaContent> media) {
		String mediaFacts = visualUnderstandingService.analyze(media, question);
		String userRequest = question == null || question.isBlank()
				? "请自然地告诉我你从刚才的图片或视频里看到了什么。"
				: question.trim();
		if (isDefaultMediaRecognition(userRequest)) {
			conversationMemory.remember(sessionId, "[用户发送了图片或视频] " + userRequest, mediaFacts);
			return AssistantReply.text(mediaFacts);
		}
		String context = """
				以下是视觉模型从用户刚发送的图片或视频中提取的内容。请把它当作事实依据，结合用户的问题作答。
				用户的媒体已经成功上传并被视觉模型读取。不得声称用户没有发送、忘记发送、未上传图片或文件，也不得声称你看不到附件。
				直接完成用户要求；如果用户没有提出额外要求，就自然描述媒体内容，不要反问用户是否需要重新发送。
				不要机械复述，不要使用教程腔；语气要像微信聊天一样自然、友好，可根据画面氛围适度表达感受，但不要编造。

				媒体内容：
				%s
				""".formatted(mediaFacts);
		AssistantReply reply = functionCallingService.reply(sessionId, userRequest, List.of(), context);
		conversationMemory.remember(sessionId, "[用户发送了图片或视频] " + userRequest, reply.text());
		return reply;
	}

	private static boolean isDefaultMediaRecognition(String request) {
		return request.equals("请自然地告诉我你从刚才的图片或视频里看到了什么。")
				|| request.startsWith("用户已经成功上传了图片。请直接识别并自然描述图片内容")
				|| request.startsWith("请识别并自然描述图片内容");
	}

	public String chat(String question) {
		return functionCallingService.reply("assistant", question, List.of()).text();
	}

	public String chat(String sessionId, String question) {
		return reply(sessionId, question).text();
	}

	public AssistantReply reply(String sessionId, String question) {
		List<ConversationTurn> history = conversationMemory.history(sessionId);
		AssistantReply reply = functionCallingService.reply(sessionId, question, history);
		conversationMemory.remember(sessionId, question, reply.text());
		return reply;
	}

	public void clearConversation(String sessionId) {
		conversationMemory.clear(sessionId);
	}

}
