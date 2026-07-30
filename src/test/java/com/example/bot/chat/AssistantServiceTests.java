package com.example.bot.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.bot.skills.Skill;
import com.example.bot.skills.SkillContext;
import com.example.bot.skills.SkillContribution;
import com.example.bot.skills.SkillManager;

class AssistantServiceTests {

	private final FunctionCallingService functionCallingService = mock(FunctionCallingService.class);
	private final VisualUnderstandingService visualUnderstandingService = mock(VisualUnderstandingService.class);
	private final AssistantService assistantService = new AssistantService(
			functionCallingService, new ConversationMemory(8, 30),
			visualUnderstandingService);

	@Test
	void shouldEnrichMessageBeforeCallingTheReplyModel() {
		String question = "use my private notes";
		Skill contextSkill = new Skill() {
			@Override public String id() { return "test-context"; }
			@Override public SkillContribution apply(SkillContext context) {
				return new SkillContribution(id(), "context-from-skill");
			}
		};
		AssistantService service = new AssistantService(functionCallingService,
				new ConversationMemory(8, 30), visualUnderstandingService,
				new SkillManager(List.of(contextSkill)));
		when(functionCallingService.reply(eq("rag-session"), eq(question), eq(List.of()),
				contains("context-from-skill"))).thenReturn(AssistantReply.text("answer"));

		AssistantReply reply = service.reply("rag-session", question);

		assertEquals("answer", reply.text());
	}

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
	void shouldUseRecentTripContextWhenReadingATicketSelectionScreenshot() {
		String sessionId = "travel-screenshot-session";
		String tripQuestion = "我选第二个杭州方案，灵隐寺只买基础门票，不要套票";
		String tripAnswer = "好的，后续按轻松深度方案选择基础门票。";
		when(functionCallingService.reply(sessionId, tripQuestion, List.of()))
				.thenReturn(AssistantReply.text(tripAnswer));
		assistantService.reply(sessionId, tripQuestion);

		List<MediaContent> media = List.of(new MediaContent(MediaContent.MediaType.IMAGE,
				new byte[] { 7, 8, 9 }, "image/jpeg"));
		String screenshotQuestion = "按刚才的方案我应该点哪个？";
		when(visualUnderstandingService.analyze(media, screenshotQuestion))
				.thenReturn("页面可见“成人基础票”和“门票+演出套票”两个选项。");
		when(functionCallingService.reply(eq(sessionId), eq(screenshotQuestion),
				eq(List.of(new ConversationTurn(tripQuestion, tripAnswer))),
				contains("不得声称已经代用户点击"), eq(media)))
				.thenReturn(AssistantReply.text("选择“成人基础票”，排除“门票+演出套票”。"));

		AssistantReply reply = assistantService.replyToMedia(
				sessionId, screenshotQuestion, media);

		assertEquals("选择“成人基础票”，排除“门票+演出套票”。", reply.text());
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
