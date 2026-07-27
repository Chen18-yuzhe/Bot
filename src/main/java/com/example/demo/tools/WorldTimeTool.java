package com.example.demo.tools;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonParser;

@Component
public class WorldTimeTool implements AssistantTool {
	private static final ZoneId BEIJING_ZONE = ZoneId.of("Asia/Shanghai");
	private static final int MAX_LOCATIONS = 8;
	private static final Map<String, String> COMMON_ZONES = Map.ofEntries(
			Map.entry("北京", "Asia/Shanghai"), Map.entry("中国", "Asia/Shanghai"),
			Map.entry("上海", "Asia/Shanghai"), Map.entry("杭州", "Asia/Shanghai"),
			Map.entry("香港", "Asia/Hong_Kong"), Map.entry("澳门", "Asia/Macau"),
			Map.entry("台北", "Asia/Taipei"), Map.entry("台湾", "Asia/Taipei"),
			Map.entry("东京", "Asia/Tokyo"), Map.entry("日本", "Asia/Tokyo"),
			Map.entry("首尔", "Asia/Seoul"), Map.entry("韩国", "Asia/Seoul"),
			Map.entry("新加坡", "Asia/Singapore"), Map.entry("曼谷", "Asia/Bangkok"),
			Map.entry("迪拜", "Asia/Dubai"), Map.entry("伦敦", "Europe/London"),
			Map.entry("巴黎", "Europe/Paris"), Map.entry("柏林", "Europe/Berlin"),
			Map.entry("莫斯科", "Europe/Moscow"), Map.entry("纽约", "America/New_York"),
			Map.entry("华盛顿", "America/New_York"), Map.entry("洛杉矶", "America/Los_Angeles"),
			Map.entry("旧金山", "America/Los_Angeles"), Map.entry("芝加哥", "America/Chicago"),
			Map.entry("温哥华", "America/Vancouver"), Map.entry("多伦多", "America/Toronto"),
			Map.entry("悉尼", "Australia/Sydney"), Map.entry("墨尔本", "Australia/Melbourne"));
	private static final FunctionDefinition DEFINITION = FunctionDefinition.builder()
			.name("query_time")
			.description("查询世界各地区当前日期和时间，自动按时区数据库处理夏令时。未指定地区时默认查询北京时间。比较多个地区时使用locations一次查询，最多8个。")
			.parameters(JsonParser.parseString("""
					{"type":"object","properties":{"location":{"type":"string","description":"单个地区名称，例如北京、纽约、伦敦；省略全部参数时默认北京"},"timeZone":{"type":"string","description":"可选IANA时区，例如Asia/Shanghai、America/New_York；常见地区可省略"},"locations":{"type":"array","minItems":1,"maxItems":8,"description":"批量查询的地区列表，不要与location同时使用","items":{"type":"object","properties":{"location":{"type":"string","description":"地区显示名称"},"timeZone":{"type":"string","description":"可选IANA时区，常见地区可省略"}},"required":["location"],"additionalProperties":false}}},"additionalProperties":false}
					""").getAsJsonObject()).build();

	private final ObjectMapper objectMapper;
	private final Clock clock;

	@Autowired
	public WorldTimeTool(ObjectMapper objectMapper) {
		this(objectMapper, Clock.systemUTC());
	}

	WorldTimeTool(ObjectMapper objectMapper, Clock clock) {
		this.objectMapper = objectMapper;
		this.clock = clock;
	}

	@Override public FunctionDefinition definition() { return DEFINITION; }

	@Override
	public ToolResult execute(JsonNode arguments, ToolContext context) {
		List<LocationRequest> requests = requests(arguments);
		Instant queriedAt = clock.instant();
		var response = objectMapper.createObjectNode();
		response.put("queriedAt", queriedAt.toString());
		response.put("defaultTimeZone", BEIJING_ZONE.getId());
		var results = response.putArray("results");
		for (LocationRequest request : requests) {
			ZonedDateTime current = queriedAt.atZone(request.zone());
			var item = results.addObject();
			item.put("location", request.location());
			item.put("timeZone", request.zone().getId());
			item.put("date", current.toLocalDate().toString());
			item.put("time", current.toLocalTime().withNano(0).toString());
			item.put("dayOfWeek", current.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINA));
			item.put("utcOffset", current.getOffset().getId());
			item.put("isoDateTime", current.withNano(0).toString());
		}
		return ToolResult.text(response.toString());
	}

	private static List<LocationRequest> requests(JsonNode arguments) {
		JsonNode batch = arguments.path("locations");
		String singleLocation = arguments.path("location").asText("").trim();
		String singleZone = arguments.path("timeZone").asText("").trim();
		if (batch.isArray() && !batch.isEmpty() && (!singleLocation.isEmpty() || !singleZone.isEmpty())) {
			throw new IllegalArgumentException("location/timeZone 和 locations 不能同时提供");
		}
		if (batch.isArray() && !batch.isEmpty()) {
			if (batch.size() > MAX_LOCATIONS) throw new IllegalArgumentException("一次最多查询 8 个地区");
			Map<String, LocationRequest> unique = new LinkedHashMap<>();
			for (JsonNode item : batch) {
				String location = item.path("location").asText("").trim();
				if (location.isEmpty()) throw new IllegalArgumentException("地区名称不能为空");
				String zone = item.path("timeZone").asText("").trim();
				LocationRequest request = request(location, zone);
				unique.putIfAbsent(request.zone().getId(), request);
			}
			return new ArrayList<>(unique.values());
		}
		if (batch.isArray()) throw new IllegalArgumentException("locations 不能为空数组");
		if (singleLocation.isEmpty() && singleZone.isEmpty()) {
			return List.of(new LocationRequest("北京", BEIJING_ZONE));
		}
		String location = singleLocation.isEmpty() ? singleZone : singleLocation;
		return List.of(request(location, singleZone));
	}

	private static LocationRequest request(String location, String providedZone) {
		String zoneName = providedZone;
		if (zoneName == null || zoneName.isBlank()) {
			zoneName = COMMON_ZONES.getOrDefault(location, location);
		}
		try {
			return new LocationRequest(location, ZoneId.of(zoneName));
		} catch (Exception exception) {
			throw new IllegalArgumentException("无法确定“" + location + "”的时区，请提供IANA时区，例如 Asia/Shanghai");
		}
	}

	private record LocationRequest(String location, ZoneId zone) { }
}
