package com.example.demo.tools;

import org.springframework.stereotype.Component;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.example.demo.stock.StockTraceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonParser;

@Component
public class StockDataTool implements AssistantTool {

	private static final FunctionDefinition DEFINITION = FunctionDefinition.builder()
			.name("query_stock_data")
			.description("查询 A 股单只股票的每日行情、技术指标、估值、财务、风险和已核验研究摘要。仅在用户给出 6 位 A 股代码时调用，例如 600519、000001 或 600519.SH。回答必须以工具结果的数据截至时间和事实为依据，用通俗中文说明偏强/中性/偏弱、主要依据、风险和数据缺口；不得编造价格、新闻或财务数据，不得给出确定性的买入/卖出指令、目标价、仓位或收益承诺。若用户只提供公司名称而没有代码，应先索要股票代码。")
			.parameters(JsonParser.parseString("""
					{"type":"object","properties":{"symbol":{"type":"string","pattern":"^[0-9]{6}([.](SH|SZ|BJ))?$","description":"6 位 A 股代码，例如 600519 或 000001.SZ"}},"required":["symbol"],"additionalProperties":false}
					""").getAsJsonObject())
			.build();

	private final StockTraceService stockTraceService;
	private final ObjectMapper objectMapper;

	public StockDataTool(StockTraceService stockTraceService, ObjectMapper objectMapper) {
		this.stockTraceService = stockTraceService;
		this.objectMapper = objectMapper;
	}

	@Override public FunctionDefinition definition() { return DEFINITION; }

	@Override
	public ToolResult execute(JsonNode arguments, ToolContext context) {
		String symbol = arguments.path("symbol").asText("").trim();
		if (symbol.isBlank()) throw new IllegalArgumentException("缺少必填参数：symbol");
		StockTraceService.StockSummary summary = stockTraceService.summary(symbol);
		var result = objectMapper.createObjectNode();
		result.put("symbol", summary.symbol());
		result.put("summary", summary.reply());
		result.set("meta", summary.meta());
		result.put("notice", "仅基于股票数据服务返回的研究快照，不构成投资建议；市场有风险，历史数据不代表未来表现。");
		return ToolResult.text(result.toString());
	}
}
