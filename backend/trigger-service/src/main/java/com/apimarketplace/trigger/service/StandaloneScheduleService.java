package com.apimarketplace.trigger.service;

import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.trigger.client.dto.ScheduledExecutionDto;
import com.apimarketplace.trigger.client.dto.StandaloneScheduleRequest;
import com.apimarketplace.trigger.domain.ScheduledExecutionEntity;
import com.apimarketplace.trigger.repository.ScheduledExecutionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for managing standalone schedules (not yet linked to a workflow).
 * Follows the same pattern as StandaloneWebhookService.
 *
 * <p>Post-V261: every row carries a non-null {@code organization_id}.
 * Personal-scope (IsNull) finders are gone; all entry points require
 * a non-blank {@code organizationId}.
 */
@Service
public class StandaloneScheduleService {

    private static final Logger logger = LoggerFactory.getLogger(StandaloneScheduleService.class);

    private final ScheduledExecutionRepository scheduleRepository;
    private final ScheduleCronParser cronParser;
    private final PlanLimitHelper planLimitHelper;

    public StandaloneScheduleService(ScheduledExecutionRepository scheduleRepository,
                                     ScheduleCronParser cronParser,
                                     PlanLimitHelper planLimitHelper) {
        this.scheduleRepository = scheduleRepository;
        this.cronParser = cronParser;
        this.planLimitHelper = planLimitHelper;
    }

    /** Create a schedule in the given organization workspace. */
    @Transactional
    public ScheduledExecutionDto create(String tenantId, String organizationId,
                                         String userPlan, StandaloneScheduleRequest request) {
        TenantResolver.requireOrgId(organizationId);
        // Dedup: if sourceNodeId provided, return existing record.
        if (request.sourceNodeId() != null && !request.sourceNodeId().isBlank()) {
            Optional<ScheduledExecutionEntity> existing =
                    scheduleRepository.findByOrganizationIdStrictAndSourceNodeId(organizationId, request.sourceNodeId());
            if (existing.isPresent()) {
                return toDto(existing.get());
            }
        }

        // Plan limit check - excludes orphans (never-linked drafts).
        // Reaped after 24h by StandaloneTriggerReaperService.
        long currentCount = scheduleRepository.countActiveByOrganizationIdStrict(organizationId);
        planLimitHelper.checkLimit(userPlan, currentCount);

        // Validate cron
        if (!cronParser.isValid(request.cron())) {
            throw new IllegalArgumentException("Invalid cron expression: " + request.cron());
        }

        Instant nextExecutionAt = cronParser.getNextExecution(request.cron(), request.timezone());
        if (nextExecutionAt == null) {
            nextExecutionAt = Instant.now().plusSeconds(60);
        }

        ScheduledExecutionEntity entity = new ScheduledExecutionEntity();
        entity.setTenantId(tenantId);
        // Workspace identity stamp. Fire daemon (findDueExecutions) returns this
        // column so the dispatcher can propagate it onto the created
        // workflow_run.organization_id - the schedule fires in the workspace
        // it was created in, never in the wrong scope.
        entity.setOrganizationId(organizationId);
        entity.setName(request.name());
        entity.setDescription(request.description());
        entity.setCronExpression(request.cron());
        entity.setTimezone(request.timezone());
        entity.setMaxExecutions(request.maxExecutions());
        entity.setEnabled(request.enabled());
        entity.setNextExecutionAt(nextExecutionAt);
        entity.setSourceNodeId(request.sourceNodeId());

        if (request.expiresInDays() != null && request.expiresInDays() > 0) {
            entity.setExpiresAt(Instant.now().plus(request.expiresInDays(), ChronoUnit.DAYS));
        }

        ScheduledExecutionEntity saved = scheduleRepository.save(entity);
        logger.info("Created standalone schedule '{}' for tenant {} org {} (id={})",
                saved.getName(), tenantId, organizationId, saved.getId());
        return toDto(saved);
    }

    /** Strict-isolation update. */
    @Transactional
    public ScheduledExecutionDto update(String tenantId, String organizationId, UUID id, StandaloneScheduleRequest request) {
        TenantResolver.requireOrgId(organizationId);
        ScheduledExecutionEntity entity = scheduleRepository.findByIdAndOrganizationIdStrict(id, organizationId)
                .orElseThrow(() -> new IllegalArgumentException("Schedule not found"));

        if (request.cron() != null && !request.cron().isBlank()) {
            if (!cronParser.isValid(request.cron())) {
                throw new IllegalArgumentException("Invalid cron expression: " + request.cron());
            }
            entity.setCronExpression(request.cron());
            Instant nextExecutionAt = cronParser.getNextExecution(request.cron(), request.timezone());
            if (nextExecutionAt != null) {
                entity.setNextExecutionAt(nextExecutionAt);
            }
        }
        if (request.timezone() != null) entity.setTimezone(request.timezone());
        if (request.name() != null) entity.setName(request.name());
        if (request.description() != null) entity.setDescription(request.description());
        if (request.maxExecutions() != null) entity.setMaxExecutions(request.maxExecutions());
        entity.setUpdatedAt(Instant.now());

        ScheduledExecutionEntity saved = scheduleRepository.save(entity);
        return toDto(saved);
    }

    /**
     * Adopt a standalone schedule onto a workflow at pin time - the schedule equivalent of
     * {@code StandaloneChatEndpointService.updateWorkflowReference}, restored after the F4
     * PUB-HIJACK fix removed schedule's adoption entirely (chat/webhook/form kept theirs).
     *
     * <p>Sets BOTH {@code workflow_id} AND {@code trigger_id} - a schedule fires only when
     * both are present ({@code findDueForUpdate} requires workflow_id; the executor requires
     * a non-blank triggerId). Without this, a standalone schedule created in the builder
     * (workflow_id NULL, trigger_id '') is never linked → never fires + not counted + reaped.
     *
     * <p><b>Hijack-safe.</b> The mutation is guarded NULL→value only ({@link
     * #assertWorkflowReferenceMutationAllowed}); once owned the row is immutable (also enforced
     * at the DB by the V206 trigger). The finder is org-scoped, so a cross-org claim is
     * impossible. Combined with PlanStripUtils (a cloned/acquired plan never carries the
     * source's {@code scheduleId}), the F4 clone-hijack class stays closed.
     */
    @Transactional
    public ScheduledExecutionDto updateWorkflowReference(String tenantId, String organizationId, UUID scheduleId,
                                                         UUID workflowId, String triggerId, String workflowName) {
        TenantResolver.requireOrgId(organizationId);
        ScheduledExecutionEntity entity = scheduleRepository.findByIdAndOrganizationIdStrict(scheduleId, organizationId)
                .orElseThrow(() -> new IllegalArgumentException("Schedule not found"));

        assertWorkflowReferenceMutationAllowed(entity.getWorkflowId(), workflowId);
        entity.setWorkflowId(workflowId);
        entity.setTriggerId(triggerId);
        if (workflowName != null) entity.setWorkflowName(workflowName);
        entity.setUpdatedAt(Instant.now());

        ScheduledExecutionEntity saved = scheduleRepository.save(entity);
        logger.info("Adopted standalone schedule {} onto workflow {} trigger '{}' (org {})",
                scheduleId, workflowId, triggerId, organizationId);
        return toDto(saved);
    }

    private void assertWorkflowReferenceMutationAllowed(UUID currentWorkflowId, UUID requestedWorkflowId) {
        if (currentWorkflowId != null && !java.util.Objects.equals(currentWorkflowId, requestedWorkflowId)) {
            throw new WorkflowReferenceImmutableException(
                    "workflowId is immutable on standalone schedules; delete and recreate the schedule to change it");
        }
    }

    /** Strict-isolation delete. */
    @Transactional
    public void delete(String tenantId, String organizationId, UUID id) {
        TenantResolver.requireOrgId(organizationId);
        ScheduledExecutionEntity entity = scheduleRepository.findByIdAndOrganizationIdStrict(id, organizationId)
                .orElseThrow(() -> new IllegalArgumentException("Schedule not found"));
        scheduleRepository.delete(entity);
    }

    /** Strict-isolation list. */
    public List<ScheduledExecutionDto> getAll(String tenantId, String organizationId) {
        TenantResolver.requireOrgId(organizationId);
        List<ScheduledExecutionEntity> rows = scheduleRepository.findAllByOrganizationIdStrict(organizationId);
        return rows.stream().map(this::toDto).toList();
    }

    /** Strict-isolation single fetch. */
    public ScheduledExecutionDto getById(String tenantId, String organizationId, UUID id) {
        TenantResolver.requireOrgId(organizationId);
        ScheduledExecutionEntity entity = scheduleRepository.findByIdAndOrganizationIdStrict(id, organizationId)
                .orElseThrow(() -> new IllegalArgumentException("Schedule not found"));
        return toDto(entity);
    }

    /** Strict-isolation toggle. */
    @Transactional
    public ScheduledExecutionDto toggle(String tenantId, String organizationId, UUID id, boolean enabled) {
        TenantResolver.requireOrgId(organizationId);
        ScheduledExecutionEntity entity = scheduleRepository.findByIdAndOrganizationIdStrict(id, organizationId)
                .orElseThrow(() -> new IllegalArgumentException("Schedule not found"));
        entity.setEnabled(enabled);
        if (enabled) {
            Instant nextExecutionAt = cronParser.getNextExecution(entity.getCronExpression(), entity.getTimezone());
            if (nextExecutionAt != null) {
                entity.setNextExecutionAt(nextExecutionAt);
            }
        }
        entity.setUpdatedAt(Instant.now());
        return toDto(scheduleRepository.save(entity));
    }


    public Map<String, Object> getConfig(String tenantId, String organizationId, String userPlan) {
        TenantResolver.requireOrgId(organizationId);
        long count = scheduleRepository.countActiveByOrganizationIdStrict(organizationId);
        int max = planLimitHelper.getMaxEndpoints(userPlan);
        return Map.of("currentCount", count, "maxPerUser", max);
    }

    private ScheduledExecutionDto toDto(ScheduledExecutionEntity entity) {
        ScheduledExecutionDto dto = new ScheduledExecutionDto();
        dto.setId(entity.getId());
        dto.setWorkflowId(entity.getWorkflowId());
        dto.setTriggerId(entity.getTriggerId());
        dto.setTenantId(entity.getTenantId());
        // Carry workspace identity through the service ↔ orchestrator boundary
        // so ScheduleExecutorService.executeWorkflowSchedule sees the right scope.
        dto.setOrganizationId(entity.getOrganizationId());
        dto.setName(entity.getName());
        dto.setWorkflowName(entity.getWorkflowName());
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
        dto.setDescription(entity.getDescription());
        dto.setIsActive(entity.getIsActive());
        dto.setSourceNodeId(entity.getSourceNodeId());
        dto.setAgentEntityId(entity.getAgentEntityId());
        dto.setSchedulePrompt(entity.getSchedulePrompt());
        dto.setWithMemory(entity.getWithMemory());
        return dto;
    }
}
