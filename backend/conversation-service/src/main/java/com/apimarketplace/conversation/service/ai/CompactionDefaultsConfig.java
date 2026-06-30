package com.apimarketplace.conversation.service.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Stage 2 follow-up (#51) - config for the post-turn compaction orchestrator.
 *
 * <p>Bound from {@code conversation.compaction.*} in application.yml. Decoupled
 * from {@code AgentDefaultsConfig} (which lives in agent-service) because the
 * chat pipeline runs in conversation-service and agent-service is an HTTP hop
 * away - we don't want the chat compactor paying a round-trip just to read a
 * YAML default. The two configs may drift; operators pick one fallback per
 * service intentionally.
 *
 * <p><b>Why a dedicated enable flag.</b> Summariser spend is non-zero. Operators
 * rolling the feature out region-by-region need a cheap, fail-closed kill
 * switch that doesn't require a code ship. The default is {@code false} -
 * opt-in per environment - so a service that redeploys with this library but
 * hasn't wired its per-day cost cap yet never burns summariser tokens by
 * accident. Flip to {@code true} once the cap in
 * {@code ai.summarizer.max-calls-per-conversation-per-day} is set for the env.
 */
@Configuration
@ConfigurationProperties(prefix = "conversation.compaction")
public class CompactionDefaultsConfig {

    /**
     * Master switch. Default {@code true} - compaction is ON by default so that
     * long conversations get summarised to stay within the model context window.
     * The orchestrator gates (HOT+WARM turn window, COLD size gate, cadence) bound
     * the summariser spend, and any agent / conversation / workspace-default may
     * opt OUT via its per-scope override (resolved in {@link
     * com.apimarketplace.agent.summary.CompactionConfigResolver}). A per-day cost
     * cap can additionally be wired via the caller-owned
     * {@code ai.summarizer.max-calls-per-conversation-per-day} (see {@link
     * com.apimarketplace.agent.summary.ColdSummaryGate}). Set this to {@code false}
     * per environment to make the orchestrator a no-op on the chat hot path.
     */
    private boolean enabled = true;

    /**
     * Number of most-recent turns kept in the HOT+WARM window. Anything older
     * is considered COLD and eligible for summarisation. 20 matches the v4
     * plan's default - enough to preserve the typical Claude Code / Cursor
     * back-and-forth around a multi-step fix without starving the summariser
     * of input on long sessions. Tune per environment if short sessions
     * dominate.
     */
    private int hotWarmTurnWindow = 20;

    /**
     * Minimum new turns between summary regenerations, absent a keyword
     * invalidation. Passed through to {@link
     * com.apimarketplace.agent.summary.ColdSummaryGate#passesCadenceOrKeywordGate}.
     * Zero or negative → the gate applies its own default ({@code
     * ColdSummaryGate.DEFAULT_CADENCE_TURNS=5}).
     */
    private int cadenceTurns = 5;

    /**
     * Third-tier fallback summariser model. {@link
     * com.apimarketplace.agent.summary.AgentCompactionModelResolver} treats
     * this as the YAML default when neither an agent-level override nor a
     * primary model is available on the conversation. Defaults to
     * {@code anthropic/claude-haiku-4-5} matching the
     * {@code AgentDefaultsConfig} Haiku choice (cost-sensitive summariser).
     */
    private ModelRef compactionModel = new ModelRef();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getHotWarmTurnWindow() { return hotWarmTurnWindow; }
    public void setHotWarmTurnWindow(int hotWarmTurnWindow) { this.hotWarmTurnWindow = hotWarmTurnWindow; }

    public int getCadenceTurns() { return cadenceTurns; }
    public void setCadenceTurns(int cadenceTurns) { this.cadenceTurns = cadenceTurns; }

    public ModelRef getCompactionModel() { return compactionModel; }
    public void setCompactionModel(ModelRef compactionModel) { this.compactionModel = compactionModel; }

    /**
     * YAML-bound {@code conversation.compaction.compaction-model.{provider,name}}.
     */
    public static class ModelRef {
        private String provider = "anthropic";
        private String name = "claude-haiku-4-5";

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }
}
