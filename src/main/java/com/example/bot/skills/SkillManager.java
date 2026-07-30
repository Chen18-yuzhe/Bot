package com.example.bot.skills;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Automatically registers every Spring-discovered {@link Skill}.
 *
 * <p>To add a skill, add an {@code @Component} implementation under
 * {@code src/main/java/com/example/bot/skills}; it is discovered during the
 * normal component scan and included in this ordered pipeline automatically.</p>
 */
@Component
public class SkillManager {
	private static final Logger log = LoggerFactory.getLogger(SkillManager.class);
	private final List<Skill> skills;

	public SkillManager(List<Skill> discoveredSkills) {
		Map<String, Skill> byId = new LinkedHashMap<>();
		for (Skill skill : discoveredSkills) {
			if (skill.id() == null || skill.id().isBlank()) {
				throw new IllegalStateException("Skill id must not be blank: " + skill.getClass().getName());
			}
			if (byId.putIfAbsent(skill.id(), skill) != null) {
				throw new IllegalStateException("Duplicate skill id: " + skill.id());
			}
		}
		this.skills = byId.values().stream()
				.sorted(Comparator.comparingInt(Skill::order).thenComparing(Skill::id))
				.toList();
		log.info("Registered skills: {}", this.skills.stream().map(Skill::id).toList());
	}

	public String enrich(SkillContext context) {
		return skills.stream()
				.filter(skill -> skill.supports(context))
				.map(skill -> safelyApply(skill, context))
				.filter(SkillContribution::useful)
				.map(SkillContribution::context)
				.reduce((left, right) -> left + "\n\n" + right)
				.orElse("");
	}

	public List<String> registeredSkillIds() {
		return skills.stream().map(Skill::id).toList();
	}

	private SkillContribution safelyApply(Skill skill, SkillContext context) {
		try {
			SkillContribution contribution = skill.apply(context);
			return contribution == null ? SkillContribution.empty(skill.id()) : contribution;
		} catch (RuntimeException error) {
			// An optional enrichment source must not make normal chat unavailable.
			log.warn("Skill {} failed; continuing without its context: {}", skill.id(), error.getMessage());
			return SkillContribution.empty(skill.id());
		}
	}
}
