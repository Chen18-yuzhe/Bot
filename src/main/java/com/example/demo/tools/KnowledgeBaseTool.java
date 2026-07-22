package com.example.demo.tools;

import org.springframework.stereotype.Component;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.example.demo.knowledge.KnowledgeBaseService;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.gson.JsonParser;

@Component
public class KnowledgeBaseTool implements AssistantTool {
	private static final FunctionDefinition DEFINITION = FunctionDefinition.builder()
			.name("search_knowledge_base")
			.description("检索用户的个人项目资料和本地知识文件。涉及项目实现、文档、笔记或私人资料时调用。")
			.parameters(JsonParser.parseString("""
					{"type":"object","properties":{"query":{"type":"string","description":"需要从个人知识库查找的问题"},"limit":{"type":"integer","minimum":1,"maximum":8,"description":"最多返回的相关片段数"}},"required":["query"],"additionalProperties":false}
					""").getAsJsonObject()).build();
	private final KnowledgeBaseService service;

	public KnowledgeBaseTool(KnowledgeBaseService service) { this.service = service; }
	@Override public FunctionDefinition definition() { return DEFINITION; }
	@Override public ToolResult execute(JsonNode arguments, ToolContext context) {
		String query = arguments.path("query").asText("").trim();
		if (query.isBlank()) throw new IllegalArgumentException("缺少必填参数：query");
		return ToolResult.text(service.search(query, arguments.path("limit").asInt(5)));
	}
}
