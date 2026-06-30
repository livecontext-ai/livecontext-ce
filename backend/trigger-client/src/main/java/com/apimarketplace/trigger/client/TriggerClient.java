package com.apimarketplace.trigger.client;

import com.apimarketplace.trigger.client.dto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import com.apimarketplace.common.web.OrgContextHeaderForwarder;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.util.*;

/**
 * HTTP client for communicating with trigger-service.
 * Used by orchestrator-service for webhook/schedule operations.
 */
public class TriggerClient {

    private static final Logger log = LoggerFactory.getLogger(TriggerClient.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public TriggerClient(String triggerServiceUrl) {
        this.restTemplate = new RestTemplate();
        this.baseUrl = triggerServiceUrl;
    }

    public TriggerClient(RestTemplate restTemplate, String triggerServiceUrl) {
        this.restTemplate = restTemplate;
        this.baseUrl = triggerServiceUrl;
    }

    // ========== Webhook Token Operations ==========

    /**
     * Find a webhook token entity by token string.
     */
    public WebhookTokenDto findByToken(String token) {
        String url = baseUrl + "/api/internal/trigger/tokens/by-token/" + token;
        try {
            ResponseEntity<WebhookTokenDto> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(buildHeaders(null)), WebhookTokenDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.debug("Token not found: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Ensure a token exists for a workflow trigger.
     */
    public WebhookTokenDto ensureTokenForTrigger(UUID workflowId, String triggerId) {
        String url = baseUrl + "/api/internal/trigger/tokens/ensure";
        Map<String, Object> body = Map.of("workflowId", workflowId, "triggerId", triggerId);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, buildHeaders(null));
        try {
            ResponseEntity<WebhookTokenDto> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, WebhookTokenDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to ensure token for workflow={}, trigger={}: {}", workflowId, triggerId, e.getMessage());
            return null;
        }
    }

    /**
     * Get all tokens for a workflow.
     */
    public Map<String, String> getTokensForWorkflow(UUID workflowId) {
        String url = baseUrl + "/api/internal/trigger/tokens/by-workflow/" + workflowId;
        try {
            ResponseEntity<Map<String, String>> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(buildHeaders(null)),
                    new ParameterizedTypeReference<>() {});
            return response.getBody() != null ? response.getBody() : Collections.emptyMap();
        } catch (Exception e) {
            log.error("Failed to get tokens for workflow={}: {}", workflowId, e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * Regenerate token for a workflow trigger.
     */
    public WebhookTokenDto regenerateTokenForTrigger(UUID workflowId, String triggerId) {
        String url = baseUrl + "/api/internal/trigger/tokens/regenerate";
        Map<String, Object> body = Map.of("workflowId", workflowId, "triggerId", triggerId);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, buildHeaders(null));
        try {
            ResponseEntity<WebhookTokenDto> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, WebhookTokenDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to regenerate token: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Delete all tokens for a workflow.
     */
    public void deleteTokensForWorkflow(UUID workflowId) {
        deleteTokensForWorkflow(workflowId, null, null);
    }

    /**
     * Scope-aware overload - forwards X-User-ID + X-Organization-ID so the
     * internal endpoint's owner-or-org check can filter rows.
     * Audit 2026-05-16 round-3.
     */
    public void deleteTokensForWorkflow(UUID workflowId, String tenantId, String organizationId) {
        String url = baseUrl + "/api/internal/trigger/tokens/by-workflow/" + workflowId;
        HttpHeaders headers = buildHeaders(null);
        if (tenantId != null && !tenantId.isBlank()) headers.set("X-User-ID", tenantId);
        OrgContextHeaderForwarder.setIfPresent(headers, organizationId);
        try {
            restTemplate.exchange(url, HttpMethod.DELETE, new HttpEntity<>(headers), Void.class);
        } catch (Exception e) {
            log.warn("Failed to delete tokens for workflow={}: {}", workflowId, e.getMessage());
        }
    }

    /**
     * Cleanup orphan tokens for a workflow.
     */
    public void cleanupOrphanTokens(UUID workflowId, List<String> currentTriggerIds) {
        String url = baseUrl + "/api/internal/trigger/tokens/cleanup-orphans";
        Map<String, Object> body = Map.of("workflowId", workflowId, "currentTriggerIds", currentTriggerIds);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, buildHeaders(null));
        try {
            restTemplate.exchange(url, HttpMethod.POST, entity, Void.class);
        } catch (Exception e) {
            log.warn("Failed to cleanup orphan tokens: {}", e.getMessage());
        }
    }

    /**
     * Find which workflow IDs (from the given set) have at least one webhook token.
     * Single batch call to avoid N+1 queries.
     */
    public Set<UUID> findWorkflowIdsWithTokens(Collection<UUID> workflowIds) {
        if (workflowIds == null || workflowIds.isEmpty()) return Collections.emptySet();
        String url = baseUrl + "/api/internal/trigger/tokens/workflow-ids-with-tokens";
        HttpEntity<Collection<UUID>> entity = new HttpEntity<>(workflowIds, buildHeaders(null));
        try {
            ResponseEntity<Set<UUID>> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity,
                    new ParameterizedTypeReference<>() {});
            return response.getBody() != null ? response.getBody() : Collections.emptySet();
        } catch (Exception e) {
            log.error("Failed to find workflow IDs with tokens: {}", e.getMessage());
            return Collections.emptySet();
        }
    }

    /**
     * Get webhook URL for a token.
     */
    public String getWebhookUrl(String baseAppUrl, String token) {
        return baseAppUrl + "/webhook/" + token;
    }

    // ========== Standalone Webhook Operations ==========

    /**
     * Create a standalone webhook (internal call, bypasses gateway auth).
     */
    public StandaloneWebhookDto createStandaloneWebhook(String tenantId, String name, String description,
                                                          String httpMethod, String authType,
                                                          String sourceNodeId) {
        return createStandaloneWebhook(tenantId, name, description, httpMethod, authType, sourceNodeId, null);
    }

    /**
     * Org-aware overload - stamps the webhook + webhook_tokens.organization_id (V215)
     * so org-teammates see the trigger + the fire-path guards (TriggerDispatchService)
     * resolve the correct workspace. Audit 2026-05-16.
     */
    public StandaloneWebhookDto createStandaloneWebhook(String tenantId, String name, String description,
                                                          String httpMethod, String authType,
                                                          String sourceNodeId, String organizationId) {
        String url = baseUrl + "/api/internal/trigger/webhooks/create";
        Map<String, Object> body = new HashMap<>();
        body.put("tenantId", tenantId);
        body.put("name", name);
        if (description != null) body.put("description", description);
        if (httpMethod != null) body.put("httpMethod", httpMethod);
        if (authType != null) body.put("authType", authType);
        if (sourceNodeId != null) body.put("sourceNodeId", sourceNodeId);
        if (organizationId != null && !organizationId.isBlank()) body.put("organizationId", organizationId);
        HttpHeaders headers = buildHeaders(null);
        OrgContextHeaderForwarder.setIfPresent(headers, organizationId);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        try {
            ResponseEntity<StandaloneWebhookDto> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, StandaloneWebhookDto.class);
            return response.getBody();
        } catch (org.springframework.web.client.HttpClientErrorException.Conflict e) {
            log.debug("Standalone webhook already exists (409), idempotent no-op: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("Failed to create standalone webhook: {}", e.getMessage());
            return null;
        }
    }

    /** Backward-compatible overload without sourceNodeId. */
    public StandaloneWebhookDto createStandaloneWebhook(String tenantId, String name, String description,
                                                          String httpMethod, String authType) {
        return createStandaloneWebhook(tenantId, name, description, httpMethod, authType, null, null);
    }

    /**
     * Update a standalone webhook's config (httpMethod, authType, authConfig, workflow back-link).
     * Used by orchestrator's PinAwareTriggerSyncService to propagate plan changes from the builder
     * to the standalone_webhooks row. Returns null on transport / not-found errors.
     */
    public StandaloneWebhookDto updateStandaloneWebhook(String tenantId, UUID webhookId,
                                                         StandaloneWebhookRequest request) {
        String url = baseUrl + "/api/internal/trigger/webhooks/" + webhookId;
        HttpHeaders headers = buildHeaders(null);
        headers.set("X-User-ID", tenantId);
        HttpEntity<StandaloneWebhookRequest> entity = new HttpEntity<>(request, headers);
        try {
            ResponseEntity<StandaloneWebhookDto> response = restTemplate.exchange(
                    url, HttpMethod.PUT, entity, StandaloneWebhookDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to update standalone webhook {}: {}", webhookId, e.getMessage());
            return null;
        }
    }

    /**
     * Strict variant of {@link #updateStandaloneWebhook}: throws {@link TriggerClientException}
     * with kind discrimination (NOT_FOUND vs SERVER_ERROR vs TRANSPORT). Used by orchestrator
     * to fail-closed on webhook config push errors - auth/method changes that silently fail
     * are a SECURITY risk (public webhook may stay accessible without auth).
     */
    public StandaloneWebhookDto updateStandaloneWebhookStrict(String tenantId, UUID webhookId,
                                                               StandaloneWebhookRequest request) {
        String url = baseUrl + "/api/internal/trigger/webhooks/" + webhookId;
        HttpHeaders headers = buildHeaders(null);
        headers.set("X-User-ID", tenantId);
        HttpEntity<StandaloneWebhookRequest> entity = new HttpEntity<>(request, headers);
        try {
            ResponseEntity<StandaloneWebhookDto> response = restTemplate.exchange(
                    url, HttpMethod.PUT, entity, StandaloneWebhookDto.class);
            return response.getBody();
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            throw new TriggerClientException(TriggerClientException.Kind.NOT_FOUND,
                    "Standalone webhook " + webhookId + " not found", e);
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            throw new TriggerClientException(TriggerClientException.Kind.CLIENT_ERROR,
                    "Client error updating webhook " + webhookId + ": " + e.getMessage(), e);
        } catch (org.springframework.web.client.HttpServerErrorException e) {
            throw new TriggerClientException(TriggerClientException.Kind.SERVER_ERROR,
                    "Server error updating webhook " + webhookId + ": " + e.getMessage(), e);
        } catch (Exception e) {
            throw new TriggerClientException(TriggerClientException.Kind.TRANSPORT,
                    "Transport error updating webhook " + webhookId + ": " + e.getMessage(), e);
        }
    }

    /**
     * Find a standalone webhook by token.
     */
    public StandaloneWebhookDto findStandaloneByToken(String token) {
        String url = baseUrl + "/api/internal/trigger/webhooks/by-token/" + token;
        try {
            ResponseEntity<StandaloneWebhookDto> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(buildHeaders(null)), StandaloneWebhookDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.debug("Standalone webhook not found for token: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Find standalone webhooks by workflow ID.
     */
    public List<StandaloneWebhookDto> findStandaloneByWorkflowId(UUID workflowId) {
        String url = baseUrl + "/api/internal/trigger/webhooks/by-workflow/" + workflowId;
        try {
            ResponseEntity<List<StandaloneWebhookDto>> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(buildHeaders(null)),
                    new ParameterizedTypeReference<>() {});
            return response.getBody() != null ? response.getBody() : Collections.emptyList();
        } catch (Exception e) {
            log.warn("Failed to find webhooks for workflow={}: {}", workflowId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Convenience method to log a webhook call.
     */
    public void logWebhookCall(UUID webhookId, String requestMethod, Map<String, Object> requestHeaders,
                                Map<String, Object> requestPayload, String responseStatus, int workflowsTriggered) {
        logWebhookCall(new WebhookCallLogRequest(webhookId, requestMethod, requestHeaders,
                requestPayload, responseStatus, workflowsTriggered));
    }

    /**
     * Log a webhook call.
     */
    public void logWebhookCall(WebhookCallLogRequest request) {
        String url = baseUrl + "/api/internal/trigger/webhooks/log-call";
        HttpEntity<WebhookCallLogRequest> entity = new HttpEntity<>(request, buildHeaders(null));
        try {
            restTemplate.exchange(url, HttpMethod.POST, entity, Void.class);
        } catch (Exception e) {
            log.warn("Failed to log webhook call: {}", e.getMessage());
        }
    }

    /**
     * Get decrypted auth config for a standalone webhook.
     */
    public Map<String, String> getDecryptedAuthConfig(UUID webhookId) {
        String url = baseUrl + "/api/internal/trigger/webhooks/" + webhookId + "/decrypted-auth";
        try {
            ResponseEntity<Map<String, String>> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(buildHeaders(null)),
                    new ParameterizedTypeReference<>() {});
            return response.getBody() != null ? response.getBody() : Collections.emptyMap();
        } catch (Exception e) {
            log.warn("Failed to get decrypted auth config: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    // ========== Schedule Operations ==========

    /**
     * Create or update a schedule via internal API.
     *
     * <p>{@code organizationId} is the workflow's active workspace at the time of sync.
     * When non-null, the internal endpoint stamps it on new schedule rows AND backfills
     * legacy rows that were created NULL-org by pre-V218 sync paths. This is what makes
     * SCHEDULE rows visible in the bell Triggers tab via
     * {@code ActiveAutomationsService.getSchedulesByOrganization}.
     */
    public ScheduledExecutionDto createOrUpdateSchedule(UUID workflowId, String triggerId, String tenantId,
                                                           String organizationId,
                                                           ScheduleCreateRequest request,
                                                           String name, String workflowName) {
        String url = baseUrl + "/api/internal/trigger/schedules/create";
        Map<String, Object> body = new HashMap<>();
        body.put("workflowId", workflowId.toString());
        body.put("triggerId", triggerId);
        body.put("tenantId", tenantId);
        if (organizationId != null && !organizationId.isBlank()) {
            body.put("organizationId", organizationId);
        }
        body.put("cron", request.cron());
        body.put("timezone", request.timezone());
        body.put("maxExecutions", request.maxExecutions());
        body.put("enabled", request.enabled());
        body.put("expiresInDays", request.expiresInDays());
        if (name != null) body.put("name", name);
        if (workflowName != null) body.put("workflowName", workflowName);
        HttpHeaders headers = buildHeaders(null);
        OrgContextHeaderForwarder.setIfPresent(headers, organizationId);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        try {
            ResponseEntity<ScheduledExecutionDto> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, ScheduledExecutionDto.class);
            return response.getBody();
        } catch (org.springframework.web.client.HttpClientErrorException.Conflict e) {
            log.debug("Schedule already exists (409) for workflow={}, trigger={}, idempotent no-op: {}",
                    workflowId, triggerId, e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("Failed to create/update schedule for workflow={}, trigger={}: {}",
                    workflowId, triggerId, e.getMessage());
            return null;
        }
    }

    /** Backward-compatible overload without organizationId - routes to the 7-arg form with null. */
    public ScheduledExecutionDto createOrUpdateSchedule(UUID workflowId, String triggerId, String tenantId,
                                                           ScheduleCreateRequest request,
                                                           String name, String workflowName) {
        return createOrUpdateSchedule(workflowId, triggerId, tenantId, null, request, name, workflowName);
    }

    /** Backward-compatible overload without name/workflowName. */
    public ScheduledExecutionDto createOrUpdateSchedule(UUID workflowId, String triggerId, String tenantId,
                                                           ScheduleCreateRequest request) {
        return createOrUpdateSchedule(workflowId, triggerId, tenantId, null, request, null, null);
    }

    /**
     * Find due schedules (for daemon execution).
     */
    public List<ScheduledExecutionDto> findDueSchedules(Instant before) {
        String url = UriComponentsBuilder.fromHttpUrl(baseUrl + "/api/internal/trigger/schedules/due")
                .queryParam("before", before.toString())
                .toUriString();
        try {
            ResponseEntity<List<ScheduledExecutionDto>> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(buildHeaders(null)),
                    new ParameterizedTypeReference<>() {});
            return response.getBody() != null ? response.getBody() : Collections.emptyList();
        } catch (Exception e) {
            log.error("Failed to find due schedules: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Atomically claim and advance due schedules using FOR UPDATE SKIP LOCKED.
     * Returns DTOs with the ORIGINAL nextExecutionAt (before advance) for deterministic requestId.
     */
    public List<ScheduledExecutionDto> claimAndAdvanceDueSchedules(Instant before) {
        String url = UriComponentsBuilder.fromHttpUrl(baseUrl + "/api/internal/trigger/schedules/claim-and-advance")
                .queryParam("before", before.toString())
                .toUriString();
        try {
            ResponseEntity<List<ScheduledExecutionDto>> response = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(buildHeaders(null)),
                    new ParameterizedTypeReference<>() {});
            return response.getBody() != null ? response.getBody() : Collections.emptyList();
        } catch (Exception e) {
            log.warn("claimAndAdvanceDueSchedules failed, falling back to findDueSchedules: {}", e.getMessage());
            return findDueSchedules(before);
        }
    }

    /**
     * Get a schedule by ID.
     */
    public ScheduledExecutionDto getSchedule(UUID scheduleId) {
        String url = baseUrl + "/api/internal/trigger/schedules/" + scheduleId;
        try {
            ResponseEntity<ScheduledExecutionDto> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(buildHeaders(null)), ScheduledExecutionDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.warn("Failed to get schedule={}: {}", scheduleId, e.getMessage());
            return null;
        }
    }

    /**
     * Get schedule by workflow and trigger.
     */
    public ScheduledExecutionDto getScheduleByWorkflowAndTrigger(UUID workflowId, String triggerId) {
        return getScheduleByWorkflowAndTrigger(workflowId, triggerId, null);
    }

    /**
     * Get schedule by workflow and trigger in the caller's active workspace.
     */
    public ScheduledExecutionDto getScheduleByWorkflowAndTrigger(UUID workflowId, String triggerId, String organizationId) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseUrl + "/api/internal/trigger/schedules/find")
                .queryParam("workflowId", workflowId)
                .queryParam("triggerId", triggerId);
        if (organizationId != null && !organizationId.isBlank()) {
            builder.queryParam("organizationId", organizationId);
        }
        String url = builder.toUriString();
        HttpHeaders headers = buildHeaders(null);
        OrgContextHeaderForwarder.setIfPresent(headers, organizationId);
        try {
            ResponseEntity<ScheduledExecutionDto> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), ScheduledExecutionDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.debug("No schedule found for workflow={}, trigger={}", workflowId, triggerId);
            return null;
        }
    }

    /**
     * Get all schedules for a workflow.
     */
    public List<ScheduledExecutionDto> getSchedulesByWorkflow(UUID workflowId) {
        return getSchedulesByWorkflow(workflowId, null);
    }

    /**
     * Get all schedules for a workflow in the caller's active workspace.
     */
    public List<ScheduledExecutionDto> getSchedulesByWorkflow(UUID workflowId, String organizationId) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromHttpUrl(baseUrl + "/api/internal/trigger/schedules/by-workflow/" + workflowId);
        if (organizationId != null && !organizationId.isBlank()) {
            builder.queryParam("organizationId", organizationId);
        }
        String url = builder.toUriString();
        HttpHeaders headers = buildHeaders(null);
        OrgContextHeaderForwarder.setIfPresent(headers, organizationId);
        try {
            ResponseEntity<List<ScheduledExecutionDto>> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers),
                    new ParameterizedTypeReference<>() {});
            return response.getBody() != null ? response.getBody() : Collections.emptyList();
        } catch (Exception e) {
            log.warn("Failed to get schedules for workflow={}: {}", workflowId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Record schedule execution. The trigger-service computes the real next
     * cron execution time from the schedule's cron expression and timezone.
     */
    public ScheduledExecutionDto recordScheduleExecution(UUID scheduleId) {
        String url = baseUrl + "/api/internal/trigger/schedules/" + scheduleId + "/record-execution";
        // Send empty body - trigger-service computes nextExecutionAt from cron/timezone
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(new HashMap<>(), buildHeaders(null));
        try {
            ResponseEntity<ScheduledExecutionDto> response =
                    restTemplate.exchange(url, HttpMethod.PUT, entity, ScheduledExecutionDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to record schedule execution: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Restore a schedule's dispatch markers after orchestrator advanced it but failed
     * to enqueue execution. The trigger-service applies this only if the row still
     * matches the advanced markers observed by the caller.
     */
    public ScheduledExecutionDto restoreScheduleDispatch(
            UUID scheduleId,
            Instant previousNextExecutionAt,
            Instant previousLastExecutionAt,
            Instant advancedNextExecutionAt,
            Instant advancedLastExecutionAt,
            int previousExecutionCount,
            int advancedExecutionCount) {
        String url = baseUrl + "/api/internal/trigger/schedules/" + scheduleId + "/restore-dispatch";
        Map<String, Object> body = new HashMap<>();
        if (previousNextExecutionAt != null) {
            body.put("previousNextExecutionAt", previousNextExecutionAt.toString());
        }
        if (previousLastExecutionAt != null) {
            body.put("previousLastExecutionAt", previousLastExecutionAt.toString());
        }
        if (advancedNextExecutionAt != null) {
            body.put("advancedNextExecutionAt", advancedNextExecutionAt.toString());
        }
        if (advancedLastExecutionAt != null) {
            body.put("advancedLastExecutionAt", advancedLastExecutionAt.toString());
        }
        body.put("previousExecutionCount", previousExecutionCount);
        body.put("advancedExecutionCount", advancedExecutionCount);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, buildHeaders(null));
        try {
            ResponseEntity<ScheduledExecutionDto> response =
                    restTemplate.exchange(url, HttpMethod.PUT, entity, ScheduledExecutionDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to restore schedule dispatch markers: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Disable a schedule (legacy hard-disable, archives via TriggerLifecycleManager).
     * New callers SHOULD prefer {@link #suspendSchedule(UUID, String)} for non-permanent
     * disables - those preserve the row + state machine + can be re-armed by sync/pin.
     */
    public void disableSchedule(UUID scheduleId) {
        String url = baseUrl + "/api/internal/trigger/schedules/" + scheduleId + "/disable";
        try {
            restTemplate.exchange(url, HttpMethod.PUT, new HttpEntity<>(buildHeaders(null)), Void.class);
        } catch (Exception e) {
            log.warn("Failed to disable schedule={}: {}", scheduleId, e.getMessage());
        }
    }

    /**
     * Round-7 PR5: suspend a schedule (recoverable). Used by sync paths when a workflow
     * is unpinned - the schedule moves to {@code SUSPENDED_UNPINNED} state, preserving
     * all configuration so a future re-pin can re-arm it without re-creating the row.
     *
     * @param reason {@code TriggerLifecycleManager.Reason} string constant
     */
    public void suspendSchedule(UUID scheduleId, String reason) {
        String url = baseUrl + "/api/internal/trigger/schedules/" + scheduleId
                + "/suspend?reason=" + reason;
        try {
            restTemplate.exchange(url, HttpMethod.PUT, new HttpEntity<>(buildHeaders(null)), Void.class);
        } catch (Exception e) {
            log.warn("Failed to suspend schedule={} reason={}: {}", scheduleId, reason, e.getMessage());
        }
    }

    /**
     * Arm every existing schedule row for a workflow. Used as the first step
     * of reactivate to re-enable rows that the pin/unpin flow may have left
     * in {@code SUSPENDED_UNPINNED} state (see
     * {@code ScheduleSyncService.disableAllSchedules}). Note: cancel and stop
     * do NOT touch schedule rows, so this is not symmetric with cancel.
     *
     * @return number of schedule rows armed; 0 means the caller has no
     *         existing rows for this workflow - the caller should typically
     *         fall back to a full pinned-plan re-sync to recreate them.
     */
    public int enableSchedulesByWorkflow(UUID workflowId) {
        return enableSchedulesByWorkflow(workflowId, null);
    }

    public int enableSchedulesByWorkflow(UUID workflowId, String organizationId) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromHttpUrl(baseUrl + "/api/internal/trigger/schedules/by-workflow/" + workflowId + "/enable");
        if (organizationId != null && !organizationId.isBlank()) {
            builder.queryParam("organizationId", organizationId);
        }
        String url = builder.toUriString();
        HttpHeaders headers = buildHeaders(null);
        OrgContextHeaderForwarder.setIfPresent(headers, organizationId);
        try {
            ResponseEntity<Integer> response = restTemplate.exchange(
                    url, HttpMethod.PUT, new HttpEntity<>(headers), Integer.class);
            Integer body = response.getBody();
            return body != null ? body : 0;
        } catch (Exception e) {
            log.warn("Failed to enable schedules for workflow={}: {}", workflowId, e.getMessage());
            return 0;
        }
    }

    /**
     * Archive every non-ARCHIVED schedule row for a workflow. Used by the
     * orchestrator's workflow-deletion cascade ({@code WorkflowManagementService.deleteWorkflow}).
     *
     * <p>Routes through {@code TriggerLifecycleManager.archiveSchedule} server-side
     * so a {@code WORKFLOW_DELETED} (or caller-supplied) state-transition audit row
     * is appended. Returns the number of rows transitioned to ARCHIVED.
     *
     * @param reason optional override; defaults to {@code WORKFLOW_DELETED} when null/blank.
     */
    public int archiveSchedulesByWorkflow(UUID workflowId, String reason) {
        return archiveByWorkflowGeneric("schedules", workflowId, reason);
    }

    /**
     * Archive every non-ARCHIVED standalone-webhook row for a workflow. See
     * {@link #archiveSchedulesByWorkflow(UUID, String)} for semantics.
     */
    public int archiveWebhooksByWorkflow(UUID workflowId, String reason) {
        return archiveByWorkflowGeneric("standalone-webhooks", workflowId, reason);
    }

    /**
     * Archive every non-ARCHIVED standalone-chat-endpoint row for a workflow. See
     * {@link #archiveSchedulesByWorkflow(UUID, String)} for semantics.
     */
    public int archiveChatEndpointsByWorkflow(UUID workflowId, String reason) {
        return archiveByWorkflowGeneric("standalone-chat-endpoints", workflowId, reason);
    }

    /**
     * Archive every non-ARCHIVED standalone-form-endpoint row for a workflow. See
     * {@link #archiveSchedulesByWorkflow(UUID, String)} for semantics.
     */
    public int archiveFormEndpointsByWorkflow(UUID workflowId, String reason) {
        return archiveByWorkflowGeneric("standalone-form-endpoints", workflowId, reason);
    }

    private int archiveByWorkflowGeneric(String resourcePath, UUID workflowId, String reason) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(
                baseUrl + "/api/internal/trigger/" + resourcePath + "/by-workflow/" + workflowId + "/archive");
        if (reason != null && !reason.isBlank()) {
            builder.queryParam("reason", reason);
        }
        String url = builder.build().encode().toUriString();
        try {
            ResponseEntity<Integer> response = restTemplate.exchange(
                    url, HttpMethod.PUT, new HttpEntity<>(buildHeaders(null)), Integer.class);
            Integer body = response.getBody();
            return body != null ? body : 0;
        } catch (Exception e) {
            log.warn("Failed to archive {} for workflow={} reason={}: {}",
                    resourcePath, workflowId, reason, e.getMessage());
            return 0;
        }
    }

    /**
     * Suspend (not delete) every ACTIVE schedule row for a workflow. Mirror of
     * {@link #enableSchedulesByWorkflow}. Used by the cancel/pause cascade
     * (design v3.5 §L3, extended to all {@code WorkflowType}s on 2026-05-13):
     * when a workflow's run is cancelled or paused, the triggers must stop
     * firing - but the rows themselves are kept so the user can re-pin /
     * re-activate later via {@code reactivateWorkflow} or pin/unpin.
     *
     * <p>ARCHIVED + already-SUSPENDED rows are skipped server-side; the
     * returned count is the number of ACTIVE rows transitioned to SUSPENDED.
     *
     * @param reason persisted verbatim to {@code last_disabled_reason} (e.g.
     *               {@code "USER_DISABLED"}, {@code "WORKFLOW_UNPINNED"}; see
     *               trigger-service's {@code TriggerLifecycleManager.Reason}
     *               for the full set). {@code null} or blank defaults to
     *               {@code USER_DISABLED} server-side.
     * @param source one of the {@code TriggerLifecycleManager.Source} enum
     *               values stringified ({@code PIN}, {@code ADMIN},
     *               {@code REAPER}, {@code SYNC}, {@code QUOTA},
     *               {@code DELETION}, {@code RUN_TERMINATION}). {@code null}
     *               or blank defaults to {@code ADMIN} server-side. Forensic
     *               accuracy: set {@code RUN_TERMINATION} for cancel/pause
     *               cascade (since 2026-05-13), {@code DELETION} for
     *               workflow-row deletion cascade, {@code PIN} for the
     *               unpin/rearm flow, etc.
     * @return number of ACTIVE rows suspended; {@code 0} on error.
     */
    public int suspendSchedulesByWorkflow(UUID workflowId, String reason, String source) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(
                baseUrl + "/api/internal/trigger/schedules/by-workflow/" + workflowId + "/suspend");
        if (reason != null && !reason.isBlank()) {
            builder.queryParam("reason", reason);
        }
        if (source != null && !source.isBlank()) {
            builder.queryParam("source", source);
        }
        String url = builder.build().encode().toUriString();
        try {
            ResponseEntity<Integer> response = restTemplate.exchange(
                    url, HttpMethod.PUT, new HttpEntity<>(buildHeaders(null)), Integer.class);
            Integer body = response.getBody();
            return body != null ? body : 0;
        } catch (Exception e) {
            log.warn("Failed to suspend schedules for workflow={} reason={} source={}: {}",
                    workflowId, reason, source, e.getMessage());
            return 0;
        }
    }

    /**
     * Backwards-compatible overload - defaults source to {@code null}
     * (server resolves to {@code ADMIN}). New callers SHOULD prefer the
     * 3-arg form so the audit log records who initiated the suspension.
     */
    public int suspendSchedulesByWorkflow(UUID workflowId, String reason) {
        return suspendSchedulesByWorkflow(workflowId, reason, null);
    }

    /**
     * Get all schedules for a tenant (for Settings overview).
     */
    public List<ScheduledExecutionDto> getSchedulesByTenant(String tenantId) {
        return getSchedulesByTenant(tenantId, null);
    }

    /**
     * Get schedules in the caller's active scope. When {@code organizationId}
     * is present, trigger-service returns rows for that organization; otherwise
     * it returns the user's personal-scope rows.
     */
    public List<ScheduledExecutionDto> getSchedulesByTenant(String tenantId, String organizationId) {
        String url = baseUrl + "/api/internal/trigger/schedules/by-tenant/" + tenantId;
        HttpHeaders headers = buildHeaders(null);
        OrgContextHeaderForwarder.setIfPresent(headers, organizationId);
        try {
            ResponseEntity<List<ScheduledExecutionDto>> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers),
                    new ParameterizedTypeReference<>() {});
            return response.getBody() != null ? response.getBody() : Collections.emptyList();
        } catch (Exception e) {
            log.warn("Failed to get schedules for tenant={}: {}", tenantId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * V220 follow-up - org-scoped sibling of {@link #getSchedulesByTenant(String)}.
     * Returns every schedule tagged with {@code organization_id == orgId},
     * regardless of {@code tenant_id}. Required for the "active automations"
     * strip on the home dashboard: a schedule created by a teammate in the
     * shared workspace has {@code tenant_id == teammate_user_id} and would be
     * invisible to the {@code by-tenant} read.
     *
     * <p>Routes to {@code GET /api/internal/trigger/schedules/by-organization/{orgId}},
     * which delegates to {@code ScheduledExecutionRepository.findAllByOrganizationIdStrict}
     * (PR22 strict-isolation finder; no tenant predicate).
     */
    public List<ScheduledExecutionDto> getSchedulesByOrganization(String orgId) {
        String url = baseUrl + "/api/internal/trigger/schedules/by-organization/" + orgId;
        try {
            ResponseEntity<List<ScheduledExecutionDto>> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(buildHeaders(null)),
                    new ParameterizedTypeReference<>() {});
            return response.getBody() != null ? response.getBody() : Collections.emptyList();
        } catch (Exception e) {
            log.warn("Failed to get schedules for org={}: {}", orgId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Count schedules for a tenant (for plan limit display).
     */
    public long countSchedulesByTenant(String tenantId) {
        return countSchedulesByTenant(tenantId, null);
    }

    /**
     * Count schedules in the active scope. The internal endpoint uses the
     * organization header to switch from personal-scope to org-scope counting.
     */
    public long countSchedulesByTenant(String tenantId, String organizationId) {
        String url = baseUrl + "/api/internal/trigger/schedules/count-by-tenant/" + tenantId;
        HttpHeaders headers = buildHeaders(tenantId);
        OrgContextHeaderForwarder.setIfPresent(headers, organizationId);
        try {
            ResponseEntity<Long> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), Long.class);
            return response.getBody() != null ? response.getBody() : 0L;
        } catch (Exception e) {
            log.warn("Failed to count schedules for tenant={}: {}", tenantId, e.getMessage());
            return 0L;
        }
    }

    /**
     * Toggle a schedule enabled/disabled.
     */
    public ScheduledExecutionDto toggleSchedule(UUID scheduleId, boolean enabled) {
        return toggleSchedule(scheduleId, enabled, null);
    }

    /**
     * Toggle a schedule enabled/disabled inside the active organization scope.
     */
    public ScheduledExecutionDto toggleSchedule(UUID scheduleId, boolean enabled, String organizationId) {
        return toggleSchedule(scheduleId, enabled, organizationId, null);
    }

    /**
     * Toggle a schedule enabled/disabled inside the active organization scope.
     * The tenant header is required in CE monolith self-calls so the second
     * security-filter pass preserves trusted org headers.
     */
    public ScheduledExecutionDto toggleSchedule(UUID scheduleId, boolean enabled, String organizationId, String tenantId) {
        String url = baseUrl + "/api/internal/trigger/schedules/" + scheduleId + "/toggle";
        Map<String, Boolean> body = Map.of("enabled", enabled);
        HttpHeaders headers = buildHeaders(tenantId);
        OrgContextHeaderForwarder.setIfPresent(headers, organizationId);
        HttpEntity<Map<String, Boolean>> entity = new HttpEntity<>(body, headers);
        try {
            ResponseEntity<ScheduledExecutionDto> response = restTemplate.exchange(
                    url, HttpMethod.PUT, entity, ScheduledExecutionDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.warn("Failed to toggle schedule={}: {}", scheduleId, e.getMessage());
            return null;
        }
    }

    /**
     * Archive a single schedule by id (state=ARCHIVED). Routes server-side
     * through {@code TriggerLifecycleManager.archiveSchedule}; the row +
     * execution_count survive indefinitely with a full audit-log entry. Archived
     * rows are not currently auto-reaped - they persist until manual cleanup or a
     * future ARCHIVED-row reaper.
     *
     * @param reason optional override; defaults server-side to {@code USER_DELETED}.
     */
    public void archiveScheduleById(UUID scheduleId, String reason) {
        archiveScheduleById(scheduleId, reason, null);
    }

    /**
     * Archive a schedule by id inside the active organization scope.
     *
     * @return {@code true} when trigger-service accepted the archive request.
     */
    public boolean archiveScheduleById(UUID scheduleId, String reason, String organizationId) {
        return archiveScheduleById(scheduleId, reason, organizationId, null);
    }

    /**
     * Archive a schedule by id inside the active organization scope.
     * The tenant header is required in CE monolith self-calls so the second
     * security-filter pass preserves trusted org headers.
     *
     * @return {@code true} when trigger-service accepted the archive request.
     */
    public boolean archiveScheduleById(UUID scheduleId, String reason, String organizationId, String tenantId) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(
                baseUrl + "/api/internal/trigger/schedules/" + scheduleId + "/archive");
        if (reason != null && !reason.isBlank()) {
            builder.queryParam("reason", reason);
        }
        String url = builder.build().encode().toUriString();
        HttpHeaders headers = buildHeaders(tenantId);
        OrgContextHeaderForwarder.setIfPresent(headers, organizationId);
        try {
            ResponseEntity<Void> response =
                    restTemplate.exchange(url, HttpMethod.PUT, new HttpEntity<>(headers), Void.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.warn("Failed to archive schedule={} reason={}: {}", scheduleId, reason, e.getMessage());
            return false;
        }
    }

    /**
     * Cleanup expired schedules.
     */
    public int cleanupExpiredSchedules() {
        String url = baseUrl + "/api/internal/trigger/schedules/cleanup-expired";
        try {
            ResponseEntity<Integer> response = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(buildHeaders(null)), Integer.class);
            return response.getBody() != null ? response.getBody() : 0;
        } catch (Exception e) {
            log.warn("Failed to cleanup expired schedules: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * Validate a cron expression. Returns a map with keys {@code valid} (boolean),
     * and when valid: {@code description} (String) + {@code nextExecutions}
     * (List of up to 3 ISO-8601 instant strings) in the requested timezone.
     *
     * <p>Returns {@code { "valid": false }} on transport error so callers can
     * surface the same shape to the UI without special-casing failures.
     */
    public Map<String, Object> validateCron(String cron, String timezone) {
        String url = baseUrl + "/api/internal/trigger/schedules/validate-cron";
        Map<String, String> body = new HashMap<>();
        if (cron != null) body.put("cron", cron);
        if (timezone != null) body.put("timezone", timezone);
        HttpEntity<Map<String, String>> entity = new HttpEntity<>(body, buildHeaders(null));
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity,
                    new ParameterizedTypeReference<>() {});
            return response.getBody() != null ? response.getBody() : Map.of("valid", false);
        } catch (Exception e) {
            log.warn("Failed to validate cron '{}': {}", cron, e.getMessage());
            return Map.of("valid", false);
        }
    }

    // ========== Standalone Schedule Operations ==========

    /**
     * Create a standalone schedule (no workflow linked yet).
     */
    public ScheduledExecutionDto createStandaloneSchedule(String tenantId, String userPlan,
                                                           StandaloneScheduleRequest request) {
        return createStandaloneSchedule(tenantId, userPlan, request, null);
    }

    /**
     * Create a standalone schedule in the active workspace scope.
     */
    public ScheduledExecutionDto createStandaloneSchedule(String tenantId, String userPlan,
                                                           StandaloneScheduleRequest request,
                                                           String organizationId) {
        String url = baseUrl + "/api/internal/trigger/schedules/standalone";
        HttpHeaders headers = buildHeaders(null);
        headers.set("X-User-ID", tenantId);
        if (userPlan != null) headers.set("X-User-Plan", userPlan);
        OrgContextHeaderForwarder.setIfPresent(headers, organizationId);
        HttpEntity<StandaloneScheduleRequest> entity = new HttpEntity<>(request, headers);
        try {
            ResponseEntity<ScheduledExecutionDto> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, ScheduledExecutionDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to create standalone schedule: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Get a standalone schedule by ID.
     */
    public ScheduledExecutionDto getStandaloneSchedule(String tenantId, UUID id) {
        String url = baseUrl + "/api/internal/trigger/schedules/standalone/" + id;
        HttpHeaders headers = buildHeaders(null);
        headers.set("X-User-ID", tenantId);
        try {
            ResponseEntity<ScheduledExecutionDto> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), ScheduledExecutionDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.debug("Standalone schedule not found: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Update a standalone schedule.
     */
    public ScheduledExecutionDto updateStandaloneSchedule(String tenantId, UUID id,
                                                           StandaloneScheduleRequest request) {
        return updateStandaloneSchedule(tenantId, id, request, null);
    }

    /**
     * Update a standalone schedule in the active workspace scope.
     */
    public ScheduledExecutionDto updateStandaloneSchedule(String tenantId, UUID id,
                                                           StandaloneScheduleRequest request,
                                                           String organizationId) {
        String url = baseUrl + "/api/internal/trigger/schedules/standalone/" + id;
        HttpHeaders headers = buildHeaders(null);
        headers.set("X-User-ID", tenantId);
        OrgContextHeaderForwarder.setIfPresent(headers, organizationId);
        HttpEntity<StandaloneScheduleRequest> entity = new HttpEntity<>(request, headers);
        try {
            ResponseEntity<ScheduledExecutionDto> response = restTemplate.exchange(
                    url, HttpMethod.PUT, entity, ScheduledExecutionDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to update standalone schedule {}: {}", id, e.getMessage());
            return null;
        }
    }

    /**
     * Strict variant of {@link #updateStandaloneSchedule}: throws {@link TriggerClientException}
     * with {@code Kind.NOT_FOUND} on 404 and {@code Kind.SERVER_ERROR}/{@code Kind.TRANSPORT}
     * on 5xx/network. Use this when the caller must distinguish phantom-row from transient
     * failure to decide between fall-through and skip-no-mutate.
     */
    public ScheduledExecutionDto updateStandaloneScheduleStrict(String tenantId, UUID id,
                                                                  StandaloneScheduleRequest request) {
        String url = baseUrl + "/api/internal/trigger/schedules/standalone/" + id;
        HttpHeaders headers = buildHeaders(null);
        headers.set("X-User-ID", tenantId);
        HttpEntity<StandaloneScheduleRequest> entity = new HttpEntity<>(request, headers);
        try {
            ResponseEntity<ScheduledExecutionDto> response = restTemplate.exchange(
                    url, HttpMethod.PUT, entity, ScheduledExecutionDto.class);
            return response.getBody();
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            throw new TriggerClientException(TriggerClientException.Kind.NOT_FOUND,
                    "Standalone schedule " + id + " not found", e);
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            throw new TriggerClientException(TriggerClientException.Kind.CLIENT_ERROR,
                    "Client error updating standalone schedule " + id + ": " + e.getMessage(), e);
        } catch (org.springframework.web.client.HttpServerErrorException e) {
            throw new TriggerClientException(TriggerClientException.Kind.SERVER_ERROR,
                    "Server error updating standalone schedule " + id + ": " + e.getMessage(), e);
        } catch (Exception e) {
            throw new TriggerClientException(TriggerClientException.Kind.TRANSPORT,
                    "Transport error updating standalone schedule " + id + ": " + e.getMessage(), e);
        }
    }

    /**
     * Adopt a standalone schedule onto a workflow at pin time - set BOTH workflow_id and
     * trigger_id (a schedule fires only when both are present). RESTORED after the F4
     * PUB-HIJACK removal: the old call rewrote workflow_id UNCONDITIONALLY (the hijack);
     * this one is guarded NULL→value only server-side ({@code assertWorkflowReferenceMutationAllowed})
     * + V206 at the DB + an org-scoped finder, so it re-enables legitimate adoption without
     * re-opening the clone-hijack (PlanStripUtils also strips scheduleId from cloned plans).
     * Best-effort: returns null on failure; adoption is idempotent so the next sync retries.
     */
    public ScheduledExecutionDto updateScheduleWorkflowReferenceStrict(
            String tenantId, String organizationId, UUID scheduleId,
            UUID workflowId, String triggerId, String workflowName) {
        String url = baseUrl + "/api/internal/trigger/schedules/standalone/" + scheduleId + "/workflow";
        HttpHeaders headers = buildHeaders(null);
        headers.set("X-User-ID", tenantId);
        if (organizationId != null) headers.set("X-Organization-ID", organizationId);
        Map<String, String> body = new HashMap<>();
        if (workflowId != null) body.put("workflowId", workflowId.toString());
        if (triggerId != null) body.put("triggerId", triggerId);
        if (workflowName != null) body.put("workflowName", workflowName);
        HttpEntity<Map<String, String>> entity = new HttpEntity<>(body, headers);
        try {
            ResponseEntity<ScheduledExecutionDto> response = restTemplate.exchange(
                    url, HttpMethod.PATCH, entity, ScheduledExecutionDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.warn("Failed to adopt standalone schedule {} onto workflow {}: {}",
                    scheduleId, workflowId, e.getMessage());
            return null;
        }
    }

    // Historical note: the ORIGINAL updateScheduleWorkflowReference(Strict) was removed in
    // v5 because its sole caller (legacy ScheduleSyncService rebind) rewrote workflow_id
    // UNCONDITIONALLY → the F4 PUB-HIJACK (silent workflow_id rebind on app clone publish).
    // The method ABOVE is the guarded replacement (NULL→value only). V206's BEFORE UPDATE
    // trigger enforces workflow_id immutability at the DB level. Standalone schedule rows are
    // created with workflow_id/trigger_id NULL (createStandaloneSchedule does not set them) and
    // are bound to their workflow on first pin via that guarded adoption call.

    // ========== Chat Endpoint Operations ==========

    /**
     * Create a chat endpoint via trigger-service.
     */
    public StandaloneChatEndpointDto createChatEndpoint(String tenantId, String userPlan,
                                                         StandaloneChatEndpointRequest request) {
        return createChatEndpoint(tenantId, userPlan, request, null);
    }

    /**
     * Org-aware overload - Audit 2026-05-17 round-3. Threads X-Organization-ID
     * so trigger-service stamps standalone_chat_endpoints.organization_id
     * (mirror of createStandaloneWebhook pattern).
     */
    public StandaloneChatEndpointDto createChatEndpoint(String tenantId, String userPlan,
                                                         StandaloneChatEndpointRequest request,
                                                         String organizationId) {
        String url = baseUrl + "/api/internal/trigger/chat-endpoints";
        HttpHeaders headers = buildHeaders(null);
        headers.set("X-User-ID", tenantId);
        if (userPlan != null) headers.set("X-User-Plan", userPlan);
        OrgContextHeaderForwarder.setIfPresent(headers, organizationId);
        HttpEntity<StandaloneChatEndpointRequest> entity = new HttpEntity<>(request, headers);
        try {
            ResponseEntity<StandaloneChatEndpointDto> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, StandaloneChatEndpointDto.class);
            return response.getBody();
        } catch (org.springframework.web.client.HttpClientErrorException.Conflict e) {
            log.debug("Chat endpoint already exists (409), idempotent no-op: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("Failed to create chat endpoint: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Update a chat endpoint.
     */
    public StandaloneChatEndpointDto updateChatEndpoint(String tenantId, UUID endpointId,
                                                         StandaloneChatEndpointRequest request) {
        String url = baseUrl + "/api/internal/trigger/chat-endpoints/" + endpointId;
        HttpHeaders headers = buildHeaders(null);
        headers.set("X-User-ID", tenantId);
        HttpEntity<StandaloneChatEndpointRequest> entity = new HttpEntity<>(request, headers);
        try {
            ResponseEntity<StandaloneChatEndpointDto> response = restTemplate.exchange(
                    url, HttpMethod.PUT, entity, StandaloneChatEndpointDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to update chat endpoint {}: {}", endpointId, e.getMessage());
            return null;
        }
    }

    /**
     * Delete a chat endpoint.
     */
    public void deleteChatEndpoint(String tenantId, UUID endpointId) {
        String url = baseUrl + "/api/internal/trigger/chat-endpoints/" + endpointId;
        HttpHeaders headers = buildHeaders(null);
        headers.set("X-User-ID", tenantId);
        try {
            restTemplate.exchange(url, HttpMethod.DELETE, new HttpEntity<>(headers), Void.class);
        } catch (Exception e) {
            log.warn("Failed to delete chat endpoint {}: {}", endpointId, e.getMessage());
        }
    }

    /**
     * Get all chat endpoints for a tenant.
     */
    public List<StandaloneChatEndpointDto> getChatEndpoints(String tenantId) {
        String url = baseUrl + "/api/internal/trigger/chat-endpoints";
        HttpHeaders headers = buildHeaders(null);
        headers.set("X-User-ID", tenantId);
        try {
            ResponseEntity<List<StandaloneChatEndpointDto>> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers),
                    new ParameterizedTypeReference<>() {});
            return response.getBody() != null ? response.getBody() : Collections.emptyList();
        } catch (Exception e) {
            log.error("Failed to get chat endpoints: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Get a chat endpoint by ID.
     */
    public StandaloneChatEndpointDto getChatEndpointById(String tenantId, UUID endpointId) {
        String url = baseUrl + "/api/internal/trigger/chat-endpoints/" + endpointId;
        HttpHeaders headers = buildHeaders(null);
        headers.set("X-User-ID", tenantId);
        try {
            ResponseEntity<StandaloneChatEndpointDto> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), StandaloneChatEndpointDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.debug("Chat endpoint not found {}: {}", endpointId, e.getMessage());
            return null;
        }
    }

    /**
     * Regenerate token for a chat endpoint.
     */
    public StandaloneChatEndpointDto regenerateChatToken(String tenantId, UUID endpointId) {
        String url = baseUrl + "/api/internal/trigger/chat-endpoints/" + endpointId + "/regenerate-token";
        HttpHeaders headers = buildHeaders(null);
        headers.set("X-User-ID", tenantId);
        try {
            ResponseEntity<StandaloneChatEndpointDto> response = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(headers), StandaloneChatEndpointDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to regenerate chat token {}: {}", endpointId, e.getMessage());
            return null;
        }
    }

    /**
     * Get access logs for a chat endpoint.
     */
    public Map<String, Object> getChatEndpointAccessLogs(String tenantId, UUID endpointId, int page, int size) {
        String url = UriComponentsBuilder.fromHttpUrl(baseUrl + "/api/internal/trigger/chat-endpoints/" + endpointId + "/logs")
                .queryParam("page", page)
                .queryParam("size", size)
                .toUriString();
        HttpHeaders headers = buildHeaders(null);
        headers.set("X-User-ID", tenantId);
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers),
                    new ParameterizedTypeReference<>() {});
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to get chat access logs {}: {}", endpointId, e.getMessage());
            return null;
        }
    }

    /**
     * Get chat endpoint config (plan limits).
     */
    public EndpointConfigDto getChatEndpointConfig(String tenantId, String userPlan) {
        String url = baseUrl + "/api/internal/trigger/chat-endpoints/config";
        HttpHeaders headers = buildHeaders(null);
        headers.set("X-User-ID", tenantId);
        if (userPlan != null) headers.set("X-User-Plan", userPlan);
        try {
            ResponseEntity<EndpointConfigDto> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), EndpointConfigDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to get chat endpoint config: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Update workflow reference on a chat endpoint.
     */
    public StandaloneChatEndpointDto updateChatEndpointWorkflowReference(String tenantId, UUID endpointId,
                                                                          UUID workflowId, String workflowName) {
        String url = baseUrl + "/api/internal/trigger/chat-endpoints/" + endpointId + "/workflow";
        HttpHeaders headers = buildHeaders(null);
        headers.set("X-User-ID", tenantId);
        Map<String, String> body = new HashMap<>();
        if (workflowId != null) body.put("workflowId", workflowId.toString());
        if (workflowName != null) body.put("workflowName", workflowName);
        HttpEntity<Map<String, String>> entity = new HttpEntity<>(body, headers);
        try {
            ResponseEntity<StandaloneChatEndpointDto> response = restTemplate.exchange(
                    url, HttpMethod.PATCH, entity, StandaloneChatEndpointDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to update chat endpoint workflow ref {}: {}", endpointId, e.getMessage());
            return null;
        }
    }

    /**
     * Find a chat endpoint by its public token.
     */
    public StandaloneChatEndpointDto findChatEndpointByToken(String token) {
        String url = baseUrl + "/api/internal/trigger/chat-endpoints/by-token/" + token;
        try {
            ResponseEntity<StandaloneChatEndpointDto> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(buildHeaders(null)), StandaloneChatEndpointDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.debug("Chat endpoint not found for token: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Log a chat access event.
     */
    public void logChatAccess(UUID chatEndpointId, String sessionId, String conversationId,
                               String action, String ipAddress) {
        String url = baseUrl + "/api/internal/trigger/chat-endpoints/log-access";
        Map<String, Object> body = new HashMap<>();
        body.put("chatEndpointId", chatEndpointId.toString());
        if (sessionId != null) body.put("sessionId", sessionId);
        if (conversationId != null) body.put("conversationId", conversationId);
        body.put("action", action);
        if (ipAddress != null) body.put("ipAddress", ipAddress);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, buildHeaders(null));
        try {
            restTemplate.exchange(url, HttpMethod.POST, entity, Void.class);
        } catch (Exception e) {
            log.warn("Failed to log chat access: {}", e.getMessage());
        }
    }

    /**
     * Sync triggerId for all chat endpoints linked to a workflow.
     */
    public void syncChatEndpointTriggerId(UUID workflowId, String triggerId) {
        String url = baseUrl + "/api/internal/trigger/chat-endpoints/sync-trigger-id";
        Map<String, Object> body = new HashMap<>();
        body.put("workflowId", workflowId.toString());
        if (triggerId != null) body.put("triggerId", triggerId);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, buildHeaders(null));
        try {
            restTemplate.exchange(url, HttpMethod.PUT, entity, Void.class);
        } catch (Exception e) {
            log.warn("Failed to sync chat endpoint triggerIds for workflow {}: {}", workflowId, e.getMessage());
        }
    }

    // ========== Form Endpoint Operations ==========

    /**
     * Create a form endpoint via trigger-service.
     */
    public StandaloneFormEndpointDto createFormEndpoint(String tenantId, String userPlan,
                                                         StandaloneFormEndpointRequest request) {
        return createFormEndpoint(tenantId, userPlan, request, null);
    }

    /**
     * Org-aware overload - Audit 2026-05-17 round-3. Threads X-Organization-ID
     * so trigger-service stamps standalone_form_endpoints.organization_id.
     */
    public StandaloneFormEndpointDto createFormEndpoint(String tenantId, String userPlan,
                                                         StandaloneFormEndpointRequest request,
                                                         String organizationId) {
        String url = baseUrl + "/api/internal/trigger/form-endpoints";
        HttpHeaders headers = buildHeaders(null);
        headers.set("X-User-ID", tenantId);
        if (userPlan != null) headers.set("X-User-Plan", userPlan);
        OrgContextHeaderForwarder.setIfPresent(headers, organizationId);
        HttpEntity<StandaloneFormEndpointRequest> entity = new HttpEntity<>(request, headers);
        try {
            ResponseEntity<StandaloneFormEndpointDto> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, StandaloneFormEndpointDto.class);
            return response.getBody();
        } catch (org.springframework.web.client.HttpClientErrorException.Conflict e) {
            // 409 = endpoint already exists (idempotent re-save of a workflow/app). Not an
            // error - the desired state is already in place. Logged at DEBUG to keep ERROR
            // metrics/alerts meaningful. Audit 2026-06-14 (prod ERROR-noise cleanup).
            log.debug("Form endpoint already exists (409), idempotent no-op: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("Failed to create form endpoint: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Update a form endpoint.
     */
    public StandaloneFormEndpointDto updateFormEndpoint(String tenantId, UUID endpointId,
                                                         StandaloneFormEndpointRequest request) {
        String url = baseUrl + "/api/internal/trigger/form-endpoints/" + endpointId;
        HttpHeaders headers = buildHeaders(null);
        headers.set("X-User-ID", tenantId);
        HttpEntity<StandaloneFormEndpointRequest> entity = new HttpEntity<>(request, headers);
        try {
            ResponseEntity<StandaloneFormEndpointDto> response = restTemplate.exchange(
                    url, HttpMethod.PUT, entity, StandaloneFormEndpointDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to update form endpoint {}: {}", endpointId, e.getMessage());
            return null;
        }
    }

    /**
     * Delete a form endpoint.
     */
    public void deleteFormEndpoint(String tenantId, UUID endpointId) {
        String url = baseUrl + "/api/internal/trigger/form-endpoints/" + endpointId;
        HttpHeaders headers = buildHeaders(null);
        headers.set("X-User-ID", tenantId);
        try {
            restTemplate.exchange(url, HttpMethod.DELETE, new HttpEntity<>(headers), Void.class);
        } catch (Exception e) {
            log.warn("Failed to delete form endpoint {}: {}", endpointId, e.getMessage());
        }
    }

    /**
     * Get all form endpoints for a tenant.
     */
    public List<StandaloneFormEndpointDto> getFormEndpoints(String tenantId) {
        String url = baseUrl + "/api/internal/trigger/form-endpoints";
        HttpHeaders headers = buildHeaders(null);
        headers.set("X-User-ID", tenantId);
        try {
            ResponseEntity<List<StandaloneFormEndpointDto>> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers),
                    new ParameterizedTypeReference<>() {});
            return response.getBody() != null ? response.getBody() : Collections.emptyList();
        } catch (Exception e) {
            log.error("Failed to get form endpoints: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Get a form endpoint by ID.
     */
    public StandaloneFormEndpointDto getFormEndpointById(String tenantId, UUID endpointId) {
        String url = baseUrl + "/api/internal/trigger/form-endpoints/" + endpointId;
        HttpHeaders headers = buildHeaders(null);
        headers.set("X-User-ID", tenantId);
        try {
            ResponseEntity<StandaloneFormEndpointDto> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), StandaloneFormEndpointDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.debug("Form endpoint not found {}: {}", endpointId, e.getMessage());
            return null;
        }
    }

    /**
     * Regenerate token for a form endpoint.
     */
    public StandaloneFormEndpointDto regenerateFormToken(String tenantId, UUID endpointId) {
        String url = baseUrl + "/api/internal/trigger/form-endpoints/" + endpointId + "/regenerate-token";
        HttpHeaders headers = buildHeaders(null);
        headers.set("X-User-ID", tenantId);
        try {
            ResponseEntity<StandaloneFormEndpointDto> response = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(headers), StandaloneFormEndpointDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to regenerate form token {}: {}", endpointId, e.getMessage());
            return null;
        }
    }

    /**
     * Get submission logs for a form endpoint.
     */
    public Map<String, Object> getFormEndpointSubmissionLogs(String tenantId, UUID endpointId, int page, int size) {
        String url = UriComponentsBuilder.fromHttpUrl(baseUrl + "/api/internal/trigger/form-endpoints/" + endpointId + "/logs")
                .queryParam("page", page)
                .queryParam("size", size)
                .toUriString();
        HttpHeaders headers = buildHeaders(null);
        headers.set("X-User-ID", tenantId);
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers),
                    new ParameterizedTypeReference<>() {});
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to get form submission logs {}: {}", endpointId, e.getMessage());
            return null;
        }
    }

    /**
     * Get form endpoint config (plan limits).
     */
    public EndpointConfigDto getFormEndpointConfig(String tenantId, String userPlan) {
        String url = baseUrl + "/api/internal/trigger/form-endpoints/config";
        HttpHeaders headers = buildHeaders(null);
        headers.set("X-User-ID", tenantId);
        if (userPlan != null) headers.set("X-User-Plan", userPlan);
        try {
            ResponseEntity<EndpointConfigDto> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), EndpointConfigDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to get form endpoint config: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Update workflow reference on a form endpoint.
     */
    public StandaloneFormEndpointDto updateFormEndpointWorkflowReference(String tenantId, UUID endpointId,
                                                                          UUID workflowId, String workflowName) {
        String url = baseUrl + "/api/internal/trigger/form-endpoints/" + endpointId + "/workflow";
        HttpHeaders headers = buildHeaders(null);
        headers.set("X-User-ID", tenantId);
        Map<String, String> body = new HashMap<>();
        if (workflowId != null) body.put("workflowId", workflowId.toString());
        if (workflowName != null) body.put("workflowName", workflowName);
        HttpEntity<Map<String, String>> entity = new HttpEntity<>(body, headers);
        try {
            ResponseEntity<StandaloneFormEndpointDto> response = restTemplate.exchange(
                    url, HttpMethod.PATCH, entity, StandaloneFormEndpointDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to update form endpoint workflow ref {}: {}", endpointId, e.getMessage());
            return null;
        }
    }

    /**
     * Find a form endpoint by its public token.
     */
    public StandaloneFormEndpointDto findFormEndpointByToken(String token) {
        String url = baseUrl + "/api/internal/trigger/form-endpoints/by-token/" + token;
        try {
            ResponseEntity<StandaloneFormEndpointDto> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(buildHeaders(null)), StandaloneFormEndpointDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.debug("Form endpoint not found for token: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Log a form submission event.
     */
    public void logFormSubmission(UUID formEndpointId, Map<String, Object> submissionData,
                                   String status, int workflowsTriggered, String ipAddress) {
        String url = baseUrl + "/api/internal/trigger/form-endpoints/log-submission";
        Map<String, Object> body = new HashMap<>();
        body.put("formEndpointId", formEndpointId.toString());
        if (submissionData != null) body.put("submissionData", submissionData);
        body.put("responseStatus", status);
        body.put("workflowsTriggered", workflowsTriggered);
        if (ipAddress != null) body.put("ipAddress", ipAddress);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, buildHeaders(null));
        try {
            restTemplate.exchange(url, HttpMethod.POST, entity, Void.class);
        } catch (Exception e) {
            log.warn("Failed to log form submission: {}", e.getMessage());
        }
    }

    /**
     * Sync triggerId for all form endpoints linked to a workflow.
     */
    public void syncFormEndpointTriggerId(UUID workflowId, String triggerId) {
        String url = baseUrl + "/api/internal/trigger/form-endpoints/sync-trigger-id";
        Map<String, Object> body = new HashMap<>();
        body.put("workflowId", workflowId.toString());
        if (triggerId != null) body.put("triggerId", triggerId);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, buildHeaders(null));
        try {
            restTemplate.exchange(url, HttpMethod.PUT, entity, Void.class);
        } catch (Exception e) {
            log.warn("Failed to sync form endpoint triggerIds for workflow {}: {}", workflowId, e.getMessage());
        }
    }

    // ========== Agent Schedule Operations ==========

    /**
     * Create or update a schedule for an agent entity.
     */
    public ScheduledExecutionDto createAgentSchedule(UUID agentEntityId, String tenantId,
                                                      String cron, String timezone,
                                                      Integer maxExecutions, String schedulePrompt,
                                                      String name) {
        String url = baseUrl + "/api/internal/trigger/schedules/agent";
        Map<String, Object> body = new HashMap<>();
        body.put("agentEntityId", agentEntityId.toString());
        body.put("tenantId", tenantId);
        body.put("cronExpression", cron);
        body.put("timezone", timezone != null ? timezone : "UTC");
        if (maxExecutions != null) body.put("maxExecutions", maxExecutions);
        if (schedulePrompt != null) body.put("schedulePrompt", schedulePrompt);
        if (name != null) body.put("name", name);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, buildHeaders(null));
        try {
            ResponseEntity<ScheduledExecutionDto> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, ScheduledExecutionDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to create agent schedule for agent={}: {}", agentEntityId, e.getMessage());
            return null;
        }
    }

    /**
     * Get the schedule for an agent entity.
     */
    public ScheduledExecutionDto getAgentSchedule(UUID agentEntityId, String tenantId) {
        String url = UriComponentsBuilder
                .fromHttpUrl(baseUrl + "/api/internal/trigger/schedules/by-agent/" + agentEntityId)
                .queryParam("tenantId", tenantId)
                .toUriString();
        try {
            ResponseEntity<ScheduledExecutionDto> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(buildHeaders(null)), ScheduledExecutionDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.debug("Agent schedule not found for agent={}: {}", agentEntityId, e.getMessage());
            return null;
        }
    }

    /**
     * Delete the schedule for an agent entity.
     */
    public void deleteAgentSchedule(UUID agentEntityId) {
        String url = baseUrl + "/api/internal/trigger/schedules/by-agent/" + agentEntityId;
        try {
            restTemplate.exchange(url, HttpMethod.DELETE, new HttpEntity<>(buildHeaders(null)), Void.class);
        } catch (Exception e) {
            log.warn("Failed to delete agent schedule for agent={}: {}", agentEntityId, e.getMessage());
        }
    }

    /**
     * Toggle enable/disable for an agent schedule.
     */
    public ScheduledExecutionDto toggleAgentSchedule(UUID agentEntityId, String tenantId, boolean enabled) {
        String url = UriComponentsBuilder
                .fromHttpUrl(baseUrl + "/api/internal/trigger/schedules/by-agent/" + agentEntityId + "/toggle")
                .queryParam("tenantId", tenantId)
                .queryParam("enabled", enabled)
                .toUriString();
        try {
            ResponseEntity<ScheduledExecutionDto> response = restTemplate.exchange(
                    url, HttpMethod.PATCH, new HttpEntity<>(buildHeaders(null)), ScheduledExecutionDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.warn("Failed to toggle agent schedule for agent={}: {}", agentEntityId, e.getMessage());
            return null;
        }
    }

    // ========== Datasource Trigger Subscriptions ==========

    /**
     * Upsert a datasource trigger subscription. Called by orchestrator's
     * DatasourceSubscriptionSyncService when a workflow is pinned/saved.
     */
    public DatasourceTriggerSubscriptionDto createOrUpdateDatasourceSubscription(DatasourceSubscriptionRequest request) {
        String url = baseUrl + "/api/internal/trigger/datasource-subscriptions";
        HttpEntity<DatasourceSubscriptionRequest> entity = new HttpEntity<>(request, buildHeaders(null));
        try {
            ResponseEntity<DatasourceTriggerSubscriptionDto> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, DatasourceTriggerSubscriptionDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to create/update datasource subscription for workflow={}, trigger={}: {}",
                    request.getWorkflowId(), request.getTriggerId(), e.getMessage());
            return null;
        }
    }

    /**
     * Get all datasource subscriptions for a workflow.
     */
    public List<DatasourceTriggerSubscriptionDto> getDatasourceSubscriptionsByWorkflow(UUID workflowId) {
        String url = baseUrl + "/api/internal/trigger/datasource-subscriptions/by-workflow/" + workflowId;
        try {
            ResponseEntity<List<DatasourceTriggerSubscriptionDto>> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(buildHeaders(null)),
                    new ParameterizedTypeReference<>() {});
            return response.getBody() != null ? response.getBody() : Collections.emptyList();
        } catch (Exception e) {
            log.warn("Failed to get datasource subscriptions for workflow={}: {}", workflowId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Delete all datasource subscriptions for a workflow.
     */
    public void deleteDatasourceSubscriptionsByWorkflow(UUID workflowId) {
        String url = baseUrl + "/api/internal/trigger/datasource-subscriptions/by-workflow/" + workflowId;
        try {
            restTemplate.exchange(url, HttpMethod.DELETE, new HttpEntity<>(buildHeaders(null)), Void.class);
        } catch (Exception e) {
            log.warn("Failed to delete datasource subscriptions for workflow={}: {}", workflowId, e.getMessage());
        }
    }

    /**
     * Remove subscriptions for a workflow that no longer appear in the current trigger list.
     */
    public void pruneDatasourceSubscriptions(UUID workflowId, List<String> currentTriggerIds) {
        String url = baseUrl + "/api/internal/trigger/datasource-subscriptions/by-workflow/" + workflowId + "/prune";
        HttpEntity<List<String>> entity = new HttpEntity<>(currentTriggerIds, buildHeaders(null));
        try {
            restTemplate.exchange(url, HttpMethod.POST, entity, Void.class);
        } catch (Exception e) {
            log.warn("Failed to prune datasource subscriptions for workflow={}: {}", workflowId, e.getMessage());
        }
    }

    /**
     * Dispatch a row event from datasource-service to trigger-service, which
     * fans out to matching subscriptions and calls orchestrator's fire endpoint.
     * Fire-and-forget - never throws (event loss on outage is preferred to
     * blocking the datasource write).
     */
    public void dispatchDatasourceEvent(DatasourceEventDispatchRequest request) {
        String url = baseUrl + "/api/internal/trigger/datasource-events/dispatch";
        HttpEntity<DatasourceEventDispatchRequest> entity = new HttpEntity<>(request, buildHeaders(null));
        try {
            restTemplate.exchange(url, HttpMethod.POST, entity, Void.class);
        } catch (Exception e) {
            log.warn("Failed to dispatch datasource event type={} datasource={} row={}: {}",
                    request.getEventType(), request.getDataSourceId(), request.getRowId(), e.getMessage());
        }
    }

    // ========== Helpers ==========

    private HttpHeaders buildHeaders(String tenantId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        if (tenantId != null) {
            headers.set("X-User-ID", tenantId);
        }
        // PR16 - forward X-Organization-ID / X-Organization-Role from the
        // inbound request to keep workspace context across cross-service hops.
        OrgContextHeaderForwarder.forward(headers);
        return headers;
    }

}
