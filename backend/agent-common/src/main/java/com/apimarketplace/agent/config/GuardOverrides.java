package com.apimarketplace.agent.config;

import java.util.Map;
import java.util.Set;

/**
 * Single source of truth for the 3 per-turn / loop-detector guard keys that can
 * be overridden per agent (V100 columns on {@code agent.agents}) or per conversation
 * (JSONB path {@code conversation.chat_config.turnLimits.*}).
 *
 * <p>The three keys are:
 * <ul>
 *   <li>{@link #MAX_PER_RESOURCE_PER_TURN} - uniform per-resource cap applied to
 *       agent / skill / sub_agent / interface / workflow / table creation calls.
 *       Counted per resource type (i.e. the cap is "up to N per type per turn",
 *       not "N total"). Replaces the previous per-resource caps.</li>
 *   <li>{@link #LOOP_IDENTICAL_STOP} - LoopDetector identical-call hard stop.</li>
 *   <li>{@link #LOOP_CONSECUTIVE_STOP} - LoopDetector consecutive-call hard stop.</li>
 * </ul>
 *
 * <p>Used by the two write paths that accept guard overrides:
 * <ul>
 *   <li>{@code agent-service} {@code AgentService.validateGuardOverrides} - rejects
 *       unknown keys and out-of-range values before they hit V100 CHECK constraints.</li>
 *   <li>{@code conversation-service} {@code ConversationCommandService.updateConversation}
 *       - rejects the same shape inside {@code chatConfig.turnLimits}.</li>
 * </ul>
 *
 * <p>Used by the read path ({@code AgentContextBuilder}) to populate
 * {@code AgentLoopContext} and the credentials map with effective values.
 *
 * <p>Credential key ({@code CRED_MAX_PER_RESOURCE_PER_TURN}) lets tool modules
 * (AgentCrudModule / SkillCrudModule / SubAgentExecutionHandler / InterfaceCrudModule
 * / WorkflowBuilderProvider / DataSourceTableModule) resolve the per-turn cap for
 * general-chat runs where there is no caller-agent entity to read from - see
 * {@link #resolve(Integer, Map, String, int)}.
 *
 * <p><strong>Range rules mirror V100 CHECK constraints</strong> - keep the two in sync.
 * {@code null} is always accepted (= clear override / fall back to platform default).
 */
public final class GuardOverrides {

    private GuardOverrides() {}

    // --- Key names (match V100 column camelCase accessors and chatConfig.turnLimits field names) ---
    /**
     * Uniform cap on resource-creation calls per turn, applied separately to each
     * tracked resource type (agent / skill / sub_agent / interface / workflow / table).
     */
    public static final String MAX_PER_RESOURCE_PER_TURN = "maxPerResourcePerTurn";
    public static final String LOOP_IDENTICAL_STOP = "loopIdenticalStop";
    public static final String LOOP_CONSECUTIVE_STOP = "loopConsecutiveStop";

    /** Ordered for deterministic test output - agent-scope (3 keys). */
    public static final Set<String> KEYS = Set.of(
        MAX_PER_RESOURCE_PER_TURN,
        LOOP_IDENTICAL_STOP,
        LOOP_CONSECUTIVE_STOP
    );

    // --- Credential keys for conversation-scope fallback (__chat*__ namespace) ---
    // Tool modules read this when __agentId__ is absent (general chat).
    public static final String CRED_MAX_PER_RESOURCE_PER_TURN = "__chatMaxPerResourcePerTurn__";

    /**
     * Validates a map of guard overrides. Fail-fast so callers get a 400
     * IllegalArgumentException instead of a 500 ConstraintViolationException at flush.
     *
     * <p>A {@code null} value for a known key is allowed (= reset to NULL → YAML default).
     *
     * @throws IllegalArgumentException if the map contains an unknown key or an
     *         out-of-range value
     */
    public static void validate(Map<String, Integer> overrides) {
        if (overrides == null || overrides.isEmpty()) return;
        for (Map.Entry<String, Integer> e : overrides.entrySet()) {
            String key = e.getKey();
            Integer v = e.getValue();
            if (!KEYS.contains(key)) {
                throw new IllegalArgumentException("Unknown guard override: " + key);
            }
            if (v == null) continue; // null = reset; always allowed
            switch (key) {
                case MAX_PER_RESOURCE_PER_TURN:
                    if (v <= 0) throw new IllegalArgumentException(key + " must be > 0");
                    break;
                case LOOP_IDENTICAL_STOP:
                    if (v < 2) throw new IllegalArgumentException("loopIdenticalStop must be >= 2");
                    break;
                case LOOP_CONSECUTIVE_STOP:
                    if (v < 4) throw new IllegalArgumentException("loopConsecutiveStop must be >= 4");
                    break;
                default:
                    // Unreachable - guarded by KEYS check above.
                    break;
            }
        }
    }

    /**
     * Resolve a per-turn guard value with precedence: caller-agent override →
     * chatConfig credential → YAML default.
     *
     * <p>Used by tool modules (AgentCrudModule / SkillCrudModule /
     * SubAgentExecutionHandler / InterfaceCrudModule / DataSourceTableModule)
     * that already loaded the caller-agent entity and want to honour
     * conversation-scope overrides for general-chat runs.
     *
     * @param agentOverride value read from caller-agent entity column ({@code null}
     *                      when no caller-agent or column is NULL)
     * @param credentials   agent credentials map (may be {@code null})
     * @param credKey       typically {@link #CRED_MAX_PER_RESOURCE_PER_TURN}
     * @param yamlDefault   platform-default fallback
     * @return resolved positive integer
     */
    public static int resolve(Integer agentOverride, Map<String, Object> credentials,
                              String credKey, int yamlDefault) {
        if (agentOverride != null && agentOverride > 0) return agentOverride;
        if (credentials != null) {
            Object raw = credentials.get(credKey);
            if (raw instanceof Number n) {
                int v = n.intValue();
                if (v > 0) return v;
            }
        }
        return yamlDefault;
    }
}
