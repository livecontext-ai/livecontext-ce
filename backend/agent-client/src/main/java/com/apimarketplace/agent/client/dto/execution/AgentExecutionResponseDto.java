package com.apimarketplace.agent.client.dto.execution;

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
     * BILLED identity (model execution links, cloud only). After a billed model is
     * executed through a CLI bridge under a different execution identity, the
     * response is re-stamped with the billed identity so the orchestrator's
     * observability + credit consumption (which read the result's provider/model as
     * authoritative) charge the billed price, not the bridge's. No-op when both are
     * unchanged.
     */
    public AgentExecutionResponseDto withBilledIdentity(String billedProvider, String billedModel) {
        if (java.util.Objects.equals(billedProvider, provider) && java.util.Objects.equals(billedModel, model)) {
            return this;
        }
        return new AgentExecutionResponseDto(
            success, finalResponse, content, toolResults, iterations, totalUsage, error, durationMs,
            billedProvider, billedModel, conversationHistory, stopReason, metrics, usagePerIteration,
            iterationDurations, finishReasonsPerIteration, thinkingSections, orderedEntries, budgetScope);
    }
}
