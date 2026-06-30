package com.apimarketplace.agent.skills;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Provides default (built-in) skill definitions.
 * These are seeded as real DB entities per tenant on first access.
 * Users can edit the content but cannot delete default skills.
 * Reset restores original content from this provider.
 *
 * Used by:
 * - agent-service: SkillService.seedDefaultSkills() creates DB entities from these definitions
 * - orchestrator-service: SkillCrudModule handles skill(action='get', skill_id='default:...')
 * - conversation-service: AgentContextBuilder injects [DEFAULT SKILLS] section into system prompt
 */
public final class DefaultSkillsProvider {

    private DefaultSkillsProvider() {}

    public record DefaultSkill(String id, String name, String description, String icon, String instructions) {}

    private static final Map<String, DefaultSkill> SKILLS = new LinkedHashMap<>();

    // ═══════════════════════════════════════════════════════════════════
    // SKILL INSTRUCTIONS
    // ═══════════════════════════════════════════════════════════════════

    private static final String DEEP_RESEARCH_INSTRUCTIONS = """
            # Deep Research Skill

            ## Purpose
            Conduct thorough, autonomous web research using `web_search` tool with iterative queries,
            source evaluation, cross-referencing, and structured report synthesis.

            ## Tools Used
            - `web_search(action='search', query='...', max_results=10)` - fast web search (~1s), returns URLs + snippets
            - `web_search(action='fetch', url='...')` - extract full page content as markdown (~5s)
            - `web_search(action='fetch', urls=['url1','url2',...])` - batch fetch up to 10 URLs in parallel
            - `table(action='create', name='...', data=[...])` - persist research findings for later reference

            ## Process
            1. **Decompose the query**: Break the user's question into 3-5 sub-topics or angles
            2. **Search iteratively**: For each angle, call `web_search(action='search', query='...')`
               - Use `time_range='month'` or `time_range='year'` for recent information
               - Refine queries based on initial results - rephrase, add/remove keywords
            3. **Evaluate snippets**: If search snippets answer the question, no need to fetch full pages
            4. **Fetch key sources**: For in-depth analysis, use `web_search(action='fetch', urls=[...])` on the 3-5 most relevant URLs
            5. **Cross-reference**: Verify claims across at least 2 independent sources
            6. **Synthesize**: Combine findings into a structured report
            7. **Persist if needed**: Save structured findings to a table with `table(action='create')` for future reference

            ## Output Format
            - **Executive Summary** (2-3 sentences)
            - **Key Findings** (bulleted, each with [source URL])
            - **Detailed Analysis** (organized by sub-topic)
            - **Confidence Assessment** (high/medium/low per finding)
            - **Sources** (numbered list with clickable URLs)

            ## Rules
            - Maximum 5 search iterations per research session
            - Start broad, then narrow with refined queries
            - Prefer recent sources (use `time_range` parameter) unless historical context needed
            - Flag conflicting information explicitly with both sources
            - Distinguish facts from opinions from speculation
            - Always show which search queries were used for transparency
            - If a fetch fails or returns empty, try an alternative URL from search results
            - **Cleanup**: At the end of the research session, if you created temporary tables for internal use only and did not mention them to the user in your final report, delete them with `table(action='delete', table_id=N)` to avoid cluttering the user's workspace
            """;

    // ═══════════════════════════════════════════════════════════════════
    // STATIC INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════

    static {
        register(new DefaultSkill(
            "default:deep_research",
            "Deep Research",
            "Autonomous deep web research with iterative queries and synthesis",
            "search",
            DEEP_RESEARCH_INSTRUCTIONS
        ));
    }

    // ═══════════════════════════════════════════════════════════════════
    // REGISTRY
    // ═══════════════════════════════════════════════════════════════════

    private static void register(DefaultSkill skill) {
        SKILLS.put(skill.id(), skill);
    }

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
