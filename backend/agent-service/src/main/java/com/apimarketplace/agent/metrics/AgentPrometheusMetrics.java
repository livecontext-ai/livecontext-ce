package com.apimarketplace.agent.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.stereotype.Component;

/**
 * Agent and LLM usage metrics exposed via Prometheus/Micrometer.
 *
 * Cardinality policy:
 * - No PII tags (no tenantId, no userId).
 * - Bounded tags: provider (openai/anthropic/google/...), model, agent_type, result.
 * - Provider/model cardinality is naturally bounded (~20 combinations).
 */
@Component
public class AgentPrometheusMetrics {

    public static final String EXECUTIONS_TOTAL = "agent_executions_total";
    public static final String EXECUTION_DURATION_MS = "agent_execution_duration_ms";
    public static final String TOKENS_TOTAL = "agent_tokens_total";
    public static final String TOOL_CALLS_TOTAL = "agent_tool_calls_total";
    public static final String ITERATIONS_TOTAL = "agent_iterations_total";
    public static final String CREDITS_CONSUMED = "agent_credits_consumed";
    public static final String LOOP_DETECTED_TOTAL = "agent_loop_detected_total";
    public static final String EXECUTION_LINK_FALLBACK_TOTAL = "agent_execution_link_fallback_total";
    public static final String UNREPORTED_USAGE_TOTAL = "agent_unreported_usage_total";

    private final MeterRegistry registry;

    public AgentPrometheusMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Record a completed agent execution (workflow, chat, classify, guardrail).
     */
    public void recordExecution(String provider, String model, String agentType,
                                 boolean success, long durationMs,
                                 long promptTokens, long completionTokens,
                                 int toolCalls, int iterations, boolean loopDetected) {
        String safeProvider = safeTag(provider);
        String safeModel = safeTag(model);
        String safeType = safeTag(agentType);
        String result = success ? "success" : "failure";

        // Execution count
        Counter.builder(EXECUTIONS_TOTAL)
                .tags(Tags.of("provider", safeProvider, "model", safeModel,
                        "agent_type", safeType, "result", result))
                .description("Total agent executions")
                .register(registry)
                .increment();

        // Duration
        DistributionSummary.builder(EXECUTION_DURATION_MS)
                .tags(Tags.of("provider", safeProvider, "model", safeModel, "agent_type", safeType))
                .description("Agent execution duration in milliseconds")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry)
                .record(durationMs);

        // Tokens - separate counters for prompt vs completion
        if (promptTokens > 0) {
            Counter.builder(TOKENS_TOTAL)
                    .tags(Tags.of("provider", safeProvider, "model", safeModel, "type", "prompt"))
                    .description("Total LLM tokens consumed")
                    .register(registry)
                    .increment(promptTokens);
        }
        if (completionTokens > 0) {
            Counter.builder(TOKENS_TOTAL)
                    .tags(Tags.of("provider", safeProvider, "model", safeModel, "type", "completion"))
                    .description("Total LLM tokens consumed")
                    .register(registry)
                    .increment(completionTokens);
        }

        // Tool calls
        if (toolCalls > 0) {
            Counter.builder(TOOL_CALLS_TOTAL)
                    .tags(Tags.of("agent_type", safeType))
                    .description("Total tool calls made by agents")
                    .register(registry)
                    .increment(toolCalls);
        }

        // Iterations
        if (iterations > 0) {
            Counter.builder(ITERATIONS_TOTAL)
                    .tags(Tags.of("agent_type", safeType))
                    .description("Total agent loop iterations")
                    .register(registry)
                    .increment(iterations);
        }

        // Loop detection
        if (loopDetected) {
            Counter.builder(LOOP_DETECTED_TOTAL)
                    .tags(Tags.of("agent_type", safeType))
                    .description("Agent executions where loop was detected")
                    .register(registry)
                    .increment();
        }
    }

    /**
     * Record credit consumption from an agent execution.
     *
     * <p>{@code sourceType} tags the metric with the credit-ledger category
     * ({@code AGENT_EXECUTION}, {@code CLASSIFY_EXECUTION}, {@code
     * GUARDRAIL_EXECUTION}, {@code COMPACTION_SUMMARY}, …). Grafana panel #10
     * (agent-llm-usage dashboard) uses it to segregate compaction-summary
     * spend from primary agent spend. Pass {@code null} when the call site
     * has no source-type context - it is tagged as {@code unknown} so queries
     * don't silently drop the sample.
     */
    public void recordCreditsConsumed(String provider, String model, String sourceType, double credits) {
        if (credits <= 0) return;
        Counter.builder(CREDITS_CONSUMED)
                .tags(Tags.of(
                        "provider",    safeTag(provider),
                        "model",       safeTag(model),
                        "source_type", safeTag(sourceType)))
                .description("Credits consumed by agent executions (tagged by credit-ledger source type)")
                .register(registry)
                .increment(credits);
    }

    /**
     * Record an execution-link bridge dispatch that failed BEFORE producing any visible
     * output (no content, no tool results) and was silently retried on the billed pair's
     * direct API instead of surfacing the failure. The fallback is invisible to the end
     * user by design, so this counter is the only signal that a CLI bridge is unhealthy -
     * without it, every fallback pays the direct API's full price with nobody alerted.
     */
    public void recordExecutionLinkFallback(String billedProvider, String billedModel, String bridgeProvider) {
        Counter.builder(EXECUTION_LINK_FALLBACK_TOTAL)
                .tags(Tags.of(
                        "billed_provider",  safeTag(billedProvider),
                        "billed_model",     safeTag(billedModel),
                        "bridge_provider",  safeTag(bridgeProvider)))
                .description("Execution-link bridge dispatches that failed pre-output and fell back to the billed pair's direct API")
                .register(registry)
                .increment();
    }

    /**
     * Record an execution that demonstrably did work - it made at least one tool call -
     * and yet reported no token usage at all, so nothing could be billed for it.
     *
     * The known producer is a CLI killed mid-turn: a provider that reports usage only when
     * a turn completes hands back zeros when it is stopped. Those runs used to be
     * indistinguishable from runs where no model call ever happened, and were written off
     * in silence. This counter is what makes the write-off countable: a rise on a
     * (provider, model) pair means that provider is costing money nobody is charged for.
     *
     * "Did work" is read from the row's tool calls, never from its iteration count: the
     * loop counts an iteration before calling the provider, so an iteration proves an
     * attempt, not an answer, and a provider outage would spike this counter on runs that
     * cost nothing.
     */
    public void recordUnreportedUsage(String provider, String model, String stopReason) {
        Counter.builder(UNREPORTED_USAGE_TOTAL)
                .tags(Tags.of(
                        "provider",    safeTag(provider),
                        "model",       safeTag(model),
                        "stop_reason", safeTag(stopReason)))
                .description("Agent executions that did work but reported no token usage, so nothing was billed")
                .register(registry)
                .increment();
    }

    private static String safeTag(String v) {
        if (v == null || v.isBlank()) return "unknown";
        if (v.length() > 48) return v.substring(0, 48);
        return v.toLowerCase();
    }
}
