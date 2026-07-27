package com.example.demo.tools;

import org.springframework.stereotype.Component;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.example.demo.document.DocumentSummaryService;
import com.example.demo.knowledge.KnowledgeBaseService;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.gson.JsonParser;

@Component
public class KnowledgeBaseIngestTool implements AssistantTool {
	private static final FunctionDefinition DEFINITION = FunctionDefinition.builder()
			.name("add_to_knowledge_base")
			.description("把本轮或近期上传的文档解析后永久加入个人知识库。必须有文档附件，并且只有用户明确要求保存、导入或加入知识库时调用；仅总结或问答不要调用。")
			.parameters(JsonParser.parseString("""
					{"type":"object","properties":{"name":{"type":"string","description":"知识文件名称，可不含扩展名"}},"additionalProperties":false}
					""").getAsJsonObject()).build();
	private final KnowledgeBaseService knowledgeBase;
	private final DocumentSummaryService documents;

	public KnowledgeBaseIngestTool(KnowledgeBaseService knowledgeBase, DocumentSummaryService documents) {
		this.knowledgeBase = knowledgeBase;
		this.documents = documents;
	}
	@Override public FunctionDefinition definition() { return DEFINITION; }
	@Override public ToolResult execute(JsonNode arguments, ToolContext context) {
		if (context.documents().isEmpty()) throw new IllegalArgumentException("请先上传需要加入知识库的文档");
		var media = context.documents().get(0);
		String extracted = documents.extractText(media);
		String requested = arguments.path("name").asText("").trim();
		String name = requested.isBlank() ? media.fileName() : requested;
		var path = knowledgeBase.addForSession(context.sessionId(), name, extracted);
		return ToolResult.text("文档已加入个人知识库：" + path.getFileName());
	}
}
