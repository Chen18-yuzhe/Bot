package com.example.bot.tools;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.fasterxml.jackson.databind.JsonNode;

/** Implement and register this interface to add a new model-callable capability. */
public interface AssistantTool {
	FunctionDefinition definition();
	ToolResult execute(JsonNode arguments, ToolContext context);
}
