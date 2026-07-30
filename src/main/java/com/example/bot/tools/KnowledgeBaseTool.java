package com.example.bot.tools;

import org.springframework.stereotype.Component;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.example.bot.knowledge.KnowledgeBaseService;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.gson.JsonParser;

@Component
public class KnowledgeBaseTool implements AssistantTool {
	private static final FunctionDefinition DEFINITION = FunctionDefinition.builder()
			.name("search_knowledge_base")
			.description("只检索用户已经保存到本地个人知识库的项目资料、文档和笔记。不能搜索互联网；公共新闻、实时信息和知识库未收录的内容应使用联网搜索。")
			.parameters(JsonParser.parseString("""
					{"type":"object","properties":{"query":{"type":"string","description":"需要从个人知识库查找的问题"},"limit":{"type":"integer","minimum":1,"maximum":8,"description":"最多返回的相关片段数"}},"required":["query"],"additionalProperties":false}
					""").getAsJsonObject()).build();
	private final KnowledgeBaseService service;

	public KnowledgeBaseTool(KnowledgeBaseService service) { this.service = service; }
	@Override public FunctionDefinition definition() { return DEFINITION; }
	@Override public ToolResult execute(JsonNode arguments, ToolContext context) {
		String query = arguments.path("query").asText("").trim();
		if (query.isBlank()) throw new IllegalArgumentException("缺少必填参数：query");
		String result = service.searchForSession(context.sessionId(), query, arguments.path("limit").asInt(5));
		return result.startsWith("个人知识库中没有找到") || result.startsWith("个人知识库尚未建立")
				? ToolResult.noResult(result) : ToolResult.text(result);
	}
}
