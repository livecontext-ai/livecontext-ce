package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.domain.workflow.*;
import com.apimarketplace.orchestrator.execution.v2.nodes.*;
import com.apimarketplace.orchestrator.validation.DAGIndependenceValidator;
import com.apimarketplace.orchestrator.validation.PlanAliasValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ExecutionTreeBuilder.
 *
 * ExecutionTreeBuilder is a FACADE that delegates to:
 * - ExecutionNodeFactory: Basic node creation
 * - EdgeWiringOrchestrator: Edge wiring
 * - ExecutionServiceInjector: Service injection
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ExecutionTreeBuilder")
class ExecutionTreeBuilderTest {

    @Mock
    private ExecutionNodeFactory nodeFactory;

    @Mock
    private EdgeWiringOrchestrator edgeWirer;

    @Mock
    private ExecutionServiceInjector serviceInjector;

    @Mock
    private DAGIndependenceValidator dagValidator;

    @Mock
    private PlanAliasValidator aliasValidator;

    private ExecutionTreeBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new ExecutionTreeBuilder(nodeFactory, edgeWirer, serviceInjector, dagValidator, aliasValidator);
    }

    @Nested
    @DisplayName("build()")
    class BuildTests {

        @Test
        @DisplayName("Should call nodeFactory.createBasicNodes()")
        void shouldCallNodeFactoryCreateBasicNodes() {
            WorkflowPlan plan = createMinimalPlan();

            // Setup: Add trigger node when createBasicNodes is called
            doAnswer(invocation -> {
                @SuppressWarnings("unchecked")
                Map<String, ExecutionNode> nodeMap = invocation.getArgument(0);
                TriggerNode triggerNode = mock(TriggerNode.class);
                // findAllRootNodes() uses isTriggerNode(), not getType()
                when(triggerNode.isTriggerNode()).thenReturn(true);
                nodeMap.put("trigger:start", triggerNode);
                return null;
            }).when(nodeFactory).createBasicNodes(any(), any(), any(), any());

            builder.build("run-1", "workflow-run-1", "tenant-1", plan);

            verify(nodeFactory).createBasicNodes(any(), eq(plan), eq("tenant-1"), isNull());
        }

        @Test
        @DisplayName("Should pass active organization scope to nodeFactory")
        void shouldPassOrganizationScopeToNodeFactory() {
            WorkflowPlan plan = createMinimalPlan();

            doAnswer(invocation -> {
                @SuppressWarnings("unchecked")
                Map<String, ExecutionNode> nodeMap = invocation.getArgument(0);
                TriggerNode triggerNode = mock(TriggerNode.class);
                when(triggerNode.isTriggerNode()).thenReturn(true);
                nodeMap.put("trigger:start", triggerNode);
                return null;
            }).when(nodeFactory).createBasicNodes(any(), any(), any(), any());

            ExecutionTree tree = builder.build(
                "run-1",
                "workflow-run-1",
                "tenant-1",
                plan,
                "org-1",
                "OWNER");

            assertEquals("org-1", tree.getOrganizationId());
            assertEquals("OWNER", tree.getOrganizationRole());
            verify(nodeFactory).createBasicNodes(any(), eq(plan), eq("tenant-1"), eq("org-1"));
        }

        @Test
        @DisplayName("Should call edgeWirer.wireSuccessorsFromEdges()")
        void shouldCallEdgeWirerWireSuccessors() {
            WorkflowPlan plan = createMinimalPlan();
            setupMockTriggerNode();

            builder.build("run-1", "workflow-run-1", "tenant-1", plan);

            verify(edgeWirer).wireSuccessorsFromEdges(any(), eq(plan));
        }

        @Test
        @DisplayName("Should call serviceInjector.injectServices()")
        void shouldCallServiceInjectorInjectServices() {
            WorkflowPlan plan = createMinimalPlan();
            setupMockTriggerNode();

            builder.build("run-1", "workflow-run-1", "tenant-1", plan);

            verify(serviceInjector).injectServices(any());
        }

        @Test
        @DisplayName("Should return ExecutionTree with correct runId")
        void shouldReturnExecutionTreeWithCorrectRunId() {
            WorkflowPlan plan = createMinimalPlan();
            setupMockTriggerNode();

            ExecutionTree tree = builder.build("run-123", "workflow-run-1", "tenant-1", plan);

            assertEquals("run-123", tree.getRunId());
        }

        @Test
        @DisplayName("Should return ExecutionTree with correct workflowRunId")
        void shouldReturnExecutionTreeWithCorrectWorkflowRunId() {
            WorkflowPlan plan = createMinimalPlan();
            setupMockTriggerNode();

            ExecutionTree tree = builder.build("run-1", "wfr-456", "tenant-1", plan);

            assertEquals("wfr-456", tree.getWorkflowRunId());
        }

        @Test
        @DisplayName("Should return ExecutionTree with correct tenantId")
        void shouldReturnExecutionTreeWithCorrectTenantId() {
            WorkflowPlan plan = createMinimalPlan();
            setupMockTriggerNode();

            ExecutionTree tree = builder.build("run-1", "workflow-run-1", "tenant-xyz", plan);

            assertEquals("tenant-xyz", tree.getTenantId());
        }

        @Test
        @DisplayName("Should return ExecutionTree with plan")
        void shouldReturnExecutionTreeWithPlan() {
            WorkflowPlan plan = createMinimalPlan();
            setupMockTriggerNode();

            ExecutionTree tree = builder.build("run-1", "workflow-run-1", "tenant-1", plan);

            assertEquals(plan, tree.getPlan());
        }

        @Test
        @DisplayName("Should return ExecutionTree with root node")
        void shouldReturnExecutionTreeWithRootNode() {
            WorkflowPlan plan = createMinimalPlan();
            TriggerNode triggerNode = setupMockTriggerNode();

            ExecutionTree tree = builder.build("run-1", "workflow-run-1", "tenant-1", plan);

            assertNotNull(tree.getRootNode());
            assertEquals(triggerNode, tree.getRootNode());
        }

        @Test
        @DisplayName("Should return tree with empty rootNodes when no trigger node found")
        void shouldReturnTreeWithEmptyRootNodesWhenNoTriggerNodeFound() {
            WorkflowPlan plan = createMinimalPlan();
            // Don't add any trigger node

            ExecutionTree tree = builder.build("run-1", "workflow-run-1", "tenant-1", plan);

            assertNotNull(tree);
            assertTrue(tree.getRootNodes().isEmpty());
            assertNull(tree.getRootNode());
        }

        @Test
        @DisplayName("Should call components in correct order")
        void shouldCallComponentsInCorrectOrder() {
            WorkflowPlan plan = createMinimalPlan();
            setupMockTriggerNode();

            builder.build("run-1", "workflow-run-1", "tenant-1", plan);

            // Verify order: nodeFactory -> edgeWirer -> serviceInjector
            var inOrder = inOrder(nodeFactory, edgeWirer, serviceInjector);
            inOrder.verify(nodeFactory).createBasicNodes(any(), any(), any(), any());
            inOrder.verify(edgeWirer).wireSuccessorsFromEdges(any(), any());
            inOrder.verify(serviceInjector).injectServices(any());
        }

        @Test
        @DisplayName("Should invoke PlanAliasValidator before building the tree")
        void shouldInvokeAliasValidator() {
            WorkflowPlan plan = createMinimalPlan();
            setupMockTriggerNode();

            builder.build("run-1", "workflow-run-1", "tenant-1", plan);

            // Alias validation must run before node factory builds the graph,
            // otherwise an ambiguous plan would partially execute before failing.
            var inOrder = inOrder(aliasValidator, nodeFactory);
            inOrder.verify(aliasValidator).validate(plan);
            inOrder.verify(nodeFactory).createBasicNodes(any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should let PlanAliasValidationException bubble up wrapped as RuntimeException")
        void shouldPropagateAliasValidationException() {
            WorkflowPlan plan = createMinimalPlan();
            doThrow(new com.apimarketplace.orchestrator.validation.PlanAliasValidationException(
                java.util.Map.of("foo", java.util.List.of("mcp:foo", "trigger:foo"))))
                .when(aliasValidator).validate(any());

            RuntimeException ex = assertThrows(RuntimeException.class,
                () -> builder.build("run-1", "workflow-run-1", "tenant-1", plan));

            // ExecutionTreeBuilder wraps any exception in "Failed to build execution tree"
            assertTrue(ex.getMessage().contains("Failed to build execution tree"));
            assertInstanceOf(com.apimarketplace.orchestrator.validation.PlanAliasValidationException.class,
                ex.getCause());
        }
    }

    @Nested
    @DisplayName("Error handling")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Should wrap exception in RuntimeException")
        void shouldWrapExceptionInRuntimeException() {
            WorkflowPlan plan = createMinimalPlan();
            doThrow(new IllegalStateException("Test error"))
                .when(nodeFactory).createBasicNodes(any(), any(), any(), any());

            RuntimeException ex = assertThrows(RuntimeException.class,
                () -> builder.build("run-1", "workflow-run-1", "tenant-1", plan));

            assertEquals("Failed to build execution tree", ex.getMessage());
            assertInstanceOf(IllegalStateException.class, ex.getCause());
        }

        @Test
        @DisplayName("Should handle edge wiring failure")
        void shouldHandleEdgeWiringFailure() {
            WorkflowPlan plan = createMinimalPlan();
            setupMockTriggerNode();
            doThrow(new RuntimeException("Wiring failed"))
                .when(edgeWirer).wireSuccessorsFromEdges(any(), any());

            RuntimeException ex = assertThrows(RuntimeException.class,
                () -> builder.build("run-1", "workflow-run-1", "tenant-1", plan));

            assertTrue(ex.getMessage().contains("Failed to build execution tree"));
        }
    }

    // ===== Helper methods =====

    private TriggerNode setupMockTriggerNode() {
        TriggerNode triggerNode = mock(TriggerNode.class);
        // findAllRootNodes() uses isTriggerNode(), not getType()
        lenient().when(triggerNode.isTriggerNode()).thenReturn(true);
        lenient().when(triggerNode.getType()).thenReturn(NodeType.TRIGGER);

        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, ExecutionNode> nodeMap = invocation.getArgument(0);
            nodeMap.put("trigger:start", triggerNode);
            return null;
        }).when(nodeFactory).createBasicNodes(any(), any(), any(), any());

        return triggerNode;
    }

    private WorkflowPlan createMinimalPlan() {
        Map<String, Object> data = new HashMap<>();
        data.put("id", "test-plan");
        data.put("tenant_id", "test-tenant");
        data.put("triggers", List.of(
            Map.of("id", "t1", "label", "Start", "type", "webhook", "strategy", "single")
        ));
        data.put("mcps", List.of());
        data.put("edges", List.of());
        return WorkflowPlan.fromMap(data);
    }
}
