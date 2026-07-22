package com.example.demo.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

class AssistantServiceTests {

	private final FunctionCallingService functionCallingService = mock(FunctionCallingService.class);
	private final VisualUnderstandingService visualUnderstandingService = mock(VisualUnderstandingService.class);
	private final AssistantService assistantService = new AssistantService(
			functionCallingService, new ConversationMemory(8, 30),
			visualUnderstandingService);

	@Test
	void shouldRouteExplicitImageRequestToWanModel() {
		String question = "给我画一只在月球上的橘猫";
		GeneratedImage generated = new GeneratedImage(new byte[] { 1, 2 }, "image/png", "wan.png");
		when(functionCallingService.reply("wechat-user-image", question, List.of()))
				.thenReturn(AssistantReply.image("图片生成好了 🎨", generated));

		AssistantReply reply = assistantService.reply("wechat-user-image", question);

		assertEquals("图片生成好了 🎨", reply.text());
		assertEquals(generated, reply.image());
	}

	@Test
	void shouldPolishVisualAnalysisBeforeReplying() {
		List<MediaContent> media = List.of(new MediaContent(MediaContent.MediaType.IMAGE,
				new byte[] { 1, 2, 3 }, "image/jpeg"));
		when(visualUnderstandingService.analyze(media, "")).thenReturn("海滩上，一个人与一只狗面对面坐着。");
		String answer = assistantService.understandMedia("wechat-user-1", "", media);

		assertEquals("海滩上，一个人与一只狗面对面坐着。", answer);
	}

	@Test
	void shouldRouteImageAndTextGenerationRequestWithoutDependingOnHistory() {
		List<MediaContent> media = List.of(new MediaContent(MediaContent.MediaType.IMAGE,
				new byte[] { 1, 2, 3 }, "image/png"));
		String question = "生成一张类似的图片，把豆包放最上边";
		GeneratedImage generated = new GeneratedImage(new byte[] { 4, 5 }, "image/png", "wan.png");
		when(visualUnderstandingService.analyze(media, question)).thenReturn("暗色史诗场景，豆包标志原本位于中间。");
		when(functionCallingService.reply(eq("new-session"), eq(question), eq(List.of()),
				contains("豆包标志原本位于中间"), eq(media)))
				.thenReturn(AssistantReply.image("图片生成好了 🎨", generated));

		AssistantReply reply = assistantService.replyToMedia("new-session", question, media);

		assertEquals(generated, reply.image());
	}

	@Test
	void shouldUseQwenDirectlyForNormalQuestion() {
		when(functionCallingService.reply("assistant", "你是谁？", List.of()))
				.thenReturn(AssistantReply.text("我是AI助手"));

		String answer = assistantService.chat("你是谁？");

		assertEquals("我是AI助手", answer);
	}

	@Test
	void shouldQueryCurrentWeatherAndGiveItToQwen() {
		String question = "北京今天天气怎么样？";
		when(functionCallingService.reply("assistant", question, List.of()))
				.thenReturn(AssistantReply.text("北京现在晴，30℃。数据来源：和风天气"));

		String answer = assistantService.chat(question);

		assertEquals("北京现在晴，30℃。数据来源：和风天气", answer);
	}

	@Test
	void shouldQueryForecastAndGiveItToQwen() {
		String question = "北京未来几天天气怎么样？";
		when(functionCallingService.reply("assistant", question, List.of()))
				.thenReturn(AssistantReply.text("未来几天以晴到多云为主。"));

		String answer = assistantService.chat(question);

		assertEquals("未来几天以晴到多云为主。", answer);
	}

	@Test
	void shouldReturnQwenFollowUpWhenWeatherCityIsMissing() {
		when(functionCallingService.reply("assistant", "今天天气怎么样？", List.of()))
				.thenReturn(AssistantReply.text("请告诉我你想查询哪个城市的天气。"));

		String answer = assistantService.chat("今天天气怎么样？");

		assertEquals("请告诉我你想查询哪个城市的天气。", answer);
	}

	@Test
	void shouldNotCallWeatherForQuestionAboutWeatherCapability() {
		String question = "你为啥不能查天气";
		when(functionCallingService.reply("assistant", question, List.of()))
				.thenReturn(AssistantReply.text("我可以帮你查询天气，请告诉我城市。"));

		String answer = assistantService.chat(question);

		assertEquals("我可以帮你查询天气，请告诉我城市。", answer);
	}

	@Test
	void shouldPassPreviousTurnsForTheSameWechatUser() {
		when(functionCallingService.reply("wechat-user-1", "我叫小陈", List.of()))
				.thenReturn(AssistantReply.text("你好，小陈。"));
		when(functionCallingService.reply("wechat-user-1", "我叫什么？",
				List.of(new ConversationTurn("我叫小陈", "你好，小陈。"))))
				.thenReturn(AssistantReply.text("你叫小陈。"));

		assistantService.chat("wechat-user-1", "我叫小陈");
		String answer = assistantService.chat("wechat-user-1", "我叫什么？");

		assertEquals("你叫小陈。", answer);
	}
}
