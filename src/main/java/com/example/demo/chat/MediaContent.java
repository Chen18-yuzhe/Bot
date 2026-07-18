package com.example.demo.chat;

/** A media attachment downloaded from a WeChat message. */
public record MediaContent(MediaType type, byte[] data, String mimeType) {

	public enum MediaType {
		IMAGE, VIDEO
	}
}
