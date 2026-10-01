package com.example.bot.model;

/** 模型请求的一次工具调用，与具体供应商 SDK 无关。 */
public record ToolCall(String id, String name, String argumentsJson) {
}
