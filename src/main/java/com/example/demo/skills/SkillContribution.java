package com.example.demo.skills;

/** Context emitted by a skill for the model call. */
public record SkillContribution(String skillId, String context) {
	public static SkillContribution empty(String skillId) {
		return new SkillContribution(skillId, "");
	}

	public boolean useful() {
		return context != null && !context.isBlank();
	}
}
