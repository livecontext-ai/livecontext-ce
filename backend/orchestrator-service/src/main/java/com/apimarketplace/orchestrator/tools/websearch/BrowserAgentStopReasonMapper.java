package com.apimarketplace.orchestrator.tools.websearch;

import java.util.Locale;

/**
 * Single source of truth mapping a browser-use runner's {@code stop_reason}
 * onto the canonical {@code AgentObservabilityRequest.stopReason} vocabulary
 * agent-service expects. Used by both:
 *
 * <ul>
 *   <li>{@code BrowserAgentNode} - workflow node observability (run inside
 *       a workflow execution).</li>
 *   <li>{@code BrowserAgentModule} - chat-tool observability (run from a
 *       chat agent's {@code web_search action=agent_browse} tool call).</li>
 * </ul>
 *
 * <p>Keeping the mapping here prevents the two paths from drifting and
 * silently sending different {@code stopReason} labels for the same exit
 * condition - which would split the dashboard's per-stop-reason histograms
 * across two buckets nobody would notice.
 */
public final class BrowserAgentStopReasonMapper {

    private BrowserAgentStopReasonMapper() {
        // utility class
    }

    /**
     * Map runner stop_reason to the canonical
     * {@code AgentObservabilityRequest.stopReason} vocabulary.
     *
     * @param runnerReason the {@code stop_reason} field from the Python runner
     *                     response (may be null/blank when the run errored
     *                     before producing a result)
     * @param success      whether the runner declared success - used as the
     *                     fallback when {@code runnerReason} is missing
     */
    public static String map(String runnerReason, boolean success) {
        if (runnerReason == null || runnerReason.isBlank()) {
            return success ? "COMPLETED" : "ERROR";
        }
        return switch (runnerReason.toUpperCase(Locale.ROOT)) {
            case "COMPLETED" -> "COMPLETED";
            case "MAX_STEPS" -> "MAX_ITERATIONS";
            case "USER_TAKEOVER" -> "STOPPED_BY_USER";
            case "TIMEOUT" -> "TIMEOUT";
            case "CANCELLED" -> "CANCELLED";
            case "BUDGET_EXHAUSTED" -> "BUDGET_EXHAUSTED";
            case "LLM_FAILED", "SCHEMA_MISMATCH", "DOMAIN_BLOCKED" -> "ERROR";
            default -> "ERROR";
        };
    }
}
