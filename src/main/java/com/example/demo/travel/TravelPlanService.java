package com.example.demo.travel;

import static com.example.demo.travel.TravelModels.*;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.example.demo.ApplicationException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class TravelPlanService {
	private static final Map<String, List<AttractionSeed>> CATALOG = Map.of(
			"北京", List.of(
					new AttractionSeed("故宫博物院", "世界文化遗产，适合历史与建筑主题游览。", "约 4 小时"),
					new AttractionSeed("天坛公园", "明清皇家祭祀建筑群，适合建筑与摄影。", "约 2.5 小时"),
					new AttractionSeed("颐和园", "大型皇家园林，适合慢游与自然景观。", "约 4 小时"),
					new AttractionSeed("中国国家博物馆", "系统了解中国历史文化，通常需要预约。", "约 3 小时"),
					new AttractionSeed("什刹海", "胡同、湖区与城市生活结合的步行区域。", "约 2 小时"),
					new AttractionSeed("慕田峪长城", "长城代表性段落，往返市区需预留交通时间。", "约 6 小时")),
			"杭州", List.of(
					new AttractionSeed("西湖风景名胜区", "杭州代表性湖泊景观，适合步行、骑行和游船。", "约 4 小时"),
					new AttractionSeed("灵隐寺", "历史悠久的佛教寺院与山林景观。", "约 3 小时"),
					new AttractionSeed("西溪国家湿地公园", "城市湿地生态景观，适合自然与亲子主题。", "约 4 小时"),
					new AttractionSeed("中国茶叶博物馆", "了解中国茶文化与龙井产区。", "约 2 小时"),
					new AttractionSeed("良渚博物院", "展示良渚文明与考古成果，通常需预约。", "约 3 小时"),
					new AttractionSeed("河坊街", "传统街区与地方小吃集中区域。", "约 2 小时")),
			"上海", List.of(
					new AttractionSeed("上海博物馆", "中国古代艺术收藏丰富，适合人文主题。", "约 3 小时"),
					new AttractionSeed("外滩", "近代建筑与浦江城市景观集中区域。", "约 2 小时"),
					new AttractionSeed("豫园", "江南古典园林与老城厢文化。", "约 2.5 小时"),
					new AttractionSeed("上海科技馆", "适合亲子与科普主题，开放状态需实时确认。", "约 4 小时"),
					new AttractionSeed("武康路历史文化名街", "适合城市漫步、建筑和咖啡主题。", "约 2 小时"),
					new AttractionSeed("中华艺术宫", "大型现代艺术展馆，展览信息需实时确认。", "约 3 小时")),
			"成都", List.of(
					new AttractionSeed("成都大熊猫繁育研究基地", "观察大熊猫与保育科普，建议早间游览。", "约 4 小时"),
					new AttractionSeed("金沙遗址博物馆", "古蜀文明重要考古遗址与博物馆。", "约 3 小时"),
					new AttractionSeed("武侯祠", "三国文化与历史建筑代表景点。", "约 2.5 小时"),
					new AttractionSeed("杜甫草堂", "诗歌文化、园林与历史建筑结合。", "约 2.5 小时"),
					new AttractionSeed("宽窄巷子", "传统街区与城市休闲区域。", "约 2 小时"),
					new AttractionSeed("青城山", "道教文化与山林徒步，需安排近一整天。", "约 7 小时")),
			"西安", List.of(
					new AttractionSeed("秦始皇帝陵博物院", "兵马俑所在地，需预留市区往返时间。", "约 6 小时"),
					new AttractionSeed("陕西历史博物馆", "系统展示周秦汉唐历史，通常需要预约。", "约 3 小时"),
					new AttractionSeed("西安城墙", "保存完整的古代城垣，适合步行或骑行。", "约 3 小时"),
					new AttractionSeed("大雁塔文化休闲景区", "唐代佛教建筑与城市公共文化空间。", "约 2.5 小时"),
					new AttractionSeed("西安碑林博物馆", "碑刻与书法艺术收藏集中。", "约 2.5 小时"),
					new AttractionSeed("大唐不夜城", "夜间文化街区，演出安排需实时确认。", "约 2 小时")));

	private final Map<String, StoredPlan> plans = new ConcurrentHashMap<>();
	private final Map<String, String> latestPlanByUser = new ConcurrentHashMap<>();
	private final ObjectMapper objectMapper;
	private final TravelStateStore stateStore;

	@Autowired
	public TravelPlanService(ObjectMapper objectMapper, TravelStateStore stateStore) {
		this.objectMapper = objectMapper;
		this.stateStore = stateStore;
	}

	public TravelPlanService() {
		this(new ObjectMapper(), null);
	}

	public PlanResponse createPlan(PlanRequest input) {
		RequestSummary request = validate(input);
		String planId = "plan_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
		long base = Math.max(18_000L, request.travelers() * request.days() * 24_000L);
		List<PlanOption> options = List.of(
				option("balanced", "均衡经典线", "适合首次到访，兼顾代表景点与节奏", base, request),
				option("slow", "轻松深度线", "减少换乘，留出更多自由活动时间", base - request.travelers() * request.days() * 2_000L, request),
				option("culture", "兴趣主题线", "围绕你的兴趣安排更多主题场馆和体验", base + request.travelers() * request.days() * 5_000L, request));
		PlanResponse response = new PlanResponse(planId, request, options,
				new ProviderStatus("待接入地图 API", "内置基础目录；建议接入景点 POI API", "由天气工具按预报窗口核验", "待用户选定方案后核验",
						"当前费用为估算，不是实时票价；路线距离、开放安排和下单前票价均需复核。"),
				java.time.Instant.now().toString());
		plans.put(planId, new StoredPlan(input.userId(), response));
		latestPlanByUser.put(input.userId(), planId);
		if (stateStore != null) {
			stateStore.put(TravelStateStore.PLAN, planId, input.userId(), serialize(response),
					stateStore.defaultExpiry(TravelStateStore.PLAN));
		}
		return response;
	}

	public PlanResponse getPlan(String planId, String userId) {
		StoredPlan plan = plans.get(planId);
		if (plan == null && stateStore != null) {
			plan = stateStore.get(TravelStateStore.PLAN, planId, userId)
					.map(json -> new StoredPlan(userId, deserialize(json, PlanResponse.class)))
					.orElse(null);
			if (plan != null) plans.put(planId, plan);
		}
		if (plan == null || !plan.userId().equals(userId)) throw new ApplicationException(HttpStatus.NOT_FOUND, "未找到行程方案，请重新生成");
		return plan.response();
	}

	public PlanResponse getLatestPlan(String userId) {
		String id = latestPlanByUser.get(userId);
		if (id != null) return getPlan(id, userId);
		if (stateStore != null) {
			PlanResponse response = stateStore.getLatest(TravelStateStore.PLAN, userId)
					.map(json -> deserialize(json, PlanResponse.class)).orElse(null);
			if (response != null) {
				plans.put(response.planId(), new StoredPlan(userId, response));
				latestPlanByUser.put(userId, response.planId());
				return response;
			}
		}
		throw new ApplicationException(HttpStatus.NOT_FOUND, "当前会话还没有行程方案，请先生成方案");
	}

	public List<Attraction> discoverAttractions(String destination, List<String> interests) {
		List<AttractionSeed> seeds = catalog(destination);
		String interestNote = interests == null || interests.isEmpty()
				? "" : "；与你的兴趣“" + String.join("、", interests) + "”的匹配度由 Agent 进一步比较";
		List<Attraction> result = new ArrayList<>();
		for (int index = 0; index < seeds.size(); index++) {
			AttractionSeed seed = seeds.get(index);
			result.add(new Attraction(slug(destination) + "-poi-" + (index + 1), seed.name(),
					seed.introduction() + interestNote, seed.duration(),
					"实时距离、路况和交通方式需接入地图 API 后确认"));
		}
		return result;
	}

	private static RequestSummary validate(PlanRequest input) {
		if (input == null) throw new IllegalArgumentException("请求体不能为空");
		if (blank(input.userId()) || blank(input.destination())) throw new IllegalArgumentException("userId 和 destination 为必填项");
		if (input.destination().trim().length() > 80) throw new IllegalArgumentException("目的地不能超过 80 个字符");
		LocalDate departure;
		try { departure = LocalDate.parse(input.departureDate(), DateTimeFormatter.ISO_LOCAL_DATE); }
		catch (Exception exception) { throw new IllegalArgumentException("departureDate 必须是 yyyy-MM-dd"); }
		if (departure.isBefore(LocalDate.now(ZoneOffset.UTC))) throw new IllegalArgumentException("出发日期不能早于今天");
		if (input.days() == null || input.days() < 1 || input.days() > 14) throw new IllegalArgumentException("days 必须在 1 到 14 天之间");
		if (input.travelers() == null || input.travelers() < 1 || input.travelers() > 12) throw new IllegalArgumentException("travelers 必须在 1 到 12 人之间");
		if (input.budgetCents() == null || input.budgetCents() < 10_000L) throw new IllegalArgumentException("budgetCents 至少为 10000（单位：分）");
		List<String> interests = input.interests() == null ? List.of() : input.interests().stream().filter(v -> !blank(v)).map(String::trim).limit(8).toList();
		return new RequestSummary(input.destination().trim(), departure.toString(), input.days(), input.travelers(), input.budgetCents(), interests);
	}

	private static PlanOption option(String kind, String title, String positioning, long amount, RequestSummary request) {
		LocalDate start = LocalDate.parse(request.departureDate());
		List<DayItinerary> days = new ArrayList<>();
		List<AttractionSeed> catalog = catalog(request.destination());
		int offset = switch (kind) {
			case "slow" -> 1;
			case "culture" -> 3;
			default -> 0;
		};
		for (int index = 0; index < request.days(); index++) {
			String theme = request.interests().isEmpty() ? "城市人文" : request.interests().get(index % request.interests().size());
			AttractionSeed first = catalog.get((offset + index * 2) % catalog.size());
			AttractionSeed second = catalog.get((offset + index * 2 + 1) % catalog.size());
			List<Attraction> attractions = List.of(
					new Attraction(slug(request.destination()) + "-" + kind + "-" + (index + 1) + "a",
							first.name(), first.introduction() + " 主题侧重：" + theme + "。开放与预约信息需实时复核。",
							first.duration(), "建议使用地图路线服务确认出发点与实时路况"),
					new Attraction(slug(request.destination()) + "-" + kind + "-" + (index + 1) + "b",
							second.name(), second.introduction() + " 主题侧重：" + theme + "。开放与预约信息需实时复核。",
							second.duration(), "与上一站的真实距离需接入地图 API 后确认"));
				days.add(new DayItinerary(index + 1, start.plusDays(index).toString(), "住宿地 → " + attractions.get(0).name() + " → " + attractions.get(1).name() + " → 住宿地",
						attractions, "仅当天气工具返回结果覆盖本日期时才可引用预报；否则出发前 7 天、3 天和当天复核。", amount / request.days()));
		}
		return new PlanOption(kind, title, positioning, amount, request.budgetCents() - amount, days,
				List.of("票价、库存、开放时间并非实时数据，确认前请执行报价复核。", "路线为区域聚合建议，接入地图后将生成导航链接和耗时。"));
	}

	private static boolean blank(String value) { return value == null || value.isBlank(); }
	private static List<AttractionSeed> catalog(String destination) {
		if (destination != null) {
			for (Map.Entry<String, List<AttractionSeed>> entry : CATALOG.entrySet()) {
				if (destination.contains(entry.getKey())) return entry.getValue();
			}
		}
		return List.of(
				new AttractionSeed("当地综合博物馆（待 POI 确认）", "优先了解目的地历史与文化。", "约 3 小时"),
				new AttractionSeed("城市代表性历史街区（待 POI 确认）", "适合步行了解地方建筑与生活。", "约 2 小时"),
				new AttractionSeed("当地自然景观（待 POI 确认）", "根据季节、天气和交通进一步筛选。", "约 4 小时"),
				new AttractionSeed("城市地标（待 POI 确认）", "适合首次到访者建立城市印象。", "约 2 小时"),
				new AttractionSeed("地方文化体验（待供应商确认）", "根据兴趣匹配非遗、演出或手作活动。", "约 2 小时"),
				new AttractionSeed("特色餐饮街区（待 POI 确认）", "结合预算、饮食偏好和营业时间筛选。", "约 2 小时"));
	}
	private static String slug(String value) {
		return Integer.toUnsignedString((value == null ? "" : value).hashCode(), 36);
	}
	private String serialize(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (Exception exception) {
			throw new ApplicationException(HttpStatus.INTERNAL_SERVER_ERROR, "无法保存旅游方案", exception);
		}
	}
	private <T> T deserialize(String json, Class<T> type) {
		try {
			return objectMapper.readValue(json, type);
		} catch (Exception exception) {
			throw new ApplicationException(HttpStatus.INTERNAL_SERVER_ERROR, "无法读取旅游方案", exception);
		}
	}
	private record AttractionSeed(String name, String introduction, String duration) { }
	private record StoredPlan(String userId, PlanResponse response) { }
}
