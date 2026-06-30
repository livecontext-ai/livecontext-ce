package com.apimarketplace.agent.service.dto;

import com.apimarketplace.agent.client.dto.AgentObservabilityRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

/**
 * Adapter that converts a ChatAgentObservabilityRequest (from conversation-service)
 * into the unified AgentObservabilityRequest DTO used by the workflow path.
 * This eliminates duplicate persistence logic by routing both chat and workflow
 * recording through the same doRecordFromRequest() code path.
 */
public final class ChatObservabilityAdapter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ChatObservabilityAdapter() {}

    /**
     * Convert a chat observability request into the unified AgentObservabilityRequest.
     *
     * @param tenantId the tenant ID (from X-User-ID header)
     * @param request  the chat-specific request from conversation-service
     * @return unified AgentObservabilityRequest ready for doRecordFromRequest()
     */
    public static AgentObservabilityRequest toUnifiedRequest(String tenantId, String organizationId,
                                                              ChatAgentObservabilityRequest request) {
        var req = new AgentObservabilityRequest();

        // Identity
        req.setTenantId(tenantId);
        // PR20 - workspace scope on the persisted observability row.
        req.setOrganizationId(organizationId);
        req.setAgentType("agent");
        req.setNodeId("chat");

        if (request.agentEntityId() != null) {
            try {
                req.setAgentEntityId(UUID.fromString(request.agentEntityId()));
            } catch (IllegalArgumentException ignored) {}
        }

        // Chat context
        req.setSource(request.source() != null && !request.source().isBlank() ? request.source() : "CHAT");
        req.setConversationId(request.conversationId());
        // (Legacy: ChatObservabilityAdapter used to also setRunId(conversationId).
        // That aliased run_id to conversationId - a hack from before agent_executions
        // had a dedicated conversationId column. Removed: the conversation linkage
        // lives in conversation_id, runId stays null for chat. Workflow path sets
        // runId from workflow_run.id; chat genuinely has no workflow run.)

        // Task linkage
        if (request.taskId() != null && !request.taskId().isBlank()) {
            try {
                req.setTaskId(UUID.fromString(request.taskId()));
            } catch (IllegalArgumentException e) {
                // Should never happen - source is always UUID.toString() from AgentTaskService
            }
        }

        // Stable executionId - dispatcher-minted UUID that becomes agent_executions.id.
        // Lets the row-writer link the row back to claim log entries written by MCP
        // task_claim mid-execution.
        if (request.executionId() != null && !request.executionId().isBlank()) {
            req.setExecutionId(request.executionId());
        }
        req.setSystemPrompt(request.systemPrompt());
        req.setMemoryEnabled(true); // Chat agents always have conversation memory

        // Execution result
        req.setStatus(request.success() ? "COMPLETED" : "FAILED");
        req.setStopReason(request.stopReason());
        req.setBudgetScope(request.budgetScope());
        req.setErrorMessage(request.errorMessage());
        req.setDurationMs(request.durationMs());

        // LLM config
        req.setProvider(request.provider());
        req.setModel(request.model());
        req.setTemperature(request.temperature());
        req.setMaxTokensConfig(request.maxTokens());
        req.setMaxIterationsConfig(request.maxIterations());

        // Token usage
        req.setPromptTokens(request.totalPromptTokens());
        req.setCompletionTokens(request.totalCompletionTokens());
        req.setTotalTokens(request.totalTokens());
        req.setCacheCreationTokens(request.totalCacheCreationTokens() != null ? request.totalCacheCreationTokens() : 0);
        req.setCacheReadTokens(request.totalCacheReadTokens() != null ? request.totalCacheReadTokens() : 0);
        req.setCachedTokens(request.totalCachedTokens() != null ? request.totalCachedTokens() : 0);
        req.setReasoningTokens(request.totalReasoningTokens() != null ? request.totalReasoningTokens() : 0);

        // Counters
        req.setIterationCount(request.iterationCount());
        req.setTotalToolCalls(request.totalToolCalls());

        // Loop detection
        req.setLoopDetected(request.loopDetected());
        req.setLoopType(request.loopType());
        req.setLoopToolName(request.loopToolName());

        // Only convert detailed data when full detail is available (matches old hasFullDetail logic)
        boolean hasHistory = request.conversationHistory() != null && !request.conversationHistory().isEmpty();
        boolean hasToolResults = request.toolResults() != null && !request.toolResults().isEmpty();
        boolean hasFullDetail = hasHistory || hasToolResults;

        if (hasFullDetail) {
            req.setMessages(convertMessages(request.systemPrompt(), request.userPrompt(), request.conversationHistory()));
            req.setToolCalls(convertToolCalls(request.toolResults(), request.toolCallsPerIteration()));
            req.setIterations(convertIterations(request));
        }

        return req;
    }

    /**
     * Convert chat message DTOs to unified MessageData.
     * Explicitly prepends a controlled SYSTEM + USER pair (matching the sub-agent path),
     * then appends only execution messages (ASSISTANT/TOOL) from the conversation history,
     * stripping any SYSTEM/USER that may have leaked into the execution history.
     */
    private static List<AgentObservabilityRequest.MessageData> convertMessages(
            String systemPrompt, String userPrompt,
            List<ChatAgentObservabilityRequest.MessageDto> history) {

        var messages = new ArrayList<AgentObservabilityRequest.MessageData>();
        int seq = 0;

        // 1. Prepend controlled SYSTEM message
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            var sysMd = new AgentObservabilityRequest.MessageData();
            sysMd.setSequenceNumber(seq++);
            sysMd.setRole("SYSTEM");
            sysMd.setContent(systemPrompt);
            messages.add(sysMd);
        }

        // 2. Prepend controlled USER message (iteration 0 = before any LLM response)
        if (userPrompt != null && !userPrompt.isBlank()) {
            var userMd = new AgentObservabilityRequest.MessageData();
            userMd.setSequenceNumber(seq++);
            userMd.setRole("USER");
            userMd.setContent(userPrompt);
            userMd.setIterationNumber(0);
            messages.add(userMd);
        }

        // 3. Append execution messages, skipping any SYSTEM/USER to avoid duplication
        if (history != null) {
            int iterationCounter = 0;
            for (var dto : history) {
                String role = dto.role() != null ? dto.role().toUpperCase() : "ASSISTANT";
                // Skip SYSTEM and USER - they are already prepended above
                if ("SYSTEM".equals(role) || "USER".equals(role)) {
                    continue;
                }
                if ("ASSISTANT".equals(role)) {
                    iterationCounter++;
                }

                var md = new AgentObservabilityRequest.MessageData();
                md.setSequenceNumber(seq++);
                md.setRole(role);
                md.setContent(dto.content());
                md.setToolCallId(dto.toolCallId());
                md.setToolName(dto.toolName());
                md.setIterationNumber(iterationCounter);
                messages.add(md);
            }
        }

        return messages.isEmpty() ? null : messages;
    }

    /**
     * Convert chat tool result DTOs to unified ToolCallData, with iteration mapping.
     */
    private static List<AgentObservabilityRequest.ToolCallData> convertToolCalls(
            List<ChatAgentObservabilityRequest.ToolResultDto> toolResults,
            List<Integer> toolCallsPerIteration) {
        if (toolResults == null || toolResults.isEmpty()) return null;

        // Build iteration mapping: toolCallsPerIteration = [1, 1, 0] → tool 0 = iter 1, tool 1 = iter 2
        int[] iterationForTool = buildIterationMapping(toolResults.size(), toolCallsPerIteration);

        var toolCalls = new ArrayList<AgentObservabilityRequest.ToolCallData>();
        int seq = 0;
        for (var dto : toolResults) {
            var tc = new AgentObservabilityRequest.ToolCallData();
            tc.setSequenceNumber(seq);
            tc.setIterationNumber(iterationForTool[seq]);
            tc.setToolCallId(dto.toolCallId());
            tc.setToolName(dto.toolName() != null ? dto.toolName() : "unknown");
            tc.setArguments(coerceToMap(dto.arguments()));
            tc.setSuccess(dto.success());
            tc.setResult(dto.content());
            tc.setErrorMessage(dto.error());
            tc.setDurationMs(dto.durationMs() != null ? dto.durationMs() : 0L);
            tc.setMetadata(dto.metadata());
            toolCalls.add(tc);
            seq++;
        }
        return toolCalls.isEmpty() ? null : toolCalls;
    }

    /**
     * Build iteration mapping from toolCallsPerIteration counts (1-based).
     * E.g., [1, 1, 0] → tool[0]=1, tool[1]=2
     */
    private static int[] buildIterationMapping(int totalToolCalls, List<Integer> toolCallsPerIteration) {
        int[] mapping = new int[totalToolCalls];
        if (toolCallsPerIteration == null || toolCallsPerIteration.isEmpty()) {
            java.util.Arrays.fill(mapping, 1);
            return mapping;
        }

        int toolIndex = 0;
        for (int iter = 0; iter < toolCallsPerIteration.size() && toolIndex < totalToolCalls; iter++) {
            int count = toolCallsPerIteration.get(iter);
            for (int j = 0; j < count && toolIndex < totalToolCalls; j++) {
                mapping[toolIndex] = iter + 1;
                toolIndex++;
            }
        }
        int lastIteration = toolCallsPerIteration.size();
        while (toolIndex < totalToolCalls) {
            mapping[toolIndex] = lastIteration;
            toolIndex++;
        }
        return mapping;
    }

    /**
     * Convert per-iteration data from the chat request into unified IterationData.
     */
    private static List<AgentObservabilityRequest.IterationData> convertIterations(
            ChatAgentObservabilityRequest request) {
        if (request.iterationCount() <= 0) return null;

        var iterations = new ArrayList<AgentObservabilityRequest.IterationData>();
        for (int i = 0; i < request.iterationCount(); i++) {
            var iter = new AgentObservabilityRequest.IterationData();
            iter.setIterationNumber(i + 1); // 1-based to match chat convention

            if (request.usagePerIteration() != null && i < request.usagePerIteration().size()) {
                var u = request.usagePerIteration().get(i);
                iter.setPromptTokens(u.promptTokens() != null ? u.promptTokens() : 0);
                iter.setCompletionTokens(u.completionTokens() != null ? u.completionTokens() : 0);
                iter.setCacheCreationTokens(u.cacheCreationInputTokens() != null ? u.cacheCreationInputTokens() : 0);
                iter.setCacheReadTokens(u.cacheReadInputTokens() != null ? u.cacheReadInputTokens() : 0);
                iter.setCachedTokens(u.cachedTokens() != null ? u.cachedTokens() : 0);
                iter.setReasoningTokens(u.reasoningTokens() != null ? u.reasoningTokens() : 0);
            }

            if (request.iterationDurations() != null && i < request.iterationDurations().size()) {
                iter.setDurationMs(request.iterationDurations().get(i));
            }

            if (request.finishReasonsPerIteration() != null && i < request.finishReasonsPerIteration().size()) {
                iter.setFinishReason(request.finishReasonsPerIteration().get(i));
            }

            if (request.toolCallsPerIteration() != null && i < request.toolCallsPerIteration().size()) {
                iter.setToolCallCount(request.toolCallsPerIteration().get(i));
            }

            iterations.add(iter);
        }
        return iterations.isEmpty() ? null : iterations;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> coerceToMap(Object arguments) {
        if (arguments == null) return null;
        if (arguments instanceof Map) return (Map<String, Object>) arguments;
        if (arguments instanceof String s && !s.isBlank()) {
            try {
                return OBJECT_MAPPER.readValue(s, Map.class);
            } catch (Exception e) {
                return Map.of("_raw", s);
            }
        }
        return null;
    }
}
