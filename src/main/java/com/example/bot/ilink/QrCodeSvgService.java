package com.example.bot.ilink;

import java.util.Map;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import javax.imageio.ImageIO;

import org.springframework.stereotype.Component;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

@Component
public class QrCodeSvgService {

	private static final int SIZE = 360;

	public String render(String content) {
		if (content == null || content.isBlank()) {
			throw new IllegalArgumentException("二维码内容为空");
		}
		try {
			BitMatrix matrix = new QRCodeWriter().encode(
					content,
					BarcodeFormat.QR_CODE,
					SIZE,
					SIZE,
					Map.of(
							EncodeHintType.CHARACTER_SET, "UTF-8",
							EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
							EncodeHintType.MARGIN, 2));
			return toSvg(matrix);
		} catch (WriterException exception) {
			throw new IllegalStateException("无法生成登录二维码", exception);
		}
	}

	/** Renders a chat-safe PNG for channels such as WeChat that cannot send SVG. */
	public byte[] renderPng(String content) {
		if (content == null || content.isBlank()) {
			throw new IllegalArgumentException("二维码内容为空");
		}
		try {
			BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, SIZE, SIZE,
					Map.of(EncodeHintType.CHARACTER_SET, "UTF-8",
							EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
							EncodeHintType.MARGIN, 2));
			BufferedImage image = new BufferedImage(matrix.getWidth(), matrix.getHeight(), BufferedImage.TYPE_INT_RGB);
			for (int y = 0; y < matrix.getHeight(); y++) {
				for (int x = 0; x < matrix.getWidth(); x++) {
					image.setRGB(x, y, matrix.get(x, y) ? 0x000000 : 0xFFFFFF);
				}
			}
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			if (!ImageIO.write(image, "png", output)) throw new IllegalStateException("无法编码二维码 PNG");
			return output.toByteArray();
		} catch (WriterException | java.io.IOException exception) {
			throw new IllegalStateException("无法生成登录二维码 PNG", exception);
		}
	}

	private static String toSvg(BitMatrix matrix) {
		StringBuilder path = new StringBuilder(matrix.getWidth() * matrix.getHeight() / 3);
		for (int y = 0; y < matrix.getHeight(); y++) {
			int x = 0;
			while (x < matrix.getWidth()) {
				while (x < matrix.getWidth() && !matrix.get(x, y)) x++;
				int start = x;
				while (x < matrix.getWidth() && matrix.get(x, y)) x++;
				if (start < x) {
					path.append('M').append(start).append(' ').append(y)
							.append('h').append(x - start).append("v1H")
							.append(start).append('z');
				}
			}
		}
		return """
				<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %d %d"
				     width="%d" height="%d" shape-rendering="crispEdges"
				     role="img" aria-label="登录二维码">
				  <rect width="100%%" height="100%%" fill="#fff"/>
				  <path d="%s" fill="#000"/>
				</svg>
				""".formatted(matrix.getWidth(), matrix.getHeight(),
				matrix.getWidth(), matrix.getHeight(), path);
	}
}
