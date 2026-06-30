package com.apimarketplace.orchestrator.services;

import com.apimarketplace.orchestrator.domain.workflow.*;
import com.apimarketplace.orchestrator.services.interfaces.ExecutionEventPublisher;
import com.apimarketplace.orchestrator.services.completion.StepCompletionOrchestrator;
import com.apimarketplace.orchestrator.services.streaming.*;
import com.apimarketplace.orchestrator.services.streaming.bus.WorkflowEventPublisher;
import com.apimarketplace.orchestrator.services.streaming.context.RunContextRegistry;
import com.apimarketplace.orchestrator.services.streaming.emitter.StreamingBatchScheduler;
import com.apimarketplace.orchestrator.services.streaming.redis.WorkflowRedisPublisher;
import com.apimarketplace.orchestrator.services.streaming.state.RunStateStore;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import java.util.*;

/**
 * Service de streaming unifie et simplifie.
 * Acts as a FACADE that orchestrates events for workflow execution,
 * publishing via Redis for WebSocket distribution.
 *
 * <p>Delegates to specialized services:
 * <ul>
 *   <li>{@link WorkflowEventEmitter} - Workflow status/configuration/statistics events</li>
 *   <li>{@link ItemContextEnricher} - Item context metadata enrichment</li>
 *   <li>{@link InitialSnapshotRegistrar} - Initial snapshot registration</li>
 *   <li>{@link LabelExtractor} - Label extraction for DB queries</li>
 *   <li>{@link SnapshotCacheService} - Snapshot caching for completed runs</li>
 *   <li>{@link LoopEventPublisher} - Loop aggregate event publishing</li>
 *   <li>{@link StepByStepEventService} - Pause/resume/rerun events</li>
 * </ul>
 */
@Service
public class WorkflowStreamingService implements ExecutionEventPublisher {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowStreamingService.class);

    private final WorkflowEventPublisher eventPublisher;
    private final RunStateStore runStateStore;
    private final SnapshotCacheService snapshotCacheService;
    private final LoopEventPublisher loopEventPublisher;
    private final StepByStepEventService stepByStepEventService;
    private final WorkflowEventEmitter workflowEventEmitter;
    private final ItemContextEnricher itemContextEnricher;
    private final InitialSnapshotRegistrar snapshotRegistrar;
    private final LabelExtractor labelExtractor;
    private final RunContextRegistry contextRegistry;

    private StreamingBatchScheduler batchScheduler;

    @Autowired
    private StepCompletionOrchestrator stepCompletionOrchestrator;

    @Autowired(required = false)
    private WorkflowRedisPublisher redisPublisher;

    @Autowired
    private StepEventBuilder stepEventBuilder;

    @Autowired
    private NodeEventEmitterService nodeEventEmitterService;

    /**
     * Phase A2 (archi-refoundation 2026-05-04) - sequencer for batch-update,
     * workflowError, workflow_statistics events that bypass
     * {@link WorkflowEventPublisher} (audit B v6 T1: 3-site chokepoint, not 1).
     * required-false to keep tests bootable; production wiring always provides one.
     */
    @Autowired(required = false)
    private WsEventSequencer wsEventSequencer;

    /**
     * Multi-trigger ordering guard (2026-05-05) - see
     * {@link SeqPublishLockStripes}. Shared across the 3 publish chokepoints
     * so the same runId always serializes through the same stripe.
     */
    @Autowired(required = false)
    private SeqPublishLockStripes seqPublishLockStripes;

    public WorkflowStreamingService(
            WorkflowEventPublisher eventPublisher,
            RunStateStore runStateStore,
            SnapshotCacheService snapshotCacheService,
            LoopEventPublisher loopEventPublisher,
            StepByStepEventService stepByStepEventService,
            WorkflowEventEmitter workflowEventEmitter,
            ItemContextEnricher itemContextEnricher,
            InitialSnapshotRegistrar snapshotRegistrar,
            LabelExtractor labelExtractor,
            @Lazy RunContextRegistry contextRegistry) {
        this.eventPublisher = eventPublisher;
        this.runStateStore = runStateStore;
        this.snapshotCacheService = snapshotCacheService;
        this.loopEventPublisher = loopEventPublisher;
        this.stepByStepEventService = stepByStepEventService;
        this.workflowEventEmitter = workflowEventEmitter;
        this.itemContextEnricher = itemContextEnricher;
        this.snapshotRegistrar = snapshotRegistrar;
        this.labelExtractor = labelExtractor;
        this.contextRegistry = contextRegistry;
    }

    @Autowired(required = false)
    public void setBatchScheduler(@Lazy StreamingBatchScheduler batchScheduler) {
        this.batchScheduler = batchScheduler;
    }

    /**
     * Initializes streaming for an execution.
     */
    public void initializeStreaming(WorkflowExecution execution) {
        logger.info("Initializing streaming for execution: {}", execution.getRunId());

        try {
            sendWorkflowStatusEvent(execution, "RUNNING", "Workflow started");
            sendWorkflowConfigurationEvent(execution);
            snapshotRegistrar.registerInitialSnapshots(execution);
        } catch (Exception e) {
            logger.warn("Error initializing streaming: {}", e.getMessage());
        }
    }

    /**
     * Finalizes streaming for an execution.
     *
     * <p>IMPORTANT: Caches the final snapshot to Redis BEFORE closing the context.
     * This enables reconnection to get data from cache instead of DB.
     *
     * <p>Uses {@link RunContextRegistry#close(String)} for unified cleanup:
     * one call closes everything (state, events, etc.).
     */
    public void finalizeStreaming(WorkflowExecution execution) {
        logger.info("Finalizing streaming for execution: {}", execution.getRunId());

        String runId = execution.getRunId();
        if (workflowEventEmitter.isFinalized(runId)) {
            logger.debug("Execution {} already finalized, skipping duplicate finalization", runId);
            contextRegistry.close(runId);
            return;
        }

        try {
            String status = execution.getStatus().getValue().toUpperCase();
            String message = execution.isCompleted() ? "Workflow completed successfully" :
                execution.isFailed() ? "Workflow failed: " + execution.getErrorMessage() :
                "Workflow finished";

            sendWorkflowStatisticsEvent(execution);
            sendWorkflowStatusEvent(execution, status, message);
            workflowEventEmitter.markFinalized(runId);

            // Note: sendWorkflowStatusEvent() already publishes to Redis via sendEvent().
            // No separate Redis publish needed here - the enriched payload from
            // WorkflowRedisPublisher includes type and runId for frontend routing.

            // Cache final snapshot to Redis BEFORE closing context
            cacheSnapshotToRedis(runId, status);

            // ONE call cleans up EVERYTHING: RunState, NodeEvents, InterfaceEvents, etc.
            contextRegistry.close(runId);
            logger.info("RunContext closed for runId: {}", runId);

        } catch (Exception e) {
            logger.warn("Error finalizing streaming: {}", e.getMessage());
        }
    }

    /**
     * Caches the final snapshot to Redis before context is closed.
     * This allows reconnection to retrieve data from cache instead of DB.
     *
     * <p>Note: Memory cleanup is handled by RunContextRegistry.close()
     */
    private void cacheSnapshotToRedis(String runId, String status) {
        if (runId == null || batchScheduler == null) {
            return;
        }
        try {
            Map<String, Object> snapshot = batchScheduler.snapshotForRun(runId);
            if (snapshot != null && !snapshot.isEmpty()) {
                snapshotCacheService.cacheSnapshot(runId, snapshot, status);
                logger.info("📦 Cached final snapshot to Redis for runId: {} (status: {})", runId, status);
            } else {
                logger.debug("No snapshot to cache for runId: {}", runId);
            }
            // Note: lastPayloads cleanup is now handled by RunContextRegistry.close()
        } catch (Exception e) {
            logger.warn("Failed to cache snapshot to Redis for runId {}: {}", runId, e.getMessage());
        }
    }

    /**
     * Sends a workflow status event.
     */
    public void sendWorkflowStatusEvent(WorkflowExecution execution, String status, String message) {
        Map<String, Object> eventData = workflowEventEmitter.buildStatusEventData(execution, status, message);
        workflowEventEmitter.emitStatusEvent(execution, status, message, eventData);
        sendEvent(execution.getRunId(), "workflowStatus", eventData);
    }

    /**
     * Sends a workflow configuration event.
     */
    public void sendWorkflowConfigurationEvent(WorkflowExecution execution) {
        Map<String, Object> eventData = workflowEventEmitter.buildConfigurationEventData(execution);
        sendEvent(execution.getRunId(), "workflowConfiguration", eventData);
    }

    /**
     * Sends a step execution event.
     * DELEGATES TO {@link StepCompletionOrchestrator} for DB persistence, in-memory state, and streaming emission.
     */
    public void sendStepEvent(WorkflowExecution execution, String stepId, StepExecutionResult result) {
        sendStepEvent(execution, stepId, stepId, stepId, result);
    }

    /**
     * Sends a step execution event with differentiation between node ID and logical alias.
     */
    public void sendStepEvent(WorkflowExecution execution, String stepId, String stepAlias, String normalizedStepId,
                              StepExecutionResult result) {
        StepExecutionResult eventResult = itemContextEnricher.enrichWithItemContext(execution, result);
        Integer itemIndex = itemContextEnricher.extractIntegerFromOutput(eventResult, "item_index", "itemIndex", "absoluteIndex");
        Integer iteration = itemContextEnricher.extractIntegerFromOutput(eventResult, "currentIteration", "iteration", "loopIteration");

        String normalizedIdToEmit = normalizedStepId != null ? normalizedStepId : (stepAlias != null ? stepAlias : stepId);
        String eventNormalizedId = loopEventPublisher.normalizeLoopItemScopedStepId(normalizedIdToEmit);
        String nodeLabel = LabelNormalizer.extractLabel(eventNormalizedId != null ? eventNormalizedId : normalizedIdToEmit);

        // Handle loop aggregate events
        String iterationStepId = resolveLoopIterationStepId(eventNormalizedId, eventResult);
        if (iterationStepId != null) {
            loopEventPublisher.publishLoopAggregateEvent(execution, iterationStepId);
            return;
        }

        // Delegate to StepCompletionOrchestrator
        stepCompletionOrchestrator.completeStep(
            execution,
            eventNormalizedId != null ? eventNormalizedId : stepId,
            nodeLabel,
            eventResult,
            itemIndex,
            iteration
        );
    }

    /**
     * Sends a step event WITHOUT persisting to database.
     * Used for decision nodes that are already persisted via recordDecisionEvaluation.
     */
    public void sendStepEventWithoutPersistence(WorkflowExecution execution, String stepId, String stepAlias,
                                                 String normalizedStepId, StepExecutionResult result) {
        StepExecutionResult eventResult = itemContextEnricher.enrichWithItemContext(execution, result);
        String aliasToEmit = stepAlias != null ? stepAlias : stepId;
        String normalizedIdToEmit = normalizedStepId != null ? normalizedStepId : aliasToEmit;

        String eventStepId = loopEventPublisher.normalizeLoopItemScopedStepId(stepId);
        String eventAlias = loopEventPublisher.normalizeLoopItemScopedStepId(aliasToEmit);
        String eventNormalizedId = loopEventPublisher.normalizeLoopItemScopedStepId(normalizedIdToEmit);

        String nodeLabel = labelExtractor.extractLabelForDbQuery(execution, stepId, aliasToEmit);
        Integer itemIndex = itemContextEnricher.extractIntegerFromOutput(eventResult, "item_index", "itemIndex", "absoluteIndex");
        Integer iteration = itemContextEnricher.extractIntegerFromOutput(eventResult, "currentIteration", "iteration", "loopIteration");

        // Update in-memory state
        if (nodeEventEmitterService != null) {
            nodeEventEmitterService.recordStepExecution(execution, nodeLabel, eventResult, itemIndex, iteration);
        }
        Map<String, Object> statusCounts = nodeEventEmitterService != null
            ? nodeEventEmitterService.getStatusCounts(execution.getRunId(), nodeLabel)
            : null;

        Map<String, Object> eventData = stepEventBuilder.build(
            execution,
            eventStepId != null ? eventStepId : stepId,
            eventAlias != null ? eventAlias : aliasToEmit,
            eventNormalizedId != null ? eventNormalizedId : normalizedIdToEmit,
            eventResult,
            statusCounts
        );

        String iterationStepId = resolveLoopIterationStepId(eventNormalizedId, eventResult);
        if (iterationStepId != null) {
            loopEventPublisher.publishLoopAggregateEvent(execution, iterationStepId);
            return;
        }

        if (eventResult.error() != null) {
            eventData.put("error", eventResult.error().getMessage());
            eventData.put("errorType", eventResult.error().getClass().getSimpleName());
        }

        eventPublisher.emitStep(execution.getRunId(), eventNormalizedId, eventData);
    }

    public void sendStepEventWithoutPersistence(WorkflowExecution execution, String stepId, StepExecutionResult result) {
        sendStepEventWithoutPersistence(execution, stepId, stepId, stepId, result);
    }

    public void sendBatchUpdate(String runId, Map<String, Object> payload) {
        sendEvent(runId, "batch-update", payload);
    }

    /**
     * Publishes a snapshot directly via Redis.
     * Called by SnapshotService after each significant event.
     *
     * This replaces the batch scheduler approach - snapshots are published
     * immediately by the execution thread, not by a separate scheduler.
     *
     * @param runId The workflow run ID
     * @param snapshot The snapshot data built from DB
     */
    public void sendDirectSnapshot(String runId, Map<String, Object> snapshot) {
        if (runId == null || snapshot == null) {
            return;
        }
        sendEvent(runId, "batch-update", snapshot);
    }

    public void sendErrorEvent(WorkflowExecution execution, Exception error) {
        Map<String, Object> eventData = workflowEventEmitter.buildErrorEventData(execution, error);
        sendEvent(execution.getRunId(), "workflowError", eventData);
    }

    public void sendWorkflowStatisticsEvent(WorkflowExecution execution) {
        Map<String, Object> eventData = workflowEventEmitter.buildStatisticsEventData(execution);
        workflowEventEmitter.emitStatisticsEvent(execution, eventData);
        sendEvent(execution.getRunId(), "workflow_statistics", eventData);
    }

    private void sendEvent(String runId, String eventType, Map<String, Object> eventData) {
        if (redisPublisher == null) {
            logger.warn("No Redis publisher for runId: {}. Event {} not sent", runId, eventType);
            return;
        }
        // Per-runId lock keeps nextSeq → publishEvent atomic (see SeqPublishLockStripes).
        Runnable publishAction = () -> {
            try {
                // Phase A2: stamp seq when sequencer is wired (production), else publish without
                long seq = (wsEventSequencer != null) ? wsEventSequencer.nextSeq(runId) : -1L;
                redisPublisher.publishEvent(runId, eventType, eventData, seq);
                logger.debug("Event published: {} for runId: {} seq={}", eventType, runId, seq);
            } catch (Exception e) {
                logger.warn("Error publishing event {} for runId {}: {}", eventType, runId, e.getMessage());
            }
        };
        if (seqPublishLockStripes != null) {
            seqPublishLockStripes.withRunIdLock(runId, publishAction);
        } else {
            publishAction.run();
        }
    }

    private String resolveLoopIterationStepId(String normalizedStepId, StepExecutionResult result) {
        if (loopEventPublisher.shouldSuppressLoopIterationEvent(normalizedStepId)) {
            return normalizedStepId;
        }
        if (result == null || result.stepId() == null) {
            return null;
        }
        String resultStepId = result.stepId();
        if (!loopEventPublisher.isLoopIterationStep(resultStepId)) {
            return null;
        }
        return loopEventPublisher.normalizeLoopItemScopedStepId(resultStepId);
    }

    // Step-by-step execution event methods - delegated to StepByStepEventService

    public void sendWorkflowStatusEvent(WorkflowExecution execution, RunStatus status, String message) {
        sendWorkflowStatusEvent(execution, status.getValue(), message);
    }

    public void sendReadyStepsEvent(WorkflowExecution execution, Set<String> readySteps) {
        stepByStepEventService.sendReadyStepsEvent(execution, readySteps);
    }

    public void sendPauseEvent(WorkflowExecution execution, Set<String> readySteps) {
        stepByStepEventService.sendPauseEvent(execution, readySteps);
    }

    public void sendRerunEvent(WorkflowExecution execution, String stepId, Set<String> resetSteps, int newEpoch) {
        stepByStepEventService.sendRerunEvent(execution, stepId, resetSteps, newEpoch);
    }

    public void sendResumeEvent(WorkflowExecution execution) {
        stepByStepEventService.sendResumeEvent(execution);
    }

    public void sendDecisionEvaluatedEvent(WorkflowExecution execution,
                                           String coreId,
                                           String selectedBranch,
                                           Set<String> skippedBranches,
                                           List<?> evaluations) {
        stepByStepEventService.sendDecisionEvaluatedEvent(execution, coreId, selectedBranch, skippedBranches, evaluations);
    }
}
