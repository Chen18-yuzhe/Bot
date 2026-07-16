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

	public AssistantService(QwenService qwenService, WeatherService weatherService,
			WeatherIntentParser weatherIntentParser, ConversationMemory conversationMemory) {
		this.qwenService = qwenService;
		this.weatherService = weatherService;
		this.weatherIntentParser = weatherIntentParser;
		this.conversationMemory = conversationMemory;
	}

	public String chat(String question) {
		return chatWithHistory(question, List.of());
	}

	public String chat(String sessionId, String question) {
		List<ConversationTurn> history = conversationMemory.history(sessionId);
		String answer = chatWithHistory(question, history);
		conversationMemory.remember(sessionId, question, answer);
		return answer;
	}

	public void clearConversation(String sessionId) {
		conversationMemory.clear(sessionId);
	}

	private String chatWithHistory(String question, List<ConversationTurn> history) {
		boolean hasHistory = history != null && !history.isEmpty();
		String firstAnswer = hasHistory ? qwenService.chat(question, history) : qwenService.chat(question);
		WeatherIntent intent = weatherIntentParser.parse(firstAnswer);
		if (!intent.weatherQuestion()) {
			return firstAnswer;
		}

		log.info("千问请求调用天气系统，查询城市：{}，类型：{}", intent.city(), intent.queryType());
		if (intent.queryType() == WeatherIntent.WeatherQueryType.FORECAST) {
			WeatherForecastResponse forecast = weatherService.getThreeDayForecast(intent.city());
			return hasHistory
					? qwenService.chatWithContext(question, buildForecastContext(forecast), history)
					: qwenService.chatWithContext(question, buildForecastContext(forecast));
		}

		WeatherResponse weather = weatherService.getCurrentWeather(intent.city());
		return hasHistory
				? qwenService.chatWithContext(question, buildWeatherContext(weather), history)
				: qwenService.chatWithContext(question, buildWeatherContext(weather));
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
