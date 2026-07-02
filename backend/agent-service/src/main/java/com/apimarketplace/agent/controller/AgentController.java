package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.util.RequestParameterExtractor;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.agent.domain.AgentEntity;
import com.apimarketplace.agent.domain.AgentWebhookTokenEntity;
import com.apimarketplace.agent.domain.AgentWidgetConfigEntity;
import com.apimarketplace.agent.dto.AgentAvatarResponse;
import com.apimarketplace.agent.service.AgentService;
import com.apimarketplace.agent.service.AgentWidgetConfigService;
import com.apimarketplace.agent.webhook.AgentWebhookTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * REST controller for Agent operations.
 *
 * <p>Uses centralized infrastructure:
 * <ul>
 *   <li>{@link TenantResolver} for X-User-ID header extraction</li>
 *   <li>{@link RequestParameterExtractor} for type-safe parameter extraction</li>
 *   <li>GlobalExceptionHandler for error responses</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private static final Logger logger = LoggerFactory.getLogger(AgentController.class);

    private final AgentService agentService;
    private final AgentWebhookTokenService webhookTokenService;
    private final AgentWidgetConfigService widgetConfigService;
    private final TenantResolver tenantResolver;
    private final RequestParameterExtractor extractor;
    private final String webhookBaseUrl;
    private final String widgetBaseUrl;
    private final String triggerServiceUrl;
    private final RestTemplate triggerRestTemplate;

    public AgentController(
            AgentService agentService,
            AgentWebhookTokenService webhookTokenService,
            AgentWidgetConfigService widgetConfigService,
            TenantResolver tenantResolver,
            RequestParameterExtractor extractor,
            @Value("${agent.webhook.base-url:}") String webhookBaseUrl,
            @Value("${agent.widget.base-url:}") String widgetBaseUrl,
            @Value("${services.trigger-service.url:http://localhost:8091}") String triggerServiceUrl) {
        this.agentService = agentService;
        this.webhookTokenService = webhookTokenService;
        this.widgetConfigService = widgetConfigService;
        this.tenantResolver = tenantResolver;
        this.extractor = extractor;
        this.webhookBaseUrl = webhookBaseUrl;
        this.widgetBaseUrl = widgetBaseUrl;
        this.triggerServiceUrl = triggerServiceUrl;
        this.triggerRestTemplate = new RestTemplate();
    }

    /**
     * Create a new agent.
     */
    @PostMapping
    public ResponseEntity<?> createAgent(
            HttpServletRequest httpRequest,
            @RequestBody Map<String, Object> request) {

        String tenantId = tenantResolver.resolveOrNull(httpRequest);
        tenantResolver.validate(tenantId);

        String organizationId = tenantResolver.resolveOrgId(httpRequest);

        Map<String, Integer> guardOverrides;
        try {
            guardOverrides = extractor.extractIntegerMap(request, GUARD_OVERRIDE_KEYS);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "invalid_guard_override",
                "message", e.getMessage()
            ));
        }

        ResponseEntity<Object> compactionError = validateCompactionAfterTurns(request);
        if (compactionError != null) {
            return compactionError;
        }

        ResponseEntity<Object> inactivityError = validateInactivityTimeout(request);
        if (inactivityError != null) {
            return inactivityError;
        }

        ResponseEntity<Object> compactionModelError = validateCompactionModel(request);
        if (compactionModelError != null) {
            return compactionModelError;
        }

        AgentEntity created = agentService.createAgent(
            tenantId,
            extractor.getText(request, "name"),
            extractor.getText(request, "description"),
            extractor.getText(request, "systemPrompt"),
            extractor.getString(request, "modelProvider"),
            extractor.getString(request, "modelName"),
            extractor.getBigDecimal(request, "temperature"),
            extractor.getInteger(request, "maxTokens"),
            extractor.getInteger(request, "maxIterations"),
            extractor.getInteger(request, "executionTimeout"),
            extractor.getMap(request, "toolsConfig"),
            extractor.getUUID(request, "workflowId"),
            extractor.getLong(request, "dataSourceId"),
            extractor.getUUID(request, "conversationId"),
            extractor.getMap(request, "config"),
            extractor.getString(request, "avatarUrl"),
            extractor.getBoolean(request, "isPublic"),
            extractor.getBoolean(request, "isActive"),
            organizationId,
            extractor.getBigDecimal(request, "creditBudget"),
            extractor.getString(request, "budgetResetMode"),
            guardOverrides,
            extractor.getString(request, "reasoningEffort")
        );

        // V340 - opt-in backlog participation. Only applied when the client sends
        // the key (absent ⇒ default false from the column). Kept off the giant
        // createAgent signature on purpose; the dedicated setter runs its own scope gate.
        if (request.containsKey("backlogEnabled")) {
            created = agentService.setBacklogEnabled(
                created.getId(), tenantId, organizationId,
                Boolean.TRUE.equals(extractor.getBoolean(request, "backlogEnabled")));
        }

        // V350 - per-agent compaction enable + cadence. Dedicated setter (own scope
        // gate), kept off the giant createAgent signature like backlogEnabled above.
        if (request.containsKey("compactionEnabled") || request.containsKey("compactionAfterTurns")) {
            created = agentService.setCompactionOverrides(
                created.getId(), tenantId, organizationId,
                request.containsKey("compactionEnabled"), extractor.getBoolean(request, "compactionEnabled"),
                request.containsKey("compactionAfterTurns"), extractor.getInteger(request, "compactionAfterTurns"));
        }

        // V372 - per-agent inactivity watchdog window (seconds). Dedicated setter (own
        // scope gate), kept off the giant createAgent signature like backlogEnabled above.
        // Pre-validated, so the post-create setter never throws (no orphaned agent).
        if (request.containsKey("inactivityTimeout")) {
            created = agentService.setInactivityTimeout(
                created.getId(), tenantId, organizationId,
                extractor.getInteger(request, "inactivityTimeout"));
        }

        // V106 columns, user-facing write path - per-agent compaction SUMMARISER model.
        // Dedicated setter (own scope gate) like the compaction enable/cadence above.
        // Pre-validated (both-or-neither), so the post-create setter never throws.
        // getText (strict, throws on non-text) - a numeric JSON value must never be
        // toString-coerced into a model id (the "106735" content-corruption class).
        if (request.containsKey("compactionModelProvider") || request.containsKey("compactionModelName")) {
            created = agentService.setCompactionModel(
                created.getId(), tenantId, organizationId,
                extractor.getText(request, "compactionModelProvider"),
                extractor.getText(request, "compactionModelName"));
        }

        return ResponseEntity.ok(created);
    }

    private static final List<String> GUARD_OVERRIDE_KEYS = List.of(
        com.apimarketplace.agent.config.GuardOverrides.MAX_PER_RESOURCE_PER_TURN,
        com.apimarketplace.agent.config.GuardOverrides.LOOP_IDENTICAL_STOP,
        com.apimarketplace.agent.config.GuardOverrides.LOOP_CONSECUTIVE_STOP);

    /**
     * Validate the optional {@code compactionAfterTurns} on a create/update body
     * BEFORE persistence. Returns a 400 response when the value is present and
     * {@code < 1}; {@code null} when valid/absent so the caller proceeds. Mirrors
     * the guard-override 400 style (no silent clamp - an invalid cadence fails loud).
     */
    private ResponseEntity<Object> validateCompactionAfterTurns(Map<String, Object> request) {
        if (request.containsKey("compactionAfterTurns")) {
            Integer cat = extractor.getInteger(request, "compactionAfterTurns");
            if (cat != null && cat < 1) {
                return ResponseEntity.badRequest().<Object>body(Map.of(
                    "error", "invalid_compaction_after_turns",
                    "message", "compactionAfterTurns must be >= 1"));
            }
        }
        return null;
    }

    /**
     * Validate the optional {@code inactivityTimeout} (seconds) on a create/update body
     * BEFORE persistence. Returns a 400 response when the value is present and outside the
     * accepted set ({@code 0} = disabled, or {@code [10, 7200]}); {@code null} when
     * valid/absent so the caller proceeds. Pre-validating here means the dedicated
     * {@code setInactivityTimeout} setter (run AFTER create) never throws, so a bad value
     * cannot leave an orphaned agent. Mirrors {@link #validateCompactionAfterTurns}.
     */
    private ResponseEntity<Object> validateInactivityTimeout(Map<String, Object> request) {
        if (request.containsKey("inactivityTimeout")) {
            Integer it = extractor.getInteger(request, "inactivityTimeout");
            if (it != null && it != 0 && (it < 10 || it > 7200)) {
                return ResponseEntity.badRequest().<Object>body(Map.of(
                    "error", "invalid_inactivity_timeout",
                    "message", "inactivityTimeout must be 0 (disabled) or between 10 and 7200 (seconds)"));
            }
        }
        return null;
    }

    /**
     * Validate the optional compaction summariser model pair on a create/update body
     * BEFORE persistence: {@code compactionModelProvider} and {@code compactionModelName}
     * must be set together (both non-blank) or cleared together (both null/blank) - the
     * resolver treats a partial pair as unset, so persisting one would silently do
     * nothing. Returns 400 on a partial pair; {@code null} when valid/absent. Mirrors
     * {@link #validateInactivityTimeout} so the post-create setter never throws.
     */
    private ResponseEntity<Object> validateCompactionModel(Map<String, Object> request) {
        if (request.containsKey("compactionModelProvider") || request.containsKey("compactionModelName")) {
            String provider;
            String name;
            try {
                // getText (strict): a numeric JSON value is a client bug, not a model id -
                // reject it here instead of toString-coercing ("106735" corruption class).
                provider = extractor.getText(request, "compactionModelProvider");
                name = extractor.getText(request, "compactionModelName");
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().<Object>body(Map.of(
                    "error", "invalid_compaction_model",
                    "message", e.getMessage()));
            }
            boolean hasProvider = provider != null && !provider.isBlank();
            boolean hasName = name != null && !name.isBlank();
            if (hasProvider != hasName) {
                return ResponseEntity.badRequest().<Object>body(Map.of(
                    "error", "invalid_compaction_model",
                    "message", "compactionModelProvider and compactionModelName must be set together (or both cleared)"));
            }
        }
        return null;
    }

    /**
     * List agents for a tenant (org-aware).
     */
    @GetMapping
    public ResponseEntity<List<AgentEntity>> listAgents(
            HttpServletRequest request,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {

        String tenantId = tenantResolver.resolveOrNull(request);
        String orgId = tenantResolver.resolveOrgId(request);
        String orgRole = tenantResolver.resolveOrgRole(request);
        List<AgentEntity> agents = agentService.listAgents(tenantId, orgId, orgRole);
        return ResponseEntity.ok(agents);
    }

    /**
     * Paged, DB-searchable, server-sorted + server-visibility-filtered list. Returns
     * {@code { items, totalCount, page, size, publicationStatuses }}. {@code publicationStatuses} maps
     * each shared agent id on the page to {@code {status, rejectionReason?}} (absent = not shared),
     * batched server-side so the card needs no per-row publication call. {@code sort} = name |
     * lastModified (default lastModified), {@code visibility} = all | public | private (default all).
     */
    @GetMapping("/paged")
    public ResponseEntity<java.util.Map<String, Object>> listAgentsPaged(
            HttpServletRequest request,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "25") int size,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "visibility", required = false) String visibility) {

        String tenantId = tenantResolver.resolveOrNull(request);
        String orgId = tenantResolver.resolveOrgId(request);
        String orgRole = tenantResolver.resolveOrgRole(request);

        AgentService.AgentPage pageResult = agentService.listAgentsPaged(
                tenantId, orgId, orgRole, q, page, size, sort, visibility);

        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("items", pageResult.items());
        body.put("totalCount", pageResult.totalCount());
        body.put("page", pageResult.page());
        body.put("size", pageResult.size());
        // Per-agent publication badge for the page (id -> {status, rejectionReason?}), batched
        // server-side - replaces the former full getAllMyPublications sweep on the client.
        body.put("publicationStatuses", pageResult.publicationStatuses());
        return ResponseEntity.ok(body);
    }

    /**
     * List (id, avatarUrl) for every agent visible to the caller - backs the
     * conversation sidebar's avatar map. Lightweight projection: returns ~80
     * bytes per row vs. the full {@code GET /api/agents} entity dump (which
     * carries system_prompt LOBs and config blobs). Single round-trip; the
     * caller caches client-side via React Query.
     *
     * <p>Org-aware via {@link AgentService#listAgentAvatars}.
     *
     * GET /api/agents/avatars
     */
    @GetMapping("/avatars")
    public ResponseEntity<List<AgentAvatarResponse>> listAgentAvatars(
            HttpServletRequest request,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {

        String tenantId = tenantResolver.resolveOrNull(request);
        String orgId = tenantResolver.resolveOrgId(request);
        String orgRole = tenantResolver.resolveOrgRole(request);
        return ResponseEntity.ok(agentService.listAgentAvatars(tenantId, orgId, orgRole));
    }

    /**
     * Get an agent by conversationId.
     * Note: This route must be defined BEFORE the generic /{id} route.
     */
    @GetMapping("/by-conversation/{conversationId}")
    public ResponseEntity<AgentEntity> getAgentByConversationId(
            @PathVariable("conversationId") UUID conversationId,
            HttpServletRequest request,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {

        String tenantId = tenantResolver.resolveOrNull(request);
        tenantResolver.validate(tenantId);

        Optional<AgentEntity> opt = agentService.getAgentByConversationId(conversationId, tenantId);
        return opt.map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Get an agent by ID.
     */
    @GetMapping("/{id:[0-9a-fA-F\\-]{36}}")
    public ResponseEntity<AgentEntity> getAgent(
            @PathVariable("id") UUID id,
            HttpServletRequest request,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole) {

        String tenantId = tenantResolver.resolveOrNull(request);
        // Audit 2026-05-16 MF: thread X-Organization-ID so org-teammates can read
        // shared agents. Without it the service falls back to strict tenant.
        Optional<AgentEntity> opt = agentService.getAgent(id, tenantId, orgId, orgRole);
        return opt.map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Server-managed budget fields that MUST NOT appear on a PUT request body.
     * They are computed server-side (see {@code buildBudgetResponse} / cascade reservation
     * in {@code SubAgentExecutionHandler}) and exposing them as writable would let clients
     * bypass the sub-agent budget hierarchy guarantees (§6.1 AGENT_BUDGET_HIERARCHY.md).
     */
    private static final List<String> READ_ONLY_BUDGET_FIELDS =
        List.of("creditsReserved", "creditsFree", "creditsConsumed", "creditsConsumedFromSubagents");

    /**
     * Update an agent.
     */
    @PutMapping("/{id:[0-9a-fA-F\\-]{36}}")
    public ResponseEntity<?> updateAgent(
            @PathVariable("id") UUID id,
            HttpServletRequest httpRequest,
            @RequestHeader(value = "X-Organization-ID", required = false) String callerOrgId,
            @RequestBody Map<String, Object> request) {

        // Reject requests that try to mutate server-managed budget fields.
        // Fail fast (400) rather than silently stripping - makes the contract obvious
        // to both humans and LLMs, and catches tooling drift.
        for (String forbidden : READ_ONLY_BUDGET_FIELDS) {
            if (request.containsKey(forbidden)) {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "read_only_field",
                    "field", forbidden,
                    "message", "'" + forbidden + "' is managed by the server and cannot be set via PUT. "
                        + "Use 'creditBudget' to change the cap, or POST /budget/reset to zero consumption."
                ));
            }
        }

        String tenantId = tenantResolver.resolveOrNull(httpRequest);

        // Handle conversationId with explicit clearing support
        UUID conversationId = null;
        if (request.containsKey("conversationId")) {
            Object conversationIdObj = request.get("conversationId");
            if (conversationIdObj != null) {
                String conversationIdStr = conversationIdObj.toString();
                if (!conversationIdStr.isBlank()) {
                    conversationId = UUID.fromString(conversationIdStr);
                }
            }
        }

        Map<String, Integer> guardOverrides;
        try {
            guardOverrides = extractor.extractIntegerMap(request, GUARD_OVERRIDE_KEYS);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "invalid_guard_override",
                "message", e.getMessage()
            ));
        }

        ResponseEntity<Object> compactionError = validateCompactionAfterTurns(request);
        if (compactionError != null) {
            return compactionError;
        }

        ResponseEntity<Object> inactivityError = validateInactivityTimeout(request);
        if (inactivityError != null) {
            return inactivityError;
        }

        ResponseEntity<Object> compactionModelError = validateCompactionModel(request);
        if (compactionModelError != null) {
            return compactionModelError;
        }

        AgentEntity updated = agentService.updateAgent(
            id,
            tenantId,
            extractor.getText(request, "name"),
            extractor.getText(request, "description"),
            extractor.getText(request, "systemPrompt"),
            extractor.getString(request, "modelProvider"),
            extractor.getString(request, "modelName"),
            extractor.getBigDecimal(request, "temperature"),
            extractor.getInteger(request, "maxTokens"),
            extractor.getInteger(request, "maxIterations"),
            extractor.getInteger(request, "executionTimeout"),
            extractor.getMap(request, "toolsConfig"),
            extractor.getUUID(request, "workflowId"),
            extractor.getLong(request, "dataSourceId"),
            conversationId,
            extractor.getMap(request, "config"),
            extractor.getString(request, "avatarUrl"),
            extractor.getBoolean(request, "isPublic"),
            extractor.getBoolean(request, "isActive"),
            extractor.getBigDecimal(request, "creditBudget"),
            extractor.getString(request, "budgetResetMode"),
            guardOverrides,
            callerOrgId,
            extractor.getString(request, "reasoningEffort"),
            request.containsKey("creditBudget")
        );

        // V340 - patch semantics: absent ⇒ unchanged; present ⇒ set the opt-in flag.
        if (request.containsKey("backlogEnabled")) {
            updated = agentService.setBacklogEnabled(
                id, tenantId, callerOrgId,
                Boolean.TRUE.equals(extractor.getBoolean(request, "backlogEnabled")));
        }

        // V350 - per-agent compaction enable + cadence (patch semantics).
        if (request.containsKey("compactionEnabled") || request.containsKey("compactionAfterTurns")) {
            updated = agentService.setCompactionOverrides(
                id, tenantId, callerOrgId,
                request.containsKey("compactionEnabled"), extractor.getBoolean(request, "compactionEnabled"),
                request.containsKey("compactionAfterTurns"), extractor.getInteger(request, "compactionAfterTurns"));
        }

        // V372 - per-agent inactivity watchdog window (patch semantics: absent => unchanged).
        if (request.containsKey("inactivityTimeout")) {
            updated = agentService.setInactivityTimeout(
                id, tenantId, callerOrgId,
                extractor.getInteger(request, "inactivityTimeout"));
        }

        // Per-agent compaction SUMMARISER model (patch semantics: absent => unchanged;
        // both blank/null => clear back to inherit). Pre-validated both-or-neither.
        // getText (strict) - see the create-path note on the "106735" coercion class.
        if (request.containsKey("compactionModelProvider") || request.containsKey("compactionModelName")) {
            updated = agentService.setCompactionModel(
                id, tenantId, callerOrgId,
                extractor.getText(request, "compactionModelProvider"),
                extractor.getText(request, "compactionModelName"));
        }

        return ResponseEntity.ok(updated);
    }

    /**
     * Reset credits consumed for an agent.
     *
     * <p>Returns 409 CONFLICT when the agent has a non-zero {@code credits_reserved}
     * balance - i.e. a sub-agent cascade reservation is still in flight. Resetting
     * consumed credits while reservations are pending would corrupt the invariant
     * {@code consumed + reserved ≤ total} that the cascade enforcement relies on
     * (§6.2 AGENT_BUDGET_HIERARCHY.md).
     */
    @PostMapping("/{id:[0-9a-fA-F\\-]{36}}/budget/reset")
    public ResponseEntity<Map<String, Object>> resetCredits(
            @PathVariable("id") UUID id,
            HttpServletRequest request,
            @RequestHeader(value = "X-Organization-ID", required = false) String callerOrgId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole) {

        String tenantId = tenantResolver.resolveOrNull(request);
        boolean applied = agentService.resetCredits(id, tenantId, orgRole, callerOrgId);
        if (!applied) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "reservation_in_flight",
                "agentId", id.toString(),
                "message", "Cannot reset credits while a sub-agent reservation is in flight. "
                    + "Wait for descendants to finish, then retry."
            ));
        }
        return ResponseEntity.ok(Map.of("status", "reset", "agentId", id.toString()));
    }

    /**
     * Clone an agent.
     */
    @PostMapping("/{id:[0-9a-fA-F\\-]{36}}/clone")
    public ResponseEntity<AgentEntity> cloneAgent(
            @PathVariable("id") UUID id,
            HttpServletRequest request,
            @RequestHeader(value = "X-Organization-ID", required = false) String callerOrgId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole) {

        String tenantId = tenantResolver.resolveOrNull(request);
        AgentEntity cloned = agentService.cloneAgent(id, tenantId, orgRole, callerOrgId);
        return ResponseEntity.ok(cloned);
    }

    /**
     * Delete an agent.
     */
    @DeleteMapping("/{id:[0-9a-fA-F\\-]{36}}")
    public ResponseEntity<Void> deleteAgent(
            @PathVariable("id") UUID id,
            HttpServletRequest request,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestHeader(value = "X-Organization-ID", required = false) String callerOrgId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole) {

        String tenantId = tenantResolver.resolveOrNull(request);
        // PR-2.b: pass gateway-validated org-role so the deny-list check in the
        // service runs with the correct admin-bypass / member-restriction logic.
        // callerOrgId (also gateway-validated) lets the service do the positive
        // owner-or-org scope check before falling through to deny-list.
        agentService.deleteAgent(id, tenantId, orgRole, callerOrgId);
        return ResponseEntity.ok().build();
    }

    // ========== Webhook Management Endpoints ==========

    /**
     * Create or update webhook for an agent.
     */
    @PostMapping("/{id:[0-9a-fA-F\\-]{36}}/webhook")
    public ResponseEntity<Map<String, Object>> createOrUpdateWebhook(
            @PathVariable("id") UUID id,
            HttpServletRequest httpRequest,
            @RequestBody(required = false) Map<String, Object> request) {

        String tenantId = tenantResolver.resolveOrNull(httpRequest);
        tenantResolver.validate(tenantId);
        String orgId = httpRequest.getHeader("X-Organization-ID");
        String orgRole = httpRequest.getHeader("X-Organization-Role");

        // 2026-05-18 - workspace-scoped: use the 4-arg getAgent so org-teammates
        // can manage the webhook AND personal-scope owner cannot accidentally
        // hit this endpoint while their active session is an org.
        Optional<AgentEntity> agentOpt = agentService.getAgent(id, tenantId, orgId, orgRole);
        if (agentOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        // Per-resource write gate: a MEMBER with a READ/DENY restriction on this
        // agent can READ it (above) but must NOT mutate its webhook config.
        agentService.assertCanWriteAgent(id, tenantId, orgId);

        String httpMethod = request != null ? extractor.getString(request, "httpMethod") : null;
        String authType = request != null ? extractor.getString(request, "authType") : null;
        Map<String, Object> authConfig = request != null ? extractor.getMap(request, "authConfig") : null;
        Boolean memoryEnabled = request != null && request.get("memoryEnabled") instanceof Boolean b ? b : null;

        AgentWebhookTokenEntity webhook = webhookTokenService.createOrUpdateWebhook(
            id, httpMethod, authType, authConfig, memoryEnabled
        );

        return ResponseEntity.ok(buildWebhookResponse(webhook, httpRequest));
    }

    /**
     * Get webhook configuration for an agent.
     */
    @GetMapping("/{id:[0-9a-fA-F\\-]{36}}/webhook")
    public ResponseEntity<Map<String, Object>> getWebhook(
            @PathVariable("id") UUID id,
            HttpServletRequest httpRequest,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {

        String tenantId = tenantResolver.resolveOrNull(httpRequest);
        String orgId = httpRequest.getHeader("X-Organization-ID");
        String orgRole = httpRequest.getHeader("X-Organization-Role");

        // 2026-05-18 - workspace-scoped 4-arg getAgent (see createOrUpdateWebhook).
        Optional<AgentEntity> agentOpt = agentService.getAgent(id, tenantId, orgId, orgRole);
        if (agentOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Optional<AgentWebhookTokenEntity> webhookOpt = webhookTokenService.getWebhookForAgent(id);
        if (webhookOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(buildWebhookResponse(webhookOpt.get(), httpRequest));
    }

    /**
     * Regenerate webhook token for an agent.
     */
    @PostMapping("/{id:[0-9a-fA-F\\-]{36}}/webhook/regenerate")
    public ResponseEntity<Map<String, Object>> regenerateWebhookToken(
            @PathVariable("id") UUID id,
            HttpServletRequest httpRequest) {

        String tenantId = tenantResolver.resolveOrNull(httpRequest);
        tenantResolver.validate(tenantId);
        String orgId = httpRequest.getHeader("X-Organization-ID");
        String orgRole = httpRequest.getHeader("X-Organization-Role");

        // 2026-05-18 - workspace-scoped 4-arg getAgent (see createOrUpdateWebhook).
        Optional<AgentEntity> agentOpt = agentService.getAgent(id, tenantId, orgId, orgRole);
        if (agentOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        // Per-resource write gate (see createOrUpdateWebhook).
        agentService.assertCanWriteAgent(id, tenantId, orgId);

        try {
            webhookTokenService.regenerateToken(id);
            Optional<AgentWebhookTokenEntity> webhookOpt = webhookTokenService.getWebhookForAgent(id);
            if (webhookOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(buildWebhookResponse(webhookOpt.get(), httpRequest));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Enable or disable webhook for an agent.
     */
    @PatchMapping("/{id:[0-9a-fA-F\\-]{36}}/webhook")
    public ResponseEntity<Map<String, Object>> setWebhookActive(
            @PathVariable("id") UUID id,
            HttpServletRequest httpRequest,
            @RequestBody Map<String, Object> request) {

        String tenantId = tenantResolver.resolveOrNull(httpRequest);
        tenantResolver.validate(tenantId);
        String orgId = httpRequest.getHeader("X-Organization-ID");
        String orgRole = httpRequest.getHeader("X-Organization-Role");

        // 2026-05-18 - workspace-scoped 4-arg getAgent (see createOrUpdateWebhook).
        Optional<AgentEntity> agentOpt = agentService.getAgent(id, tenantId, orgId, orgRole);
        if (agentOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        // Per-resource write gate (see createOrUpdateWebhook).
        agentService.assertCanWriteAgent(id, tenantId, orgId);

        Boolean active = extractor.getBoolean(request, "active");
        if (active == null) {
            return ResponseEntity.badRequest().build();
        }

        webhookTokenService.setWebhookActive(id, active);

        Optional<AgentWebhookTokenEntity> webhookOpt = webhookTokenService.getWebhookForAgent(id);
        if (webhookOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(buildWebhookResponse(webhookOpt.get(), httpRequest));
    }

    /**
     * Delete webhook for an agent.
     */
    @DeleteMapping("/{id:[0-9a-fA-F\\-]{36}}/webhook")
    public ResponseEntity<Void> deleteWebhook(
            @PathVariable("id") UUID id,
            HttpServletRequest httpRequest) {

        String tenantId = tenantResolver.resolveOrNull(httpRequest);
        tenantResolver.validate(tenantId);
        String orgId = httpRequest.getHeader("X-Organization-ID");
        String orgRole = httpRequest.getHeader("X-Organization-Role");

        // 2026-05-18 - workspace-scoped 4-arg getAgent (see createOrUpdateWebhook).
        Optional<AgentEntity> agentOpt = agentService.getAgent(id, tenantId, orgId, orgRole);
        if (agentOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        // Per-resource write gate (see createOrUpdateWebhook).
        agentService.assertCanWriteAgent(id, tenantId, orgId);

        webhookTokenService.deleteWebhook(id);
        return ResponseEntity.ok().build();
    }

    /**
     * Build webhook response with URL.
     */
    private Map<String, Object> buildWebhookResponse(AgentWebhookTokenEntity webhook, HttpServletRequest request) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("agentId", webhook.getAgentId().toString());
        response.put("token", webhook.getToken());
        response.put("httpMethod", webhook.getHttpMethod());
        response.put("authType", webhook.getAuthType());
        response.put("isActive", webhook.getIsActive());
        response.put("memoryEnabled", Boolean.TRUE.equals(webhook.getMemoryEnabled()));
        response.put("createdAt", webhook.getCreatedAt().toString());
        response.put("updatedAt", webhook.getUpdatedAt().toString());

        response.put("webhookUrl", webhookTokenService.getWebhookUrl(resolveWebhookBaseUrl(request), webhook.getToken()));

        return response;
    }

    /**
     * Configured webhook base URL, falling back to the request's scheme/host/port.
     */
    private String resolveWebhookBaseUrl(HttpServletRequest request) {
        String baseUrl = webhookBaseUrl;
        if (baseUrl == null || baseUrl.isBlank()) {
            String scheme = request.getScheme();
            String serverName = request.getServerName();
            int serverPort = request.getServerPort();
            if ((scheme.equals("http") && serverPort == 80) || (scheme.equals("https") && serverPort == 443)) {
                baseUrl = scheme + "://" + serverName;
            } else {
                baseUrl = scheme + "://" + serverName + ":" + serverPort;
            }
        }
        return baseUrl;
    }

    /**
     * Fleet batch - webhook + schedule trigger state for EVERY workspace agent in
     * ONE call. Returns one row per agent that has an ACTIVE webhook or an ENABLED
     * schedule (agents with neither are omitted, matching the per-agent 404). This
     * replaces the GET /{id}/webhook + GET /{id}/schedule fan-out (2 requests per
     * agent, nearly all 404) the Agent Fleet canvas otherwise makes.
     *
     * <p>Row shape: {@code {agentId, hasWebhook, hasSchedule, webhookUrl?, cronExpression?, timezone?}}.
     */
    @GetMapping("/triggers")
    public ResponseEntity<List<Map<String, Object>>> getFleetTriggers(HttpServletRequest httpRequest) {
        String tenantId = tenantResolver.resolveOrNull(httpRequest);
        tenantResolver.validate(tenantId);
        String orgId = tenantResolver.resolveOrgId(httpRequest);
        String orgRole = tenantResolver.resolveOrgRole(httpRequest);

        Map<UUID, Map<String, Object>> byAgent = new LinkedHashMap<>();

        // 1) Active webhooks across the workspace.
        String baseUrl = resolveWebhookBaseUrl(httpRequest);
        for (AgentWebhookTokenEntity wh : webhookTokenService.findActiveByOrganization(orgId)) {
            Map<String, Object> row = byAgent.computeIfAbsent(wh.getAgentId(), this::newTriggerRow);
            row.put("hasWebhook", true);
            row.put("webhookUrl", webhookTokenService.getWebhookUrl(baseUrl, wh.getToken()));
        }

        // 2) Enabled agent schedules across the workspace (proxied from trigger-service).
        try {
            HttpHeaders headers = buildTriggerScopeHeaders(tenantId, orgId, orgRole);
            ResponseEntity<List> resp = triggerRestTemplate.exchange(
                    triggerServiceUrl + "/api/internal/trigger/schedules/by-tenant/" + tenantId,
                    HttpMethod.GET, new HttpEntity<>(headers), List.class);
            List<Map<String, Object>> schedules = resp.getBody();
            if (schedules != null) {
                for (Map<String, Object> s : schedules) {
                    Object agentRaw = s.get("agentEntityId");
                    if (agentRaw == null || !Boolean.TRUE.equals(s.get("enabled"))) {
                        continue; // workflow schedule, or a disabled agent schedule
                    }
                    UUID agentId;
                    try {
                        agentId = UUID.fromString(agentRaw.toString());
                    } catch (IllegalArgumentException e) {
                        continue;
                    }
                    Map<String, Object> row = byAgent.computeIfAbsent(agentId, this::newTriggerRow);
                    row.put("hasSchedule", true);
                    row.put("cronExpression", s.get("cronExpression"));
                    row.put("timezone", s.get("timezone"));
                }
            }
        } catch (Exception e) {
            // A trigger-service hiccup must not blank the whole fleet - webhooks still return.
            logger.warn("Fleet triggers: schedule batch lookup failed: {}", e.getMessage());
        }

        return ResponseEntity.ok(List.copyOf(byAgent.values()));
    }

    private Map<String, Object> newTriggerRow(UUID agentId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("agentId", agentId.toString());
        row.put("hasWebhook", false);
        row.put("hasSchedule", false);
        return row;
    }

    // ========== Schedule Endpoints ==========

    /**
     * Create or update schedule for an agent.
     */
    @PostMapping("/{id:[0-9a-fA-F\\-]{36}}/schedule")
    public ResponseEntity<?> createOrUpdateSchedule(
            @PathVariable("id") UUID id,
            HttpServletRequest httpRequest,
            @RequestBody Map<String, Object> body) {

        String tenantId = tenantResolver.resolveOrNull(httpRequest);
        tenantResolver.validate(tenantId);
        String orgId = tenantResolver.resolveOrgId(httpRequest);
        String orgRole = tenantResolver.resolveOrgRole(httpRequest);

        Optional<AgentEntity> agentOpt = agentService.getAgent(id, tenantId, orgId, orgRole);
        if (agentOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        // Per-resource write gate: a READ/DENY-restricted MEMBER can READ the agent
        // but must NOT create/update its schedule in trigger-service.
        agentService.assertCanWriteAgent(id, tenantId, orgId);

        Object cronValue = body.get("cron") != null ? body.get("cron") : body.get("cronExpression");
        String cron = cronValue != null ? cronValue.toString() : null;
        if (cron == null || cron.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "cron expression is required"));
        }

        Map<String, Object> triggerBody = new LinkedHashMap<>();
        triggerBody.put("agentEntityId", id.toString());
        triggerBody.put("tenantId", tenantId);
        if (orgId != null && !orgId.isBlank()) {
            triggerBody.put("organizationId", orgId);
        }
        triggerBody.put("cronExpression", cron);
        triggerBody.put("timezone", body.getOrDefault("timezone", "UTC"));
        triggerBody.put("maxExecutions", body.get("maxExecutions"));
        // Forward schedule_prompt / with_memory ONLY when the caller actually supplies
        // them. A partial edit (e.g. cron-only) must not carry null/false downstream and
        // clobber the stored prompt/memory - trigger-service preserves omitted fields on
        // update (prod incident 2026-06-14: a cron-only save blanked the prompt, after
        // which every hourly fire short-circuited on "no effective prompt → skipping").
        if (body.containsKey("schedulePrompt")) {
            triggerBody.put("schedulePrompt", body.get("schedulePrompt"));
        }
        if (body.containsKey("withMemory")) {
            triggerBody.put("withMemory", body.get("withMemory"));
        }
        if (body.containsKey("enabled")) {
            triggerBody.put("enabled", body.get("enabled"));
        }
        triggerBody.put("name", agentOpt.get().getName() + " Schedule");

        try {
            HttpHeaders headers = buildTriggerScopeHeaders(tenantId, orgId, orgRole);
            headers.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<Map> resp = triggerRestTemplate.exchange(
                    triggerServiceUrl + "/api/internal/trigger/schedules/agent",
                    HttpMethod.POST, new HttpEntity<>(triggerBody, headers), Map.class);
            return ResponseEntity.ok(resp.getBody());
        } catch (Exception e) {
            logger.error("Failed to create agent schedule for agent={}: {}", id, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to create schedule"));
        }
    }

    /**
     * Get schedule for an agent.
     */
    @GetMapping("/{id:[0-9a-fA-F\\-]{36}}/schedule")
    public ResponseEntity<?> getSchedule(
            @PathVariable("id") UUID id,
            HttpServletRequest httpRequest) {

        String tenantId = tenantResolver.resolveOrNull(httpRequest);
        tenantResolver.validate(tenantId);
        String orgId = tenantResolver.resolveOrgId(httpRequest);
        String orgRole = tenantResolver.resolveOrgRole(httpRequest);

        if (agentService.getAgent(id, tenantId, orgId, orgRole).isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        try {
            HttpHeaders headers = buildTriggerScopeHeaders(tenantId, orgId, orgRole);
            ResponseEntity<Map> resp = triggerRestTemplate.exchange(
                    triggerServiceUrl + "/api/internal/trigger/schedules/by-agent/" + id + "?tenantId=" + tenantId,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    Map.class);
            return ResponseEntity.ok(resp.getBody());
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            logger.error("Failed to get agent schedule for agent={}: {}", id, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to get schedule"));
        }
    }

    /**
     * Toggle schedule enabled/disabled.
     */
    @PatchMapping("/{id:[0-9a-fA-F\\-]{36}}/schedule")
    public ResponseEntity<?> toggleSchedule(
            @PathVariable("id") UUID id,
            HttpServletRequest httpRequest,
            @RequestBody Map<String, Object> body) {

        String tenantId = tenantResolver.resolveOrNull(httpRequest);
        tenantResolver.validate(tenantId);
        String orgId = tenantResolver.resolveOrgId(httpRequest);
        String orgRole = tenantResolver.resolveOrgRole(httpRequest);

        if (agentService.getAgent(id, tenantId, orgId, orgRole).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        // Per-resource write gate (see createOrUpdateSchedule).
        agentService.assertCanWriteAgent(id, tenantId, orgId);

        boolean enabled = Boolean.parseBoolean(body.getOrDefault("enabled", "true").toString());

        try {
            HttpHeaders headers = buildTriggerScopeHeaders(tenantId, orgId, orgRole);
            headers.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<Map> resp = triggerRestTemplate.exchange(
                    triggerServiceUrl + "/api/internal/trigger/schedules/by-agent/" + id
                            + "/toggle?tenantId=" + tenantId + "&enabled=" + enabled,
                    HttpMethod.PUT, new HttpEntity<>(headers), Map.class);
            return ResponseEntity.ok(resp.getBody());
        } catch (Exception e) {
            logger.error("Failed to toggle agent schedule for agent={}: {}", id, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to toggle schedule"));
        }
    }

    /**
     * Delete schedule for an agent.
     */
    @DeleteMapping("/{id:[0-9a-fA-F\\-]{36}}/schedule")
    public ResponseEntity<Void> deleteSchedule(
            @PathVariable("id") UUID id,
            HttpServletRequest httpRequest,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole) {

        // Scope-check: caller must own the agent OR be in the agent's org.
        // Audit 2026-05-16 - prior implementation had NO scope guard, allowing
        // cross-tenant schedule deletion by knowing only the agentId.
        String tenantId = tenantResolver.resolveOrNull(httpRequest);
        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.status(401).build();
        }
        if (agentService.getAgent(id, tenantId, orgId, orgRole).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        // Per-resource write gate (see createOrUpdateSchedule).
        agentService.assertCanWriteAgent(id, tenantId, orgId);

        try {
            HttpHeaders headers = buildTriggerScopeHeaders(tenantId, orgId, orgRole);
            triggerRestTemplate.exchange(
                    triggerServiceUrl + "/api/internal/trigger/schedules/by-agent/" + id,
                    HttpMethod.DELETE,
                    new HttpEntity<>(headers),
                    Void.class);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            logger.error("Failed to delete agent schedule for agent={}: {}", id, e.getMessage());
            return ResponseEntity.noContent().build();
        }
    }

    private HttpHeaders buildTriggerScopeHeaders(String tenantId, String orgId) {
        return buildTriggerScopeHeaders(tenantId, orgId, null);
    }

    private HttpHeaders buildTriggerScopeHeaders(String tenantId, String orgId, String orgRole) {
        HttpHeaders headers = new HttpHeaders();
        if (tenantId != null && !tenantId.isBlank()) {
            headers.set("X-User-ID", tenantId);
        }
        if (orgId != null && !orgId.isBlank()) {
            headers.set("X-Organization-ID", orgId);
        }
        if (orgRole != null && !orgRole.isBlank()) {
            headers.set("X-Organization-Role", orgRole);
        }
        return headers;
    }

    // ========== Widget Configuration Endpoints ==========

    /**
     * Create or update widget configuration for an agent.
     */
    @PostMapping("/{id:[0-9a-fA-F\\-]{36}}/widget")
    public ResponseEntity<Map<String, Object>> createOrUpdateWidgetConfig(
            @PathVariable("id") UUID id,
            HttpServletRequest httpRequest,
            @RequestBody(required = false) Map<String, Object> request) {

        String tenantId = tenantResolver.resolveOrNull(httpRequest);
        String orgId = tenantResolver.resolveOrgId(httpRequest);
        String orgRole = tenantResolver.resolveOrgRole(httpRequest);
        tenantResolver.validate(tenantId);

        // Verify agent exists and belongs to tenant
        Optional<AgentEntity> agentOpt = agentService.getAgent(id, tenantId, orgId, orgRole);
        if (agentOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        // Per-resource write gate: a READ/DENY-restricted MEMBER can READ the agent
        // but must NOT mutate its widget config.
        agentService.assertCanWriteAgent(id, tenantId, orgId);

        // Extract and validate parameters
        String position = request != null ? extractor.getString(request, "position") : null;
        String theme = request != null ? extractor.getString(request, "theme") : null;
        String primaryColor = request != null ? extractor.getString(request, "primaryColor") : null;
        String welcomeMessage = request != null ? extractor.getText(request, "welcomeMessage") : null;
        String bubbleText = request != null ? extractor.getText(request, "bubbleText") : null;
        Boolean showAvatar = request != null ? extractor.getBoolean(request, "showAvatar") : null;
        Integer autoOpenDelay = request != null ? extractor.getInteger(request, "autoOpenDelay") : null;
        String allowedOrigins = request != null ? extractor.getString(request, "allowedOrigins") : null;

        // Validate inputs
        if (!widgetConfigService.isValidPosition(position)) {
            return ResponseEntity.badRequest().build();
        }
        if (!widgetConfigService.isValidTheme(theme)) {
            return ResponseEntity.badRequest().build();
        }
        if (!widgetConfigService.isValidColor(primaryColor)) {
            return ResponseEntity.badRequest().build();
        }

        AgentWidgetConfigEntity widget = widgetConfigService.createOrUpdateWidgetConfig(
            id, position, theme, primaryColor, welcomeMessage, bubbleText,
            showAvatar, autoOpenDelay, allowedOrigins
        );

        return ResponseEntity.ok(buildWidgetResponse(widget, agentOpt.get(), httpRequest));
    }

    /**
     * Get widget configuration for an agent.
     */
    @GetMapping("/{id:[0-9a-fA-F\\-]{36}}/widget")
    public ResponseEntity<Map<String, Object>> getWidgetConfig(
            @PathVariable("id") UUID id,
            HttpServletRequest httpRequest,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {

        String tenantId = tenantResolver.resolveOrNull(httpRequest);
        String orgId = tenantResolver.resolveOrgId(httpRequest);
        String orgRole = tenantResolver.resolveOrgRole(httpRequest);

        // Verify agent exists and belongs to tenant
        Optional<AgentEntity> agentOpt = agentService.getAgent(id, tenantId, orgId, orgRole);
        if (agentOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Optional<AgentWidgetConfigEntity> widgetOpt = widgetConfigService.getWidgetConfig(id);
        if (widgetOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(buildWidgetResponse(widgetOpt.get(), agentOpt.get(), httpRequest));
    }

    /**
     * Enable or disable widget for an agent.
     */
    @PatchMapping("/{id:[0-9a-fA-F\\-]{36}}/widget")
    public ResponseEntity<Map<String, Object>> setWidgetActive(
            @PathVariable("id") UUID id,
            HttpServletRequest httpRequest,
            @RequestBody Map<String, Object> request) {

        String tenantId = tenantResolver.resolveOrNull(httpRequest);
        String orgId = tenantResolver.resolveOrgId(httpRequest);
        String orgRole = tenantResolver.resolveOrgRole(httpRequest);
        tenantResolver.validate(tenantId);

        // Verify agent exists and belongs to tenant
        Optional<AgentEntity> agentOpt = agentService.getAgent(id, tenantId, orgId, orgRole);
        if (agentOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        // Per-resource write gate (see createOrUpdateWidgetConfig).
        agentService.assertCanWriteAgent(id, tenantId, orgId);

        Boolean active = extractor.getBoolean(request, "active");
        if (active == null) {
            return ResponseEntity.badRequest().build();
        }

        widgetConfigService.setWidgetActive(id, active);

        Optional<AgentWidgetConfigEntity> widgetOpt = widgetConfigService.getWidgetConfig(id);
        if (widgetOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(buildWidgetResponse(widgetOpt.get(), agentOpt.get(), httpRequest));
    }

    /**
     * Delete widget configuration for an agent.
     */
    @DeleteMapping("/{id:[0-9a-fA-F\\-]{36}}/widget")
    public ResponseEntity<Void> deleteWidgetConfig(
            @PathVariable("id") UUID id,
            HttpServletRequest httpRequest) {

        String tenantId = tenantResolver.resolveOrNull(httpRequest);
        String orgId = tenantResolver.resolveOrgId(httpRequest);
        String orgRole = tenantResolver.resolveOrgRole(httpRequest);
        tenantResolver.validate(tenantId);

        // Verify agent exists and belongs to tenant
        Optional<AgentEntity> agentOpt = agentService.getAgent(id, tenantId, orgId, orgRole);
        if (agentOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        // Per-resource write gate (see createOrUpdateWidgetConfig).
        agentService.assertCanWriteAgent(id, tenantId, orgId);

        widgetConfigService.deleteWidgetConfig(id);
        return ResponseEntity.ok().build();
    }

    /**
     * Build widget configuration response.
     */
    private Map<String, Object> buildWidgetResponse(AgentWidgetConfigEntity widget, AgentEntity agent, HttpServletRequest request) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("agentId", widget.getAgentId().toString());
        response.put("widgetToken", widget.getWidgetToken());
        response.put("position", widget.getPosition());
        response.put("theme", widget.getTheme());
        response.put("primaryColor", widget.getPrimaryColor());
        response.put("welcomeMessage", widget.getWelcomeMessage());
        response.put("bubbleText", widget.getBubbleText());
        response.put("showAvatar", widget.getShowAvatar());
        response.put("autoOpenDelay", widget.getAutoOpenDelay());
        response.put("allowedOrigins", widget.getAllowedOrigins());
        response.put("isActive", widget.getIsActive());
        response.put("createdAt", widget.getCreatedAt().toString());
        response.put("updatedAt", widget.getUpdatedAt().toString());

        // Include agent info for widget display
        response.put("agentName", agent.getName());
        response.put("agentAvatarUrl", agent.getAvatarUrl());

        // Build widget script URL
        String baseUrl = widgetBaseUrl;
        if (baseUrl == null || baseUrl.isBlank()) {
            // Fallback: use request URL to build base
            String scheme = request.getScheme();
            String serverName = request.getServerName();
            int serverPort = request.getServerPort();
            if ((scheme.equals("http") && serverPort == 80) || (scheme.equals("https") && serverPort == 443)) {
                baseUrl = scheme + "://" + serverName;
            } else {
                baseUrl = scheme + "://" + serverName + ":" + serverPort;
            }
        }
        response.put("widgetScriptUrl", widgetConfigService.getWidgetScriptUrl(baseUrl));

        // Generate embed code
        response.put("embedCode", generateEmbedCode(widget, baseUrl));

        return response;
    }

    /**
     * Generate the embed code snippet for the widget.
     */
    private String generateEmbedCode(AgentWidgetConfigEntity widget, String baseUrl) {
        return String.format(
            "<script\n" +
            "  src=\"%s/widget.js\"\n" +
            "  data-widget-token=\"%s\"\n" +
            "  data-position=\"%s\"\n" +
            "  data-theme=\"%s\"\n" +
            "  data-color=\"%s\"\n" +
            "  data-welcome=\"%s\"\n" +
            "  data-bubble-text=\"%s\"\n" +
            "  data-show-avatar=\"%s\"\n" +
            "  data-auto-open=\"%s\"\n" +
            "></script>",
            baseUrl,
            widget.getWidgetToken(),
            widget.getPosition(),
            widget.getTheme(),
            widget.getPrimaryColor(),
            escapeHtml(widget.getWelcomeMessage()),
            escapeHtml(widget.getBubbleText()),
            widget.getShowAvatar(),
            widget.getAutoOpenDelay()
        );
    }

    /**
     * Escape HTML special characters for embed code.
     */
    private String escapeHtml(String text) {
        if (text == null) return "";
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }
}
