package com.apimarketplace.agent.loop;

import com.apimarketplace.common.credit.LlmCacheTokens;

/**
 * Snapshot of execution state passed to {@link PreIterationGuard#check(IterationContext)}.
 *
 * <p>Built fresh by {@code AgentLoopService} before each iteration. Carries everything a
 * guard needs to make a proceed/deny decision without reaching back into mutable
 * loop state. Immutable record.</p>
 *
 * <p>{@code lastIterationPromptTokens} / {@code lastIterationCompletionTokens} expose the
 * <em>delta</em> consumed by the most recently completed iteration (V162). Combined
 * with the running average, they let guards project the next iteration with
 * {@code max(avg, lastDelta * safety_factor)} - closing step-function bursts that
 * pure-average projection misses.</p>
 *
 * @param tenantId                       Tenant the run belongs to. May be null for system runs.
 * @param agentId                        Agent entity ID, when available (sub-agent runs may set null).
 * @param provider                       Provider name (e.g. "openai", "anthropic").
 * @param model                          Concrete model name (e.g. "gpt-4o", "claude-opus-4-6").
 * @param upcomingIteration              1-based index of the iteration that is about to start.
 * @param iterationsCompleted            Number of iterations already completed (== upcomingIteration - 1).
 * @param promptTokensSoFar              Total prompt tokens consumed by completed iterations.
 * @param completionTokensSoFar          Total completion tokens consumed by completed iterations.
 * @param lastIterationPromptTokens      Prompt tokens consumed by the most recent iteration alone (V162).
 *                                       Zero on iteration 1.
 * @param lastIterationCompletionTokens  Completion tokens consumed by the most recent iteration
 *                                       alone (V162). Zero on iteration 1.
 * @param elapsedMs                      Wall-clock duration of the run so far, in milliseconds.
 * @param cacheTokensSoFar               Cache counters of the completed iterations, in the
 *                                       provider's own convention (never null): cache write /
 *                                       read beside the prompt (Anthropic shape), cached as a
 *                                       subset of it (everyone else). The same record the
 *                                       ledger is billed with.
 * @param lastIterationCacheTokens       Cache counters of the most recent iteration alone
 *                                       (never null; {@link #NO_CACHE} on iteration 1).
 *
 * <p>The cache counters are what lets a guard price a cached token at its cache price: a
 * prompt count alone cannot say how much of it was a cheap cache read, and a guard that
 * priced it all at the input rate projected a Claude Code turn at about five times its
 * real debit (2026-09-30).</p>
 */
public record IterationContext(
    String tenantId,
    String agentId,
    String provider,
    String model,
    int upcomingIteration,
    int iterationsCompleted,
    long promptTokensSoFar,
    long completionTokensSoFar,
    long lastIterationPromptTokens,
    long lastIterationCompletionTokens,
    long elapsedMs,
    LlmCacheTokens cacheTokensSoFar,
    LlmCacheTokens lastIterationCacheTokens
) {
    /** No cache counters at all. */
    public static final LlmCacheTokens NO_CACHE = new LlmCacheTokens(0, 0, 0, 0);

    public IterationContext {
        if (cacheTokensSoFar == null) cacheTokensSoFar = NO_CACHE;
        if (lastIterationCacheTokens == null) lastIterationCacheTokens = NO_CACHE;
    }

    /** Constructor for callers without cache counters (the guards then price the prompt total alone). */
    public IterationContext(String tenantId, String agentId, String provider, String model,
                            int upcomingIteration, int iterationsCompleted,
                            long promptTokensSoFar, long completionTokensSoFar,
                            long lastIterationPromptTokens, long lastIterationCompletionTokens,
                            long elapsedMs) {
        this(tenantId, agentId, provider, model, upcomingIteration, iterationsCompleted,
             promptTokensSoFar, completionTokensSoFar, lastIterationPromptTokens,
             lastIterationCompletionTokens, elapsedMs, NO_CACHE, NO_CACHE);
    }

    /** Backward-compat constructor - pre-V162 callers without delta tracking. */
    public IterationContext(String tenantId, String agentId, String provider, String model,
                             int upcomingIteration, int iterationsCompleted,
                             long promptTokensSoFar, long completionTokensSoFar, long elapsedMs) {
        this(tenantId, agentId, provider, model, upcomingIteration, iterationsCompleted,
             promptTokensSoFar, completionTokensSoFar, 0L, 0L, elapsedMs);
    }

    /** Sum of prompt + completion tokens consumed so far. */
    public long totalTokensSoFar() {
        return promptTokensSoFar + completionTokensSoFar;
    }

    /** Average prompt tokens per completed iteration, or 0 if none completed. */
    public long avgPromptTokensPerIteration() {
        return iterationsCompleted > 0 ? promptTokensSoFar / iterationsCompleted : 0L;
    }

    /** Average completion tokens per completed iteration, or 0 if none completed. */
    public long avgCompletionTokensPerIteration() {
        return iterationsCompleted > 0 ? completionTokensSoFar / iterationsCompleted : 0L;
    }

    /**
     * Average cache counters per completed iteration (integer division, like the prompt
     * and completion averages), or {@link #NO_CACHE} if none completed.
     */
    public LlmCacheTokens avgCacheTokensPerIteration() {
        if (iterationsCompleted <= 0) return NO_CACHE;
        return new LlmCacheTokens(
            orZero(cacheTokensSoFar.cacheCreationTokens()) / iterationsCompleted,
            orZero(cacheTokensSoFar.cacheReadTokens()) / iterationsCompleted,
            orZero(cacheTokensSoFar.cachedTokens()) / iterationsCompleted,
            orZero(cacheTokensSoFar.reasoningTokens()) / iterationsCompleted);
    }

    private static int orZero(Integer value) {
        return value != null ? value : 0;
    }
}
