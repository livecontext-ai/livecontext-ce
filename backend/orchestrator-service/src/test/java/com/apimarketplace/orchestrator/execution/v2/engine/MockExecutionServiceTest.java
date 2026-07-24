package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.domain.workflow.NodeMock;
import com.apimarketplace.orchestrator.domain.workflow.Step;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.nodes.BaseNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeType;
import com.apimarketplace.orchestrator.execution.v2.nodes.StepNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MockExecutionService} - the single decision point of the
 * per-node mock mode: gate consult, per-node block resolution, parked mocks,
 * ALL_MCP catalog fallback scope, and the null-return passthrough contract.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MockExecutionService - mock-or-real decision point")
class MockExecutionServiceTest {

    private static final String RUN_ID = "run-1";
    private static final String NODE_ID = "mcp:fetch_emails";

    @Mock private MockRunGate mockRunGate;
    @Mock private MockNodeResultFactory factory;
    @Mock private ExecutionContext context;

    private MockExecutionService service;

    @BeforeEach
    void setUp() {
        service = new MockExecutionService(mockRunGate, factory);
        lenient().when(context.runId()).thenReturn(RUN_ID);
    }

    private WorkflowPlan planWithMock(String nodeKey, NodeMock mock) {
        return new WorkflowPlan("11111111-1111-1111-1111-111111111111", "tenant-1",
            null, null, null, null, null, null, null, null,
            Map.of(), mock != null ? Map.of(nodeKey, mock) : Map.of(), Map.of());
    }

    private StepNode mcpStepNode(String toolId, boolean crud) {
        Step step = new Step(toolId, crud ? "crud-read-row" : "mcp", "Fetch Emails", null,
            Map.of(), crud ? 1L : null,
            crud ? new Step.CrudConfig(null, null, null, null, null, null, null) : null,
            null, null, null, null);
        return new StepNode(NODE_ID, step);
    }

    @Test
    @DisplayName("OFF mode is a pure passthrough: returns null, factory never consulted")
    void offModePassthrough() {
        when(mockRunGate.mode(RUN_ID)).thenReturn(MockRunGate.MockRunMode.OFF);
        when(context.plan()).thenReturn(planWithMock(NODE_ID,
            NodeMock.fromMap(Map.of("output", Map.of("x", 1)), NODE_ID)));

        assertThat(service.tryMock(mcpStepNode("gmail/list", false), context)).isNull();
        verifyNoInteractions(factory);
    }

    @Test
    @DisplayName("null context / null plan / null node return null without consulting the gate")
    void nullInputsPassthrough() {
        assertThat(service.tryMock(null, context)).isNull();
        when(context.plan()).thenReturn(null);
        assertThat(service.tryMock(mcpStepNode("gmail/list", false), context)).isNull();
        verifyNoInteractions(factory);
    }

    @Test
    @DisplayName("DEFAULT mode + enabled mock block delegates to the factory with the resolved block")
    void enabledMockDelegatesToFactory() {
        NodeMock mock = NodeMock.fromMap(Map.of("output", Map.of("count", 2)), NODE_ID);
        when(mockRunGate.mode(RUN_ID)).thenReturn(MockRunGate.MockRunMode.DEFAULT);
        when(context.plan()).thenReturn(planWithMock(NODE_ID, mock));
        StepNode node = mcpStepNode("gmail/list", false);
        NodeExecutionResult expected = NodeExecutionResult.success(NODE_ID, Map.of("count", 2));
        when(factory.build(node, context, mock)).thenReturn(expected);

        assertThat(service.tryMock(node, context)).isSameAs(expected);
    }

    @Test
    @DisplayName("a PARKED mock (enabled=false) executes real: returns null")
    void parkedMockPassthrough() {
        NodeMock parked = NodeMock.fromMap(Map.of("enabled", false, "output", Map.of("x", 1)), NODE_ID);
        when(mockRunGate.mode(RUN_ID)).thenReturn(MockRunGate.MockRunMode.DEFAULT);
        when(context.plan()).thenReturn(planWithMock(NODE_ID, parked));

        assertThat(service.tryMock(mcpStepNode("gmail/list", false), context)).isNull();
        verifyNoInteractions(factory);
    }

    @Test
    @DisplayName("a PARKED mock with a durationMs never reaches the factory: no simulated wait for real executions")
    void parkedMockWithDurationNeverSleeps() {
        NodeMock parked = NodeMock.fromMap(Map.of(
            "enabled", false, "output", Map.of("x", 1), "durationMs", 600_000), NODE_ID);
        when(mockRunGate.mode(RUN_ID)).thenReturn(MockRunGate.MockRunMode.DEFAULT);
        when(context.plan()).thenReturn(planWithMock(NODE_ID, parked));

        long before = System.currentTimeMillis();
        assertThat(service.tryMock(mcpStepNode("gmail/list", false), context)).isNull();

        assertThat(System.currentTimeMillis() - before).isLessThan(1_000L);
        verifyNoInteractions(factory);
    }

    @Test
    @DisplayName("DEFAULT mode + no block = real execution (granular hybrid runs)")
    void noBlockInDefaultModePassthrough() {
        when(mockRunGate.mode(RUN_ID)).thenReturn(MockRunGate.MockRunMode.DEFAULT);
        when(context.plan()).thenReturn(planWithMock(NODE_ID, null));

        assertThat(service.tryMock(mcpStepNode("gmail/list", false), context)).isNull();
        verifyNoInteractions(factory);
    }

    @Test
    @DisplayName("ALL_MCP mode: mcp catalog-tool node WITHOUT a block gets the implicit catalog_example mock")
    void allMcpFallbackForCatalogTools() {
        when(mockRunGate.mode(RUN_ID)).thenReturn(MockRunGate.MockRunMode.ALL_MCP);
        when(context.plan()).thenReturn(planWithMock(NODE_ID, null));
        StepNode node = mcpStepNode("gmail/gmail-list-messages", false);
        when(factory.build(eq(node), eq(context), any())).thenAnswer(inv -> {
            NodeMock fallback = inv.getArgument(2);
            assertThat(fallback.isCatalogExample()).isTrue();
            assertThat(fallback.isEffective()).isTrue();
            return NodeExecutionResult.success(NODE_ID, Map.of());
        });

        assertThat(service.tryMock(node, context)).isNotNull();
        verify(factory).build(eq(node), eq(context), any());
    }

    @Test
    @DisplayName("ALL_MCP fallback scope: CRUD steps, non-slug ids and non-step nodes stay REAL")
    void allMcpFallbackScopeExclusions() {
        when(mockRunGate.mode(RUN_ID)).thenReturn(MockRunGate.MockRunMode.ALL_MCP);
        when(context.plan()).thenReturn(planWithMock(NODE_ID, null));

        // CRUD step - excluded
        assertThat(service.tryMock(mcpStepNode("crud/read-row", true), context)).isNull();
        // non-slug test id - excluded
        assertThat(service.tryMock(mcpStepNode("s1", false), context)).isNull();
        // non-step node (transform core) - excluded
        BaseNode transform = new BaseNode("core:format", NodeType.TRANSFORM) {
            @Override
            public NodeExecutionResult execute(ExecutionContext ctx) {
                return NodeExecutionResult.success(nodeId, Map.of());
            }
        };
        assertThat(service.tryMock(transform, context)).isNull();
        verify(factory, never()).build(any(), any(), any());
    }

    @Test
    @DisplayName("ALL_MCP mode still applies EXPLICIT blocks on non-mcp nodes (configured semantics included)")
    void allMcpAppliesExplicitBlocks() {
        NodeMock coreMock = NodeMock.fromMap(Map.of("output", Map.of("result", "ok")), "core:format");
        when(mockRunGate.mode(RUN_ID)).thenReturn(MockRunGate.MockRunMode.ALL_MCP);
        when(context.plan()).thenReturn(planWithMock("core:format", coreMock));
        BaseNode transform = new BaseNode("core:format", NodeType.TRANSFORM) {
            @Override
            public NodeExecutionResult execute(ExecutionContext ctx) {
                return NodeExecutionResult.success(nodeId, Map.of());
            }
        };
        NodeExecutionResult expected = NodeExecutionResult.success("core:format", Map.of("result", "ok"));
        when(factory.build(transform, context, coreMock)).thenReturn(expected);

        assertThat(service.tryMock(transform, context)).isSameAs(expected);
    }
}
