package com.apimarketplace.monolith;

import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.factory.BridgeAvailabilityFilter;
import com.apimarketplace.agent.factory.LLMProviderFactory;
import com.apimarketplace.agent.service.ModelCatalogService;
import com.apimarketplace.conversation.service.ConversationQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Stub controller for conversation-service v3 endpoints in CE monolith mode.
 *
 * ChatControllerV3 and StreamControllerV3 are excluded from the monolith component scan because
 * they are cloud/WebFlux streaming controllers. CE wires the shared Redis stream state adapter
 * explicitly and keeps these servlet endpoints small.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "deployment.mode", havingValue = "monolith")
public class CeConversationStubController {

    private final LLMProviderFactory llmProviderFactory;
    private final com.apimarketplace.agent.tool.ToolExecutionService monolithToolExecutionService;
    private final ConversationQueryService conversationQueryService;
    // Stream STATE (real RedisStreamStateService in CE, see MonolithAdapterConfig). The agent-worker
    // callback writes content/tool/index keys to Redis, but the metadata HASH (which getByConversationId
    // requires to resolve a stream) is only written via registerExternalStream - which used to be a no-op
    // here, so the snapshot replay found nothing. Writing it makes the WS snapshot/recovery work.
    private final com.apimarketplace.conversation.streaming.StreamStateService streamStateService;
    // Owner resolution for external streams (in-JVM equivalent of cloud
    // InternalAccessController's lookup): attributes the stream to the
    // conversation owner so /streams/active sees externally-driven runs.
    private final com.apimarketplace.conversation.repository.ConversationRepository conversationRepository;

    @Autowired(required = false)
    private ModelCatalogService modelCatalogService;

    @Value("${conversation.bridge.url:}")
    private String bridgeUrl;

    /**
     * Strict availability: when true (default) a CLI provider whose bridge
     * availability cannot be verified is hidden from the CE picker, matching
     * cloud ModelCatalogService. Override via models.bridge-availability.strict.
     */
    @Value("${models.bridge-availability.strict:true}")
    private boolean bridgeAvailabilityStrict;

    /**
     * Shared availability filter (same one used by cloud agent-service via
     * ModelCatalogService). Built lazily after Spring injects the
     * bridge URL so monolith and cloud go through the EXACT same code path.
     */
    private BridgeAvailabilityFilter bridgeAvailabilityFilter;

    @PostConstruct
    void init() {
        this.bridgeAvailabilityFilter = new BridgeAvailabilityFilter(bridgeUrl, bridgeAvailabilityStrict);
    }

    /**
     * Available AI models - runs the base provider list through the shared
     * BridgeAvailabilityFilter so monolith CE picker only advertises CLI
     * providers whose binary is actually installed. Centralised 2026-04-09:
     * before this, the stub bypassed the filter entirely and listed phantom
     * providers (codex/gemini-cli/mistral-vibe) even when not installed.
     *
     * @param category which catalogue slice to answer with, mirroring the cloud twin
     *                 ({@code ChatControllerV3}). Absent means the chat slice, which is
     *                 what this endpoint has always returned. A surface that runs a
     *                 different KIND of model names its category: the classify node asks
     *                 for {@code classification} to reach the decision models, which the
     *                 chat slice deliberately excludes.
     *
     *                 <p>Without it the CE classify inspector could never offer the
     *                 decision engine, while an authoring agent could still put one in a
     *                 plan through the internal catalogue endpoint - a node its own owner
     *                 could not see or change.
     *
     *                 <p>Restricted to a known set for the same reason as the cloud twin:
     *                 the eligibility rule is permissive for a category it does not
     *                 recognise, so an arbitrary value would answer with the whole
     *                 catalogue. An unknown value is ignored and the chat slice answered.
     */
    @GetMapping("/api/v3/chat/models")
    public ResponseEntity<Map<String, Object>> getAvailableModels(
            @RequestParam(value = "category", required = false) String category,
            @RequestHeader(value = "X-User-ID", required = false) String authenticatedUserId) {
        try {
            // The null check is not redundant: Set.of(...) throws on contains(null), and
            // null is the COMMON case here (every caller wanting the chat slice omits it).
            String slice = category != null && PUBLIC_CATEGORIES.contains(category) ? category : null;
            Map<String, Object> models;
            if (modelCatalogService != null) {
                models = authenticatedUserId == null || authenticatedUserId.isBlank()
                        ? modelCatalogService.getPublicModelsForCategory(slice)
                        : modelCatalogService.getModelsForCategory(slice, authenticatedUserId);
                // Deliberately NOT calling hideBridgeProvidersForPublicRead here. That filter trims a
                // declared PUBLIC read on the hosted product; this controller only ever serves a
                // self-hosted install, where the CLI providers it exists to advertise are the
                // operator's own, under their own login. An inert call would put a hosted-shaped
                // rule on the self-hosted path for symmetry's sake.
            } else {
                // Fallback path: no catalogue service, so no category overlay and no mode
                // filter either. That second absence matters now that a provider can be
                // non-chat: getAllModelsInfo filters on isConfigured() alone, so a keyed
                // decision provider would land in the CE CHAT picker here, which is the one
                // place the mode rule does not reach on its own. Filter it explicitly.
                models = llmProviderFactory.getAllModelsInfo();
                bridgeAvailabilityFilter.filter(models);
                retainModelsEligibleFor(models, slice);
            }
            return ResponseEntity.ok(models);
        } catch (Exception e) {
            log.error("Error retrieving available models: {}", e.getMessage(), e);
            return ResponseEntity.ok(Map.of("error", "Error retrieving models"));
        }
    }

    /**
     * Active streams for the caller - CE serves this from the same
     * RedisStreamStateService user index the cloud path reads. Externally-driven
     * runs (workflow agent nodes, task assignees) are attributed to the
     * conversation owner at registration, so the main chat page's reconnect
     * probe can auto-attach mid-flight. Used to always return [] ("no reactive
     * streaming infrastructure"), which left CE's main chat blind to in-flight
     * external streams.
     */
    @GetMapping("/api/v3/streams/active")
    public ResponseEntity<List<String>> getActiveStreams(
            @RequestHeader(value = "X-User-ID", required = false) String authenticatedUserId) {
        if (authenticatedUserId == null || authenticatedUserId.isBlank()) {
            return ResponseEntity.ok(List.of());
        }
        try {
            List<String> ids = streamStateService.getStreamingConversationIds(authenticatedUserId)
                    .collectList()
                    .block(java.time.Duration.ofSeconds(3));
            return ResponseEntity.ok(ids == null ? List.of() : ids);
        } catch (Exception e) {
            log.warn("[CE] Active-streams lookup failed (best-effort, returning empty): {}", e.getMessage());
            return ResponseEntity.ok(List.of());
        }
    }

    @PostMapping("/api/internal/streams/register")
    public ResponseEntity<Void> registerInternalStream(@RequestBody(required = false) Map<String, String> body) {
        String streamId = body == null ? null : body.get("streamId");
        String conversationId = body == null ? null : body.get("conversationId");
        if (streamId == null || streamId.isBlank() || conversationId == null || conversationId.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        // Write the stream metadata hash + conversation index (mirrors cloud InternalAccessController).
        // Without this the hash is missing → RedisStreamStateService.getByConversationId returns empty →
        // the WS snapshot replay finds nothing even though content/tool keys exist. Owner attribution
        // feeds the stream:user index so /streams/active (above) sees this run.
        try {
            String ownerUserId = null;
            try {
                ownerUserId = conversationRepository.findById(conversationId)
                        .map(com.apimarketplace.conversation.entity.Conversation::getUserId)
                        .orElse(null);
            } catch (Exception e) {
                log.warn("[CE] Failed to resolve owner of conversation {}: {}", conversationId, e.getMessage());
            }
            streamStateService.registerExternalStream(
                    streamId, conversationId,
                    body.getOrDefault("model", null), body.getOrDefault("provider", null),
                    ownerUserId).block();
        } catch (Exception e) {
            log.warn("[CE] Stream register failed (best-effort): streamId={}, conv={}: {}", streamId, conversationId, e.getMessage());
        }
        log.debug("[CE] Stream registered: streamId={}, conversationId={}", streamId, conversationId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/api/internal/streams/{streamId}/finalize")
    public ResponseEntity<Void> finalizeInternalStream(
            @PathVariable String streamId,
            @RequestBody(required = false) Map<String, String> body) {
        String state = body == null ? "COMPLETED" : body.getOrDefault("state", "COMPLETED");
        // Advance the stream state so a snapshot replay AFTER completion emits a terminal `done` event
        // (the frontend only finalizes the bubble on `done`), not a non-terminal `content`.
        try {
            switch (state == null ? "COMPLETED" : state.toUpperCase()) {
                // Same reader as the cloud endpoint: the client sends "errorMessage", and this
                // stub used to read "error", so every CE stream error was stored as "Unknown error".
                case "ERROR" -> streamStateService.error(streamId,
                        com.apimarketplace.conversation.controller.internal.InternalAccessController
                                .producerErrorMessage(body)).block();
                case "STOPPED", "STOPPED_BY_USER" -> streamStateService.stop(streamId).block();
                default -> streamStateService.complete(streamId).block();
            }
        } catch (Exception e) {
            log.warn("[CE] Stream finalize failed (best-effort): streamId={}, state={}: {}", streamId, state, e.getMessage());
        }
        log.debug("[CE] Stream finalized: streamId={}, state={}", streamId, state);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/api/v3/streams/by-conversation/{conversationId}/state")
    public ResponseEntity<Map<String, Object>> getStreamState(@PathVariable String conversationId) {
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("conversationId", conversationId);
        response.put("content", "");
        response.put("toolEvents", List.of());
        response.put("hasActiveStream", false);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/api/v3/streams/by-conversation/{conversationId}/status")
    public ResponseEntity<Map<String, Object>> getStreamStatusByConversation(@PathVariable String conversationId) {
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("streamId", null);
        response.put("conversationId", conversationId);
        response.put("model", null);
        response.put("provider", null);
        response.put("state", null);
        response.put("createdAt", null);
        response.put("lastActivity", null);
        response.put("contentLength", 0);
        response.put("hasActiveStream", false);
        response.put("timestamp", java.time.Instant.now().toString());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/api/v3/streams/{streamId}/stop")
    public ResponseEntity<Void> stopStream(@PathVariable String streamId) {
        // Actually cancel the run, don't just ack: setCancelKey writes agent:cancel:{streamId}
        // which the agent loop polls to halt (ConversationRedisStreamingCallback.shouldStop).
        // Previously this only logged, so pressing Stop in CE never stopped the agent.
        try {
            streamStateService.stop(streamId).block();
            streamStateService.setCancelKey(streamId).block();
            log.info("[CE] Stream stop applied for stream: {}", streamId);
        } catch (Exception e) {
            log.warn("[CE] Stream stop failed for stream {}: {}", streamId, e.getMessage());
        }
        return ResponseEntity.ok().build();
    }

    @PostMapping("/api/v3/streams/by-conversation/{conversationId}/stop")
    public ResponseEntity<Void> stopStreamByConversation(@PathVariable String conversationId) {
        try {
            var metadata = streamStateService.getByConversationId(conversationId).block();
            if (metadata != null && metadata.state().isActive()) {
                String streamId = metadata.streamId();
                streamStateService.stop(streamId).block();
                streamStateService.setCancelKey(streamId).block();
                log.info("[CE] Stream stop applied for conversation {} (stream {})", conversationId, streamId);
            } else {
                log.info("[CE] No active stream to stop for conversation: {}", conversationId);
            }
        } catch (Exception e) {
            log.warn("[CE] Stream stop failed for conversation {}: {}", conversationId, e.getMessage());
        }
        return ResponseEntity.ok().build();
    }

    @GetMapping("/api/v3/streams/{streamId}/status")
    public ResponseEntity<Map<String, Object>> getStreamStatus(@PathVariable String streamId) {
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("streamId", streamId);
        response.put("hasActiveStream", false);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/api/v3/streams/metrics")
    public ResponseEntity<Map<String, Object>> getStreamMetrics() {
        return ResponseEntity.ok(Map.of(
            "localActiveStreams", 0,
            "note", "CE monolith uses servlet WebSocket events without reactive stream state"
        ));
    }

    /**
     * Conversation tool execution endpoint - handles set_conversation_title, etc.
     * In cloud, this is ConversationToolExecutionController (excluded from monolith).
     * Routes to MonolithToolExecutionService which handles conversation tools locally.
     */
    @PostMapping("/api/internal/conversation/tools/execute")
    public ResponseEntity<Map<String, Object>> executeConversationTool(
            @RequestHeader(value = "X-User-ID", required = false) String authenticatedUserId,
            @RequestHeader(value = "X-Organization-ID", required = false) String authenticatedOrganizationId,
            @RequestBody Map<String, Object> body) {
        if (authenticatedUserId == null || authenticatedUserId.isBlank()) {
            return ResponseEntity.status(401).body(Map.of(
                "success", false,
                "error", "Authentication required"
            ));
        }

        if (body == null) {
            body = Map.of();
        }

        String toolName = firstString(body, "tool", "toolName");
        if (toolName == null || toolName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "error", "Tool name is required"
            ));
        }

        String toolCallId = (String) body.get("toolCallId");
        Map<String, Object> arguments = firstMap(body, "parameters", "arguments");
        Map<String, Object> credentials = buildConversationToolCredentials(body);
        String tenantId = authenticatedUserId.trim();
        String organizationId = asNonBlankString(authenticatedOrganizationId);
        if (organizationId != null) {
            credentials.put("__orgId__", organizationId);
        }
        String bodyTenantId = firstString(body, "tenantId", "userId");
        if (bodyTenantId != null && !bodyTenantId.isBlank() && !tenantId.equals(bodyTenantId)) {
            log.warn("[CE] Ignoring forged conversation tool tenant body value {} for authenticated user {}",
                bodyTenantId, tenantId);
        }

        log.info("[CE] Conversation tool: {} for tenant: {}", toolName, tenantId);

        String conversationId = asNonBlankString(credentials.get("conversationId"));
        if (conversationId != null && conversationQueryService
                .getConversationById(conversationId, tenantId, organizationId)
                .isEmpty()) {
            log.warn("[CE] Refused conversation tool {} for out-of-scope conversation {} and user {}",
                toolName, conversationId, tenantId);
            return ResponseEntity.status(404).body(Map.of(
                "success", false,
                "error", "Conversation not found"
            ));
        }

        var toolCall = new ToolCall(toolCallId, toolName, arguments, null);
        var toolDefinition = ToolDefinition.builder().name(toolName).build();
        var result = monolithToolExecutionService.executeTool(toolCall, toolDefinition, tenantId, credentials);

        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("success", result.success());
        response.put("result", result.content());
        if (result.error() != null) response.put("error", result.error());
        if (result.metadata() != null) response.put("metadata", result.metadata());
        return ResponseEntity.ok(response);
    }

    private static String firstString(Map<String, Object> body, String primaryKey, String legacyKey) {
        Object primary = body.get(primaryKey);
        if (primary instanceof String value && !value.isBlank()) {
            return value;
        }
        Object legacy = body.get(legacyKey);
        return legacy instanceof String value ? value : null;
    }

    private static String asNonBlankString(Object value) {
        if (value == null) {
            return null;
        }
        String stringValue = value.toString();
        return stringValue.isBlank() ? null : stringValue;
    }

    private static Map<String, Object> firstMap(Map<String, Object> body, String primaryKey, String legacyKey) {
        Object primary = body.get(primaryKey);
        if (primary instanceof Map<?, ?> primaryMap) {
            return copyStringKeyMap(primaryMap);
        }
        Object legacy = body.get(legacyKey);
        if (legacy instanceof Map<?, ?> legacyMap) {
            return copyStringKeyMap(legacyMap);
        }
        return Map.of();
    }

    private static Map<String, Object> buildConversationToolCredentials(Map<String, Object> body) {
        Map<String, Object> credentials = new java.util.LinkedHashMap<>();
        Object nestedCredentials = body.get("credentials");
        if (nestedCredentials instanceof Map<?, ?> nestedMap) {
            credentials.putAll(copyStringKeyMap(nestedMap));
        }

        putIfPresent(credentials, "conversationId", body.get("conversationId"));
        putIfPresent(credentials, "turnId", body.get("turnId"));

        Map<String, String> forwardedCredentialKeys = Map.ofEntries(
            Map.entry("agentId", "__agentId__"),
            Map.entry("allowedToolIds", "__allowedToolIds__"),
            Map.entry("allowedWorkflowIds", "__allowedWorkflowIds__"),
            Map.entry("allowedApplicationIds", "__allowedApplicationIds__"),
            Map.entry("allowedTableIds", "__allowedTableIds__"),
            Map.entry("allowedInterfaceIds", "__allowedInterfaceIds__"),
            Map.entry("allowedAgentIds", "__allowedAgentIds__"),
            Map.entry("allowedFileIds", "__allowedFileIds__"),
            Map.entry("approvedServices", "__approvedServices__"),
            Map.entry("orgId", "__orgId__"),
            Map.entry("orgRole", "__orgRole__"),
            Map.entry("viewingWorkflowId", "__viewingWorkflowId__"),
            Map.entry("viewingWorkflowName", "__viewingWorkflowName__"),
            Map.entry("streamId", "__streamId__")
        );

        forwardedCredentialKeys.forEach((bodyKey, credentialKey) ->
            putIfPresent(credentials, credentialKey, body.get(bodyKey)));

        // Per-resource access modes travel PLAIN, mirroring the cloud twin
        // (ConversationToolExecutionController). They were absent while every allowed*Ids list
        // was forwarded, so this boundary carried the allow-lists but not the read/write axis.
        for (String accessModeKey : com.apimarketplace.agent.config.ToolAccessControl.ACCESS_MODE_KEYS) {
            putIfPresent(credentials, accessModeKey, body.get(accessModeKey));
        }

        return credentials;
    }

    private static Map<String, Object> copyStringKeyMap(Map<?, ?> source) {
        Map<String, Object> copy = new java.util.LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key instanceof String stringKey) {
                copy.put(stringKey, value);
            }
        });
        return copy;
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    /** The categories this endpoint will answer for; chat is the default and is null. */
    private static final java.util.Set<String> PUBLIC_CATEGORIES =
            java.util.Set.of(com.apimarketplace.agent.domain.ModelCategory.CLASSIFICATION.key());

    /**
     * Drop the models a category does not accept, for the fallback path that has no
     * catalogue service to do it.
     *
     * <p>Mirrors {@code ModelCatalogService.filterProvidersByCategoryMode}, including its
     * resolution of a null category to the chat slice: a model with no mode is chat, which
     * is what every provider written before decision models reports.
     */
    @SuppressWarnings("unchecked")
    private static void retainModelsEligibleFor(Map<String, Object> catalog, String category) {
        Object providersRaw = catalog.get("providers");
        if (!(providersRaw instanceof List<?> providers)) return;
        String slice = category != null
                ? category
                : com.apimarketplace.agent.domain.ModelCategory.CHAT.key();
        for (Object entry : providers) {
            if (!(entry instanceof Map<?, ?> provider)) continue;
            Object modelsRaw = ((Map<String, Object>) provider).get("models");
            if (!(modelsRaw instanceof List<?>)) continue;
            ((List<Map<String, Object>>) modelsRaw).removeIf(m ->
                    !com.apimarketplace.agent.domain.ModelCategory.acceptsMode(
                            slice, (String) m.get("mode")));
        }
    }

}
