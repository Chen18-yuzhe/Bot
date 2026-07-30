package com.example.bot.chat;

/** A media attachment downloaded from a WeChat message. */
public record MediaContent(MediaType type, byte[] data, String mimeType, String fileName) {
	public MediaContent(MediaType type, byte[] data, String mimeType) {
		this(type, data, mimeType, null);
	}

	public enum MediaType {
		IMAGE, VIDEO, AUDIO, DOCUMENT
	}
}
