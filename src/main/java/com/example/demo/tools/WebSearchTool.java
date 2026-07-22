package com.example.demo.tools;

import org.springframework.stereotype.Component;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.example.demo.search.WebSearchService;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.gson.JsonParser;

@Component
public class WebSearchTool implements AssistantTool {
	private static final FunctionDefinition DEFINITION = FunctionDefinition.builder()
			.name("search_web")
			.description("搜索互联网以回答新闻、政策、价格、赛事、软件版本等可能变化的问题。稳定常识无需调用。")
			.parameters(JsonParser.parseString("""
					{"type":"object","properties":{"query":{"type":"string","description":"完整、具体的搜索问题"}},"required":["query"],"additionalProperties":false}
					""").getAsJsonObject()).build();
	private final WebSearchService service;

	public WebSearchTool(WebSearchService service) { this.service = service; }
	@Override public FunctionDefinition definition() { return DEFINITION; }
	@Override public ToolResult execute(JsonNode arguments, ToolContext context) {
		String query = arguments.path("query").asText("").trim();
		if (query.isBlank()) throw new IllegalArgumentException("缺少必填参数：query");
		return ToolResult.text(service.search(query));
	}
}
