package com.apimarketplace.conversation.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import com.apimarketplace.common.web.OrgContextHeaderForwarder;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared HTTP client for conversation-service.
 * Used by agent-service, orchestrator-service, and any other service
 * that needs to interact with conversations.
 */
public class ConversationClient {

    private static final Logger log = LoggerFactory.getLogger(ConversationClient.class);
    private static final Duration DEFAULT_SYNC_READ_TIMEOUT = Duration.ofMinutes(325);

    private final RestTemplate restTemplate;
    private final RestTemplate syncRestTemplate; // Long timeout for sync chat (agent execution can take minutes)
    private final String baseUrl;

    public ConversationClient(RestTemplate restTemplate, String baseUrl) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;

        // Sync calls block until the agent completes. One agent can own five
        // in-progress tasks while conversation-service serializes them by
        // conversation, so this covers four queued turns plus the current turn.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(defaultSyncReadTimeout());
        this.syncRestTemplate = new RestTemplate(factory);
    }

    static Duration defaultSyncReadTimeout() {
        return DEFAULT_SYNC_READ_TIMEOUT;
    }

    /** The conversation-service base URL this client targets (visible for wiring tests). */
    public String getBaseUrl() {
        return baseUrl;
    }

    // ==================== AGENT CONVERSATIONS ====================

    /**
     * Find the unique conversation for an agent entity (read-only, no create).
     * Returns null if no conversation exists for this agent.
     */
    @SuppressWarnings("unchecked")
    public String findAgentConversation(String agentId, String tenantId, String organizationId) {
        String findUrl = baseUrl + "/api/conversations/agent/" + agentId;
        try {
            ResponseEntity<Map> resp = restTemplate.exchange(
                    findUrl, HttpMethod.GET, createEntity(null, tenantId, organizationId), Map.class);
            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                return (String) resp.getBody().get("id");
            }
        } catch (HttpClientErrorException.NotFound e) {
            log.debug("No conversation found for agent {}", agentId);
        } catch (Exception e) {
            log.warn("Error finding conversation for agent {}: {}", agentId, e.getMessage());
        }
        return null;
    }

    /**
     * Find or create THE unique conversation for an agent entity.
     * Same pattern as AgentConversationManager.ensureConversation().
     */
    @SuppressWarnings("unchecked")
    public String findOrCreateAgentConversation(String agentId, String tenantId, String agentName) {
        return findOrCreateAgentConversation(agentId, tenantId, agentName, null);
    }

    /**
     * Find or create THE unique conversation for an agent entity in an explicit scope.
     * Passing organizationId is required for async callers because they no longer have
     * a servlet request context from which the header forwarder can recover scope.
     */
    @SuppressWarnings("unchecked")
    public String findOrCreateAgentConversation(String agentId, String tenantId, String agentName,
                                                 String organizationId) {
        // 1. Try to find existing
        String findUrl = baseUrl + "/api/conversations/agent/" + agentId;
        try {
            ResponseEntity<Map> resp = restTemplate.exchange(
                    findUrl, HttpMethod.GET, createEntity(null, tenantId, organizationId), Map.class);
            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                String id = (String) resp.getBody().get("id");
                log.debug("Found existing conversation {} for agent {}", id, agentId);
                return id;
            }
        } catch (HttpClientErrorException.NotFound e) {
            log.debug("No existing conversation for agent {}, will create one", agentId);
        } catch (Exception e) {
            log.warn("Error finding conversation for agent {}: {}", agentId, e.getMessage());
        }

        // 2. Create new
        try {
            Map<String, String> body = new HashMap<>();
            if (agentName != null) body.put("title", agentName);
            ResponseEntity<Map> resp = restTemplate.exchange(
                    findUrl, HttpMethod.POST, createEntity(body, tenantId, organizationId), Map.class);
            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                String id = (String) resp.getBody().get("id");
                log.info("Created conversation {} for agent {}", id, agentId);
                return id;
            }
        } catch (Exception e) {
            log.warn("Failed to create conversation for agent {}: {}", agentId, e.getMessage());
        }
        return null;
    }

    /**
     * Create a standalone conversation (not linked to agent's main conversation).
     * Used by webhook (memory=off) and schedule (withMemory=false).
     */
    @SuppressWarnings("unchecked")
    public String createConversation(String tenantId, String title, String model, String provider,
                                     String agentId, Boolean memoryEnabled) {
        return createConversation(tenantId, title, model, provider, agentId, memoryEnabled, null);
    }

    /**
     * Audit 2026-05-17 round-5 - org-aware variant. Daemon callers (schedule
     * cron, recurrence fire, etc.) MUST pass organizationId so the conversation
     * row lands in the correct workspace.
     */
    @SuppressWarnings("unchecked")
    public String createConversation(String tenantId, String title, String model, String provider,
                                     String agentId, Boolean memoryEnabled, String organizationId) {
        String url = baseUrl + "/api/conversations";
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("title", title);
            body.put("model", model);
            body.put("provider", provider);
            if (agentId != null) body.put("agentId", agentId);
            if (memoryEnabled != null) body.put("memoryEnabled", memoryEnabled);
            body.put("active", true);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-User-ID", tenantId);
            OrgContextHeaderForwarder.setIfPresent(headers, organizationId);
            ResponseEntity<Map> resp = restTemplate.postForEntity(url, new HttpEntity<>(body, headers), Map.class);
            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                String id = resp.getBody().get("id").toString();
                log.info("Created conversation {}", id);
                return id;
            }
        } catch (Exception e) {
            log.error("Error creating conversation: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Create a sub-agent conversation linked to a parent conversation.
     */
    @SuppressWarnings("unchecked")
    public String createSubAgentConversation(String agentId, String tenantId, String agentName,
                                              String parentConversationId, String model, String provider) {
        return createSubAgentConversation(agentId, tenantId, agentName, parentConversationId, model, provider, null);
    }

    /**
     * Org-aware variant for sub-agent conversation creation.
     * Sub-agent execution is async - RequestContextHolder is empty, so
     * organizationId must be passed explicitly to land the conversation
     * in the correct workspace.
     */
    @SuppressWarnings("unchecked")
    public String createSubAgentConversation(String agentId, String tenantId, String agentName,
                                              String parentConversationId, String model, String provider,
                                              String organizationId) {
        String url = baseUrl + "/api/conversations";
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("title", agentName != null ? agentName : "Sub-agent");
            body.put("agentId", agentId);
            body.put("parentConversationId", parentConversationId);
            if (model != null) body.put("model", model);
            if (provider != null) body.put("provider", provider);

            ResponseEntity<Map> resp = restTemplate.exchange(
                    url, HttpMethod.POST, createEntity(body, tenantId, organizationId), Map.class);
            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                String id = (String) resp.getBody().get("id");
                log.info("Created sub-agent conversation {} for agent {} (parent={})", id, agentId, parentConversationId);
                return id;
            }
        } catch (Exception e) {
            log.warn("Failed to create sub-agent conversation for agent {}: {}", agentId, e.getMessage());
        }
        return null;
    }

    // ==================== CHAT (Internal) ====================

    /**
     * Execute a chat synchronously via /api/internal/chat/sync.
     * Blocks until the agent completes. Returns {success, content, error, conversationId}.
     * Used by webhook (source=WEBHOOK) and schedule (source=SCHEDULE).
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> sendChatSync(String tenantId, String conversationId, String message,
                                             String agentId, String model, String provider, String source) {
        return sendChatSync(tenantId, conversationId, message, agentId, model, provider, source, null, null);
    }

    /**
     * Execute a chat synchronously via /api/internal/chat/sync.
     * Blocks until the agent completes. Returns {success, content, error, conversationId}.
     * When taskId is provided, the resulting agent execution is linked to that task.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> sendChatSync(String tenantId, String conversationId, String message,
                                             String agentId, String model, String provider,
                                             String source, String taskId) {
        return sendChatSync(tenantId, conversationId, message, agentId, model, provider, source, taskId, null);
    }

    /**
     * Execute a chat synchronously in an explicit scope.
     * Passing organizationId is required for async task/review execution paths where
     * RequestContextHolder is not available anymore.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> sendChatSync(String tenantId, String conversationId, String message,
                                             String agentId, String model, String provider,
                                             String source, String taskId, String organizationId) {
        return sendChatSync(tenantId, conversationId, message, agentId, model, provider,
                source, taskId, organizationId, null);
    }

    /**
     * Execute a chat synchronously in an explicit scope and optional task review execution scope.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> sendChatSync(String tenantId, String conversationId, String message,
                                             String agentId, String model, String provider,
                                             String source, String taskId, String organizationId,
                                             String reviewerExecutionId) {
        String url = baseUrl + "/api/internal/chat/sync";
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("message", message);
            body.put("conversationId", conversationId);
            body.put("model", model);
            body.put("provider", provider);
            if (agentId != null) body.put("agentId", agentId);
            if (source != null) body.put("source", source);
            if (taskId != null) body.put("taskId", taskId);
            if (reviewerExecutionId != null) body.put("reviewerExecutionId", reviewerExecutionId);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-User-ID", tenantId);
            headers.set("Accept", MediaType.APPLICATION_JSON_VALUE);
            // PR16 - forward X-Organization-ID / X-Organization-Role.
            applyOrgContextHeaders(headers, organizationId);

            ResponseEntity<Map> resp = syncRestTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                log.info("Sync chat completed for conversation {} (source={})", conversationId, source);
                return resp.getBody();
            }
            return Map.of("success", false, "error", "Unexpected response: " + resp.getStatusCode());
        } catch (Exception e) {
            log.error("Error in sync chat: {}", e.getMessage());
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    // ==================== DELETE ====================

    public boolean deleteConversationsByAgentId(String agentId, String tenantId) {
        return deleteConversationsBy("by-agent", agentId, tenantId, null);
    }

    /**
     * Org-aware variant for cascade agent deletion.
     * Cascade deletes may run in async contexts where RequestContextHolder
     * is empty, so organizationId must be passed explicitly.
     */
    public boolean deleteConversationsByAgentId(String agentId, String tenantId, String organizationId) {
        return deleteConversationsBy("by-agent", agentId, tenantId, organizationId);
    }

    public boolean deleteConversationsByWorkflowId(String workflowId, String tenantId) {
        return deleteConversationsBy("by-workflow", workflowId, tenantId, null);
    }

    /**
     * Org-aware variant for cascade workflow deletion.
     * Cascade deletes may run in async contexts where RequestContextHolder
     * is empty, so organizationId must be passed explicitly.
     */
    public boolean deleteConversationsByWorkflowId(String workflowId, String tenantId, String organizationId) {
        return deleteConversationsBy("by-workflow", workflowId, tenantId, organizationId);
    }

    @SuppressWarnings("unchecked")
    private boolean deleteConversationsBy(String pathSegment, String id, String tenantId, String organizationId) {
        String url = baseUrl + "/api/conversations/" + pathSegment + "/" + id;
        try {
            ResponseEntity<Map> resp = restTemplate.exchange(
                    url, HttpMethod.DELETE, createEntity(null, tenantId, organizationId), Map.class);
            if (resp.getStatusCode().is2xxSuccessful()) {
                Map<String, Object> body = resp.getBody();
                int count = body != null ? (Integer) body.getOrDefault("deletedCount", 0) : 0;
                log.info("Deleted {} conversations for {} {}", count, pathSegment, id);
                return true;
            }
        } catch (Exception e) {
            log.warn("Failed to delete conversations for {} {}: {}", pathSegment, id, e.getMessage());
        }
        return false;
    }

    // ==================== SHARING ====================

    /**
     * Enable sharing on a conversation. Returns the conversation DTO with shareToken.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> enableSharing(String conversationId, String tenantId, String shareMode) {
        String url = baseUrl + "/api/conversations/" + conversationId + "/share";
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("shareMode", shareMode != null ? shareMode : "read");
            body.put("memoryEnabled", true);
            ResponseEntity<Map> resp = restTemplate.exchange(
                    url, HttpMethod.POST, createEntity(body, tenantId), Map.class);
            return resp.getBody() != null ? resp.getBody() : Map.of();
        } catch (Exception e) {
            log.warn("Failed to enable sharing for conversation {}: {}", conversationId, e.getMessage());
            return Map.of("error", e.getMessage());
        }
    }

    /**
     * Disable sharing on a conversation. Sets shareMode to "off".
     */
    public void disableSharing(String conversationId, String tenantId) {
        String url = baseUrl + "/api/conversations/" + conversationId + "/share";
        try {
            restTemplate.exchange(url, HttpMethod.DELETE, createEntity(null, tenantId), Void.class);
        } catch (Exception e) {
            log.warn("Failed to disable sharing for conversation {}: {}", conversationId, e.getMessage());
        }
    }

    // ==================== MESSAGES ====================

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getConversationMessages(String conversationId, int limit, String tenantId) {
        return getConversationMessages(conversationId, limit, tenantId, null);
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getConversationMessages(String conversationId, int limit, String tenantId,
                                                             String organizationId) {
        // conversation-service exposes paginated reads at GET /{id}/messages/page
        // (page 0 = newest batch, DESC order). There is NO plain /messages GET.
        // Fetch page 0 sized to `limit`, unwrap PagedResponseDto.content, then
        // reverse the DESC page into chronological order (oldest first) - the order
        // every memory/history consumer (agent memory, get_history, sub-agent, widget) expects.
        String url = baseUrl + "/api/conversations/" + conversationId + "/messages/page?page=0&size=" + limit;
        try {
            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                    url, HttpMethod.GET, createEntity(null, tenantId, organizationId),
                    new ParameterizedTypeReference<Map<String, Object>>() {});
            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                Object content = resp.getBody().get("content");
                if (content instanceof List<?> list) {
                    List<Map<String, Object>> messages = new ArrayList<>();
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> map) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> typed = (Map<String, Object>) map;
                            messages.add(typed);
                        }
                    }
                    Collections.reverse(messages); // DESC (newest first) -> chronological (oldest first)
                    return messages;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to fetch messages for conversation {}: {}", conversationId, e.getMessage());
        }
        return List.of();
    }

    public void saveMessage(String conversationId, String role, String content,
                            String toolCallsJson, String tenantId, String executionId) {
        saveMessage(conversationId, role, content, toolCallsJson, tenantId, executionId, null);
    }

    public void saveMessage(String conversationId, String role, String content,
                            String toolCallsJson, String tenantId, String executionId,
                            String organizationId) {
        String url = baseUrl + "/api/conversations/" + conversationId + "/messages";
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("role", role);
            if (content != null) body.put("content", content);
            if (toolCallsJson != null) body.put("toolCalls", toolCallsJson);
            if (executionId != null) body.put("executionId", executionId);

            restTemplate.exchange(url, HttpMethod.POST, createEntity(body, tenantId, organizationId), Map.class);
            log.debug("Saved {} message to conversation {}", role, conversationId);
        } catch (Exception e) {
            log.warn("Failed to save message to conversation {}: {}", conversationId, e.getMessage());
        }
    }

    public void saveMessage(String conversationId, String role, String content,
                            String toolCallsJson, String tenantId) {
        saveMessage(conversationId, role, content, toolCallsJson, tenantId, null);
    }

    public void saveMessage(String conversationId, String role, String content) {
        saveMessage(conversationId, role, content, null, null, null);
    }

    // ==================== TOOL RESULTS ====================

    @SuppressWarnings("unchecked")
    public String saveToolResult(String conversationId, String tenantId, String toolName,
                                 String toolCallId, boolean success, Long durationMs,
                                 String content, String error, String executionId) {
        return saveToolResult(conversationId, tenantId, toolName, toolCallId, success, durationMs,
            content, error, executionId, null);
    }

    @SuppressWarnings("unchecked")
    public String saveToolResult(String conversationId, String tenantId, String toolName,
                                 String toolCallId, boolean success, Long durationMs,
                                 String content, String error, String executionId,
                                 String organizationId) {
        String url = baseUrl + "/api/tool-results";
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("conversationId", conversationId);
            body.put("toolName", toolName);
            body.put("toolCallId", toolCallId);
            body.put("success", success);
            if (durationMs != null) body.put("durationMs", durationMs);
            if (content != null) body.put("content", content);
            if (error != null) body.put("error", error);
            if (executionId != null) body.put("executionId", executionId);

            ResponseEntity<Map> resp = restTemplate.exchange(
                    url, HttpMethod.POST, createEntity(body, tenantId, organizationId), Map.class);
            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                return (String) resp.getBody().get("id");
            }
        } catch (Exception e) {
            log.warn("Failed to save tool result for {} in conversation {}: {}", toolName, conversationId, e.getMessage());
        }
        return null;
    }

    public String saveToolResult(String conversationId, String tenantId, String toolName,
                                 String toolCallId, boolean success, Long durationMs,
                                 String content, String error) {
        return saveToolResult(conversationId, tenantId, toolName, toolCallId, success, durationMs, content, error, null);
    }

    // ==================== STREAM LIFECYCLE ====================

    /**
     * Register a stream in conversation-service using an existing streamId.
     * Creates the stream metadata hash and conv index in Redis so the
     * snapshot endpoint can find and replay the stream's accumulated state.
     *
     * @param streamId the pre-assigned stream ID (caller controls the ID)
     * @param conversationId the conversation this stream belongs to
     * @param model the LLM model name
     * @param provider the LLM provider (e.g. "workflow", "openai")
     */
    public void registerStream(String streamId, String conversationId, String model, String provider) {
        String url = baseUrl + "/api/internal/streams/register";
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("streamId", streamId);
            body.put("conversationId", conversationId);
            body.put("model", model);
            body.put("provider", provider);

            restTemplate.exchange(url, HttpMethod.POST, createInternalEntity(body), Void.class);
            log.debug("Registered stream {} for conversation {}", streamId, conversationId);
        } catch (Exception e) {
            log.warn("Failed to register stream for conversation {}: {}", conversationId, e.getMessage());
        }
    }

    /**
     * Finalize a stream (mark as COMPLETED, ERROR, etc.) in conversation-service.
     * Sets shorter TTL for cleanup.
     *
     * @param streamId the stream to finalize
     * @param terminalState "COMPLETED" or "ERROR"
     */
    public void finalizeStream(String streamId, String terminalState) {
        String url = baseUrl + "/api/internal/streams/" + streamId + "/finalize";
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("state", terminalState);

            restTemplate.exchange(url, HttpMethod.POST, createInternalEntity(body), Void.class);
            log.debug("Finalized stream {} with state {}", streamId, terminalState);
        } catch (Exception e) {
            log.warn("Failed to finalize stream {}: {}", streamId, e.getMessage());
        }
    }

    private <T> HttpEntity<T> createInternalEntity(T body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // PR16 - forward org context.
        OrgContextHeaderForwarder.forward(headers);
        return new HttpEntity<>(body, headers);
    }

    // ==================== MESSAGE SEARCH ====================

    /**
     * Full-text search across the given conversations via the internal S2S
     * endpoint.
     *
     * <p>The caller MUST pre-resolve {@code conversationIds} according to
     * the agent's permission scope (allowlist, reviewer, etc.) - this client
     * does no permission check. Returns the raw response map: a list of
     * {@code results} hits plus pagination metadata
     * ({@code nextCursor}, {@code hasMore}, {@code returnedCount},
     * {@code scopeTruncated}).
     *
     * @param conversationIds   pre-authorized conversation IDs
     * @param query             FTS query (websearch_to_tsquery syntax)
     * @param since             optional lower bound on created_at (ISO instant) - pass null to skip
     * @param until             optional upper bound - pass null to skip
     * @param roles             role filter (e.g. ["USER","ASSISTANT"]) - pass null to skip
     * @param toolName          optional filter on tool_name - pass null to skip
     * @param includeInactive   if false, conversations with active=false are skipped
     * @param limit             max results (clamped server-side to 50)
     * @param cursor            opaque pagination cursor from previous response - pass null for first page
     * @return server response or empty map on failure
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> searchMessages(
            List<String> conversationIds,
            String query,
            String since,
            String until,
            List<String> roles,
            String toolName,
            boolean includeInactive,
            int limit,
            String cursor) {
        String url = baseUrl + "/api/internal/messages/search";
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("conversationIds", conversationIds);
            body.put("query", query);
            if (since != null) body.put("since", since);
            if (until != null) body.put("until", until);
            if (roles != null && !roles.isEmpty()) body.put("roles", roles);
            if (toolName != null && !toolName.isBlank()) body.put("toolName", toolName);
            body.put("includeInactive", includeInactive);
            body.put("limit", limit);
            if (cursor != null && !cursor.isBlank()) body.put("cursor", cursor);

            ResponseEntity<Map> resp = restTemplate.exchange(
                    url, HttpMethod.POST, createInternalEntity(body), Map.class);
            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                return resp.getBody();
            }
        } catch (HttpClientErrorException.BadRequest e) {
            // Bubble validation errors up to the caller (agent tool) so the
            // LLM gets an actionable message instead of a silent empty list.
            log.debug("searchMessages rejected: {}", e.getResponseBodyAsString());
            Map<String, Object> err = new HashMap<>();
            err.put("error", e.getResponseBodyAsString());
            return err;
        } catch (Exception e) {
            log.warn("searchMessages failed: {}", e.getMessage());
        }
        return Map.of();
    }

    // ==================== HELPER ====================

    private <T> HttpEntity<T> createEntity(T body, String tenantId) {
        return createEntity(body, tenantId, null);
    }

    private <T> HttpEntity<T> createEntity(T body, String tenantId, String organizationId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (tenantId != null) headers.set("X-User-ID", tenantId);
        // PR16 - forward X-Organization-ID / X-Organization-Role from the
        // inbound request to keep workspace context across cross-service hops.
        applyOrgContextHeaders(headers, organizationId);
        return new HttpEntity<>(body, headers);
    }

    private static void applyOrgContextHeaders(HttpHeaders outbound, String organizationId) {
        if (organizationId != null && !organizationId.isBlank()) {
            outbound.set("X-Organization-ID", organizationId);
            return;
        }
        OrgContextHeaderForwarder.forward(outbound);
    }

}
