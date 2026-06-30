package com.apimarketplace.orchestrator.services.persistence;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service responsible for resolving and creating workflow entities.
 * Handles caching of workflow and run IDs for efficient lookups.
 */
@Service
public class WorkflowEntityResolverService {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowEntityResolverService.class);

    private final WorkflowRepository workflowRepository;
    private final WorkflowRunRepository workflowRunRepository;

    // Caches for run ID mappings
    private final Map<String, UUID> runWorkflowIds = new ConcurrentHashMap<>();
    private final Map<String, UUID> runWorkflowRunIds = new ConcurrentHashMap<>();
    // F5: prior implementation used `ConcurrentHashMap.newKeySet()` here, which
    // gave a JVM-lifetime block-list - a single failed auto-create (transient
    // DB hiccup, unique-constraint race between replicas) silently denied
    // persistence for that runId until restart. Bound to 60s TTL so transient
    // failures heal naturally; the DataIntegrityViolationException re-query
    // path in createWorkflowEntity handles the racing-replica case explicitly.
    private final Cache<String, Boolean> unresolvedWorkflowRuns = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(60))
            .maximumSize(10_000)
            .build();

    public WorkflowEntityResolverService(
            WorkflowRepository workflowRepository,
            WorkflowRunRepository workflowRunRepository) {
        this.workflowRepository = workflowRepository;
        this.workflowRunRepository = workflowRunRepository;
    }

    /**
     * Resolves the WorkflowEntity for an execution.
     * Creates a new entity if one doesn't exist.
     */
    public Optional<WorkflowEntity> resolveWorkflowEntity(WorkflowExecution execution) {
        String runId = execution.getRunId();
        if (unresolvedWorkflowRuns.getIfPresent(runId) != null) {
            return Optional.empty();
        }

        UUID cached = runWorkflowIds.get(runId);
        if (cached != null) {
            return workflowRepository.findById(cached);
        }

        String planId = execution.getPlan().getId();
        logger.info("Looking for workflow with plan ID: {} for run {}", planId, runId);

        if (planId == null || planId.isBlank()) {
            if (markUnresolved(runId)) {
                logger.warn("Workflow plan id missing for run {}, skipping persistence", runId);
            }
            return Optional.empty();
        }

        UUID workflowId;
        try {
            workflowId = UUID.fromString(planId);
        } catch (IllegalArgumentException e) {
            if (markUnresolved(runId)) {
                logger.warn("Workflow plan id '{}' is not a valid UUID for run {}", planId, runId);
            }
            return Optional.empty();
        }

        Optional<WorkflowEntity> existingEntity = workflowRepository.findById(workflowId);

        WorkflowEntity entity = existingEntity.orElseGet(() -> createWorkflowEntity(execution, workflowId));

        if (entity != null) {
            entity.setPlan(new HashMap<>(execution.getPlan().getOriginalPlan()));
            entity.setDataInputs(execution.getInitialInputs());
            runWorkflowIds.put(runId, workflowId);
            return Optional.of(entity);
        }

        markUnresolved(runId);
        return Optional.empty();
    }

    /**
     * Mark a runId as unresolved for the next 60s (F5 TTL). Returns true if this
     * is the first mark in the window (caller logs WARN once), false if already
     * marked. Idempotency preserves the historical
     * {@code ConcurrentHashMap.newKeySet().add()} contract that gated WARN logs.
     */
    private boolean markUnresolved(String runId) {
        return unresolvedWorkflowRuns.asMap().putIfAbsent(runId, Boolean.TRUE) == null;
    }

    /**
     * Resolves the WorkflowRunEntity ID for an execution.
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

        if (markUnresolved(execution.getRunId())) {
            logger.warn("No workflow run entity found for run {}", execution.getRunId());
        }
        return Optional.empty();
    }

    /**
     * Gets the current epoch from the workflow run entity's metadata.
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
     * Gets the current spawn from the workflow run entity's metadata.
     *
     * <p>Uses {@code lastRerunSpawn} (set by StepRerunService on each rerun).
     * For first execution, returns 0 (no rerun has occurred).
     *
     * @param workflowRunId The workflow run UUID
     * @return Current spawn (0 if not set or first execution)
     */
    public int getCurrentSpawnFromRun(UUID workflowRunId) {
        if (workflowRunId == null) {
            return 0;
        }
        return workflowRunRepository.findById(workflowRunId)
                .map(entity -> {
                    Map<String, Object> metadata = entity.getMetadata();
                    if (metadata == null) return 0;
                    Object spawnValue = metadata.get("lastRerunSpawn");
                    if (spawnValue instanceof Number) {
                        return ((Number) spawnValue).intValue();
                    }
                    return 0;
                })
                .orElse(0);
    }

    /**
     * Resolves the parent run organization for native persistence paths that
     * bypass JPA entity listeners.
     */
    public Optional<String> getOrganizationIdFromRun(UUID workflowRunId) {
        if (workflowRunId == null) {
            return Optional.empty();
        }
        return workflowRunRepository.findById(workflowRunId)
                .map(WorkflowRunEntity::getOrganizationId)
                .filter(orgId -> orgId != null && !orgId.isBlank());
    }

    /**
     * Gets the current spawn for a specific trigger from the workflow run entity's metadata.
     *
     * @param workflowRunId The workflow run UUID
     * @param triggerId The trigger ID (e.g., "trigger:my_webhook")
     * @return Current spawn (0 if not set)
     */
    @SuppressWarnings("unchecked")
    public int getCurrentSpawnFromRun(UUID workflowRunId, String triggerId) {
        if (workflowRunId == null || triggerId == null) {
            return 0;
        }
        return workflowRunRepository.findById(workflowRunId)
                .map(entity -> {
                    Map<String, Object> metadata = entity.getMetadata();
                    if (metadata == null) return 0;
                    Object dagCurrentSpawn = metadata.get("dagCurrentSpawn");
                    if (dagCurrentSpawn instanceof Map) {
                        Object spawnValue = ((Map<String, Object>) dagCurrentSpawn).get(triggerId);
                        if (spawnValue instanceof Number) {
                            return ((Number) spawnValue).intValue();
                        }
                    }
                    return 0;
                })
                .orElse(0);
    }

    /**
     * Caches the workflow and run IDs for a run.
     */
    public void cacheIds(String runId, UUID workflowId, UUID workflowRunId) {
        if (workflowId != null) {
            runWorkflowIds.put(runId, workflowId);
        }
        if (workflowRunId != null) {
            runWorkflowRunIds.put(runId, workflowRunId);
        }
    }

    /**
     * Cleans up all caches for a run.
     */
    public void cleanup(String runId) {
        runWorkflowIds.remove(runId);
        runWorkflowRunIds.remove(runId);
        unresolvedWorkflowRuns.invalidate(runId);
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
            entity.setDescription(description instanceof String ? (String) description
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
        } catch (DataIntegrityViolationException dive) {
            // F5: unique-constraint race between replicas. Another replica just
            // auto-created the same workflowId.
            // Don't poison the cache with a JVM-lifetime block - re-query the row
            // that the other replica just committed. The unresolvedWorkflowRuns
            // entry, if added by the caller fallback, will expire in 60s anyway.
            logger.warn("Auto-create raced with another replica for workflow {} run {} - re-querying: {}",
                    workflowId, execution.getRunId(), dive.getMessage());
            return workflowRepository.findById(workflowId).orElseGet(() -> {
                logger.error("Auto-create lost the race but re-query returned empty for workflow {} run {} - "
                                + "transient state, will retry in 60s when unresolved-cache TTL expires",
                        workflowId, execution.getRunId());
                return null;
            });
        } catch (Exception e) {
            logger.error("Unable to auto-create workflow entity {} for run {}: [{}] {}",
                    workflowId, execution.getRunId(), e.getClass().getSimpleName(), e.getMessage(), e);
            return null;
        }
    }
}
