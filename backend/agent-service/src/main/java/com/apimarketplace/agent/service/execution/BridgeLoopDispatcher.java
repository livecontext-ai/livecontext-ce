package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.bridge.BridgeAccessGuard;
import com.apimarketplace.agent.client.dto.execution.AgentExecutionRequestDto;
import com.apimarketplace.agent.client.dto.execution.AgentExecutionResponseDto;
import com.apimarketplace.agent.domain.AgentStopReason;
import com.apimarketplace.agent.domain.CompletionResponse;
import com.apimarketplace.agent.domain.Message;
import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.domain.UsageInfo;
import com.apimarketplace.agent.loop.AgentLoopContext;
import com.apimarketplace.agent.loop.AgentLoopResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dispatches bridge calls for loop-shaped callers (ClassifyService, GuardrailService,
 * AgentRemoteExecutionService) so CLI-based providers (claude-code, codex, gemini-cli,
 * mistral-vibe) don't crash on {@code BridgeProviderStub.complete()}.
 *
 * <p>Same contract as {@link com.apimarketplace.agent.loop.AgentLoopService#execute}:
 * takes an {@link AgentLoopContext}, returns an {@link AgentLoopResult}. Callers
 * route via:
 * <pre>
 *   AgentLoopResult result = isBridgeProvider(provider) &amp;&amp; isAvailable()
 *       ? bridgeDispatcher.execute(context)
 *       : agentLoopService.execute(context, callback);
 * </pre>
 *
 * <p>Budget enforcement for the bridge path is done by the bridge server itself
 * (via {@code tenantBalance}/{@code maxCreditBudget} in the request DTO). The local
 * {@code preIterationGuard} on the context is not consulted here - matching how
 * {@link SubAgentExecutionHandler}'s bridge path works.
 */
@Slf4j
@Component
public class BridgeLoopDispatcher {

    @Autowired(required = false)
    private SubAgentBridgeClient bridgeClient;

    /**
     * CLI-bridge access guard. Required on the bridge path because this dispatcher
     * bypasses {@link com.apimarketplace.agent.factory.LLMProviderFactory#getProviderForUser}
     * - that's the guard call site on the non-bridge path, so bridges would otherwise
     * escape the gate entirely. Absent in cloud agent-service (no bridges installed).
     */
    @Autowired(required = false)
    private BridgeAccessGuard bridgeAccessGuard;

    /** Whether the bridge client is wired (i.e. {@code conversation.bridge.enabled=true}). */
    public boolean isAvailable() {
        return bridgeClient != null;
    }

    /**
     * @return true when the provider should route to the bridge AND the client is available.
     */
    public boolean shouldDispatch(String provider) {
        return bridgeClient != null && SubAgentBridgeClient.isBridgeProvider(provider);
    }

    /**
     * Dispatch a pre-built {@link AgentExecutionRequestDto} to the bridge. Used by
     * {@link AgentRemoteExecutionService} which already has the request DTO from the
     * HTTP caller - no need to rebuild it from an {@link AgentLoopContext}.
     *
     * <p>Returns {@code null} when the bridge client is unavailable or the bridge
     * returned no response (logged by the client). Callers must handle null.
     *
     * @param userRoles comma-separated role string forwarded from the inbound
     *                  {@code X-User-Roles} header (e.g. {@code "ADMIN,USER"}).
     *                  Required for {@code admin_only} policy evaluation -
     *                  passing {@code null} would degrade every admin caller to
     *                  the default {@code USER} role and deny the dispatch.
     */
    public AgentExecutionResponseDto dispatchRaw(AgentExecutionRequestDto request, String userRoles) {
        if (bridgeClient == null) {
            log.warn("[BRIDGE_DISPATCH] dispatchRaw called but bridge client not available");
            return null;
        }
        // Gate the bridge subscription before dispatching - the non-bridge path gates
        // inside LLMProviderFactory, but dispatchRaw bypasses the factory so we must
        // call the guard here. Throws BridgeAccessDeniedException → GlobalExceptionHandler
        // maps to 403/429 with the typed reason.
        if (bridgeAccessGuard != null) {
            bridgeAccessGuard.enforce(request.tenantId(), userRoles, request.provider(), true);
        }
        return bridgeClient.execute(request);
    }

    /**
     * Execute via the bridge and return a {@link AgentLoopResult} compatible with the
     * direct {@code AgentLoopService} path. Caller is responsible for routing.
     */
    public AgentLoopResult execute(AgentLoopContext context) {
        if (bridgeClient == null) {
            return AgentLoopResult.builder()
                .success(false)
                .error("Bridge client not available - set conversation.bridge.enabled=true")
                .provider(context.provider())
                .model(context.model())
                .stopReason(AgentStopReason.ERROR)
                .toolResults(Collections.emptyList())
                .conversationHistory(Collections.emptyList())
                .usagePerIteration(Collections.emptyList())
                .iterationDurations(Collections.emptyList())
                .finishReasonsPerIteration(Collections.emptyList())
                .build();
        }

        long start = System.currentTimeMillis();
        AgentExecutionRequestDto dto = buildRequest(context);

        log.info("[BRIDGE_DISPATCH] purpose={}, provider={}, model={}, tenantId={}, agentId={}",
            context.getPurposeOrDefault(), context.provider(), context.model(),
            context.tenantId(), context.agentId());

        // Gate the bridge subscription before dispatching (classify/guardrail path).
        // Bypasses LLMProviderFactory so we must call the guard here - same defense
        // as dispatchRaw. Throws BridgeAccessDeniedException → GlobalExceptionHandler
        // maps reason → 403/429.
        if (bridgeAccessGuard != null) {
            bridgeAccessGuard.enforce(context.tenantId(), context.userRoles(), context.provider(), true);
        }

        AgentExecutionResponseDto response = bridgeClient.execute(dto);

        if (response == null) {
            long duration = System.currentTimeMillis() - start;
            log.error("[BRIDGE_DISPATCH] Bridge returned null response (provider={}, model={})",
                context.provider(), context.model());
            return AgentLoopResult.builder()
                .success(false)
                .error("Bridge execution failed: no response from bridge server")
                .provider(context.provider())
                .model(context.model())
                .durationMs(duration)
                .stopReason(AgentStopReason.ERROR)
                .toolResults(Collections.emptyList())
                .conversationHistory(Collections.emptyList())
                .usagePerIteration(Collections.emptyList())
                .iterationDurations(Collections.emptyList())
                .finishReasonsPerIteration(Collections.emptyList())
                .build();
        }

        return convertResponse(response, context.provider(), context.model());
    }

    /**
     * Build a minimal {@link AgentExecutionRequestDto} from an {@link AgentLoopContext}.
     * Streaming/conversation fields are left null - classify/guardrail are single-shot,
     * no streaming; {@code AgentRemoteExecutionService} has its own richer request DTO
     * and calls the bridge client directly without going through this dispatcher.
     */
    private AgentExecutionRequestDto buildRequest(AgentLoopContext context) {
        List<Map<String, Object>> toolMaps = null;
        if (context.tools() != null && !context.tools().isEmpty()) {
            toolMaps = context.tools().stream()
                .map(this::toolDefToMap)
                .toList();
        }

        List<Map<String, Object>> historyMaps = null;
        if (context.conversationHistory() != null && !context.conversationHistory().isEmpty()) {
            historyMaps = context.conversationHistory().stream()
                .map(msg -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("role", msg.role() != null ? msg.role().name() : "USER");
                    map.put("content", msg.content());
                    if (msg.toolCallId() != null) map.put("toolCallId", msg.toolCallId());
                    if (msg.toolName() != null) map.put("toolName", msg.toolName());
                    return map;
                })
                .toList();
        }

        return new AgentExecutionRequestDto(
            context.userPrompt(),
            context.systemPrompt(),
            context.provider(),
            context.model(),
            context.temperature(),
            context.maxTokens(),
            toolMaps,
            context.autoDiscoverTools(),
            context.maxTools(),
            context.maxIterations(),
            context.executionTimeout(),
            historyMaps,
            context.tenantId(),
            context.runId(),
            context.nodeId(),
            context.variables(),
            context.credentials(),
            null,   // maxCreditBudget - not available in this context
            null,   // streamChannelId - no streaming for classify/guardrail
            null,   // itemIndex
            null,   // loopIteration
            null,   // conversationId
            null,   // streamingFormat
            null,   // parentConversationId
            null,   // subAgentName
            null,   // subAgentAvatarUrl
            null,   // subAgentId
            null,   // workflowRunId
            null,   // attachments
            context.agentId(),
            null,   // tenantBalance
            null,   // pricingRates
            null,   // creditsConsumedSoFar
            context.loopIdenticalStop(),
            context.loopConsecutiveStop(),
            null,   // executionId - classify/guardrail are sub-loops within an outer execution, no own id
            null,   // source
            context.reasoningEffort(),  // null for classify/guardrail - effort applies to MAIN turns
            null    // enabledModules - classify/guardrail run with autoDiscoverTools=false (no tools), so module scoping is moot
        );
    }

    /**
     * Convert {@link AgentExecutionResponseDto} to {@link AgentLoopResult}. Mirrors the
     * logic in {@link SubAgentExecutionHandler#convertBridgeResponse} so downstream
     * parsing (ClassifyService, GuardrailService) sees the same shape whether the
     * response came from the bridge or the local agent loop.
     */
    AgentLoopResult convertResponse(AgentExecutionResponseDto response, String provider, String model) {
        if (!response.success()) {
            return AgentLoopResult.builder()
                .success(false)
                .error(response.error())
                .iterations(response.iterations())
                .durationMs(response.durationMs())
                .provider(response.provider() != null ? response.provider() : provider)
                .model(response.model() != null ? response.model() : model)
                .stopReason(parseStopReason(response.stopReason()))
                .conversationHistory(convertHistory(response.conversationHistory()))
                .toolResults(Collections.emptyList())
                .usagePerIteration(Collections.emptyList())
                .iterationDurations(Collections.emptyList())
                .finishReasonsPerIteration(Collections.emptyList())
                .build();
        }

        String content = response.content() != null ? response.content() : response.finalResponse();
        CompletionResponse completion = CompletionResponse.builder()
            .content(content)
            .finishReason("stop")
            .build();

        UsageInfo usage = response.totalUsage() != null ? convertUsage(response.totalUsage()) : null;

        List<UsageInfo> usagePerIteration = response.usagePerIteration() != null
            ? response.usagePerIteration().stream().map(this::convertUsage).toList()
            : Collections.emptyList();

        List<Message> history = convertHistory(response.conversationHistory());
        if (history.isEmpty() && content != null && !content.isBlank()) {
            history = List.of(new Message(Message.Role.ASSISTANT, content, null, null, null, null));
        }

        return AgentLoopResult.builder()
            .success(true)
            .response(completion)
            .content(content)
            .toolResults(Collections.emptyList())
            .iterations(response.iterations())
            .usage(usage)
            .durationMs(response.durationMs())
            .provider(response.provider() != null ? response.provider() : provider)
            .model(response.model() != null ? response.model() : model)
            .conversationHistory(history)
            .stopReason(parseStopReason(response.stopReason()))
            .metrics(response.metrics() != null ? response.metrics() : Map.of())
            .usagePerIteration(usagePerIteration)
            .iterationDurations(response.iterationDurations() != null
                ? response.iterationDurations() : Collections.emptyList())
            .finishReasonsPerIteration(response.finishReasonsPerIteration() != null
                ? response.finishReasonsPerIteration() : Collections.emptyList())
            .build();
    }

    private List<Message> convertHistory(List<Map<String, Object>> bridgeHistory) {
        if (bridgeHistory == null || bridgeHistory.isEmpty()) return Collections.emptyList();
        List<Message> messages = new ArrayList<>();
        for (Map<String, Object> msgMap : bridgeHistory) {
            String roleStr = msgMap.get("role") instanceof String s ? s : "ASSISTANT";
            Message.Role role;
            try {
                role = Message.Role.valueOf(roleStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                role = Message.Role.ASSISTANT;
            }
            String content = msgMap.get("content") instanceof String s ? s : null;
            String toolCallId = msgMap.get("toolCallId") instanceof String s ? s : null;
            String toolName = msgMap.get("toolName") instanceof String s ? s : null;
            messages.add(new Message(role, content, toolCallId, toolName, null, null));
        }
        return messages;
    }

    private UsageInfo convertUsage(Map<String, Object> usageMap) {
        return new UsageInfo(
            getInt(usageMap, "promptTokens"),
            getInt(usageMap, "completionTokens"),
            getInt(usageMap, "totalTokens"),
            getInt(usageMap, "cacheCreationInputTokens"),
            getInt(usageMap, "cacheReadInputTokens"),
            getInt(usageMap, "cachedTokens"),
            getInt(usageMap, "reasoningTokens"),
            getInt(usageMap, "thoughtsTokenCount"),
            getInt(usageMap, "cachedContentTokenCount")
        );
    }

    private Integer getInt(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.intValue();
        return null;
    }

    private AgentStopReason parseStopReason(String stopReasonStr) {
        if (stopReasonStr == null) return AgentStopReason.COMPLETED;
        try {
            return AgentStopReason.valueOf(stopReasonStr);
        } catch (IllegalArgumentException e) {
            return AgentStopReason.COMPLETED;
        }
    }

    private Map<String, Object> toolDefToMap(ToolDefinition tool) {
        Map<String, Object> map = new HashMap<>();
        if (tool.id() != null) map.put("id", tool.id());
        if (tool.name() != null) map.put("name", tool.name());
        if (tool.description() != null) map.put("description", tool.description());
        if (tool.apiSlug() != null) map.put("apiSlug", tool.apiSlug());
        if (tool.toolSlug() != null) map.put("toolSlug", tool.toolSlug());
        if (tool.parameters() != null) map.put("parameters", tool.parameters());
        if (tool.requiredParameters() != null) map.put("requiredParameters", tool.requiredParameters());
        if (tool.metadata() != null) map.put("metadata", tool.metadata());
        if (tool.timeoutMs() != null) map.put("timeoutMs", tool.timeoutMs());
        return map;
    }

    // ---- test seam -------------------------------------------------------------
    /** Package-private setter used by unit tests. */
    void setBridgeClient(SubAgentBridgeClient client) {
        this.bridgeClient = client;
    }

    /** Package-private setter used by unit tests. */
    void setBridgeAccessGuard(BridgeAccessGuard guard) {
        this.bridgeAccessGuard = guard;
    }
}
