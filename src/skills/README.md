# Runtime Skills

This directory documents the source-level Skill extension point. Java skills are
implemented as `@Component` classes in
`src/main/java/com/example/demo/skills/`, because Maven compiles that directory
and Spring automatically scans it at startup.

To add a skill, implement `Skill`, give it a unique `id`, and place it in that
package. `SkillManager` receives the complete discovered set, checks duplicate
IDs, orders it, and runs applicable skills without changing a routing `if/else`.

The first built-in skill is `RagRetrievalSkill`, which supplies local knowledge
base passages to the LLM before reply generation.
