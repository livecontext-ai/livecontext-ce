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
 *   <li>Weak-model COLD cap 0.8k → threshold 2k still. Nothing else
 *       bounds how often a small-window convo may be summarised: no
 *       per-conversation per-day cap is implemented in this codebase,
 *       so the gate itself is the only brake.</li>
 * </ul>
 *
 * <p><b>Two gates, not one.</b> Regen requires <em>both</em> conditions
 * to be met:
 * <ol>
 *   <li><b>Size gate:</b> the COLD zone currently holds enough tokens
 *       to justify the summarizer call (see above).</li>
 *   <li><b>Trigger gate:</b> an invalidation keyword match (caller
 *       computes via {@link ColdSummaryInvalidationKeywords}), or the
 *       condition selected by the caller's {@link CompactionTrigger}:
 *       5 new turns since the last summary ({@code TURNS}), enough new
 *       COLD tokens since the last summary ({@code SIZE}), or whichever
 *       of the two comes first ({@code SIZE_OR_TURNS}).</li>
 * </ol>
 * The size gate is not one of those modes and is never bypassed: it is a
 * credit floor, not a policy. Callers that do not pass a mode get
 * {@code TURNS}, which is the pre-2026-09 behaviour.
 * Running the summarizer on a COLD zone that doesn't clear the size
 * threshold wastes a paid call on input too small to summarise, which is
 * why the floor is ANDed in front of every mode.
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

    /**
     * Default COLD-growth threshold, in tokens, for the size-based triggers.
     * Used when a caller leaves {@code sizeTriggerColdTokens} non-positive.
     *
     * <p>32k is deliberately far above {@link #MIN_COLD_TOKENS_FLOOR}: the
     * floor asks "is a summary worth its own tokens?", this asks "has enough
     * arrived that carrying it verbatim into every following turn now costs
     * more than one summariser call?". Sized against the 2026-09-17 production
     * case where one conversation's COLD zone reached the hundreds of thousands
     * of tokens between two cadence-driven summaries, and every turn in between
     * re-sent it in full.
     */
    public static final int DEFAULT_SIZE_TRIGGER_COLD_TOKENS = 32_000;

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
     * <p>A {@code true} here means "size and trigger justify it", not
     * "spend credits unconditionally". There is no per-day cost cap behind
     * this decision: no such property is implemented in this codebase.
     */
    public static boolean shouldRegenerate(int currentColdTokens,
                                           int modelColdCap,
                                           int turnsSinceLastSummary,
                                           int cadenceTurns,
                                           boolean keywordTriggered) {
        return shouldRegenerate(currentColdTokens, modelColdCap, turnsSinceLastSummary,
                cadenceTurns, keywordTriggered, CompactionTrigger.TURNS, 0, 0);
    }

    /**
     * Trigger-aware combined decision. Same contract as the five-argument
     * overload, with the cadence replaced by the caller's
     * {@link CompactionTrigger} mode.
     *
     * <p>The size GATE still applies in every mode: a pass below
     * {@link #thresholdFor} is refused whatever the trigger says, because the
     * floor protects credits rather than expressing a policy.
     *
     * @param newColdTokensSinceLastSummary COLD tokens appended since the stored
     *        envelope's coverage. NOT the absolute COLD size: see
     *        {@link CompactionTrigger} for why an absolute comparison would fire
     *        on every turn for ever.
     * @param sizeTriggerColdTokens growth threshold; non-positive falls back to
     *        {@link #DEFAULT_SIZE_TRIGGER_COLD_TOKENS}.
     */
    public static boolean shouldRegenerate(int currentColdTokens,
                                           int modelColdCap,
                                           int turnsSinceLastSummary,
                                           int cadenceTurns,
                                           boolean keywordTriggered,
                                           CompactionTrigger trigger,
                                           int newColdTokensSinceLastSummary,
                                           int sizeTriggerColdTokens) {
        return shouldRegenerate(currentColdTokens, modelColdCap, turnsSinceLastSummary,
                cadenceTurns, keywordTriggered, trigger, newColdTokensSinceLastSummary,
                sizeTriggerColdTokens, false);
    }

    /**
     * Full decision. {@code envelopeUntrusted} substitutes for the SIZE
     * condition only; see
     * {@link #passesTriggerGate(int, int, boolean, CompactionTrigger, int, int, boolean)}.
     */
    public static boolean shouldRegenerate(int currentColdTokens,
                                           int modelColdCap,
                                           int turnsSinceLastSummary,
                                           int cadenceTurns,
                                           boolean keywordTriggered,
                                           CompactionTrigger trigger,
                                           int newColdTokensSinceLastSummary,
                                           int sizeTriggerColdTokens,
                                           boolean envelopeUntrusted) {
        return passesSizeGate(currentColdTokens, modelColdCap)
                && passesTriggerGate(turnsSinceLastSummary, cadenceTurns, keywordTriggered,
                        trigger, newColdTokensSinceLastSummary, sizeTriggerColdTokens,
                        envelopeUntrusted);
    }

    /**
     * Growth check: has COLD grown by at least the configured number of tokens
     * since the last summary? A non-positive threshold falls back to
     * {@link #DEFAULT_SIZE_TRIGGER_COLD_TOKENS}, mirroring how a non-positive
     * cadence falls back to {@link #DEFAULT_CADENCE_TURNS}.
     */
    public static boolean passesSizeTrigger(int newColdTokensSinceLastSummary,
                                            int sizeTriggerColdTokens) {
        int effective = sizeTriggerColdTokens > 0
                ? sizeTriggerColdTokens
                : DEFAULT_SIZE_TRIGGER_COLD_TOKENS;
        return newColdTokensSinceLastSummary >= effective;
    }

    /**
     * The "is it time?" half of the decision, mode-aware. An invalidation
     * keyword wins in EVERY mode: it means the stored envelope is known to be
     * wrong, which is a correctness signal and not a pacing one.
     *
     * <p>A {@code null} mode is read as {@link CompactionTrigger#TURNS} so a
     * caller that has not been updated keeps the historical behaviour.
     */
    public static boolean passesTriggerGate(int turnsSinceLastSummary,
                                            int cadenceTurns,
                                            boolean keywordTriggered,
                                            CompactionTrigger trigger,
                                            int newColdTokensSinceLastSummary,
                                            int sizeTriggerColdTokens) {
        return passesTriggerGate(turnsSinceLastSummary, cadenceTurns, keywordTriggered,
                trigger, newColdTokensSinceLastSummary, sizeTriggerColdTokens, false);
    }

    /**
     * Trigger gate with the untrustworthy-envelope signal.
     *
     * <p>{@code envelopeUntrusted} means the stored envelope's coverage no
     * longer describes the current COLD zone, so the caller has marked it
     * stale. It satisfies the SIZE condition and NOTHING ELSE.
     *
     * <p>That narrowness is the point, and it has two halves.
     *
     * <p><b>It reaches the SIZE condition only.</b> An untrustworthy envelope
     * means the growth tally was measured against a coverage that no longer
     * applies, so the SIZE condition can refuse for ever a zone nothing will
     * ever replace. The CADENCE has not become meaningless, so it keeps
     * deciding on its own terms and {@link CompactionTrigger#TURNS} is
     * bit-for-bit unaffected by this argument. Routing the signal through
     * {@code keywordTriggered} instead returned true before either condition
     * was consulted, which silently made a shrink fire in TURNS mode where the
     * cadence had always refused it.
     *
     * <p><b>It is PACED by the cadence, it does not bypass pacing.</b> The
     * condition that makes an envelope untrustworthy persists across turns (the
     * stale status is stored on the row), so an unpaced substitution retried on
     * every assistant message until a write landed: with a failing provider
     * that is one paid call per message, and nothing else caps summariser spend
     * here. Deferring to the cadence bounds the retry wherever
     * {@code turnsSinceLastSummary} is a genuine delta since the last summary,
     * while still guaranteeing the untrustworthy envelope is replaced instead
     * of surviving for ever below the growth threshold. Note the limit: a
     * caller that forces that argument to a value which only grows (the
     * orchestrator does so on a shrunk or unusable coverage, to apply full
     * cadence pressure) keeps firing every turn, exactly as TURNS already does
     * on the same branch. The bound is on the pacing this gate can express,
     * not on what a caller chooses to report.
     */
    public static boolean passesTriggerGate(int turnsSinceLastSummary,
                                            int cadenceTurns,
                                            boolean keywordTriggered,
                                            CompactionTrigger trigger,
                                            int newColdTokensSinceLastSummary,
                                            int sizeTriggerColdTokens,
                                            boolean envelopeUntrusted) {
        if (keywordTriggered) {
            return true;
        }
        CompactionTrigger mode = trigger == null ? CompactionTrigger.TURNS : trigger;
        if (mode.usesSize()
                && (passesSizeTrigger(newColdTokensSinceLastSummary, sizeTriggerColdTokens)
                        || (envelopeUntrusted
                                && passesCadenceOrKeywordGate(turnsSinceLastSummary,
                                        cadenceTurns, false)))) {
            return true;
        }
        return mode.usesTurns()
                && passesCadenceOrKeywordGate(turnsSinceLastSummary, cadenceTurns, false);
    }
}
