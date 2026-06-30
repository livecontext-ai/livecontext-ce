package com.apimarketplace.agent.domain;

import com.apimarketplace.agent.loop.CallPurpose;

/**
 * Stage 1b - discrete thinking-budget tiers for providers that expose a cap on
 * reasoning-token spend. A level is a coarse caller intent ("fast", "balanced",
 * "deep") that each provider maps to its own concrete budget knob; today only
 * Gemini 2.5/3.x honors this via {@code thinkingConfig.thinkingBudget}.
 *
 * <p>Why tiers instead of raw numbers: most callers (agent loops, classifiers,
 * guardrails) want to express intent ("this is fast-path - don't waste reasoning
 * tokens") without pinning a specific number that would need to move as models
 * evolve. Raw {@link CompletionRequest#thinkingBudget()} remains available for
 * callers that need exact control and takes precedence when both are set.
 *
 * <p>Budget values are per-turn caps on reasoning tokens ({@code thoughtsTokenCount}
 * in Gemini usage), billed as OUTPUT - not input. Lower tiers save output cost
 * on turns where reasoning adds no observable quality gain.
 */
public enum ThinkingLevel {
    /** Minimal reasoning (~128 tok). Fast-path tool routing, extraction, classification. */
    LOW(128),

    /** Balanced reasoning (~1024 tok). Default for conversational turns. */
    MEDIUM(1024),

    /** Deep reasoning (~8192 tok). Builder/planner turns, multi-step analysis. */
    HIGH(8192);

    private final int budgetTokens;

    ThinkingLevel(int budgetTokens) {
        this.budgetTokens = budgetTokens;
    }

    /** Provider-agnostic token cap this level maps to. */
    public int budgetTokens() {
        return budgetTokens;
    }

    /**
     * Stage 1b AUTO resolution - map ({@link CallPurpose}, turn-shape) to a
     * discrete tier without calling the LLM. Callers that want adaptive spend
     * compute this at request build time and set
     * {@link CompletionRequest#thinkingLevel()}.
     *
     * <p>Rules:
     * <ul>
     *   <li>{@code null purpose} → treated as {@link CallPurpose#MAIN}, matching
     *   {@link CallPurpose#orDefault(CallPurpose)} so a caller that omits the
     *   field still routes through the adaptive pipeline.</li>
     *   <li>{@code CLASSIFY} / {@code GUARDRAIL} → {@link #MEDIUM} - short
     *   sub-conversation; lower tiers hurt accuracy, higher tiers waste tokens.</li>
     *   <li>{@code MAIN} with {@code toolCount <= 2} AND {@code userMsgChars < 50}
     *   → {@link #LOW}. Short, simple turns ("hi", "thanks") don't need reasoning.</li>
     *   <li>{@code MAIN} otherwise → {@link #HIGH}. Anything with meaningful
     *   prompt length or tool surface gets full reasoning.</li>
     * </ul>
     *
     * <p>Thresholds (2 tools / 50 chars) are Stage 1 heuristics. Monthly
     * calibration (Stage 1a.4 piece D) will retune as production data accrues.
     *
     * @param purpose        role of this turn; {@code null} → treated as {@code MAIN}
     * @param toolCount      number of tools attached to the request
     * @param userMsgChars   character length of the current user message
     * @return the resolved tier (never {@code null})
     */
    public static ThinkingLevel auto(CallPurpose purpose, int toolCount, int userMsgChars) {
        return switch (CallPurpose.orDefault(purpose)) {
            case CLASSIFY, GUARDRAIL -> MEDIUM;
            case MAIN -> (toolCount <= 2 && userMsgChars < 50) ? LOW : HIGH;
        };
    }
}
