package com.example.bot.tools;

import org.springframework.stereotype.Component;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.example.bot.ilink.UserNameService;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.gson.JsonParser;

/** Model-governed durable memory for explicit, stable user facts. */
@Component
public class UserProfileMemoryTool implements AssistantTool {
	private static final FunctionDefinition DEFINITION = FunctionDefinition.builder()
			.name("remember_user_profile")
			.description("""
					永久保存、更新或清除当前用户的长期资料，包括称呼、明确自述的健康状况与过敏、长期偏好和固定限制。
					用户可用任何自然语言表达，不要求固定句式。仅保存未来多次对话仍可能有用、且用户明确以第一人称陈述的稳定事实。
					不要保存临时任务/行程/情绪、第三人信息、引用内容、推测、诊断结论、账号秘密、密码、验证码、证件号、支付信息或完整联系方式。
					健康信息只能保存用户明确自述的状况，不得根据症状自行诊断。语意不确定时先追问，不调用本工具。
					""")
			.parameters(JsonParser.parseString("""
					{"type":"object","properties":{
					  "action":{"type":"string","enum":["set","clear"]},
					  "category":{"type":"string","enum":["name","health","allergy","preference","constraint","profile"]},
					  "key":{"type":"string","description":"稳定、简短的中文键，如高血压、食物过敏、饮食偏好；称呼类别可写称呼"},
					  "value":{"type":"string","description":"明确事实的简洁表述；action=set 时必填"}
					},"required":["action","category","key"],"additionalProperties":false}
					""").getAsJsonObject()).build();

	private final UserNameService profiles;

	public UserProfileMemoryTool(UserNameService profiles) { this.profiles = profiles; }
	@Override public FunctionDefinition definition() { return DEFINITION; }

	@Override
	public ToolResult execute(JsonNode arguments, ToolContext context) {
		String action = arguments.path("action").asText("").trim();
		String category = arguments.path("category").asText("").trim();
		String key = arguments.path("key").asText("").trim();
		String value = arguments.path("value").asText("").trim();
		if (!java.util.Set.of("name", "health", "allergy", "preference", "constraint", "profile").contains(category)) {
			throw new IllegalArgumentException("不支持的长期记忆类别");
		}
		if (!"set".equals(action) && !"clear".equals(action)) {
			throw new IllegalArgumentException("action 必须是 set 或 clear");
		}
		if ("name".equals(category)) {
			if ("set".equals(action) && value.isBlank()) throw new IllegalArgumentException("称呼不能为空");
			profiles.update(context.sessionId(), "clear".equals(action) ? "" : value);
			return ToolResult.text("clear".equals(action) ? "已清除永久称呼。" : "已永久保存用户称呼：“" + value + "”。");
		}
		String memoryKey = category + "." + key;
		if ("set".equals(action) && value.isBlank()) throw new IllegalArgumentException("长期记忆内容不能为空");
		profiles.updateMemory(context.sessionId(), memoryKey, "clear".equals(action) ? "" : value);
		return ToolResult.text("clear".equals(action) ? "已清除该条长期用户资料。" : "已永久保存该条长期用户资料。 ");
	}
}
