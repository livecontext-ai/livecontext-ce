package com.apimarketplace.agent.summary;

import java.util.Objects;

/**
 * Stage 5.2b - resolves which (provider, model) pair to call for the
 * COLD summariser on a given agent. Pure 3-branch decision.
 *
 * <p><b>Why a dedicated resolver.</b> The summariser runs on a model
 * the user picks per agent (V106 migration adds {@code
 * compaction_model_provider} / {@code compaction_model_name} on
 * {@code agent.agents}, both nullable). The compaction cost is
 * attributed via {@code COMPACTION_SUMMARY} source type (5.2c), so we
 * want <em>one</em> place to answer "what model is the summariser
 * calling right now?", not three copies of the same fallback ladder
 * scattered across {@code ColdSummarizerService}, an observability
 * resolver, and a cost estimator.
 *
 * <p><b>Resolution order.</b> The ladder is:
 * <ol>
 *   <li><b>Explicit override.</b> Both {@code overrideProvider} and
 *       {@code overrideName} are non-blank → use them. Either-or
 *       (provider set, name null) is treated as "not set" and falls
 *       through - partial overrides usually mean the frontend's model
 *       picker committed a broken state; we refuse to guess.</li>
 *   <li><b>Primary model fallback.</b> The agent's own
 *       {@code model_provider}/{@code model_name}. Same blank check.</li>
 *   <li><b>YAML default.</b> {@code AgentDefaultsConfig.compactionModel}
 *       - e.g. {@code anthropic/claude-haiku-4-5}. Always populated
 *       at config-bind time; a null YAML default is a config bug, and
 *       {@link #resolve(String, String, String, String, String, String)}
 *       throws so the caller fails fast at startup rather than at the
 *       first summary turn.</li>
 * </ol>
 *
 * <p>Returning a {@link ModelRef} rather than a String pair keeps the
 * call sites readable and avoids the
 * {@code Pair<String,String>}/{@code String[]} smell.
 */
public final class AgentCompactionModelResolver {

    private AgentCompactionModelResolver() {}

    /**
     * (provider, name) carrier. Both fields non-null.
     */
    public record ModelRef(String provider, String name) {
        public ModelRef {
            Objects.requireNonNull(provider, "provider must not be null");
            Objects.requireNonNull(name, "name must not be null");
        }
    }

    /**
     * Resolve the summariser model for an agent.
     *
     * @param overrideProvider        {@code agent.compaction_model_provider};
     *                                may be null / blank.
     * @param overrideName            {@code agent.compaction_model_name};
     *                                may be null / blank.
     * @param primaryProvider         {@code agent.model_provider}; may be
     *                                null / blank for legacy rows.
     * @param primaryName             {@code agent.model_name}; may be null
     *                                / blank for legacy rows.
     * @param yamlDefaultProvider     {@code AgentDefaultsConfig.compactionModel.provider};
     *                                MUST be non-blank (config bug
     *                                otherwise).
     * @param yamlDefaultName         {@code AgentDefaultsConfig.compactionModel.name};
     *                                MUST be non-blank.
     * @return the resolved {@link ModelRef}.
     * @throws IllegalStateException if all three tiers are blank. This
     *         is treated as a config error - we refuse to substitute a
     *         hard-coded fallback because it would silently route
     *         compaction spend to the wrong vendor.
     */
    public static ModelRef resolve(String overrideProvider,
                                   String overrideName,
                                   String primaryProvider,
                                   String primaryName,
                                   String yamlDefaultProvider,
                                   String yamlDefaultName) {
        if (!isBlank(overrideProvider) && !isBlank(overrideName)) {
            return new ModelRef(overrideProvider, overrideName);
        }
        if (!isBlank(primaryProvider) && !isBlank(primaryName)) {
            return new ModelRef(primaryProvider, primaryName);
        }
        if (!isBlank(yamlDefaultProvider) && !isBlank(yamlDefaultName)) {
            return new ModelRef(yamlDefaultProvider, yamlDefaultName);
        }
        throw new IllegalStateException(
                "AgentCompactionModelResolver: no provider/name available "
                        + "- override, primary and YAML default all blank. "
                        + "Check AgentDefaultsConfig.compactionModel binding.");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
