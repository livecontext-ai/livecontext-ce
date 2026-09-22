package com.apimarketplace.conversation.service.ai;

import com.apimarketplace.agent.summary.ColdSummaryGate;
import com.apimarketplace.agent.summary.CompactionTrigger;

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
 * rolling the feature out region-by-region need a cheap kill switch that
 * doesn't require a code ship. The field default is {@code true}; set it to
 * {@code false} per environment to make the orchestrator a no-op on the chat
 * hot path.
 */
@Configuration
@ConfigurationProperties(prefix = "conversation.compaction")
public class CompactionDefaultsConfig {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(CompactionDefaultsConfig.class);

    /**
     * Master switch. Default {@code true} - compaction is ON by default so that
     * long conversations get summarised to stay within the model context window.
     * The orchestrator gates (HOT+WARM turn window, COLD size gate, trigger gate)
     * bound the summariser spend, and any agent / conversation / workspace-default
     * may opt OUT via its per-scope override (resolved in {@link
     * com.apimarketplace.agent.summary.CompactionConfigResolver}). Those gates are
     * the ONLY brake: no per-conversation per-day cap is implemented in this
     * codebase. Set this to {@code false} per environment to make the
     * orchestrator a no-op on the chat hot path.
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
     * Which condition fires the summariser: {@code turns} (default),
     * {@code size}, or {@code size-or-turns}. Exactly one mode is active; the
     * modes are alternative ways of answering "is it time?", never two
     * independent switches. Parsed leniently by {@link CompactionTrigger#parse}
     * so a typo degrades to the pre-feature {@code turns} behaviour instead of
     * failing the service's startup.
     *
     * <p>Held as a String rather than the enum so an unknown value cannot abort
     * context binding, which is what Spring's strict enum conversion would do.
     */
    private String trigger = CompactionTrigger.TURNS.name();

    /**
     * Growth threshold in COLD tokens for the {@code size} and
     * {@code size-or-turns} modes: the summariser fires once the COLD zone has
     * grown by this much SINCE the last summary. Zero or negative falls back to
     * {@link ColdSummaryGate#DEFAULT_SIZE_TRIGGER_COLD_TOKENS}.
     *
     * <p>Ignored entirely in {@code turns} mode.
     */
    private int sizeTriggerColdTokens = ColdSummaryGate.DEFAULT_SIZE_TRIGGER_COLD_TOKENS;

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

    public String getTrigger() { return trigger; }
    public void setTrigger(String trigger) { this.trigger = trigger; }

    /** The configured mode, parsed leniently; never null. */
    public CompactionTrigger resolvedTrigger() { return CompactionTrigger.parse(trigger); }

    /**
     * The growth threshold actually used, clamped so a mistyped value cannot
     * turn the summariser into a per-turn expense.
     *
     * <p>Non-positive falls back to the documented default, mirroring the
     * cadence. A positive value below {@link ColdSummaryGate#MIN_COLD_TOKENS_FLOOR}
     * is raised to it, and the raise is logged rather than applied silently.
     *
     * <p>The reason is the aging rate, NOT the floor. The floor is compared
     * against ABSOLUTE COLD tokens while this threshold is compared against
     * GROWTH since the last envelope, so the two never imply one another: a
     * 500k-token COLD zone that grew by 50 tokens clears the floor and misses
     * even a 500-token threshold. What makes a tiny threshold dangerous is that
     * roughly one message ages out of the HOT+WARM window per turn, so a
     * threshold below one message's token count is met on almost every turn, and
     * nothing else caps summariser spend here.
     *
     * <p>The clamp REDUCES that risk, it does not remove it: a stream of large
     * tool results can age ~2000 tokens into COLD per turn and fire at the
     * clamped value. Operators wanting a hard bound should raise the threshold,
     * not rely on this.
     */
    public int resolvedSizeTriggerColdTokens() {
        if (sizeTriggerColdTokens <= 0) {
            return ColdSummaryGate.DEFAULT_SIZE_TRIGGER_COLD_TOKENS;
        }
        return Math.max(sizeTriggerColdTokens, ColdSummaryGate.MIN_COLD_TOKENS_FLOOR);
    }

    /**
     * Log the effective trigger configuration once, so an operator can confirm
     * from the boot log that the mode they set is the mode in force. The locale
     * trap in {@link CompactionTrigger#parse} made a misconfigured mode
     * completely invisible; this is the counterpart that makes it visible.
     */
    @jakarta.annotation.PostConstruct
    void logEffectiveTrigger() {
        CompactionTrigger resolved = resolvedTrigger();
        boolean unreadable = trigger != null && !trigger.isBlank()
                && !trigger.trim().replace('-', '_').equalsIgnoreCase(resolved.name());
        if (unreadable) {
            LOG.warn("conversation.compaction.trigger=\"{}\" is not a known mode; falling back to {}."
                            + " Known modes: turns, size, size-or-turns",
                    trigger, resolved);
        }
        if (resolved != CompactionTrigger.TURNS || !CompactionTrigger.TURNS.name().equalsIgnoreCase(trigger)) {
            LOG.info("Compaction trigger: configured=\"{}\" effective={} sizeTriggerColdTokens={}{}",
                    trigger, resolved, resolvedSizeTriggerColdTokens(),
                    resolved == CompactionTrigger.SIZE
                            ? " (SIZE ignores the cadence, so a conversation of small turns may never be"
                              + " summarised; scopes that set their own cadence are resolved to"
                              + " SIZE_OR_TURNS instead, which this line cannot know)"
                            : "");
        }
        if (sizeTriggerColdTokens > 0 && sizeTriggerColdTokens < ColdSummaryGate.MIN_COLD_TOKENS_FLOOR) {
            LOG.warn("conversation.compaction.size-trigger-cold-tokens={} is below the COLD credit floor {};"
                            + " raised to the floor to stop the summariser firing on every turn",
                    sizeTriggerColdTokens, ColdSummaryGate.MIN_COLD_TOKENS_FLOOR);
        }
    }

    public int getSizeTriggerColdTokens() { return sizeTriggerColdTokens; }
    public void setSizeTriggerColdTokens(int sizeTriggerColdTokens) {
        this.sizeTriggerColdTokens = sizeTriggerColdTokens;
    }

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
