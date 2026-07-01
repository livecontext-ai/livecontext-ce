package com.apimarketplace.agent.skills;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Provides default (built-in) skill definitions.
 * These are seeded as real DB entities per tenant on first access.
 * Users can edit the content but cannot delete default skills.
 * Reset restores original content from this provider.
 *
 * <p>No built-in skills are registered any more. The former "Deep Research"
 * default was removed: global skills are now distributed from the cloud via the
 * signed skill bundle (see SkillBundleService), so the platform no longer ships
 * a hard-coded per-tenant default. The registry is intentionally empty - every
 * accessor below degrades to an empty result, so the seeding path
 * (SkillService.seedDefaultSkills), the {@code default:} lookup in
 * SkillCrudModule and the [DEFAULT SKILLS] prompt section in AgentContextBuilder
 * all become no-ops until a new built-in is registered.
 *
 * Used by:
 * - agent-service: SkillService.seedDefaultSkills() creates DB entities from these definitions
 * - orchestrator-service: SkillCrudModule handles skill(action='get', skill_id='default:...')
 * - conversation-service: AgentContextBuilder injects [DEFAULT SKILLS] section into system prompt
 */
public final class DefaultSkillsProvider {

    private DefaultSkillsProvider() {}

    public record DefaultSkill(String id, String name, String description, String icon, String instructions) {}

    /**
     * Built-in default skills, keyed by {@code default:<key>} id. Intentionally
     * empty (see class doc). To reintroduce a built-in, put a DefaultSkill here.
     */
    private static final Map<String, DefaultSkill> SKILLS = new LinkedHashMap<>();

    public static List<DefaultSkill> getAll() {
        return List.copyOf(SKILLS.values());
    }

    public static Optional<DefaultSkill> getById(String id) {
        return Optional.ofNullable(SKILLS.get(id));
    }

    public static boolean isDefaultSkillId(String id) {
        return id != null && id.startsWith("default:");
    }

    /**
     * Build the [DEFAULT SKILLS] prompt section for active skill IDs.
     * Injected into the system prompt by AgentContextBuilder.
     */
    public static String buildPromptSection(List<String> activeIds) {
        if (activeIds == null || activeIds.isEmpty()) return "";

        List<DefaultSkill> active = activeIds.stream()
            .map(SKILLS::get)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());

        if (active.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("[DEFAULT SKILLS]\n");
        sb.append("These skills are always available. To activate a skill and load its full instructions, call: skill(action='get', skill_id='<id>')\n\n");

        for (DefaultSkill skill : active) {
            sb.append("- ").append(skill.name())
              .append(" [").append(skill.id()).append("]")
              .append(" - ").append(skill.description())
              .append("\n");
        }

        return sb.toString().stripTrailing();
    }
}
