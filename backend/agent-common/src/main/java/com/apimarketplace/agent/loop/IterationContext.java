package com.apimarketplace.agent.loop;

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
    long elapsedMs
) {
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
}
