package com.example.bot.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.example.bot.model.ToolDefinition;

/**
 * 工具注册表。向模型推理层暴露供应商无关的 {@link ToolDefinition}，
 * 内部把工具实现声明的 FunctionDefinition 翻译为通用 JSON Schema 描述。
 */
@Component
public class AssistantToolRegistry {
	private final Map<String, AssistantTool> tools;

	public AssistantToolRegistry(List<AssistantTool> registeredTools) {
		Map<String, AssistantTool> indexed = new LinkedHashMap<>();
		for (AssistantTool tool : registeredTools) {
			String name = tool.definition().getName();
			if (indexed.putIfAbsent(name, tool) != null) {
				throw new IllegalStateException("重复的 Function Calling 工具名：" + name);
			}
		}
		this.tools = Map.copyOf(indexed);
	}

	public List<ToolDefinition> definitions() {
		return tools.values().stream()
				.map(tool -> toDefinition(tool.definition()))
				.toList();
	}

	public ToolDefinition definition(String name) {
		AssistantTool tool = require(name);
		return toDefinition(tool.definition());
	}

	public AssistantTool require(String name) {
		AssistantTool tool = tools.get(name);
		if (tool == null) throw new IllegalArgumentException("模型请求了未注册的工具：" + name);
		return tool;
	}

	public List<String> names() {
		return tools.keySet().stream().sorted().toList();
	}

	private static ToolDefinition toDefinition(FunctionDefinition definition) {
		return new ToolDefinition(definition.getName(), definition.getDescription(),
				definition.getParameters() == null ? "{}" : definition.getParameters().toString());
	}
}
