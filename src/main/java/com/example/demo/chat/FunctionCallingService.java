package com.example.demo.chat;

import java.util.ArrayList;
import java.util.List;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.util.regex.Pattern;

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
import com.alibaba.dashscope.tools.ToolCallBase;
import com.alibaba.dashscope.tools.ToolCallFunction;
import com.alibaba.dashscope.utils.Constants;
import com.example.demo.ApplicationException;
import com.example.demo.tools.AssistantTool;
import com.example.demo.tools.AssistantToolRegistry;
import com.example.demo.tools.ToolContext;
import com.example.demo.tools.ToolResult;
import com.example.demo.audio.GeneratedAudio;
import com.example.demo.document.GeneratedDocument;
import com.example.demo.monitoring.ToolMetricsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class FunctionCallingService {
	private static final Logger log = LoggerFactory.getLogger(FunctionCallingService.class);
	private static final int MAX_TOOL_ROUNDS = 4;
	private static final Pattern EXPLICIT_IMAGE_REQUEST = Pattern.compile(
			"(?:生成|画|绘制|制作|创作|设计).{0,16}(?:图片|图像|照片|头像|图标|海报|插画|画)|"
			+ "(?:图片|图像|照片|头像|图标|海报|插画).{0,16}(?:生成|画|绘制|制作|创作|设计)");
	private static final Pattern IMAGE_EDIT_REQUEST = Pattern.compile(
			"(?:修改|编辑|替换|添加|删除|移除|换背景|换成|改成|放到|移到|扩图|修复|增强|去水印|变清晰|调色)");
	private static final Pattern REMINDER_REQUEST = Pattern.compile(
			"(?:提醒我|记得提醒|设置提醒|创建提醒|取消.{0,6}提醒|查看.{0,6}提醒|提醒列表|有哪些提醒|待办列表)");
	private static final Pattern SPEECH_REQUEST = Pattern.compile("^(?:语音合成|合成语音)\\s*[：:].+");
	private static final Pattern VOICE_REQUEST = Pattern.compile("^(?:音色列表|当前音色|切换音色|设置音色|/voices|/voice).*");
	private static final String SYSTEM_PROMPT = """
			你是运行在微信中的中文 AI 助手。回答要简洁、自然、友好。
			需要实时数据或执行外部能力时必须调用提供的工具，不得编造工具结果。
			工具返回天气数据时，回答末尾注明“数据来源：和风天气”。
			联网搜索工具返回来源链接时必须保留来源，不得删除或伪造链接。
			人物文生图必须明确为成年人；不要在提示词中保留真实艺术家、导演或摄影师姓名，改写为可观察的视觉特征。
			不要向用户展示工具名、JSON 参数或内部执行过程。
			""";

	private final String apiKey;
	private final String model;
	private final String baseUrl;
	private final AssistantToolRegistry registry;
	private final ObjectMapper objectMapper;
	private final ToolMetricsService metrics;

	public FunctionCallingService(@Value("${aliyun.dashscope.api-key:}") String apiKey,
			@Value("${aliyun.dashscope.model:qwen3.7-plus}") String model,
			@Value("${aliyun.dashscope.base-url:}") String baseUrl,
			AssistantToolRegistry registry, ObjectMapper objectMapper, ToolMetricsService metrics) {
		this.apiKey = apiKey;
		this.model = model;
		this.baseUrl = baseUrl;
		this.registry = registry;
		this.objectMapper = objectMapper;
		this.metrics = metrics;
	}

	public AssistantReply reply(String sessionId, String question, List<ConversationTurn> history) {
		return reply(sessionId, question, history, null, List.of());
	}

	public AssistantReply reply(String sessionId, String question, List<ConversationTurn> history, String context) {
		return reply(sessionId, question, history, context, List.of());
	}

	public AssistantReply reply(String sessionId, String question, List<ConversationTurn> history,
			String context, List<MediaContent> media) {
		if (apiKey == null || apiKey.isBlank()) {
			throw new ApplicationException(HttpStatus.SERVICE_UNAVAILABLE, "请先配置环境变量 DASHSCOPE_API_KEY");
		}
		List<Object> messages = new ArrayList<>();
		messages.add(message(Role.SYSTEM.getValue(), SYSTEM_PROMPT));
		messages.add(message(Role.SYSTEM.getValue(), "当前北京时间：" +
				ZonedDateTime.now(ZoneId.of("Asia/Shanghai")) + "。创建提醒时必须使用带时区的 ISO-8601 时间。"));
		if (context != null && !context.isBlank()) messages.add(message(Role.SYSTEM.getValue(), context));
		if (history != null) {
			for (ConversationTurn turn : history) {
				messages.add(message(Role.USER.getValue(), turn.userMessage()));
				messages.add(message(Role.ASSISTANT.getValue(), turn.assistantMessage()));
			}
		}
		messages.add(message(Role.USER.getValue(), question));
		GeneratedImage generatedImage = null;
		GeneratedDocument generatedDocument = null;
		GeneratedAudio generatedAudio = null;
		String forcedTool = forcedTool(question, context, media);
		configureEndpoint();
		try {
			for (int round = 0; round <= MAX_TOOL_ROUNDS; round++) {
				var requestBuilder = MultiModalConversationParam.builder()
						.apiKey(apiKey).model(model).messages(messages)
						.tools(registry.definitions()).parallelToolCalls(false)
						.enableThinking(false).maxTokens(1600);
				if (round == 0 && forcedTool != null) {
					requestBuilder.toolChoice(registry.definition(forcedTool));
					log.info("明确能力请求，强制选择 Function Calling 工具：{}", forcedTool);
				} else {
					requestBuilder.toolChoice("auto");
				}
				MultiModalConversationParam request = requestBuilder.build();
				MultiModalConversationResult response = new MultiModalConversation().call(request);
				MultiModalMessage assistant = response.getOutput().getChoices().get(0).getMessage();
				messages.add(assistant);
				List<ToolCallBase> calls = assistant.getToolCalls();
				if (calls == null || calls.isEmpty()) {
					String answer = assistant.getContent().stream().filter(part -> part.containsKey("text"))
							.map(part -> String.valueOf(part.get("text"))).findFirst().orElse("");
					if (answer == null || answer.isBlank()) {
						answer = generatedImage != null ? "图片处理好了 🎨"
								: generatedDocument != null ? "文件处理好了。"
								: generatedAudio != null ? "语音生成好了。" : "暂时无法生成回答。";
					}
					if (generatedImage != null) return AssistantReply.image(answer.trim(), generatedImage);
					if (generatedDocument != null) return AssistantReply.document(answer.trim(), generatedDocument);
					if (generatedAudio != null) return AssistantReply.audio(answer.trim(), generatedAudio);
					return AssistantReply.text(answer.trim());
				}
				if (round == MAX_TOOL_ROUNDS) throw new IllegalStateException("工具调用次数超过限制");
				for (ToolCallBase base : calls) {
					if (!(base instanceof ToolCallFunction call)) throw new IllegalArgumentException("不支持的工具调用类型");
					String name = call.getFunction().getName();
					JsonNode arguments = objectMapper.readTree(call.getFunction().getArguments());
					AssistantTool tool = registry.require(name);
					log.info("执行 Function Calling 工具：{}，轮次={}", name, round + 1);
					ToolMetricsService.Invocation invocation = metrics.start(name);
					ToolResult result;
					try {
						result = tool.execute(arguments, new ToolContext(sessionId, question, media));
						metrics.success(invocation);
						log.info("Function Calling 工具执行成功：{}", name);
					} catch (Exception toolError) {
						metrics.failure(invocation);
						log.warn("Function Calling 工具执行失败：{}，原因={}", name, toolError.getMessage());
						throw toolError;
					}
					if (result.image() != null) generatedImage = result.image();
					if (result.document() != null) generatedDocument = result.document();
					if (result.audio() != null) generatedAudio = result.audio();
					messages.add(MultiModalMessage.builder().role("tool").toolCallId(call.getId())
							.name(name).content(List.of(java.util.Map.of("text", result.content()))).build());
				}
			}
			throw new IllegalStateException("Function Calling 未产生最终回答");
		} catch (ApplicationException exception) {
			log.warn("Function Calling 上游能力失败：{}", exception.getMessage());
			throw exception;
		} catch (IllegalArgumentException exception) {
			log.warn("Function Calling 参数无效：{}", exception.getMessage());
			throw new ApplicationException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
		} catch (Exception exception) {
			log.error("Function Calling 执行失败：{}", exception.getMessage(), exception);
			throw new ApplicationException(HttpStatus.BAD_GATEWAY, "AI 工具调用失败，请稍后再试", exception);
		}
	}

	private static MultiModalMessage message(String role, String content) {
		return MultiModalMessage.builder().role(role)
				.content(List.of(java.util.Map.of("text", content))).build();
	}

	static boolean requiresImageGeneration(String question, String context) {
		String value = question == null ? "" : question.trim();
		return EXPLICIT_IMAGE_REQUEST.matcher(value).find() && (context == null || context.isBlank());
	}

	static boolean requiresImageEditing(String question, List<MediaContent> media) {
		if (media == null || media.stream().noneMatch(item -> item.type() == MediaContent.MediaType.IMAGE)) return false;
		String value = question == null ? "" : question.trim();
		return IMAGE_EDIT_REQUEST.matcher(value).find() || EXPLICIT_IMAGE_REQUEST.matcher(value).find();
	}

	static String forcedTool(String question, String context, List<MediaContent> media) {
		List<MediaContent> attachments = media == null ? List.of() : media;
		String value = question == null ? "" : question.trim();
		if (attachments.stream().anyMatch(item -> item.type() == MediaContent.MediaType.DOCUMENT)
				&& value.matches(".*(?:加入|添加|保存|导入).{0,8}(?:知识库|资料库).*") ) return "add_to_knowledge_base";
		if (attachments.stream().anyMatch(item -> item.type() == MediaContent.MediaType.DOCUMENT)) return "process_document";
		if (attachments.stream().anyMatch(item -> item.type() == MediaContent.MediaType.AUDIO)) return "transcribe_audio";
		if (requiresImageEditing(question, attachments)) return "edit_image";
		if (requiresImageGeneration(question, context)) return "generate_image";
		if (SPEECH_REQUEST.matcher(value).matches()) return "synthesize_speech";
		if (VOICE_REQUEST.matcher(value.toLowerCase(java.util.Locale.ROOT)).matches()) return "manage_voice";
		if (REMINDER_REQUEST.matcher(value).find()) return "manage_reminder";
		return null;
	}

	private void configureEndpoint() {
		if (baseUrl != null && !baseUrl.isBlank()) {
			String endpoint = baseUrl.trim().replace("/compatible-mode/v1", "/api/v1");
			Constants.baseHttpApiUrl = endpoint.endsWith("/") ? endpoint : endpoint + "/";
		}
	}
}
