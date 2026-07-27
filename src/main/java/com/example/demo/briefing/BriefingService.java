package com.example.demo.briefing;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.demo.alert.AlertPushService;
import com.example.demo.alert.StockAlertService;
import com.example.demo.search.WebSearchService;
import com.example.demo.watchlist.WatchlistService;
import com.example.demo.weather.WeatherResponse;
import com.example.demo.weather.WeatherService;

/**
 * Composes the subscription briefing at delivery time — weather, watchlist
 * quotes and trending topics are all fetched fresh when the message is about
 * to be sent, never at subscription time. Every section fails independently:
 * a broken upstream degrades one line instead of killing the whole briefing.
 */
@Service
public class BriefingService {
	private static final Logger log = LoggerFactory.getLogger(BriefingService.class);
	private static final ZoneId BEIJING_ZONE = ZoneId.of("Asia/Shanghai");
	private static final int MAX_QUOTE_SYMBOLS = 8;
	private static final int MAX_TRENDING_CHARS = 500;

	private final WeatherService weatherService;
	private final WatchlistService watchlistService;
	private final StockAlertService alertService;
	private final WebSearchService webSearchService;
	private final ExecutorService sectionExecutor = Executors.newVirtualThreadPerTaskExecutor();

	public BriefingService(WeatherService weatherService, WatchlistService watchlistService,
			StockAlertService alertService, WebSearchService webSearchService) {
		this.weatherService = weatherService;
		this.watchlistService = watchlistService;
		this.alertService = alertService;
		this.webSearchService = webSearchService;
	}

	/** sessionId 决定读取谁的自选股；city 为订阅时保存的天气城市。 */
	public String compose(String sessionId, String city) {
		String target = city == null || city.isBlank() ? "北京" : city.trim();
		LocalDate today = LocalDate.now(BEIJING_ZONE);
		// Sections run concurrently so a slow upstream delays, not serializes.
		CompletableFuture<String> weather = CompletableFuture.supplyAsync(() -> weatherSection(target),
				sectionExecutor);
		CompletableFuture<String> stocks = CompletableFuture.supplyAsync(() -> watchlistSection(sessionId),
				sectionExecutor);
		CompletableFuture<String> trending = CompletableFuture.supplyAsync(this::trendingSection,
				sectionExecutor);
		StringBuilder text = new StringBuilder("📮 每日简报 · ")
				.append(today.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))).append(' ')
				.append(today.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINA));
		append(text, "☁️ 天气", weather.join());
		append(text, "📈 自选股", stocks.join());
		append(text, "🔥 热搜", trending.join());
		return text.toString();
	}

	private static void append(StringBuilder text, String title, String section) {
		if (section == null || section.isBlank()) return;
		text.append("\n\n").append(title).append('\n').append(section.trim());
	}

	private String weatherSection(String city) {
		try {
			WeatherResponse weather = weatherService.getCurrentWeather(city);
			return weather.city() + "：" + weather.weather() + " " + weather.temperature() + "℃"
					+ "，体感 " + weather.feelsLike() + "℃，" + weather.windDirection() + weather.windScale() + "级"
					+ "，湿度 " + weather.humidity() + "%（数据来源：和风天气）";
		} catch (RuntimeException error) {
			log.warn("简报天气获取失败：城市={}，原因={}", city, error.getMessage());
			return city + "：天气获取失败，请稍后单独查询。";
		}
	}

	private String watchlistSection(String sessionId) {
		List<String> symbols;
		try {
			symbols = watchlistService.list(sessionId);
		} catch (RuntimeException error) {
			return "";
		}
		if (symbols.isEmpty()) return "还没有自选股。把 6 位股票代码发给我即可加入自选。";
		if (!alertService.isConfigured()) return "已关注 " + String.join("、", symbols) + "（行情服务未配置）";
		List<String> target = symbols.subList(0, Math.min(symbols.size(), MAX_QUOTE_SYMBOLS));
		List<CompletableFuture<String>> futures = target.stream()
				.map(symbol -> CompletableFuture.supplyAsync(() -> quoteLine(symbol), sectionExecutor))
				.toList();
		StringBuilder lines = new StringBuilder();
		futures.forEach(future -> lines.append(future.join()).append('\n'));
		if (symbols.size() > target.size()) {
			lines.append("（其余 ").append(symbols.size() - target.size()).append(" 只未展示）\n");
		}
		lines.append("仅供研究参考，不构成投资建议。");
		return lines.toString();
	}

	private String quoteLine(String symbol) {
		try {
			StockAlertService.StockQuote quote = alertService.quote(symbol);
			String price = AlertPushService.trimNumber(quote.latestPrice());
			String percent = AlertPushService.trimNumber(quote.changePercent());
			if (price.isBlank()) return quote.stockCode() + "：暂无行情";
			return quote.stockCode() + "：" + price
					+ (percent.isBlank() ? "" : "（" + (percent.startsWith("-") ? "" : "+") + percent + "%）");
		} catch (RuntimeException error) {
			return symbol + "：行情获取失败";
		}
	}

	private String trendingSection() {
		try {
			String result = webSearchService.search(LocalDate.now(BEIJING_ZONE)
					+ " 中国全网热搜榜。列出 5 条今天最热的话题，每条一行并注明平台，不要旧新闻。");
			if (result == null || result.isBlank() || result.startsWith("联网搜索没有返回有效结果")) {
				return "今日热搜获取失败，请稍后单独查询。";
			}
			return result.length() > MAX_TRENDING_CHARS ? result.substring(0, MAX_TRENDING_CHARS) + "…" : result;
		} catch (RuntimeException error) {
			log.warn("简报热搜获取失败：{}", error.getMessage());
			return "今日热搜获取失败，请稍后单独查询。";
		}
	}
}
