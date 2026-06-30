package com.apimarketplace.conversation.service.ai.schema;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Stage 4a.4 - configuration for the help-seen freshness gate.
 * Consumed by {@link HelpSeenRegistry}.
 *
 * <p><b>Layout</b> (application.yml):
 * <pre>
 * conversation:
 *   jit:
 *     help-seen:
 *       hot-warm-turn-budget: 20      # how many turns since last help still counts as fresh
 *       ttl-hours: 24                 # Redis TTL for the per-conversation hash
 * </pre>
 *
 * <p><b>Why a turn budget and not a wall-clock window.</b> The conversation
 * zones (HOT/WARM/COLD in Stage 3) age on turn count, not time. Using turns
 * keeps the freshness gate in lock-step with compaction: a help response
 * naturally falls out of HOT/WARM after N turns, and this budget mirrors
 * that boundary so the LLM never thinks a help is "fresh" while its content
 * has already been summarised out of context.
 *
 * <p>{@link #ttlHours} is the Redis hash TTL - it is a storage-layer
 * concern, not a freshness criterion. Conversations idle longer than the
 * TTL lose their {@code helpSeen} state and must re-help from scratch,
 * which is the correct behaviour (the LLM's context has also been
 * reconstructed from summary by then).
 */
@Configuration
@ConfigurationProperties(prefix = "conversation.jit.help-seen")
public class HelpSeenProperties {

    /**
     * Maximum number of turns between the last help observation and the
     * current turn for which the help is still considered fresh (inclusive).
     * Default 20 mirrors the typical HOT+WARM zone size.
     */
    private int hotWarmTurnBudget = 20;

    /**
     * TTL for the per-conversation Redis hash. Conversations idle past this
     * window lose their help-seen state entirely - on next turn every call
     * is treated as a first-time call, which forces a re-help before the
     * LLM attempts the action against a potentially stale schema.
     */
    private int ttlHours = 24;

    public int getHotWarmTurnBudget() {
        return hotWarmTurnBudget;
    }

    public void setHotWarmTurnBudget(int hotWarmTurnBudget) {
        // Negative budgets would mark every entry stale immediately; clamp to 0.
        this.hotWarmTurnBudget = Math.max(0, hotWarmTurnBudget);
    }

    public int getTtlHours() {
        return ttlHours;
    }

    public void setTtlHours(int ttlHours) {
        // A zero TTL would immediately evict - clamp to at least 1 hour.
        this.ttlHours = Math.max(1, ttlHours);
    }

    public Duration getTtl() {
        return Duration.ofHours(ttlHours);
    }
}
