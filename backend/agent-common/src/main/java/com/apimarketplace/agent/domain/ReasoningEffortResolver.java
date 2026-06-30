package com.apimarketplace.agent.domain;

/**
 * Single source of truth for reasoning-effort precedence so every execution
 * entry path (chat, workflow, sub-agent) resolves the same way.
 *
 * <p>Precedence, most specific first:
 * <ol>
 *   <li><b>per-conversation/run override</b> - the chat model selector's choice;</li>
 *   <li><b>per-agent setting</b> - {@code AgentEntity.reasoningEffort};</li>
 *   <li><b>per-model default</b> - {@code ModelConfigOverrideEntity.defaultReasoningEffort},
 *   surfaced through the model catalog.</li>
 * </ol>
 *
 * <p>Each input is a nullable/blank raw string. The first one that parses to a
 * known {@link ReasoningEffort} wins and is returned as its canonical
 * {@link ReasoningEffort#wire()} value (lowercase). When none parse, returns
 * {@code null} - the caller then omits the field, and the bridge adapter lets
 * the CLI use its own default. Unrecognized values are skipped rather than
 * propagated, so a stale/garbage entry never reaches a CLI flag.
 */
public final class ReasoningEffortResolver {

    private ReasoningEffortResolver() {
    }

    /**
     * @param override     highest-precedence per-conversation/run value (nullable)
     * @param agentSetting per-agent value (nullable)
     * @param modelDefault per-model admin default (nullable)
     * @return canonical lowercase wire value of the winning level, or {@code null}
     */
    public static String resolve(String override, String agentSetting, String modelDefault) {
        String[] candidates = {override, agentSetting, modelDefault};
        for (String candidate : candidates) {
            ReasoningEffort level = ReasoningEffort.fromString(candidate);
            if (level != null) {
                return level.wire();
            }
        }
        return null;
    }
}
