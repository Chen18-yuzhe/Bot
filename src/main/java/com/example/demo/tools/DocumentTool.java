package com.example.demo.tools;

import org.springframework.stereotype.Component;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.example.demo.document.DocumentReplyPolicy;
import com.example.demo.document.DocumentSummaryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.gson.JsonParser;

@Component
public class DocumentTool implements AssistantTool {
	private static final FunctionDefinition DEFINITION = FunctionDefinition.builder()
			.name("process_document")
			.description("处理用户上传的文档，可总结、问答、提取、改写、翻译或导出 DOCX。必须有文档附件。")
			.parameters(JsonParser.parseString("""
					{"type":"object","properties":{"instruction":{"type":"string","description":"对文档执行的完整要求"},"output":{"type":"string","enum":["auto","text","file"],"description":"text 返回文字，file 返回 DOCX，auto 按内容长度和用户要求决定"}},"required":["instruction"],"additionalProperties":false}
					""").getAsJsonObject()).build();
	private final DocumentSummaryService service;

	public DocumentTool(DocumentSummaryService service) { this.service = service; }
	@Override public FunctionDefinition definition() { return DEFINITION; }
	@Override public ToolResult execute(JsonNode arguments, ToolContext context) {
		if (context.documents().isEmpty()) throw new IllegalArgumentException("请先上传需要处理的文档");
		String instruction = arguments.path("instruction").asText(context.userQuestion()).trim();
		if (instruction.isBlank()) instruction = "请总结这份文件";
		String output = arguments.path("output").asText("auto");
		if (!java.util.Set.of("auto", "text", "file").contains(output)) {
			throw new IllegalArgumentException("output 只能是 auto、text 或 file");
		}
		var result = service.summarize(context.documents().get(0), instruction);
		boolean returnFile = "file".equals(output) || ("auto".equals(output)
				&& DocumentReplyPolicy.shouldReturnFile(instruction, result.summary(), 1800));
		return returnFile ? ToolResult.document("文档已经处理完成，结果文件已生成。", result)
				: ToolResult.text(result.summary());
	}
}
