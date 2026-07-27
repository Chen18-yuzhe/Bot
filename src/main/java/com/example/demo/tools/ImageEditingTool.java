package com.example.demo.tools;

import org.springframework.stereotype.Component;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.example.demo.chat.ImageEditingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.gson.JsonParser;

@Component
public class ImageEditingTool implements AssistantTool {
	private static final FunctionDefinition DEFINITION = FunctionDefinition.builder()
			.name("edit_image")
			.description("编辑用户本轮或近期上传的已有图片，可换背景、增删内容、改变风格、修复、扩图或增强。必须有图片附件；从零创作新图片应调用图片生成工具。")
			.parameters(JsonParser.parseString("""
					{"type":"object","properties":{"instruction":{"type":"string","description":"明确、完整的图片编辑要求，说明保留内容和需要修改的内容"}},"required":["instruction"],"additionalProperties":false}
					""").getAsJsonObject()).build();
	private final ImageEditingService service;

	public ImageEditingTool(ImageEditingService service) { this.service = service; }
	@Override public FunctionDefinition definition() { return DEFINITION; }
	@Override public ToolResult execute(JsonNode arguments, ToolContext context) {
		String instruction = arguments.path("instruction").asText("").trim();
		if (instruction.isBlank()) throw new IllegalArgumentException("缺少必填参数：instruction");
		return ToolResult.image("图片已按要求编辑完成。", service.edit(context.images(), instruction));
	}
}
