package com.apimarketplace.orchestrator.services;

import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowPlanVersionEntity;
import com.apimarketplace.orchestrator.repository.WorkflowPlanVersionRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.dao.DataIntegrityViolationException;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Service for managing workflow plan version history.
 * Creates versions of the NEW plan after each save, and supports restore/rename operations.
 * The current plan is ALWAYS the latest version - no ambiguity.
 */
@Service
@Transactional
public class WorkflowPlanVersionService {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowPlanVersionService.class);

    private final WorkflowPlanVersionRepository versionRepository;
    private final WorkflowRepository workflowRepository;
    private final StorageBreakdownService breakdownService;
    private final ObjectMapper objectMapper;

    @Value("${workflow.versioning.max-versions:20}")
    private int maxVersions;

    public WorkflowPlanVersionService(WorkflowPlanVersionRepository versionRepository,
                                       WorkflowRepository workflowRepository,
                                       StorageBreakdownService breakdownService,
                                       ObjectMapper objectMapper) {
        this.versionRepository = versionRepository;
        this.workflowRepository = workflowRepository;
        this.breakdownService = breakdownService;
        this.objectMapper = objectMapper;
    }

    private static final int MAX_VERSION_RETRIES = 3;

    /**
     * Create a new version of the plan (the NEW plan, after it has been saved to workflows.plan).
     * Only creates a version if the plan actually changed compared to the latest version.
     * Returns the version number (new or existing if no change).
     *
     * @param workflowId the workflow ID
     * @param plan       the new plan to version (already saved to workflows.plan)
     * @param userId     the user performing the save
     * @param label      optional label for the version (e.g., "Agent session"), nullable
     * @return the version number assigned (or existing max if plan unchanged)
     */
    public int createVersion(UUID workflowId, Map<String, Object> plan, String userId, String label) {
        // Check if the plan actually changed compared to the latest version
        int currentMax = versionRepository.getMaxVersion(workflowId).orElse(0);
        if (currentMax > 0) {
            Optional<WorkflowPlanVersionEntity> latestVersion =
                    versionRepository.findByWorkflowIdAndVersion(workflowId, currentMax);
            if (latestVersion.isPresent() && plansAreEqual(plan, latestVersion.get().getPlan())) {
                logger.debug("Plan unchanged for workflow {}, skipping version creation (current: v{})",
                        workflowId, currentMax);
                return currentMax;
            }
        }

        // Plan changed (or no versions yet) - create a new version
        for (int attempt = 0; attempt < MAX_VERSION_RETRIES; attempt++) {
            try {
                int nextVersion = versionRepository.getMaxVersion(workflowId).orElse(0) + 1;

                WorkflowPlanVersionEntity versionEntity = new WorkflowPlanVersionEntity(
                        workflowId, nextVersion, plan, userId
                );
                if (label != null && !label.isBlank()) {
                    versionEntity.setLabel(label);
                }
                versionRepository.save(versionEntity);
                // Issue #149 - look up the workflow to thread orgId into the rollup so
                // team-workspace CONFIGURATION usage reflects version history growth.
                String orgId = workflowRepository.findById(workflowId)
                        .map(WorkflowEntity::getOrganizationId)
                        .orElse(null);
                breakdownService.trackSave(userId, "CONFIGURATION", estimatePlanSize(plan), orgId);

                logger.info("Created version {} for workflow {}{}", nextVersion, workflowId,
                        label != null && !label.isBlank() ? " (label: " + label + ")" : "");

                // Purge old versions beyond retention limit
                purgeOldVersions(workflowId);

                return nextVersion;
            } catch (DataIntegrityViolationException e) {
                if (attempt < MAX_VERSION_RETRIES - 1) {
                    logger.warn("Version number collision for workflow {} (attempt {}), retrying...",
                            workflowId, attempt + 1);
                } else {
                    logger.error("Failed to create version for workflow {} after {} attempts",
                            workflowId, MAX_VERSION_RETRIES);
                    throw e;
                }
            }
        }
        // Unreachable, but required by compiler
        throw new IllegalStateException("Failed to create version after retries");
    }

    /**
     * Create a new version without a label.
     */
    public int createVersion(UUID workflowId, Map<String, Object> plan, String userId) {
        return createVersion(workflowId, plan, userId, null);
    }

    /**
     * Same as {@link #createVersion(UUID, Map, String, String)} but in its OWN
     * transaction ({@code REQUIRES_NEW}).
     *
     * <p>Use from inside a caller-owned transaction when a versioning failure must
     * degrade (WARN + keep the legacy version stamp) instead of poisoning the caller:
     * with the default {@code REQUIRED} propagation, an exception crossing this bean's
     * proxy marks the shared transaction rollback-only even when the caller catches
     * it - the caller's commit then fails with {@code UnexpectedRollbackException},
     * turning the documented degrade into a request-level failure.
     *
     * <p>Trade-off: the version row commits even if the caller's transaction later
     * rolls back - harmless for the append-only history (dedupe absorbs replays).
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public int createVersionInNewTransaction(UUID workflowId, Map<String, Object> plan, String userId, String label) {
        return createVersion(workflowId, plan, userId, label);
    }

    /**
     * Execution-time version resolution - NEVER mints a new version number for an
     * unpinned workflow. Runs (trigger fires, re-fires, SBS steps, agent fires) must
     * stay on the same version across epochs; only explicit save paths advance the
     * version history.
     *
     * <p>Semantics ("latest plan overwrites the old one, same version"):
     * <ul>
     *   <li>plan content == latest stored version → return the latest number (read-only)</li>
     *   <li>plan content differs → overwrite the latest version's stored plan IN PLACE
     *       (same number, label preserved) so the run↔version content parity holds
     *       without inflating the history</li>
     *   <li>latest version is the workflow's pinned version → the pinned row is
     *       immutable: fall back to {@link #createVersion} (mints a draft version)</li>
     *   <li>no version history yet → {@link #createVersion} seeds v1</li>
     * </ul>
     *
     * @param workflowId the workflow ID
     * @param plan       the plan the run is about to execute
     * @param userId     the user on whose behalf the run executes
     * @return the version number whose stored content now equals {@code plan}
     */
    public int resolveContentVersionForExecution(UUID workflowId, Map<String, Object> plan, String userId) {
        int currentMax = versionRepository.getMaxVersion(workflowId).orElse(0);
        if (currentMax == 0) {
            // First version ever - seed the history.
            return createVersion(workflowId, plan, userId, null);
        }

        Optional<WorkflowPlanVersionEntity> latestOpt =
                versionRepository.findByWorkflowIdAndVersion(workflowId, currentMax);
        if (latestOpt.isEmpty()) {
            return createVersion(workflowId, plan, userId, null);
        }

        WorkflowPlanVersionEntity latest = latestOpt.get();
        if (plansAreEqual(plan, latest.getPlan())) {
            return currentMax;
        }

        // Pinned rows are immutable - a pin is a contract that this exact content
        // stays reproducible. If the latest version IS the pinned one, mint a draft
        // version instead of mutating it (editor runs on pinned workflows).
        Integer pinnedVersion = workflowRepository.findById(workflowId)
                .map(WorkflowEntity::getPinnedVersion)
                .orElse(null);
        if (pinnedVersion != null && pinnedVersion == currentMax) {
            return createVersion(workflowId, plan, userId, null);
        }

        latest.setPlan(new HashMap<>(plan));
        versionRepository.save(latest);
        logger.info("Overwrote version {} content in place for workflow {} (execution-time refresh, no new version)",
                currentMax, workflowId);
        return currentMax;
    }

    /**
     * Same as {@link #resolveContentVersionForExecution(UUID, Map, String)} but in its
     * OWN transaction ({@code REQUIRES_NEW}) - same degrade rationale as
     * {@link #createVersionInNewTransaction}: a versioning failure inside a
     * caller-owned transaction must WARN and fall back, not poison the caller's
     * commit with {@code UnexpectedRollbackException}.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public int resolveContentVersionForExecutionInNewTransaction(UUID workflowId, Map<String, Object> plan, String userId) {
        return resolveContentVersionForExecution(workflowId, plan, userId);
    }

    /**
     * Create or update a session-scoped version.
     *
     * <p>If the latest version's label matches the given sessionId, the plan is
     * overwritten in-place (same version number). Otherwise a new version is created
     * with label = sessionId.
     *
     * <p>This avoids version spam when the agent makes many modifications in a single
     * session - only one version entry is used per session, updated on each change.
     *
     * @param workflowId the workflow ID
     * @param plan       the current plan
     * @param userId     the user performing the modification
     * @param sessionId  the builder session ID (e.g. "wb_a1b2c3d4e5f6")
     * @return the version number (new or updated)
     */
    public int createOrUpdateSessionVersion(UUID workflowId, Map<String, Object> plan,
                                             String userId, String sessionId) {
        int currentMax = versionRepository.getMaxVersion(workflowId).orElse(0);

        if (currentMax > 0) {
            Optional<WorkflowPlanVersionEntity> latestOpt =
                    versionRepository.findByWorkflowIdAndVersion(workflowId, currentMax);

            if (latestOpt.isPresent()) {
                WorkflowPlanVersionEntity latest = latestOpt.get();

                // Same session → overwrite in-place (no new version number)
                if (sessionId.equals(latest.getLabel())) {
                    if (plansAreEqual(plan, latest.getPlan())) {
                        logger.debug("Session version v{} unchanged for workflow {}, skipping update",
                                currentMax, workflowId);
                        return currentMax;
                    }
                    latest.setPlan(new HashMap<>(plan));
                    versionRepository.save(latest);
                    logger.debug("Updated session version v{} for workflow {} (session={})",
                            currentMax, workflowId, sessionId);
                    return currentMax;
                }

                // Different label but plan unchanged → skip
                if (plansAreEqual(plan, latest.getPlan())) {
                    logger.debug("Plan unchanged for workflow {}, skipping version creation (current: v{})",
                            workflowId, currentMax);
                    return currentMax;
                }
            }
        }

        // Different session (or no versions yet) → create new version with sessionId as label
        return createVersion(workflowId, plan, userId, sessionId);
    }

    /**
     * Compare two plans for equality, ignoring transient fields (tenant_id, timestamps).
     * Uses Jackson for normalized deep comparison.
     */
    public boolean plansAreEqual(Map<String, Object> plan1, Map<String, Object> plan2) {
        if (plan1 == plan2) return true;
        if (plan1 == null || plan2 == null) return false;

        try {
            // Normalize both plans via Jackson to handle type differences (Integer vs Long, etc.)
            var node1 = objectMapper.valueToTree(stripTransientFields(plan1));
            var node2 = objectMapper.valueToTree(stripTransientFields(plan2));
            boolean equal = node1.equals(node2);
            if (!equal) {
                // Log the diff to diagnose spurious version creation
                logPlanDiff(node1, node2);
            }
            return equal;
        } catch (Exception e) {
            logger.warn("Error comparing plans, treating as different: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Log differences between two plan JSON trees to diagnose spurious version creation.
     */
    private void logPlanDiff(com.fasterxml.jackson.databind.JsonNode node1, com.fasterxml.jackson.databind.JsonNode node2) {
        try {
            // Compare top-level keys first
            var it1 = node1.fieldNames();
            var it2 = node2.fieldNames();
            Set<String> keys1 = new LinkedHashSet<>();
            Set<String> keys2 = new LinkedHashSet<>();
            it1.forEachRemaining(keys1::add);
            it2.forEachRemaining(keys2::add);

            // Keys only in one side
            Set<String> onlyIn1 = new LinkedHashSet<>(keys1);
            onlyIn1.removeAll(keys2);
            Set<String> onlyIn2 = new LinkedHashSet<>(keys2);
            onlyIn2.removeAll(keys1);
            if (!onlyIn1.isEmpty()) logger.debug("[PlanDiff] Keys only in canvas: {}", onlyIn1);
            if (!onlyIn2.isEmpty()) logger.debug("[PlanDiff] Keys only in stored: {}", onlyIn2);

            // Common keys with different values
            for (String key : keys1) {
                if (keys2.contains(key)) {
                    var v1 = node1.get(key);
                    var v2 = node2.get(key);
                    if (!v1.equals(v2)) {
                        // For arrays, log size diff and first differing element
                        if (v1.isArray() && v2.isArray()) {
                            if (v1.size() != v2.size()) {
                                logger.debug("[PlanDiff] Key '{}': array size {} vs {}", key, v1.size(), v2.size());
                            } else {
                                for (int i = 0; i < v1.size(); i++) {
                                    if (!v1.get(i).equals(v2.get(i))) {
                                        logger.debug("[PlanDiff] Key '{}[{}]' differs: canvas={} | stored={}",
                                            key, i,
                                            v1.get(i).toString().substring(0, Math.min(300, v1.get(i).toString().length())),
                                            v2.get(i).toString().substring(0, Math.min(300, v2.get(i).toString().length())));
                                        break; // only log first diff
                                    }
                                }
                            }
                        } else {
                            logger.debug("[PlanDiff] Key '{}' differs: canvas={} | stored={}",
                                key,
                                v1.toString().substring(0, Math.min(200, v1.toString().length())),
                                v2.toString().substring(0, Math.min(200, v2.toString().length())));
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("[PlanDiff] Error logging diff: {}", e.getMessage());
        }
    }

    /**
     * Strip fields that should not trigger a new version (cosmetic/transient).
     */
    private Map<String, Object> stripTransientFields(Map<String, Object> plan) {
        Map<String, Object> stripped = new HashMap<>(plan);
        stripped.remove("tenant_id");
        return stripped;
    }

    /**
     * Get the current (latest) version number for a workflow.
     */
    @Transactional(readOnly = true)
    public int getCurrentVersion(UUID workflowId) {
        return versionRepository.getMaxVersion(workflowId).orElse(0);
    }

    /**
     * List all versions for a workflow (metadata only, no plan body).
     */
    @Transactional(readOnly = true)
    public List<WorkflowPlanVersionEntity> listVersions(UUID workflowId) {
        return versionRepository.findByWorkflowIdOrderByVersionDesc(workflowId);
    }

    /**
     * Get a specific version with its full plan.
     */
    @Transactional(readOnly = true)
    public Optional<WorkflowPlanVersionEntity> getVersion(UUID workflowId, int version) {
        return versionRepository.findByWorkflowIdAndVersion(workflowId, version);
    }

    /**
     * Rename a version (set/update label).
     */
    public WorkflowPlanVersionEntity renameVersion(UUID workflowId, int version, String label) {
        WorkflowPlanVersionEntity entity = versionRepository.findByWorkflowIdAndVersion(workflowId, version)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Version " + version + " not found for workflow " + workflowId));

        entity.setLabel(label);
        logger.info("Renamed workflow {} version {} to '{}'", workflowId, version, label);
        return versionRepository.save(entity);
    }

    /**
     * Purge old versions beyond retention limit.
     * Protects the pinned version from deletion (even if it's beyond the retention window).
     */
    private void purgeOldVersions(UUID workflowId) {
        long count = versionRepository.countByWorkflowId(workflowId);
        if (count > maxVersions) {
            Integer pinnedVersion = workflowRepository.findById(workflowId)
                    .map(WorkflowEntity::getPinnedVersion)
                    .orElse(null);

            int deleted;
            if (pinnedVersion != null) {
                deleted = versionRepository.purgeOldVersionsExcluding(workflowId, maxVersions, pinnedVersion);
            } else {
                deleted = versionRepository.purgeOldVersions(workflowId, maxVersions);
            }
            if (deleted > 0) {
                logger.info("Purged {} old version(s) for workflow {}{}", deleted, workflowId,
                        pinnedVersion != null ? " (protected pinned v" + pinnedVersion + ")" : "");
            }
        }
    }

    private long estimatePlanSize(Map<String, Object> plan) {
        if (plan == null || plan.isEmpty()) return 0;
        try {
            return objectMapper.writeValueAsBytes(plan).length;
        } catch (Exception e) {
            return 0;
        }
    }
}
