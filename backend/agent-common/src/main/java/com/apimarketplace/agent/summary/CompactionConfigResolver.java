package com.apimarketplace.agent.summary;

/**
 * Resolves the EFFECTIVE compaction settings (enablement, cadence, trigger
 * mode and its growth threshold) for a single
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
 *       {@code cadence-turns} / {@code trigger} / {@code size-trigger-cold-tokens}
 *       (the global master switch, the fixed cadence, and the trigger mode with
 *       its growth threshold). The last two have no per-scope tier today and so
 *       resolve from YAML alone, but they resolve HERE: reading half the
 *       settings at the call site is what lets a tier quietly stop applying.</li>
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
    public record Effective(boolean enabled, int afterTurns,
                            CompactionTrigger trigger, int sizeTriggerColdTokens) {

        /** Back-compat shape for callers that predate the trigger modes. */
        public Effective(boolean enabled, int afterTurns) {
            this(enabled, afterTurns, CompactionTrigger.TURNS, 0);
        }
    }

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
        return resolve(conversationEnabled, conversationAfterTurns, agentEnabled, agentAfterTurns,
                yamlEnabled, yamlCadenceTurns, CompactionTrigger.TURNS, 0);
    }

    /**
     * Trigger-aware resolve. The mode and its growth threshold have no
     * per-conversation or per-agent tier yet, so they arrive from YAML only,
     * but they are resolved HERE rather than read straight off the config at
     * the call site: this class is the single documented home of the ladder,
     * and reading half the settings elsewhere is what makes a tier quietly
     * stop applying.
     *
     * <p><b>An explicit per-scope cadence is never silently discarded.</b>
     * {@link CompactionTrigger#SIZE} ignores the cadence by definition, so a
     * global {@code size} would make a user's own "compact after N turns"
     * setting inert while the agent UI, the agent tool help and the docs all
     * still promise it works. When a conversation or an agent has explicitly
     * set a cadence, the effective mode is upgraded to
     * {@link CompactionTrigger#SIZE_OR_TURNS} for that scope so both the
     * operator's intent and the user's survive. Nothing is upgraded when the
     * cadence is merely inherited from YAML.
     */
    public static Effective resolve(Boolean conversationEnabled, Integer conversationAfterTurns,
                                    Boolean agentEnabled, Integer agentAfterTurns,
                                    boolean yamlEnabled, int yamlCadenceTurns,
                                    CompactionTrigger yamlTrigger, int yamlSizeTriggerColdTokens) {
        boolean enabled = firstNonNull(conversationEnabled, agentEnabled, yamlEnabled);

        Integer override = firstPositive(conversationAfterTurns, agentAfterTurns);
        int afterTurns = override != null
                ? override
                : (yamlCadenceTurns >= 1 ? yamlCadenceTurns : DEFAULT_CADENCE_FLOOR);

        CompactionTrigger trigger = yamlTrigger == null ? CompactionTrigger.TURNS : yamlTrigger;
        if (trigger == CompactionTrigger.SIZE && override != null) {
            trigger = CompactionTrigger.SIZE_OR_TURNS;
        }

        return new Effective(enabled, afterTurns, trigger, yamlSizeTriggerColdTokens);
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
