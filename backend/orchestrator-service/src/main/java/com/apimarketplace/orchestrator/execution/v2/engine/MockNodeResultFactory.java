package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.domain.workflow.NodeMock;
import com.apimarketplace.orchestrator.execution.v2.constants.ExecutionMetadataKeys;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.nodes.StepNode;
import com.apimarketplace.orchestrator.services.impl.CatalogMockClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Builds the substituted {@link NodeExecutionResult} for a mocked node.
 *
 * <p>Envelope parity: the produced output carries the same engine-envelope keys a
 * real execution would ({@code node_type} via {@link ExecutionNode#schemaNodeType},
 * {@code item_index}/{@code itemIndex}/{@code item_id} from the context, and
 * {@code resolved_params} for mcp steps - the RAW node params, since template
 * resolution is exactly what a mock skips). Port-selecting nodes get their native
 * routing keys merged in via {@link ExecutionNode#portSelectionOutput}, so edges,
 * sibling-branch skips and downstream traversal behave identically to a real run.
 *
 * <p>Every produced result is dual-stamped {@code __mocked__} / {@code __mock_source__}
 * in output AND metadata (the {@code policy_attempt} pattern) so run reports, the
 * inspector and {@code get_node_output} can badge it.
 *
 * <p>Never returns AWAITING_SIGNAL: mocked approval/interface/wait nodes complete
 * immediately (that is the guarantee). {@code source='error'} produces a FAILED
 * result that composes with retry policies / continueOnFailure like any failure.
 * A {@code catalog_example} fetch failure also produces a FAILED result with an
 * explicit message - never a silent fallback shape.
 *
 * <p>{@code durationMs} simulates the real call's latency: the factory waits that
 * long (on the same worker thread a real slow call would block, self-capped at
 * {@link NodeMock#MAX_DURATION_MS}) before serving the result, so the step's
 * measured execution time reflects it. Applies to every source, including
 * {@code error}. The wait is sliced into 100 ms chunks polling the run's cancel
 * signal (the {@code WaitNode} F2.4 pattern), so a user STOP releases the worker
 * within ~100 ms instead of the full simulated duration; a thread interrupt
 * (executor shutdown) ends the wait the same way. Either way the mock result is
 * served immediately - the wait is best-effort, never a new failure mode.
 */
@Service
public class MockNodeResultFactory {

    private static final Logger logger = LoggerFactory.getLogger(MockNodeResultFactory.class);

    private CatalogMockClient catalogMockClient;

    @Autowired(required = false)
    public void setCatalogMockClient(CatalogMockClient catalogMockClient) {
        this.catalogMockClient = catalogMockClient;
    }

    /** Optional: run cancel-signal source so a user STOP releases a simulated duration early. */
    private com.apimarketplace.orchestrator.services.streaming.redis.WorkflowRedisPublisher workflowRedisPublisher;

    @Autowired(required = false)
    public void setWorkflowRedisPublisher(
            com.apimarketplace.orchestrator.services.streaming.redis.WorkflowRedisPublisher workflowRedisPublisher) {
        this.workflowRedisPublisher = workflowRedisPublisher;
    }

    public NodeExecutionResult build(ExecutionNode node, ExecutionContext ctx, NodeMock mock) {
        long start = System.currentTimeMillis();
        String nodeId = node.getNodeId();

        simulateDuration(nodeId, ctx, mock);

        if (mock.isError()) {
            Map<String, Object> output = new HashMap<>(mock.error().output());
            applyEnvelope(output, node, ctx);
            output.put("error", mock.error().message());
            markMocked(output, mock.source());
            logger.info("[Mock] Node {} FAILED by mock (source=error): {}", nodeId, mock.error().message());
            return new NodeExecutionResult(nodeId, NodeStatus.FAILED, output,
                    Optional.of(mock.error().message()), mockMetadata(mock.source()),
                    System.currentTimeMillis() - start);
        }

        Map<String, Object> output;
        if (mock.isCatalogExample()) {
            String toolId = resolveCatalogToolId(node);
            try {
                if (catalogMockClient == null) {
                    throw new CatalogMockClient.MockExampleUnavailableException(
                            "Catalog example mocks are not available in this deployment");
                }
                output = catalogMockClient.fetchProjectedExample(toolId, ctx.tenantId());
            } catch (CatalogMockClient.MockExampleUnavailableException e) {
                Map<String, Object> failOutput = new HashMap<>();
                applyEnvelope(failOutput, node, ctx);
                failOutput.put("error", e.getMessage());
                markMocked(failOutput, mock.source());
                logger.warn("[Mock] Node {} catalog_example mock failed: {}", nodeId, e.getMessage());
                return new NodeExecutionResult(nodeId, NodeStatus.FAILED, failOutput,
                        Optional.of(e.getMessage()), mockMetadata(mock.source()),
                        System.currentTimeMillis() - start);
            }
        } else {
            // Static mock: shallow copy so per-item envelope stamping never mutates
            // the (immutable) plan-borne map. Nested values are treated as read-only
            // data by the engine, exactly like real tool outputs.
            output = mock.output() != null ? new HashMap<>(mock.output()) : new HashMap<>();
        }

        if (mock.port() != null) {
            output.putAll(node.portSelectionOutput(mock.port()));
        }
        applyEnvelope(output, node, ctx);
        markMocked(output, mock.source());

        logger.info("[Mock] Node {} served mock output (source={}, port={})",
                nodeId, mock.source(), mock.port());
        return new NodeExecutionResult(nodeId, NodeStatus.COMPLETED, output,
                Optional.empty(), mockMetadata(mock.source()),
                System.currentTimeMillis() - start);
    }

    /**
     * Simulated execution time: blocks the current worker thread for
     * {@code mock.durationMs}, exactly where the real call would block. The value
     * is parse-capped at {@link NodeMock#MAX_DURATION_MS} (10 minutes).
     *
     * <p>Sliced into 100 ms chunks polling the run's cancel signal (the
     * {@code WaitNode} F2.4 pattern): the engine's user-STOP cancellation is
     * cooperative, so a monolithic sleep would hold a pooled worker for the full
     * simulated duration after a STOP (compounding across a split fan-out). A
     * thread interrupt (executor shutdown) also ends the wait, with the flag
     * restored. Either way the mock result is then served immediately - the wait
     * is best-effort and never introduces a new failure mode.
     */
    private void simulateDuration(String nodeId, ExecutionContext ctx, NodeMock mock) {
        if (!mock.hasSimulatedDuration()) {
            return;
        }
        long durationMs = mock.durationMs();
        String runId = ctx != null ? ctx.runId() : null;
        logger.info("[Mock] Node {} simulating {} ms of execution time", nodeId, durationMs);
        long remaining = durationMs;
        try {
            while (remaining > 0) {
                long slice = Math.min(100L, remaining);
                Thread.sleep(slice);
                remaining -= slice;
                if (workflowRedisPublisher != null && runId != null
                        && workflowRedisPublisher.isAgentCancelSignalSet(runId)) {
                    logger.info("[Mock] Node {} simulated duration aborted by run cancel signal ({} ms remaining)",
                            nodeId, remaining);
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.debug("[Mock] Node {} simulated duration interrupted - serving the mock immediately", nodeId);
        }
    }

    /**
     * Same envelope a real execution writes (see {@code StepNode.execute} enrichment):
     * schema-dispatch tag + split/item identity. {@code node_type} is always
     * overwritten - schema mapper dispatch must never depend on what the mock
     * author happened to type.
     */
    private void applyEnvelope(Map<String, Object> output, ExecutionNode node, ExecutionContext ctx) {
        String nodeType = node.schemaNodeType();
        if (nodeType != null) {
            output.put(ExecutionMetadataKeys.NODE_TYPE, nodeType);
        }
        output.put(ExecutionMetadataKeys.ITEM_INDEX, ctx.itemIndex());
        output.put("itemIndex", ctx.itemIndex());
        output.put("item_id", ctx.itemId());
        if (node instanceof StepNode stepNode && stepNode.getStepConfig() != null
                && stepNode.getStepConfig().params() != null) {
            // Raw params, templates UNRESOLVED - input resolution is exactly what a
            // mock skips; the inspector still shows what the node would have sent.
            output.put("resolved_params", stepNode.getStepConfig().params());
        }
    }

    private void markMocked(Map<String, Object> output, String source) {
        output.put(ExecutionMetadataKeys.MOCKED, Boolean.TRUE);
        output.put(ExecutionMetadataKeys.MOCK_SOURCE, source);
    }

    private Map<String, Object> mockMetadata(String source) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(ExecutionMetadataKeys.MOCKED, Boolean.TRUE);
        metadata.put(ExecutionMetadataKeys.MOCK_SOURCE, source);
        return metadata;
    }

    private String resolveCatalogToolId(ExecutionNode node) {
        if (node instanceof StepNode stepNode && stepNode.getStepConfig() != null) {
            return stepNode.getStepConfig().id();
        }
        return null;
    }
}
