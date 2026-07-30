package com.example.bot.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 任务挂起时面向人工的请求。例如：Agent 没有登录信息时挂起，
 * 人工完成登录授权后通过 submitHumanInput 恢复任务。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HumanRequest(Kind kind, String prompt, long requestedAtMs) {
	public enum Kind {
		/** 需要人工完成登录 / 授权。 */
		LOGIN,
		/** 需要人工审批某个动作（如数据库写入）。 */
		APPROVAL,
		/** 需要人工补充信息。 */
		INPUT,
		/** 需要人工审核异常后决定继续或终止。 */
		REVIEW
	}
}
