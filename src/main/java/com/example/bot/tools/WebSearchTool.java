package com.example.bot.tools;

import org.springframework.stereotype.Component;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.example.bot.search.WebSearchService;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.gson.JsonParser;

@Component
public class WebSearchTool implements AssistantTool {
	private static final FunctionDefinition DEFINITION = FunctionDefinition.builder()
			.name("search_web")
			.description("搜索公开互联网，适用于新闻、政策、价格、赛事、软件版本等可能变化的信息。不能检索用户私人知识库或本轮附件；稳定常识无需调用。")
			.parameters(JsonParser.parseString("""
					{"type":"object","properties":{"query":{"type":"string","description":"完整、具体的搜索问题"}},"required":["query"],"additionalProperties":false}
					""").getAsJsonObject()).build();
	private final WebSearchService service;

	public WebSearchTool(WebSearchService service) { this.service = service; }
	@Override public FunctionDefinition definition() { return DEFINITION; }
	@Override public ToolResult execute(JsonNode arguments, ToolContext context) {
		String query = arguments.path("query").asText("").trim();
		if (query.isBlank()) throw new IllegalArgumentException("缺少必填参数：query");
		String result = service.search(query);
		return result.startsWith(WebSearchService.NO_VERIFIED_SOURCES)
				|| result.startsWith("联网搜索没有返回有效结果")
				? ToolResult.noResult(result) : ToolResult.text(result);
	}
}
