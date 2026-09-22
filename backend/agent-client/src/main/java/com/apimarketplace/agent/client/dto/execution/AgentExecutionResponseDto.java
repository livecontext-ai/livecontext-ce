package com.apimarketplace.agent.client.dto.execution;

import com.apimarketplace.agent.domain.AgentStopReason;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * DTO for agent execution response from agent-service.
 * Mirrors AgentExecutionResult fields from orchestrator-service.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentExecutionResponseDto(
    boolean success,
    String finalResponse,
    String content,
    List<Map<String, Object>> toolResults,
    int iterations,
    Map<String, Object> totalUsage,
    String error,
    long durationMs,
    String provider,
    String model,
    List<Map<String, Object>> conversationHistory,
    String stopReason,
    Map<String, Object> metrics,
    List<Map<String, Object>> usagePerIteration,
    List<Long> iterationDurations,
    List<String> finishReasonsPerIteration,
    // Conversation-specific data (Phase 6 - for DB persistence parity with local mode)
    List<Map<String, Object>> thinkingSections,
    List<Map<String, Object>> orderedEntries,
    // Budget scope when stopReason=BUDGET_EXHAUSTED ("tenant", "agent", "parent_reservation")
    String budgetScope
) {
    /**
     * Return a copy with {@link #provider()} AND {@link #model()} replaced by the
     * BILLED identity (model execution links, cloud only), and the usage counts
     * re-expressed in that identity's counting convention. After a billed model is
     * executed through a CLI bridge under a different execution identity, the
     * response is re-stamped with the billed identity so the orchestrator's
     * observability + credit consumption (which read the result's provider/model as
     * authoritative) charge the billed price, not the bridge's. No-op when both are
     * unchanged.
     *
     * <p><b>The usage MUST travel with the label.</b> Billing reads
     * {@code promptTokens} through the stamped provider's convention, and the Claude
     * Code bridge folds the cache into its prompt total while the Anthropic API keeps
     * it beside. Re-stamping the label alone therefore billed the cache twice - at full
     * input rate inside the prompt total, then again on its own discounted line. That is
     * why {@code executionProvider} is a required argument rather than an overload: a
     * caller cannot re-stamp an identity without saying which one produced the numbers.
     *
     * @param executionProvider the provider that actually ran this execution and
     *                          therefore produced {@link #totalUsage()}
     */
    public AgentExecutionResponseDto withBilledIdentity(String billedProvider, String billedModel,
                                                        String executionProvider) {
        if (java.util.Objects.equals(billedProvider, provider) && java.util.Objects.equals(billedModel, model)) {
            return this;
        }
        Map<String, Object> billedUsage = com.apimarketplace.agent.domain.TokenUsageConventions
                .toBilledConvention(totalUsage, executionProvider, billedProvider);
        List<Map<String, Object>> billedPerIteration = usagePerIteration == null ? null
                : usagePerIteration.stream()
                    .map(u -> com.apimarketplace.agent.domain.TokenUsageConventions
                            .toBilledConvention(u, executionProvider, billedProvider))
                    .toList();
        return new AgentExecutionResponseDto(
            success, finalResponse, content, toolResults, iterations, billedUsage, error, durationMs,
            billedProvider, billedModel, conversationHistory, stopReason, metrics, billedPerIteration,
            iterationDurations, finishReasonsPerIteration, thinkingSections, orderedEntries, budgetScope);
    }

    /**
     * The same response with one more metric. The record has no dedicated field for a
     * per-execution fact decided after the run (whose key it ran on, for one), so such facts
     * ride on the metrics map under a stable key; this keeps the copy in one place.
     */
    public AgentExecutionResponseDto withMetric(String key, Object value) {
        Map<String, Object> merged = metrics == null ? new java.util.HashMap<>() : new java.util.HashMap<>(metrics);
        merged.put(key, value);
        return new AgentExecutionResponseDto(
            success, finalResponse, content, toolResults, iterations, totalUsage, error, durationMs,
            provider, model, conversationHistory, stopReason, merged, usagePerIteration,
            iterationDurations, finishReasonsPerIteration, thinkingSections, orderedEntries, budgetScope);
    }

    /**
     * True when this response carries no content, no tool results, and no thinking/reasoning
     * sections, i.e. nothing an end user could have seen yet. A bridge run streams to Redis as
     * it goes - INCLUDING live thinking deltas (the claude-code adapter publishes each one via
     * {@code publisher.publishThinking(...)} as they arrive, before the run's final content) -
     * independently of this response object, which only arrives once the whole dispatch
     * finishes. So {@code thinkingSections}/{@code orderedEntries} must be checked too: a run
     * that streamed only extended-thinking before crashing (empty content, empty tool results)
     * would otherwise read as "invisible" and get silently retried on top of reasoning the user
     * already saw. This is the only signal the caller has for "is a retry on this response
     * still invisible" - used by the execution-link bridge-failure fallback (see
     * {@code AgentRemoteExecutionService}, {@code SubAgentExecutionHandler}) to decide whether a
     * failed linked bridge dispatch is safe to silently retry on the billed pair's direct API.
     */
    public boolean hasNoVisibleOutput() {
        boolean noContent = isBlank(content) && isBlank(finalResponse);
        boolean noTools = toolResults == null || toolResults.isEmpty();
        boolean noThinking = (thinkingSections == null || thinkingSections.isEmpty())
            && (orderedEntries == null || orderedEntries.isEmpty());
        return noContent && noTools && noThinking;
    }

    /**
     * True when this run ended because a human asked it to stop.
     *
     * <p>A cancelled run must never be silently retried, whatever it managed to produce
     * first. "Invisible" answers "would a retry surprise the user", and for a Stop pressed
     * in the first seconds - before any content, tool result or thinking reached the
     * screen - the honest answer is yes: it would re-run the whole turn the user just
     * cancelled, on the billed pair's direct API, at full price, with the user watching a
     * stopped chat. Intent, not output, decides this one.
     */
    public boolean wasCancelledByUser() {
        // STOPPED_BY_USER only. `CANCELLED` is the SYSTEM cancelling a run (deploy,
        // scale-down, supervisor) and is exactly the case the invisible retry was built
        // for: nobody asked for the turn to end, so re-running it on the billed pair's
        // API is a rescue rather than a surprise charge.
        return AgentStopReason.STOPPED_BY_USER.name().equals(stopReason);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
