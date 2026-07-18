package com.example.demo.chat;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.demo.weather.DailyWeatherForecast;
import com.example.demo.weather.WeatherForecastResponse;
import com.example.demo.weather.WeatherResponse;
import com.example.demo.weather.WeatherService;

@Service
public class AssistantService {

	private static final Logger log = LoggerFactory.getLogger(AssistantService.class);

	private final QwenService qwenService;
	private final WeatherService weatherService;
	private final WeatherIntentParser weatherIntentParser;
	private final ConversationMemory conversationMemory;
	private final VisualUnderstandingService visualUnderstandingService;
	private final ImageGenerationIntentParser imageGenerationIntentParser;
	private final ImageGenerationService imageGenerationService;

	public AssistantService(QwenService qwenService, WeatherService weatherService,
			WeatherIntentParser weatherIntentParser, ConversationMemory conversationMemory,
			VisualUnderstandingService visualUnderstandingService,
			ImageGenerationIntentParser imageGenerationIntentParser,
			ImageGenerationService imageGenerationService) {
		this.qwenService = qwenService;
		this.weatherService = weatherService;
		this.weatherIntentParser = weatherIntentParser;
		this.conversationMemory = conversationMemory;
		this.visualUnderstandingService = visualUnderstandingService;
		this.imageGenerationIntentParser = imageGenerationIntentParser;
		this.imageGenerationService = imageGenerationService;
	}

	public String understandMedia(String sessionId, String question, List<MediaContent> media) {
		String mediaFacts = visualUnderstandingService.analyze(media);
		String userRequest = question == null || question.isBlank()
				? "请自然地告诉我你从刚才的图片或视频里看到了什么。"
				: question.trim();
		String context = """
				以下是视觉模型从用户刚发送的图片或视频中提取的内容。请把它当作事实依据，结合用户的问题作答。
				不要机械复述，不要使用教程腔；语气要像微信聊天一样自然、友好，可根据画面氛围适度表达感受，但不要编造。

				媒体内容：
				%s
				""".formatted(mediaFacts);
		List<ConversationTurn> history = conversationMemory.history(sessionId);
		String answer = history.isEmpty()
				? qwenService.chatWithContext(userRequest, context)
				: qwenService.chatWithContext(userRequest, context, history);
		conversationMemory.remember(sessionId, "[用户发送了图片或视频] " + userRequest, answer);
		return answer;
	}

	public String chat(String question) {
		return chatWithHistory(question, List.of());
	}

	public String chat(String sessionId, String question) {
		return reply(sessionId, question).text();
	}

	public AssistantReply reply(String sessionId, String question) {
		List<ConversationTurn> history = conversationMemory.history(sessionId);
		AssistantReply reply = replyWithHistory(question, history);
		conversationMemory.remember(sessionId, question, reply.text());
		return reply;
	}

	public void clearConversation(String sessionId) {
		conversationMemory.clear(sessionId);
	}

	private String chatWithHistory(String question, List<ConversationTurn> history) {
		return replyWithHistory(question, history).text();
	}

	private AssistantReply replyWithHistory(String question, List<ConversationTurn> history) {
		boolean hasHistory = history != null && !history.isEmpty();
		String firstAnswer = hasHistory ? qwenService.chat(question, history) : qwenService.chat(question);
		ImageGenerationIntent imageIntent = imageGenerationIntentParser.parse(firstAnswer);
		if (imageIntent.requested()) {
			log.info("千问请求调用文生图模型，提示词长度={}", imageIntent.prompt().length());
			GeneratedImage image = imageGenerationService.generate(imageIntent.prompt());
			return AssistantReply.image("图片生成好了 🎨", image);
		}
		WeatherIntent intent = weatherIntentParser.parse(firstAnswer);
		if (!intent.weatherQuestion()) {
			return AssistantReply.text(firstAnswer);
		}

		log.info("千问请求调用天气系统，查询城市：{}，类型：{}", intent.city(), intent.queryType());
		if (intent.queryType() == WeatherIntent.WeatherQueryType.FORECAST) {
			WeatherForecastResponse forecast = weatherService.getThreeDayForecast(intent.city());
			String answer = hasHistory
					? qwenService.chatWithContext(question, buildForecastContext(forecast), history)
					: qwenService.chatWithContext(question, buildForecastContext(forecast));
			return AssistantReply.text(answer);
		}

		WeatherResponse weather = weatherService.getCurrentWeather(intent.city());
		String answer = hasHistory
				? qwenService.chatWithContext(question, buildWeatherContext(weather), history)
				: qwenService.chatWithContext(question, buildWeatherContext(weather));
		return AssistantReply.text(answer);
	}

	private String buildWeatherContext(WeatherResponse weather) {
		return """
				以下是和风天气 API 返回的实时数据。请仅依据这些数据回答，不要编造天气信息；
				回答末尾注明“数据来源：和风天气”。
				城市：%s
				省份：%s
				天气：%s
				温度：%s℃
				体感温度：%s℃
				湿度：%s%%
				风向：%s
				风力：%s级
				观测时间：%s
				更新时间：%s
				""".formatted(
				weather.city(), weather.province(), weather.weather(), weather.temperature(),
				weather.feelsLike(), weather.humidity(), weather.windDirection(), weather.windScale(),
				weather.observationTime(), weather.updateTime());
	}

	private String buildForecastContext(WeatherForecastResponse forecast) {
		StringBuilder context = new StringBuilder("""
				以下是和风天气 API 返回的未来天气预报。请仅依据这些数据回答用户关于日期和天气的问题，不要编造信息；
				回答末尾注明“数据来源：和风天气”。
				城市：%s
				省份：%s
				更新时间：%s
				未来预报：
				""".formatted(forecast.city(), forecast.province(), forecast.updateTime()));
		for (DailyWeatherForecast day : forecast.daily()) {
			context.append("日期：").append(day.date())
					.append("，白天天气：").append(day.weatherDay())
					.append("，夜间天气：").append(day.weatherNight())
					.append("，最低温：").append(day.tempMin()).append("℃")
					.append("，最高温：").append(day.tempMax()).append("℃")
					.append("，湿度：").append(day.humidity()).append("%")
					.append("，风向：").append(day.windDirection())
					.append("，风力：").append(day.windScale()).append("级")
					.append("，预计降水：").append(day.precipitation()).append("毫米\n");
		}
		return context.toString();
	}
}
