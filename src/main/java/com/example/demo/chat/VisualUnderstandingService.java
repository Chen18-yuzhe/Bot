package com.example.demo.chat;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.common.MultiModalMessage;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.utils.Constants;
import com.example.demo.ApplicationException;

@Service
public class VisualUnderstandingService {

	private static final Logger log = LoggerFactory.getLogger(VisualUnderstandingService.class);
	private static final String ANALYSIS_PROMPT = """
			请把本轮用户文字和本轮所有媒体作为一个整体理解，并先判断用户的真实任务。
			每个媒体已按发送顺序编号；多图比较、找差异、选择、排序等任务必须同时参考全部媒体，不要依赖旧对话猜测本轮附件。
			只输出客观、充分的媒体事实，不要输出工具调用标记，也不要直接替用户完成文生图提示词。
			如果用户要求编辑、替换、添加、删除图片元素，要详细提取参考图中相关的主体、构图、风格、色彩、光线和位置关系，供后续工具决策使用。
			图像要识别主体、动作、场景、文字和重要细节；
			视频要按时间顺序概括关键事件、人物动作、场景变化，并留意画面中的文字。
			不要寒暄；无法确定的内容要明确说明，不要猜测人物身份或敏感属性。
			用户本轮文字：%s
			""";

	private final String apiKey;
	private final String model;
	private final float videoFps;
	private final String baseUrl;

	public VisualUnderstandingService(@Value("${aliyun.dashscope.api-key:}") String apiKey,
			@Value("${aliyun.dashscope.model:qwen3.7-plus}") String model,
			@Value("${aliyun.dashscope.video-fps:2}") float videoFps,
			@Value("${aliyun.dashscope.base-url:}") String baseUrl) {
		this.apiKey = apiKey;
		this.model = model;
		this.videoFps = Math.max(0.1f, Math.min(10f, videoFps));
		this.baseUrl = baseUrl;
	}

	public String analyze(List<MediaContent> media) {
		return analyze(media, "");
	}

	public String analyze(List<MediaContent> media, String userInstruction) {
		if (apiKey == null || apiKey.isBlank()) {
			throw new ApplicationException(HttpStatus.SERVICE_UNAVAILABLE, "请先配置环境变量 DASHSCOPE_API_KEY");
		}
		if (media == null || media.isEmpty()) {
			throw new ApplicationException(HttpStatus.BAD_REQUEST, "没有可识别的图片或视频");
		}

		List<Map<String, Object>> content = buildContent(media, videoFps, userInstruction);

		MultiModalMessage userMessage = MultiModalMessage.builder()
				.role(Role.USER.getValue())
				.content(content)
				.build();
		MultiModalConversationParam request = MultiModalConversationParam.builder()
				.apiKey(apiKey)
				.model(model)
				.message(userMessage)
				.maxTokens(1600)
				.build();

		configureEndpoint();
		log.info("正在调用千问视觉模型：{}，媒体数量={}", model, media.size());
		try {
			MultiModalConversationResult response = new MultiModalConversation().call(request);
			Object text = response.getOutput().getChoices().get(0).getMessage().getContent().stream()
					.filter(part -> part.containsKey("text"))
					.map(part -> part.get("text"))
					.findFirst()
					.orElse(null);
			if (text == null || text.toString().isBlank()) {
				throw new IllegalStateException("视觉模型没有返回文本内容");
			}
			return text.toString().trim();
		} catch (Exception exception) {
			log.error("千问视觉模型调用失败：{}", exception.getMessage(), exception);
			throw new ApplicationException(HttpStatus.BAD_GATEWAY, "图片或视频识别失败，请稍后再试", exception);
		}
	}

	static List<Map<String, Object>> buildContent(List<MediaContent> media, float videoFps) {
		return buildContent(media, videoFps, "");
	}

	static List<Map<String, Object>> buildContent(List<MediaContent> media, float videoFps, String userInstruction) {
		List<Map<String, Object>> content = new ArrayList<>();
		for (MediaContent item : media) {
			Map<String, Object> part = new HashMap<>();
			String dataUrl = "data:" + item.mimeType() + ";base64,"
					+ Base64.getEncoder().encodeToString(item.data());
			if (item.type() == MediaContent.MediaType.VIDEO) {
				part.put("video", dataUrl);
				part.put("fps", videoFps);
			} else {
				part.put("image", dataUrl);
			}
			content.add(part);
		}
		String instruction = userInstruction == null || userInstruction.isBlank()
				? "（没有文字指令，请自然描述媒体内容）"
				: userInstruction.trim();
		content.add(Map.of("text", ANALYSIS_PROMPT.formatted(instruction)));
		return content;
	}

	private void configureEndpoint() {
		if (baseUrl != null && !baseUrl.isBlank()) {
			String endpoint = baseUrl.trim().replace("/compatible-mode/v1", "/api/v1");
			Constants.baseHttpApiUrl = endpoint.endsWith("/") ? endpoint : endpoint + "/";
		}
	}
}
