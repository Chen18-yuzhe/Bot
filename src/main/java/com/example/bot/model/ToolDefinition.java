package com.example.bot.model;

/**
 * 暴露给模型的工具定义，与具体供应商 SDK 无关。
 * parametersJson 为 JSON Schema 文本（例如 {"type":"object",...}），
 * 由各供应商实现翻译成自身的工具描述格式。
 */
public record ToolDefinition(String name, String description, String parametersJson) {
}
