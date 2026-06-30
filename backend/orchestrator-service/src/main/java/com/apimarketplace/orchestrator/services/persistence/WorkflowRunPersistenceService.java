package com.apimarketplace.orchestrator.services.persistence;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.repository.WorkflowPlanVersionRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.events.WorkflowRunTerminatedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service responsible for workflow run lifecycle persistence.
 * Handles workflow start, completion, and entity resolution.
 */
@Service
@Transactional
public class WorkflowRunPersistenceService {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowRunPersistenceService.class);

    private final WorkflowRepository workflowRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final ScheduleSyncService scheduleSyncService;

    @Autowired
    private WorkflowPlanVersionRepository planVersionRepository;

    @Autowired
    private StorageBreakdownService breakdownService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthClient authClient;

    @Autowired(required = false)
    private com.apimarketplace.orchestrator.services.markup.PlatformMarkupPinService platformMarkupPinService;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    // Cache for run ID to workflow/run UUID mappings
    private final Map<String, UUID> runWorkflowIds = new ConcurrentHashMap<>();
    private final Map<String, UUID> runWorkflowRunIds = new ConcurrentHashMap<>();
    private final Set<String> unresolvedWorkflowRuns = ConcurrentHashMap.newKeySet();

    public WorkflowRunPersistenceService(
            WorkflowRepository workflowRepository,
            WorkflowRunRepository workflowRunRepository,
            ScheduleSyncService scheduleSyncService) {
        this.workflowRepository = workflowRepository;
        this.workflowRunRepository = workflowRunRepository;
        this.scheduleSyncService = scheduleSyncService;
    }

    /**
     * Records the start of a workflow execution.
     *
     * @param execution The workflow execution to record
     */
    public void recordWorkflowStart(WorkflowExecution execution) {
        resolveWorkflowEntity(execution).ifPresent(entity -> {
            try {
                Instant now = Instant.now();

                boolean hasReusable = hasReusableTrigger(execution.getPlan());

                // Create run entity
                WorkflowRunEntity runEntity = buildRunEntity(entity, execution, hasReusable, now);

                // Save and update references
                WorkflowRunEntity savedRun = workflowRunRepository.save(runEntity);

                // EXECUTION_DATA tracked by daily reconciliation only (incremental tracking
                // causes negative drift because state_snapshot grows during execution).
                // TODO(storage-drift): together with the asymmetry at StateSnapshotService:2133
                // and WorkflowManagementService:707, this leaves drift that
                // StorageReconciliationService.refreshExecutionData has to mask inline on
                // every GET /storage/{quota,breakdown}. Fixing at source = removing that
                // workaround.

                execution.setWorkflowRunId(savedRun.getId());
                runWorkflowIds.put(execution.getRunId(), entity.getId());
                runWorkflowRunIds.put(execution.getRunId(), savedRun.getId());

                // Update workflow entity status
                updateWorkflowEntityStatus(entity, now);

                // Sync schedule if applicable
                if (scheduleSyncService.hasScheduleTrigger(execution.getPlan())) {
                    scheduleSyncService.syncFromPinnedVersion(entity);
                }

            } catch (Exception e) {
                logger.warn("Unable to persist workflow start for run {}: {}",
                           execution.getRunId(), e.getMessage(), e);
            }
        });
    }

    /**
     * Records the completion of a workflow execution.
     *
     * @param execution The workflow execution that completed
     */
    public void recordWorkflowCompletion(WorkflowExecution execution) {
        resolveWorkflowRunId(execution).ifPresent(runId -> {
            try {
                workflowRunRepository.findById(runId).ifPresent(runEntity -> {
                    Instant now = Instant.now();
                    RunStatus terminalStatus = execution.getStatus() != null
                            ? execution.getStatus() : RunStatus.FAILED;
                    runEntity.setStatus(terminalStatus);
                    runEntity.setEndedAt(now);
                    // duration_ms is NOT set here - per-epoch durations are stored in
                    // workflow_epochs (started_at/closed_at/duration_ms per header row),
                    // and the cumulative total is accumulated in StateSnapshot.totalDurationMs.
                    runEntity.setUpdatedAt(now);
                    workflowRunRepository.save(runEntity);

                    // Round-7 redesign (PR3): notify the trigger lifecycle that a run
                    // terminated. The listener (RunTerminationListener) calls
                    // WorkflowPinService.rearm() AFTER_COMMIT - we never rearm against
                    // a not-yet-persisted run-status write.
                    if (eventPublisher != null && runEntity.getWorkflow() != null) {
                        eventPublisher.publishEvent(new WorkflowRunTerminatedEvent(
                                runEntity.getId(),
                                runEntity.getWorkflow().getId(),
                                terminalStatus,
                                runEntity.getPlanVersion()
                        ));
                    }
                });
            } catch (Exception e) {
                logger.warn("Unable to persist workflow run completion for {}: {}",
                           execution.getRunId(), e.getMessage(), e);
            }
        });

        resolveWorkflowEntity(execution).ifPresent(entity -> {
            try {
                Instant now = Instant.now();
                entity.setLastExecutedAt(now);
                // Preserve lifecycle status (DRAFT/ACTIVE) - don't overwrite with run outcome.
                // Run status is tracked on WorkflowRunEntity.
                entity.setUpdatedAt(now);
                workflowRepository.save(entity);
            } catch (Exception e) {
                logger.warn("Unable to update workflow {} summary after run {}: {}",
                           entity.getId(), execution.getRunId(), e.getMessage(), e);
            }
        });

        // Cancel platform-markup pricing pins so stragglers don't keep billing
        // markup after the run has reached a terminal state. Fail-open - pin
        // cancel is advisory, not load-bearing.
        if (platformMarkupPinService != null && execution.getRunId() != null) {
            try {
                platformMarkupPinService.cancelPinsForRun(execution.getRunId());
            } catch (Exception e) {
                logger.warn("Failed to cancel platform-markup pins for run {}: {}",
                        execution.getRunId(), e.getMessage());
            }
        }

        cleanupRunCaches(execution.getRunId());
    }

    /**
     * Resolves the workflow entity for an execution.
     *
     * @param execution The workflow execution
     * @return Optional containing the workflow entity if found
     */
    public Optional<WorkflowEntity> resolveWorkflowEntity(WorkflowExecution execution) {
        String runId = execution.getRunId();
        if (unresolvedWorkflowRuns.contains(runId)) {
            return Optional.empty();
        }

        UUID cached = runWorkflowIds.get(runId);
        if (cached != null) {
            return workflowRepository.findById(cached);
        }

        String planId = execution.getPlan().getId();
        logger.info("🔍 Looking for workflow with plan ID: {} for run {}", planId, runId);

        if (planId == null || planId.isBlank()) {
            if (unresolvedWorkflowRuns.add(runId)) {
                logger.warn("Workflow plan id missing for run {}, skipping persistence", runId);
            }
            return Optional.empty();
        }

        UUID workflowId;
        try {
            workflowId = UUID.fromString(planId);
            logger.info("🔍 Converted plan ID to UUID: {} for run {}", workflowId, runId);
        } catch (IllegalArgumentException e) {
            if (unresolvedWorkflowRuns.add(runId)) {
                logger.warn("Workflow plan id '{}' is not a valid UUID for run {}, skipping persistence",
                           planId, runId);
            }
            return Optional.empty();
        }

        logger.info("🔍 Searching for workflow entity with ID: {} in repository", workflowId);
        Optional<WorkflowEntity> existingEntity = workflowRepository.findById(workflowId);

        if (existingEntity.isPresent()) {
            logger.info("✅ Found existing workflow entity: {} for run {}", workflowId, runId);
        } else {
            logger.warn("⚠️ Workflow entity NOT FOUND with ID: {} for run {}, will create new one",
                       workflowId, runId);
        }

        WorkflowEntity entity = existingEntity.orElseGet(() -> {
            logger.info("🏗️ Creating new workflow entity with ID: {} for run {}", workflowId, runId);
            return createWorkflowEntity(execution, workflowId);
        });

        if (entity != null) {
            entity.setPlan(new HashMap<>(execution.getPlan().getOriginalPlan()));
            entity.setDataInputs(execution.getInitialInputs());
            runWorkflowIds.put(runId, workflowId);
            return Optional.of(entity);
        }

        unresolvedWorkflowRuns.add(runId);
        return Optional.empty();
    }

    /**
     * Resolves the workflow run ID for an execution.
     *
     * @param execution The workflow execution
     * @return Optional containing the workflow run UUID if found
     */
    public Optional<UUID> resolveWorkflowRunId(WorkflowExecution execution) {
        UUID executionRunId = execution.getWorkflowRunId();
        if (executionRunId != null) {
            runWorkflowRunIds.put(execution.getRunId(), executionRunId);
            return Optional.of(executionRunId);
        }

        UUID cached = runWorkflowRunIds.get(execution.getRunId());
        if (cached != null) {
            execution.setWorkflowRunId(cached);
            return Optional.of(cached);
        }

        Optional<WorkflowRunEntity> existing = workflowRunRepository.findByRunIdPublic(execution.getRunId());
        if (existing.isPresent()) {
            WorkflowRunEntity entity = existing.get();
            runWorkflowRunIds.put(execution.getRunId(), entity.getId());
            if (entity.getWorkflow() != null) {
                runWorkflowIds.put(execution.getRunId(), entity.getWorkflow().getId());
            }
            execution.setWorkflowRunId(entity.getId());
            return Optional.of(entity.getId());
        }

        if (unresolvedWorkflowRuns.add(execution.getRunId())) {
            logger.warn("No workflow run entity found for run {}", execution.getRunId());
        }
        return Optional.empty();
    }

    /**
     * Gets the current epoch from the workflow run entity's metadata.
     * The epoch is incremented each time a re-run is triggered.
     *
     * @param workflowRunId The workflow run UUID
     * @return The current epoch (0 if not set or not found)
     */
    public int getCurrentEpochFromRun(UUID workflowRunId) {
        if (workflowRunId == null) {
            return 0;
        }
        return workflowRunRepository.findById(workflowRunId)
                .map(entity -> {
                    Map<String, Object> metadata = entity.getMetadata();
                    if (metadata != null && metadata.containsKey("currentEpoch")) {
                        Object epochValue = metadata.get("currentEpoch");
                        if (epochValue instanceof Number) {
                            return ((Number) epochValue).intValue();
                        }
                    }
                    return 0;
                })
                .orElse(0);
    }

    /**
     * Clears the deduplication caches for a run.
     *
     * @param runId The run ID to clear caches for
     */
    public void clearDeduplicationCaches(String runId) {
        cleanupRunCaches(runId);
    }

    /**
     * Gets the cached workflow ID for a run ID.
     */
    public UUID getCachedWorkflowId(String runId) {
        return runWorkflowIds.get(runId);
    }

    /**
     * Gets the cached workflow run ID for a run ID.
     */
    public UUID getCachedWorkflowRunId(String runId) {
        return runWorkflowRunIds.get(runId);
    }

    // ============================================================
    // PRIVATE HELPER METHODS
    // ============================================================

    /**
     * Creates a new WorkflowRunEntity for a given execution, with optional user plan.
     * Overload that stores userPlan in metadata for priority-based execution queue.
     */
    public WorkflowRunEntity buildRunEntity(WorkflowEntity entity, WorkflowExecution execution,
                                             boolean hasReusable, Instant now, String userPlan) {
        WorkflowRunEntity runEntity = buildRunEntity(entity, execution, hasReusable, now);
        if (userPlan != null && !userPlan.isBlank()) {
            Map<String, Object> metadata = runEntity.getMetadata() != null
                ? new HashMap<>(runEntity.getMetadata()) : new HashMap<>();
            metadata.put("userPlan", userPlan);
            runEntity.setMetadata(metadata);
        }
        return runEntity;
    }

    /**
     * Creates a new WorkflowRunEntity for a given execution.
     * Public so that the facade (WorkflowPersistenceService) can delegate here - single source of truth.
     */
    public WorkflowRunEntity buildRunEntity(WorkflowEntity entity, WorkflowExecution execution,
                                             boolean hasReusable, Instant now) {
        WorkflowRunEntity runEntity = new WorkflowRunEntity(
                entity,
                entity.getTenantId(),
                execution.getRunId(),
                execution.getInitialInputs(),
                Collections.emptyMap(),
                null
        );

        // Stamp organization_id from the parent workflow so org-teammates can
        // see this run in their workspace. Default-from-workflow keeps the
        // run consistent with the workflow's home; controllers/agents that
        // resolve a more specific org (e.g. MCP path with explicit ctx.orgId)
        // can override after this builder if needed. Audit 2026-05-16: prior
        // implementation never set organization_id at the builder layer,
        // forcing every caller (controller + async fire path + MCP tool +
        // public token + app acquire) to remember the stamp - most didn't.
        // Single root fix collapses ~6 NULL-org-stamp sites downstream.
        runEntity.setOrganizationId(entity.getOrganizationId());

        // Set status based on trigger type
        if (hasReusable) {
            runEntity.setStatus(RunStatus.WAITING_TRIGGER);
            logger.info("🔗 [WorkflowRunPersistence] Workflow has reusable trigger, " +
                       "setting status to WAITING_TRIGGER for run {}", execution.getRunId());
        } else {
            runEntity.setStatus(RunStatus.RUNNING);
        }

        runEntity.setStartedAt(now);
        runEntity.setCreatedAt(now);
        runEntity.setUpdatedAt(now);

        // Resolve display name for the executor and store in run metadata
        String tenantId = entity.getTenantId();
        try {
            String displayName = authClient.getDisplayName(tenantId);
            if (displayName != null) {
                Map<String, Object> metadata = runEntity.getMetadata() != null
                    ? new HashMap<>(runEntity.getMetadata()) : new HashMap<>();
                metadata.put("__displayName__", displayName);
                runEntity.setMetadata(metadata);
                runEntity.setCreatedBy(displayName);
                execution.setDisplayName(displayName);
            }
        } catch (Exception e) {
            logger.debug("Could not resolve display name for tenant {}: {}", tenantId, e.getMessage());
        }

        // Calculate total nodes
        if (execution.getPlan() != null) {
            int totalNodes = calculateTotalNodes(execution.getPlan());
            runEntity.setTotalNodes(totalNodes);
            logger.info("📊 [WorkflowRunPersistence] Calculated totalNodes={} for run {}",
                       totalNodes, execution.getRunId());
        }

        // Save the plan used for this execution.
        // If the workflow has a pinned version and no explicit plan version was set by the caller
        // (i.e., this is a trigger-initiated run, not a manual canvas run), use the pinned plan.
        if (execution.getPlan() != null && execution.getPlan().getOriginalPlan() != null) {
            runEntity.setPlan(new HashMap<>(execution.getPlan().getOriginalPlan()));
        }

        // Resolve pinned version for initial run creation (e.g., workflow activated with existing pin).
        // Prefer the last run's plan (may contain save-in-run modifications) over the version table.
        if (entity.getPinnedVersion() != null && execution.getPlanVersion() == null) {
            try {
                // If the MOST RECENT run for this pinned version is CANCELLED,
                // skip the last-run fallback and go directly to the version table.
                // Rationale: cancel is an explicit user action - falling back to an
                // older run's plan (with stale save-in-run modifications) is unexpected.
                var mostRecentRun = workflowRunRepository
                        .findFirstByWorkflowIdAndPlanVersionOrderByStartedAtDesc(
                                entity.getId(), entity.getPinnedVersion());
                boolean latestIsCancelled = mostRecentRun.isPresent()
                        && mostRecentRun.get().getStatus() == com.apimarketplace.orchestrator.domain.workflow.RunStatus.CANCELLED;

                if (!latestIsCancelled) {
                    // Check if a previous run exists for this pinned version (has save-in-run changes)
                    var lastPinnedRun = workflowRunRepository
                            .findFirstByWorkflowIdAndPlanVersionAndStatusInOrderByStartedAtDesc(
                                    entity.getId(), entity.getPinnedVersion(),
                                    java.util.List.of(
                                            com.apimarketplace.orchestrator.domain.workflow.RunStatus.COMPLETED,
                                            com.apimarketplace.orchestrator.domain.workflow.RunStatus.WAITING_TRIGGER,
                                            com.apimarketplace.orchestrator.domain.workflow.RunStatus.RUNNING,
                                            com.apimarketplace.orchestrator.domain.workflow.RunStatus.PAUSED));
                    if (lastPinnedRun.isPresent() && lastPinnedRun.get().getPlan() != null) {
                        runEntity.setPlan(new HashMap<>(lastPinnedRun.get().getPlan()));
                        runEntity.setPlanVersion(entity.getPinnedVersion());
                        logger.info("[PinnedVersion] Initial run {} using last run's plan for pinned v{} for workflow {}",
                                execution.getRunId(), entity.getPinnedVersion(), entity.getId());
                    } else {
                        // No previous run - fall back to version table (first run for this version)
                        var pinned = planVersionRepository.findByWorkflowIdAndVersion(
                                entity.getId(), entity.getPinnedVersion());
                        if (pinned.isPresent()) {
                            runEntity.setPlan(new HashMap<>(pinned.get().getPlan()));
                            runEntity.setPlanVersion(entity.getPinnedVersion());
                            logger.info("[PinnedVersion] Initial run {} using version table for pinned v{} for workflow {}",
                                    execution.getRunId(), entity.getPinnedVersion(), entity.getId());
                        }
                    }
                } else {
                    // Latest run was cancelled - use version table directly (clean slate)
                    var pinned = planVersionRepository.findByWorkflowIdAndVersion(
                            entity.getId(), entity.getPinnedVersion());
                    if (pinned.isPresent()) {
                        runEntity.setPlan(new HashMap<>(pinned.get().getPlan()));
                        runEntity.setPlanVersion(entity.getPinnedVersion());
                        logger.info("[PinnedVersion] Initial run {} using version table for pinned v{} (latest run was CANCELLED) for workflow {}",
                                execution.getRunId(), entity.getPinnedVersion(), entity.getId());
                    }
                }
            } catch (Exception e) {
                logger.warn("[PinnedVersion] Failed to resolve pinned version for initial run: {}",
                        e.getMessage());
            }
        }

        // Use the plan version resolved by the execution flow (auto-save logic),
        // or fall back to the current max version if not set by the caller.
        if (runEntity.getPlanVersion() == null) {
            try {
                Integer resolvedVersion = execution.getPlanVersion();
                if (resolvedVersion != null) {
                    runEntity.setPlanVersion(resolvedVersion);
                } else {
                    Integer currentVersion = planVersionRepository.getMaxVersion(entity.getId()).orElse(null);
                    runEntity.setPlanVersion(currentVersion);
                }
            } catch (Exception e) {
                logger.warn("Unable to resolve plan version for workflow {}: {}", entity.getId(), e.getMessage());
            }
        }

        return runEntity;
    }

    private void updateWorkflowEntityStatus(WorkflowEntity entity, Instant now) {
        // Preserve lifecycle status (DRAFT/ACTIVE) - don't overwrite with RUNNING.
        // Run status is tracked on WorkflowRunEntity, not on the workflow itself.
        entity.setLastExecutedAt(now);
        entity.setUpdatedAt(now);
        workflowRepository.save(entity);
    }

    private WorkflowEntity createWorkflowEntity(WorkflowExecution execution, UUID workflowId) {
        try {
            WorkflowEntity entity = new WorkflowEntity();
            entity.setId(workflowId);
            entity.setTenantId(execution.getPlan().getTenantId());

            // Stamp organization_id from the current request context when available.
            // WorkflowExecution doesn't carry orgId, so the request-bound header
            // (X-Organization-ID, forwarded by the gateway) is the authoritative source
            // in HTTP-driven exec paths. Daemon/async threads (no servlet request) get
            // null - same conservative fallback every persist hook uses post-round-3.
            String requestOrgId = com.apimarketplace.common.web.TenantResolver
                    .currentRequestOrganizationId();
            if (requestOrgId != null && !requestOrgId.isBlank()) {
                entity.setOrganizationId(requestOrgId);
            }

            Map<String, Object> rawPlan = new HashMap<>(execution.getPlan().getOriginalPlan());

            String planName = rawPlan.getOrDefault("name", "").toString();
            String defaultName = "Workflow " + workflowId.toString().substring(0, 8);
            entity.setName(planName != null && !planName.isBlank() ? planName : defaultName);

            Object description = rawPlan.get("description");
            entity.setDescription(description instanceof String
                ? (String) description
                : "Auto-generated workflow placeholder for runtime execution");

            entity.setStatus(WorkflowEntity.WorkflowStatus.ACTIVE);
            entity.setIsActive(true);

            Instant now = Instant.now();
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("generatedAt", now.toString());
            metadata.put("source", "runtime");
            if (rawPlan.containsKey("original_id")) {
                metadata.put("original_plan_id", rawPlan.get("original_id"));
            }
            entity.setMetadata(metadata);
            entity.setPlan(rawPlan);
            entity.setDataInputs(execution.getInitialInputs());

            WorkflowEntity saved = workflowRepository.save(entity);
            logger.info("Auto-created workflow entity {} for run {}", workflowId, execution.getRunId());
            return saved;
        } catch (Exception e) {
            logger.error("Unable to auto-create workflow entity {} for run {}: {}",
                        workflowId, execution.getRunId(), e.getMessage(), e);
            return null;
        }
    }

    private void cleanupRunCaches(String runId) {
        runWorkflowIds.remove(runId);
        runWorkflowRunIds.remove(runId);
        unresolvedWorkflowRuns.remove(runId);
    }


    /**
     * Calculates total executable nodes for a workflow plan.
     * Counts: triggers, mcps (steps), agents, and ALL cores (decision, set, html_extract,
     * loop, switch, fork, merge, transform, wait, etc.).
     */
    private int calculateTotalNodes(WorkflowPlan plan) {
        int triggerCount = plan.getTriggers() != null ? plan.getTriggers().size() : 0;
        int stepCount = plan.getMcps() != null ? plan.getMcps().size() : 0;
        int agentCount = plan.getAgents() != null ? plan.getAgents().size() : 0;
        int coreCount = plan.getCores() != null ? plan.getCores().size() : 0;
        return triggerCount + stepCount + agentCount + coreCount;
    }

    /**
     * Estimates the size of a workflow run's EXECUTION_DATA columns
     * (state_snapshot, plan, trigger_payload, metadata) in bytes.
     * Matches the columns measured by StorageReconciliationQueries.EXECUTION_DATA.
     */
    public long estimateRunExecutionDataSize(WorkflowRunEntity run) {
        long size = 0;
        try {
            if (run.getStateSnapshot() != null) {
                size += run.getStateSnapshot().getBytes(StandardCharsets.UTF_8).length;
            }
            if (run.getPlan() != null) {
                size += objectMapper.writeValueAsBytes(run.getPlan()).length;
            }
            if (run.getTriggerPayload() != null) {
                size += objectMapper.writeValueAsBytes(run.getTriggerPayload()).length;
            }
            if (run.getMetadata() != null) {
                size += objectMapper.writeValueAsBytes(run.getMetadata()).length;
            }
        } catch (Exception e) {
            logger.debug("Failed to estimate run execution data size for run {}: {}",
                    run.getRunIdPublic(), e.getMessage());
        }
        return size;
    }

    /**
     * Checks if the workflow plan has a reusable trigger (webhook, manual, chat).
     */
    private boolean hasReusableTrigger(WorkflowPlan plan) {
        if (plan == null || plan.getTriggers() == null) {
            return false;
        }
        return plan.getTriggers().stream()
            .anyMatch(trigger -> {
                String type = trigger.type();
                return "webhook".equalsIgnoreCase(type)
                    || "manual".equalsIgnoreCase(type)
                    || "chat".equalsIgnoreCase(type);
            });
    }
}
