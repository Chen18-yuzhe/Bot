package com.example.demo.chat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.alibaba.dashscope.aigc.imagegeneration.ImageGeneration;
import com.alibaba.dashscope.aigc.imagegeneration.ImageGenerationMessage;
import com.alibaba.dashscope.aigc.imagegeneration.ImageGenerationParam;
import com.alibaba.dashscope.utils.Constants;
import com.example.demo.ApplicationException;

@Service
public class ImageEditingService {
	private static final Logger log = LoggerFactory.getLogger(ImageEditingService.class);
	private static final int MAX_INPUT_BYTES = 20 * 1024 * 1024;
	private static final int MAX_OUTPUT_BYTES = 25 * 1024 * 1024;
	private final String apiKey;
	private final String model;
	private final String size;
	private final String baseUrl;
	private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

	public ImageEditingService(@Value("${aliyun.dashscope.api-key:}") String apiKey,
			@Value("${aliyun.dashscope.image-edit-model:wan2.7-image-pro}") String model,
			@Value("${aliyun.dashscope.image-edit-size:2K}") String size,
			@Value("${aliyun.dashscope.base-url:}") String baseUrl) {
		this.apiKey = apiKey;
		this.model = model;
		this.size = size;
		this.baseUrl = baseUrl;
	}

	public GeneratedImage edit(List<MediaContent> images, String instruction) {
		if (apiKey == null || apiKey.isBlank()) {
			throw new ApplicationException(HttpStatus.SERVICE_UNAVAILABLE, "请先配置 DASHSCOPE_API_KEY");
		}
		if (images == null || images.isEmpty()) throw new IllegalArgumentException("图片编辑需要先上传图片");
		if (instruction == null || instruction.isBlank()) throw new IllegalArgumentException("图片编辑指令不能为空");
		List<MediaContent> selected = images.stream().limit(3).toList();
		long inputBytes = selected.stream().mapToLong(item -> item.data().length).sum();
		if (inputBytes > MAX_INPUT_BYTES) throw new IllegalArgumentException("待编辑图片总大小不能超过 20MB");
		configureEndpoint();
		List<Map<String, Object>> content = new ArrayList<>();
		content.add(Map.of("text", instruction.trim()));
		for (MediaContent image : selected) {
			String dataUrl = "data:" + image.mimeType() + ";base64," + Base64.getEncoder().encodeToString(image.data());
			content.add(Map.of("image", dataUrl));
		}
		ImageGenerationMessage message = ImageGenerationMessage.builder().role("user").content(content).build();
		ImageGenerationParam request = ImageGenerationParam.builder().apiKey(apiKey).model(model)
				.message(message).size(size).n(1).watermark(false).build();
		long startedAt = System.nanoTime();
		log.info("开始图片编辑：模型={}，图片数量={}，输入字节={}，指令字数={}",
				model, selected.size(), inputBytes, instruction.length());
		try {
			var response = new ImageGeneration().call(request);
			String imageUrl = response.getOutput().getChoices().stream()
					.flatMap(choice -> choice.getMessage().getContent().stream())
					.filter(part -> part.containsKey("image"))
					.map(part -> String.valueOf(part.get("image"))).findFirst()
					.orElseThrow(() -> new IllegalStateException("图片编辑模型没有返回图片地址"));
			byte[] bytes = download(imageUrl);
			log.info("图片编辑完成：输出字节={}，耗时={}毫秒", bytes.length,
					(System.nanoTime() - startedAt) / 1_000_000L);
			return new GeneratedImage(bytes, "image/png", "wan-image-edited.png");
		} catch (Exception error) {
			log.error("图片编辑失败：模型={}，原因={}", model, error.getMessage(), error);
			throw new ApplicationException(HttpStatus.BAD_GATEWAY, "图片编辑失败，请稍后再试", error);
		}
	}

	private byte[] download(String imageUrl) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(imageUrl)).timeout(Duration.ofSeconds(90)).GET().build();
		HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
		if (response.statusCode() != 200 || response.body() == null || response.body().length == 0) {
			throw new IOException("下载编辑图片失败，HTTP=" + response.statusCode());
		}
		if (response.body().length > MAX_OUTPUT_BYTES) throw new IOException("编辑后的图片超过发送大小限制");
		return response.body();
	}

	private void configureEndpoint() {
		if (baseUrl != null && !baseUrl.isBlank()) {
			String endpoint = baseUrl.trim().replace("/compatible-mode/v1", "/api/v1");
			Constants.baseHttpApiUrl = endpoint.endsWith("/") ? endpoint : endpoint + "/";
		}
	}
}
