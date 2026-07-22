package com.example.demo.chat;

import java.util.Locale;
import java.util.regex.Pattern;

public final class ImageReplyPolicy {
	private static final Pattern EXPLICIT_REFERENCE = Pattern.compile(
			"这张|这幅|这个画面|刚才(?:的|那张)?|上面(?:的|那张)?|前面(?:的|那张)?|上一张|这些图|两张图|"
			+ "(?:图片|照片|图像|画面).{0,10}(?:描述|分析|识别|查看|看看|评价|修改|编辑|美化|处理|比较|区别|不同)|"
			+ "(?:描述|分析|识别|查看|看看|评价|修改|编辑|美化|处理|比较).{0,10}(?:图片|照片|图像|画面|这张|两张)|"
			+ "类似(?:的)?(?:图片|照片|图像)|同款|参考(?:这张|刚才|上面|前面|图片|照片|图)|"
			+ "找不同|比较.{0,8}(?:图片|照片|图像|两张)|(?:图片|照片|图像).{0,8}(?:区别|不同)");
	private static final Pattern SHORT_INSTRUCTION = Pattern.compile(
			"^(?:请|帮我|麻烦)?(?:描述|分析|识别|看看|看一下|评价|修改|编辑|美化|处理|比较)(?:一下|下)?(?:它|这张|这个)?(?:吧|。|！|!)?$");
	private static final Pattern CONTEXTUAL_EDIT = Pattern.compile(
			"^(?:(?:请|帮我|麻烦)?(?:把|将)(?:它|这个|背景|人物|人像|天空|颜色|文字|水印|主体).{0,12}"
			+ "(?:换成|改成|替换为|去掉|删除|移除|添加|加上|变成|调成|增强|修复|扩图|变清晰).{0,20}|"
			+ "(?:背景|人物|人像|天空|颜色|文字|水印|主体).{0,8}(?:换成|改成|替换为|去掉|删除|移除|添加|加上|变成|调成|增强|修复|扩图|变清晰).{0,20}|"
			+ "(?:换成|改成|替换为|去掉|删除|移除|加上|变成|调成|增强|修复|扩图|变清晰).{0,20})$");

	private ImageReplyPolicy() {
	}

	public static boolean referencesUploadedImage(String instruction) {
		if (instruction == null || instruction.isBlank()) return false;
		String value = instruction.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
		return EXPLICIT_REFERENCE.matcher(value).find() || SHORT_INSTRUCTION.matcher(value).matches()
				|| CONTEXTUAL_EDIT.matcher(value).matches();
	}
}
