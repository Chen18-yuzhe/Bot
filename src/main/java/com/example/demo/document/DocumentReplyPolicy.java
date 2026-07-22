package com.example.demo.document;

public final class DocumentReplyPolicy {
	private DocumentReplyPolicy() {
	}

	public static boolean shouldReturnFile(String instruction, String summary, int maxReplyChars) {
		if (isFileRequested(instruction)) return true;
		return summary != null && summary.length() > maxReplyChars;
	}

	public static boolean referencesUploadedFile(String instruction) {
		if (instruction == null || instruction.isBlank()) return false;
		String value = normalize(instruction);
		return isFileRequested(value)
				|| value.matches(".*(?:加入|添加|保存|导入|收录)(?:到|进|至)?(?:我的|个人)?知识库.*")
				|| value.matches(".*(?:知识库).{0,8}(?:加入|添加|保存|导入|收录).*")
				|| value.matches("^(?:请|帮我|麻烦)?(?:分析|总结|概括|整理|查看|解读|提取重点|列出重点|生成摘要)(?:一下|下|这个|这份)?(?:吧|。|！|!)?$")
				|| value.matches("^(?:请|帮我|麻烦)?翻译(?:成|为)?(?:英文|英语|中文|汉语)?(?:一下|下)?(?:吧|。|！|!)?$")
				|| value.matches(".*(?:这个|这份|刚才|上面|前面|之前|上传|原来).{0,6}(?:文件|文档|表格|报告|ppt|pdf|word).*")
				|| value.matches(".*(?:文件|文档|表格|报告|ppt|pdf|word).{0,6}(?:总结|分析|提取|整理|改写|翻译|查看).*");
	}

	private static boolean isFileRequested(String instruction) {
		if (instruction == null || instruction.isBlank()) return false;
		String value = normalize(instruction);
		if (value.contains("导出")) return true;
		String[] actions = { "生成", "返回", "输出", "保存", "整理成", "整理为" };
		String[] formats = { "word", "docx", "文档", "文件", "报告" };
		for (String action : actions) {
			int actionAt = value.indexOf(action);
			if (actionAt < 0) continue;
			for (String format : formats) {
				int formatAt = value.indexOf(format, actionAt + action.length());
				if (formatAt >= 0 && formatAt - actionAt <= 12) return true;
			}
		}
		return value.contains("word格式") || value.contains("docx格式") || value.contains("文件格式");
	}

	private static String normalize(String instruction) {
		return instruction.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", "");
	}

}
