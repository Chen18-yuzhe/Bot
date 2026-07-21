package com.example.demo.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.alibaba.dashscope.tools.ToolBase;
import com.alibaba.dashscope.tools.ToolFunction;

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

	public List<ToolBase> definitions() {
		return tools.values().stream()
				.map(tool -> (ToolBase) ToolFunction.builder().function(tool.definition()).build())
				.toList();
	}

	public AssistantTool require(String name) {
		AssistantTool tool = tools.get(name);
		if (tool == null) throw new IllegalArgumentException("模型请求了未注册的工具：" + name);
		return tool;
	}
}
