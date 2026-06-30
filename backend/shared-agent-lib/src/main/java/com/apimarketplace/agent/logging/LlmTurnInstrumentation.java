package com.apimarketplace.agent.logging;

import com.apimarketplace.agent.domain.CompletionRequest;
import com.apimarketplace.agent.domain.Message;
import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.domain.UsageInfo;
import com.apimarketplace.agent.loop.AgentLoopContext;
import com.apimarketplace.agent.loop.CallPurpose;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stage 0.2 instrumentation - emits one {@code [LLM_TURN]} structured log line per
 * LLM call so the Grafana dashboard and p95 alerts can be built from stdout/Loki.
 *
 * <p>Coarse-grained char counts are measured at the loop chokepoint where only the
 * assembled {@link CompletionRequest} is available: {@code systemPrompt_chars},
 * {@code history_chars}, {@code tools_chars}, {@code userPrompt_chars}. Finer
 * breakdown (skillsTree / taskSummary / workflowBuilderSession / etc.) lives at
 * the caller-side builders ({@code AgentContextBuilder}, {@code SubAgentExecutionHandler})
 * and is added in a later stage via {@link AgentLoopContext} sub-component fields.
 *
 * <p><b>Scope:</b> invoked only on the MAIN path via
 * {@link com.apimarketplace.agent.loop.AgentLoopExecutor#processIteration}; the
 * CLASSIFY/GUARDRAIL fast paths (ClassifyService, GuardrailService) are intentionally
 * out of scope for Stage 0 since this telemetry targets main-context growth. The
 * {@code purpose} field is still written to the log line so the utility remains
 * provider-agnostic for future reuse.
 */
@Slf4j
public final class LlmTurnInstrumentation {

    private static final ObjectMapper JSON = new ObjectMapper();

    private LlmTurnInstrumentation() {}

    /**
     * Emit the structured log line. Safe to call regardless of whether the caller
     * has populated every optional field on {@link AgentLoopContext} - missing
     * values are either omitted or logged as {@code null}.
     */
    public static void logTurn(
            AgentLoopContext context,
            CompletionRequest request,
            UsageInfo usage,
            int turnIndex) {
        Map<String, Object> fields = new LinkedHashMap<>();

        fields.put("turnIndex", turnIndex);
        fields.put("provider", context.provider());
        fields.put("model", request != null ? request.model() : context.model());
        fields.put("tenantId", context.tenantId());
        fields.put("agentId", context.agentId());
        fields.put("runId", context.runId());
        fields.put("purpose", CallPurpose.orDefault(context.purpose()).name());

        if (request != null) {
            // Stage 1a.1: call sites that migrated to layered systemBlocks leave
            // the legacy systemPrompt() empty. Count what actually lands on the
            // wire via effectiveSystemPrompt() so telemetry tracks the real
            // prompt size, not a stale legacy field.
            String sys = request.effectiveSystemPrompt();
            String user = request.userPrompt();
            List<Message> history = request.conversationHistory();
            List<ToolDefinition> tools = request.tools();

            fields.put("systemPrompt_chars", sys != null ? sys.length() : 0);
            fields.put("userPrompt_chars", user != null ? user.length() : 0);
            fields.put("history_chars", sumMessageChars(history));
            fields.put("history_messages", history != null ? history.size() : 0);
            fields.put("tools_chars", sumToolsChars(tools));
            fields.put("tools_count", tools != null ? tools.size() : 0);
            fields.put("stream", request.isStreaming());
        }

        if (usage != null) {
            fields.put("promptTokens", usage.promptTokens());
            fields.put("completionTokens", usage.completionTokens());
            fields.put("totalTokens", usage.totalTokens());
            // Provider-specific breakdowns - null when not applicable.
            fields.put("cacheReadInputTokens", usage.cacheReadInputTokens());
            fields.put("cacheCreationInputTokens", usage.cacheCreationInputTokens());
            fields.put("cachedTokens", usage.cachedTokens());
            fields.put("reasoningTokens", usage.reasoningTokens());
            fields.put("thoughtsTokenCount", usage.thoughtsTokenCount());
            fields.put("cachedContentTokenCount", usage.cachedContentTokenCount());
        }

        log.info("[LLM_TURN] {}", serialize(fields));
    }

    private static int sumMessageChars(List<Message> messages) {
        if (messages == null) return 0;
        int sum = 0;
        for (Message m : messages) {
            if (m != null && m.content() != null) {
                sum += m.content().length();
            }
        }
        return sum;
    }

    private static int sumToolsChars(List<ToolDefinition> tools) {
        if (tools == null) return 0;
        int sum = 0;
        for (ToolDefinition t : tools) {
            if (t == null) continue;
            if (t.name() != null) sum += t.name().length();
            if (t.description() != null) sum += t.description().length();
            if (t.parameters() != null) {
                try {
                    sum += JSON.writeValueAsString(t.parameters()).length();
                } catch (JsonProcessingException ignored) {
                    // Best-effort - parameters map serialization is rarely fatal; skip if it is.
                }
            }
        }
        return sum;
    }

    private static String serialize(Map<String, Object> fields) {
        try {
            return JSON.writeValueAsString(fields);
        } catch (JsonProcessingException e) {
            return fields.toString();
        }
    }
}
