package com.apimarketplace.orchestrator.services.streaming;

import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.services.streaming.redis.WorkflowRedisPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Service for step-by-step execution mode events.
 * Handles pause, resume, rerun, ready steps, and decision evaluation events.
 *
 * Events are published to Redis for delivery to WebSocket clients via the Gateway.
 */
@Service
public class StepByStepEventService {

    private static final Logger logger = LoggerFactory.getLogger(StepByStepEventService.class);

    private final WorkflowRedisPublisher redisPublisher;

    /**
     * Phase A2 (archi-refoundation 2026-05-04) - sequencer for SBS-emitted
     * readySteps / workflowStatus / stepRerun / decisionEvaluated events that
     * bypass {@link WorkflowEventPublisher} (audit B v6 T1).
     * required-false to keep tests bootable.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.apimarketplace.orchestrator.services.streaming.WsEventSequencer wsEventSequencer;

    /**
     * Multi-trigger ordering guard (2026-05-05) - see
     * {@link SeqPublishLockStripes}.
     */
    @Autowired(required = false)
    private SeqPublishLockStripes seqPublishLockStripes;

    @Autowired
    public StepByStepEventService(@Autowired(required = false) WorkflowRedisPublisher redisPublisher) {
        this.redisPublisher = redisPublisher;
    }

    public void sendReadyStepsEvent(WorkflowExecution execution, Set<String> readySteps) {
        sendReadyStepsEvent(execution, readySteps, null);
    }

    public void sendReadyStepsEvent(WorkflowExecution execution, Set<String> readySteps, StateSnapshot snapshot) {
        if (execution == null || readySteps == null) return;

        Map<String, Object> eventData = new HashMap<>();
        eventData.put("type", "readySteps");
        eventData.put("runId", execution.getRunId());
        eventData.put("readySteps", new ArrayList<>(readySteps));
        eventData.put("timestamp", System.currentTimeMillis());

        // Include authoritative step ID sets from StateSnapshot so the frontend
        // gets a coherent view immediately (without waiting for debounced batch-update).
        if (snapshot != null) {
            eventData.put("completedStepIds", new ArrayList<>(snapshot.getCompletedNodeIds()));
            eventData.put("failedStepIds", new ArrayList<>(snapshot.getFailedNodeIds()));
            eventData.put("skippedStepIds", new ArrayList<>(snapshot.getSkippedNodeIds()));
        }

        publishEvent(execution.getRunId(), "readySteps", eventData);
        logger.info("Sent ready steps event for runId: {} - {} steps ready",
            execution.getRunId(), readySteps.size());
    }

    public void sendPauseEvent(WorkflowExecution execution, Set<String> readySteps) {
        if (execution == null) return;

        Map<String, Object> eventData = new HashMap<>();
        eventData.put("type", "workflowPaused");
        eventData.put("runId", execution.getRunId());
        eventData.put("status", "PAUSED");
        eventData.put("readySteps", readySteps != null ? new ArrayList<>(readySteps) : List.of());
        eventData.put("timestamp", System.currentTimeMillis());
        eventData.put("message", "Workflow paused - waiting for user action");

        publishEvent(execution.getRunId(), "workflowStatus", eventData);
        logger.info("Sent pause event for runId: {}", execution.getRunId());
    }

    public void sendResumeEvent(WorkflowExecution execution) {
        if (execution == null) return;

        Map<String, Object> eventData = new HashMap<>();
        eventData.put("type", "workflowResuming");
        eventData.put("runId", execution.getRunId());
        eventData.put("status", "RESUMING");
        eventData.put("timestamp", System.currentTimeMillis());
        eventData.put("message", "Workflow resuming");

        publishEvent(execution.getRunId(), "workflowStatus", eventData);
        logger.info("Sent resume event for runId: {}", execution.getRunId());
    }

    public void sendRerunEvent(WorkflowExecution execution, String stepId, Set<String> resetSteps, int newEpoch) {
        if (execution == null || stepId == null) return;

        Map<String, Object> eventData = new HashMap<>();
        eventData.put("type", "stepRerun");
        eventData.put("runId", execution.getRunId());
        eventData.put("stepId", stepId);
        eventData.put("resetSteps", resetSteps != null ? new ArrayList<>(resetSteps) : List.of());
        eventData.put("epoch", newEpoch);
        eventData.put("timestamp", System.currentTimeMillis());
        eventData.put("message", "Re-running from step: " + stepId);

        publishEvent(execution.getRunId(), "stepRerun", eventData);
        logger.info("Sent re-run event for runId: {}, stepId: {}, epoch: {}, reset {} steps",
            execution.getRunId(), stepId, newEpoch, resetSteps != null ? resetSteps.size() : 0);
    }

    public void sendDecisionEvaluatedEvent(
            WorkflowExecution execution,
            String coreId,
            String selectedBranch,
            Set<String> skippedBranches,
            List<?> evaluations) {

        if (execution == null) return;

        Map<String, Object> eventData = new HashMap<>();
        eventData.put("type", "decisionEvaluated");
        eventData.put("runId", execution.getRunId());
        eventData.put("coreId", coreId);
        eventData.put("selectedBranch", selectedBranch);
        eventData.put("skippedBranches", skippedBranches != null ? new ArrayList<>(skippedBranches) : List.of());
        eventData.put("evaluations", evaluations);
        eventData.put("timestamp", System.currentTimeMillis());
        eventData.put("message", "Decision node evaluated: " + coreId);

        publishEvent(execution.getRunId(), "decisionEvaluated", eventData);
        logger.info("Sent decision evaluated event for runId: {}, core: {}, selected: {}",
            execution.getRunId(), coreId, selectedBranch);
    }

    public void sendWorkflowStatusEvent(String runId, RunStatus status, String message) {
        if (runId == null || status == null) return;

        Map<String, Object> eventData = new HashMap<>();
        eventData.put("type", "workflowStatus");
        eventData.put("runId", runId);
        eventData.put("status", status.getValue());
        eventData.put("message", message);
        eventData.put("timestamp", System.currentTimeMillis());

        publishEvent(runId, "workflowStatus", eventData);
    }

    private void publishEvent(String runId, String eventType, Map<String, Object> eventData) {
        if (redisPublisher == null) {
            logger.warn("No Redis publisher available for runId: {}. Event {} was NOT sent", runId, eventType);
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
}
