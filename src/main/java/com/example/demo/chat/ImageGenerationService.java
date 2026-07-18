package com.example.demo.chat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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
import com.alibaba.dashscope.aigc.imagegeneration.ImageGenerationResult;
import com.alibaba.dashscope.utils.Constants;
import com.example.demo.ApplicationException;

@Service
public class ImageGenerationService {

	private static final Logger log = LoggerFactory.getLogger(ImageGenerationService.class);
	private static final int MAX_IMAGE_BYTES = 25 * 1024 * 1024;

	private final String apiKey;
	private final String model;
	private final String size;
	private final String baseUrl;
	private final HttpClient httpClient;

	public ImageGenerationService(@Value("${aliyun.dashscope.api-key:}") String apiKey,
			@Value("${aliyun.dashscope.image-model:wan2.7-image-pro}") String model,
			@Value("${aliyun.dashscope.image-size:2K}") String size,
			@Value("${aliyun.dashscope.base-url:}") String baseUrl) {
		this.apiKey = apiKey;
		this.model = model;
		this.size = size;
		this.baseUrl = baseUrl;
		this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
	}

	public GeneratedImage generate(String prompt) {
		if (apiKey == null || apiKey.isBlank()) {
			throw new ApplicationException(HttpStatus.SERVICE_UNAVAILABLE, "请先配置环境变量 DASHSCOPE_API_KEY");
		}
		if (prompt == null || prompt.isBlank()) {
			throw new ApplicationException(HttpStatus.BAD_REQUEST, "生图提示词不能为空");
		}
		configureEndpoint();
		ImageGenerationMessage message = ImageGenerationMessage.builder()
				.role("user")
				.content(List.of(Map.of("text", prompt.trim())))
				.build();
		ImageGenerationParam request = ImageGenerationParam.builder()
				.apiKey(apiKey)
				.model(model)
				.message(message)
				.size(size)
				.n(1)
				.watermark(false)
				.build();

		log.info("正在调用万相文生图模型：{}，尺寸={}", model, size);
		try {
			ImageGenerationResult response = new ImageGeneration().call(request);
			String imageUrl = response.getOutput().getChoices().stream()
					.flatMap(choice -> choice.getMessage().getContent().stream())
					.filter(part -> part.containsKey("image"))
					.map(part -> part.get("image").toString())
					.findFirst()
					.orElseThrow(() -> new IllegalStateException("万相没有返回图片地址"));
			byte[] bytes = download(imageUrl);
			log.info("万相图片生成成功，文件大小={}字节", bytes.length);
			return new GeneratedImage(bytes, "image/png", "wan2.7-image-pro.png");
		} catch (Exception exception) {
			log.error("万相文生图失败：{}", exception.getMessage(), exception);
			if (isDataInspectionFailure(exception)) {
				throw new ApplicationException(HttpStatus.BAD_REQUEST,
						"提示词触发了百炼内容审核，请去掉真实人物或创作者姓名，并明确人物为成年人后再试。", exception);
			}
			throw new ApplicationException(HttpStatus.BAD_GATEWAY, "图片生成失败，请稍后再试", exception);
		}
	}

	private static boolean isDataInspectionFailure(Exception exception) {
		Throwable current = exception;
		while (current != null) {
			String message = current.getMessage();
			if (message != null && (message.contains("DataInspectionFailed")
					|| message.contains("Green net check failed"))) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}

	private byte[] download(String imageUrl) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(imageUrl))
				.timeout(Duration.ofSeconds(60)).GET().build();
		HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
		if (response.statusCode() != 200 || response.body() == null || response.body().length == 0) {
			throw new IOException("下载生成图片失败，HTTP=" + response.statusCode());
		}
		if (response.body().length > MAX_IMAGE_BYTES) {
			throw new IOException("生成图片超过微信发送大小限制");
		}
		return response.body();
	}

	private void configureEndpoint() {
		if (baseUrl != null && !baseUrl.isBlank()) {
			String endpoint = baseUrl.trim().replace("/compatible-mode/v1", "/api/v1");
			Constants.baseHttpApiUrl = endpoint.endsWith("/") ? endpoint : endpoint + "/";
		}
	}
}
