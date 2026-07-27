package com.example.demo.tools;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.example.demo.search.WebSearchService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonParser;

@Component
public class DailyInfoLookupTool implements AssistantTool {
	private static final ZoneId BEIJING_ZONE = ZoneId.of("Asia/Shanghai");
	private static final FunctionDefinition DEFINITION = FunctionDefinition.builder()
			.name("lookup_daily_info")
			.description("联网查询实时生活信息。category=trending 查询全网热搜榜；air_quality 查询城市实时空气质量指数 AQI；nutrition 查询食物营养成分表；recipe 查询菜谱做法；holiday 查询中国大陆节假日和调休。返回结果必须保留来源，营养数据可能因食材/品牌/烹饪方式不同而变化。")
			.parameters(JsonParser.parseString("""
					{"type":"object","properties":{"category":{"type":"string","enum":["trending","air_quality","nutrition","recipe","holiday"],"description":"信息类型"},"query":{"type":"string","description":"热搜可填平台或主题；空气质量填城市；营养填食物/品牌；菜谱填食材或菜名；节假日可填节日名称"},"amountGrams":{"type":"number","minimum":1,"maximum":5000,"description":"营养查询的食用量（克），默认100"},"year":{"type":"integer","minimum":2020,"maximum":2100,"description":"节假日年份，默认当前年"},"dietaryPreference":{"type":"string","description":"菜谱偏好，例如低脂、素食、少盐、20分钟内"}},"required":["category"],"additionalProperties":false}
					""").getAsJsonObject()).build();

	private final WebSearchService webSearchService;
	private final ObjectMapper objectMapper;
	private final Clock clock;

	@Autowired
	public DailyInfoLookupTool(WebSearchService webSearchService, ObjectMapper objectMapper) {
		this(webSearchService, objectMapper, Clock.system(BEIJING_ZONE));
	}

	DailyInfoLookupTool(WebSearchService webSearchService, ObjectMapper objectMapper, Clock clock) {
		this.webSearchService = webSearchService;
		this.objectMapper = objectMapper;
		this.clock = clock;
	}

	@Override public FunctionDefinition definition() { return DEFINITION; }

	@Override
	public ToolResult execute(JsonNode arguments, ToolContext context) {
		String category = arguments.path("category").asText("").trim();
		String query = arguments.path("query").asText("").trim();
		if (query.length() > 160) throw new IllegalArgumentException("query 过长，最多 160 个字符");
		String searchQuery = switch (category) {
			case "trending" -> trendingQuery(query);
			case "air_quality" -> airQualityQuery(required(query, "空气质量查询需要城市"));
			case "nutrition" -> nutritionQuery(required(query, "营养查询需要食物名称"), arguments.path("amountGrams").asDouble(100d));
			case "recipe" -> recipeQuery(required(query, "菜谱查询需要菜名或食材"), arguments.path("dietaryPreference").asText(""));
			case "holiday" -> holidayQuery(query, arguments.path("year").asInt(LocalDate.now(clock).getYear()));
			default -> throw new IllegalArgumentException("category 不支持：" + category);
		};
		String answer = webSearchService.search(searchQuery);
		var response = objectMapper.createObjectNode();
		response.put("category", category);
		response.put("searchedFor", searchQuery);
		response.put("result", answer);
		if ("nutrition".equals(category)) {
			response.put("notice", "营养数值受食材品种、品牌、可食部分和烹饪方式影响，仅供饮食参考。");
		}
		if ("air_quality".equals(category)) {
			response.put("notice", "AQI 和健康建议以检索结果注明的当地标准与观测时间为准。");
		}
		return answer.startsWith("联网搜索没有返回有效结果") ? ToolResult.noResult(response.toString()) : ToolResult.text(response.toString());
	}

	private String trendingQuery(String query) {
		String target = query.isBlank() ? "微博、百度、知乎、抖音、B站" : query;
		return LocalDate.now(clock) + " 中国全网热搜榜 " + target
				+ "。列出各平台热门话题、热度/排名（若有）、更新时间和来源链接；不要把旧榜单当成今日数据。";
	}

	private static String airQualityQuery(String city) {
		return city + " 实时空气质量 AQI PM2.5 PM10 首要污染物 观测时间 健康建议。优先官方环境监测或可靠天气数据来源。";
	}

	private static String nutritionQuery(String food, double amount) {
		if (!Double.isFinite(amount) || amount <= 0d || amount > 5000d) throw new IllegalArgumentException("amountGrams 必须在 1 到 5000 之间");
		return food + " 营养成分表 每 " + String.format(Locale.ROOT, "%.0f", amount)
				+ " 克：能量、蛋白质、脂肪、碳水化合物、膳食纤维、糖、钠。优先食品标签、权威营养数据库或官方来源，并注明食材状态（生/熟/品牌）。";
	}

	private static String recipeQuery(String food, String preference) {
		String constraint = preference == null || preference.isBlank() ? "" : "，要求：" + preference.trim();
		return food + " 家常菜谱做法：列出食材用量、关键步骤、预计耗时和火候要点" + constraint + "。";
	}

	private static String holidayQuery(String query, int year) {
		if (year < 2020 || year > 2100) throw new IllegalArgumentException("year 必须在 2020 到 2100 之间");
		String target = query == null || query.isBlank() ? "全部法定节假日" : query.trim();
		return year + " 年中国大陆 " + target + " 放假和调休安排。优先中国政府网、国务院办公厅或权威政府网站，说明具体日期、补班日期和公告来源。";
	}

	private static String required(String value, String message) {
		if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
		return value;
	}
}
