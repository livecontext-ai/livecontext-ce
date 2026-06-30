package com.apimarketplace.orchestrator.services.agent;

import com.apimarketplace.agent.domain.Message;
import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.domain.ToolResult;
import com.apimarketplace.agent.streaming.StreamingCallback;
import com.apimarketplace.conversation.client.ConversationClient;
import com.apimarketplace.orchestrator.services.streaming.bus.WorkflowEventPublisher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Centralized conversation management for ALL agent types (workflow agents and sub-agents).
 * <p>
 * Single source of truth for:
 * <ul>
 *   <li>Finding or creating the unique conversation per agent entity</li>
 *   <li>Saving user prompts before execution</li>
 *   <li>Saving assistant responses with rich metadata (toolCalls, visualization, iconSlug, resultId)</li>
 *   <li>Creating streaming callbacks for live conversation panel updates</li>
 *   <li>Loading agent memory (conversation history)</li>
 * </ul>
 * <p>
 * Both {@code AgentNode} (workflow path) and {@code AgentExecuteModule} (sub-agent path) use this
 * service to ensure consistent conversation behavior regardless of how the agent is invoked.
 */
@Service
public class AgentConversationManager {

    private static final Logger log = LoggerFactory.getLogger(AgentConversationManager.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ConversationClient conversationClient;
    private final ConversationEventPublisher conversationEventPublisher;
    private final StringRedisTemplate redisTemplate;

    public AgentConversationManager(ConversationClient conversationClient,
                                     ConversationEventPublisher conversationEventPublisher,
                                     StringRedisTemplate redisTemplate) {
        this.conversationClient = conversationClient;
        this.conversationEventPublisher = conversationEventPublisher;
        this.redisTemplate = redisTemplate;
    }

    // ==================== Conversation Lifecycle ====================

    /**
     * Find or create THE unique conversation for this agent entity.
     * Each agent entity has exactly one conversation - reused across all invocations.
     *
     * <p>The {@code organizationId} (the OWNER org of this run/agent) MUST be passed
     * explicitly: workflow agents run on async / non-servlet threads where there is
     * no request-bound {@code X-Organization-ID} to recover. Without it the
     * conversation row would be stamped from whatever org happens to be ambient on
     * the thread (a cross-tenant bleed), instead of the workflow owner's org. The
     * conversation-client honors "explicit beats inherited", so a non-blank org here
     * is authoritative and the ambient fallback is never consulted.
     *
     * @param agentConfigId the agent entity UUID
     * @param tenantId the tenant/user ID
     * @param agentLabel the agent label (used as title for new conversations)
     * @param organizationId the OWNER org of this run/agent (from the execution context)
     * @return the conversation ID, or null if unavailable
     */
    public String ensureConversation(String agentConfigId, String tenantId, String agentLabel,
                                     String organizationId) {
        if (agentConfigId == null || conversationClient == null) {
            return null;
        }
        try {
            return conversationClient.findOrCreateAgentConversation(
                agentConfigId, tenantId, agentLabel, organizationId);
        } catch (Exception e) {
            log.warn("Failed to ensure conversation for agent {}: {}", agentConfigId, e.getMessage());
            return null;
        }
    }

    /**
     * Start an agent execution: save the user prompt and begin streaming.
     *
     * @param conversationId the conversation ID
     * @param prompt the user prompt
     * @param tenantId the tenant/user ID
     * @param executionId optional execution ID for linking messages
     * @param model the LLM model name (for stream_started event)
     * @return a StreamSession for tracking the stream, or null if no conversation
     */
    public StreamSession startExecution(String conversationId, String prompt,
                                         String tenantId, String executionId, String model) {
        return startExecution(conversationId, prompt, tenantId, executionId, model, false);
    }

    /**
     * Start an agent execution with optional prompt skip.
     *
     * @param conversationId the conversation ID
     * @param prompt the user prompt
     * @param tenantId the tenant/user ID
     * @param executionId optional execution ID for linking messages
     * @param model the LLM model name (for stream_started event)
     * @param skipUserPrompt if true, the user prompt is NOT saved (already persisted by caller, e.g. chat trigger frontend)
     * @return a StreamSession for tracking the stream, or null if no conversation
     */
    public StreamSession startExecution(String conversationId, String prompt,
                                         String tenantId, String executionId, String model,
                                         boolean skipUserPrompt) {
        if (conversationId == null) {
            return null;
        }

        // Save user message (prompt) immediately so the panel shows it in real-time
        // Skip when the frontend already saved it (chat trigger flow)
        if (!skipUserPrompt && prompt != null && !prompt.isBlank()) {
            try {
                conversationClient.saveMessage(conversationId, "user", prompt, null, tenantId, executionId);
                log.debug("Saved user prompt to conversation {}", conversationId);
            } catch (Exception e) {
                log.warn("Failed to save user prompt to conversation {}: {}", conversationId, e.getMessage());
            }
        }

        // Start conversation streaming
        String streamId = UUID.randomUUID().toString();
        if (conversationEventPublisher != null) {
            conversationEventPublisher.publishStreamStarted(conversationId, streamId, model);
            log.debug("Started stream {} for conversation {}", streamId, conversationId);
        }

        return new StreamSession(conversationId, streamId);
    }

    // ==================== Streaming ====================

    /**
     * Create a streaming callback that publishes events to both the workflow channel
     * (optional) and the conversation WebSocket channel.
     * <p>
     * For workflow agents: pass runId, nodeId, eventPublisher for workflow streaming.
     * For sub-agents: pass null for workflow params - only conversation streaming is used.
     *
     * @param session the stream session (from startExecution)
     * @param runId workflow run ID (null for sub-agents)
     * @param nodeId workflow node ID (null for sub-agents)
     * @param itemIndex trigger item index (0 for sub-agents)
     * @param iteration loop iteration (null for sub-agents)
     * @param workflowPublisher workflow event publisher (null for sub-agents)
     * @return a StreamingCallback, or null if no session
     */
    public StreamingCallback createStreamingCallback(StreamSession session,
                                                      String runId, String nodeId,
                                                      int itemIndex, Integer iteration,
                                                      WorkflowEventPublisher workflowPublisher) {
        return createStreamingCallback(session, runId, nodeId, itemIndex, iteration,
            workflowPublisher, null, null, null, null);
    }

    /**
     * Create a streaming callback with optional parent-forwarding for sub-agent visibility.
     *
     * @param parentConversationId parent's conversation ID for event forwarding (nullable)
     * @param subAgentName         sub-agent display name (nullable)
     * @param subAgentAvatarUrl    sub-agent avatar URL (nullable)
     * @param subAgentId           sub-agent entity ID (nullable)
     */
    public StreamingCallback createStreamingCallback(StreamSession session,
                                                      String runId, String nodeId,
                                                      int itemIndex, Integer iteration,
                                                      WorkflowEventPublisher workflowPublisher,
                                                      String parentConversationId,
                                                      String subAgentName, String subAgentAvatarUrl,
                                                      String subAgentId) {
        if (session == null) {
            if (workflowPublisher != null && runId != null && nodeId != null) {
                return new AgentToolCallEventBridge(runId, nodeId, itemIndex, iteration, workflowPublisher);
            }
            return null;
        }

        return new AgentToolCallEventBridge(
            runId, nodeId, itemIndex, iteration,
            workflowPublisher,
            conversationEventPublisher,
            session.conversationId(),
            session.streamId(),
            parentConversationId,
            subAgentName, subAgentAvatarUrl, subAgentId,
            redisTemplate
        );
    }

    /**
     * Get the conversation event publisher (for direct sub-agent start/complete events).
     */
    public ConversationEventPublisher getConversationEventPublisher() {
        return conversationEventPublisher;
    }

    /**
     * Complete the streaming session after execution finishes.
     *
     * @param session the stream session
     * @param result the agent execution result
     */
    public void completeStream(StreamSession session, AgentExecutionResult result) {
        if (session == null || conversationEventPublisher == null) {
            return;
        }

        if (result.isSuccess()) {
            conversationEventPublisher.publishCompleted(
                session.conversationId(), session.streamId(), result.getContent());
        } else {
            conversationEventPublisher.publishError(
                session.conversationId(), session.streamId(),
                result.getError() != null ? result.getError() : "Agent execution failed");
        }
    }

    // ==================== Save Assistant Response ====================

    /**
     * Save the assistant response with rich tool call metadata (visualization, iconSlug, resultId).
     * This is the full-format save used by both workflow agents and sub-agents.
     *
     * @param conversationId the conversation ID
     * @param tenantId the tenant/user ID
     * @param result the agent execution result
     * @param executionId optional execution ID for linking messages
     */
    public void saveAssistantResponse(String conversationId, String tenantId,
                                       AgentExecutionResult result, String executionId) {
        if (conversationId == null || conversationClient == null) {
            return;
        }

        try {
            // 1. Save tool results FIRST to get resultIds for the toolCalls JSON
            Map<String, String> toolCallIdToResultId = saveToolResults(
                conversationId, tenantId, result, executionId);

            // 2. Build rich toolCalls JSON with resultId, visualization, iconSlug
            String toolCallsJson = buildToolCallsJson(result, toolCallIdToResultId);
            String responseContent = result.getContent();

            // 3. Save assistant message with enriched toolCalls
            if ((responseContent != null && !responseContent.isBlank()) || toolCallsJson != null) {
                conversationClient.saveMessage(
                    conversationId, "assistant", responseContent, toolCallsJson, tenantId, executionId);
            }

            int toolCount = result.getToolResults() != null ? result.getToolResults().size() : 0;
            log.info("Saved agent response to conversation {} ({} tool calls)", conversationId, toolCount);
        } catch (Exception e) {
            log.warn("Failed to save agent response to conversation {}: {}", conversationId, e.getMessage());
        }
    }

    // ==================== Agent Memory ====================

    /**
     * Load conversation history for agent memory.
     * Returns null if memory is not available or disabled.
     *
     * @param agentConfigId the agent entity UUID
     * @param tenantId the tenant/user ID
     * @param limit max number of messages to load
     * @param organizationId the OWNER org of this run/agent (explicit; never ambient)
     * @return the conversation history, or null
     */
    public List<Message> loadMemory(String agentConfigId, String tenantId, int limit,
                                    String organizationId) {
        if (agentConfigId == null || conversationClient == null) {
            return null;
        }

        try {
            String conversationId = conversationClient.findOrCreateAgentConversation(
                agentConfigId, tenantId, null, organizationId);

            if (conversationId == null) {
                log.debug("No conversation found for agent entity {}", agentConfigId);
                return null;
            }

            List<Map<String, Object>> messages = conversationClient.getConversationMessages(
                conversationId, limit, tenantId);

            if (messages.isEmpty()) {
                log.debug("No conversation history for agent entity {}", agentConfigId);
                return null;
            }

            List<Message> history = new ArrayList<>();
            for (Map<String, Object> msg : messages) {
                String role = (String) msg.get("role");
                String content = (String) msg.get("content");
                if (role != null && content != null) {
                    Message.Role messageRole = switch (role.toLowerCase()) {
                        case "user" -> Message.Role.USER;
                        case "assistant" -> Message.Role.ASSISTANT;
                        case "system" -> Message.Role.SYSTEM;
                        default -> Message.Role.USER;
                    };
                    history.add(Message.builder().role(messageRole).content(content).build());
                }
            }

            log.info("Loaded {} memory messages for agent entity {}", history.size(), agentConfigId);
            return history.isEmpty() ? null : history;
        } catch (Exception e) {
            log.warn("Failed to load agent memory for {}: {}", agentConfigId, e.getMessage());
            return null;
        }
    }

    // ==================== Private: Tool Results ====================

    /**
     * Persist each tool result to the conversation-service's tool_results table.
     * Returns a map of toolCallId -> resultId for enriching the toolCalls JSON.
     */
    private Map<String, String> saveToolResults(String conversationId, String tenantId,
                                                 AgentExecutionResult result, String executionId) {
        Map<String, String> toolCallIdToResultId = new LinkedHashMap<>();
        List<ToolResult> toolResults = result.getToolResults();
        if (toolResults == null || toolResults.isEmpty()) {
            return toolCallIdToResultId;
        }

        for (ToolResult tr : toolResults) {
            try {
                ToolCall tc = tr.toolCall();
                String toolCallId = tc != null ? tc.id() : UUID.randomUUID().toString();
                String toolName = tc != null ? tc.toolName() : "unknown";

                String resultId = conversationClient.saveToolResult(
                    conversationId, tenantId, toolName, toolCallId,
                    tr.success(), tr.durationMs(), tr.content(), tr.error(), executionId);

                if (resultId != null) {
                    toolCallIdToResultId.put(toolCallId, resultId);
                }
            } catch (Exception e) {
                log.warn("Failed to save tool result for {}: {}",
                    tr.toolCall() != null ? tr.toolCall().toolName() : "unknown", e.getMessage());
            }
        }
        return toolCallIdToResultId;
    }

    // ==================== Private: ToolCalls JSON ====================

    /**
     * Build toolCalls JSON from AgentExecutionResult with rich metadata.
     * Includes resultId, visualization, iconSlug, and displayToolName so the frontend
     * can fetch full tool result content and render appropriate visualizations.
     */
    private String buildToolCallsJson(AgentExecutionResult result,
                                       Map<String, String> toolCallIdToResultId) {
        List<ToolResult> toolResults = result.getToolResults();
        if (toolResults == null || toolResults.isEmpty()) {
            return null;
        }

        List<Map<String, Object>> toolCallEntries = new ArrayList<>();

        for (ToolResult tr : toolResults) {
            Map<String, Object> entry = new LinkedHashMap<>();
            ToolCall tc = tr.toolCall();

            String toolCallId = tc != null ? tc.id() : UUID.randomUUID().toString();
            String toolName = tc != null ? tc.toolName() : "unknown";

            // Core fields matching AgentStreamingCallbackFactory format
            entry.put("id", toolCallId);
            entry.put("toolName", toolName);

            // Serialize arguments to JSON string
            if (tc != null && tc.arguments() != null) {
                try {
                    entry.put("arguments", OBJECT_MAPPER.writeValueAsString(tc.arguments()));
                } catch (JsonProcessingException e) {
                    entry.put("arguments", tc.arguments().toString());
                }
            }

            entry.put("success", tr.success());
            entry.put("timestamp", System.currentTimeMillis());

            if (tr.durationMs() != null) {
                entry.put("durationMs", tr.durationMs());
            }
            if (tr.error() != null) {
                entry.put("error", tr.error());
            }

            // Add resultId so frontend can fetch full tool result content on demand
            String resultId = toolCallIdToResultId.get(toolCallId);
            if (resultId != null) {
                entry.put("resultId", resultId);
            }

            // Add visualization and display metadata
            Map<String, Object> visualization = buildVisualization(tr);
            if (visualization != null) {
                entry.put("visualization", visualization);
            }

            String iconSlug = resolveIconSlug(toolName, tr);
            if (iconSlug != null) {
                entry.put("iconSlug", iconSlug);
            }
            String displayToolName = resolveDisplayToolName(toolName, tr);
            if (displayToolName != null) {
                entry.put("displayToolName", displayToolName);
            }

            toolCallEntries.add(entry);
        }

        try {
            return OBJECT_MAPPER.writeValueAsString(toolCallEntries);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize toolCalls JSON: {}", e.getMessage());
            return null;
        }
    }

    // ==================== Private: Visualization Metadata ====================

    /**
     * Build visualization metadata for a tool result.
     * Only uses visualization from explicit metadata with a valid resource ID.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> buildVisualization(ToolResult tr) {
        if (tr.metadata() != null && tr.metadata().containsKey("visualization")) {
            Object viz = tr.metadata().get("visualization");
            if (viz instanceof Map) {
                Map<String, Object> vizMap = (Map<String, Object>) viz;
                if (vizMap.containsKey("id")) {
                    return vizMap;
                }
            }
        }
        return null;
    }

    /**
     * Resolve icon slug for a tool name.
     */
    private String resolveIconSlug(String toolName, ToolResult tr) {
        if (tr.metadata() != null && tr.metadata().containsKey("iconSlug")) {
            return tr.metadata().get("iconSlug").toString();
        }
        String normalized = toolName.toLowerCase();
        if (normalized.contains("web_search") || normalized.contains("websearch")) return "web_search";
        if (normalized.equals("datasource") || normalized.equals("table")) return "datasource";
        if (normalized.equals("workflow")) return "workflow";
        if (normalized.equals("interface")) return "interface";
        return null;
    }

    /**
     * Resolve display name for a tool.
     */
    private String resolveDisplayToolName(String toolName, ToolResult tr) {
        if (tr.metadata() != null && tr.metadata().containsKey("toolName")) {
            return tr.metadata().get("toolName").toString();
        }
        String normalized = toolName.toLowerCase();
        if (normalized.contains("web_search") || normalized.contains("websearch")) return "Web Search";
        if (normalized.equals("datasource") || normalized.equals("table")) return "Table";
        if (normalized.equals("workflow")) return "Workflow";
        if (normalized.equals("interface")) return "Interface";
        return toolName;
    }

    // ==================== Stream Session ====================

    /**
     * Represents an active streaming session for a conversation.
     */
    public record StreamSession(String conversationId, String streamId) {}
}
