package com.example.demo.tools;

import com.example.demo.chat.GeneratedImage;

public record ToolResult(String content, GeneratedImage image) {
	public static ToolResult text(String content) {
		return new ToolResult(content, null);
	}

	public static ToolResult image(String content, GeneratedImage image) {
		return new ToolResult(content, image);
	}
}
