package com.example.bot.chat;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import com.example.bot.ApplicationException;
import com.example.bot.tools.AssistantTool;
import com.example.bot.tools.AssistantToolRegistry;
import com.example.bot.tools.ToolContext;
import com.example.bot.tools.ToolResult;
import com.example.bot.audio.GeneratedAudio;
import com.example.bot.document.GeneratedDocument;
import com.example.bot.monitoring.ToolMetricsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class FunctionCallingService {
	private static final Logger log = LoggerFactory.getLogger(FunctionCallingService.class);
	private static final int MAX_TOOL_ROUNDS = 6;
	private static final Pattern EXPLICIT_IMAGE_REQUEST = Pattern.compile(
			"(?:生成|画|绘制|制作|创作|设计).{0,16}(?:图片|图像|照片|头像|图标|海报|插画|画)|"
			+ "(?:图片|图像|照片|头像|图标|海报|插画).{0,16}(?:生成|画|绘制|制作|创作|设计)");
	private static final Pattern IMAGE_EDIT_REQUEST = Pattern.compile(
			"(?:修改|编辑|替换|添加|删除|移除|换背景|换成|改成|放到|移到|扩图|修复|增强|去水印|变清晰|调色)");
	private static final Pattern REMINDER_REQUEST = Pattern.compile(
			"(?:提醒我|记得提醒|设置提醒|创建提醒|取消.{0,6}提醒|查看.{0,6}提醒|提醒列表|有哪些提醒|待办列表)");
	/** 价格条件+提醒是预警而不是定时提醒；必须先于 REMINDER_REQUEST 判断。 */
	private static final Pattern SPEECH_REQUEST = Pattern.compile("^(?:语音合成|合成语音)\\s*[：:].+");
	private static final Pattern VOICE_REQUEST = Pattern.compile("^(?:音色列表|当前音色|切换音色|设置音色|/voices|/voice).*");
	private static final Pattern TIME_REQUEST = Pattern.compile(
			"^(?:请问|帮我查|查询|告诉我)?\\s*.{0,30}(?:现在几点|几点了|当前时间|当地时间|是什么时间|时差).*$");
	private static final Pattern BODY_FAT_REQUEST = Pattern.compile(".*(?:体脂率|BFR|body\\s*fat).*", Pattern.CASE_INSENSITIVE);
	private static final Pattern CALCULATION_REQUEST = Pattern.compile(
			"^(?:计算|算一下|科学计算)\\s*[：:].+|.*(?:sqrt|sin|cos|tan|ln|log|\\^|×|÷|\\d\\s*[+*/%]\\s*\\d).*",
			Pattern.CASE_INSENSITIVE);
	private static final Pattern BILIBILI_REQUEST = Pattern.compile(
			".*(?:B站|bilibili).*(?:收藏夹|字幕|视频)|.*(?:收藏夹|字幕).*(?:B站|bilibili).*",
			Pattern.CASE_INSENSITIVE);
	private static final String SYSTEM_PROMPT = """
			你是运行在微信中的中文 AI 助手。回答要简洁、自然、友好。
			需要实时数据或执行外部能力时必须调用提供的工具，不得编造工具结果。
			一个问题需要多个事实或多个外部动作时，可以按顺序多次调用同一工具或不同工具；必须等所需步骤全部完成后再综合回答。
			比较多个对象时必须获取每个对象的数据。后续工具依赖前一步结果时，先读取工具结果再决定下一次调用。
			工具返回失败结果时，可以调整参数、改用其他可用工具或基于已成功的数据回答，并明确说明缺失信息；不要声称失败的动作已经完成。
			用户意图、必填参数或目标对象不明确时，先向用户提出一个简短的澄清问题，不要猜测参数后反复调用工具。
			工具失败或没有结果时最多修正参数重试一次；仍无结果就停止调用并询问用户。已经成功执行的外部动作不得重复执行。
			工具返回天气数据时，回答末尾注明“数据来源：和风天气”。
			用户未指定地区而询问当前时间时，默认查询北京时间；不同地区时间必须使用时间工具，不得自行推算夏令时。
			涉及数学表达式、三角函数、阶乘或精确数值计算时必须调用科学计算工具；用户询问体脂率/BFR时必须调用体脂估算工具。
			热搜、空气质量、营养成分、菜谱和节假日属于可能变化的信息，必须调用实时生活信息工具并保留其来源。
			健康小妙招可直接给出通用、低风险的生活建议，但不得诊断疾病、替代医生或承诺疗效；出现急性症状、孕期、未成年人、用药或慢性病管理时建议咨询专业人士。
			营养和体脂结果仅作一般参考，应说明食材、品牌、烹饪方式和围度测量误差会影响结果。
			联网搜索工具返回来源链接时必须保留来源，不得删除或伪造链接。
			Bilibili 收藏夹属于私人数据，只能通过收藏夹工具读取；会话未获授权时不得尝试绕过或推测内容。
			当前这条用户消息始终是首要目标。只有用户明确说“继续、刚才、这个、那个”等指代时，才延续上一轮；
			新问题不得被上一轮未完成、失败或待授权的任务劫持，也不得重复上一轮的错误提示或工具调用。
			人物文生图必须明确为成年人；不要在提示词中保留真实艺术家、导演或摄影师姓名，改写为可观察的视觉特征。
			不要向用户展示工具名、JSON 参数或内部执行过程。
			""";

	private final String apiKey;
	private final String baseUrl;
	private final DashScopeModelFallback modelFallback;
	private final AssistantToolRegistry registry;
	private final ObjectMapper objectMapper;
	private final ToolMetricsService metrics;

	public FunctionCallingService(@Value("${aliyun.dashscope.api-key:}") String apiKey,
			@Value("${aliyun.dashscope.base-url:}") String baseUrl,
			DashScopeModelFallback modelFallback,
			AssistantToolRegistry registry, ObjectMapper objectMapper, ToolMetricsService metrics) {
		this.apiKey = apiKey;
		this.baseUrl = baseUrl;
		this.modelFallback = modelFallback;
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
		ToolChainGuard chainGuard = new ToolChainGuard();
		boolean guardInstructionAdded = false;
		configureEndpoint();
		try {
			for (int round = 0; round <= MAX_TOOL_ROUNDS; round++) {
				if (chainGuard.halted() && !guardInstructionAdded) {
					messages.add(message(Role.SYSTEM.getValue(), "工具链保护已触发：" + chainGuard.haltReason()
							+ "。禁止继续调用任何工具；请基于已有结果回答，并只询问一个最关键的澄清问题。"));
					guardInstructionAdded = true;
				}
				boolean toolsHalted = chainGuard.halted();
				boolean forceToolThisRound = !toolsHalted && round == 0 && forcedTool != null;
				if (forceToolThisRound) {
					log.info("明确能力请求，强制选择 Function Calling 工具：{}", forcedTool);
				}
				MultiModalConversationResult response = modelFallback.call("Function Calling", model -> {
					var requestBuilder = MultiModalConversationParam.builder()
							.apiKey(apiKey).model(model).messages(messages)
							.tools(registry.definitions()).parallelToolCalls(false)
							.enableThinking(false).maxTokens(1600);
					if (toolsHalted) {
						requestBuilder.toolChoice("none");
					} else if (forceToolThisRound) {
						requestBuilder.toolChoice(registry.definition(forcedTool));
						log.info("Function Calling 使用模型：{}", model);
					} else {
						requestBuilder.toolChoice("auto");
					}
					return new MultiModalConversation().call(requestBuilder.build());
				});
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
				if (chainGuard.halted()) {
					log.warn("模型在工具链停止后仍请求工具，已执行最终兜底");
					return AssistantReply.text("我还缺少完成这个请求的必要信息。请把目标、范围或关键参数说得更具体一些，我再继续处理。");
				}
				if (round == MAX_TOOL_ROUNDS) throw new IllegalStateException("工具调用次数超过限制");
				for (ToolCallBase base : calls) {
					if (!(base instanceof ToolCallFunction call)) throw new IllegalArgumentException("不支持的工具调用类型");
					String name = call.getFunction().getName();
					JsonNode arguments = objectMapper.readTree(call.getFunction().getArguments());
					AssistantTool tool = registry.require(name);
					ToolChainGuard.Decision decision = chainGuard.beforeCall(name, arguments);
					if (!decision.allowed()) {
						log.warn("阻止 Function Calling：{}，轮次={}，原因={}", name, round + 1, decision.code());
						messages.add(toolMessage(call, name, toolFailureContent(name,
								decision.code(), decision.message(), false)));
						continue;
					}
					log.info("执行 Function Calling 工具：{}，轮次={}，调用序号={}/{}",
							name, round + 1, decision.callNumber(), ToolChainGuard.MAX_TOTAL_CALLS);
					ToolMetricsService.Invocation invocation = metrics.start(name, sessionId);
					ToolResult result;
					try {
						result = tool.execute(arguments, new ToolContext(sessionId, question, media));
						metrics.success(invocation);
						chainGuard.success(name, decision.fingerprint(), result);
						log.info("Function Calling 工具执行完成：{}，结果有效={}", name, result.useful());
					} catch (Exception toolError) {
						metrics.failure(invocation, toolError.getMessage());
						chainGuard.failure(name);
						log.warn("Function Calling 工具执行失败：{}，原因={}", name, toolError.getMessage());
						messages.add(toolMessage(call, name, toolFailureContent(name,
								"TOOL_EXECUTION_FAILED", safeToolErrorMessage(toolError), !chainGuard.halted())));
						continue;
					}
					if (result.image() != null) generatedImage = result.image();
					if (result.document() != null) generatedDocument = result.document();
					if (result.audio() != null) generatedAudio = result.audio();
					messages.add(toolMessage(call, name, result.content()));
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

	private static MultiModalMessage toolMessage(ToolCallFunction call, String name, String content) {
		return MultiModalMessage.builder().role("tool").toolCallId(call.getId())
				.name(name).content(List.of(Map.of("text", content))).build();
	}

	private String toolFailureContent(String tool, String code, String message, boolean retryable) {
		var failure = objectMapper.createObjectNode();
		failure.put("success", false);
		failure.put("tool", tool);
		failure.put("errorCode", code);
		failure.put("message", message);
		failure.put("retryable", retryable);
		return failure.toString();
	}

	static String safeToolErrorMessage(Exception exception) {
		if (!(exception instanceof ApplicationException) && !(exception instanceof IllegalArgumentException)) {
			return "外部能力暂时不可用";
		}
		String message = exception.getMessage();
		if (message == null || message.isBlank()) return "外部能力暂时不可用";
		return message.length() > 300 ? message.substring(0, 300) + "…" : message;
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
		if (TIME_REQUEST.matcher(value).matches()) return "query_time";
		if (BODY_FAT_REQUEST.matcher(value).matches()) return "calculate_body_fat";
		if (CALCULATION_REQUEST.matcher(value).matches()) return "scientific_calculator";
		if (BILIBILI_REQUEST.matcher(value).matches()) return "manage_bilibili_favorites";
		return null;
	}

	private void configureEndpoint() {
		if (baseUrl != null && !baseUrl.isBlank()) {
			String endpoint = baseUrl.trim().replace("/compatible-mode/v1", "/api/v1");
			Constants.baseHttpApiUrl = endpoint.endsWith("/") ? endpoint : endpoint + "/";
		}
	}
}
