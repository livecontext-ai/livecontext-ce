package com.apimarketplace.orchestrator.services.resume.state;

import com.apimarketplace.common.storage.service.StorageService;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.WorkflowStepDataEntity;
import com.apimarketplace.orchestrator.domain.workflow.*;
import com.apimarketplace.orchestrator.services.WorkflowRunStatusService;
import com.apimarketplace.orchestrator.services.resume.cache.WorkflowCacheManager;
import com.apimarketplace.orchestrator.services.streaming.state.RunStateStore;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import com.apimarketplace.orchestrator.utils.WorkflowUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Shared helper methods for state reconstruction.
 * Single Responsibility: Common utility methods used across state builders.
 */
public class StateReconstructorHelper {

    private static final Logger logger = LoggerFactory.getLogger(StateReconstructorHelper.class);

    private final StorageService storageService;
    private final WorkflowRunStatusService workflowRunStatusService;
    private final RunStateStore runStateStore;
    private final WorkflowCacheManager cacheManager;

    public StateReconstructorHelper(
            StorageService storageService,
            WorkflowRunStatusService workflowRunStatusService,
            RunStateStore runStateStore,
            WorkflowCacheManager cacheManager) {
        this.storageService = storageService;
        this.workflowRunStatusService = workflowRunStatusService;
        this.runStateStore = runStateStore;
        this.cacheManager = cacheManager;
    }

    /**
     * Determines the status of a step.
     */
    public RunStatus determineStepStatus(
            String stepId,
            Set<String> completedStepIds,
            Set<String> failedStepIds,
            Set<String> skippedStepIds,
            Set<String> readySteps,
            Set<String> awaitingSignalNodeIds) {

        if (completedStepIds.contains(stepId)) return RunStatus.COMPLETED;
        if (failedStepIds.contains(stepId)) return RunStatus.FAILED;
        if (skippedStepIds.contains(stepId)) return RunStatus.SKIPPED;
        if (awaitingSignalNodeIds != null && awaitingSignalNodeIds.contains(stepId)) return RunStatus.AWAITING_SIGNAL;
        if (readySteps.contains(stepId)) return RunStatus.PENDING;
        return RunStatus.PENDING;
    }

    /**
     * Determines the status of a step (legacy overload without awaiting signal).
     */
    public RunStatus determineStepStatus(
            String stepId,
            Set<String> completedStepIds,
            Set<String> failedStepIds,
            Set<String> skippedStepIds,
            Set<String> readySteps) {
        return determineStepStatus(stepId, completedStepIds, failedStepIds, skippedStepIds, readySteps, null);
    }

    /**
     * Determines the status of an edge.
     * An edge is SKIPPED if either source OR destination is skipped.
     */
    public RunStatus determineEdgeStatus(
            String from,
            String to,
            Set<String> completedStepIds,
            Set<String> failedStepIds,
            Set<String> skippedStepIds) {

        String normalizedFrom = WorkflowUtils.normalizeStepIdSafe(from);
        String normalizedTo = WorkflowUtils.normalizeStepIdSafe(to);

        // Also extract raw labels for matching (e.g., "1" from "mcp:1")
        String rawFrom = extractLabel(from);
        String rawTo = extractLabel(to);

        boolean fromCompleted = completedStepIds.contains(normalizedFrom) || completedStepIds.contains(from) || completedStepIds.contains(rawFrom);
        boolean toCompleted = completedStepIds.contains(normalizedTo) || completedStepIds.contains(to) || completedStepIds.contains(rawTo);
        boolean fromSkipped = skippedStepIds.contains(normalizedFrom) || skippedStepIds.contains(from) || skippedStepIds.contains(rawFrom);
        boolean toSkipped = skippedStepIds.contains(normalizedTo) || skippedStepIds.contains(to) || skippedStepIds.contains(rawTo);

        // If source or destination is skipped, edge is completed (branch not taken)
        if (fromSkipped || toSkipped) return RunStatus.COMPLETED;

        if (fromCompleted && toCompleted) return RunStatus.COMPLETED;
        if (fromCompleted) return RunStatus.RUNNING;
        if (failedStepIds.contains(normalizedFrom) || failedStepIds.contains(from) || failedStepIds.contains(rawFrom)) return RunStatus.FAILED;
        return RunStatus.PENDING;
    }

    /**
     * Determines the overall workflow status.
     */
    public RunStatus determineOverallStatus(
            WorkflowRunEntity runEntity,
            Set<String> completedStepIds,
            Set<String> failedStepIds,
            Set<String> readySteps,
            WorkflowPlan plan) {

        // Check database status first - respect special statuses
        RunStatus dbStatus = runEntity.getStatus();
        if (dbStatus == RunStatus.PAUSED) {
            return RunStatus.PAUSED;
        }
        if (dbStatus == RunStatus.WAITING_TRIGGER) {
            // Webhook workflow waiting for trigger - don't override with RUNNING
            return RunStatus.WAITING_TRIGGER;
        }
        if (dbStatus == RunStatus.CANCELLED) {
            return RunStatus.CANCELLED;
        }

        // Respect terminal statuses from DB (e.g., after reusable trigger cycle completes)
        if (dbStatus == RunStatus.COMPLETED || dbStatus == RunStatus.FAILED) {
            return dbStatus;
        }

        // If there are ready steps, workflow can continue
        if (!readySteps.isEmpty()) {
            return RunStatus.RUNNING;
        }

        // Check if all steps are done
        Set<String> allStepIds = getAllStepIds(plan);
        int totalProcessed = completedStepIds.size() + failedStepIds.size();

        if (totalProcessed >= allStepIds.size()) {
            return failedStepIds.isEmpty() ? RunStatus.COMPLETED : RunStatus.FAILED;
        }

        return dbStatus != null ? dbStatus : RunStatus.PENDING;
    }

    /**
     * Gets all step IDs from a plan.
     */
    public Set<String> getAllStepIds(WorkflowPlan plan) {
        Set<String> ids = new HashSet<>();
        for (Step step : plan.getMcps()) {
            ids.add(step.getNormalizedKey());
        }
        for (Trigger trigger : plan.getTriggers()) {
            ids.add(trigger.getNormalizedKey());
        }
        return ids;
    }

    /**
     * Loads step output from storage and enriches it with httpstatus metadata.
     * Uses read-only method to avoid transaction conflicts when called from read-only context.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> loadStepOutput(WorkflowStepDataEntity entity) {
        Map<String, Object> apiOutput = new LinkedHashMap<>();

        // Load API response from storage
        if (entity.getOutputStorageId() != null) {
            try {
                // Use getByIdReadOnly to avoid UPDATE on accessed_at in read-only transactions
                Optional<Object> stored = entity.getOrganizationId() != null && !entity.getOrganizationId().isBlank()
                        ? storageService.getByIdReadOnlyForScope(entity.getOutputStorageId(), entity.getTenantId(), entity.getOrganizationId())
                        : storageService.getByIdReadOnly(entity.getOutputStorageId(), entity.getTenantId());
                if (stored.isPresent() && stored.get() instanceof Map) {
                    apiOutput.putAll((Map<String, Object>) stored.get());
                }
            } catch (Exception e) {
                logger.warn("Failed to load output for step {}: {}", entity.getStepAlias(), e.getMessage());
            }
        }

        // Add httpstatus object with code and error
        Map<String, Object> httpstatus = new LinkedHashMap<>();
        httpstatus.put("code", entity.getHttpStatus() != null ? entity.getHttpStatus() : 200);
        httpstatus.put("error", entity.getErrorMessage() != null ? entity.getErrorMessage() : "");

        // Avoid double wrapping: if storage already has "output" structure, use it directly
        // Storage format from StepPayloadService: {output: {data: [...], ...}}
        if (apiOutput.containsKey("output") && apiOutput.get("output") instanceof Map) {
            Map<String, Object> innerOutput = (Map<String, Object>) apiOutput.get("output");
            innerOutput.put("httpstatus", httpstatus);
            return apiOutput; // Already wrapped, return as-is
        }

        // Otherwise, wrap for expression resolution: {{mcp:label.output.xxx}}
        apiOutput.put("httpstatus", httpstatus);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("output", apiOutput);

        return result;
    }

    /**
     * Load loop state from in-memory RunStateStore or persisted snapshot.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> loadLoopState(String runId, WorkflowRunEntity runEntity) {
        // First, try to get from in-memory RunStateStore (if workflow still running)
        try {
            RunStateStore.RunSnapshot snapshot = runStateStore.snapshot(runId);
            if (snapshot != null && !snapshot.loops().isEmpty()) {
                logger.debug("Loaded loops from in-memory RunStateStore for runId={}: {}",
                    runId, snapshot.loops().size());
                Map<String, Object> loopsMap = new LinkedHashMap<>();
                for (Map<String, Object> loop : snapshot.loops()) {
                    String loopId = (String) loop.get("loopId");
                    if (loopId != null) {
                        loopsMap.put(loopId, loop);
                    }
                }
                return loopsMap;
            }
        } catch (Exception e) {
            logger.debug("Could not load loops from RunStateStore for runId={}: {}",
                runId, e.getMessage());
        }

        // Fallback: Try to get from persisted snapshot in workflow_run_status table
        try {
            if (runEntity.getId() != null) {
                var statusEntity = workflowRunStatusService.findByRunId(runEntity.getId());
                if (statusEntity.isPresent()) {
                    Map<String, Object> payload = statusEntity.get().getPayload();
                    if (payload != null && payload.containsKey("loops")) {
                        Object loopsObj = payload.get("loops");
                        if (loopsObj instanceof Map) {
                            logger.debug("Loaded loops from persisted snapshot for runId={}", runId);
                            return (Map<String, Object>) loopsObj;
                        } else if (loopsObj instanceof List) {
                            // Convert list to map (keyed by loopId)
                            List<Map<String, Object>> loopsList = (List<Map<String, Object>>) loopsObj;
                            Map<String, Object> loopsMap = new LinkedHashMap<>();
                            for (Map<String, Object> loop : loopsList) {
                                String loopId = (String) loop.get("loopId");
                                if (loopId != null) {
                                    loopsMap.put(loopId, loop);
                                }
                            }
                            logger.debug("Loaded loops from persisted snapshot (list form) for runId={}: {}",
                                runId, loopsMap.size());
                            return loopsMap;
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Could not load loops from persisted snapshot for runId={}: {}",
                runId, e.getMessage());
        }

        return new LinkedHashMap<>();
    }

    /**
     * Calculates execution time from entity.
     */
    public long calculateExecutionTime(WorkflowStepDataEntity entity) {
        if (entity.getStartTime() != null && entity.getEndTime() != null) {
            return entity.getEndTime().toEpochMilli() - entity.getStartTime().toEpochMilli();
        }
        return 0;
    }

    /**
     * Normalizes a core node ID to a standard format.
     */
    public String normalizeCoreId(String coreId) {
        if (coreId == null) return null;
        String normalized = LabelNormalizer.extractCoreLabel(coreId);
        return "core:" + (normalized != null ? normalized : coreId);
    }

    /**
     * Checks if a stepKey corresponds to an iterating node (loop or split) in the plan.
     * StepKey can be in format: "core:label", "label", or raw loop ID.
     *
     * Both loop and split nodes have termination semantics that need special handling:
     * - They should only be marked COMPLETED when they have actually terminated (exitReason present)
     * - On rerun, they should be reset to PENDING and respawn their items
     */
    public boolean isLoopKey(String stepKey, WorkflowPlan plan) {
        if (stepKey == null || plan == null || plan.getCores() == null) {
            return false;
        }

        // If it already has core: prefix, verify it's actually a loop/split type
        if (stepKey.startsWith("core:")) {
            for (var core : plan.getCores()) {
                if (!isIteratingCoreType(core.type())) continue;
                String normalizedKey = core.getNormalizedKey();
                if (stepKey.equals(normalizedKey)) {
                    return true;
                }
            }
            return false;
        }

        // Check against all iterating cores (loop and split)
        for (var core : plan.getCores()) {
            if (core.id() == null || !isIteratingCoreType(core.type())) continue;

            // Check by label
            String label = core.label() != null ? core.label() : core.id();
            String normalizedLabel = LabelNormalizer.normalizeLabel(label);
            if (normalizedLabel != null && normalizedLabel.equals(stepKey)) {
                return true;
            }

            // Check by ID
            if (core.id().equals(stepKey)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Checks if a core type is an iterating node type (loop or split).
     * These nodes have special termination semantics and need exitReason checking.
     */
    private boolean isIteratingCoreType(String type) {
        return "loop".equals(type) || "split".equals(type);
    }

    /**
     * Finds the normalized key for a step alias from the workflow plan.
     * The alias stored in DB is the raw label (e.g., "test"), but we need
     * the normalized key (e.g., "trigger:test") for matching in calculateReadySteps.
     */
    public String findNormalizedKeyForAlias(WorkflowPlan plan, String alias) {
        if (alias == null) return null;

        // Check triggers
        for (Trigger trigger : plan.getTriggers()) {
            String label = trigger.label() != null ? trigger.label() : trigger.id();
            String normalizedLabel = LabelNormalizer.normalizeLabel(label);
            if (alias.equals(normalizedLabel) || alias.equals(label) || alias.equals(trigger.id())) {
                return trigger.getNormalizedKey();
            }
        }

        // Check steps
        for (Step step : plan.getMcps()) {
            String label = step.label();
            String normalizedLabel = LabelNormalizer.normalizeLabel(label);
            if (alias.equals(normalizedLabel) || alias.equals(label) || alias.equals(step.id())) {
                return step.getNormalizedKey();
            }
        }

        // Check agents
        for (Agent agent : plan.getAgents()) {
            String label = agent.label();
            String normalizedLabel = LabelNormalizer.normalizeLabel(label);
            if (alias.equals(normalizedLabel) || alias.equals(label) || alias.equals(agent.id())) {
                return agent.getNormalizedKey();
            }
        }

        // Check core nodes
        for (Core core : plan.getCores()) {
            String label = core.label() != null ? core.label() : core.id();
            String normalizedLabel = LabelNormalizer.normalizeLabel(label);
            if (alias.equals(normalizedLabel) || alias.equals(label) || alias.equals(core.id())) {
                return "core:" + (normalizedLabel != null ? normalizedLabel : core.id());
            }
        }

        // Check tables (CRUD operations use "table:" prefix, NOT "mcp:")
        if (plan.getTables() != null) {
            for (Step table : plan.getTables()) {
                String label = table.label();
                String normalizedLabel = LabelNormalizer.normalizeLabel(label);
                if (alias.equals(normalizedLabel) || alias.equals(label) || alias.equals(table.id())) {
                    return LabelNormalizer.tableKey(label);
                }
            }
        }

        // Check interfaces
        if (plan.getInterfaces() != null) {
            for (InterfaceDef iface : plan.getInterfaces()) {
                String label = iface.label();
                String normalizedLabel = LabelNormalizer.normalizeLabel(label);
                if (alias.equals(normalizedLabel) || alias.equals(label) || alias.equals(iface.id())) {
                    return iface.getNormalizedKey();
                }
            }
        }

        // Check notes
        if (plan.getNotes() != null) {
            for (Note note : plan.getNotes()) {
                String label = note.label();
                if (label != null) {
                    String normalizedLabel = LabelNormalizer.normalizeLabel(label);
                    if (alias.equals(normalizedLabel) || alias.equals(label) || alias.equals(note.id())) {
                        return "note:" + normalizedLabel;
                    }
                }
            }
        }

        return null;
    }

    /**
     * Normalize a node ID to ensure consistent format (mcp:xxx, trigger:xxx, loop:xxx, decision:xxx).
     */
    public String normalizeNodeId(String nodeId) {
        if (nodeId == null) {
            return null;
        }

        // Already normalized - includes all known prefixes
        if (LabelNormalizer.isNormalizedKey(nodeId)) {
            return nodeId;
        }

        // Extract label and normalize
        String normalizedLabel = LabelNormalizer.normalizeLabel(nodeId);
        if (normalizedLabel == null) {
            normalizedLabel = nodeId.toLowerCase().replace(" ", "_");
        }

        return "mcp:" + normalizedLabel;
    }

    /**
     * Extract label from a prefixed node ID (e.g., "core:while_loop" -> "while_loop").
     */
    public String extractLabel(String nodeId) {
        if (nodeId == null) {
            return null;
        }
        int colonIndex = nodeId.indexOf(':');
        if (colonIndex >= 0 && colonIndex < nodeId.length() - 1) {
            return nodeId.substring(colonIndex + 1);
        }
        return nodeId;
    }

    /**
     * Checks if a workflow is currently paused.
     */
    public boolean isPaused(String runId, WorkflowRunEntity runEntity) {
        return cacheManager.isPaused(runId) || runEntity.getStatus() == RunStatus.PAUSED;
    }
}
