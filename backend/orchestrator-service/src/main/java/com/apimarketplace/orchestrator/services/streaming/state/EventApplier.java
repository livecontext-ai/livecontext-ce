package com.apimarketplace.orchestrator.services.streaming.state;

import com.apimarketplace.orchestrator.services.streaming.events.AgentToolCallEvent;
import com.apimarketplace.orchestrator.services.streaming.events.DebugLogEvent;
import com.apimarketplace.orchestrator.services.streaming.events.EdgeStatusEvent;
import com.apimarketplace.orchestrator.services.streaming.events.LoopEvent;
import com.apimarketplace.orchestrator.services.streaming.events.MergeEvent;
import com.apimarketplace.orchestrator.services.streaming.events.RetryEvent;
import com.apimarketplace.orchestrator.services.streaming.events.StepStatusEvent;
import com.apimarketplace.orchestrator.services.streaming.events.WorkflowEvent;
import com.apimarketplace.orchestrator.services.streaming.events.WorkflowStatisticsEvent;
import com.apimarketplace.orchestrator.services.streaming.events.WorkflowStatusEvent;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Handles application of workflow events to the run state.
 * Dispatches events to appropriate handlers based on event type.
 *
 * <p>Note: Snapshots are now sent directly by SnapshotService from DB.
 * This class only maintains supplementary state (loops, merges, logs, interfaces).</p>
 */
@Component
public class EventApplier {

    private static final Logger log = LoggerFactory.getLogger(EventApplier.class);

    private final RunStateStoreAccessor stateAccessor;

    public EventApplier(RunStateStoreAccessor stateAccessor) {
        this.stateAccessor = stateAccessor;
    }

    /**
     * Apply a workflow event to the appropriate run state.
     * Dispatches to specialized handlers based on event type.
     *
     * @param event The workflow event to apply
     */
    public void applyEvent(WorkflowEvent event) {
        if (event instanceof StepStatusEvent stepEvent) {
            applyStepEvent(stepEvent);
            return;
        }
        if (event instanceof EdgeStatusEvent edgeEvent) {
            applyEdgeEvent(edgeEvent);
            return;
        }
        if (event instanceof WorkflowStatusEvent statusEvent) {
            applyWorkflowStatusEvent(statusEvent);
            return;
        }
        if (event instanceof WorkflowStatisticsEvent statisticsEvent) {
            applyWorkflowStatisticsEvent(statisticsEvent);
            return;
        }
        if (event instanceof LoopEvent loopEvent) {
            applyLoopEvent(loopEvent);
            return;
        }
        if (event instanceof RetryEvent retryEvent) {
            applyRetryEvent(retryEvent);
            return;
        }
        if (event instanceof MergeEvent mergeEvent) {
            applyMergeEvent(mergeEvent);
            return;
        }
        if (event instanceof DebugLogEvent debugLogEvent) {
            applyDebugLogEvent(debugLogEvent);
            return;
        }
        if (event instanceof AgentToolCallEvent agentToolCallEvent) {
            applyAgentToolCallEvent(agentToolCallEvent);
        }
    }

    /**
     * Apply an agent tool call event.
     */
    public void applyAgentToolCallEvent(AgentToolCallEvent event) {
        RunState state = stateAccessor.getOrCreateRunState(event.runId());
        state.updateAgentToolCall(event);
    }

    /**
     * Apply a step status event.
     * StatusCounts are read from DB in RunState.snapshot(), not cached here.
     */
    public void applyStepEvent(StepStatusEvent event) {
        RunState state = stateAccessor.getOrCreateRunState(event.runId());
        Map<String, Object> sanitized = StateUtils.sanitizeStepPayload(event.normalizedStepId(), event.payload());
        state.updateStep(event.runId(), event.normalizedStepId(), sanitized);
    }

    /**
     * Apply an edge status event.
     */
    public void applyEdgeEvent(EdgeStatusEvent event) {
        RunState state = stateAccessor.getOrCreateRunState(event.runId());
        state.updateEdge(event.edgeId(), event.from(), event.to(), event.lifecycle(), event.itemIndex(), event.iteration());
    }

    /**
     * Apply a workflow status event.
     */
    public void applyWorkflowStatusEvent(WorkflowStatusEvent event) {
        RunState state = stateAccessor.getOrCreateRunState(event.runId());
        state.updateWorkflowStatus(event.payload(), event.status(), event.message(), event.terminal());
    }

    /**
     * Apply a workflow statistics event.
     */
    public void applyWorkflowStatisticsEvent(WorkflowStatisticsEvent event) {
        RunState state = stateAccessor.getOrCreateRunState(event.runId());
        state.updateWorkflowStatistics(event.payload());
    }

    /**
     * Apply a loop event.
     */
    public void applyLoopEvent(LoopEvent event) {
        RunState state = stateAccessor.getOrCreateRunState(event.runId());
        state.updateLoop(event.loopId(), event);
    }

    /**
     * Apply a retry event.
     */
    public void applyRetryEvent(RetryEvent event) {
        RunState state = stateAccessor.getOrCreateRunState(event.runId());
        state.updateRetry(event.stepId(), event.retryIndex(), event.payload());
    }

    /**
     * Apply a merge event.
     */
    public void applyMergeEvent(MergeEvent event) {
        RunState state = stateAccessor.getOrCreateRunState(event.runId());
        state.updateMerge(event.mergeId(), event);
    }

    /**
     * Apply a debug log event.
     */
    public void applyDebugLogEvent(DebugLogEvent event) {
        RunState state = stateAccessor.getOrCreateRunState(event.runId());
        state.appendLog(event.level(), event.message(), event.timestamp());
    }
}
