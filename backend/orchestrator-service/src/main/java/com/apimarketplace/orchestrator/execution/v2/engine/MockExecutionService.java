package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.domain.workflow.NodeMock;
import com.apimarketplace.orchestrator.domain.workflow.Step;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.nodes.StepNode;
import org.springframework.stereotype.Service;

/**
 * Single decision point for the per-node mock mode: given a node about to
 * execute, either returns the substituted mock result or {@code null} to signal
 * "execute for real". The caller pattern keeps the passthrough byte-identical:
 *
 * <pre>
 *   NodeExecutionResult mocked = mockExecutionService.tryMock(node, ctx);
 *   if (mocked != null) return mocked;
 *   // ... unchanged real execution ...
 * </pre>
 *
 * <p>Consulted at exactly the two leaf call sites of
 * {@code SplitAwareNodeExecutor} ({@code executeNodeBody} and the per-item
 * fan-out invoker), which every mockable node family funnels through in both
 * AUTO and step-by-step modes. Substitution above {@code node.execute()} means
 * signal nodes never yield, agents never dispatch, tables never write.
 *
 * <p>Resolution order: run gate ({@link MockRunGate}: editor-run guard +
 * per-run off/all_mcp override) → per-node {@code mock} block from the plan
 * ({@code enabled=false} = parked, executes real) → in ALL_MCP mode, an implicit
 * {@code catalog_example} mock for mcp catalog-tool nodes without a block.
 */
@Service
public class MockExecutionService {

    /** Implicit block applied to mcp catalog tools in ALL_MCP mode. */
    private static final NodeMock ALL_MCP_FALLBACK =
            new NodeMock(true, NodeMock.SOURCE_CATALOG_EXAMPLE, null, null, null);

    private final MockRunGate mockRunGate;
    private final MockNodeResultFactory mockNodeResultFactory;

    public MockExecutionService(MockRunGate mockRunGate, MockNodeResultFactory mockNodeResultFactory) {
        this.mockRunGate = mockRunGate;
        this.mockNodeResultFactory = mockNodeResultFactory;
    }

    /**
     * @return the mock result to substitute, or {@code null} when the node must
     *         execute for real (the caller then takes its unchanged code path)
     */
    public NodeExecutionResult tryMock(ExecutionNode node, ExecutionContext ctx) {
        if (node == null || ctx == null || ctx.plan() == null) {
            return null;
        }
        MockRunGate.MockRunMode mode = mockRunGate.mode(ctx.runId());
        if (!mode.isMockingEnabled()) {
            return null;
        }
        NodeMock mock = ctx.plan().getNodeMock(node.getNodeId());
        if (mock != null && !mock.isEffective()) {
            return null; // parked (enabled=false) - executes real
        }
        if (mock == null) {
            if (mode == MockRunGate.MockRunMode.ALL_MCP && isCatalogToolStep(node)) {
                mock = ALL_MCP_FALLBACK;
            } else {
                return null;
            }
        }
        return mockNodeResultFactory.build(node, ctx, mock);
    }

    /**
     * ALL_MCP fallback scope: mcp catalog-tool steps only (slug-form or UUID id).
     * Table CRUD steps and internal {@code crud/} operations are excluded -
     * fabricating their shapes is the author's job via an explicit static mock.
     */
    private boolean isCatalogToolStep(ExecutionNode node) {
        if (!(node instanceof StepNode stepNode)) {
            return false;
        }
        Step config = stepNode.getStepConfig();
        if (config == null || config.isCrudStep()) {
            return false;
        }
        return com.apimarketplace.orchestrator.domain.workflow.WorkflowPlanParser
                .isCatalogToolId(config.id());
    }
}
