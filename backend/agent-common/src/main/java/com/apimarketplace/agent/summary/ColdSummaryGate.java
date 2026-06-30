package com.apimarketplace.agent.summary;

/**
 * Stage 5.3 - regen gate for the COLD summarizer. Answers "should we
 * (re)generate the COLD summary right now?" without a static global
 * threshold.
 *
 * <p><b>Why model-specific.</b> The v4 plan used a flat {@code COLD >
 * 5k} gate, which never fires on Gemini because its COLD zone is
 * capped at 2.5k tokens (R49). The formula below resolves the
 * contradiction: threshold is {@code max(2k, 0.15 × model.cold_cap)}.
 * Concretely:
 * <ul>
 *   <li>Claude COLD cap 4k → threshold 2k (floor wins).</li>
 *   <li>Gemini COLD cap 2.5k → threshold 2k (floor wins - summarizer
 *       now actually fires on Gemini).</li>
 *   <li>Weak-model COLD cap 0.8k → threshold 2k still, but caller is
 *       expected to also check the cost cap in {@code
 *       ai.summarizer.max-calls-per-conversation-per-day=12} so a
 *       small-window convo doesn't thrash.</li>
 * </ul>
 *
 * <p><b>Two gates, not one.</b> Regen requires <em>both</em> conditions
 * to be met:
 * <ol>
 *   <li><b>Size gate:</b> the COLD zone currently holds enough tokens
 *       to justify the summarizer call (see above).</li>
 *   <li><b>Cadence-or-keyword gate:</b> either 5 new turns since the
 *       last summary, or an invalidation keyword match (caller computes
 *       via {@link ColdSummaryInvalidationKeywords}).</li>
 * </ol>
 * Running the summarizer on a COLD zone that doesn't clear the size
 * threshold is the #1 cause of the {@code compaction_summary_pct_of_total
 * &gt; 15%} alert in Grafana (pathological FP rate on the keyword
 * matcher).
 *
 * <p>Pure decision library. No IO, no allocation. Caller owns state.
 */
public final class ColdSummaryGate {

    /**
     * Absolute minimum COLD token count before the summarizer may
     * fire, regardless of the model's COLD cap. Keeps the summarizer
     * from burning credits on a nearly-empty zone (the summary output
     * would be larger than the input).
     */
    public static final int MIN_COLD_TOKENS_FLOOR = 2_000;

    /**
     * Fraction of the model's COLD cap that counts as "enough to
     * summarise". 15% was picked empirically against Claude Code's
     * autoCompact telemetry - below this fraction, summaries either
     * duplicate HOT/WARM content or are too short to be useful.
     */
    public static final double MIN_COLD_FRACTION_OF_CAP = 0.15;

    /**
     * Default cadence floor - at least one summary regeneration per
     * N new turns, even absent keyword triggers. Prevents very long
     * conversations from running on stale summaries that never
     * invalidate.
     */
    public static final int DEFAULT_CADENCE_TURNS = 5;

    private ColdSummaryGate() {}

    /**
     * Per-model threshold: the <em>larger</em> of
     * {@link #MIN_COLD_TOKENS_FLOOR} and
     * {@link #MIN_COLD_FRACTION_OF_CAP} × {@code modelColdCap} (R21/R49:
     * floor protects small-cap models from being summarised on tiny
     * input; fraction protects large-cap models from being
     * under-triggered).
     *
     * <p>A non-positive {@code modelColdCap} returns the floor
     * unchanged - defensive behaviour for callers that haven't
     * populated the model registry yet.
     */
    public static int thresholdFor(int modelColdCap) {
        if (modelColdCap <= 0) return MIN_COLD_TOKENS_FLOOR;
        int fractional = (int) Math.ceil(modelColdCap * MIN_COLD_FRACTION_OF_CAP);
        return Math.max(MIN_COLD_TOKENS_FLOOR, fractional);
    }

    /**
     * Size-gate check: does the current COLD zone clear the model's
     * threshold?
     */
    public static boolean passesSizeGate(int currentColdTokens, int modelColdCap) {
        return currentColdTokens > thresholdFor(modelColdCap);
    }

    /**
     * Cadence-or-keyword check. Returns {@code true} iff one of:
     * <ul>
     *   <li>{@code turnsSinceLastSummary >= cadenceTurns}</li>
     *   <li>{@code keywordTriggered} is true (caller pre-computed via
     *       {@link ColdSummaryInvalidationKeywords})</li>
     * </ul>
     * A non-positive {@code cadenceTurns} is treated as the default
     * (5) - absent config, we always honour the cadence floor.
     */
    public static boolean passesCadenceOrKeywordGate(int turnsSinceLastSummary,
                                                     int cadenceTurns,
                                                     boolean keywordTriggered) {
        if (keywordTriggered) return true;
        int effective = cadenceTurns > 0 ? cadenceTurns : DEFAULT_CADENCE_TURNS;
        return turnsSinceLastSummary >= effective;
    }

    /**
     * Combined decision: returns {@code true} iff <em>both</em> gates
     * pass. This is the final "should we fire the summarizer now?"
     * check.
     *
     * <p>The caller is still responsible for the per-day cost cap
     * ({@code ai.summarizer.max-calls-per-conversation-per-day}); a
     * {@code true} here means "size and trigger justify it", not
     * "spend credits unconditionally".
     */
    public static boolean shouldRegenerate(int currentColdTokens,
                                           int modelColdCap,
                                           int turnsSinceLastSummary,
                                           int cadenceTurns,
                                           boolean keywordTriggered) {
        return passesSizeGate(currentColdTokens, modelColdCap)
                && passesCadenceOrKeywordGate(turnsSinceLastSummary,
                        cadenceTurns, keywordTriggered);
    }
}
