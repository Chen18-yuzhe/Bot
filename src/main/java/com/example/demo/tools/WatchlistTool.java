package com.example.demo.tools;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.stereotype.Component;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.example.demo.alert.StockAlertService;
import com.example.demo.stock.StockTraceService;
import com.example.demo.watchlist.WatchlistService;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.gson.JsonParser;

@Component
public class WatchlistTool implements AssistantTool {
	/** Research summaries are slow LLM calls; keep the fallback fan-out small. */
	private static final int MAX_SUMMARY_SYMBOLS = 3;
	private static final int MAX_QUOTE_SYMBOLS = 10;

	private static final FunctionDefinition DEFINITION = FunctionDefinition.builder()
			.name("manage_watchlist")
			.description("管理当前用户的自选股清单，并支持一键查看自选股行情。用户说“加自选/关注某股票”用 add，"
					+ "“移除/取关”用 remove，“我的自选/自选列表”用 list，“自选股行情/今天自选表现”用 quotes。"
					+ "add 和 remove 需要 6 位股票代码；用户只说公司名时先询问代码。自选股也是早报和预警的默认标的。")
			.parameters(JsonParser.parseString("""
					{"type":"object","properties":{"action":{"type":"string","enum":["add","remove","list","quotes"]},"symbol":{"type":"string","pattern":"^[0-9]{6}([.](SH|SZ|BJ))?$","description":"add/remove 时的 6 位 A 股代码"}},"required":["action"],"additionalProperties":false}
					""").getAsJsonObject()).build();

	private final WatchlistService watchlistService;
	private final StockAlertService alertService;
	private final StockTraceService stockTraceService;
	private final ExecutorService quoteExecutor = Executors.newVirtualThreadPerTaskExecutor();

	public WatchlistTool(WatchlistService watchlistService, StockAlertService alertService,
			StockTraceService stockTraceService) {
		this.watchlistService = watchlistService;
		this.alertService = alertService;
		this.stockTraceService = stockTraceService;
	}

	@Override public FunctionDefinition definition() { return DEFINITION; }

	@Override
	public ToolResult execute(JsonNode arguments, ToolContext context) {
		String action = arguments.path("action").asText("").trim();
		return switch (action) {
			case "add" -> add(arguments, context);
			case "remove" -> remove(arguments, context);
			case "list" -> list(context);
			case "quotes" -> quotes(context);
			default -> throw new IllegalArgumentException("action 只能是 add、remove、list 或 quotes");
		};
	}

	private ToolResult add(JsonNode arguments, ToolContext context) {
		String symbol = requiredSymbol(arguments);
		boolean added = watchlistService.add(context.sessionId(), symbol);
		List<String> symbols = watchlistService.list(context.sessionId());
		return ToolResult.text((added ? "已加入自选：" : "该股票已在自选中：") + symbol
				+ "。当前自选：" + String.join("、", symbols));
	}

	private ToolResult remove(JsonNode arguments, ToolContext context) {
		String symbol = requiredSymbol(arguments);
		boolean removed = watchlistService.remove(context.sessionId(), symbol);
		if (!removed) return ToolResult.noResult("自选中没有 " + symbol + "。");
		List<String> symbols = watchlistService.list(context.sessionId());
		return ToolResult.text("已移除自选：" + symbol
				+ (symbols.isEmpty() ? "。自选清单已清空。" : "。当前自选：" + String.join("、", symbols)));
	}

	private ToolResult list(ToolContext context) {
		List<String> symbols = watchlistService.list(context.sessionId());
		if (symbols.isEmpty()) return ToolResult.noResult("自选清单是空的。把 6 位股票代码发给我即可加入自选。");
		return ToolResult.text("当前自选（" + symbols.size() + " 只）：" + String.join("、", symbols)
				+ "。可以说“自选股行情”查看当日表现。");
	}

	private ToolResult quotes(ToolContext context) {
		List<String> symbols = watchlistService.list(context.sessionId());
		if (symbols.isEmpty()) return ToolResult.noResult("自选清单是空的，先添加股票代码再查看行情。");
		List<String> sections = alertService.isConfigured()
				? quoteSections(symbols) : summarySections(symbols);
		return ToolResult.text(String.join("\n", sections)
				+ "\n仅供研究参考，不构成投资建议。");
	}

	private List<String> quoteSections(List<String> symbols) {
		List<String> target = symbols.subList(0, Math.min(symbols.size(), MAX_QUOTE_SYMBOLS));
		List<CompletableFuture<String>> futures = target.stream()
				.map(symbol -> CompletableFuture.supplyAsync(() -> quoteLine(symbol), quoteExecutor))
				.toList();
		List<String> sections = new java.util.ArrayList<>(futures.stream().map(CompletableFuture::join).toList());
		if (symbols.size() > target.size()) {
			sections.add("（其余 " + (symbols.size() - target.size()) + " 只未展示）");
		}
		return sections;
	}

	private String quoteLine(String symbol) {
		try {
			StockAlertService.StockQuote quote = alertService.quote(symbol);
			String percent = com.example.demo.alert.AlertPushService.trimNumber(quote.changePercent());
			String price = com.example.demo.alert.AlertPushService.trimNumber(quote.latestPrice());
			return quote.stockCode() + "：" + (price.isBlank() ? "暂无行情" : price
					+ (percent.isBlank() ? "" : "（" + (percent.startsWith("-") ? "" : "+") + percent + "%）"));
		} catch (RuntimeException error) {
			return symbol + "：行情获取失败";
		}
	}

	/** Without the internal quote API, fall back to the slower research summary path. */
	private List<String> summarySections(List<String> symbols) {
		List<String> target = symbols.subList(0, Math.min(symbols.size(), MAX_SUMMARY_SYMBOLS));
		List<CompletableFuture<String>> futures = target.stream()
				.map(symbol -> CompletableFuture.supplyAsync(() -> summarySection(symbol), quoteExecutor))
				.toList();
		List<String> sections = new java.util.ArrayList<>(futures.stream().map(CompletableFuture::join).toList());
		if (symbols.size() > target.size()) {
			sections.add("（自选较多，本次只展示前 " + target.size() + " 只，可指定代码单独查询）");
		}
		return sections;
	}

	private String summarySection(String symbol) {
		try {
			return stockTraceService.summary(symbol).reply();
		} catch (RuntimeException error) {
			return symbol + "：数据获取失败";
		}
	}

	private static String requiredSymbol(JsonNode arguments) {
		String symbol = arguments.path("symbol").asText("").trim();
		if (symbol.isBlank()) throw new IllegalArgumentException("缺少必填参数：symbol");
		return symbol;
	}
}
