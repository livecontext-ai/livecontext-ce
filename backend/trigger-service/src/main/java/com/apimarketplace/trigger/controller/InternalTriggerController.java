package com.apimarketplace.trigger.controller;

import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.common.scope.TolerantScope;
import com.apimarketplace.trigger.client.dto.*;
import com.apimarketplace.trigger.domain.ScheduledExecutionEntity;
import com.apimarketplace.trigger.domain.StandaloneChatEndpointEntity;
import com.apimarketplace.trigger.domain.StandaloneFormEndpointEntity;
import com.apimarketplace.trigger.domain.StandaloneWebhookEntity;
import com.apimarketplace.trigger.domain.TriggerState;
import com.apimarketplace.trigger.domain.WebhookTokenEntity;
import com.apimarketplace.trigger.repository.ScheduledExecutionRepository;
import com.apimarketplace.trigger.repository.StandaloneChatEndpointRepository;
import com.apimarketplace.trigger.repository.StandaloneFormEndpointRepository;
import com.apimarketplace.trigger.repository.StandaloneWebhookRepository;
import com.apimarketplace.trigger.service.ScheduleCronParser;
import com.apimarketplace.trigger.service.StandaloneChatEndpointService;
import com.apimarketplace.trigger.service.StandaloneFormEndpointService;
import com.apimarketplace.trigger.service.StandaloneScheduleService;
import com.apimarketplace.trigger.service.TriggerLifecycleManager;
import com.apimarketplace.trigger.service.StandaloneWebhookService;
import com.apimarketplace.trigger.service.WebhookTokenService;
import com.apimarketplace.trigger.service.WorkflowReferenceImmutableException;
import org.springframework.data.domain.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Internal API controller for orchestrator-to-trigger-service calls.
 * Not exposed via gateway - only used by orchestrator through TriggerClient.
 */
@RestController
@RequestMapping("/api/internal/trigger")
public class InternalTriggerController {

    private static final Logger logger = LoggerFactory.getLogger(InternalTriggerController.class);

    @Value("${orchestrator.webhook.base-url:http://localhost:8080}")
    private String baseUrl;

    private final WebhookTokenService tokenService;
    private final StandaloneWebhookService standaloneWebhookService;
    private final StandaloneScheduleService standaloneScheduleService;
    private final StandaloneChatEndpointService chatEndpointService;
    private final StandaloneFormEndpointService formEndpointService;
    private final StandaloneWebhookRepository webhookRepository;
    private final StandaloneChatEndpointRepository chatEndpointRepository;
    private final StandaloneFormEndpointRepository formEndpointRepository;
    private final ScheduledExecutionRepository scheduleRepository;
    private final ScheduleCronParser cronParser;
    private final TriggerLifecycleManager triggerLifecycleManager;

    public InternalTriggerController(WebhookTokenService tokenService,
                                     StandaloneWebhookService standaloneWebhookService,
                                     StandaloneScheduleService standaloneScheduleService,
                                     StandaloneChatEndpointService chatEndpointService,
                                     StandaloneFormEndpointService formEndpointService,
                                     StandaloneWebhookRepository webhookRepository,
                                     StandaloneChatEndpointRepository chatEndpointRepository,
                                     StandaloneFormEndpointRepository formEndpointRepository,
                                     ScheduledExecutionRepository scheduleRepository,
                                     ScheduleCronParser cronParser,
                                     TriggerLifecycleManager triggerLifecycleManager) {
        this.tokenService = tokenService;
        this.standaloneWebhookService = standaloneWebhookService;
        this.standaloneScheduleService = standaloneScheduleService;
        this.chatEndpointService = chatEndpointService;
        this.formEndpointService = formEndpointService;
        this.webhookRepository = webhookRepository;
        this.chatEndpointRepository = chatEndpointRepository;
        this.formEndpointRepository = formEndpointRepository;
        this.scheduleRepository = scheduleRepository;
        this.cronParser = cronParser;
        this.triggerLifecycleManager = triggerLifecycleManager;
    }

    // ========== Webhook Token Operations ==========

    @GetMapping("/tokens/by-token/{token}")
    public ResponseEntity<WebhookTokenDto> findByToken(@PathVariable("token") String token) {
        Optional<WebhookTokenEntity> entity = tokenService.findByToken(token);
        return entity.map(e -> ResponseEntity.ok(toTokenDto(e)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/tokens/ensure")
    @Transactional
    public ResponseEntity<WebhookTokenDto> ensureTokenForTrigger(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-User-ID", required = false) String headerUserId,
            @RequestHeader("X-Organization-ID") String headerOrgId) {
        UUID workflowId = UUID.fromString(body.get("workflowId").toString());
        String triggerId = body.get("triggerId").toString();
        // PR22c R3 - workspace tag plumbed end-to-end so the dispatch guard at
        // WebhookDispatchService:133 has a workspace to compare. Body wins over
        // header (explicit caller intent). PR16 forwarder threads the header
        // automatically when callers don't pass an explicit body field.
        String bodyOrgId = body.get("organizationId") != null ? body.get("organizationId").toString() : null;
        String organizationId = (bodyOrgId != null && !bodyOrgId.isBlank()) ? bodyOrgId : headerOrgId;
        // V253 (2026-05-18) - tenant tag captured at create time for the
        // strict-isolation check in deleteTokensForWorkflowScoped. Same body/
        // header precedence as orgId.
        String bodyTenantId = body.get("tenantId") != null ? body.get("tenantId").toString() : null;
        String tenantId = (bodyTenantId != null && !bodyTenantId.isBlank()) ? bodyTenantId : headerUserId;
        WebhookTokenEntity entity = tokenService.ensureTokenForTrigger(
                workflowId, triggerId, organizationId, tenantId);
        return ResponseEntity.ok(toTokenDto(entity));
    }

    @GetMapping("/tokens/by-workflow/{workflowId}")
    public ResponseEntity<Map<String, String>> getTokensForWorkflow(@PathVariable("workflowId") UUID workflowId) {
        return ResponseEntity.ok(tokenService.getTokensForWorkflow(workflowId));
    }

    @PostMapping("/tokens/regenerate")
    @Transactional
    public ResponseEntity<WebhookTokenDto> regenerateToken(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-User-ID", required = false) String headerUserId,
            @RequestHeader("X-Organization-ID") String headerOrgId) {
        UUID workflowId = UUID.fromString(body.get("workflowId").toString());
        String triggerId = body.get("triggerId").toString();
        // PR22c R3 - see ensureTokenForTrigger above. Body wins over header.
        String bodyOrgId = body.get("organizationId") != null ? body.get("organizationId").toString() : null;
        String organizationId = (bodyOrgId != null && !bodyOrgId.isBlank()) ? bodyOrgId : headerOrgId;
        // V253 (2026-05-18) - tenant tag stamped on brand-new rows (regenerate
        // also enters the create branch when no token exists yet).
        String bodyTenantId = body.get("tenantId") != null ? body.get("tenantId").toString() : null;
        String tenantId = (bodyTenantId != null && !bodyTenantId.isBlank()) ? bodyTenantId : headerUserId;
        WebhookTokenEntity entity = tokenService.regenerateTokenForTrigger(
                workflowId, triggerId, organizationId, tenantId);
        return ResponseEntity.ok(toTokenDto(entity));
    }

    /**
     * Cascade-delete every webhook token for the given workflowId, scoped to
     * the caller's owner-or-org reach. Audit 2026-05-16 round-3: prior
     * implementation deleted by FK only, allowing cross-tenant attack if
     * the internal route was ever exposed. New contract:
     *
     * <ul>
     *   <li>Caller supplies {@code X-User-ID} (always) and may supply
     *       {@code X-Organization-ID} (when acting in an org workspace).</li>
     *   <li>Only tokens whose {@code organization_id} matches the caller's
     *       active org are deleted. Tokens without {@code organization_id}
     *       (legacy / personal) are kept under tenant equality.</li>
     *   <li>If no tokens for that workflowId match the caller's scope at
     *       all → 404 (don't leak workflowId existence cross-tenant).</li>
     * </ul>
     *
     * <p>Cascade-safe for org-shared workflows: when Bob (org-teammate)
     * deletes Alice's workflow, the tokens' {@code organization_id} matches
     * Bob's active org so they're cleaned up.
     */
    @DeleteMapping("/tokens/by-workflow/{workflowId}")
    @Transactional
    public ResponseEntity<Void> deleteTokensForWorkflow(
            @PathVariable("workflowId") UUID workflowId,
            @RequestHeader(value = "X-User-ID", required = false) String tenantId,
            @RequestHeader("X-Organization-ID") String orgId) {
        if (orgId == null || orgId.isBlank()) {
            return ResponseEntity.status(401).build();
        }
        int deleted = tokenService.deleteTokensForWorkflowScoped(workflowId, tenantId, orgId);
        if (deleted == 0) {
            // Either workflow has no tokens, or none in caller's scope. Treat both as 404.
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/tokens/workflow-ids-with-tokens")
    public ResponseEntity<Set<UUID>> findWorkflowIdsWithTokens(@RequestBody List<UUID> workflowIds) {
        return ResponseEntity.ok(tokenService.findWorkflowIdsWithTokens(workflowIds));
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/tokens/cleanup-orphans")
    @Transactional
    public ResponseEntity<Void> cleanupOrphanTokens(@RequestBody Map<String, Object> body) {
        UUID workflowId = UUID.fromString(body.get("workflowId").toString());
        List<String> currentTriggerIds = (List<String>) body.get("currentTriggerIds");
        tokenService.cleanupOrphanTokens(workflowId, currentTriggerIds);
        return ResponseEntity.noContent().build();
    }

    // ========== Standalone Webhook Operations ==========

    @GetMapping("/webhooks/by-token/{token}")
    public ResponseEntity<StandaloneWebhookDto> findStandaloneByToken(@PathVariable("token") String token) {
        Optional<StandaloneWebhookEntity> entity = standaloneWebhookService.findByToken(token);
        return entity.map(e -> ResponseEntity.ok(toStandaloneDto(e)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/webhooks/by-workflow/{workflowId}")
    public ResponseEntity<List<StandaloneWebhookDto>> findStandaloneByWorkflowId(
            @PathVariable("workflowId") UUID workflowId,
            @RequestHeader("X-Organization-ID") String organizationId) {
        List<StandaloneWebhookEntity> entities = standaloneWebhookService.findByWorkflowId(workflowId);
        // Strict-org filter on the workflow-side trigger panel read (post-V261: every row has a non-null org).
        entities = entities.stream()
                .filter(w -> organizationId.equals(w.getOrganizationId()))
                .toList();
        return ResponseEntity.ok(entities.stream().map(this::toStandaloneDto).toList());
    }

    @PostMapping("/webhooks/log-call")
    @Transactional
    public ResponseEntity<Void> logWebhookCall(@RequestBody WebhookCallLogRequest request) {
        standaloneWebhookService.logCall(
                request.webhookId(), request.requestMethod(),
                request.requestHeaders(), request.requestPayload(),
                request.responseStatus(), request.workflowsTriggered());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/webhooks/{webhookId}/decrypted-auth")
    public ResponseEntity<Map<String, String>> getDecryptedAuthConfig(
            @PathVariable("webhookId") UUID webhookId) {
        Optional<StandaloneWebhookEntity> entityOpt = webhookRepository.findById(webhookId);
        if (entityOpt.isEmpty()) return ResponseEntity.notFound().build();
        Map<String, String> decrypted = standaloneWebhookService.decryptAuthConfig(entityOpt.get().getAuthConfig());
        return ResponseEntity.ok(decrypted != null ? decrypted : Collections.emptyMap());
    }

    @PostMapping("/webhooks/create")
    public ResponseEntity<StandaloneWebhookDto> createStandaloneWebhook(
            @RequestBody Map<String, Object> body,
            @RequestHeader("X-Organization-ID") String headerOrgId) {
        String tenantId = (String) body.get("tenantId");
        String name = (String) body.get("name");
        String description = (String) body.get("description");
        String httpMethod = (String) body.getOrDefault("httpMethod", "POST");
        String authType = (String) body.getOrDefault("authType", "none");
        String sourceNodeId = (String) body.get("sourceNodeId");
        // Workspace identity. Body field wins over header so explicit caller
        // intent overrides header drift; header is the default contract from
        // agent-side trigger setup.
        String bodyOrgId = (String) body.get("organizationId");
        String organizationId = (bodyOrgId != null && !bodyOrgId.isBlank()) ? bodyOrgId : headerOrgId;

        com.apimarketplace.trigger.client.dto.StandaloneWebhookRequest request =
                new com.apimarketplace.trigger.client.dto.StandaloneWebhookRequest(
                        name, description, httpMethod, authType, null, null, null, sourceNodeId);

        // Internal caller (agent-side webhook setup) - pass the tenant's plan
        // string if provided, otherwise null so PlanLimitHelper applies its
        // default. Dedup on sourceNodeId short-circuits before the limit check.
        String userPlan = (String) body.get("userPlan");
        StandaloneWebhookDto dto = standaloneWebhookService.create(tenantId, organizationId, userPlan, request);
        return ResponseEntity.ok(dto);
    }

    // Used by orchestrator's PinAwareTriggerSyncService to push webhook config
    // (httpMethod, authType, authConfig, workflow back-link) from the workflow
    // plan to the standalone_webhooks row. Without this PUT path, edits in the
    // builder UI never reach the row and a webhook can stay stuck on its
    // creation-time auth config (parity with the schedule cron-stale bug).
    @PutMapping("/webhooks/{webhookId}")
    public ResponseEntity<StandaloneWebhookDto> updateStandaloneWebhook(
            @PathVariable("webhookId") UUID webhookId,
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader("X-Organization-ID") String organizationId,
            @RequestBody com.apimarketplace.trigger.client.dto.StandaloneWebhookRequest request) {
        try {
            // PR24 R1 audit convergent must-fix A+C: this orchestrator builder-sync
            // path was overlooked in the first PR24 pass. Without orgId threading,
            // every org-scoped webhook would 404 on save (stale-auth regression
            // because PinAwareTriggerSyncService.updateStandaloneWebhookStrict
            // is the canonical channel for httpMethod / authType / authConfig pushes).
            return ResponseEntity.ok(standaloneWebhookService.update(tenantId, organizationId, webhookId, request));
        } catch (WorkflowReferenceImmutableException e) {
            return ResponseEntity.badRequest().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // ========== Schedule Operations ==========

    @GetMapping("/schedules/due")
    public ResponseEntity<List<ScheduledExecutionDto>> findDueSchedules(
            @RequestParam("before") String before) {
        Instant instant = Instant.parse(before);
        List<ScheduledExecutionEntity> entities = scheduleRepository.findDueExecutions(instant);
        return ResponseEntity.ok(entities.stream().map(this::toScheduleDto).toList());
    }

    @PostMapping("/schedules/claim-and-advance")
    @Transactional
    public ResponseEntity<List<ScheduledExecutionDto>> claimAndAdvanceDueSchedules(
            @RequestParam("before") String before) {
        Instant instant = Instant.parse(before);
        List<ScheduledExecutionEntity> entities = scheduleRepository.findDueForUpdate(instant);
        List<ScheduledExecutionDto> result = new java.util.ArrayList<>();
        for (ScheduledExecutionEntity schedule : entities) {
            Instant originalNextExecution = schedule.getNextExecutionAt();
            int originalCount = schedule.getExecutionCount();

            if (!cronParser.isValid(schedule.getCronExpression())) {
                logger.warn("[Schedule] Auto-archiving schedule {} during claim - invalid cron '{}'",
                        schedule.getId(), schedule.getCronExpression());
                triggerLifecycleManager.archiveSchedule(schedule.getId(),
                        TriggerLifecycleManager.Reason.INVALID_CRON_LEGACY,
                        TriggerLifecycleManager.Source.REAPER);
                continue;
            }

            Instant nextExecutionAt = cronParser.getNextExecution(
                    schedule.getCronExpression(), schedule.getTimezone());
            if (nextExecutionAt == null) {
                nextExecutionAt = Instant.now().plusSeconds(60);
            }

            schedule.recordExecution(nextExecutionAt);
            scheduleRepository.save(schedule);

            ScheduledExecutionDto dto = toScheduleDto(schedule);
            dto.setNextExecutionAt(originalNextExecution);
            dto.setExecutionCount(originalCount);
            result.add(dto);
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/schedules/{scheduleId}")
    public ResponseEntity<ScheduledExecutionDto> getSchedule(@PathVariable("scheduleId") UUID scheduleId) {
        return scheduleRepository.findById(scheduleId)
                .map(e -> ResponseEntity.ok(toScheduleDto(e)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/schedules/find")
    public ResponseEntity<ScheduledExecutionDto> findSchedule(
            @RequestParam("workflowId") UUID workflowId,
            @RequestParam("triggerId") String triggerId,
            @RequestParam(value = "organizationId", required = false) String queryOrgId,
            @RequestHeader(value = "X-Organization-ID", required = false) String headerOrgId) {
        String organizationId = hasText(queryOrgId) ? queryOrgId : headerOrgId;
        if (!hasText(organizationId)) {
            return ResponseEntity.badRequest().build();
        }
        // Use list query to survive any pre-V60 duplicates
        List<ScheduledExecutionEntity> results = scheduleRepository
                .findAllByWorkflowIdAndTriggerId(workflowId, triggerId);
        // Strict-org filter (post-V261: every row has a non-null org).
        results = results.stream()
                .filter(s -> organizationId.equals(s.getOrganizationId()))
                .toList();
        if (results.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toScheduleDto(results.get(0)));
    }

    @GetMapping("/schedules/by-workflow/{workflowId}")
    public ResponseEntity<List<ScheduledExecutionDto>> getSchedulesByWorkflow(
            @PathVariable("workflowId") UUID workflowId,
            @RequestParam(value = "organizationId", required = false) String queryOrgId,
            @RequestHeader(value = "X-Organization-ID", required = false) String headerOrgId) {
        String organizationId = hasText(queryOrgId) ? queryOrgId : headerOrgId;
        if (!hasText(organizationId)) {
            return ResponseEntity.badRequest().build();
        }
        List<ScheduledExecutionEntity> entities = scheduleRepository.findByWorkflowId(workflowId);
        // Strict-org filter (same shape as findSchedule above).
        entities = entities.stream()
                .filter(s -> organizationId.equals(s.getOrganizationId()))
                .toList();
        return ResponseEntity.ok(entities.stream().map(this::toScheduleDto).toList());
    }

    @PutMapping("/schedules/{scheduleId}/record-execution")
    @Transactional
    public ResponseEntity<ScheduledExecutionDto> recordScheduleExecution(
            @PathVariable("scheduleId") UUID scheduleId,
            @RequestBody(required = false) Map<String, Object> body) {
        ScheduledExecutionEntity schedule = scheduleRepository.findById(scheduleId).orElse(null);
        if (schedule == null) return ResponseEntity.notFound().build();

        // Legacy-data hot-loop guard. The strict step validator (rejecting `*/N` where
        // N exceeds the field range) is enforced on every NEW write, but rows already
        // persisted in production with a now-invalid cron (e.g. `*/120 * * * *` from
        // the silent-collapse era) would, on every fire, reach this method, get
        // `getNextExecution == null`, fall back to `Instant.now() + 60s`, and start
        // firing every minute forever - worse than the original bug. Detect the case
        // and ARCHIVE the row via the lifecycle manager so it stops dispatching and
        // surfaces in the audit log for operator review. The lifecycle manager keeps
        // the legacy `enabled` / `is_active` booleans in sync via its contract.
        if (!cronParser.isValid(schedule.getCronExpression())) {
            logger.warn("[Schedule] Auto-archiving schedule {} - persisted cron '{}' is no longer valid under strict validation " +
                    "(silent-collapse legacy). The row will not fire again until manually re-created with a valid cron.",
                    scheduleId, schedule.getCronExpression());
            triggerLifecycleManager.archiveSchedule(scheduleId,
                    TriggerLifecycleManager.Reason.INVALID_CRON_LEGACY,
                    TriggerLifecycleManager.Source.REAPER);
            return ResponseEntity.ok(toScheduleDto(schedule));
        }

        // Compute real next execution from cron expression and timezone
        Instant nextExecutionAt = cronParser.getNextExecution(
                schedule.getCronExpression(), schedule.getTimezone());
        if (nextExecutionAt == null) {
            // Defensive fallback: cron is reported valid but no future firing exists
            // (extremely unlikely - `0 0 31 2 *` style impossible-day patterns).
            // Use the caller-supplied value when present, otherwise the +60s safety.
            if (body != null && body.get("nextExecutionAt") != null) {
                nextExecutionAt = Instant.parse(body.get("nextExecutionAt").toString());
            } else {
                nextExecutionAt = Instant.now().plusSeconds(60);
            }
            logger.warn("[Schedule] Failed to compute next cron execution for schedule {} (cron valid but no future firing), " +
                    "using fallback: {}", scheduleId, nextExecutionAt);
        }

        schedule.recordExecution(nextExecutionAt);
        ScheduledExecutionEntity saved = scheduleRepository.save(schedule);
        return ResponseEntity.ok(toScheduleDto(saved));
    }

    @PutMapping("/schedules/{scheduleId}/restore-dispatch")
    @Transactional
    public ResponseEntity<ScheduledExecutionDto> restoreScheduleDispatch(
            @PathVariable("scheduleId") UUID scheduleId,
            @RequestBody Map<String, Object> body) {
        ScheduledExecutionEntity schedule = scheduleRepository.findById(scheduleId).orElse(null);
        if (schedule == null) return ResponseEntity.notFound().build();

        int previousExecutionCount = intBody(body, "previousExecutionCount", schedule.getExecutionCount());
        int advancedExecutionCount = intBody(body, "advancedExecutionCount", previousExecutionCount + 1);
        Instant advancedNextExecutionAt = instantBody(body, "advancedNextExecutionAt", schedule.getNextExecutionAt());
        Instant advancedLastExecutionAt = instantBody(body, "advancedLastExecutionAt", schedule.getLastExecutionAt());

        if (schedule.getExecutionCount() != advancedExecutionCount
                || !Objects.equals(schedule.getNextExecutionAt(), advancedNextExecutionAt)
                || !Objects.equals(schedule.getLastExecutionAt(), advancedLastExecutionAt)) {
            logger.warn("[Schedule] Refusing restore-dispatch for schedule {} because dispatch markers changed "
                            + "(currentCount={}, expectedCount={}, currentNext={}, expectedNext={}, "
                            + "currentLast={}, expectedLast={})",
                    scheduleId, schedule.getExecutionCount(), advancedExecutionCount,
                    schedule.getNextExecutionAt(), advancedNextExecutionAt,
                    schedule.getLastExecutionAt(), advancedLastExecutionAt);
            return ResponseEntity.ok(toScheduleDto(schedule));
        }

        schedule.setNextExecutionAt(instantBody(body, "previousNextExecutionAt", schedule.getNextExecutionAt()));
        schedule.setLastExecutionAt(instantBody(body, "previousLastExecutionAt", null));
        schedule.setExecutionCount(previousExecutionCount);
        schedule.setUpdatedAt(Instant.now());
        ScheduledExecutionEntity saved = scheduleRepository.save(schedule);
        return ResponseEntity.ok(toScheduleDto(saved));
    }

    /**
     * Round-7 PR5: legacy hard-disable endpoint, kept for back-compat. Internally
     * routes through TriggerLifecycleManager.archiveSchedule (state=ARCHIVED), which
     * keeps the legacy {@code enabled}/{@code is_active} booleans in sync via the
     * lifecycle manager's contract. Callers SHOULD prefer
     * {@code /schedules/{id}/suspend?reason=…} for non-permanent disables.
     */
    @PutMapping("/schedules/{scheduleId}/disable")
    public ResponseEntity<Void> disableSchedule(@PathVariable("scheduleId") UUID scheduleId) {
        if (!scheduleRepository.existsById(scheduleId)) return ResponseEntity.notFound().build();
        triggerLifecycleManager.archiveSchedule(scheduleId,
            TriggerLifecycleManager.Reason.USER_DISABLED,
            TriggerLifecycleManager.Source.ADMIN);
        return ResponseEntity.ok().build();
    }

    /**
     * Round-7 PR5: suspend a schedule with a reason (recoverable, distinct from
     * permanent disable). Used by ScheduleSyncService when a workflow is unpinned -
     * the schedule is paused (state=SUSPENDED_UNPINNED) but not archived, so a future
     * re-pin event can re-arm it via {@code TriggerLifecycleManager.armSchedule}.
     */
    @PutMapping("/schedules/{scheduleId}/suspend")
    public ResponseEntity<Void> suspendSchedule(@PathVariable("scheduleId") UUID scheduleId,
                                                @RequestParam("reason") String reason) {
        if (!scheduleRepository.existsById(scheduleId)) return ResponseEntity.notFound().build();
        triggerLifecycleManager.suspendSchedule(scheduleId, reason,
            TriggerLifecycleManager.Source.SYNC);
        return ResponseEntity.ok().build();
    }

    @PutMapping("/schedules/{scheduleId}/enable")
    public ResponseEntity<Void> enableSchedule(@PathVariable("scheduleId") UUID scheduleId) {
        if (!scheduleRepository.existsById(scheduleId)) return ResponseEntity.notFound().build();
        triggerLifecycleManager.armSchedule(scheduleId, TriggerLifecycleManager.Source.ADMIN);
        return ResponseEntity.ok().build();
    }

    @PutMapping("/schedules/by-workflow/{workflowId}/enable")
    @Transactional
    public ResponseEntity<Integer> enableSchedulesByWorkflow(
            @PathVariable("workflowId") UUID workflowId,
            @RequestParam(value = "organizationId", required = false) String queryOrgId,
            @RequestHeader(value = "X-Organization-ID", required = false) String headerOrgId) {
        // RC1: returns the count of DISPATCHABLE rows post-arm (state=ACTIVE),
        // NOT the total row count. Previously the endpoint returned schedules.size()
        // which inflated the count with ARCHIVED rows: a workflow with 2 ARCHIVED
        // schedule rows + a pinned plan declaring 2 schedule triggers would read
        // expected=2, actual=2 → "aligned" → no alarm. But ARCHIVED rows do not
        // dispatch. By returning the dispatchable count, the caller's
        // expected-vs-actual comparison becomes honest: ARCHIVED rows count as 0
        // toward dispatchable, so any verification path can reliably distinguish
        // "everything armed and ready to fire" from "rows present but inert".
        //
        // The `armSchedule` call (RC2) refuses ARCHIVED transitions outright,
        // so this loop never silently revives an archive - the boolean return
        // tells us per-row whether the post-arm state is ACTIVE+enabled+isActive.
        String organizationId = hasText(queryOrgId) ? queryOrgId : headerOrgId;
        List<ScheduledExecutionEntity> schedules = scheduleRepository.findByWorkflowId(workflowId);
        if (hasText(organizationId)) {
            schedules = schedules.stream()
                    .filter(schedule -> organizationId.equals(schedule.getOrganizationId()))
                    .toList();
        }
        int dispatchableCount = 0;
        for (ScheduledExecutionEntity schedule : schedules) {
            // Do NOT resurrect orphans suspended because their trigger was removed from the
            // plan. This bulk re-arm exists to recover SUSPENDED_UNPINNED rows (post re-pin,
            // see ScheduleSyncService.syncFromPinnedVersion) and USER_DISABLED rows (post
            // workflow reactivate) - NOT structural PLAN_TRIGGER_REMOVED deletions. Without
            // this guard the orphan-suspend done by ScheduleSyncService.cleanupOrphanSchedules
            // is undone ~20ms later in the SAME sync, so a schedule whose trigger was deleted
            // from the plan keeps firing forever (new epoch every tick). Re-adding the trigger
            // re-activates it via createOrUpdateScheduleInternal (which explicitly arms a
            // PLAN_TRIGGER_REMOVED row), so the re-add path does not depend on this sweep.
            //
            // Reason-based skip residual: if the row was ALREADY suspended for a stronger reason
            // (e.g. USER_DISABLED, precedence 70) BEFORE its trigger was removed, the orphan
            // suspend's PLAN_TRIGGER_REMOVED (50) is precedence-masked, so the reason stays
            // USER_DISABLED and this skip does not catch it - the row gets re-armed here. That is
            // bounded: the orchestrator fire-time guard (ReusableTriggerService.executeTriggerInternal)
            // refuses the fire because the trigger is absent from the plan, so NO epoch is ever
            // opened; the row settles back to PLAN_TRIGGER_REMOVED on the next sync once the masking
            // reason clears. A reason-independent fix (caller passes the in-plan trigger id set) is a
            // follow-up.
            if (TriggerLifecycleManager.Reason.PLAN_TRIGGER_REMOVED.equals(schedule.getLastDisabledReason())
                    && schedule.getState() != TriggerState.ACTIVE) {
                continue;
            }
            if (triggerLifecycleManager.armSchedule(schedule.getId(),
                    TriggerLifecycleManager.Source.SYNC)) {
                dispatchableCount++;
            }
        }
        return ResponseEntity.ok(dispatchableCount);
    }

    /**
     * Scope-aware bulk-delete of all schedules for a workflow.
     * Audit 2026-05-16 round-3: prior version deleted by FK with no scope filter.
     * Now only deletes rows the caller has owner-or-org reach over.
     */
    @DeleteMapping("/schedules/by-workflow/{workflowId}")
    @Transactional
    public ResponseEntity<Void> deleteSchedulesByWorkflow(
            @PathVariable("workflowId") UUID workflowId,
            @RequestHeader(value = "X-User-ID", required = false) String tenantId,
            @RequestHeader("X-Organization-ID") String orgId) {
        if (orgId == null || orgId.isBlank()) {
            return ResponseEntity.status(401).build();
        }
        List<ScheduledExecutionEntity> rows = scheduleRepository.findByWorkflowId(workflowId);
        if (rows.isEmpty()) return ResponseEntity.noContent().build();
        int deleted = 0;
        for (ScheduledExecutionEntity s : rows) {
            if (isRowInScope(s, tenantId, orgId)) {
                scheduleRepository.delete(s);
                deleted++;
            }
        }
        if (deleted == 0) {
            logger.warn("[SCOPE] Refused cross-tenant schedule delete by-workflow {} for tenantId={}, orgId={}",
                    workflowId, tenantId, orgId);
            return ResponseEntity.notFound().build();
        }
        if (deleted < rows.size()) {
            logger.warn("[SCOPE] Schedule cleanup for workflow {} matched only {}/{} rows for caller tenantId={}, orgId={}",
                    workflowId, deleted, rows.size(), tenantId, orgId);
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * Bulk-archive every non-ARCHIVED schedule row for a workflow.
     *
     * <p>Used by orchestrator's {@code WorkflowManagementService.deleteWorkflow} cascade.
     * Pre-v5 this was a hard DELETE that bypassed the audit log. v5 routes through
     * {@code TriggerLifecycleManager.archiveSchedule} so the workflow-deletion event
     * is recorded as a state transition with reason {@code WORKFLOW_DELETED} and
     * source {@code DELETION}; the row + execution_count are preserved for forensic
     * / replay purposes. Archived rows are not currently auto-reaped - manual
     * cleanup or a future archived-row reaper handles eventual GC.
     *
     * <p>ARCHIVED rows are skipped (already inert). Returns transition count.
     */
    @PutMapping("/schedules/by-workflow/{workflowId}/archive")
    @Transactional
    public ResponseEntity<Integer> archiveSchedulesByWorkflow(
            @PathVariable("workflowId") UUID workflowId,
            @RequestParam(value = "reason", required = false) String reason) {
        String resolvedReason = reason != null && !reason.isBlank()
                ? reason
                : TriggerLifecycleManager.Reason.WORKFLOW_DELETED;
        List<ScheduledExecutionEntity> schedules = scheduleRepository.findByWorkflowId(workflowId);
        int archivedCount = 0;
        for (ScheduledExecutionEntity schedule : schedules) {
            if (schedule.getState() != TriggerState.ARCHIVED) {
                triggerLifecycleManager.archiveSchedule(schedule.getId(), resolvedReason,
                        TriggerLifecycleManager.Source.DELETION);
                archivedCount++;
            }
        }
        return ResponseEntity.ok(archivedCount);
    }

    @PutMapping("/standalone-webhooks/by-workflow/{workflowId}/archive")
    @Transactional
    public ResponseEntity<Integer> archiveWebhooksByWorkflow(
            @PathVariable("workflowId") UUID workflowId,
            @RequestParam(value = "reason", required = false) String reason) {
        String resolvedReason = reason != null && !reason.isBlank()
                ? reason
                : TriggerLifecycleManager.Reason.WORKFLOW_DELETED;
        List<StandaloneWebhookEntity> webhooks = webhookRepository.findByWorkflowId(workflowId);
        int archivedCount = 0;
        for (StandaloneWebhookEntity w : webhooks) {
            if (w.getState() != TriggerState.ARCHIVED) {
                triggerLifecycleManager.archiveWebhook(w.getId(), resolvedReason,
                        TriggerLifecycleManager.Source.DELETION);
                archivedCount++;
            }
        }
        return ResponseEntity.ok(archivedCount);
    }

    @PutMapping("/standalone-chat-endpoints/by-workflow/{workflowId}/archive")
    @Transactional
    public ResponseEntity<Integer> archiveChatEndpointsByWorkflow(
            @PathVariable("workflowId") UUID workflowId,
            @RequestParam(value = "reason", required = false) String reason) {
        String resolvedReason = reason != null && !reason.isBlank()
                ? reason
                : TriggerLifecycleManager.Reason.WORKFLOW_DELETED;
        List<StandaloneChatEndpointEntity> chats = chatEndpointRepository.findByWorkflowId(workflowId);
        int archivedCount = 0;
        for (StandaloneChatEndpointEntity c : chats) {
            if (c.getState() != TriggerState.ARCHIVED) {
                triggerLifecycleManager.archiveChat(c.getId(), resolvedReason,
                        TriggerLifecycleManager.Source.DELETION);
                archivedCount++;
            }
        }
        return ResponseEntity.ok(archivedCount);
    }

    @PutMapping("/standalone-form-endpoints/by-workflow/{workflowId}/archive")
    @Transactional
    public ResponseEntity<Integer> archiveFormEndpointsByWorkflow(
            @PathVariable("workflowId") UUID workflowId,
            @RequestParam(value = "reason", required = false) String reason) {
        String resolvedReason = reason != null && !reason.isBlank()
                ? reason
                : TriggerLifecycleManager.Reason.WORKFLOW_DELETED;
        List<StandaloneFormEndpointEntity> forms = formEndpointRepository.findByWorkflowId(workflowId);
        int archivedCount = 0;
        for (StandaloneFormEndpointEntity f : forms) {
            if (f.getState() != TriggerState.ARCHIVED) {
                triggerLifecycleManager.archiveForm(f.getId(), resolvedReason,
                        TriggerLifecycleManager.Source.DELETION);
                archivedCount++;
            }
        }
        return ResponseEntity.ok(archivedCount);
    }

    /**
     * Bulk-suspend every ACTIVE schedule row for a workflow. Mirror of
     * {@link #enableSchedulesByWorkflow} for the cancel/unpin path.
     *
     * <p>Used by orchestrator's cancel/pause cascade (design v3.5 §L3, extended
     * to all {@code WorkflowType}s on 2026-05-13 to fix the paused-zombie bug):
     * when a workflow's run is cancelled or paused, the triggers must also stop
     * firing. This endpoint walks the schedule rows and routes each through
     * {@link TriggerLifecycleManager}'s {@code suspendSchedule}, so the audit
     * trail records each transition with {@code reason} and {@code source}.
     *
     * <p><b>ARCHIVED + SUSPENDED rows are skipped silently</b> (already inert,
     * no double-suspend). Only {@code state == ACTIVE} rows transition to
     * {@code SUSPENDED_NO_RUN} with the supplied reason. This pre-filter is
     * <i>load-bearing for the precedence guard</i>: a row already at e.g.
     * {@code SUSPENDED_NO_RUN/WORKFLOW_DELETED} (precedence 100) would
     * otherwise enter {@code transitionSchedule} and have its reason gated by
     * {@code Reason.canReplace} - but it never reaches that path because the
     * {@code state == ACTIVE} filter excludes it here. Net: stronger reasons
     * are preserved by construction, not by relying on the precedence guard
     * alone. Returns the count of rows actually transitioned.
     *
     * @param reason the suspension reason persisted to {@code last_disabled_reason}
     *               (one of the {@link TriggerLifecycleManager.Reason} constants).
     *               Defaults to {@code USER_DISABLED} when omitted.
     * @param source the originating source (one of the
     *               {@link TriggerLifecycleManager.Source} enum values).
     *               Defaults to {@code ADMIN} when omitted or unparseable.
     *               Recorded in the audit log so forensic queries can
     *               distinguish e.g. workflow-deletion-cascade
     *               ({@code DELETION}) from cancel/pause cascade
     *               ({@code RUN_TERMINATION}) from admin REST suspension
     *               ({@code ADMIN}).
     * @return number of schedule rows transitioned ACTIVE → SUSPENDED.
     */
    @PutMapping("/schedules/by-workflow/{workflowId}/suspend")
    @Transactional
    public ResponseEntity<Integer> suspendSchedulesByWorkflow(
            @PathVariable("workflowId") UUID workflowId,
            @RequestParam(value = "reason", required = false) String reason,
            @RequestParam(value = "source", required = false) String source) {
        String resolvedReason = reason != null && !reason.isBlank()
                ? reason
                : TriggerLifecycleManager.Reason.USER_DISABLED;
        TriggerLifecycleManager.Source resolvedSource = TriggerLifecycleManager.Source.ADMIN;
        if (source != null && !source.isBlank()) {
            try {
                resolvedSource = TriggerLifecycleManager.Source.valueOf(source.trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                // Unknown source string - reject at the boundary rather than silently
                // corrupting the audit log with ADMIN. Per CLAUDE.md "validation at
                // system boundaries", explicit-but-invalid input gets 400.
                logger.warn("[suspendSchedulesByWorkflow] Unknown source '{}' for workflow {} - rejecting",
                        source, workflowId);
                return ResponseEntity.badRequest().build();
            }
        }
        List<ScheduledExecutionEntity> schedules = scheduleRepository.findByWorkflowId(workflowId);
        int suspendedCount = 0;
        for (ScheduledExecutionEntity schedule : schedules) {
            if (schedule.getState() == TriggerState.ACTIVE) {
                triggerLifecycleManager.suspendSchedule(schedule.getId(), resolvedReason, resolvedSource);
                suspendedCount++;
            }
        }
        return ResponseEntity.ok(suspendedCount);
    }

    @PostMapping("/schedules/create")
    @Transactional
    public ResponseEntity<?> createOrUpdateScheduleInternal(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-Organization-ID", required = false) String headerOrgId) {
        UUID workflowId = UUID.fromString(body.get("workflowId").toString());
        String triggerId = body.get("triggerId").toString();
        String tenantId = body.get("tenantId").toString();
        // PR22 R2 - workspace identity. Body field wins over header (explicit caller intent).
        String bodyOrgId = body.get("organizationId") != null ? body.get("organizationId").toString() : null;
        String organizationId = (bodyOrgId != null && !bodyOrgId.isBlank()) ? bodyOrgId : headerOrgId;
        if (!hasText(organizationId)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "X-Organization-ID or body.organizationId is required",
                    "success", false,
                    "errorCode", "MISSING_ORGANIZATION_ID"));
        }
        String cron = body.get("cron").toString();
        String timezone = body.getOrDefault("timezone", "UTC").toString();
        Integer maxExecutions = body.get("maxExecutions") != null
                ? ((Number) body.get("maxExecutions")).intValue() : null;
        boolean enabled = body.get("enabled") == null || Boolean.parseBoolean(body.get("enabled").toString());
        Integer expiresInDays = body.get("expiresInDays") != null
                ? ((Number) body.get("expiresInDays")).intValue() : null;

        // Strict-validation gate - defense in depth. Without this, an invalid cron
        // (e.g. `*/120 * * * *` collapsing to "minute 0" silently) would have
        // `getNextExecution` return null and the row would persist with the
        // `+60s` fallback below, dispatching forever at the wrong cadence.
        // Mirrors the public-path validator at ScheduleController.createOrUpdateSchedule:98.
        if (!cronParser.isValid(cron)) {
            logger.warn("[Schedule] Rejected invalid cron '{}' on /schedules/create (workflow={}, trigger={})",
                    cron, workflowId, triggerId);
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Invalid cron expression",
                    "cron", cron));
        }

        // Compute real next execution from cron expression
        Instant computedNext = cronParser.getNextExecution(cron, timezone);
        if (computedNext == null) {
            computedNext = Instant.now().plusSeconds(60); // Fallback if cron parsing fails
            logger.warn("[Schedule] Failed to compute next cron execution for cron '{}', using fallback", cron);
        }
        final Instant nextExecution = computedNext;

        // Find existing schedule - use list query to survive pre-migration duplicates
        List<ScheduledExecutionEntity> existing = scheduleRepository.findAllByWorkflowIdAndTriggerId(workflowId, triggerId);
        ScheduledExecutionEntity schedule;
        if (existing.isEmpty()) {
            schedule = new ScheduledExecutionEntity(workflowId, triggerId, tenantId,
                    cron, timezone, nextExecution);
            // PR22 R2 - stamp workspace at create time so the fire daemon scope-guard works.
            schedule.setOrganizationId(organizationId);
        } else {
            // Keep the one with most executions, delete the rest
            existing.sort(Comparator.comparingInt(ScheduledExecutionEntity::getExecutionCount).reversed());
            schedule = existing.get(0);
            // Defensive backfill: legacy rows created by pre-V218 sync paths landed
            // NULL-org and stayed orphaned even when the workflow itself was later
            // tagged. On any UPDATE we now upgrade the row to match the caller's
            // workspace - same shape as the public ScheduleController fix.
            if (schedule.getOrganizationId() == null && organizationId != null
                    && !organizationId.isBlank()) {
                logger.info("[Schedule] Backfilling NULL organization_id on schedule {} (workflow={} trigger={}) → {}",
                        schedule.getId(), workflowId, triggerId, organizationId);
                schedule.setOrganizationId(organizationId);
            }
            if (existing.size() > 1) {
                logger.warn("[Schedule] Found {} duplicates for workflow={} trigger={}, cleaning up",
                        existing.size() - 1, workflowId, triggerId);
                scheduleRepository.deleteAll(existing.subList(1, existing.size()));
            }

            // P0a: refuse to UPDATE an ARCHIVED row's `enabled` flag. Without this guard,
            // the `setEnabled(true)` below would silently flip an archived row back to
            // enabled=true while leaving state=ARCHIVED - drift that the dispatcher's
            // legacy-boolean-based filter could pick up (P0b in the same audit). The
            // RC2 ARCHIVED-permanence contract is enforced HERE too: archived rows
            // are not re-armed, even via the upsert door. Cron config (cron, timezone,
            // maxExecutions, name) updates also skipped - modifying an archived row
            // would muddy forensics. Caller paths (sync from pinned plan) treat this
            // as a no-op; the row stays archived until the documented unarchive path.
            if (schedule.getState() == TriggerState.ARCHIVED) {
                logger.warn("[Schedule] Refused upsert on ARCHIVED schedule {} (workflow={}, trigger={}, reason={}) - " +
                        "ARCHIVED is permanent; row left untouched.",
                        schedule.getId(), workflowId, triggerId, schedule.getLastDisabledReason());
                return ResponseEntity.ok(toScheduleDto(schedule));
            }
        }

        schedule.setCronExpression(cron);
        schedule.setTimezone(timezone);
        schedule.setMaxExecutions(maxExecutions);
        schedule.setEnabled(enabled);
        schedule.setNextExecutionAt(nextExecution);
        schedule.setUpdatedAt(Instant.now());

        // Name and workflowName for display in Settings → Triggers
        if (body.get("name") != null) {
            schedule.setName(body.get("name").toString());
        }
        if (body.get("workflowName") != null) {
            schedule.setWorkflowName(body.get("workflowName").toString());
        }

        if (expiresInDays != null && expiresInDays > 0) {
            schedule.setExpiresAt(Instant.now().plus(expiresInDays, java.time.temporal.ChronoUnit.DAYS));
        }

        scheduleRepository.save(schedule);

        // Re-activate a trigger that was previously removed from the plan and is now being
        // re-added. The config update above only refreshes cron/timezone/enabled; it does NOT
        // transition the lifecycle state, so the row stays SUSPENDED_NO_RUN and never dispatches
        // (findDueForUpdate filters state='ACTIVE'). Previously the blanket
        // enableSchedulesByWorkflow sweep revived it - but that sweep now deliberately skips
        // PLAN_TRIGGER_REMOVED rows to stop orphan resurrection, so the legitimate re-add path
        // must arm explicitly here. Scoped to PLAN_TRIGGER_REMOVED: do not silently revive
        // USER_DISABLED / WORKFLOW_UNPINNED rows through an upsert.
        if (enabled && schedule.getState() != TriggerState.ARCHIVED
                && TriggerLifecycleManager.Reason.PLAN_TRIGGER_REMOVED.equals(schedule.getLastDisabledReason())) {
            triggerLifecycleManager.armSchedule(schedule.getId(), TriggerLifecycleManager.Source.SYNC);
            schedule = scheduleRepository.findById(schedule.getId()).orElse(schedule);
        }
        return ResponseEntity.ok(toScheduleDto(schedule));
    }

    @GetMapping("/schedules/by-tenant/{tenantId}")
    public ResponseEntity<List<ScheduledExecutionDto>> getSchedulesByTenant(
            @PathVariable("tenantId") String tenantId,
            @RequestHeader("X-Organization-ID") String organizationId) {
        // Strict-org isolation (post-V261 every schedule row carries a non-null org).
        List<ScheduledExecutionEntity> entities =
                scheduleRepository.findAllByOrganizationIdStrict(organizationId);
        return ResponseEntity.ok(entities.stream().map(this::toScheduleDto).toList());
    }

    /**
     * V220 follow-up - explicit org-scoped read for callers that hold an
     * orgId in hand and don't want to rely on the implicit
     * {@code X-Organization-ID} header forwarder. Used by
     * {@code ActiveAutomationsService} on the home dashboard so teammate-owned
     * schedules (tenant_id == teammate_user_id) surface in the shared
     * workspace's "active automations" strip.
     *
     * <p>Strict-org predicate: returns every row tagged with the given
     * {@code organization_id} regardless of {@code tenant_id}. Personal-scope
     * rows are excluded - callers wanting those use the {@code /by-tenant}
     * endpoint without the org header.
     */
    @GetMapping("/schedules/by-organization/{orgId}")
    public ResponseEntity<List<ScheduledExecutionDto>> getSchedulesByOrganization(
            @PathVariable("orgId") String orgId) {
        List<ScheduledExecutionEntity> entities =
                scheduleRepository.findAllByOrganizationIdStrict(orgId);
        return ResponseEntity.ok(entities.stream().map(this::toScheduleDto).toList());
    }

    @GetMapping("/schedules/count-by-tenant/{tenantId}")
    public ResponseEntity<Long> countSchedulesByTenant(
            @PathVariable("tenantId") String tenantId,
            @RequestHeader("X-Organization-ID") String organizationId) {
        // Quota-safe count: excludes orphans (workflow_id IS NULL AND
        // agent_entity_id IS NULL). Abandoned builder drafts no longer burn
        // the user's slots; the reaper cleans them up after 24h.
        // Strict-org count post-V261.
        long count = scheduleRepository.countActiveByOrganizationIdStrict(organizationId);
        return ResponseEntity.ok(count);
    }

    @PutMapping("/schedules/{scheduleId}/toggle")
    @Transactional
    public ResponseEntity<ScheduledExecutionDto> toggleSchedule(
            @PathVariable("scheduleId") UUID scheduleId,
            @RequestHeader("X-Organization-ID") String organizationId,
            @RequestBody Map<String, Boolean> body) {
        ScheduledExecutionEntity schedule = scheduleRepository
                .findByIdAndOrganizationIdStrict(scheduleId, organizationId)
                .orElse(null);
        if (schedule == null) return ResponseEntity.notFound().build();
        // Round-5 audit (F2): refuse to flip `enabled=true` on an ARCHIVED row.
        // Without this guard, an admin clicking "toggle on" in the Settings UI
        // would silently create the same drift class P0a closed (state=ARCHIVED,
        // enabled=true) - a row invisible to the dispatcher (P0b state filter)
        // but visible to /schedules listings as "enabled". Disable (false) is
        // accepted on any state - it's a no-op for ARCHIVED but harmless.
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        if (enabled && schedule.getState() == TriggerState.ARCHIVED) {
            logger.warn("[Schedule] Refused toggle-on of ARCHIVED schedule {} (reason={}) - " +
                    "ARCHIVED is permanent; route through admin unarchive if recovery is intentional.",
                    scheduleId, schedule.getLastDisabledReason());
            return ResponseEntity.ok(toScheduleDto(schedule));
        }
        if (enabled) {
            triggerLifecycleManager.armSchedule(scheduleId, TriggerLifecycleManager.Source.ADMIN);
            schedule = scheduleRepository.findById(scheduleId).orElse(schedule);
            Instant nextExecution = cronParser.getNextExecution(schedule.getCronExpression(), schedule.getTimezone());
            if (nextExecution != null) {
                schedule.setNextExecutionAt(nextExecution);
                schedule.setUpdatedAt(Instant.now());
                scheduleRepository.save(schedule);
            }
        } else if (schedule.getState() != TriggerState.ARCHIVED) {
            triggerLifecycleManager.suspendSchedule(scheduleId,
                    TriggerLifecycleManager.Reason.USER_DISABLED,
                    TriggerLifecycleManager.Source.ADMIN);
            schedule = scheduleRepository.findById(scheduleId).orElse(schedule);
        }
        return ResponseEntity.ok(toScheduleDto(schedule));
    }

    /**
     * Archive a single schedule row by id (replaces the prior hard-delete path).
     *
     * <p>Routes through {@code TriggerLifecycleManager.archiveSchedule} so the
     * transition is appended to {@code trigger_state_audit_log} with the
     * caller-supplied reason (default {@code USER_DELETED}). Row + execution_count
     * survive indefinitely for forensic / replay purposes. Archived rows are not
     * auto-reaped today - manual cleanup or a future ARCHIVED-row reaper handles GC.
     *
     * <p>ARCHIVED rows are a no-op (state is permanent). Unknown ids → 404.
     */
    @PutMapping("/schedules/{scheduleId}/archive")
    @Transactional
    public ResponseEntity<Void> archiveScheduleById(
            @PathVariable("scheduleId") UUID scheduleId,
            @RequestHeader("X-Organization-ID") String organizationId,
            @RequestParam(value = "reason", required = false) String reason) {
        if (scheduleRepository.findByIdAndOrganizationIdStrict(scheduleId, organizationId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        String resolvedReason = reason != null && !reason.isBlank()
                ? reason
                : TriggerLifecycleManager.Reason.USER_DELETED;
        triggerLifecycleManager.archiveSchedule(scheduleId, resolvedReason,
                TriggerLifecycleManager.Source.ADMIN);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/schedules/cleanup-expired")
    @Transactional
    public ResponseEntity<Integer> cleanupExpiredSchedules() {
        // RC10: route max-executions-reached schedules through archiveSchedule
        // (state=ARCHIVED, reason=MAX_EXEC_REACHED) instead of the prior bulk
        // UPDATE that only flipped `enabled=false`. The bulk update created
        // drift rows at state=ACTIVE,enabled=false that were silently armed
        // back by any reactivate (the boolean `enabled` flipped to true,
        // but `executionCount >= maxExecutions` still held, so the row passed
        // arm but skipped dispatch - invisible-but-not-firing).
        //
        // Routing through archiveSchedule + the ARCHIVED-refusal guard on
        // armSchedule (RC2) closes the loop: archived rows stay archived,
        // any future arm attempt logs a WARN with the reason. Manual recovery
        // requires the documented admin unarchive path.
        int deleted = scheduleRepository.deleteExpired(Instant.now());
        List<ScheduledExecutionEntity> completed = scheduleRepository.findCompletedActiveSchedules();
        int archived = 0;
        for (ScheduledExecutionEntity s : completed) {
            triggerLifecycleManager.archiveSchedule(s.getId(),
                    TriggerLifecycleManager.Reason.MAX_EXEC_REACHED,
                    TriggerLifecycleManager.Source.QUOTA);
            archived++;
        }
        return ResponseEntity.ok(deleted + archived);
    }

    /**
     * Validate a cron expression and return its description + the next 3 firings.
     *
     * <p>Public via gateway through orchestrator's
     * {@code POST /api/schedules/validate-cron}, which proxies here via
     * {@code TriggerClient.validateCron}. Lives on the internal controller because
     * the validation has no per-tenant state and no workflowId in its semantics.
     *
     * <p>Response shape:
     * <pre>{@code
     * {
     *   "valid": boolean,
     *   "description": "Every 2 hours",          // only when valid
     *   "nextExecutions": [                       // up to 3 ISO-8601 strings, only when valid
     *     "2026-05-15T20:00:00Z",
     *     "2026-05-15T22:00:00Z",
     *     "2026-05-16T00:00:00Z"
     *   ]
     * }
     * }</pre>
     */
    @PostMapping("/schedules/validate-cron")
    public ResponseEntity<Map<String, Object>> validateCron(@RequestBody Map<String, String> body) {
        String cron = body.get("cron");
        String timezone = body.getOrDefault("timezone", "UTC");

        boolean valid = cronParser.isValid(cron);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", valid);

        if (valid) {
            result.put("description", cronParser.getDescription(cron));
            List<Instant> nextExecutions = cronParser.getNextExecutions(cron, timezone, 3);
            result.put("nextExecutions", nextExecutions.stream().map(Instant::toString).toList());
        }
        return ResponseEntity.ok(result);
    }

    // ========== Standalone Schedule Operations ==========

    @PostMapping("/schedules/standalone")
    public ResponseEntity<ScheduledExecutionDto> createStandaloneSchedule(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader("X-Organization-ID") String organizationId,
            @RequestHeader(value = "X-User-Plan", required = false) String userPlan,
            @RequestBody StandaloneScheduleRequest request) {
        try {
            // PR22 R2 - workspace identity threaded to the org-aware service overload.
            ScheduledExecutionDto dto = standaloneScheduleService.create(tenantId, organizationId, userPlan, request);
            return ResponseEntity.ok(dto);
        } catch (IllegalStateException | IllegalArgumentException e) {
            logger.warn("Failed to create standalone schedule for tenant {}: {}", tenantId, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/schedules/standalone/{id}")
    public ResponseEntity<ScheduledExecutionDto> getStandaloneSchedule(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader("X-Organization-ID") String organizationId,
            @PathVariable("id") UUID id) {
        try {
            return ResponseEntity.ok(standaloneScheduleService.getById(tenantId, organizationId, id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PutMapping("/schedules/standalone/{id}")
    public ResponseEntity<ScheduledExecutionDto> updateStandaloneSchedule(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader("X-Organization-ID") String organizationId,
            @PathVariable("id") UUID id,
            @RequestBody StandaloneScheduleRequest request) {
        try {
            return ResponseEntity.ok(standaloneScheduleService.update(tenantId, organizationId, id, request));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // v5: PUT /schedules/standalone/{id}/workflow endpoint removed. Was used
    // by ScheduleSyncService.updateScheduleWorkflowReferenceStrict before the
    // F4 PUB-HIJACK fix; that call site is gone, no other product caller exists.
    // Schedule rows are now created with their (workflow_id, trigger_id) set
    // at insert time and protected by V206's immutable-workflow-id trigger.

    // ========== Chat Endpoint Operations ==========

    @GetMapping("/chat-endpoints")
    public ResponseEntity<List<StandaloneChatEndpointDto>> getChatEndpoints(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader("X-Organization-ID") String organizationId) {
        return ResponseEntity.ok(chatEndpointService.getAll(tenantId, organizationId));
    }

    @PostMapping("/chat-endpoints")
    public ResponseEntity<StandaloneChatEndpointDto> createChatEndpoint(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader("X-Organization-ID") String organizationId,
            @RequestHeader(value = "X-User-Plan", required = false) String userPlan,
            @RequestBody StandaloneChatEndpointRequest request) {
        try {
            return ResponseEntity.ok(chatEndpointService.create(tenantId, organizationId, userPlan, request));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).build();
        }
    }

    @GetMapping("/chat-endpoints/{id}")
    public ResponseEntity<StandaloneChatEndpointDto> getChatEndpointById(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader("X-Organization-ID") String organizationId,
            @PathVariable("id") UUID id) {
        try {
            return ResponseEntity.ok(chatEndpointService.getById(tenantId, organizationId, id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PutMapping("/chat-endpoints/{id}")
    public ResponseEntity<StandaloneChatEndpointDto> updateChatEndpoint(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader("X-Organization-ID") String organizationId,
            @PathVariable("id") UUID id,
            @RequestBody StandaloneChatEndpointRequest request) {
        try {
            return ResponseEntity.ok(chatEndpointService.update(tenantId, organizationId, id, request));
        } catch (WorkflowReferenceImmutableException e) {
            return ResponseEntity.badRequest().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/chat-endpoints/{id}")
    public ResponseEntity<Void> deleteChatEndpoint(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader("X-Organization-ID") String organizationId,
            @PathVariable("id") UUID id) {
        try {
            chatEndpointService.delete(tenantId, organizationId, id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/chat-endpoints/{id}/regenerate-token")
    public ResponseEntity<StandaloneChatEndpointDto> regenerateChatToken(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader("X-Organization-ID") String organizationId,
            @PathVariable("id") UUID id) {
        try {
            return ResponseEntity.ok(chatEndpointService.regenerateToken(tenantId, organizationId, id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/chat-endpoints/{id}/logs")
    public ResponseEntity<Page<ChatEndpointAccessLogDto>> getChatAccessLogs(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader("X-Organization-ID") String organizationId,
            @PathVariable("id") UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            return ResponseEntity.ok(chatEndpointService.getAccessLogs(tenantId, organizationId, id, page, size));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/chat-endpoints/config")
    public ResponseEntity<EndpointConfigDto> getChatEndpointConfig(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader("X-Organization-ID") String organizationId,
            @RequestHeader(value = "X-User-Plan", required = false) String userPlan) {
        return ResponseEntity.ok(chatEndpointService.getConfig(tenantId, organizationId, userPlan));
    }

    @PatchMapping("/chat-endpoints/{id}/workflow")
    public ResponseEntity<StandaloneChatEndpointDto> updateChatWorkflowReference(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader("X-Organization-ID") String organizationId,
            @PathVariable("id") UUID id,
            @RequestBody Map<String, String> body) {
        try {
            UUID workflowId = body.get("workflowId") != null ? UUID.fromString(body.get("workflowId")) : null;
            String workflowName = body.get("workflowName");
            return ResponseEntity.ok(chatEndpointService.updateWorkflowReference(tenantId, organizationId, id, workflowId, workflowName));
        } catch (WorkflowReferenceImmutableException e) {
            return ResponseEntity.badRequest().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Adopt a standalone schedule onto a workflow at pin time (restored after the F4
     * PUB-HIJACK fix removed it; mirrors the chat/form workflow-reference endpoints but
     * also carries {@code triggerId}, which a schedule needs to fire). Guarded NULL→value
     * only in the service + V206 at the DB.
     */
    @PatchMapping("/schedules/standalone/{id}/workflow")
    public ResponseEntity<ScheduledExecutionDto> updateScheduleWorkflowReference(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader("X-Organization-ID") String organizationId,
            @PathVariable("id") UUID id,
            @RequestBody Map<String, String> body) {
        try {
            UUID workflowId = body.get("workflowId") != null ? UUID.fromString(body.get("workflowId")) : null;
            String triggerId = body.get("triggerId");
            String workflowName = body.get("workflowName");
            ScheduledExecutionDto dto = standaloneScheduleService.updateWorkflowReference(
                    tenantId, organizationId, id, workflowId, triggerId, workflowName);
            // Re-add re-activation (standalone path): adopting a schedule onto a workflow trigger
            // means that trigger is back in the plan. Mirror createOrUpdateScheduleInternal so the
            // bulk re-arm's PLAN_TRIGGER_REMOVED skip does not strand a re-added standalone schedule
            // (updateWorkflowReference only sets workflow_id/trigger_id; it does NOT transition state).
            Optional<ScheduledExecutionEntity> adopted = scheduleRepository.findById(dto.getId());
            if (adopted.isPresent() && adopted.get().getState() != TriggerState.ARCHIVED
                    && TriggerLifecycleManager.Reason.PLAN_TRIGGER_REMOVED.equals(adopted.get().getLastDisabledReason())) {
                triggerLifecycleManager.armSchedule(adopted.get().getId(), TriggerLifecycleManager.Source.SYNC);
                // Return the post-arm state (re-fetch), consistent with createOrUpdateScheduleInternal.
                dto = scheduleRepository.findById(adopted.get().getId()).map(this::toScheduleDto).orElse(dto);
            }
            return ResponseEntity.ok(dto);
        } catch (WorkflowReferenceImmutableException e) {
            return ResponseEntity.badRequest().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/chat-endpoints/by-token/{token}")
    public ResponseEntity<StandaloneChatEndpointDto> findChatEndpointByToken(
            @PathVariable("token") String token) {
        return chatEndpointService.findByToken(token)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/chat-endpoints/log-access")
    public ResponseEntity<Void> logChatAccess(@RequestBody Map<String, Object> body) {
        UUID chatEndpointId = UUID.fromString(body.get("chatEndpointId").toString());
        String sessionId = (String) body.get("sessionId");
        String conversationId = (String) body.get("conversationId");
        String action = (String) body.get("action");
        String ipAddress = (String) body.get("ipAddress");
        chatEndpointService.logAccess(chatEndpointId, sessionId, conversationId, action, ipAddress);
        return ResponseEntity.ok().build();
    }

    @PutMapping("/chat-endpoints/sync-trigger-id")
    @Transactional
    public ResponseEntity<Void> syncChatEndpointTriggerId(@RequestBody Map<String, Object> body) {
        UUID workflowId = UUID.fromString(body.get("workflowId").toString());
        String triggerId = body.get("triggerId") != null ? body.get("triggerId").toString() : null;
        chatEndpointService.syncTriggerId(workflowId, triggerId);
        return ResponseEntity.ok().build();
    }

    // ========== Form Endpoint Operations ==========

    @GetMapping("/form-endpoints")
    public ResponseEntity<List<StandaloneFormEndpointDto>> getFormEndpoints(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader("X-Organization-ID") String organizationId) {
        return ResponseEntity.ok(formEndpointService.getAll(tenantId, organizationId));
    }

    @PostMapping("/form-endpoints")
    public ResponseEntity<StandaloneFormEndpointDto> createFormEndpoint(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader("X-Organization-ID") String organizationId,
            @RequestHeader(value = "X-User-Plan", required = false) String userPlan,
            @RequestBody StandaloneFormEndpointRequest request) {
        try {
            return ResponseEntity.ok(formEndpointService.create(tenantId, organizationId, userPlan, request));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).build();
        }
    }

    @GetMapping("/form-endpoints/{id}")
    public ResponseEntity<StandaloneFormEndpointDto> getFormEndpointById(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader("X-Organization-ID") String organizationId,
            @PathVariable("id") UUID id) {
        try {
            return ResponseEntity.ok(formEndpointService.getById(tenantId, organizationId, id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PutMapping("/form-endpoints/{id}")
    public ResponseEntity<StandaloneFormEndpointDto> updateFormEndpoint(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader("X-Organization-ID") String organizationId,
            @PathVariable("id") UUID id,
            @RequestBody StandaloneFormEndpointRequest request) {
        try {
            return ResponseEntity.ok(formEndpointService.update(tenantId, organizationId, id, request));
        } catch (WorkflowReferenceImmutableException e) {
            return ResponseEntity.badRequest().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/form-endpoints/{id}")
    public ResponseEntity<Void> deleteFormEndpoint(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader("X-Organization-ID") String organizationId,
            @PathVariable("id") UUID id) {
        try {
            formEndpointService.delete(tenantId, organizationId, id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/form-endpoints/{id}/regenerate-token")
    public ResponseEntity<StandaloneFormEndpointDto> regenerateFormToken(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader("X-Organization-ID") String organizationId,
            @PathVariable("id") UUID id) {
        try {
            return ResponseEntity.ok(formEndpointService.regenerateToken(tenantId, organizationId, id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/form-endpoints/{id}/logs")
    public ResponseEntity<Page<FormSubmissionLogDto>> getFormSubmissionLogs(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader("X-Organization-ID") String organizationId,
            @PathVariable("id") UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            return ResponseEntity.ok(formEndpointService.getSubmissionLogs(tenantId, organizationId, id, page, size));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/form-endpoints/config")
    public ResponseEntity<EndpointConfigDto> getFormEndpointConfig(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader("X-Organization-ID") String organizationId,
            @RequestHeader(value = "X-User-Plan", required = false) String userPlan) {
        return ResponseEntity.ok(formEndpointService.getConfig(tenantId, organizationId, userPlan));
    }

    @PatchMapping("/form-endpoints/{id}/workflow")
    public ResponseEntity<StandaloneFormEndpointDto> updateFormWorkflowReference(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader("X-Organization-ID") String organizationId,
            @PathVariable("id") UUID id,
            @RequestBody Map<String, String> body) {
        try {
            UUID workflowId = body.get("workflowId") != null ? UUID.fromString(body.get("workflowId")) : null;
            String workflowName = body.get("workflowName");
            return ResponseEntity.ok(formEndpointService.updateWorkflowReference(tenantId, organizationId, id, workflowId, workflowName));
        } catch (WorkflowReferenceImmutableException e) {
            return ResponseEntity.badRequest().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/form-endpoints/by-token/{token}")
    public ResponseEntity<StandaloneFormEndpointDto> findFormEndpointByToken(
            @PathVariable("token") String token) {
        return formEndpointService.findByToken(token)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/form-endpoints/log-submission")
    public ResponseEntity<Void> logFormSubmission(@RequestBody Map<String, Object> body) {
        UUID formEndpointId = UUID.fromString(body.get("formEndpointId").toString());
        Map<String, Object> submissionData = (Map<String, Object>) body.get("submissionData");
        String status = (String) body.get("responseStatus");
        int workflowsTriggered = body.get("workflowsTriggered") != null
                ? ((Number) body.get("workflowsTriggered")).intValue() : 0;
        String ipAddress = (String) body.get("ipAddress");
        formEndpointService.logSubmission(formEndpointId, submissionData, status, workflowsTriggered, ipAddress);
        return ResponseEntity.ok().build();
    }

    @PutMapping("/form-endpoints/sync-trigger-id")
    @Transactional
    public ResponseEntity<Void> syncFormEndpointTriggerId(@RequestBody Map<String, Object> body) {
        UUID workflowId = UUID.fromString(body.get("workflowId").toString());
        String triggerId = body.get("triggerId") != null ? body.get("triggerId").toString() : null;
        formEndpointService.syncTriggerId(workflowId, triggerId);
        return ResponseEntity.ok().build();
    }

    // ========== Agent Schedule Operations ==========

    @PostMapping("/schedules/agent")
    @Transactional
    public ResponseEntity<?> createAgentSchedule(
            @RequestBody Map<String, Object> body,
            @RequestHeader("X-Organization-ID") String headerOrgId) {
        UUID agentEntityId = UUID.fromString(body.get("agentEntityId").toString());
        String tenantId = body.get("tenantId").toString();
        // PR22 R2 - workspace identity. Body field wins; falls back to header (PR16 forwarder).
        String bodyOrgId = body.get("organizationId") != null ? body.get("organizationId").toString() : null;
        String organizationId = (bodyOrgId != null && !bodyOrgId.isBlank()) ? bodyOrgId : headerOrgId;
        String cron = body.get("cronExpression").toString();
        String timezone = body.getOrDefault("timezone", "UTC").toString();
        Integer maxExecutions = body.get("maxExecutions") != null
                ? ((Number) body.get("maxExecutions")).intValue() : null;
        String name = body.get("name") != null ? body.get("name").toString() : null;

        // Strict-validation gate - same defense in depth as /schedules/create.
        // Agent schedule rows are upserted by agent-service via TriggerClient and a
        // bad cron here would dispatch the agent indefinitely at the wrong cadence.
        if (!cronParser.isValid(cron)) {
            logger.warn("[Schedule] Rejected invalid cron '{}' on /schedules/agent (agentEntity={})",
                    cron, agentEntityId);
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Invalid cron expression",
                    "cron", cron));
        }

        // Compute next execution from cron expression
        Instant computedNext = cronParser.getNextExecution(cron, timezone);
        if (computedNext == null) {
            computedNext = Instant.now().plusSeconds(60);
            logger.warn("[Schedule] Failed to compute next cron execution for agent schedule cron '{}', using fallback", cron);
        }

        // Upsert: find existing or create new
        Optional<ScheduledExecutionEntity> existingSchedule = findAgentScheduleInScope(agentEntityId, tenantId, organizationId);
        if (existingSchedule.isPresent() && existingSchedule.get().getState() == TriggerState.ARCHIVED) {
            ScheduledExecutionEntity archived = existingSchedule.get();
            logger.warn("[Schedule] Refused agent schedule upsert on ARCHIVED row {} (agentEntity={}, reason={})",
                    archived.getId(), agentEntityId, archived.getLastDisabledReason());
            return ResponseEntity.ok(toScheduleDto(archived));
        }
        ScheduledExecutionEntity schedule = existingSchedule.orElseGet(() -> {
                    ScheduledExecutionEntity entity = new ScheduledExecutionEntity(
                            null, null, tenantId, cron, timezone, Instant.now());
                    entity.setAgentEntityId(agentEntityId);
                    // PR22 R2 - stamp workspace at create time only (upsert preserves
                    // existing scope; flipping mid-life would orphan the row in its
                    // previous workspace's sidebar).
                    entity.setOrganizationId(organizationId);
                    return entity;
                });

        boolean isNewSchedule = existingSchedule.isEmpty();
        boolean enabled = body.get("enabled") == null || Boolean.parseBoolean(body.get("enabled").toString());

        schedule.setCronExpression(cron);
        schedule.setTimezone(timezone);
        schedule.setMaxExecutions(maxExecutions);
        // Merge, do NOT clobber: schedule_prompt + with_memory are preserved on an
        // existing row when the caller omits them. A partial edit (e.g. changing only
        // the cron cadence) must not wipe the prompt - prod incident 2026-06-14, where a
        // cron-only update blanked schedule_prompt and reset with_memory, so every fire
        // short-circuited on "no effective prompt → skipping". Explicit values (including
        // "" to clear the prompt, or an explicit withMemory) are still honoured; only an
        // absent/null field is treated as "no change". On create, fall back to null/false.
        if (body.containsKey("schedulePrompt") && body.get("schedulePrompt") != null) {
            schedule.setSchedulePrompt(body.get("schedulePrompt").toString());
        } else if (isNewSchedule) {
            schedule.setSchedulePrompt(null);
        }
        if (body.containsKey("withMemory") && body.get("withMemory") != null) {
            schedule.setWithMemory(Boolean.parseBoolean(body.get("withMemory").toString()));
        } else if (isNewSchedule) {
            schedule.setWithMemory(false);
        }
        schedule.setNextExecutionAt(computedNext);
        applyScheduleEnabledState(schedule, enabled);
        schedule.setUpdatedAt(Instant.now());
        if (name != null) {
            schedule.setName(name);
        }

        scheduleRepository.save(schedule);
        return ResponseEntity.ok(toScheduleDto(schedule));
    }

    @GetMapping("/schedules/by-agent/{agentEntityId}")
    public ResponseEntity<ScheduledExecutionDto> getAgentSchedule(
            @PathVariable("agentEntityId") UUID agentEntityId,
            @RequestParam("tenantId") String tenantId,
            @RequestHeader("X-Organization-ID") String orgId) {
        return findAgentScheduleInScope(agentEntityId, tenantId, orgId)
                .map(e -> ResponseEntity.ok(toScheduleDto(e)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Scope-aware delete of agent schedules. Audit 2026-05-16 round-3.
     */
    @DeleteMapping("/schedules/by-agent/{agentEntityId}")
    @Transactional
    public ResponseEntity<Void> deleteAgentSchedule(
            @PathVariable("agentEntityId") UUID agentEntityId,
            @RequestHeader(value = "X-User-ID", required = false) String tenantId,
            @RequestHeader("X-Organization-ID") String orgId) {
        if (orgId == null || orgId.isBlank()) {
            return ResponseEntity.status(401).build();
        }
        List<ScheduledExecutionEntity> rows = scheduleRepository.findByAgentEntityId(agentEntityId);
        if (rows.isEmpty()) return ResponseEntity.noContent().build();
        int deleted = 0;
        for (ScheduledExecutionEntity s : rows) {
            if (isRowInScope(s, tenantId, orgId)) {
                scheduleRepository.delete(s);
                deleted++;
            }
        }
        if (deleted == 0) {
            logger.warn("[SCOPE] Refused cross-tenant agent schedule delete agentEntityId={} for tenantId={}, orgId={}",
                    agentEntityId, tenantId, orgId);
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.noContent().build();
    }

    @RequestMapping(value = "/schedules/by-agent/{agentEntityId}/toggle", method = {RequestMethod.PATCH, RequestMethod.PUT})
    @Transactional
    public ResponseEntity<ScheduledExecutionDto> toggleAgentSchedule(
            @PathVariable("agentEntityId") UUID agentEntityId,
            @RequestParam("tenantId") String tenantId,
            @RequestParam("enabled") boolean enabled,
            @RequestHeader("X-Organization-ID") String orgId) {
        Optional<ScheduledExecutionEntity> opt = findAgentScheduleInScope(agentEntityId, tenantId, orgId);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();

        ScheduledExecutionEntity schedule = opt.get();
        if (enabled) {
            boolean armed = triggerLifecycleManager.armSchedule(schedule.getId(), TriggerLifecycleManager.Source.ADMIN);
            if (!armed) {
                return ResponseEntity.ok(toScheduleDto(schedule));
            }
            schedule = scheduleRepository.findById(schedule.getId()).orElse(schedule);
            Instant nextExecution = cronParser.getNextExecution(
                    schedule.getCronExpression(), schedule.getTimezone());
            if (nextExecution != null) {
                schedule.setNextExecutionAt(nextExecution);
                schedule.setUpdatedAt(Instant.now());
                scheduleRepository.save(schedule);
            }
        } else if (schedule.getState() != TriggerState.ARCHIVED) {
            triggerLifecycleManager.suspendSchedule(schedule.getId(),
                    TriggerLifecycleManager.Reason.USER_DISABLED,
                    TriggerLifecycleManager.Source.ADMIN);
            schedule = scheduleRepository.findById(schedule.getId()).orElse(schedule);
        }

        return ResponseEntity.ok(toScheduleDto(schedule));
    }

    private Optional<ScheduledExecutionEntity> findAgentScheduleInScope(
            UUID agentEntityId,
            String tenantId,
            String orgId) {
        if (!hasText(orgId)) {
            return Optional.empty();
        }
        List<ScheduledExecutionEntity> matches =
                scheduleRepository.findAllByAgentEntityIdAndOrganizationIdStrict(agentEntityId, orgId);
        if (matches.size() > 1) {
            logger.warn("[Schedule] Found {} agent schedules for agentEntityId={} tenantId={} orgId={}; using oldest row",
                    matches.size(), agentEntityId, tenantId, orgId);
        }
        return matches.stream().findFirst();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static void applyScheduleEnabledState(ScheduledExecutionEntity schedule, boolean enabled) {
        schedule.setEnabled(enabled);
        schedule.setIsActive(enabled);
        if (enabled) {
            schedule.setState(TriggerState.ACTIVE);
            schedule.setLastDisabledReason(null);
            schedule.setLastDisabledAt(null);
        } else {
            schedule.setState(TriggerState.SUSPENDED_NO_RUN);
            schedule.setLastDisabledReason(TriggerLifecycleManager.Reason.USER_DISABLED);
            schedule.setLastDisabledAt(Instant.now());
        }
    }

    // ========== DTO Mappers ==========

    private WebhookTokenDto toTokenDto(WebhookTokenEntity entity) {
        WebhookTokenDto dto = new WebhookTokenDto();
        dto.setId(entity.getId());
        dto.setWorkflowId(entity.getWorkflowId());
        dto.setTriggerId(entity.getTriggerId());
        dto.setToken(entity.getToken());
        // PR22c R3 - workspace tag for the pinned-webhook dispatch guard at
        // WebhookDispatchService:133. NULL for legacy/multi-DAG tokens or any
        // token created before the V215 deploy - dispatch is permissive on NULL.
        dto.setOrganizationId(entity.getOrganizationId());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }

    private StandaloneWebhookDto toStandaloneDto(StandaloneWebhookEntity entity) {
        StandaloneWebhookDto dto = new StandaloneWebhookDto();
        dto.setId(entity.getId());
        dto.setTenantId(entity.getTenantId());
        dto.setOrganizationId(entity.getOrganizationId());
        dto.setName(entity.getName());
        dto.setDescription(entity.getDescription());
        dto.setToken(entity.getToken());
        dto.setWebhookUrl(baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1) + "/webhook/" + entity.getToken()
                : baseUrl + "/webhook/" + entity.getToken());
        dto.setHttpMethod(entity.getHttpMethod());
        dto.setAuthType(entity.getAuthType());
        dto.setAuthConfig(entity.getAuthConfig());
        dto.setWorkflowId(entity.getWorkflowId());
        dto.setWorkflowName(entity.getWorkflowName());
        dto.setIsActive(entity.getIsActive());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        dto.setSourceNodeId(entity.getSourceNodeId());
        return dto;
    }

    private static int intBody(Map<String, Object> body, String key, int defaultValue) {
        Object value = body != null ? body.get(key) : null;
        if (value == null) return defaultValue;
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static Instant instantBody(Map<String, Object> body, String key, Instant defaultValue) {
        Object value = body != null ? body.get(key) : null;
        if (value == null || value.toString().isBlank()) return defaultValue;
        return Instant.parse(value.toString());
    }

    private ScheduledExecutionDto toScheduleDto(ScheduledExecutionEntity entity) {
        ScheduledExecutionDto dto = new ScheduledExecutionDto();
        dto.setId(entity.getId());
        dto.setWorkflowId(entity.getWorkflowId());
        dto.setTriggerId(entity.getTriggerId());
        dto.setTenantId(entity.getTenantId());
        // PR22 - surface workspace so the fire daemon can stamp the workflow_run.
        dto.setOrganizationId(entity.getOrganizationId());
        dto.setCronExpression(entity.getCronExpression());
        dto.setTimezone(entity.getTimezone());
        dto.setMaxExecutions(entity.getMaxExecutions());
        dto.setEnabled(entity.isEnabled());
        dto.setNextExecutionAt(entity.getNextExecutionAt());
        dto.setLastExecutionAt(entity.getLastExecutionAt());
        dto.setExecutionCount(entity.getExecutionCount());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        dto.setExpiresAt(entity.getExpiresAt());
        dto.setName(entity.getName());
        dto.setWorkflowName(entity.getWorkflowName());
        dto.setDescription(entity.getDescription());
        dto.setIsActive(entity.getIsActive());
        dto.setSourceNodeId(entity.getSourceNodeId());
        dto.setAgentEntityId(entity.getAgentEntityId());
        dto.setSchedulePrompt(entity.getSchedulePrompt());
        dto.setWithMemory(entity.getWithMemory());
        return dto;
    }

    /**
     * tolerant-scope: internal cascade DELETE called from the orchestrator's
     * workflow-deletion path. The orchestrator already validated the caller's
     * scope on the parent workflow before invoking this endpoint; the
     * tolerant predicate here lets the cascade clean up rows owned by the
     * workflow whether the parent was personal (org=NULL) or org-tagged,
     * matching the orchestrator-side authority. Public route variant in
     * {@code ScheduleController.isRowInScope} uses strict isolation via
     * {@link ScopeGuard#isInStrictScope}.
     */
    @TolerantScope(reason = "internal cascade DELETE - caller already gated upstream by orchestrator workflow-deletion path")
    private static boolean isRowInScope(ScheduledExecutionEntity s, String tenantId, String orgId) {
        if (s == null) return false;
        return ScopeGuard.isInOwnerOrOrgScope(
                tenantId, orgId, s.getTenantId(), s.getOrganizationId());
    }
}
