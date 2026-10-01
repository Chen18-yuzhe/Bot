package com.example.bot.agent;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/** Multi-step travel planning and ticketing task with a deliberately narrow tool set. */
@Component
public class TravelTaskType implements AgentTaskType {
	public static final String NAME = "travel";
	private static final Pattern URL = Pattern.compile("https?://[^\\s<>\\]\\[）)\"']+",
			Pattern.CASE_INSENSITIVE);
	private static final Pattern PLACEHOLDER_URL = Pattern.compile(
			"(?i)(?:x{4,}|example\\.(?:com|org|net)|placeholder|待替换)");
	private static final Pattern PURCHASE_ENTRY_CLAIM = Pattern.compile(
			"(?s)(?:已|已经|成功|系统已|我已).{0,8}(?:生成|创建|提供).{0,8}(?:购票清单|购票入口|购票导航)");
	private static final String SELF_SERVICE_NOTICE =
			"暂时不能给到您具体的购票页面，麻烦您进入平台自主购票。";

	private static final Set<String> TOOLS = Set.of(
			"travel_plan",
			"travel_discovery",
			"travel_ticket_platform_entries",
			"travel_checkout",
			"travel_transport",
			"travel_fliggy_search",
			"query_weather",
			"search_web");

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public int routingPriority() {
		return 100;
	}

	@Override
	public boolean supports(String requestContext) {
		String value = requestContext == null ? "" : requestContext.toLowerCase(Locale.ROOT);
		return containsAny(value, "旅游", "旅行", "攻略", "行程", "景点", "旅游路线", "旅行路线", "交通路线", "游玩路线", "门票", "票价", "查票",
				"订票", "购票", "退票", "酒店", "目的地", "游玩", "出游", "自由行", "周末游",
				"trip", "travel", "itinerary")
				|| value.matches("(?s).*(去|到).{1,24}(玩|游|逛).*");
	}

	@Override
	public boolean autoStart(String requestContext) {
		return supports(requestContext);
	}

	@Override
	public Set<String> allowedTools() {
		return TOOLS;
	}

	@Override
	public int maxSteps(int configuredDefault) {
		return Math.max(24, configuredDefault);
	}

	@Override
	public long tokenBudget(long configuredDefault) {
		// Ticket-entry work can legitimately need several source checks and
		// structured hand-offs. Keep a generous ceiling while timeout and step
		// limits remain the primary protection against an accidental loop.
		return Math.max(500_000, configuredDefault);
	}

	@Override
	public Duration timeout(Duration configuredDefault) {
		return configuredDefault.compareTo(Duration.ofMinutes(15)) >= 0
				? configuredDefault : Duration.ofMinutes(15);
	}

	@Override
	public RecoveryPolicy recoveryPolicy(RecoveryPolicy configuredDefault) {
		return new RecoveryPolicy(Math.max(3, configuredDefault.transientMaxAttempts()),
				configuredDefault.transientBackoffMs(), true);
	}

	@Override
	public String validateCompletion(AgentTask task, String answer) {
		String text = answer == null ? "" : answer;
		Set<String> sourcedUrls = new HashSet<>();
		Set<String> fixedTicketPlatformUrls = new HashSet<>();
		boolean checkoutCreated = false;
		boolean ticketPlatformEntriesProvided = false;
		for (AgentTranscript.Entry entry : task.getTranscript()) {
			if (!"tool".equals(entry.role())) continue;
			if (entry.text() != null && !"travel_purchase_entry".equals(entry.name())
					&& !"travel_checkout".equals(entry.name())) {
				var matcher = URL.matcher(entry.text());
				while (matcher.find()) sourcedUrls.add(normalizeUrl(matcher.group()));
			}
			if ("travel_checkout".equals(entry.name()) && entry.text() != null
					&& !entry.text().contains("\"success\":false")) {
				checkoutCreated = true;
			}
			if ("travel_ticket_platform_entries".equals(entry.name()) && entry.text() != null) {
				ticketPlatformEntriesProvided = true;
				var platformMatcher = URL.matcher(entry.text());
				while (platformMatcher.find()) fixedTicketPlatformUrls.add(normalizeUrl(platformMatcher.group()));
			}
		}
		boolean purchaseLinkIntent = isPurchaseLinkIntent(task.getGoal());
		Set<String> answerUrlSet = new HashSet<>();
		var answerUrls = URL.matcher(text);
		while (answerUrls.find()) {
			String url = normalizeUrl(answerUrls.group());
			answerUrlSet.add(url);
			if (PLACEHOLDER_URL.matcher(url).find()) {
				return "最终回答包含占位或示例链接。删除该链接，明确说明未找到可验证直达入口，禁止自行补全 URL。";
			}
			if (!sourcedUrls.contains(url)) {
				return "最终回答包含未由工具结果提供的链接：" + url
						+ "。不得猜测链接；删除它并说明只能提供已核验的公众号/小程序操作路径。";
			}
			if (purchaseLinkIntent && !fixedTicketPlatformUrls.contains(url)) {
				return "景点购票链接必须来自 travel_ticket_platform_entries 返回的三个固定平台入口：" + url;
			}
		}
		if (purchaseLinkIntent && (!ticketPlatformEntriesProvided
				|| fixedTicketPlatformUrls.size() != 3
				|| !answerUrlSet.equals(fixedTicketPlatformUrls))) {
			return "景点购票问题必须调用 travel_ticket_platform_entries，并完整返回美团、携程、飞猪三个固定入口。";
		}
		if (purchaseLinkIntent && !text.contains(SELF_SERVICE_NOTICE)) {
			return "景点购票回复必须提醒用户：" + SELF_SERVICE_NOTICE;
		}
		if (PURCHASE_ENTRY_CLAIM.matcher(text).find()
				&& !checkoutCreated && !ticketPlatformEntriesProvided) {
			return "本任务没有成功调用购票入口工具，不能宣称已生成购票清单或购票入口。";
		}
		return null;
	}

	private static boolean isPurchaseLinkIntent(String goal) {
		String value = goal == null ? "" : goal;
		return value.contains("购票") || value.contains("门票") || value.contains("票务")
				|| value.contains("链接") || value.contains("跳转") || value.contains("入口");
	}

	private static String normalizeUrl(String value) {
		String url = value;
		while (url.endsWith(".") || url.endsWith(",") || url.endsWith("。") || url.endsWith("，")) {
			url = url.substring(0, url.length() - 1);
		}
		return url;
	}

	private static boolean containsAny(String value, String... words) {
		for (String word : words) if (value.contains(word)) return true;
		return false;
	}

	@Override
	public String systemPrompt(AgentTask task) {
		String today = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
		return """
				你是微信中的旅游规划与票务执行 Agent。目标是基于同一会话上下文，逐步完成旅行规划、比较和用户明确授权后的票务动作。
				当前日期（Asia/Shanghai）是 %s。用户未说明年份时，必须选择今天之后最近一次符合条件的日期，不得猜测过去的年份。

				执行规则：
				1. 先从当前目标和近期上下文提取目的地、日期、天数、人数、预算、兴趣。缺少会显著改变方案的信息时，先用简短中文询问，不要猜。
				2. 信息齐全后调用行程工具生成至少三种有明显取舍的方案；最终回复要比较节奏、主题、预算和风险，并邀请用户选择。
				3. 天气、网页和票务数据必须标明来源、查询时间及是否实时。用户只是首次要求“给我几种方案/攻略”时，只调用一次 travel_discovery attractions、一次 query_weather 和一次 travel_plan，然后立即输出三种方案；此阶段不要查票、不要逐个查询景点、不要查询酒店或飞猪。天气工具只提供未来三天：若返回日期不覆盖出发日，必须写“出发日尚未进入预报窗口”，不得把当前天气或其它日期的预报套用到出行日。只有用户明确要求交通比较、查票、购票入口或选定方案后，才按需要调用 travel_fliggy_search。供应商未接入或飞猪未返回时只能给估算，绝不能称为实时票价或有库存。
				4. 用户后续说“第二个”“这个方案”“查票”等，先调用 travel_plan latest 读取当前会话最近的结构化方案，再识别 optionId 和景点；不得把聊天里的任务编号当作 planId，也不得要求用户重复全部信息。
				5. 必须先确认用户的出发城市。只有预计路程超过 300 公里或用户明确要求交通比较时，才调用 travel_fliggy_search 的 train 和 flight 查询；飞猪不可用时再调用 travel_transport，比较总耗时、价格、换乘和退改风险。短途跨城（例如上海到杭州）在首次方案中无需查询航班或逐班火车。缺少接口时明确标注，禁止猜测余票和票价。
				6. FlyAI 只负责发现候选，不返回可用于下单的精确 SKU、可信实时价格或库存。根据景点、日期、人数和偏好选择最匹配的商品方向，解释选择理由，并明确排除日期不符、受众不符或包含无关演出、餐饮、接送等内容的套票。不能把商品方向称为已经锁定的票种。
				7. 用户询问任何景点门票、购票入口、H5 链接或跳转链接时，不要调用 search_web、travel_purchase_links 或 travel_purchase_entry，也不要检索或生成精准景点页。只调用一次 travel_ticket_platform_entries，并逐字返回工具给出的美团、携程、飞猪三个固定网页入口。禁止在 URL 中添加景点名、日期、keyword、search 或其它查询参数。三个链接后必须原样提醒：“暂时不能给到您具体的购票页面，麻烦您进入平台自主购票。”若用户的问题只有购票链接，最终回答只保留三个平台名称、三个 URL 和这句提醒，不输出搜索过程、搜索结果或额外景点信息。
				8. 用户发来购票页面截图时，结合最近选择的方案核对画面中可见的商品名、日期、票种、数量、价格、套票内容和退改规则，明确告诉用户应点击哪个可见选项以及哪些选项应排除；不得声称已代为点击、锁票或付款。用户付款后发回订单号、订单截图或电子票时，可调用 travel_checkout record_purchase 保存用户确认的外部结果。外部渠道退款只能引导用户到原渠道操作，成功后再记录结果。不得把截图或用户口述称为平台回调验证。
				9. 实名信息和支付凭据只能填写在供应商托管的 HTTPS 结算页，Agent 和聊天窗口不得收集、转述或保存身份证号、银行卡号、支付密码、短信验证码。
				10. 网络瞬时错误由框架自动重试。工具返回 degraded=true 时继续使用降级产物，但要在最终汇报里说明限制。
				11. 任务完成后用简洁中文汇报：推荐方案、每日安排、预算、天气/票务信息时效、推荐商品方向、应选/应排除的票种或套票和尚待用户在外部页面确认的事项。只有用户明确要求购票入口时，才汇报购票入口类型（官方小程序直达/官方小程序路径/第三方商品页）。首次方案结尾必须附“依据与可信度”：景点/路线、天气、预算、交通、票务分别标为高/中/低/待核验，并写明来源和查询时间。没有真实地图 API 时，路线只能标“中：站点顺序建议，距离和耗时待地图核验”；没有实时票务时，票务只能标“待核验”。
				12. 用户明确要求查票或购票入口时，只查询其已选方案中最需要购票的 1 至 2 个景点；不要为整份行程逐个查询景点。已有信息足以回答时立即总结，避免重复搜索。
				13. 最终回答中的每个 URL 必须逐字来自工具返回，禁止输出 xxxxxx、example.com 等占位/示例链接。景点购票场景只能输出 travel_ticket_platform_entries 返回的三个固定入口，不得称为精准页、商品页、实时票价或已有库存。
				""".formatted(today);
	}
}
