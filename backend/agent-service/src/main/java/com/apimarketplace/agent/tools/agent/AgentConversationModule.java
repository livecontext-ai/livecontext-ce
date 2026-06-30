package com.apimarketplace.agent.tools.agent;

import com.apimarketplace.agent.domain.AgentEntity;
import com.apimarketplace.agent.domain.Message;
import com.apimarketplace.agent.config.ToolAccessControl;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.service.AgentService;
import com.apimarketplace.conversation.client.ConversationClient;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.agent.tools.common.ToolModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

import static com.apimarketplace.agent.tools.common.ToolParamUtils.*;
import com.apimarketplace.agent.tools.ToolErrorCode;

/**
 * Conversation module for the agent tool.
 * Handles: get_history, share actions.
 *
 * get_history: loads the last N user/assistant messages from a sub-agent's
 * conversation (or the calling agent's own conversation).
 *
 * share: enables sharing on an agent's conversation and returns a public link.
 */
@Slf4j
@Component
public class AgentConversationModule implements ToolModule {

    private static final int DEFAULT_HISTORY_LIMIT = 20;
    private static final int DEFAULT_SEARCH_LIMIT = 20;
    private static final int MAX_SEARCH_LIMIT = 50;
    /** Mirror of conversation-service's MAX_CONVERSATION_IDS - keep in sync.
     *  The internal endpoint rejects requests above this with HTTP 400, so we
     *  truncate proactively and surface {@code scope_truncated=true} instead
     *  of forwarding a doomed request. */
    private static final int MAX_CONVERSATION_IDS = 200;
    private static final Set<String> HANDLED_ACTIONS = Set.of(
            "get_history", "share", "unshare", "refresh_share", "search_messages");

    /** Macro-scope for search_messages. */
    private static final Set<String> VALID_SCOPES = Set.of("self", "children", "all_visible");

    /**
     * Result of resolving a macro-scope into concrete conversation IDs.
     *
     * @param conversationIds  authorized conversation IDs to search within
     * @param truncated        true when the scope had to be clipped to
     *                          {@value #MAX_CONVERSATION_IDS} entries (older
     *                          conversations were dropped). Surfaced to the
     *                          caller as {@code scope_truncated=true}.
     */
    private record ResolvedScope(List<String> conversationIds, boolean truncated) {}

    private final AgentService agentService;
    private final ConversationClient conversationServiceClient;
    private final RestTemplate restTemplate;
    private final String publicationServiceUrl;
    private final String shareBaseUrl;

    public AgentConversationModule(AgentService agentService,
                                    ConversationClient conversationServiceClient,
                                    RestTemplate restTemplate,
                                    @Value("${services.publication-url:http://localhost:8092}") String publicationServiceUrl,
                                    @Value("${app.share-base-url:#{null}}") String shareBaseUrl,
                                    @Value("${app.base-url:http://localhost:3000}") String appBaseUrl) {
        this.agentService = agentService;
        this.conversationServiceClient = conversationServiceClient;
        this.restTemplate = restTemplate;
        this.publicationServiceUrl = publicationServiceUrl;
        this.shareBaseUrl = shareBaseUrl != null ? shareBaseUrl : appBaseUrl;
    }

    @Override
    public List<AgentToolDefinition> getToolDefinitions() {
        return List.of();
    }

    @Override
    public boolean canHandle(String action) {
        return HANDLED_ACTIONS.contains(action);
    }

    @Override
    public Optional<ToolExecutionResult> execute(String action, Map<String, Object> parameters,
                                                  String tenantId, ToolExecutionContext context) {
        if (!canHandle(action)) return Optional.empty();
        var accessDenied = ToolAccessControl.checkWriteAccess(
                context != null ? context.credentials() : null,
                "agent",
                action);
        if (accessDenied.isPresent()) {
            return Optional.of(ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED, accessDenied.get()));
        }

        return Optional.of(switch (action) {
            case "get_history" -> executeGetHistory(parameters, tenantId, context);
            case "share" -> executeShare(parameters, tenantId, context);
            case "unshare" -> executeUnshare(parameters, tenantId, context);
            case "refresh_share" -> executeRefreshShare(parameters, tenantId, context);
            case "search_messages" -> executeSearchMessages(parameters, tenantId, context);
            default -> ToolExecutionResult.failure(ToolErrorCode.INVALID_ENUM_VALUE, "Unknown action: " + action);
        });
    }

    // ==================== Search Messages ====================

    /**
     * Full-text search across conversations the calling agent is authorized to
     * see. The {@code scope} parameter selects the macro-scope:
     * <ul>
     *   <li>{@code self} - only the caller's own conversation.</li>
     *   <li>{@code children} - conversations of agents in the caller's
     *       {@code allowedAgentIds} (declared at agent creation via
     *       {@code toolsConfig.agents}).</li>
     *   <li>{@code all_visible} - union of {@code self} and {@code children}.</li>
     * </ul>
     *
     * <p>An optional {@code agent_id} narrows the search to a single target
     * within the resolved scope; the target must be visible (own id, or in
     * the allowlist) - otherwise the call is rejected.
     *
     * <p>Results are page-able via {@code cursor}; each hit carries its own
     * {@code rank} so the caller can re-sort if needed.
     */
    private ToolExecutionResult executeSearchMessages(Map<String, Object> parameters, String tenantId,
                                                       ToolExecutionContext context) {
        Map<String, Object> p = mergeParams(parameters);

        String query = getStringParam(p, "query");
        if (query == null || query.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "Parameter 'query' is required and must not be blank.");
        }

        String scope = getStringParam(p, "scope");
        if (scope == null || scope.isBlank()) scope = "all_visible";
        if (!VALID_SCOPES.contains(scope)) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Invalid scope '" + scope + "'. Must be one of: " + VALID_SCOPES);
        }

        UUID targetAgentId = getUuidParam(p, "agent_id");
        Integer limit = getIntParam(p, "limit", DEFAULT_SEARCH_LIMIT);
        if (limit == null || limit <= 0) limit = DEFAULT_SEARCH_LIMIT;
        if (limit > MAX_SEARCH_LIMIT) limit = MAX_SEARCH_LIMIT;

        String since = getStringParam(p, "since");
        String until = getStringParam(p, "until");
        String toolName = getStringParam(p, "tool_name");
        String cursor = getStringParam(p, "cursor");

        @SuppressWarnings("unchecked")
        List<String> roles = p.get("roles") instanceof List ? (List<String>) p.get("roles") : null;

        // Resolve the visible conversation set according to the scope.
        ResolvedScope resolved;
        try {
            resolved = resolveSearchScope(scope, targetAgentId, tenantId, context);
        } catch (IllegalArgumentException e) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, e.getMessage());
        }

        if (resolved.conversationIds().isEmpty()) {
            // No visible conversations → return empty result rather than calling
            // the backend (avoids a needless round-trip and lets the caller
            // distinguish "nothing matched" from "backend down").
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("results", List.of());
            empty.put("next_cursor", null);
            empty.put("has_more", false);
            empty.put("returned_count", 0);
            empty.put("scope_truncated", resolved.truncated());
            empty.put("scope", scope);
            return ToolExecutionResult.success(empty);
        }

        try {
            Map<String, Object> response = conversationServiceClient.searchMessages(
                    resolved.conversationIds(), query.trim(), since, until, roles, toolName,
                    false, limit, cursor);

            if (response.containsKey("error")) {
                return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Search rejected by conversation-service: " + response.get("error"));
            }

            // Normalize keys to snake_case for tool consumers and add the
            // resolved scope so the caller knows what was actually searched.
            // Hits also get re-keyed individually - the help docs promise
            // snake_case fields (message_id, conversation_id, created_at, …)
            // so the LLM looks for those names. Drift here would silently
            // hide every hit field from the caller.
            // scope_truncated combines client-side clamping (>200 children)
            // and any backend-side truncation flag (in case admin/internal
            // sees more conversations than the cap allows in the future).
            boolean truncated = resolved.truncated()
                    || Boolean.TRUE.equals(response.get("scopeTruncated"));

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("results", normalizeHits(response.getOrDefault("results", List.of())));
            result.put("next_cursor", response.get("nextCursor"));
            result.put("has_more", response.getOrDefault("hasMore", false));
            result.put("returned_count", response.getOrDefault("returnedCount", 0));
            result.put("scope_truncated", truncated);
            result.put("scope", scope);
            return ToolExecutionResult.success(result);
        } catch (Exception e) {
            log.warn("[AGENT_SEARCH] search_messages failed: {}", e.getMessage());
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "search_messages failed: " + e.getMessage());
        }
    }

    /**
     * Resolve a macro-scope into the list of conversation IDs the caller is
     * authorized to search.
     *
     * <p>If {@code targetAgentId} is non-null, the caller is asking for a
     * single agent; we verify the target is in the resolved scope and narrow
     * to that one conversation.
     *
     * <p>The result is clipped to {@value #MAX_CONVERSATION_IDS} entries (the
     * limit enforced by the backend search endpoint). When clipped, the
     * returned {@link ResolvedScope} carries {@code truncated=true} so the
     * caller can warn the LLM via {@code scope_truncated=true}.
     *
     * @throws IllegalArgumentException when the target agent is not visible,
     *                                   or the caller has no own conversation
     *                                   to search ({@code scope=self} only).
     */
    private ResolvedScope resolveSearchScope(String scope, UUID targetAgentId, String tenantId,
                                              ToolExecutionContext context) {
        // Convention `null=all` (see AgentToolsProvider.java doc and
        // SubAgentExecutionHandler#getAllowedAgentIds): null means the caller
        // has NO restriction in toolsConfig.agents → it sees every agent in
        // the tenant. This is the "god agent" pattern (typical for the user's
        // primary chat agent). Empty list [] means "no children".
        List<String> allowed = getAllowedAgentIds(context);
        boolean isGodAgent = (allowed == null);

        switch (scope) {
            case "self" -> {
                if (targetAgentId != null) {
                    throw new IllegalArgumentException(
                            "agent_id is not allowed with scope='self' (the scope is already self).");
                }
                String selfConv = getConversationId(context);
                if (selfConv == null || selfConv.isBlank()) {
                    throw new IllegalArgumentException(
                            "scope='self' requested but the caller has no conversation in context.");
                }
                return new ResolvedScope(List.of(selfConv), false);
            }
            case "children" -> {
                Set<String> scopeIds = isGodAgent
                        ? allTenantAgentIds(tenantId)
                        : new LinkedHashSet<>(allowed);
                List<String> conversations = new ArrayList<>();
                boolean truncated = appendChildConversations(
                        scopeIds, targetAgentId, tenantId, conversations,
                        MAX_CONVERSATION_IDS);
                return new ResolvedScope(conversations, truncated);
            }
            case "all_visible" -> {
                List<String> conversations = new ArrayList<>();
                String selfConv = getConversationId(context);
                if (selfConv != null && !selfConv.isBlank()) {
                    conversations.add(selfConv);
                }
                Set<String> scopeIds = isGodAgent
                        ? allTenantAgentIds(tenantId)
                        : new LinkedHashSet<>(allowed);
                int remaining = MAX_CONVERSATION_IDS - conversations.size();
                boolean truncated = appendChildConversations(
                        scopeIds, targetAgentId, tenantId, conversations,
                        remaining);
                return new ResolvedScope(conversations, truncated);
            }
            default -> throw new IllegalArgumentException("Unknown scope: " + scope);
        }
    }

    /**
     * Resolve the full set of agent IDs in the caller's tenant.
     *
     * <p>Used by the god-agent path of {@link #resolveSearchScope}: an agent
     * with {@code allowedAgentIds == null} (no restriction in toolsConfig)
     * sees every agent in the tenant. The result is bounded by
     * {@link #MAX_CONVERSATION_IDS} downstream - power-user tenants with 1000+
     * agents will get the head-200 + {@code scope_truncated=true}.
     */
    private Set<String> allTenantAgentIds(String tenantId) {
        try {
            return agentService.listAllByTenant(tenantId).stream()
                    .map(a -> a.getId().toString())
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        } catch (Exception e) {
            log.warn("Failed to list tenant agents for god-agent scope: {}", e.getMessage());
            return new LinkedHashSet<>();
        }
    }

    /**
     * Append the conversations of {@code scopeAgentIds} (or the single
     * {@code targetAgentId} if non-null and visible) to {@code dest}, up to
     * {@code maxToAppend} entries.
     *
     * @return true when the allowlist had more entries than {@code maxToAppend}
     *          (so older agents were dropped). Always false when targeting a
     *          single agent.
     */
    private boolean appendChildConversations(Set<String> scopeAgentIds, UUID targetAgentId,
                                              String tenantId, List<String> dest,
                                              int maxToAppend) {
        if (targetAgentId != null) {
            if (!scopeAgentIds.contains(targetAgentId.toString())) {
                throw new IllegalArgumentException(
                        "Agent " + targetAgentId + " is not in your allowed agent list.");
            }
            scopeAgentIds = Set.of(targetAgentId.toString());
        }

        boolean truncated = false;
        int appended = 0;
        for (String agentIdStr : scopeAgentIds) {
            if (appended >= maxToAppend) {
                truncated = true;
                break;
            }
            try {
                UUID childAgentId = UUID.fromString(agentIdStr);
                Optional<AgentEntity> entity = agentService.getAgent(childAgentId, tenantId);
                if (entity.isEmpty()) continue; // skip stale allowlist entries

                String convId = conversationServiceClient.findOrCreateAgentConversation(
                        agentIdStr, tenantId, entity.get().getName());
                if (convId != null && !convId.isBlank()) {
                    dest.add(convId);
                    appended++;
                }
            } catch (IllegalArgumentException e) {
                // Skip malformed agent ids in the allowlist - log only.
                log.debug("Skipping non-UUID entry in allowedAgentIds: {}", agentIdStr);
            } catch (Exception e) {
                log.debug("Failed to resolve conversation for agent {}: {}", agentIdStr, e.getMessage());
            }
        }
        return truncated;
    }

    /**
     * Re-key each hit from camelCase (the conversation-service wire format)
     * to snake_case (the MCP tool contract documented in {@link AgentHelpModule}).
     *
     * <p>The help docs tell the LLM agent that hits expose
     * {@code message_id}, {@code conversation_id}, {@code agent_id},
     * {@code execution_id}, {@code conversation_title}, {@code role},
     * {@code tool_name}, {@code excerpt}, {@code rank}, {@code created_at}.
     * Without this re-keying, the LLM looks for {@code message_id} on each
     * hit and gets {@code messageId} back - silently invisible.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> normalizeHits(Object rawHits) {
        if (!(rawHits instanceof List<?> list)) return List.of();
        List<Map<String, Object>> normalized = new ArrayList<>(list.size());
        for (Object raw : list) {
            if (!(raw instanceof Map<?, ?> hit)) continue;
            Map<String, Object> renamed = new LinkedHashMap<>();
            // Renaming inline keeps the contract grep-able and tests trivial.
            put(renamed, "message_id", hit.get("messageId"));
            put(renamed, "conversation_id", hit.get("conversationId"));
            put(renamed, "conversation_title", hit.get("conversationTitle"));
            put(renamed, "agent_id", hit.get("agentId"));
            put(renamed, "execution_id", hit.get("executionId"));
            put(renamed, "role", hit.get("role"));
            put(renamed, "tool_name", hit.get("toolName"));
            put(renamed, "excerpt", hit.get("excerpt"));
            put(renamed, "rank", hit.get("rank"));
            put(renamed, "created_at", hit.get("createdAt"));
            normalized.add(renamed);
        }
        return normalized;
    }

    private static void put(Map<String, Object> dest, String key, Object value) {
        if (value != null) dest.put(key, value);
    }

    // ==================== Get History ====================

    private ToolExecutionResult executeGetHistory(Map<String, Object> parameters, String tenantId,
                                                   ToolExecutionContext context) {
        Map<String, Object> p = mergeParams(parameters);
        UUID agentId = getUuidParam(p, "agent_id");
        Integer limit = getIntParam(p, "limit", DEFAULT_HISTORY_LIMIT);

        // Resolve conversation: either sub-agent's or own
        String conversationId;
        String agentName;

        if (agentId != null) {
            // Get sub-agent's conversation
            List<String> allowedAgentIds = getAllowedAgentIds(context);
            if (allowedAgentIds != null && !allowedAgentIds.contains(agentId.toString())) {
                return ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED, "This agent is not in your approved agent list.");
            }

            Optional<AgentEntity> entityOpt = agentService.getAgent(agentId, tenantId);
            if (entityOpt.isEmpty()) {
                return ToolExecutionResult.failure(ToolErrorCode.AGENT_NOT_FOUND, "Agent not found: " + agentId);
            }
            AgentEntity entity = entityOpt.get();
            agentName = entity.getName();

            conversationId = conversationServiceClient.findOrCreateAgentConversation(
                agentId.toString(), tenantId, agentName);
        } else {
            // Own conversation
            conversationId = getConversationId(context);
            agentName = "self";
        }

        if (conversationId == null) {
            return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, "No conversation found for this agent.");
        }

        try {
            List<Map<String, Object>> rawMessages = conversationServiceClient.getConversationMessages(
                conversationId, limit, tenantId);

            // Filter to user/assistant only (same logic as memory loading)
            List<Map<String, String>> history = new ArrayList<>();
            if (rawMessages != null) {
                for (Map<String, Object> msg : rawMessages) {
                    String role = msg.get("role") != null ? msg.get("role").toString().toLowerCase() : null;
                    String content = msg.get("content") != null ? msg.get("content").toString() : null;
                    if (role == null || content == null || content.isBlank()) continue;

                    if ("user".equals(role) || "assistant".equals(role)) {
                        history.add(Map.of("role", role, "content", content));
                    }
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("agent_name", agentName);
            result.put("conversation_id", conversationId);
            result.put("message_count", history.size());
            result.put("messages", history);

            if (history.isEmpty()) {
                result.put("message", "No conversation history found for this agent.");
            }

            return ToolExecutionResult.success(result);
        } catch (Exception e) {
            log.warn("[AGENT_HISTORY] Failed to load history for {}: {}", agentName, e.getMessage());
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to load conversation history: " + e.getMessage());
        }
    }

    // ==================== Share ====================

    private ToolExecutionResult executeShare(Map<String, Object> parameters, String tenantId,
                                              ToolExecutionContext context) {
        Map<String, Object> p = mergeParams(parameters);
        UUID agentId = getUuidParam(p, "agent_id");
        String shareMode = getStringParam(p, "share_mode");
        if (shareMode == null) shareMode = "read";

        // Resolve conversation: either sub-agent's or own
        String conversationId;
        String agentName;

        if (agentId != null) {
            // Share sub-agent's conversation
            List<String> allowedAgentIds = getAllowedAgentIds(context);
            if (allowedAgentIds != null && !allowedAgentIds.contains(agentId.toString())) {
                return ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED, "This agent is not in your approved agent list.");
            }

            Optional<AgentEntity> entityOpt = agentService.getAgent(agentId, tenantId);
            if (entityOpt.isEmpty()) {
                return ToolExecutionResult.failure(ToolErrorCode.AGENT_NOT_FOUND, "Agent not found: " + agentId);
            }
            AgentEntity entity = entityOpt.get();
            agentName = entity.getName();

            conversationId = conversationServiceClient.findOrCreateAgentConversation(
                agentId.toString(), tenantId, agentName);
        } else {
            // Share own conversation
            conversationId = getConversationId(context);
            agentName = "self";
        }

        if (conversationId == null) {
            return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, "No conversation found to share.");
        }

        try {
            // Step 1: Enable sharing on the conversation (generates cs_ token)
            Map<String, Object> shareResult = conversationServiceClient.enableSharing(
                conversationId, tenantId, shareMode);

            String shareToken = (String) shareResult.get("shareToken");
            if (shareToken == null) {
                String error = (String) shareResult.get("error");
                return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to enable sharing: " + (error != null ? error : "no token returned"));
            }

            // Step 2: Register in shared_links registry (same as frontend ShareLinkDialog)
            // This makes the link appear in the user's shared links list
            String slToken = registerSharedLink(tenantId, shareToken, conversationId,
                agentId != null ? agentName + " conversation" : "Conversation", context);

            // Use sl_ token for URL if registration succeeded, fall back to cs_ token
            String urlToken = slToken != null ? slToken : shareToken;
            String shareUrl = shareBaseUrl + "/s/" + urlToken;

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "SHARED");
            result.put("agent_name", agentName);
            result.put("conversation_id", conversationId);
            result.put("share_token", urlToken);
            result.put("share_url", shareUrl);
            result.put("share_mode", shareMode);
            result.put("message", "Conversation shared. Public link: " + shareUrl);

            log.info("[AGENT_SHARE] Shared conversation {} for agent '{}' (mode={}): {}",
                conversationId, agentName, shareMode, shareUrl);

            return ToolExecutionResult.success(result);
        } catch (Exception e) {
            log.warn("[AGENT_SHARE] Failed to share conversation for {}: {}", agentName, e.getMessage());
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to share conversation: " + e.getMessage());
        }
    }

    // ==================== Unshare ====================

    private ToolExecutionResult executeUnshare(Map<String, Object> parameters, String tenantId,
                                                ToolExecutionContext context) {
        Map<String, Object> p = mergeParams(parameters);
        UUID agentId = getUuidParam(p, "agent_id");

        String conversationId;
        String agentName;

        if (agentId != null) {
            List<String> allowedAgentIds = getAllowedAgentIds(context);
            if (allowedAgentIds != null && !allowedAgentIds.contains(agentId.toString())) {
                return ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED, "This agent is not in your approved agent list.");
            }
            Optional<AgentEntity> entityOpt = agentService.getAgent(agentId, tenantId);
            if (entityOpt.isEmpty()) return ToolExecutionResult.failure(ToolErrorCode.AGENT_NOT_FOUND, "Agent not found: " + agentId);
            agentName = entityOpt.get().getName();
            conversationId = conversationServiceClient.findOrCreateAgentConversation(
                agentId.toString(), tenantId, agentName);
        } else {
            conversationId = getConversationId(context);
            agentName = "self";
        }

        if (conversationId == null) {
            return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, "No conversation found to unshare.");
        }

        try {
            // Step 1: Disable sharing on conversation (sets shareMode to "off")
            conversationServiceClient.disableSharing(conversationId, tenantId);

            // Step 2: Deactivate in shared_links registry
            unregisterSharedLink(conversationId, tenantId, context);

            log.info("[AGENT_UNSHARE] Unshared conversation {} for agent '{}'", conversationId, agentName);

            return ToolExecutionResult.success(Map.of(
                "status", "UNSHARED",
                "agent_name", agentName,
                "conversation_id", conversationId,
                "message", "Sharing disabled. Previous share links are no longer active."
            ));
        } catch (Exception e) {
            log.warn("[AGENT_UNSHARE] Failed to unshare conversation for {}: {}", agentName, e.getMessage());
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to unshare conversation: " + e.getMessage());
        }
    }

    // ==================== Refresh Share ====================

    private ToolExecutionResult executeRefreshShare(Map<String, Object> parameters, String tenantId,
                                                     ToolExecutionContext context) {
        Map<String, Object> p = mergeParams(parameters);
        UUID agentId = getUuidParam(p, "agent_id");
        String shareMode = getStringParam(p, "share_mode");
        if (shareMode == null) shareMode = "read";

        String conversationId;
        String agentName;

        if (agentId != null) {
            List<String> allowedAgentIds = getAllowedAgentIds(context);
            if (allowedAgentIds != null && !allowedAgentIds.contains(agentId.toString())) {
                return ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED, "This agent is not in your approved agent list.");
            }
            Optional<AgentEntity> entityOpt = agentService.getAgent(agentId, tenantId);
            if (entityOpt.isEmpty()) return ToolExecutionResult.failure(ToolErrorCode.AGENT_NOT_FOUND, "Agent not found: " + agentId);
            agentName = entityOpt.get().getName();
            conversationId = conversationServiceClient.findOrCreateAgentConversation(
                agentId.toString(), tenantId, agentName);
        } else {
            conversationId = getConversationId(context);
            agentName = "self";
        }

        if (conversationId == null) {
            return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, "No conversation found to refresh share link.");
        }

        try {
            // Step 1: Find existing shared link by conversation
            String existingSlId = findSharedLinkId(conversationId, tenantId, context);
            if (existingSlId == null) {
                return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "No shared link found for this conversation. Use agent(action='share') first.");
            }

            // Step 2: Regenerate token in shared_links registry (old URL becomes invalid)
            String newSlToken = regenerateSharedLinkToken(tenantId, existingSlId, context);
            if (newSlToken == null) {
                return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to regenerate share link.");
            }

            String shareUrl = shareBaseUrl + "/s/" + newSlToken;

            log.info("[AGENT_REFRESH_SHARE] Refreshed share link for conversation {} agent '{}': {}",
                conversationId, agentName, shareUrl);

            return ToolExecutionResult.success(Map.of(
                "status", "REFRESHED",
                "agent_name", agentName,
                "conversation_id", conversationId,
                "share_token", newSlToken,
                "share_url", shareUrl,
                "share_mode", shareMode,
                "message", "Share link refreshed. Old links are invalid. New link: " + shareUrl
            ));
        } catch (Exception e) {
            log.warn("[AGENT_REFRESH_SHARE] Failed to refresh share for {}: {}", agentName, e.getMessage());
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to refresh share link: " + e.getMessage());
        }
    }

    // ==================== Helpers ====================

    /**
     * Registers a shared link in the publication-service shared_links registry.
     * Same flow as frontend ShareLinkDialog - makes the link appear in user's shared links list.
     * Returns the sl_ token on success, null on failure (non-blocking).
     */
    /**
     * Audit 2026-05-17 round-4 - build HttpHeaders with X-User-ID + (optional)
     * X-Organization-ID + Content-Type so the publication-service shared-link
     * endpoints enforce caller scope rather than trusting body fields.
     */
    private HttpHeaders buildScopeHeaders(String tenantId, ToolExecutionContext context) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (tenantId != null && !tenantId.isBlank()) {
            headers.set("X-User-ID", tenantId);
        }
        String orgId = context != null ? context.orgId() : null;
        if (orgId != null && !orgId.isBlank()) {
            headers.set("X-Organization-ID", orgId);
        }
        // 2026-05-21 - also forward X-Organization-Role so downstream canAccess
        // deny-list checks get the caller's role (gateway-validated). Without this,
        // workspace OWNERs hit "Access restricted" on org-scoped tools because the
        // downstream defaults to null role → conservative deny.
        String orgRole = context != null ? context.orgRole() : null;
        if (orgRole != null && !orgRole.isBlank()) {
            headers.set("X-Organization-Role", orgRole);
        }
        return headers;
    }

    @SuppressWarnings("unchecked")
    private String registerSharedLink(String tenantId, String resourceToken, String conversationId,
                                       String title, ToolExecutionContext context) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("tenantId", tenantId);
            body.put("resourceType", "CONVERSATION");
            body.put("resourceToken", resourceToken);
            body.put("resourceId", conversationId);
            body.put("title", title);

            HttpHeaders headers = buildScopeHeaders(tenantId, context);
            ResponseEntity<Map> resp = restTemplate.exchange(
                publicationServiceUrl + "/api/internal/shared-links/register",
                HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);

            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                String slToken = (String) resp.getBody().get("token");
                log.info("[AGENT_SHARE] Registered shared link: sl_token={}", slToken);
                return slToken;
            }
        } catch (Exception e) {
            log.warn("[AGENT_SHARE] Failed to register shared link (non-blocking): {}", e.getMessage());
        }
        return null;
    }

    /**
     * Deactivates a shared link in the publication-service registry by conversation ID.
     */
    private void unregisterSharedLink(String conversationId, String tenantId, ToolExecutionContext context) {
        try {
            HttpHeaders scopeHeaders = buildScopeHeaders(tenantId, context);
            // Find the shared link by conversationId (stored as resourceId)
            ResponseEntity<Map> resp = restTemplate.exchange(
                publicationServiceUrl + "/api/internal/shared-links/by-resource-id/" + conversationId,
                HttpMethod.GET, new HttpEntity<>(scopeHeaders), Map.class);

            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                String resourceToken = (String) resp.getBody().get("resourceToken");
                if (resourceToken != null) {
                    restTemplate.exchange(
                        publicationServiceUrl + "/api/internal/shared-links/unregister",
                        HttpMethod.POST, new HttpEntity<>(Map.of("resourceToken", resourceToken), scopeHeaders), Void.class);
                    log.info("[AGENT_UNSHARE] Deactivated shared link for conversation {}", conversationId);
                }
            }
        } catch (Exception e) {
            log.warn("[AGENT_UNSHARE] Failed to deactivate shared link (non-blocking): {}", e.getMessage());
        }
    }

    /**
     * Finds the shared link ID for a conversation (by conversationId stored as resourceId).
     */
    @SuppressWarnings("unchecked")
    private String findSharedLinkId(String conversationId, String tenantId, ToolExecutionContext context) {
        try {
            ResponseEntity<Map> resp = restTemplate.exchange(
                publicationServiceUrl + "/api/internal/shared-links/by-resource-id/" + conversationId,
                HttpMethod.GET, new HttpEntity<>(buildScopeHeaders(tenantId, context)), Map.class);

            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                return (String) resp.getBody().get("id");
            }
        } catch (Exception e) {
            log.warn("[AGENT_SHARE] Failed to find shared link for conversation {}: {}", conversationId, e.getMessage());
        }
        return null;
    }

    /**
     * Regenerates the token for a shared link. Returns the new sl_ token or null.
     */
    @SuppressWarnings("unchecked")
    private String regenerateSharedLinkToken(String tenantId, String linkId, ToolExecutionContext context) {
        try {
            Map<String, String> body = Map.of("tenantId", tenantId, "linkId", linkId);
            ResponseEntity<Map> resp = restTemplate.exchange(
                publicationServiceUrl + "/api/internal/shared-links/regenerate-token",
                HttpMethod.POST, new HttpEntity<>(body, buildScopeHeaders(tenantId, context)), Map.class);

            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                return (String) resp.getBody().get("token");
            }
        } catch (Exception e) {
            log.warn("[AGENT_SHARE] Failed to regenerate shared link token: {}", e.getMessage());
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<String> getAllowedAgentIds(ToolExecutionContext context) {
        if (context == null || context.credentials() == null) return null;
        return ToolAccessControl.getAllowedIds(context.credentials(), "agent");
    }

    private String getConversationId(ToolExecutionContext context) {
        if (context == null || context.credentials() == null) return null;
        Object val = context.credentials().get("conversationId");
        return val instanceof String s ? s : null;
    }
}
