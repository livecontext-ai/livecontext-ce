package com.apimarketplace.conversation.service.ai;

import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.domain.ToolResult;
import com.apimarketplace.agent.tool.ToolExecutionService;
import com.apimarketplace.conversation.service.ConversationHistoryService;
import com.apimarketplace.conversation.service.ToolResultService;
import com.apimarketplace.conversation.streaming.StreamPubSubService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.apimarketplace.conversation.service.approval.ServiceInfoRegistry;
import com.apimarketplace.conversation.service.approval.ServiceInfo;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tool execution service for conversation-service.
 * Intercepts conversation-specific tools (like set_conversation_title) and executes them locally.
 * Delegates all other tools to orchestrator-service.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "deployment.mode", havingValue = "microservice", matchIfMissing = true)
@Primary
public class ConversationToolExecutionService implements ToolExecutionService {

    private static final String TOOL_SET_TITLE = "set_conversation_title";
    private static final String TOOL_GET_TOOL_RESULT = "get_tool_result";
    private static final String TOOL_REQUEST_CREDENTIAL = "request_credential";

    private final ConversationHistoryService conversationHistoryService;
    private final ToolResultService toolResultService;
    private final StreamPubSubService streamPubSubService;
    private final ToolServiceRouter toolServiceRouter;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${orchestrator.service.url:http://localhost:8099}")
    private String orchestratorUrl;

    @Value("${services.auth-service.url:http://localhost:8083}")
    private String authServiceUrl;

    @Value("${services.agent-service.url:http://localhost:8090}")
    private String agentServiceUrl;

    @Value("${mcp.gateway.url:http://localhost:8083}")
    private String mcpGatewayUrl;

    public ConversationToolExecutionService(ConversationHistoryService conversationHistoryService,
                                            ToolResultService toolResultService,
                                            StreamPubSubService streamPubSubService,
                                            ToolServiceRouter toolServiceRouter) {
        this.conversationHistoryService = conversationHistoryService;
        this.toolResultService = toolResultService;
        this.streamPubSubService = streamPubSubService;
        this.toolServiceRouter = toolServiceRouter;
        this.restTemplate = createToolExecutionRestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Create a RestTemplate with timeouts suitable for tool execution.
     * Read timeout must exceed the max per-tool timeout (currently 120s for web_search)
     * to prevent thread leaks when CompletableFuture.get() times out before the HTTP call.
     */
    private static RestTemplate createToolExecutionRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofMinutes(3)); // Must exceed max tool timeout
        return new RestTemplate(factory);
    }

    @Override
    public ToolResult executeTool(ToolCall toolCall, ToolDefinition toolDefinition,
                                  String tenantId, Map<String, Object> credentials) {
        long startTime = System.currentTimeMillis();
        String toolName = toolCall.toolName();

        log.info("ConversationToolExecutionService executing tool: {} (tenantId: {})", toolName, tenantId);

        try {
            // Handle conversation-specific tools locally
            if (TOOL_SET_TITLE.equals(toolName)) {
                return executeSetConversationTitle(toolCall, tenantId, credentials, startTime);
            }

            if (TOOL_GET_TOOL_RESULT.equals(toolName)) {
                return executeGetToolResult(toolCall, tenantId, credentials, startTime);
            }

            if (TOOL_REQUEST_CREDENTIAL.equals(toolName)) {
                // Use streamId as anti-loop scope: it's unique per LLM agent run /
                // per user message turn. A new user message → new streamId → fresh
                // counter, so the agent can legitimately retry across turns.
                // Falls back to conversationId then tenantId if streamId missing.
                String streamId = credentials != null ? (String) credentials.get("__streamId__") : null;
                String conversationId = credentials != null ? (String) credentials.get("conversationId") : null;
                String loopScope = streamId != null ? streamId : conversationId;
                return executeRequestCredential(toolCall, tenantId, loopScope, startTime);
            }

            // Delegate to orchestrator for core tools, MCP gateway for MCP tools
            boolean isCoreTools = toolDefinition.apiSlug() == null || toolDefinition.toolSlug() == null;

            if (isCoreTools) {
                return executeCoreTools(toolCall, tenantId, credentials, startTime);
            } else {
                return executeMcpTool(toolCall, toolDefinition, tenantId, credentials, startTime);
            }

        } catch (org.springframework.web.client.HttpClientErrorException e) {
            // HTTP 4xx errors - parse response body to extract metadata (iconSlug, toolName)
            long duration = System.currentTimeMillis() - startTime;
            log.error("HTTP error executing tool {}: {} - {}", toolName, e.getStatusCode(), e.getMessage());

            Map<String, Object> metadata = new java.util.HashMap<>();
            metadata.put("source", "http_error");
            String errorMessage = "HTTP error: " + e.getStatusCode();

            try {
                String responseBody = e.getResponseBodyAsString();
                if (responseBody != null && !responseBody.isBlank()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> errorResponse = objectMapper.readValue(responseBody, Map.class);

                    // Extract error message
                    if (errorResponse.containsKey("error")) {
                        errorMessage = String.valueOf(errorResponse.get("error"));
                    }

                    // Extract metadata from error response (iconSlug, toolName for catalog_call)
                    Object responseMetadata = errorResponse.get("metadata");
                    if (responseMetadata instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> metaMap = (Map<String, Object>) responseMetadata;
                        metadata.putAll(metaMap);
                        log.info("🔍 [HTTP ERROR] Extracted metadata from error response: iconSlug={}, toolName={}",
                            metadata.get("iconSlug"), metadata.get("toolName"));
                    }
                }
            } catch (Exception parseError) {
                log.debug("Could not parse HTTP error response: {}", parseError.getMessage());
            }

            return ToolResult.builder()
                .toolCall(toolCall)
                .success(false)
                .error(errorMessage)
                .durationMs(duration)
                .metadata(metadata)
                .build();

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("Error executing tool {}: {}", toolName, e.getMessage(), e);
            return ToolResult.builder()
                .toolCall(toolCall)
                .success(false)
                .error("Execution error: " + e.getMessage())
                .durationMs(duration)
                .build();
        }
    }

    /**
     * Execute set_conversation_title tool locally.
     * The conversationId is passed via credentials, not as a tool argument.
     * tenantId comes from the tool executor framework; orgId from credentials
     * (injected by AgentContextBuilder under {@code __orgId__}).
     */
    private ToolResult executeSetConversationTitle(ToolCall toolCall, String tenantId,
                                                    Map<String, Object> credentials, long startTime) {
        Map<String, Object> args = toolCall.arguments();
        String title = args != null ? (String) args.get("title") : null;
        // Get conversationId + orgId from credentials (injected by AgentContextBuilder)
        String conversationId = credentials != null ? (String) credentials.get("conversationId") : null;
        String organizationId = credentials != null ? (String) credentials.get("__orgId__") : null;

        if (title == null || title.isEmpty()) {
            return ToolResult.builder()
                .toolCall(toolCall)
                .success(false)
                .error("Title is required")
                .durationMs(System.currentTimeMillis() - startTime)
                .build();
        }

        if (conversationId == null || conversationId.isEmpty()) {
            log.error("conversationId not found in credentials for set_conversation_title tool");
            return ToolResult.builder()
                .toolCall(toolCall)
                .success(false)
                .error("Internal error: conversationId not available")
                .durationMs(System.currentTimeMillis() - startTime)
                .build();
        }

        try {
            // Validate title - if invalid, log and return success anyway (fallback will handle)
            String validationError = validateTitle(title);
            if (validationError != null) {
                log.warn("Invalid title ignored (fallback will handle): '{}' - reason: {}",
                    title.length() > 100 ? title.substring(0, 100) + "..." : title, validationError);
                // Return success to avoid agent retry - fallback title will be used
                long duration = System.currentTimeMillis() - startTime;
                Map<String, Object> result = new HashMap<>();
                result.put("success", true);
                result.put("title", "Title pending");
                result.put("conversation_id", conversationId);
                result.put("note", "Title will be set automatically");
                return ToolResult.builder()
                    .toolCall(toolCall)
                    .success(true)
                    .content(objectMapper.writeValueAsString(result))
                    .durationMs(duration)
                    .build();
            }

            // Clean the title
            String cleanTitle = title.trim()
                .replaceAll("^\"|\"$", "")  // Remove surrounding quotes
                .replaceAll("[\\r\\n\\t]", " ")
                .replaceAll("\\s+", " ");

            if (cleanTitle.length() > 50) {
                cleanTitle = cleanTitle.substring(0, 47) + "...";
            }

            // Update in database
            conversationHistoryService.updateConversationTitle(conversationId, tenantId, organizationId, cleanTitle);

            // Emit title_updated streaming event so the frontend updates in real-time
            String streamId = credentials != null ? (String) credentials.get("__streamId__") : null;
            if (streamId != null) {
                streamPubSubService.publishTitleUpdated(streamId, conversationId, cleanTitle).subscribe();
                log.debug("Published title_updated event for stream={} conversation={}", streamId, conversationId);
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("✅ [TOOL] set_conversation_title executed: '{}' for conversation: {}", cleanTitle, conversationId);

            // Return success with the title for the agent to know
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("title", cleanTitle);
            result.put("conversation_id", conversationId);

            return ToolResult.builder()
                .toolCall(toolCall)
                .success(true)
                .content(objectMapper.writeValueAsString(result))
                .durationMs(duration)
                .build();

        } catch (Exception e) {
            log.error("Failed to set conversation title: {}", e.getMessage(), e);
            return ToolResult.builder()
                .toolCall(toolCall)
                .success(false)
                .error("Failed to set title: " + e.getMessage())
                .durationMs(System.currentTimeMillis() - startTime)
                .build();
        }
    }

    /**
     * Execute get_tool_result tool locally.
     * Retrieves the full content of a previous tool execution from the database.
     */
    private ToolResult executeGetToolResult(ToolCall toolCall, String tenantId,
                                             Map<String, Object> credentials, long startTime) {
        Map<String, Object> args = toolCall.arguments();
        String toolCallId = args != null ? (String) args.get("tool_call_id") : null;

        if (toolCallId == null || toolCallId.isEmpty()) {
            return ToolResult.builder()
                .toolCall(toolCall)
                .success(false)
                .error("tool_call_id is required")
                .durationMs(System.currentTimeMillis() - startTime)
                .build();
        }

        try {
            // Org-aware lookup - credentials.__orgId__ populated by AgentContextBuilder.
            // Lets a teammate-run agent loop fetch tool results from the same org
            // conversation even when the row's tenant_id is the conversation owner.
            String organizationId = credentials != null ? (String) credentials.get("__orgId__") : null;
            var toolResultOpt = toolResultService.getByToolCallId(toolCallId, tenantId, organizationId);

            if (toolResultOpt.isEmpty()) {
                return ToolResult.builder()
                    .toolCall(toolCall)
                    .success(false)
                    .error("Tool result not found for id: " + toolCallId)
                    .durationMs(System.currentTimeMillis() - startTime)
                    .build();
            }

            var savedResult = toolResultOpt.get();
            long duration = System.currentTimeMillis() - startTime;
            log.info("✅ [TOOL] get_tool_result executed for toolCallId: {} ({}ms)", toolCallId, duration);

            Map<String, Object> result = new HashMap<>();
            result.put("tool_call_id", toolCallId);
            result.put("tool_name", savedResult.getToolName());
            result.put("success", savedResult.isSuccess());
            result.put("content", savedResult.getContentFull());
            if (savedResult.getErrorMessage() != null) {
                result.put("error", savedResult.getErrorMessage());
            }

            return ToolResult.builder()
                .toolCall(toolCall)
                .success(true)
                .content(objectMapper.writeValueAsString(result))
                .durationMs(duration)
                .build();

        } catch (Exception e) {
            log.error("Failed to get tool result: {}", e.getMessage(), e);
            return ToolResult.builder()
                .toolCall(toolCall)
                .success(false)
                .error("Failed to get tool result: " + e.getMessage())
                .durationMs(System.currentTimeMillis() - startTime)
                .build();
        }
    }

    /**
     * Execute request_credential tool locally.
     * This tool allows the agent to request batch approval for external services.
     * The result includes metadata that triggers streaming event emission in the callback.
     *
     * Accepts two formats for the 'services' parameter:
     * 1. Simple list of service types: ["gmail", "slack"]
     * 2. Full objects (backward compat): [{"serviceType": "gmail", "serviceName": "Gmail"}]
     *
     * When simple strings are provided, ServiceInfoRegistry enriches them with
     * full display info (serviceName, iconSlug).
     *
     * SILENT ERROR: If credentials already exist for ALL requested services,
     * returns an error to the LLM (so it knows) but does NOT show any popup
     * to the user (no serviceApprovalRequested metadata).
     */
    @SuppressWarnings("unchecked")
    private ToolResult executeRequestCredential(ToolCall toolCall, String tenantId, String loopScope, long startTime) {
        Map<String, Object> args = toolCall.arguments();
        String reason = args != null ? (String) args.get("reason") : null;

        // Parse services - handle different input formats
        List<Map<String, Object>> services = new ArrayList<>();
        Object servicesArg = args != null ? args.get("services") : null;

        if (servicesArg == null) {
            return ToolResult.builder()
                .toolCall(toolCall)
                .success(false)
                .error("'services' parameter is required")
                .durationMs(System.currentTimeMillis() - startTime)
                .build();
        }

        try {
            // Handle services as List
            if (servicesArg instanceof List) {
                List<?> servicesList = (List<?>) servicesArg;
                for (Object item : servicesList) {
                    if (item instanceof Map) {
                        // Already a Map - use directly but enrich if missing fields
                        Map<String, Object> serviceMap = (Map<String, Object>) item;
                        services.add(enrichServiceMap(serviceMap));
                    } else if (item instanceof String) {
                        String strItem = (String) item;
                        // Check if it's a JSON object string or simple service type
                        if (strItem.startsWith("{")) {
                            // JSON object string - parse and enrich
                            Map<String, Object> parsed = objectMapper.readValue(strItem, Map.class);
                            services.add(enrichServiceMap(parsed));
                        } else {
                            // Simple service type string (e.g., "gmail")
                            // Use ServiceInfoRegistry to get full info
                            services.add(serviceTypeToMap(strItem));
                        }
                    } else {
                        log.warn("Unknown service item type: {}", item != null ? item.getClass().getName() : "null");
                    }
                }
            } else if (servicesArg instanceof String) {
                String jsonStr = (String) servicesArg;
                if (jsonStr.startsWith("[")) {
                    // JSON array string
                    List<?> parsed = objectMapper.readValue(jsonStr, List.class);
                    for (Object item : parsed) {
                        if (item instanceof Map) {
                            services.add(enrichServiceMap((Map<String, Object>) item));
                        } else if (item instanceof String) {
                            // Simple service type in JSON array
                            services.add(serviceTypeToMap((String) item));
                        }
                    }
                } else {
                    // Single service type string
                    services.add(serviceTypeToMap(jsonStr));
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse services: {}", e.getMessage());
            return ToolResult.builder()
                .toolCall(toolCall)
                .success(false)
                .error("Invalid 'services' format: " + e.getMessage())
                .durationMs(System.currentTimeMillis() - startTime)
                .build();
        }

        if (services.isEmpty()) {
            return ToolResult.builder()
                .toolCall(toolCall)
                .success(false)
                .error("At least one service is required in the 'services' array. " +
                       "Example: services=[\"gmail\", \"slack\"], reason=\"To send emails and notify on Slack\"")
                .durationMs(System.currentTimeMillis() - startTime)
                .build();
        }

        // `force=true` is the LLM's explicit "I considered this, the user really needs to
        // (re)connect" signal - it is the ONLY path that turns a request_credential call
        // on a pre-existing credential into a warning/reconnect card. Without it, an
        // existing credential triggers a structured silent feedback to the LLM only.
        // Tolerate both Boolean and stringified ("true"/"false") forms - different LLMs
        // serialize tool args differently.
        boolean isForced = parseBooleanArg(args != null ? args.get("force") : null);

        // Check existence per service (always - we need it both to filter the normal
        // path and to drive needsAttention on the forced path).
        List<Map<String, Object>> servicesNeedingCredentials = new ArrayList<>();
        List<Map<String, Object>> servicesAlreadyConfigured = new ArrayList<>();
        boolean anyExistingForceBlocked = false;
        for (Map<String, Object> service : services) {
            String serviceType = (String) service.get("serviceType");
            Map<String, Object> existing = serviceType != null ? findExistingCredential(serviceType, tenantId) : null;
            if (existing != null) {
                Map<String, Object> entry = new HashMap<>(service);
                entry.put("lastUsedAt", existing.getOrDefault("last_used", existing.get("updated_at")));
                entry.put("credentialId", existing.get("id"));
                servicesAlreadyConfigured.add(entry);
            } else {
                servicesNeedingCredentials.add(service);
            }
        }

        if (isForced) {
            // Anti-loop guard: if the user just (re)connected this service via a previous
            // forced request and the agent is asking again within the cooldown window, it
            // means reconnection didn't actually fix the underlying problem (likely a
            // scope/quota issue, not a token issue). Block the second nag and tell the
            // LLM to try a different approach.
            // Anti-loop scope is per-stream (per LLM agent run / per user message turn):
            // a new user message creates a new streamId → fresh counter, so the agent can
            // legitimately retry across turns. Within a single turn, the guard prevents
            // the LLM from looping force=true on a service that didn't fix the problem.
            // Falls back to tenantId if no streamId is available.
            if (loopScope == null) {
                loopScope = tenantId;
            }
            List<String> blocked = new ArrayList<>();
            for (Map<String, Object> svc : servicesAlreadyConfigured) {
                String serviceType = (String) svc.get("serviceType");
                if (serviceType != null && isForceLoopBlocked(loopScope, serviceType)) {
                    blocked.add(serviceType);
                }
            }
            if (!blocked.isEmpty()) {
                long duration = System.currentTimeMillis() - startTime;
                log.warn("🚫 [FORCE-LOOP] Blocking repeated forced reconnect for {} (scope={}) - likely not a credential issue",
                    blocked, loopScope);
                Map<String, Object> meta = new HashMap<>();
                meta.put("silentError", true);
                meta.put("forceLoopBlocked", blocked);
                return ToolResult.builder()
                    .toolCall(toolCall)
                    .success(false)
                    .error("You already requested a forced reconnect for " + String.join(", ", blocked) +
                           " in this conversation and it did not resolve the issue. The 401/403 you observed is " +
                           "probably NOT a credential problem (e.g. wrong scope, quota, account, or endpoint). " +
                           "Do not call request_credential again for these services - try a different tool, " +
                           "narrow the scope, or report the failure to the user.")
                    .durationMs(duration)
                    .metadata(meta)
                    .build();
            }

            log.info("🔄 [FORCE] Forced credential request - existing services will be flagged needsAttention=true: {}",
                servicesAlreadyConfigured.stream().map(s -> s.get("serviceType")).toList());
            // On the forced path, services list keeps ALL requested services (existing + missing).
            // Existing ones drive the warning card; missing ones drive the normal connect flow.
            // Record the force timestamp NOW so a subsequent forced call within the cooldown is blocked.
            for (Map<String, Object> svc : servicesAlreadyConfigured) {
                String serviceType = (String) svc.get("serviceType");
                if (serviceType != null) {
                    recordForceRequest(loopScope, serviceType);
                }
            }
        } else {
            // Normal path: if ALL services already exist, return a HARD "already exists"
            // error (no card) so the agent cannot keep showing the user an approval card -
            // it must deliberately re-call with force=true to surface a reconnect card.
            // The user sees nothing on this path.
            if (servicesNeedingCredentials.isEmpty()) {
                long duration = System.currentTimeMillis() - startTime;
                List<String> names = servicesAlreadyConfigured.stream()
                    .map(s -> (String) s.get("serviceType")).toList();
                log.info("⛔ [EXISTS-ERROR] All requested services already configured: {} - returning already-exists error to LLM (no card)",
                    names);

                // Return a HARD error (success=false) so the agent treats "already exists"
                // as a blocker and does NOT spam the user with an approval card on every
                // turn. The actionable guidance lives in `error` (NOT metadata) because the
                // Claude Code CLI bridge forwards only `content` and `error` to the LLM
                // history - so the LLM still learns it can deliberately escalate with
                // force=true when (and only when) it is confident the token was rejected.
                Map<String, Object> meta = new HashMap<>();
                meta.put("silentError", true);   // tells frontend NOT to render a service-approval card
                meta.put("exists", true);
                meta.put("services", servicesAlreadyConfigured);
                // Enrich for the (rare) case where the tool result IS shown in the activity
                // feed - gives it a proper name/icon instead of falling back to "#1".
                String firstSlug = (String) servicesAlreadyConfigured.get(0).get("iconSlug");
                String firstName = (String) servicesAlreadyConfigured.get(0).get("serviceName");
                if (firstSlug != null) meta.put("iconSlug", firstSlug);
                if (firstName != null) {
                    meta.put("toolName", servicesAlreadyConfigured.size() == 1
                        ? "Credential check: " + firstName
                        : "Credential check: " + firstName + " + " + (servicesAlreadyConfigured.size() - 1) + " more");
                }

                return ToolResult.builder()
                    .toolCall(toolCall)
                    .success(false)
                    .error("Credentials already exist for: " + String.join(", ", names) + ". " +
                           "No reconnect card was shown to the user - do NOT call request_credential " +
                           "again unless you are sure the token itself was rejected (the tool failed " +
                           "with 401/403 saying \"expired\", \"invalid_grant\", or \"revoked\"). A 401/403 " +
                           "is often a scope, account, quota, or endpoint mismatch - in that case try a " +
                           "different tool or narrow the scope instead. If you ARE sure the credential " +
                           "must be reconnected, retry with force=true (boolean): " +
                           "request_credential(services=[\"" + names.get(0) + "\"], reason=\"token expired\", force=true).")
                    .durationMs(duration)
                    .metadata(meta)
                    .build();
            }

            // Some services exist, some don't → proceed only with the missing ones (existing behavior).
            if (!servicesAlreadyConfigured.isEmpty()) {
                log.info("🔑 [PARTIAL] Some services already have credentials: {}. Requesting only: {}",
                    servicesAlreadyConfigured.stream().map(s -> s.get("serviceType")).toList(),
                    servicesNeedingCredentials.stream().map(s -> s.get("serviceType")).toList());
            }
            services = servicesNeedingCredentials;
        }

        try {
            // Build result with services list for callback to process
            Map<String, Object> result = new HashMap<>();
            result.put("status", "credentials_required");
            result.put("services", services);
            result.put("reason", reason != null ? reason : "");
            result.put("message", "Credential connection requested. Do NOT call catalog(action='execute') for these services until connected.");

            long duration = System.currentTimeMillis() - startTime;
            log.info("✅ [TOOL] request_credential executed: {} services", services.size());

            // Extract first service's icon for display in tool card
            String iconSlug = null;
            String displayToolName = null;
            if (!services.isEmpty()) {
                Map<String, Object> firstService = services.get(0);
                iconSlug = (String) firstService.get("iconSlug");
                displayToolName = (String) firstService.get("serviceName");
                if (services.size() > 1) {
                    displayToolName = displayToolName + " + " + (services.size() - 1) + " more";
                }
            }

            // Build metadata with services list AND display info
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("serviceApprovalRequested", true);
            metadata.put("services", services);
            metadata.put("reason", reason != null ? reason : "");
            // Add icon and display name for tool card (like catalog_call does)
            if (iconSlug != null) {
                metadata.put("iconSlug", iconSlug);
            }
            if (displayToolName != null) {
                metadata.put("toolName", displayToolName);  // Use "toolName" key for consistency with catalog_call
            }

            // needsAttention drives the amber "reconnect existing credential" card.
            // Set it ONLY when the LLM explicitly forced reconnection on a service that
            // already has a credential. This is the single, deterministic gate.
            // Also expose the per-service list so the frontend can paint a mixed batch
            // correctly (existing → amber reconnect, missing → normal connect).
            if (isForced && !servicesAlreadyConfigured.isEmpty()) {
                metadata.put("needsAttention", true);
                metadata.put("needsAttentionServices",
                    servicesAlreadyConfigured.stream().map(s -> s.get("serviceType")).toList());
            }

            // Return success with metadata that triggers streaming event in AgentStreamingCallbackFactory
            return ToolResult.builder()
                .toolCall(toolCall)
                .success(true)
                .content(objectMapper.writeValueAsString(result))
                .durationMs(duration)
                .metadata(metadata)
                .build();

        } catch (Exception e) {
            log.error("Failed to process service approval request: {}", e.getMessage(), e);
            return ToolResult.builder()
                .toolCall(toolCall)
                .success(false)
                .error("Failed to process request: " + e.getMessage())
                .durationMs(System.currentTimeMillis() - startTime)
                .build();
        }
    }

    /**
     * Look up the most recent credential for the given service. Returns the raw map
     * (with `id`, `last_used`, `updated_at`, …) or null if none exists.
     *
     * Used by request_credential to (a) decide between the silent-feedback and
     * forced-warning paths, and (b) enrich the structured silent error sent to the LLM.
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> findExistingCredential(String serviceType, String tenantId) {
        if (tenantId == null || serviceType == null) {
            return null;
        }
        try {
            // Hits auth-service's INTERNAL endpoint directly (no gateway auth filter,
            // takes userId/integration as query params). The previous attempt to call
            // /api/credentials/by-integration on orchestrator returned 500 because that
            // route does not exist there - credentials live in auth-service.
            String url = authServiceUrl + "/api/internal/credentials/default?userId="
                + tenantId + "&integration=" + serviceType.toLowerCase();
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return (Map<String, Object>) response.getBody();
            }
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound nf) {
            // 404 = no credential for this integration - normal "missing" path
            return null;
        } catch (Exception e) {
            log.warn("Failed to check credentials for service '{}': {}", serviceType, e.getMessage());
        }
        return null;
    }

    // ── Anti-loop guard for forced credential reconnects ────────────────────────────
    // Per (scope, serviceType) cooldown where scope is conversationId when available
    // (preferred) or tenantId as fallback. If the agent issues request_credential(force=true)
    // for the same service twice within FORCE_LOOP_COOLDOWN, the second call is blocked
    // and the LLM is told the issue is probably not a credential problem. Prevents the
    // "user reconnects → agent re-asks immediately" loop.
    //
    // JVM-local: if conversation-service scales horizontally with sticky routing this
    // is fine; with non-sticky routing the guard becomes best-effort. Acceptable for a
    // 5-minute window - promote to Redis if scaling demands it.
    private static final Duration FORCE_LOOP_COOLDOWN = Duration.ofMinutes(5);
    static final ConcurrentHashMap<String, Instant> RECENT_FORCE_REQUESTS = new ConcurrentHashMap<>();

    private static String forceKey(String scope, String serviceType) {
        return scope + "|" + serviceType.toLowerCase();
    }

    private boolean isForceLoopBlocked(String scope, String serviceType) {
        Instant last = RECENT_FORCE_REQUESTS.get(forceKey(scope, serviceType));
        if (last == null) {
            return false;
        }
        if (Duration.between(last, Instant.now()).compareTo(FORCE_LOOP_COOLDOWN) > 0) {
            RECENT_FORCE_REQUESTS.remove(forceKey(scope, serviceType));
            return false;
        }
        return true;
    }

    private static boolean parseBooleanArg(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean b) return b;
        if (value instanceof String s) return Boolean.parseBoolean(s.trim());
        if (value instanceof Number n) return n.intValue() != 0;
        return false;
    }

    private void recordForceRequest(String scope, String serviceType) {
        RECENT_FORCE_REQUESTS.put(forceKey(scope, serviceType), Instant.now());
        // Opportunistic cleanup of stale entries to keep the map bounded.
        if (RECENT_FORCE_REQUESTS.size() > 1000) {
            Instant cutoff = Instant.now().minus(FORCE_LOOP_COOLDOWN);
            RECENT_FORCE_REQUESTS.entrySet().removeIf(e -> e.getValue().isBefore(cutoff));
        }
    }

    /**
     * Convert a simple service type string to a full service map using ServiceInfoRegistry.
     */
    private Map<String, Object> serviceTypeToMap(String serviceType) {
        ServiceInfo info = ServiceInfoRegistry.getServiceInfo(serviceType);
        Map<String, Object> map = new HashMap<>();
        map.put("serviceType", info.serviceType());
        map.put("serviceName", info.serviceName());
        if (info.iconSlug() != null) {
            map.put("iconSlug", info.iconSlug());
        }
        if (info.description() != null) {
            map.put("description", info.description());
        }
        return map;
    }

    /**
     * Enrich a service map with missing fields from ServiceInfoRegistry.
     */
    private Map<String, Object> enrichServiceMap(Map<String, Object> serviceMap) {
        String serviceType = (String) serviceMap.get("serviceType");
        if (serviceType == null) {
            return serviceMap; // Can't enrich without serviceType
        }

        ServiceInfo info = ServiceInfoRegistry.getServiceInfo(serviceType);

        // Only fill missing fields
        Map<String, Object> enriched = new HashMap<>(serviceMap);
        if (!enriched.containsKey("serviceName") || enriched.get("serviceName") == null) {
            enriched.put("serviceName", info.serviceName());
        }
        if (!enriched.containsKey("iconSlug") || enriched.get("iconSlug") == null) {
            if (info.iconSlug() != null) {
                enriched.put("iconSlug", info.iconSlug());
            }
        }
        return enriched;
    }

    /**
     * Execute a core tool via the appropriate microservice.
     * Routes to the owning service directly (agent-service, datasource-service, interface-service)
     * or falls back to orchestrator-service for remaining tools.
     */
    private ToolResult executeCoreTools(ToolCall toolCall, String tenantId, Map<String, Object> credentials, long startTime) {
        String serviceName = toolServiceRouter.getServiceName(toolCall.toolName());
        log.info("Executing CORE tool: {} via {} (tenantId: {})", toolCall.toolName(), serviceName, tenantId);

        Map<String, Object> request = new HashMap<>();
        request.put("tool", toolCall.toolName());
        request.put("parameters", toolCall.arguments());
        if (tenantId != null) {
            request.put("tenantId", tenantId);
        }
        // Pass conversationId for cooldown detection
        if (credentials != null && credentials.get("conversationId") != null) {
            request.put("conversationId", credentials.get("conversationId"));
        }
        // Pass approved services for service approval checks
        if (credentials != null && credentials.get("__approvedServices__") != null) {
            request.put("approvedServices", credentials.get("__approvedServices__"));
        }
        // Pass workflow context for workflow guard (blocks init when viewing a workflow)
        if (credentials != null && credentials.get("__viewingWorkflowId__") != null) {
            request.put("viewingWorkflowId", credentials.get("__viewingWorkflowId__"));
            request.put("viewingWorkflowName", credentials.get("__viewingWorkflowName__"));
        }
        // Pass agentId and messageId for web_search interface grouping
        if (credentials != null && credentials.get("__agentId__") != null) {
            request.put("agentId", credentials.get("__agentId__"));
        }
        if (credentials != null && credentials.get("turnId") != null) {
            request.put("messageId", credentials.get("turnId"));
            request.put("turnId", credentials.get("turnId"));
        }
        // Pass streamId and toolCallId for tool callbacks (e.g., websearch screenshots)
        if (credentials != null && credentials.get("__streamId__") != null) {
            request.put("streamId", credentials.get("__streamId__"));
        }
        if (credentials != null && credentials.get("__reviewerExecutionId__") != null) {
            request.put("reviewerExecutionId", credentials.get("__reviewerExecutionId__"));
        }
        if (toolCall.id() != null) {
            request.put("toolCallId", toolCall.id());
        }
        // Pass agent tool/workflow restrictions for enforcement at orchestrator level
        if (credentials != null && credentials.get("__allowedToolIds__") != null) {
            request.put("allowedToolIds", credentials.get("__allowedToolIds__"));
        }
        if (credentials != null && credentials.get("__allowedWorkflowIds__") != null) {
            request.put("allowedWorkflowIds", credentials.get("__allowedWorkflowIds__"));
        }
        if (credentials != null && credentials.get("__allowedApplicationIds__") != null) {
            request.put("allowedApplicationIds", credentials.get("__allowedApplicationIds__"));
        }
        if (credentials != null && credentials.get("__allowedTableIds__") != null) {
            request.put("allowedTableIds", credentials.get("__allowedTableIds__"));
        }
        if (credentials != null && credentials.get("__allowedInterfaceIds__") != null) {
            request.put("allowedInterfaceIds", credentials.get("__allowedInterfaceIds__"));
        }
        if (credentials != null && credentials.get("__allowedAgentIds__") != null) {
            request.put("allowedAgentIds", credentials.get("__allowedAgentIds__"));
        }
        if (credentials != null && credentials.get("__allowedFileIds__") != null) {
            request.put("allowedFileIds", credentials.get("__allowedFileIds__"));
        }
        // Forward access modes (read/write per resource)
        for (String key : List.of("__tableAccessMode__", "__workflowAccessMode__", "__interfaceAccessMode__",
                "__agentAccessMode__", "__applicationAccessMode__", "__skillAccessMode__", "__fileAccessMode__")) {
            if (credentials != null && credentials.get(key) != null) {
                request.put(key.substring(2, key.length() - 2), credentials.get(key)); // strip __ prefix/suffix
            }
        }
        // Forward org context for org-aware resource filtering
        if (credentials != null && credentials.get("__orgId__") != null) {
            request.put("orgId", credentials.get("__orgId__"));
        }
        if (credentials != null && credentials.get("__orgRole__") != null) {
            request.put("orgRole", credentials.get("__orgRole__"));
        }

        String url = toolServiceRouter.getExecuteUrl(toolCall.toolName());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (tenantId != null) {
            headers.set("X-Tenant-Id", tenantId);
            headers.set("X-User-ID", tenantId);
        }
        // 2026-05-21 prod fix: forward X-Organization-ID + X-Organization-Role so
        // downstream tool services (interface, agent, datasource, ...) can resolve
        // the caller's workspace via TenantResolver.currentRequestOrganizationId()
        // on the request thread. Without these headers, every org-aware tool
        // controller drops to personal-scope and either (a) throws
        // "organizationId required after V261" (e.g. InterfaceService.findInScope)
        // or (b) silently rejects org-scoped rows in canAccess deny-list (the
        // 2026-05-21 "Access to this agent is restricted" prod incident).
        // Sourced from credentials.__orgId__/__orgRole__ - same source already
        // used to populate the request body fields at the top of this method.
        if (credentials != null) {
            Object orgIdCred = credentials.get("__orgId__");
            if (orgIdCred instanceof String orgId && !orgId.isBlank()) {
                headers.set("X-Organization-ID", orgId);
            }
            Object orgRoleCred = credentials.get("__orgRole__");
            if (orgRoleCred instanceof String orgRole && !orgRole.isBlank()) {
                headers.set("X-Organization-Role", orgRole);
            }
            // Forward the platform role set (X-User-Roles) so agent-service tool
            // modules can resolve admin status - AgentHelpModule uses it to hide
            // admin-only CLI-bridge models from non-admin agents. Sourced from the
            // credentials map (captured from the chat request) so it survives the
            // async streaming threads where no servlet request is bound.
            Object userRolesCred = credentials.get("__userRoles__");
            if (userRolesCred instanceof String userRoles && !userRoles.isBlank()) {
                headers.set("X-User-Roles", userRoles);
            }
        }

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

        @SuppressWarnings("unchecked")
        ResponseEntity<Map> response = restTemplate.exchange(
            url, HttpMethod.POST, entity, Map.class
        );

        long duration = System.currentTimeMillis() - startTime;

        // Parse response even for non-200 status codes (e.g., 400) to extract metadata (iconSlug, etc.)
        if (response.getBody() != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = response.getBody();

            // DEBUG: Log response body for workflow
            if ("workflow".equals(toolCall.toolName())) {
                log.info("📦 [WORKFLOW] Response from orchestrator - metadata: {}", body.get("metadata"));
            }

            return parseExecutionResponse(toolCall, body, duration);
        } else {
            return ToolResult.builder()
                .toolCall(toolCall)
                .success(false)
                .error("Unexpected response: " + response.getStatusCode())
                .durationMs(duration)
                .build();
        }
    }

    /**
     * Execute an MCP tool via MCP Gateway.
     */
    private ToolResult executeMcpTool(ToolCall toolCall, ToolDefinition toolDefinition,
                                      String tenantId, Map<String, Object> credentials, long startTime) {
        log.info("Executing MCP tool: {} (API: {}, Tool: {})",
            toolCall.toolName(), toolDefinition.apiSlug(), toolDefinition.toolSlug());

        Map<String, Object> request = new HashMap<>();
        request.put("apiSlug", toolDefinition.apiSlug());
        request.put("toolSlug", toolDefinition.toolSlug());
        request.put("toolId", toolDefinition.id());
        request.put("parameters", toolCall.arguments());

        if (tenantId != null) {
            request.put("tenantId", tenantId);
        }

        if (credentials != null && !credentials.isEmpty()) {
            request.put("credentials", credentials);
        }

        String url = mcpGatewayUrl + "/api/mcp/execute";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (tenantId != null) {
            headers.set("X-Tenant-Id", tenantId);
        }

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

        @SuppressWarnings("unchecked")
        ResponseEntity<Map> response = restTemplate.exchange(
            url, HttpMethod.POST, entity, Map.class
        );

        long duration = System.currentTimeMillis() - startTime;

        // Parse response even for non-200 status codes (e.g., 400) to extract metadata (iconSlug, etc.)
        if (response.getBody() != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = response.getBody();
            return parseExecutionResponse(toolCall, body, duration);
        } else {
            return ToolResult.builder()
                .toolCall(toolCall)
                .success(false)
                .error("Unexpected response: " + response.getStatusCode())
                .durationMs(duration)
                .build();
        }
    }

    @Override
    public boolean isToolAvailable(ToolDefinition toolDefinition, String tenantId) {
        // Conversation-specific tools are always available
        if (TOOL_SET_TITLE.equals(toolDefinition.name()) ||
            TOOL_GET_TOOL_RESULT.equals(toolDefinition.name()) ||
            TOOL_REQUEST_CREDENTIAL.equals(toolDefinition.name())) {
            return true;
        }

        // Check MCP gateway for MCP tools
        if (toolDefinition.apiSlug() != null && toolDefinition.toolSlug() != null) {
            try {
                String url = mcpGatewayUrl + "/api/mcp/tools/" + toolDefinition.apiSlug() +
                    "/" + toolDefinition.toolSlug() + "/available";

                HttpHeaders headers = new HttpHeaders();
                if (tenantId != null) {
                    headers.set("X-Tenant-Id", tenantId);
                }

                HttpEntity<Void> entity = new HttpEntity<>(headers);

                ResponseEntity<Boolean> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, Boolean.class
                );

                return response.getStatusCode() == HttpStatus.OK &&
                       Boolean.TRUE.equals(response.getBody());

            } catch (Exception e) {
                log.warn("Error checking tool availability: {}", e.getMessage());
                return false;
            }
        }

        // Core tools are assumed available
        return true;
    }

    @SuppressWarnings("unchecked")
    private ToolResult parseExecutionResponse(ToolCall toolCall, Map<String, Object> response,
                                              long duration) {
        // Check success at top level first
        Boolean success = (Boolean) response.get("success");
        String error = (String) response.get("error");

        // Also check inside "result" object for nested success/error (common pattern)
        Object resultObj = response.get("result");
        if (resultObj instanceof Map) {
            Map<String, Object> resultMap = (Map<String, Object>) resultObj;
            // If top-level success is null or true, check nested result
            if (success == null || success) {
                Boolean nestedSuccess = (Boolean) resultMap.get("success");
                if (nestedSuccess != null && !nestedSuccess) {
                    success = false;
                    // Get error from nested result if not at top level
                    if (error == null) {
                        error = (String) resultMap.get("error");
                    }
                }
            }
        }

        // Fallback if success still null
        if (success == null) {
            success = error == null && !response.containsKey("error");
        }

        String content = null;

        if (success) {
            Object result = response.get("result");
            if (result == null) {
                result = response.get("data");
            }
            if (result == null) {
                result = response.get("output");
            }

            if (result != null) {
                try {
                    content = objectMapper.writeValueAsString(result);
                } catch (Exception e) {
                    content = result.toString();
                }
            } else {
                content = "{}";
            }
        } else {
            // Error already extracted above, just get message fallback
            if (error == null) {
                error = (String) response.get("message");
            }
            if (error == null) {
                error = "Unknown error";
            }
        }

        // Build metadata - include orchestrator metadata if present
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source", "conversation_service");

        // Pass through metadata from orchestrator response (includes iconSlug, toolName, label, etc.)
        Object responseMetadata = response.get("metadata");
        String iconSlug = null;
        String toolName = null;
        if (responseMetadata instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> orchestratorMetadata = (Map<String, Object>) responseMetadata;
            metadata.putAll(orchestratorMetadata);
            iconSlug = (String) orchestratorMetadata.get("iconSlug");
            toolName = (String) orchestratorMetadata.get("toolName");
            log.info("📦 [TOOL_EXEC] Received metadata from orchestrator: iconSlug={}, toolName={}, label={}",
                iconSlug, toolName, orchestratorMetadata.get("label"));
        }

        // Auth errors (401/403) are now handled at the source in CatalogToolsProvider
        // which returns proper error with JIT hint for LLM

        return ToolResult.builder()
            .toolCall(toolCall)
            .success(success)
            .content(content)
            .error(error)
            .durationMs(duration)
            .metadata(metadata)
            .build();
    }

    /**
     * Validate a conversation title before accepting it.
     * Returns null if valid, or an error message if invalid.
     */
    private String validateTitle(String title) {
        if (title == null || title.isBlank()) {
            return "Title cannot be empty";
        }

        // Reject if too long (raw input > 200 chars is suspicious)
        if (title.length() > 200) {
            return "Title too long (max 200 chars)";
        }

        // Reject file paths (Windows or Unix)
        if (title.contains(":\\") || title.contains("C:/") ||
            title.startsWith("/Users/") || title.startsWith("/home/") ||
            title.contains(".jar") || title.contains(".exe") || title.contains(".class")) {
            return "Title looks like a file path";
        }

        // Reject Java classpath-like strings
        if (title.contains(";C:") || title.contains("-classpath") ||
            title.contains("-Dspring.") || title.contains("-Djava.") ||
            title.contains("org.springframework") || title.contains("com.apimarketplace")) {
            return "Title looks like a Java classpath or command";
        }

        // Reject if it contains too many special characters (likely code/garbage)
        int specialCount = 0;
        for (char c : title.toCharArray()) {
            if (!Character.isLetterOrDigit(c) && !Character.isWhitespace(c) &&
                c != '-' && c != '\'' && c != '"' && c != ',' && c != '.' &&
                c != '?' && c != '!' && c != ':' && c != '-') {
                specialCount++;
            }
        }
        if (specialCount > title.length() / 3) {
            return "Title contains too many special characters";
        }

        return null; // Valid
    }
}
