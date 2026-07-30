package com.example.bot.tools;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Component;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonParser;

@Component
public class ScientificCalculatorTool implements AssistantTool {

	private static final FunctionDefinition DEFINITION = FunctionDefinition.builder()
			.name("scientific_calculator")
			.description("精确计算数学表达式，支持加减乘除、幂、括号、百分号、阶乘、pi/e、sqrt、abs、ln、log、sin/cos/tan、asin/acos/atan、floor/ceil/round、min/max。涉及数值计算时必须调用，不能心算或编造结果。")
			.parameters(JsonParser.parseString("""
					{"type":"object","properties":{"expression":{"type":"string","description":"数学表达式，例如 sqrt(2)^2 + sin(30)，使用英文函数名"},"angleUnit":{"type":"string","enum":["degree","radian"],"description":"三角函数角度单位，默认 degree（角度）"},"precision":{"type":"integer","minimum":0,"maximum":12,"description":"结果保留小数位，默认6"}},"required":["expression"],"additionalProperties":false}
					""").getAsJsonObject()).build();

	private final ObjectMapper objectMapper;

	public ScientificCalculatorTool(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override public FunctionDefinition definition() { return DEFINITION; }

	@Override
	public ToolResult execute(JsonNode arguments, ToolContext context) {
		String expression = arguments.path("expression").asText("").trim();
		if (expression.isBlank()) throw new IllegalArgumentException("缺少必填参数：expression");
		if (expression.length() > 300) throw new IllegalArgumentException("表达式过长，最多 300 个字符");
		AngleUnit unit = "radian".equals(arguments.path("angleUnit").asText())
				? AngleUnit.RADIAN : AngleUnit.DEGREE;
		int precision = arguments.has("precision") ? arguments.path("precision").asInt(6) : 6;
		if (precision < 0 || precision > 12) throw new IllegalArgumentException("precision 必须在 0 到 12 之间");
		double value = new Parser(expression, unit).parse();
		if (!Double.isFinite(value)) throw new IllegalArgumentException("计算结果不是有限数字");
		var response = objectMapper.createObjectNode();
		response.put("expression", expression);
		response.put("angleUnit", unit == AngleUnit.DEGREE ? "degree" : "radian");
		response.put("result", value);
		response.put("formatted", BigDecimal.valueOf(value).setScale(precision, RoundingMode.HALF_UP)
				.stripTrailingZeros().toPlainString());
		return ToolResult.text(response.toString());
	}

	private enum AngleUnit { DEGREE, RADIAN }

	private static final class Parser {
		private final String source;
		private final AngleUnit angleUnit;
		private int index;

		private Parser(String source, AngleUnit angleUnit) {
			this.source = source.replace("×", "*").replace("÷", "/").replace('−', '-')
					.replace("π", "pi").trim();
			this.angleUnit = angleUnit;
		}

		private double parse() {
			double value = expression();
			spaces();
			if (index != source.length()) throw error("无法识别的字符：" + source.charAt(index));
			return value;
		}

		private double expression() {
			double value = term();
			while (true) {
				if (consume('+')) value += term();
				else if (consume('-')) value -= term();
				else return value;
			}
		}

		private double term() {
			double value = unary();
			while (true) {
				if (consume('*')) value *= unary();
				else if (consume('/')) {
					double divisor = unary();
					if (divisor == 0d) throw error("不能除以 0");
					value /= divisor;
				} else if (consume('%')) {
					double divisor = unary();
					if (divisor == 0d) throw error("不能对 0 取模");
					value %= divisor;
				} else return value;
			}
		}

		private double unary() {
			if (consume('+')) return unary();
			if (consume('-')) return -unary();
			return power();
		}

		private double power() {
			double value = postfix();
			if (consume('^')) value = Math.pow(value, unary());
			return value;
		}

		private double postfix() {
			double value = primary();
			while (consume('!')) {
				if (value < 0 || value != Math.rint(value) || value > 170) {
					throw error("阶乘仅支持 0 到 170 的整数");
				}
				double factorial = 1d;
				for (int i = 2; i <= (int) value; i++) factorial *= i;
				value = factorial;
			}
			return value;
		}

		private double primary() {
			if (consume('(')) {
				double value = expression();
				if (!consume(')')) throw error("缺少右括号");
				return value;
			}
			if (index < source.length() && (Character.isDigit(source.charAt(index)) || source.charAt(index) == '.')) {
				return number();
			}
			String name = identifier();
			if (name.isEmpty()) throw error("此处需要数字、常量或函数");
			if ("pi".equalsIgnoreCase(name)) return Math.PI;
			if ("e".equalsIgnoreCase(name)) return Math.E;
			if (!consume('(')) throw error("函数 " + name + " 缺少左括号");
			double first = expression();
			double second = Double.NaN;
			if (consume(',')) second = expression();
			if (!consume(')')) throw error("函数 " + name + " 缺少右括号");
			return function(name.toLowerCase(java.util.Locale.ROOT), first, second);
		}

		private double function(String name, double first, double second) {
			return switch (name) {
				case "sqrt" -> Math.sqrt(first);
				case "abs" -> Math.abs(first);
				case "ln" -> Math.log(first);
				case "log" -> Math.log10(first);
				case "sin" -> Math.sin(angle(first));
				case "cos" -> Math.cos(angle(first));
				case "tan" -> Math.tan(angle(first));
				case "asin" -> unangle(Math.asin(first));
				case "acos" -> unangle(Math.acos(first));
				case "atan" -> unangle(Math.atan(first));
				case "floor" -> Math.floor(first);
				case "ceil" -> Math.ceil(first);
				case "round" -> Math.rint(first);
				case "min" -> requireSecond(name, first, second, Math::min);
				case "max" -> requireSecond(name, first, second, Math::max);
				default -> throw error("不支持的函数：" + name);
			};
		}

		private double requireSecond(String name, double first, double second,
				java.util.function.DoubleBinaryOperator operation) {
			if (Double.isNaN(second)) throw error(name + " 需要两个参数，例如 " + name + "(2,3)");
			return operation.applyAsDouble(first, second);
		}

		private double angle(double value) { return angleUnit == AngleUnit.DEGREE ? Math.toRadians(value) : value; }
		private double unangle(double value) { return angleUnit == AngleUnit.DEGREE ? Math.toDegrees(value) : value; }

		private double number() {
			spaces();
			int start = index;
			while (index < source.length() && (Character.isDigit(source.charAt(index)) || source.charAt(index) == '.')) index++;
			if (index < source.length() && (source.charAt(index) == 'e' || source.charAt(index) == 'E')) {
				index++;
				if (index < source.length() && (source.charAt(index) == '+' || source.charAt(index) == '-')) index++;
				while (index < source.length() && Character.isDigit(source.charAt(index))) index++;
			}
			try { return Double.parseDouble(source.substring(start, index)); }
			catch (NumberFormatException exception) { throw error("数字格式无效"); }
		}

		private String identifier() {
			spaces();
			int start = index;
			while (index < source.length() && Character.isLetter(source.charAt(index))) index++;
			return source.substring(start, index);
		}

		private boolean consume(char expected) {
			spaces();
			if (index < source.length() && source.charAt(index) == expected) { index++; return true; }
			return false;
		}

		private void spaces() { while (index < source.length() && Character.isWhitespace(source.charAt(index))) index++; }
		private IllegalArgumentException error(String message) { return new IllegalArgumentException(message + "（位置 " + (index + 1) + "）"); }
	}
}
