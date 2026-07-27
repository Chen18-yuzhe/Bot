package com.example.demo.tools;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonParser;

@Component
public class BodyFatTool implements AssistantTool {
	private static final double CM_PER_INCH = 2.54d;
	private static final FunctionDefinition DEFINITION = FunctionDefinition.builder()
			.name("calculate_body_fat")
			.description("使用美国海军围度法估算成人体脂率（BFR）。需要性别、身高、腰围、颈围；女性还需要臀围。结果仅为估算，不能用于医疗诊断。")
			.parameters(JsonParser.parseString("""
					{"type":"object","properties":{"gender":{"type":"string","enum":["male","female"],"description":"生理性别"},"heightCm":{"type":"number","minimum":80,"maximum":260,"description":"身高，厘米"},"waistCm":{"type":"number","minimum":30,"maximum":250,"description":"腰围，厘米"},"neckCm":{"type":"number","minimum":15,"maximum":100,"description":"颈围，厘米"},"hipCm":{"type":"number","minimum":40,"maximum":250,"description":"女性必填，臀围，厘米"}},"required":["gender","heightCm","waistCm","neckCm"],"additionalProperties":false}
					""").getAsJsonObject()).build();

	private final ObjectMapper objectMapper;

	public BodyFatTool(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }
	@Override public FunctionDefinition definition() { return DEFINITION; }

	@Override
	public ToolResult execute(JsonNode arguments, ToolContext context) {
		String gender = arguments.path("gender").asText("").toLowerCase(Locale.ROOT);
		double height = number(arguments, "heightCm");
		double waist = number(arguments, "waistCm");
		double neck = number(arguments, "neckCm");
		double hip = arguments.has("hipCm") ? number(arguments, "hipCm") : 0d;
		if (!"male".equals(gender) && !"female".equals(gender)) throw new IllegalArgumentException("gender 必须为 male 或 female");
		if (waist <= neck) throw new IllegalArgumentException("腰围必须大于颈围");
		if ("female".equals(gender) && hip <= 0d) throw new IllegalArgumentException("女性估算需要 hipCm（臀围）");

		double heightIn = height / CM_PER_INCH;
		double percentage = "male".equals(gender)
				? 495d / (1.0324d - 0.19077d * Math.log10((waist - neck) / CM_PER_INCH)
						+ 0.15456d * Math.log10(heightIn)) - 450d
				: 495d / (1.29579d - 0.35004d * Math.log10((waist + hip - neck) / CM_PER_INCH)
						+ 0.22100d * Math.log10(heightIn)) - 450d;
		if (!Double.isFinite(percentage) || percentage < 0d || percentage > 75d) {
			throw new IllegalArgumentException("围度组合无法得到可靠估算，请检查身高和围度单位是否为厘米");
		}
		var result = objectMapper.createObjectNode();
		result.put("method", "US Navy circumference estimate");
		result.put("gender", gender);
		result.put("bodyFatPercent", BigDecimal.valueOf(percentage).setScale(1, RoundingMode.HALF_UP).doubleValue());
		result.put("referenceRange", range(gender, percentage));
		result.put("disclaimer", "这是基于围度的成人估算，不等同于医学检测；孕期、未成年人、疾病或体重管理问题请咨询医生或营养师。");
		return ToolResult.text(result.toString());
	}

	private static double number(JsonNode arguments, String name) {
		if (!arguments.has(name) || !arguments.get(name).isNumber()) throw new IllegalArgumentException("缺少或无效参数：" + name);
		double value = arguments.get(name).asDouble();
		if (!Double.isFinite(value) || value <= 0d) throw new IllegalArgumentException(name + " 必须为正数");
		return value;
	}

	private static String range(String gender, double value) {
		if ("male".equals(gender)) {
			if (value < 6d) return "偏低";
			if (value < 25d) return "常见成人范围";
			return "偏高";
		}
		if (value < 14d) return "偏低";
		if (value < 32d) return "常见成人范围";
		return "偏高";
	}
}
