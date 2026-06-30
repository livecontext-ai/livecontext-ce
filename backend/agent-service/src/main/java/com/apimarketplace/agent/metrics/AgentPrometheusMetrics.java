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

    private static String safeTag(String v) {
        if (v == null || v.isBlank()) return "unknown";
        if (v.length() > 48) return v.substring(0, 48);
        return v.toLowerCase();
    }
}
