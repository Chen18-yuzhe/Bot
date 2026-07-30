package com.example.demo.skills;

/**
 * A pluggable message-enrichment capability.
 *
 * <p>Implementations live below {@code com.example.demo.skills} and are Spring
 * components. Spring discovers every implementation at startup; no central
 * if/else routing table needs to be changed when a new skill is added.</p>
 */
public interface Skill {

	/** A stable, unique identifier used in logs and diagnostics. */
	String id();

	/** Lower values run first. */
	default int order() { return 0; }

	/** Returns whether this skill is relevant to this message. */
	default boolean supports(SkillContext context) { return true; }

	/** Produces context for the downstream LLM, or an empty contribution. */
	SkillContribution apply(SkillContext context);
}
