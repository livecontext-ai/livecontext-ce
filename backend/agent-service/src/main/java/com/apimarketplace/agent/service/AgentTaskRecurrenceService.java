package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.AgentEntity;
import com.apimarketplace.agent.domain.AgentTaskEntity;
import com.apimarketplace.agent.domain.AgentTaskRecurrenceEntity;
import com.apimarketplace.agent.dto.CreateRecurrenceRequest;
import com.apimarketplace.agent.dto.UpdateRecurrenceRequest;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.repository.AgentTaskRecurrenceRepository;
import com.apimarketplace.agent.repository.AgentTaskRepository;
import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.common.web.TenantResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * CRUD + tick logic for agent task recurrences.
 * <p>
 * Recurrences are cron-driven templates that periodically instantiate a new
 * {@link AgentTaskEntity}. On each tick the scheduler fetches all due
 * recurrences and delegates to {@link #fireOnce(AgentTaskRecurrenceEntity)}
 * which creates one task and advances {@code next_fire_at} forward -
 * <strong>fire-once-then-skip</strong> catch-up: missed intervals are not
 * backfilled.
 */
@Service
public class AgentTaskRecurrenceService {

    private static final Logger logger = LoggerFactory.getLogger(AgentTaskRecurrenceService.class);

    private final AgentTaskRecurrenceRepository recurrenceRepository;
    private final AgentTaskRepository taskRepository;
    private final AgentRepository agentRepository;

    // Field-injected (optional) so the scheduler-spawned tasks reach open task
    // boards live. Null in slim unit tests → publish skipped, never fails a fire.
    @Autowired(required = false)
    private TaskBoardPublisher taskBoardPublisher;

    public AgentTaskRecurrenceService(AgentTaskRecurrenceRepository recurrenceRepository,
                                       AgentTaskRepository taskRepository,
                                       AgentRepository agentRepository) {
        this.recurrenceRepository = recurrenceRepository;
        this.taskRepository = taskRepository;
        this.agentRepository = agentRepository;
    }

    /** Test seam: inject the optional publisher without a Spring context. */
    void setTaskBoardPublisher(TaskBoardPublisher taskBoardPublisher) {
        this.taskBoardPublisher = taskBoardPublisher;
    }

    // ========================================================================
    // CRUD
    // ========================================================================

    @Transactional
    public AgentTaskRecurrenceEntity create(String tenantId,
                                             UUID createdByAgentId,
                                             String createdByUserId,
                                             CreateRecurrenceRequest request) {
        Objects.requireNonNull(tenantId, "tenantId");
        if (isBlank(request.title())) throw new IllegalArgumentException("title is required");
        if (isBlank(request.instructions())) throw new IllegalArgumentException("instructions are required");
        if (isBlank(request.cronExpression())) throw new IllegalArgumentException("cronExpression is required");

        String timezone = request.timezone() == null || request.timezone().isBlank() ? "UTC" : request.timezone();
        ZoneId zone = parseZone(timezone);
        CronExpression cron = parseCron(request.cronExpression());
        String organizationId = TenantResolver.currentRequestOrganizationId();

        if (request.targetAgentId() != null) {
            Optional<AgentEntity> targetLookup = !isBlank(organizationId)
                    ? agentRepository.findByIdAndOrganizationIdStrict(request.targetAgentId(), organizationId)
                    : agentRepository.findById(request.targetAgentId());
            AgentEntity target = targetLookup
                    .orElseThrow(() -> new IllegalArgumentException(
                            "target agent not found: " + request.targetAgentId()));
            if (!ScopeGuard.isInStrictScope(tenantId, organizationId, target.getTenantId(), target.getOrganizationId())) {
                throw new IllegalArgumentException("target agent belongs to a different tenant");
            }
        }

        Instant next = computeNextFire(cron, zone, Instant.now());
        AgentTaskRecurrenceEntity entity = new AgentTaskRecurrenceEntity();
        entity.setTenantId(tenantId);
        // Audit 2026-05-17 round-3 - V217 added organization_id column, but the
        // create path forgot to stamp it. Pull from the current request via the
        // same shim AgentTaskService uses for assign/inbox/outbox.
        entity.setOrganizationId(organizationId);
        entity.setCreatedByAgentId(createdByAgentId);
        entity.setCreatedByUserId(createdByUserId);
        entity.setTargetAgentId(request.targetAgentId());
        entity.setTitle(truncate(request.title(), 500));
        entity.setInstructions(request.instructions());
        entity.setTaskContext(request.taskContext());
        entity.setPriority(normalizePriority(request.priority()));
        entity.setCronExpression(request.cronExpression());
        entity.setTimezone(timezone);
        entity.setEnabled(true);
        entity.setNextFireAt(next);

        AgentTaskRecurrenceEntity saved = recurrenceRepository.save(entity);
        logger.info("Recurrence {} created (cron='{}', tz={}, next={})",
                saved.getId(), request.cronExpression(), timezone, next);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<AgentTaskRecurrenceEntity> list(String tenantId, UUID callingAgentId, String scope) {
        return list(tenantId, TenantResolver.currentRequestOrganizationId(), callingAgentId, scope);
    }

    /**
     * Audit 2026-05-17 round-4 - org-aware list. Routes through strict-org or
     * strict-personal foundation finders (PR27.2). For sub-scopes
     * (created_by_me / targeting_me), we still pre-filter by agent and
     * post-filter the result set in memory to avoid adding 4×3 query
     * variants - the list is bounded by recurrence count which is small.
     */
    @Transactional(readOnly = true)
    public List<AgentTaskRecurrenceEntity> list(String tenantId,
                                                 String organizationId,
                                                 UUID callingAgentId,
                                                 String scope) {
        TenantResolver.requireOrgId(organizationId);
        List<AgentTaskRecurrenceEntity> all =
                recurrenceRepository.findByOrganizationIdStrictOrderByCreatedAtDesc(organizationId);
        if (scope == null || "created_by_me".equalsIgnoreCase(scope)) {
            if (callingAgentId == null) return all;
            return all.stream()
                    .filter(r -> callingAgentId.equals(r.getCreatedByAgentId()))
                    .toList();
        }
        if ("targeting_me".equalsIgnoreCase(scope)) {
            if (callingAgentId == null) return List.of();
            return all.stream()
                    .filter(r -> callingAgentId.equals(r.getTargetAgentId()))
                    .toList();
        }
        if ("all_in_tenant".equalsIgnoreCase(scope)) {
            return all;
        }
        throw new IllegalArgumentException("invalid scope: " + scope);
    }

    @Transactional
    public AgentTaskRecurrenceEntity update(String tenantId,
                                             UUID recurrenceId,
                                             UUID callingAgentId,
                                             String callingUserId,
                                             UpdateRecurrenceRequest request) {
        // Audit 2026-05-17 round-4 - route through scope-aware finder.
        AgentTaskRecurrenceEntity r = findInScope(tenantId, TenantResolver.currentRequestOrganizationId(), recurrenceId)
                .orElseThrow(() -> new IllegalArgumentException("recurrence not found: " + recurrenceId));
        if (!canModify(r, callingAgentId, callingUserId)) {
            throw new IllegalStateException("only the creator may update this recurrence");
        }
        if (request.enabled() != null) r.setEnabled(request.enabled());
        if (request.title() != null) r.setTitle(truncate(request.title(), 500));
        if (request.instructions() != null) r.setInstructions(request.instructions());
        if (request.priority() != null) r.setPriority(normalizePriority(request.priority()));
        if (request.cronExpression() != null && !request.cronExpression().isBlank()) {
            CronExpression cron = parseCron(request.cronExpression());
            r.setCronExpression(request.cronExpression());
            r.setNextFireAt(computeNextFire(cron, parseZone(r.getTimezone()), Instant.now()));
        }
        return recurrenceRepository.save(r);
    }

    @Transactional
    public void delete(String tenantId, UUID recurrenceId, UUID callingAgentId, String callingUserId) {
        // Audit 2026-05-17 round-4 - route through scope-aware finder.
        AgentTaskRecurrenceEntity r = findInScope(tenantId, TenantResolver.currentRequestOrganizationId(), recurrenceId)
                .orElseThrow(() -> new IllegalArgumentException("recurrence not found: " + recurrenceId));
        if (!canModify(r, callingAgentId, callingUserId)) {
            throw new IllegalStateException("only the creator may delete this recurrence");
        }
        recurrenceRepository.delete(r);
    }

    /**
     * Audit 2026-05-17 round-4 - scope-aware single fetch. Owner-or-org
     * predicate: matches the row when (organizationId is set AND matches
     * caller's active org) OR (organizationId is NULL AND tenant matches).
     */
    private Optional<AgentTaskRecurrenceEntity> findInScope(String tenantId, String organizationId, UUID recurrenceId) {
        TenantResolver.requireOrgId(organizationId);
        return recurrenceRepository.findByIdAndOrganizationIdStrict(recurrenceId, organizationId);
    }

    // ========================================================================
    // Tick - fires a single due recurrence
    // ========================================================================

    /**
     * Fires a recurrence once: claims the fire slot, then creates a new task from
     * the template. The claim advances {@code next_fire_at} to the next cron
     * instant strictly after {@code now} via a conditional UPDATE (CAS on the
     * {@code next_fire_at} value read from {@code findDue}), BEFORE the task is
     * spawned and in the same transaction. ShedLock alone does not prevent
     * double-fire (a tick outliving {@code lockAtMostFor} lets a second instance
     * re-fetch the same due rows); with the CAS, the losing claim matches 0 rows
     * and spawns nothing, and a crash between claim and spawn rolls back both.
     * Fire-once-then-skip: if the recurrence was behind by 3 intervals, only ONE
     * task is created and the tracker jumps past all missed windows.
     */
    @Transactional
    public Optional<AgentTaskEntity> fireOnce(AgentTaskRecurrenceEntity r) {
        if (!r.isEnabled()) return Optional.empty();

        CronExpression cron;
        ZoneId zone;
        try {
            cron = parseCron(r.getCronExpression());
            zone = parseZone(r.getTimezone());
        } catch (IllegalArgumentException e) {
            logger.error("Recurrence {} has invalid cron/zone: {}", r.getId(), e.getMessage());
            r.setEnabled(false);
            recurrenceRepository.save(r);
            return Optional.empty();
        }

        Instant now = Instant.now();
        Instant expected = r.getNextFireAt();
        Instant next = computeNextFire(cron, zone, now);
        if (expected == null
                || recurrenceRepository.claimFireSlot(r.getId(), expected, next, now) == 0) {
            logger.info("Recurrence {} fire slot already claimed (concurrent tick or stale row) - skipping",
                    r.getId());
            return Optional.empty();
        }

        AgentTaskEntity task = new AgentTaskEntity();
        task.setTenantId(r.getTenantId());
        // Audit 2026-05-17 round-4 - copy organization_id from recurrence to
        // spawned task. Prior: org-scoped recurrence silently fired
        // personal-scope tasks → board scoped to org workspace stayed empty.
        task.setOrganizationId(r.getOrganizationId());
        task.setCreatedByAgentId(r.getCreatedByAgentId());
        task.setCreatedByUserId(r.getCreatedByUserId());
        task.setAssignedToAgentId(r.getTargetAgentId());  // NULL = backlog
        task.setRecurrenceId(r.getId());
        task.setTitle(r.getTitle());
        task.setInstructions(r.getInstructions());
        // Defensive copy of the template context so mutations on the task don't leak back
        task.setTaskContext(r.getTaskContext() == null ? null : new HashMap<>(r.getTaskContext()));
        task.setPriority(r.getPriority());
        task.setStatus(AgentTaskEntity.STATUS_PENDING);
        task.setDepth(0);
        AgentTaskEntity saved = taskRepository.save(task);

        // Live board update: without this, a cron-spawned task is invisible on an
        // open task board until the next slow poll (every other creation path
        // publishes via AgentTaskService.assignTask).
        publishTaskCreatedAfterCommit(saved);

        logger.info("Recurrence {} fired -> task {} (nextFireAt={})", r.getId(), saved.getId(), next);
        return Optional.of(saved);
    }

    /** After-commit task_created publish, mirroring AgentTaskService.publishAfterCommit. */
    private void publishTaskCreatedAfterCommit(AgentTaskEntity saved) {
        if (taskBoardPublisher == null) return;
        Runnable action = () -> taskBoardPublisher.publishTaskCreated(saved.getTenantId(), saved);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try { action.run(); } catch (Exception e) {
                        logger.debug("Task board publish failed (non-critical): {}", e.getMessage());
                    }
                }
            });
        } else {
            try { action.run(); } catch (Exception e) {
                logger.debug("Task board publish failed (non-critical): {}", e.getMessage());
            }
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /** Exposed for the scheduler. */
    public List<AgentTaskRecurrenceEntity> findDue(Instant now) {
        return recurrenceRepository.findDue(now);
    }

    private static CronExpression parseCron(String expr) {
        try {
            return CronExpression.parse(expr);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid cron expression: " + expr + " (" + e.getMessage() + ")");
        }
    }

    private static ZoneId parseZone(String tz) {
        try {
            return ZoneId.of(tz);
        } catch (java.time.DateTimeException e) {
            // Covers ZoneRulesException (bad tz id) and DateTimeException (bad input)
            throw new IllegalArgumentException("invalid timezone: " + tz);
        }
    }

    /**
     * Returns the first cron instant strictly after {@code from}, translated to
     * UTC via the supplied zone. {@link ZoneId} conversion is done via
     * {@code withZoneSameInstant} to avoid wall-clock drift at DST boundaries.
     */
    private static Instant computeNextFire(CronExpression cron, ZoneId zone, Instant from) {
        ZonedDateTime now = from.atZone(zone);
        ZonedDateTime next = cron.next(now);
        if (next == null) {
            // Cron will never fire again (e.g. "0 0 0 29 2 ? 2023"). Push far future.
            return Instant.now().plusSeconds(365L * 24 * 3600);
        }
        return next.withZoneSameInstant(ZoneOffset.UTC).toInstant();
    }

    private static boolean canModify(AgentTaskRecurrenceEntity r, UUID callingAgentId, String callingUserId) {
        return (callingAgentId != null && callingAgentId.equals(r.getCreatedByAgentId()))
                || (callingUserId != null && callingUserId.equals(r.getCreatedByUserId()));
    }

    private static String normalizePriority(String priority) {
        if (priority == null) return AgentTaskEntity.PRIORITY_NORMAL;
        String p = priority.trim().toLowerCase();
        return switch (p) {
            case "low", "normal", "high", "urgent" -> p;
            default -> throw new IllegalArgumentException("invalid priority: " + priority);
        };
    }

    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

}
