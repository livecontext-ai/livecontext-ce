package com.apimarketplace.orchestrator.execution.v2.events;

import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Unified event emitter for workflow execution.
 * Simplified version - logs events instead of emitting to streaming clients.
 */
@Service
public class ExecutionEventEmitter {

    private static final Logger logger = LoggerFactory.getLogger(ExecutionEventEmitter.class);

    /**
     * Emits a node execution event (logged for now).
     */
    public void emitNodeEvent(
            ExecutionNode node,
            ExecutionContext context,
            NodeExecutionResult result) {

        logger.info("📡 NODE EVENT: nodeId={}, type={}, status={}, itemId={}",
            node.getNodeId(), node.getType(), result.status(), context.itemId());
    }

    /**
     * Emits a step event with metrics.
     */
    public void emitStepEvent(
            String stepId,
            ExecutionContext context,
            NodeExecutionResult result,
            Map<String, Integer> metrics) {

        logger.info("📡 STEP EVENT: stepId={}, status={}, metrics={}, itemId={}",
            stepId, result.status(), metrics, context.itemId());
    }

    /**
     * Emits an edge status event.
     */
    public void emitEdgeEvent(
            String runId,
            String fromNodeId,
            String toNodeId,
            String status) {

        logger.debug("📡 EDGE EVENT: {}→{}, status={}", fromNodeId, toNodeId, status);
    }

    /**
     * Emits a workflow completion event.
     */
    public void emitWorkflowCompletionEvent(
            String runId,
            String status,
            int totalItems,
            int successItems,
            int failedItems) {

        logger.info("📡 WORKFLOW COMPLETE: runId={}, status={}, success={}, failed={}, total={}",
            runId, status, successItems, failedItems, totalItems);
    }

    /**
     * Emits a merge node event.
     */
    public void emitMergeEvent(
            String mergeNodeId,
            ExecutionContext context,
            Map<String, Integer> sourceItemCounts) {

        logger.info("📡 MERGE EVENT: mergeNodeId={}, sources={}", mergeNodeId, sourceItemCounts);
    }
}
