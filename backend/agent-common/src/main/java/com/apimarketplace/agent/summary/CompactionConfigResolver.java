package com.apimarketplace.agent.summary;

/**
 * Resolves the EFFECTIVE compaction enablement + cadence for a single
 * conversation, layering three independent tiers (highest precedence first):
 *
 * <ol>
 *   <li><b>Per-conversation override</b> - {@code conversation.chat_config.compaction.*}
 *       (set from the message-composer "advanced" options, or seeded from the
 *       per-(user, workspace) chat defaults).</li>
 *   <li><b>Per-agent override</b> - {@code agent.compaction_enabled} /
 *       {@code agent.compaction_after_turns} (set on the agent configuration UI
 *       or by an agent via the agent CRUD tool).</li>
 *   <li><b>YAML default</b> - {@code conversation.compaction.enabled} /
 *       {@code conversation.compaction.cadenceTurns} (the global master switch
 *       + fixed cadence).</li>
 * </ol>
 *
 * <p>Each field resolves <em>independently</em>: a conversation may override
 * {@code enabled} while leaving {@code afterTurns} to the agent or the YAML
 * default, and vice-versa. {@code null} at a tier means "inherit the next tier".
 *
 * <p>This mirrors the {@link AgentCompactionModelResolver} pattern (one place
 * answers "what is the effective compaction config right now?") so the
 * orchestrator never scatters the fallback ladder across call sites.
 */
public final class CompactionConfigResolver {

    private CompactionConfigResolver() {}

    /**
     * Ultimate cadence floor when no tier supplies a positive value. Matches
     * {@code ColdSummaryGate.DEFAULT_CADENCE_TURNS} - duplicated as a literal to
     * keep this resolver free of a hard dependency on the gate's internals.
     */
    static final int DEFAULT_CADENCE_FLOOR = 5;

    /**
     * Effective, fully-resolved compaction settings. {@code afterTurns} is always
     * {@code >= 1} (a non-positive cadence is meaningless to the gate).
     */
    public record Effective(boolean enabled, int afterTurns) {}

    /**
     * Resolve the effective compaction config from the three tiers.
     *
     * @param conversationEnabled     per-conversation enable override; {@code null} ⇒ inherit.
     * @param conversationAfterTurns  per-conversation cadence override; {@code null}/non-positive ⇒ inherit.
     * @param agentEnabled            per-agent enable override; {@code null} ⇒ inherit.
     * @param agentAfterTurns         per-agent cadence override; {@code null}/non-positive ⇒ inherit.
     * @param yamlEnabled             global master switch ({@code conversation.compaction.enabled}).
     * @param yamlCadenceTurns        global cadence ({@code conversation.compaction.cadenceTurns}).
     * @return the resolved {@link Effective} settings.
     */
    public static Effective resolve(Boolean conversationEnabled, Integer conversationAfterTurns,
                                    Boolean agentEnabled, Integer agentAfterTurns,
                                    boolean yamlEnabled, int yamlCadenceTurns) {
        boolean enabled = firstNonNull(conversationEnabled, agentEnabled, yamlEnabled);

        Integer override = firstPositive(conversationAfterTurns, agentAfterTurns);
        int afterTurns = override != null
                ? override
                : (yamlCadenceTurns >= 1 ? yamlCadenceTurns : DEFAULT_CADENCE_FLOOR);

        return new Effective(enabled, afterTurns);
    }

    private static boolean firstNonNull(Boolean first, Boolean second, boolean fallback) {
        if (first != null) return first;
        if (second != null) return second;
        return fallback;
    }

    /** First of the two that is non-null and {@code >= 1}, else {@code null}. */
    private static Integer firstPositive(Integer first, Integer second) {
        if (first != null && first >= 1) return first;
        if (second != null && second >= 1) return second;
        return null;
    }
}
