package com.example.demo.tools;

import org.springframework.stereotype.Component;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.example.demo.chat.GeneratedImage;
import com.example.demo.chat.ImageGenerationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.gson.JsonParser;

@Component
public class ImageGenerationTool implements AssistantTool {
	private static final FunctionDefinition DEFINITION = FunctionDefinition.builder()
			.name("generate_image")
			.description("从零生成一张新图片，仅在用户明确要求生成、绘制或制作新图片时调用。修改用户已有图片应调用图片编辑工具；仅讨论图片知识时不要调用。")
			.parameters(JsonParser.parseString("""
					{"type":"object","properties":{"prompt":{"type":"string","description":"适合文生图模型的完整中文提示词，包含主体、构图、风格、色彩和光线"}},"required":["prompt"],"additionalProperties":false}
					""").getAsJsonObject())
			.build();

	private final ImageGenerationService imageGenerationService;

	public ImageGenerationTool(ImageGenerationService imageGenerationService) {
		this.imageGenerationService = imageGenerationService;
	}

	@Override public FunctionDefinition definition() { return DEFINITION; }

	@Override
	public ToolResult execute(JsonNode arguments, ToolContext context) {
		String prompt = arguments.path("prompt").asText("").trim();
		if (prompt.isEmpty()) throw new IllegalArgumentException("缺少必填参数：prompt");
		GeneratedImage image = imageGenerationService.generate(prompt);
		return ToolResult.image("图片已经成功生成，可以直接告诉用户生成完成。", image);
	}
}
