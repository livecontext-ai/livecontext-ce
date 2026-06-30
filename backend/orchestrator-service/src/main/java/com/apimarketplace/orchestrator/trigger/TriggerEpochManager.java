package com.apimarketplace.orchestrator.trigger;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.streaming.context.RunContextRegistry;
import com.apimarketplace.orchestrator.validation.DAGIndependenceValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Service for managing trigger epochs.
 *
 * An epoch represents one complete execution cycle of a reusable trigger.
 * When a webhook/manual/chat trigger fires, the workflow executes, and upon completion,
 * the epoch is incremented and the run resets to WAITING_TRIGGER for the next cycle.
 *
 * <h2>Epoch Lifecycle</h2>
 * <pre>
 * Epoch 0: Initial state (never triggered)
 * Epoch 1: First trigger → execute workflow → closeEpoch() → WAITING_TRIGGER
 * Epoch 2: Second trigger → execute workflow → closeEpoch() → WAITING_TRIGGER
 * ...
 * </pre>
 *
 * <h2>Data Storage</h2>
 * <ul>
 *   <li><b>workflow_step_data</b> - Historical data with epoch column (accumulates)</li>
 *   <li><b>state_snapshot</b> - Current epoch execution state (reset each epoch)</li>
 * </ul>
 *
 * @see ReusableTriggerService
 * @see RunContextRegistry#closeEpoch(String)
 */
@Service
public class TriggerEpochManager {

    private static final Logger logger = LoggerFactory.getLogger(TriggerEpochManager.class);

    private final WorkflowRunRepository runRepository;
    private final RunContextRegistry runContextRegistry;

    @Autowired(required = false)
    private DAGIndependenceValidator dagIndependenceValidator;

    /**
     * Plan v4 §1.6 - advisory-lock helper. Optional injection: when null,
     * the existing row-lock on runRepository.save below is the correctness
     * backstop (pre-§1.6 semantics).
     */
    @Autowired(required = false)
    private com.apimarketplace.orchestrator.services.state.patch.AdvisoryLockHelper advisoryLockHelper;

    public TriggerEpochManager(
            WorkflowRunRepository runRepository,
            @Lazy RunContextRegistry runContextRegistry) {
        this.runRepository = runRepository;
        this.runContextRegistry = runContextRegistry;
    }

    /**
     * Get current epoch for a run.
     *
     * Epoch 0 = first execution (never triggered yet)
     * Epoch 1 = first trigger completed
     * Epoch N = N triggers completed
     *
     * @param run The workflow run entity
     * @return Current epoch number (0 if never triggered)
     */
    public int getCurrentEpoch(WorkflowRunEntity run) {
        if (run == null) {
            return 0;
        }
        Map<String, Object> metadata = run.getMetadata();
        if (metadata == null) {
            return 0;
        }
        Object epochValue = metadata.get("currentEpoch");
        if (epochValue instanceof Number) {
            return ((Number) epochValue).intValue();
        }
        return 0;
    }

    /**
     * Get current epoch for a specific DAG (trigger) in a run.
     *
     * For multi-DAG workflows, each trigger has its own epoch counter stored in
     * {@code metadata.dagEpochs.{triggerId}}. Falls back to the global
     * {@code currentEpoch} for single-DAG workflows or when dagEpochs is not present.
     *
     * @param run The workflow run entity
     * @param triggerId The trigger ID (e.g., "trigger:my_webhook")
     * @return Current epoch number for this DAG (0 if never triggered)
     */
    /**
     * Delegates to {@link #getGlobalEpochForDag(WorkflowRunEntity, String)} which reads
     * from {@code dagLastEpoch} (the global epoch assigned during the trigger's latest fire).
     *
     * <p>Legacy note: previously read from stale {@code dagEpochs} key which was never written
     * by {@link #incrementEpoch(WorkflowRunEntity, String)}. Fixed to use the same key chain.
     */
    public int getCurrentEpoch(WorkflowRunEntity run, String triggerId) {
        if (run == null || triggerId == null) {
            return getCurrentEpoch(run);
        }
        return getGlobalEpochForDag(run, triggerId);
    }

    /**
     * Get current epoch for a run by its public ID.
     *
     * @param runId The public run ID
     * @return Current epoch number (0 if not found or never triggered)
     */
    public int getCurrentEpoch(String runId) {
        if (runId == null) {
            return 0;
        }
        return runRepository.findByRunIdPublic(runId)
            .map(this::getCurrentEpoch)
            .orElse(0);
    }

    /**
     * Get current epoch for a specific DAG (trigger) by run ID.
     *
     * For multi-DAG workflows, each trigger has its own epoch counter.
     * Falls back to global epoch when triggerId is null or dagEpochs not present.
     *
     * @param runId The public run ID
     * @param triggerId The trigger ID (e.g., "trigger:my_webhook"). If null, falls back to global epoch.
     * @return Current epoch number for this DAG (0 if not found)
     */
    public int getCurrentEpoch(String runId, String triggerId) {
        if (runId == null) {
            return 0;
        }
        if (triggerId == null) {
            return getCurrentEpoch(runId);
        }
        return runRepository.findByRunIdPublic(runId)
            .map(run -> getCurrentEpoch(run, triggerId))
            .orElse(0);
    }

    /**
     * Get the global epoch that was assigned when a specific DAG (trigger) last fired.
     *
     * <p>In multi-DAG workflows, data is persisted using the global {@code currentEpoch}
     * (monotonic +1 per fire), but each DAG has its own fire counter ({@code dagFireCount}).
     * This method returns the global epoch assigned during the trigger's latest fire,
     * stored in {@code metadata.dagLastEpoch.{triggerId}} (or legacy {@code dagGlobalEpoch}).
     *
     * <p>This is critical for context loading: {@code RunContextService.loadRunContext()}
     * filters by epoch, so it must use the same epoch that was used during persistence.
     *
     * @param run The workflow run entity
     * @param triggerId The trigger ID (e.g., "trigger:my_webhook")
     * @return The global epoch for this DAG's latest fire, or falls back to global currentEpoch
     */
    @SuppressWarnings("unchecked")
    public int getGlobalEpochForDag(WorkflowRunEntity run, String triggerId) {
        if (run == null || triggerId == null) {
            return getCurrentEpoch(run);
        }
        Map<String, Object> metadata = run.getMetadata();
        if (metadata == null) {
            return 0;
        }
        // Try new key first: dagLastEpoch
        Object dagLastEpoch = metadata.get("dagLastEpoch");
        if (dagLastEpoch instanceof Map) {
            Object epochValue = ((Map<String, Object>) dagLastEpoch).get(triggerId);
            if (epochValue instanceof Number) {
                return ((Number) epochValue).intValue();
            }
        }
        // Backward compat: try legacy key dagGlobalEpoch
        Object dagGlobalEpoch = metadata.get("dagGlobalEpoch");
        if (dagGlobalEpoch instanceof Map) {
            Object epochValue = ((Map<String, Object>) dagGlobalEpoch).get(triggerId);
            if (epochValue instanceof Number) {
                return ((Number) epochValue).intValue();
            }
        }
        // Fall back to global epoch (backward compat or single-DAG)
        return getCurrentEpoch(run);
    }

    /**
     * Get the global epoch for a specific DAG by run ID.
     *
     * @param runId The public run ID
     * @param triggerId The trigger ID
     * @return The global epoch for this DAG's latest fire
     */
    public int getGlobalEpochForDag(String runId, String triggerId) {
        if (runId == null) {
            return 0;
        }
        if (triggerId == null) {
            return getCurrentEpoch(runId);
        }
        return runRepository.findByRunIdPublic(runId)
            .map(run -> getGlobalEpochForDag(run, triggerId))
            .orElse(0);
    }

    /**
     * Get the current spawn for a specific DAG (trigger).
     *
     * <p>Spawn increments on each rerun within the same epoch.
     * Stored in {@code metadata.dagCurrentSpawn.{triggerId}}.
     *
     * @param run The workflow run entity
     * @param triggerId The trigger ID
     * @return Current spawn (0 if never rerun)
     */
    @SuppressWarnings("unchecked")
    public int getCurrentSpawnForDag(WorkflowRunEntity run, String triggerId) {
        if (run == null || triggerId == null) {
            return 0;
        }
        Map<String, Object> metadata = run.getMetadata();
        if (metadata == null) {
            return 0;
        }
        Object dagCurrentSpawn = metadata.get("dagCurrentSpawn");
        if (dagCurrentSpawn instanceof Map) {
            Object spawnValue = ((Map<String, Object>) dagCurrentSpawn).get(triggerId);
            if (spawnValue instanceof Number) {
                return ((Number) spawnValue).intValue();
            }
        }
        return 0;
    }

    /**
     * Get the current spawn for a specific DAG by run ID.
     *
     * @param runId The public run ID
     * @param triggerId The trigger ID
     * @return Current spawn (0 if never rerun)
     */
    public int getCurrentSpawnForDag(String runId, String triggerId) {
        if (runId == null || triggerId == null) {
            return 0;
        }
        return runRepository.findByRunIdPublic(runId)
            .map(run -> getCurrentSpawnForDag(run, triggerId))
            .orElse(0);
    }

    /**
     * Increment spawn for a specific DAG (trigger) without changing epoch.
     *
     * <p>Used by rerun: keeps the same epoch but creates a new spawn
     * so that re-executed nodes get fresh context coordinates while
     * still being able to see predecessor outputs from earlier spawns.
     *
     * <p>Uses pessimistic locking to prevent concurrent spawn increments.
     *
     * @param run The workflow run entity (a fresh copy is loaded with lock)
     * @param triggerId The trigger ID
     * @return The new spawn number
     */
    @SuppressWarnings("unchecked")
    @Transactional
    public int incrementSpawn(WorkflowRunEntity run, String triggerId) {
        if (run == null) {
            throw new IllegalArgumentException("Run cannot be null");
        }
        if (triggerId == null) {
            throw new IllegalArgumentException("TriggerId cannot be null for spawn increment");
        }

        String runId = run.getRunIdPublic();

        // Re-load with pessimistic lock
        WorkflowRunEntity lockedRun = runRepository.findByRunIdPublicForUpdate(runId)
            .orElseThrow(() -> new IllegalStateException(
                "Run not found for spawn increment: " + runId));

        Map<String, Object> metadata = lockedRun.getMetadata();
        if (metadata == null) {
            metadata = new HashMap<>();
        } else {
            metadata = new HashMap<>(metadata);
        }

        // Get or create dagCurrentSpawn map
        Map<String, Object> dagCurrentSpawn;
        Object existing = metadata.get("dagCurrentSpawn");
        if (existing instanceof Map) {
            dagCurrentSpawn = new HashMap<>((Map<String, Object>) existing);
        } else {
            dagCurrentSpawn = new HashMap<>();
        }

        // Increment spawn for this trigger
        int currentSpawn = 0;
        Object spawnValue = dagCurrentSpawn.get(triggerId);
        if (spawnValue instanceof Number) {
            currentSpawn = ((Number) spawnValue).intValue();
        }
        int newSpawn = currentSpawn + 1;
        dagCurrentSpawn.put(triggerId, newSpawn);
        metadata.put("dagCurrentSpawn", dagCurrentSpawn);

        lockedRun.setMetadata(metadata);
        runRepository.save(lockedRun);

        logger.info("[TriggerEpochManager] Incremented spawn from {} to {} for triggerId={}, runId={}",
                   currentSpawn, newSpawn, triggerId, runId);

        return newSpawn;
    }

    /**
     * Increment the epoch counter for a run.
     *
     * This should be called after a trigger cycle completes successfully.
     * The epoch is stored in the run's metadata.
     *
     * @param run The workflow run entity (will be modified and saved)
     * @return The new epoch number
     */
    @Transactional
    @com.apimarketplace.orchestrator.services.state.patch.AdvisoryLockHolding
    public int incrementEpoch(WorkflowRunEntity run) {
        if (run == null) {
            throw new IllegalArgumentException("Run cannot be null");
        }
        // Plan v4 §1.6
        if (advisoryLockHelper != null) advisoryLockHelper.acquireForRun(run.getRunIdPublic());

        int currentEpoch = getCurrentEpoch(run);
        int newEpoch = currentEpoch + 1;

        Map<String, Object> metadata = run.getMetadata();
        if (metadata == null) {
            metadata = new HashMap<>();
        } else {
            // Create a mutable copy
            metadata = new HashMap<>(metadata);
        }

        metadata.put("currentEpoch", newEpoch);
        metadata.put("triggerCount", newEpoch); // Alias for webhook count
        run.setMetadata(metadata);
        runRepository.save(run);

        logger.info("[TriggerEpochManager] Incremented epoch from {} to {} for runId={}",
                   currentEpoch, newEpoch, run.getRunIdPublic());

        return newEpoch;
    }

    /**
     * Increment the epoch counter for a specific DAG (trigger) in a run.
     *
     * W3 fix: Re-loads the entity with SELECT FOR UPDATE to prevent concurrent
     * DAG epoch increments from overwriting each other's JSONB metadata changes.
     *
     * Stores per-DAG epochs in {@code metadata.dagEpochs.{triggerId}} and also
     * updates the global {@code currentEpoch} to the maximum across all DAGs
     * (for backward compatibility).
     *
     * @param run The workflow run entity (the entity itself is NOT modified; a fresh
     *            copy is loaded with pessimistic lock)
     * @param triggerId The trigger ID (e.g., "trigger:my_webhook")
     * @return The new GLOBAL epoch (monotonic, unique across all DAGs)
     */
    @SuppressWarnings("unchecked")
    @Transactional
    @com.apimarketplace.orchestrator.services.state.patch.AdvisoryLockHolding
    public int incrementEpoch(WorkflowRunEntity run, String triggerId) {
        if (run == null) {
            throw new IllegalArgumentException("Run cannot be null");
        }
        if (triggerId == null) {
            // Fallback to global epoch for single-DAG workflows
            // (the 1-arg overload also acquires the advisory lock)
            return incrementEpoch(run);
        }
        // Plan v4 §1.6
        if (advisoryLockHelper != null) advisoryLockHelper.acquireForRun(run.getRunIdPublic());

        String runId = run.getRunIdPublic();

        // W3 fix: Re-load entity with SELECT FOR UPDATE to prevent concurrent JSONB overwrites
        WorkflowRunEntity lockedRun = runRepository.findByRunIdPublicForUpdate(runId)
            .orElseThrow(() -> new IllegalStateException(
                "Run not found for epoch increment: " + runId));

        Map<String, Object> metadata = lockedRun.getMetadata();
        if (metadata == null) {
            metadata = new HashMap<>();
        } else {
            metadata = new HashMap<>(metadata);
        }

        // Get or create dagFireCount map (replaces legacy dagEpochs)
        Map<String, Object> dagFireCount;
        Object existingDagFireCount = metadata.get("dagFireCount");
        if (existingDagFireCount instanceof Map) {
            dagFireCount = new HashMap<>((Map<String, Object>) existingDagFireCount);
        } else {
            // Backward compat: migrate from dagEpochs if present
            Object legacyDagEpochs = metadata.get("dagEpochs");
            if (legacyDagEpochs instanceof Map) {
                dagFireCount = new HashMap<>((Map<String, Object>) legacyDagEpochs);
            } else {
                dagFireCount = new HashMap<>();
            }
        }

        // Increment per-DAG fire count
        int currentFireCount = 0;
        Object fireCountValue = dagFireCount.get(triggerId);
        if (fireCountValue instanceof Number) {
            currentFireCount = ((Number) fireCountValue).intValue();
        }
        int newFireCount = currentFireCount + 1;
        dagFireCount.put(triggerId, newFireCount);
        metadata.put("dagFireCount", dagFireCount);

        // Update global currentEpoch: always increment by 1 to ensure uniqueness.
        // Monotonic +1 guarantees every fire gets a unique global epoch for step_data persistence.
        int previousGlobal = getCurrentEpoch(lockedRun);
        int newGlobal = previousGlobal + 1;
        metadata.put("currentEpoch", newGlobal);
        metadata.put("triggerCount", newGlobal);

        // Store the mapping triggerId → globalEpoch for this fire (dagLastEpoch replaces dagGlobalEpoch).
        // Persistence uses globalEpoch, so context loading must also use globalEpoch.
        Map<String, Object> dagLastEpoch;
        Object existingDagLastEpoch = metadata.get("dagLastEpoch");
        if (existingDagLastEpoch instanceof Map) {
            dagLastEpoch = new HashMap<>((Map<String, Object>) existingDagLastEpoch);
        } else {
            // Backward compat: migrate from dagGlobalEpoch if present
            Object legacyDagGlobal = metadata.get("dagGlobalEpoch");
            if (legacyDagGlobal instanceof Map) {
                dagLastEpoch = new HashMap<>((Map<String, Object>) legacyDagGlobal);
            } else {
                dagLastEpoch = new HashMap<>();
            }
        }
        dagLastEpoch.put(triggerId, newGlobal);
        metadata.put("dagLastEpoch", dagLastEpoch);

        // Reset spawn to 0 for this trigger (new epoch = fresh spawn counter)
        Map<String, Object> dagCurrentSpawn;
        Object existingSpawn = metadata.get("dagCurrentSpawn");
        if (existingSpawn instanceof Map) {
            dagCurrentSpawn = new HashMap<>((Map<String, Object>) existingSpawn);
        } else {
            dagCurrentSpawn = new HashMap<>();
        }
        dagCurrentSpawn.put(triggerId, 0);
        metadata.put("dagCurrentSpawn", dagCurrentSpawn);
        lockedRun.setMetadata(metadata);
        runRepository.save(lockedRun);

        logger.info("[TriggerEpochManager] Incremented DAG fireCount from {} to {} for triggerId={}, runId={}, globalEpoch={}, spawn reset to 0",
                   currentFireCount, newFireCount, triggerId, runId, newGlobal);

        // Return the GLOBAL epoch (not the per-DAG fire count) so callers can use it
        // for openEpoch(), signal registration, and data persistence - all of which
        // require the monotonic global epoch for correct isolation.
        return newGlobal;
    }

    /**
     * Reset epoch using the rerun pattern (same as StepRerunService).
     *
     * <p>This is the preferred method for STEP_BY_STEP mode as it:
     * <ul>
     *   <li>Uses the same pattern as node rerun (consistency)</li>
     *   <li>Explicitly marks the trigger as READY (no recalculation needed)</li>
     *   <li>Resets all execution state in StateSnapshot</li>
     *   <li>Clears all in-memory caches</li>
     * </ul>
     *
     * @param runId The workflow run ID
     * @param plan The workflow plan (to extract all node IDs)
     * @param triggerId The trigger to mark as ready
     */
    public void resetWithRerunPattern(String runId, WorkflowPlan plan, String triggerId) {
        if (runId == null || plan == null || triggerId == null) {
            logger.warn("[TriggerEpochManager] Cannot reset with rerun pattern: runId={}, triggerId={}", runId, triggerId);
            return;
        }

        logger.info("[TriggerEpochManager] Resetting epoch (rerun pattern): runId={}, triggerId={}", runId, triggerId);

        // Collect all node IDs from the plan
        Set<String> allNodeIds = collectAllNodeIds(plan);

        // Delegate to RunContextRegistry with the rerun pattern
        runContextRegistry.closeEpochWithRerun(runId, allNodeIds, triggerId);

        logger.info("[TriggerEpochManager] Epoch reset (rerun pattern): runId={}, triggerId={}, nodeCount={}",
            runId, triggerId, allNodeIds.size());
    }

    /**
     * Collect all node IDs from a WorkflowPlan.
     */
    private Set<String> collectAllNodeIds(WorkflowPlan plan) {
        Set<String> allNodes = new HashSet<>();

        // Triggers
        if (plan.getTriggers() != null) {
            for (var trigger : plan.getTriggers()) {
                allNodes.add(trigger.getNormalizedKey());
            }
        }

        // MCPs (steps)
        if (plan.getMcps() != null) {
            for (var step : plan.getMcps()) {
                allNodes.add(step.getNormalizedKey());
            }
        }

        // Agents
        if (plan.getAgents() != null) {
            for (var agent : plan.getAgents()) {
                allNodes.add(agent.getNormalizedKey());
            }
        }

        // Cores (decision, loop, split, merge, etc.)
        if (plan.getCores() != null) {
            for (var core : plan.getCores()) {
                allNodes.add(core.getNormalizedKey());
            }
        }

        // Tables
        if (plan.getTables() != null) {
            for (var table : plan.getTables()) {
                allNodes.add(table.getNormalizedKey());
            }
        }

        // Interfaces
        if (plan.getInterfaces() != null) {
            for (var iface : plan.getInterfaces()) {
                allNodes.add(iface.getNormalizedKey());
            }
        }

        return allNodes;
    }

    /**
     * Reset epoch for a specific DAG using the rerun pattern.
     *
     * <p>This is the preferred method for DAG-specific resets as it:
     * <ul>
     *   <li>Uses the same pattern as node rerun (consistency)</li>
     *   <li>Explicitly marks the trigger as READY</li>
     *   <li>Only resets nodes belonging to this DAG</li>
     * </ul>
     *
     * @param runId The workflow run ID
     * @param plan The workflow plan (to extract DAG nodes)
     * @param triggerId The trigger to mark as ready
     */
    public void resetDagWithRerunPattern(String runId, WorkflowPlan plan, String triggerId) {
        if (runId == null || plan == null || triggerId == null) {
            logger.warn("[TriggerEpochManager] Cannot reset DAG with rerun pattern: runId={}, triggerId={}", runId, triggerId);
            return;
        }

        logger.info("[TriggerEpochManager] Resetting DAG epoch (rerun pattern): runId={}, triggerId={}", runId, triggerId);

        // Use per-DAG reset via triggerId (no BFS node collection needed with v3 snapshot)
        int currentEpoch = getCurrentEpoch(runId, triggerId);
        int nextEpoch = currentEpoch + 1;
        runContextRegistry.closeEpochForDagByTriggerId(runId, triggerId, nextEpoch);

        logger.info("[TriggerEpochManager] DAG epoch reset (rerun pattern): runId={}, triggerId={}, advancedToEpoch={}",
            runId, triggerId, nextEpoch);
    }

}
