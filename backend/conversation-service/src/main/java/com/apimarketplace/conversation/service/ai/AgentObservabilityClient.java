package com.apimarketplace.conversation.service.ai;

import com.apimarketplace.agent.client.dto.execution.AgentExecutionResponseDto;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.common.web.TenantResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Client for recording agent observability data in the agent-service.
 * Sends execution metrics asynchronously after chat agent completion.
 *
 * Accepts AgentExecutionResponseDto directly (no shared-agent-lib dependency).
 *
 * If the observability HTTP call fails, falls back to CreditConsumptionClient
 * (which has built-in retry + dead-letter) so that credit consumption is never lost.
 */
@Slf4j
@Service
public class AgentObservabilityClient {

    private final RestTemplate restTemplate;
    private final CreditConsumptionClient creditClient;

    @Value("${orchestrator.service.url:http://localhost:8099}")
    private String orchestratorUrl;

    public AgentObservabilityClient(RestTemplate restTemplate, CreditConsumptionClient creditClient) {
        this.restTemplate = restTemplate;
        this.creditClient = creditClient;
    }

    /**
     * Record a chat agent execution asynchronously from remote execution response.
     *
     * @param tenantId       the tenant ID
     * @param orgId          PR20 - workspace identity captured at the synchronous call
     *                       boundary (ChatRequest.getOrgId()). MUST be passed explicitly
     *                       here because this method is {@code @Async}: the worker thread
     *                       has no inbound RequestContextHolder, so the reflection-based
     *                       header forwarder cannot recover X-Organization-ID after the
     *                       async boundary. NULL = personal workspace.
     * @param agentId        the agent entity ID (nullable for direct chat without agent)
     * @param response       the agent execution response DTO
     * @param systemPrompt   the resolved system prompt
     * @param userPrompt     the user prompt for this turn
     * @param conversationId the conversation ID
     * @param agentConfig    the agent config (nullable)
     */
    @Async
    public void recordAsync(String tenantId, String orgId, String agentId, AgentExecutionResponseDto response,
                            String systemPrompt, String userPrompt, String conversationId,
                            AgentConfigProvider.AgentConfig agentConfig, String source,
                            String taskId, String executionId) {
        if (response == null || tenantId == null || tenantId.isBlank()) {
            log.warn("Skipping observability record: tenantId={}, response={}",
                tenantId, response != null ? "present" : "null");
            return;
        }

        Map<String, Object> body = null;
        try {
            body = buildRequestBody(agentId, response, systemPrompt, userPrompt, conversationId, agentConfig);
            if (source != null && !source.isBlank()) {
                body.put("source", source);
            }
            if (taskId != null && !taskId.isBlank()) {
                body.put("taskId", taskId);
            }
            // Pass the dispatcher-minted executionId so agent-service persists
            // agent_executions.id = executionId (the same UUID MCP-side claimTask
            // wrote into the claim log). Closes the task↔execution linkage race.
            if (executionId != null && !executionId.isBlank()) {
                body.put("executionId", executionId);
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-User-ID", tenantId);
            // PR20 - propagate the workspace identity onto the outbound observability
            // hop. Without this the orchestrator proxy + agent-service receiver both
            // resolve X-Organization-ID to null and the persisted row goes into
            // personal scope. Combined with the strict-isolation read path, that
            // means a team-workspace user sees zero chat-agent history - exactly
            // the bug class PR20 is meant to close.
            if (orgId != null && !orgId.isBlank()) {
                headers.set("X-Organization-ID", orgId);
            }

            String url = orchestratorUrl + "/api/internal/agent-observability/record";
            restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);

            log.info("Recorded chat observability: agentId={}, orgId={}, conversationId={}",
                    agentId, orgId, conversationId);
        } catch (Exception e) {
            log.error("Failed to record chat observability for agentId={}, falling back to direct credit consumption: {}",
                    agentId, e.getMessage());

            // FALLBACK: Even if observability recording fails, credits MUST be consumed.
            // Delegate to CreditConsumptionClient which has built-in retry + dead-letter.
            try {
                int promptTok = body != null ? toInt(body.get("totalPromptTokens")) : 0;
                int completionTok = body != null ? toInt(body.get("totalCompletionTokens")) : 0;
                // Preserve the caller-supplied source (e.g. WEBHOOK) on the fallback path -
                // hard-coding CHAT_CONVERSATION here would lose the distinction in the ledger.
                // Cache counters are intentionally NOT forwarded here: this degraded path
                // bills at the legacy full input rate, which errs on the expensive side -
                // the primary path (agent-service recordFromChat) is the cache-aware one.
                String fallbackSource = (source != null && !source.isBlank()) ? source : "CHAT_CONVERSATION";
                Runnable fallbackDebit = () -> creditClient.consumeCreditsAsync(
                    tenantId,
                    fallbackSource,
                    conversationId != null ? conversationId : (agentId != null ? agentId : "unknown"),
                    response.provider(),
                    response.model(),
                    promptTok,
                    completionTok
                );
                if (orgId != null && !orgId.isBlank()) {
                    TenantResolver.runWithOrgScope(orgId, fallbackDebit);
                } else {
                    fallbackDebit.run();
                }
                log.info("Fallback credit consumption queued for chat: tenantId={}, conversationId={}",
                        tenantId, conversationId);
            } catch (Exception fallbackError) {
                log.error("Fallback credit consumption also failed for tenant {}: {}",
                        tenantId, fallbackError.getMessage());
            }
        }
    }

    private int toInt(Object val) {
        if (val instanceof Number n) return n.intValue();
        return 0;
    }

    /**
     * Record a FAILED agent execution that never reached the agent loop. Used by
     * the sync chat path's silent-failure branches (402 insufficient credits,
     * response==null from bridge/queue, exceptions thrown mid-dispatch) so the
     * attempt shows up in Agent Performance / Agent Fleet with the correct
     * stop reason - instead of leaving the dashboard empty.
     *
     * <p>The body shape matches what {@code AgentObservabilityService.recordFromChat}
     * already accepts (same endpoint), with all counters set to 0 because no LLM
     * call was made. No credit consumption attempt - the caller already either
     * checked credits (and the user has none) or hit a transport error before
     * any tokens were generated.
     *
     * @param agentId       must be non-null - the dashboard rows are keyed on agentEntityId
     * @param source        SCHEDULE / WEBHOOK / TASK / TASK_REVIEW / CHAT / WIDGET - preserved
     * @param stopReason    BUDGET_EXHAUSTED for 402, otherwise FAILED
     * @param errorMessage  surfaced in the dashboard's error column
     * @param provider      LLM provider slug (claude-code, openai, anthropic, deepseek…) -
     *                      persisted on agent_executions.provider so the Fleet model chip
     *                      can include the row in its per-model aggregate. Without this,
     *                      AgentMetricsQueryService.getModelStatsByAgent's `WHERE ae.model
     *                      IS NOT NULL` filter silently excludes the row and the BUDGET_EXHAUSTED
     *                      chip stays at 0 even after a throttled fire.
     * @param model         LLM model slug (claude-opus-4-7, gpt-4o…) - see provider note above.
     */
    @Async
    public void recordFailureAsync(String tenantId, String orgId, String agentId,
                                    String source, String conversationId,
                                    String stopReason, String errorMessage,
                                    String userPrompt, String assistantContent,
                                    String provider, String model) {
        if (tenantId == null || tenantId.isBlank() || agentId == null || agentId.isBlank()) {
            log.debug("Skipping failure-only observability record: tenantId={}, agentId={}", tenantId, agentId);
            return;
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("agentEntityId", agentId);
            body.put("success", false);
            body.put("stopReason", stopReason != null ? stopReason : "FAILED");
            body.put("errorMessage", errorMessage);
            body.put("durationMs", 0L);
            body.put("iterationCount", 0);
            body.put("conversationId", conversationId);
            if (source != null && !source.isBlank()) {
                body.put("source", source);
            }
            // Persist provider+model so the Fleet model chip's per-model SQL aggregate
            // includes the throttled-fire row. The "IS NOT NULL" filter on model in
            // getModelStatsByAgent silently drops rows without a model - invisible bug.
            if (provider != null && !provider.isBlank()) {
                body.put("provider", provider);
            }
            if (model != null && !model.isBlank()) {
                body.put("model", model);
            }
            body.put("totalPromptTokens", 0);
            body.put("totalCompletionTokens", 0);
            body.put("totalTokens", 0);
            body.put("totalToolCalls", 0);
            body.put("successfulToolCalls", 0);
            body.put("failedToolCalls", 0);
            body.put("loopDetected", false);
            // userPrompt + assistantContent flow into agent_execution_messages via the
            // adapter's convertMessages path - without these, the Agent Performance
            // execution detail view shows an empty conversation panel for throttled
            // fires. The user sees [Error] Insufficient credits in conversation.messages
            // but the dashboard couldn't surface what the schedule actually asked for.
            boolean hasUserPrompt = userPrompt != null && !userPrompt.isBlank();
            boolean hasAssistantContent = assistantContent != null && !assistantContent.isBlank();
            if (hasUserPrompt) {
                body.put("userPrompt", userPrompt);
            }
            int messageCount = 0;
            if (hasUserPrompt || hasAssistantContent) {
                List<Map<String, Object>> history = new ArrayList<>(2);
                if (hasUserPrompt) {
                    Map<String, Object> userMsg = new LinkedHashMap<>();
                    userMsg.put("role", "USER");
                    userMsg.put("content", userPrompt);
                    history.add(userMsg);
                }
                if (hasAssistantContent) {
                    Map<String, Object> assistantMsg = new LinkedHashMap<>();
                    assistantMsg.put("role", "ASSISTANT");
                    assistantMsg.put("content", assistantContent);
                    history.add(assistantMsg);
                }
                body.put("conversationHistory", history);
                messageCount = history.size();
            }
            // Single write - the count is the final value regardless of whether the
            // conditional block above contributed. Earlier draft set 0 unconditionally
            // then overwrote in the branch; a refactor reordering the blocks would
            // have silently dropped the override.
            body.put("messageCount", messageCount);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-User-ID", tenantId);
            if (orgId != null && !orgId.isBlank()) {
                headers.set("X-Organization-ID", orgId);
            }

            String url = orchestratorUrl + "/api/internal/agent-observability/record";
            restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);

            log.info("Recorded failure observability: agentId={}, source={}, provider={}, model={}, stopReason={}, error={}",
                    agentId, source, provider, model, stopReason, errorMessage);
        } catch (Exception e) {
            log.warn("Failed to record failure observability (best-effort): agentId={}, error={}",
                    agentId, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> buildRequestBody(String agentId, AgentExecutionResponseDto response,
                                                   String systemPrompt, String userPrompt,
                                                   String conversationId,
                                                   AgentConfigProvider.AgentConfig agentConfig) {
        Map<String, Object> body = new LinkedHashMap<>();

        body.put("agentEntityId", agentId);
        body.put("provider", response.provider());
        body.put("model", response.model());
        body.put("success", response.success());
        body.put("stopReason", response.stopReason());
        // Surface budgetScope (tenant|agent) when AgentLoopService recorded a budget denial.
        if (response.metrics() != null && response.metrics().get("budgetScope") instanceof String s && !s.isBlank()) {
            body.put("budgetScope", s);
        }
        body.put("errorMessage", response.error());
        body.put("durationMs", response.durationMs());
        body.put("iterationCount", response.iterations());
        body.put("conversationId", conversationId);
        body.put("systemPrompt", systemPrompt);
        body.put("userPrompt", userPrompt);

        // Agent config
        if (agentConfig != null) {
            body.put("temperature", agentConfig.temperature());
            body.put("maxTokens", agentConfig.maxTokens());
            body.put("maxIterations", agentConfig.maxIterations());
        }

        // Token usage
        Map<String, Object> usage = response.totalUsage();
        if (usage != null) {
            int promptTok = getIntOrZero(usage, "promptTokens");
            int completionTok = getIntOrZero(usage, "completionTokens");
            int totalTok = getIntOrZero(usage, "totalTokens");
            if (totalTok == 0 && (promptTok > 0 || completionTok > 0)) {
                totalTok = promptTok + completionTok;
            }
            body.put("totalPromptTokens", promptTok);
            body.put("totalCompletionTokens", completionTok);
            body.put("totalTokens", totalTok);
            if (usage.get("cacheCreationInputTokens") != null) body.put("totalCacheCreationTokens", ((Number) usage.get("cacheCreationInputTokens")).intValue());
            if (usage.get("cacheReadInputTokens") != null) body.put("totalCacheReadTokens", ((Number) usage.get("cacheReadInputTokens")).intValue());
            if (usage.get("cachedTokens") != null) body.put("totalCachedTokens", ((Number) usage.get("cachedTokens")).intValue());
            if (usage.get("reasoningTokens") != null) body.put("totalReasoningTokens", ((Number) usage.get("reasoningTokens")).intValue());
        } else {
            body.put("totalPromptTokens", 0);
            body.put("totalCompletionTokens", 0);
            body.put("totalTokens", 0);
        }

        // Tool stats
        List<Map<String, Object>> toolResults = response.toolResults();
        int totalToolCalls = toolResults != null ? toolResults.size() : 0;
        int successfulToolCalls = 0;
        int failedToolCalls = 0;
        List<String> toolNames = new ArrayList<>();

        if (toolResults != null) {
            for (Map<String, Object> tr : toolResults) {
                if (Boolean.TRUE.equals(tr.get("success"))) successfulToolCalls++;
                else failedToolCalls++;
                Map<String, Object> toolCallMap = (Map<String, Object>) tr.get("toolCall");
                String name = toolCallMap != null ? (String) toolCallMap.get("toolName") : "unknown";
                toolNames.add(name != null ? name : "unknown");
            }
        }

        body.put("totalToolCalls", totalToolCalls);
        body.put("successfulToolCalls", successfulToolCalls);
        body.put("failedToolCalls", failedToolCalls);
        body.put("messageCount", response.conversationHistory() != null ? response.conversationHistory().size() : 0);

        if (!toolNames.isEmpty()) {
            body.put("toolSequence", String.join(",", toolNames));
            body.put("distinctTools", new ArrayList<>(new LinkedHashSet<>(toolNames)));
        }

        // Loop detection from metrics
        Map<String, Object> metrics = response.metrics();
        if (metrics != null && Boolean.TRUE.equals(metrics.get("loopDetected"))) {
            body.put("loopDetected", true);
            body.put("loopType", metrics.get("loopType") != null ? metrics.get("loopType").toString() : null);
            body.put("loopToolName", metrics.get("loopToolName") != null ? metrics.get("loopToolName").toString() : null);
        } else {
            body.put("loopDetected", false);
        }

        // Full detail: tool results (flatten nested toolCall structure for ChatAgentObservabilityRequest.ToolResultDto)
        if (toolResults != null && !toolResults.isEmpty()) {
            List<Map<String, Object>> flattenedResults = new ArrayList<>();
            for (Map<String, Object> tr : toolResults) {
                Map<String, Object> flat = new LinkedHashMap<>();
                Map<String, Object> toolCallMap = (Map<String, Object>) tr.get("toolCall");
                if (toolCallMap != null) {
                    flat.put("toolCallId", toolCallMap.get("id"));
                    flat.put("toolName", toolCallMap.get("toolName"));
                    flat.put("arguments", toolCallMap.get("arguments"));
                } else {
                    flat.put("toolCallId", null);
                    flat.put("toolName", "unknown");
                    flat.put("arguments", null);
                }
                flat.put("success", tr.get("success"));
                flat.put("content", tr.get("content"));
                flat.put("error", tr.get("error"));
                flat.put("durationMs", tr.get("durationMs"));
                flat.put("metadata", tr.get("metadata"));
                flattenedResults.add(flat);
            }
            body.put("toolResults", flattenedResults);
        }

        // Full detail: conversation history
        List<Map<String, Object>> history = response.conversationHistory();
        if (history != null && !history.isEmpty()) {
            body.put("conversationHistory", history);
        }

        // Full detail: per-iteration usage
        List<Map<String, Object>> usagePerIteration = response.usagePerIteration();
        if (usagePerIteration != null && !usagePerIteration.isEmpty()) {
            body.put("usagePerIteration", usagePerIteration);
        }

        // Full detail: per-iteration durations and finish reasons
        if (response.iterationDurations() != null && !response.iterationDurations().isEmpty()) {
            body.put("iterationDurations", response.iterationDurations());
        }
        if (response.finishReasonsPerIteration() != null && !response.finishReasonsPerIteration().isEmpty()) {
            body.put("finishReasonsPerIteration", response.finishReasonsPerIteration());
        }

        // Per-iteration tool call counts (for tool→iteration mapping)
        if (metrics != null) {
            Object toolCallsPerIter = metrics.get("toolCallsPerIteration");
            if (toolCallsPerIter instanceof List<?> list && !list.isEmpty()) {
                body.put("toolCallsPerIteration", list);
            }
        }

        return body;
    }

    private int getIntOrZero(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? ((Number) val).intValue() : 0;
    }
}
