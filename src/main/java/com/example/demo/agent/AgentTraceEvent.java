package com.example.demo.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 一次循环事件：每一次推理、工具调用、恢复决策都会落一条 trace，
 * 出问题时可完整回放任务的决策过程，便于调试和审计。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentTraceEvent(String taskId, int step, Phase phase, String kind, String summary,
		String detail, long tokensUsedTotal, long atMs) {

	/** 循环阶段：感知 → 推理 → 行动 → 观察 → 评估，外加决策 / 控制 / 终止。 */
	public enum Phase { PERCEIVE, REASON, ACT, OBSERVE, EVALUATE, DECISION, CONTROL, TERMINATE }

	public static AgentTraceEvent of(String taskId, int step, Phase phase, String kind, String summary,
			String detail, long tokensUsedTotal) {
		return new AgentTraceEvent(taskId, step, phase, kind, summary, detail, tokensUsedTotal,
				System.currentTimeMillis());
	}
}
