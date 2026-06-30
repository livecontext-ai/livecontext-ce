package com.apimarketplace.orchestrator.execution.v2.services;

import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.constants.ExecutionMetadataKeys;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionTree;
import com.apimarketplace.orchestrator.execution.v2.nodes.*;
import com.apimarketplace.orchestrator.services.resume.MergeNodeAnalyzer;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ReadyNodeCalculator Tests")
class ReadyNodeCalculatorTest {

    @Mock
    private MergeNodeAnalyzer mergeNodeAnalyzer;

    @Mock
    private ExecutionContext context;

    @Mock
    private ExecutionTree tree;

    @Mock
    private WorkflowPlan plan;

    private ReadyNodeCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new ReadyNodeCalculator(mergeNodeAnalyzer, null, null);
        // Default stubs for context methods called early in calculateReadyNodes
        when(context.getAllStepOutputs()).thenReturn(Map.of());
    }

    @Nested
    @DisplayName("getInitialReadyNodes()")
    class GetInitialReadyNodesTests {

        @Test
        @DisplayName("Should return root node ID when root exists")
        void shouldReturnRootNodeId() {
            // Given
            ExecutionNode rootNode = mock(ExecutionNode.class);
            when(rootNode.getNodeId()).thenReturn("trigger:start");
            // getInitialReadyNodes iterates tree.getRootNodes() (plural)
            when(tree.getRootNodes()).thenReturn(List.of(rootNode));

            // When
            Set<String> readyNodes = calculator.getInitialReadyNodes(tree);

            // Then
            assertEquals(1, readyNodes.size());
            assertTrue(readyNodes.contains("trigger:start"));
        }

        @Test
        @DisplayName("Should return empty set when no roots exist")
        void shouldReturnEmptySetWhenNoRoots() {
            // Given - getRootNodes returns empty list
            when(tree.getRootNodes()).thenReturn(List.of());

            // When
            Set<String> readyNodes = calculator.getInitialReadyNodes(tree);

            // Then
            assertTrue(readyNodes.isEmpty());
        }
    }

    @Nested
    @DisplayName("calculateReadyNodes()")
    class CalculateReadyNodesTests {

        @Test
        @DisplayName("Should return empty set when no roots exist")
        void shouldReturnEmptySetWhenNoRoots() {
            // Given - calculateReadyNodes iterates tree.getRootNodes() (plural)
            when(tree.getRootNodes()).thenReturn(List.of());

            // When
            Set<String> readyNodes = calculator.calculateReadyNodes(context, tree);

            // Then
            assertTrue(readyNodes.isEmpty());
        }

        @Test
        @DisplayName("Should mark root as ready when not completed and can execute")
        void shouldMarkRootAsReadyWhenNotCompletedAndCanExecute() {
            // Given
            ExecutionNode rootNode = mock(ExecutionNode.class);
            when(rootNode.getNodeId()).thenReturn("trigger:start");
            when(rootNode.getType()).thenReturn(NodeType.TRIGGER);
            when(rootNode.canExecute(context)).thenReturn(true);

            when(tree.getRootNodes()).thenReturn(List.of(rootNode));
            when(tree.getPlan()).thenReturn(plan);
            when(context.isCompleted("trigger:start")).thenReturn(false);
            when(context.isStarted("trigger:start")).thenReturn(false);
            when(mergeNodeAnalyzer.isMergeNode(plan, "trigger:start")).thenReturn(false);

            // When
            Set<String> readyNodes = calculator.calculateReadyNodes(context, tree);

            // Then
            assertTrue(readyNodes.contains("trigger:start"));
        }

        @Test
        @DisplayName("Should not mark node as ready when already completed")
        void shouldNotMarkNodeAsReadyWhenCompleted() {
            // Given
            ExecutionNode rootNode = mock(ExecutionNode.class);
            when(rootNode.getNodeId()).thenReturn("trigger:start");
            when(rootNode.getType()).thenReturn(NodeType.TRIGGER);
            when(rootNode.getNextNodes(any())).thenReturn(List.of());
            when(rootNode.getAllChildNodes()).thenReturn(List.of());

            when(tree.getRootNodes()).thenReturn(List.of(rootNode));
            when(tree.getPlan()).thenReturn(plan);
            when(context.isCompleted("trigger:start")).thenReturn(true);
            when(context.getStepOutput("trigger:start")).thenReturn(Optional.of(Map.of("data", "value")));

            // When
            Set<String> readyNodes = calculator.calculateReadyNodes(context, tree);

            // Then
            assertFalse(readyNodes.contains("trigger:start"));
        }

        @Test
        @DisplayName("Should not mark node as ready when already started")
        void shouldNotMarkNodeAsReadyWhenStarted() {
            // Given
            ExecutionNode rootNode = mock(ExecutionNode.class);
            when(rootNode.getNodeId()).thenReturn("trigger:start");
            when(rootNode.getType()).thenReturn(NodeType.TRIGGER);

            when(tree.getRootNodes()).thenReturn(List.of(rootNode));
            when(tree.getPlan()).thenReturn(plan);
            when(context.isCompleted("trigger:start")).thenReturn(false);
            when(context.isStarted("trigger:start")).thenReturn(true);

            // When
            Set<String> readyNodes = calculator.calculateReadyNodes(context, tree);

            // Then
            assertFalse(readyNodes.contains("trigger:start"));
        }

        @Test
        @DisplayName("Should traverse successors of completed node")
        void shouldTraverseSuccessorsOfCompletedNode() {
            // Given
            ExecutionNode rootNode = mock(ExecutionNode.class);
            ExecutionNode successorNode = mock(ExecutionNode.class);

            when(rootNode.getNodeId()).thenReturn("trigger:start");
            when(rootNode.getType()).thenReturn(NodeType.TRIGGER);
            when(rootNode.getAllChildNodes()).thenReturn(List.of());
            when(successorNode.getNodeId()).thenReturn("mcp:step1");
            when(successorNode.getType()).thenReturn(NodeType.MCP);
            when(successorNode.canExecute(context)).thenReturn(true);

            when(rootNode.getNextNodes(any())).thenReturn(List.of(successorNode));
            when(successorNode.getNextNodes(any())).thenReturn(List.of());

            when(tree.getRootNodes()).thenReturn(List.of(rootNode));
            when(tree.getPlan()).thenReturn(plan);

            // Root is completed
            when(context.isCompleted("trigger:start")).thenReturn(true);
            when(context.getStepOutput("trigger:start")).thenReturn(Optional.of(Map.of("data", "value")));

            // Successor is not completed
            when(context.isCompleted("mcp:step1")).thenReturn(false);
            when(context.isStarted("mcp:step1")).thenReturn(false);
            when(mergeNodeAnalyzer.isMergeNode(plan, "mcp:step1")).thenReturn(false);

            // When
            Set<String> readyNodes = calculator.calculateReadyNodes(context, tree);

            // Then
            assertTrue(readyNodes.contains("mcp:step1"));
        }

        @Test
        @DisplayName("Should not traverse successors of skipped node")
        void shouldNotTraverseSuccessorsOfSkippedNode() {
            // Given
            ExecutionNode rootNode = mock(ExecutionNode.class);
            ExecutionNode successorNode = mock(ExecutionNode.class);

            when(rootNode.getNodeId()).thenReturn("trigger:start");
            when(rootNode.getType()).thenReturn(NodeType.TRIGGER);
            when(successorNode.getNodeId()).thenReturn("mcp:step1");

            when(rootNode.getNextNodes(any())).thenReturn(List.of(successorNode));

            when(tree.getRootNodes()).thenReturn(List.of(rootNode));
            when(tree.getPlan()).thenReturn(plan);

            // Root is completed but skipped (no output)
            when(context.isCompleted("trigger:start")).thenReturn(true);
            when(context.getStepOutput("trigger:start")).thenReturn(Optional.empty());

            // When
            Set<String> readyNodes = calculator.calculateReadyNodes(context, tree);

            // Then
            assertFalse(readyNodes.contains("mcp:step1"));
        }
    }

    @Nested
    @DisplayName("Merge Node Handling")
    class MergeNodeHandlingTests {

        @Test
        @DisplayName("Should mark merge node as ready when all predecessors resolved")
        void shouldMarkMergeNodeReadyWhenAllPredecessorsResolved() {
            // Given
            ExecutionNode mergeNode = mock(ExecutionNode.class);
            when(mergeNode.getNodeId()).thenReturn("core:merge");
            when(mergeNode.getType()).thenReturn(NodeType.MERGE);

            when(tree.getRootNodes()).thenReturn(List.of(mergeNode));
            when(tree.getPlan()).thenReturn(plan);

            when(context.isCompleted("core:merge")).thenReturn(false);
            when(context.isStarted("core:merge")).thenReturn(false);

            // It's a merge node
            when(mergeNodeAnalyzer.isMergeNode(plan, "core:merge")).thenReturn(true);
            when(mergeNodeAnalyzer.findPredecessorsFromEdges(plan, "core:merge"))
                .thenReturn(List.of("mcp:step1", "mcp:step2"));

            // All predecessors completed
            when(context.isCompleted("mcp:step1")).thenReturn(true);
            when(context.isCompleted("mcp:step2")).thenReturn(true);

            // When
            Set<String> readyNodes = calculator.calculateReadyNodes(context, tree);

            // Then
            assertTrue(readyNodes.contains("core:merge"));
        }

        @Test
        @DisplayName("Should not mark merge node as ready when predecessors not resolved")
        void shouldNotMarkMergeNodeReadyWhenPredecessorsNotResolved() {
            // Given
            ExecutionNode mergeNode = mock(ExecutionNode.class);
            when(mergeNode.getNodeId()).thenReturn("core:merge");
            when(mergeNode.getType()).thenReturn(NodeType.MERGE);

            when(tree.getRootNodes()).thenReturn(List.of(mergeNode));
            when(tree.getPlan()).thenReturn(plan);

            when(context.isCompleted("core:merge")).thenReturn(false);
            when(context.isStarted("core:merge")).thenReturn(false);

            // It's a merge node
            when(mergeNodeAnalyzer.isMergeNode(plan, "core:merge")).thenReturn(true);
            when(mergeNodeAnalyzer.findPredecessorsFromEdges(plan, "core:merge"))
                .thenReturn(List.of("mcp:step1", "mcp:step2"));

            // Only one predecessor completed
            when(context.isCompleted("mcp:step1")).thenReturn(true);
            when(context.isCompleted("mcp:step2")).thenReturn(false);

            // When
            Set<String> readyNodes = calculator.calculateReadyNodes(context, tree);

            // Then
            assertFalse(readyNodes.contains("core:merge"));
        }

        @Test
        @DisplayName("Should consider skipped predecessors as resolved for merge")
        void shouldConsiderSkippedPredecessorsAsResolved() {
            // Given
            ExecutionNode mergeNode = mock(ExecutionNode.class);
            when(mergeNode.getNodeId()).thenReturn("core:merge");
            when(mergeNode.getType()).thenReturn(NodeType.MERGE);

            when(tree.getRootNodes()).thenReturn(List.of(mergeNode));
            when(tree.getPlan()).thenReturn(plan);

            when(context.isCompleted("core:merge")).thenReturn(false);
            when(context.isStarted("core:merge")).thenReturn(false);

            when(mergeNodeAnalyzer.isMergeNode(plan, "core:merge")).thenReturn(true);
            when(mergeNodeAnalyzer.findPredecessorsFromEdges(plan, "core:merge"))
                .thenReturn(List.of("mcp:step1", "mcp:step2"));

            // step1 completed, step2 completed but skipped (empty output)
            when(context.isCompleted("mcp:step1")).thenReturn(true);
            when(context.isCompleted("mcp:step2")).thenReturn(true);
            when(context.getStepOutput("mcp:step2")).thenReturn(Optional.empty());

            // When
            Set<String> readyNodes = calculator.calculateReadyNodes(context, tree);

            // Then
            assertTrue(readyNodes.contains("core:merge"));
        }
    }

    @Nested
    @DisplayName("Decision Node Handling")
    class DecisionNodeHandlingTests {

        @Test
        @DisplayName("Should traverse selected branch successors")
        void shouldTraverseSelectedBranchSuccessors() {
            // Given
            ExecutionNode decisionNode = mock(ExecutionNode.class);
            ExecutionNode ifBranchNode = mock(ExecutionNode.class);
            ExecutionNode elseBranchNode = mock(ExecutionNode.class);

            when(decisionNode.getNodeId()).thenReturn("core:check");
            when(decisionNode.getType()).thenReturn(NodeType.DECISION);
            when(decisionNode.getAllChildNodes()).thenReturn(List.of());
            when(ifBranchNode.getNodeId()).thenReturn("mcp:if_step");
            when(ifBranchNode.getType()).thenReturn(NodeType.MCP);
            when(ifBranchNode.canExecute(context)).thenReturn(true);
            when(elseBranchNode.getNodeId()).thenReturn("mcp:else_step");

            // Decision completed with selected_branch_index = 0 (if branch)
            Map<String, Object> decisionOutput = Map.of("selected_branch_index", 0);

            when(decisionNode.getNextNodes(any())).thenReturn(List.of(ifBranchNode));

            when(tree.getRootNodes()).thenReturn(List.of(decisionNode));
            when(tree.getPlan()).thenReturn(plan);

            when(context.isCompleted("core:check")).thenReturn(true);
            when(context.getStepOutput("core:check")).thenReturn(Optional.of(decisionOutput));
            when(context.isCompleted("mcp:if_step")).thenReturn(false);
            when(context.isStarted("mcp:if_step")).thenReturn(false);
            when(mergeNodeAnalyzer.isMergeNode(plan, "mcp:if_step")).thenReturn(false);

            // When
            Set<String> readyNodes = calculator.calculateReadyNodes(context, tree);

            // Then
            assertTrue(readyNodes.contains("mcp:if_step"));
        }
    }

    @Nested
    @DisplayName("Multi-DAG Support")
    class MultiDagTests {

        @Test
        @DisplayName("Should return ALL triggers as initial ready nodes in multi-DAG mode")
        void shouldReturnAllTriggersAsInitialReady() {
            // Given: 3 independent triggers
            ExecutionNode trigger1 = mock(ExecutionNode.class);
            ExecutionNode trigger2 = mock(ExecutionNode.class);
            ExecutionNode trigger3 = mock(ExecutionNode.class);

            when(trigger1.getNodeId()).thenReturn("trigger:webhook_1");
            when(trigger2.getNodeId()).thenReturn("trigger:chat_start");
            when(trigger3.getNodeId()).thenReturn("trigger:schedule_daily");

            when(tree.getRootNodes()).thenReturn(List.of(trigger1, trigger2, trigger3));

            // When
            Set<String> readyNodes = calculator.getInitialReadyNodes(tree);

            // Then - all 3 triggers are ready
            assertEquals(3, readyNodes.size());
            assertTrue(readyNodes.contains("trigger:webhook_1"));
            assertTrue(readyNodes.contains("trigger:chat_start"));
            assertTrue(readyNodes.contains("trigger:schedule_daily"));
        }

        @Test
        @DisplayName("Should traverse both DAGs independently - one completed, one pending")
        void shouldTraverseBothDagsIndependently() {
            // Given: Two independent DAGs
            // DAG1: trigger:webhook -> mcp:step_a (completed) -> mcp:step_b (ready)
            // DAG2: trigger:chat -> agent:classify (not started, ready)
            ExecutionNode webhookTrigger = mock(ExecutionNode.class);
            ExecutionNode stepA = mock(ExecutionNode.class);
            ExecutionNode stepB = mock(ExecutionNode.class);
            ExecutionNode chatTrigger = mock(ExecutionNode.class);
            ExecutionNode classify = mock(ExecutionNode.class);

            when(webhookTrigger.getNodeId()).thenReturn("trigger:webhook");
            when(webhookTrigger.getType()).thenReturn(NodeType.TRIGGER);
            when(webhookTrigger.getAllChildNodes()).thenReturn(List.of());
            when(stepA.getNodeId()).thenReturn("mcp:step_a");
            when(stepA.getType()).thenReturn(NodeType.MCP);
            when(stepA.getAllChildNodes()).thenReturn(List.of());
            when(stepB.getNodeId()).thenReturn("mcp:step_b");
            when(stepB.getType()).thenReturn(NodeType.MCP);
            when(stepB.canExecute(context)).thenReturn(true);

            when(chatTrigger.getNodeId()).thenReturn("trigger:chat");
            when(chatTrigger.getType()).thenReturn(NodeType.TRIGGER);
            when(chatTrigger.getAllChildNodes()).thenReturn(List.of());
            when(classify.getNodeId()).thenReturn("agent:classify");
            when(classify.getType()).thenReturn(NodeType.AGENT);
            when(classify.canExecute(context)).thenReturn(true);

            // DAG1: webhook -> stepA -> stepB
            when(webhookTrigger.getNextNodes(any())).thenReturn(List.of(stepA));
            when(stepA.getNextNodes(any())).thenReturn(List.of(stepB));
            when(stepB.getNextNodes(any())).thenReturn(List.of());

            // DAG2: chat -> classify
            when(chatTrigger.getNextNodes(any())).thenReturn(List.of(classify));
            when(classify.getNextNodes(any())).thenReturn(List.of());

            when(tree.getRootNodes()).thenReturn(List.of(webhookTrigger, chatTrigger));
            when(tree.getPlan()).thenReturn(plan);

            // DAG1 state: trigger and stepA completed
            when(context.isCompleted("trigger:webhook")).thenReturn(true);
            when(context.getStepOutput("trigger:webhook")).thenReturn(Optional.of(Map.of("data", "webhook_payload")));
            when(context.isCompleted("mcp:step_a")).thenReturn(true);
            when(context.getStepOutput("mcp:step_a")).thenReturn(Optional.of(Map.of("result", "ok")));
            when(context.isCompleted("mcp:step_b")).thenReturn(false);
            when(context.isStarted("mcp:step_b")).thenReturn(false);
            when(mergeNodeAnalyzer.isMergeNode(plan, "mcp:step_b")).thenReturn(false);

            // DAG2 state: trigger completed, classify not started
            when(context.isCompleted("trigger:chat")).thenReturn(true);
            when(context.getStepOutput("trigger:chat")).thenReturn(Optional.of(Map.of("message", "hello")));
            when(context.isCompleted("agent:classify")).thenReturn(false);
            when(context.isStarted("agent:classify")).thenReturn(false);
            when(mergeNodeAnalyzer.isMergeNode(plan, "agent:classify")).thenReturn(false);

            // When
            Set<String> readyNodes = calculator.calculateReadyNodes(context, tree);

            // Then - both stepB and classify should be ready (from different DAGs)
            assertTrue(readyNodes.contains("mcp:step_b"), "stepB from DAG1 should be ready");
            assertTrue(readyNodes.contains("agent:classify"), "classify from DAG2 should be ready");
            assertEquals(2, readyNodes.size());
        }

        @Test
        @DisplayName("Should not cross-contaminate DAGs - completing DAG1 doesn't affect DAG2")
        void shouldNotCrossContaminateDags() {
            // Given: Two DAGs, DAG1 fully completed, DAG2 at root
            ExecutionNode trigger1 = mock(ExecutionNode.class);
            ExecutionNode step1 = mock(ExecutionNode.class);
            ExecutionNode trigger2 = mock(ExecutionNode.class);
            ExecutionNode step2 = mock(ExecutionNode.class);

            when(trigger1.getNodeId()).thenReturn("trigger:webhook");
            when(trigger1.getType()).thenReturn(NodeType.TRIGGER);
            when(trigger1.getAllChildNodes()).thenReturn(List.of());
            when(step1.getNodeId()).thenReturn("mcp:api_call");
            when(step1.getType()).thenReturn(NodeType.MCP);
            when(step1.getAllChildNodes()).thenReturn(List.of());
            when(step1.getNextNodes(any())).thenReturn(List.of());

            when(trigger2.getNodeId()).thenReturn("trigger:schedule");
            when(trigger2.getType()).thenReturn(NodeType.TRIGGER);
            when(trigger2.canExecute(context)).thenReturn(true);

            when(step2.getNodeId()).thenReturn("table:get_users");
            when(step2.getType()).thenReturn(NodeType.MCP);

            // DAG1: fully completed
            when(trigger1.getNextNodes(any())).thenReturn(List.of(step1));
            when(context.isCompleted("trigger:webhook")).thenReturn(true);
            when(context.getStepOutput("trigger:webhook")).thenReturn(Optional.of(Map.of()));
            when(context.isCompleted("mcp:api_call")).thenReturn(true);
            when(context.getStepOutput("mcp:api_call")).thenReturn(Optional.of(Map.of()));

            // DAG2: trigger not started (still ready)
            when(trigger2.getNextNodes(any())).thenReturn(List.of(step2));
            when(context.isCompleted("trigger:schedule")).thenReturn(false);
            when(context.isStarted("trigger:schedule")).thenReturn(false);
            when(mergeNodeAnalyzer.isMergeNode(plan, "trigger:schedule")).thenReturn(false);

            when(tree.getRootNodes()).thenReturn(List.of(trigger1, trigger2));
            when(tree.getPlan()).thenReturn(plan);

            // When
            Set<String> readyNodes = calculator.calculateReadyNodes(context, tree);

            // Then - only DAG2 trigger is ready, DAG1 is fully done
            assertTrue(readyNodes.contains("trigger:schedule"), "DAG2 trigger should be ready");
            assertFalse(readyNodes.contains("trigger:webhook"), "DAG1 trigger should not be ready (completed)");
            assertFalse(readyNodes.contains("mcp:api_call"), "DAG1 step should not be ready (completed)");
        }

        @Test
        @DisplayName("Should handle 3 DAGs with mixed node types progressing independently")
        void shouldHandle3DagsWithMixedNodeTypes() {
            // DAG1: trigger:webhook -> core:decision (completed, if branch) -> mcp:send_email (ready)
            // DAG2: trigger:chat -> agent:ai_agent (running)
            // DAG3: trigger:schedule -> table:get_data (completed) -> mcp:process (ready)
            ExecutionNode trigger1 = mock(ExecutionNode.class);
            ExecutionNode decision = mock(ExecutionNode.class);
            ExecutionNode sendEmail = mock(ExecutionNode.class);
            ExecutionNode trigger2 = mock(ExecutionNode.class);
            ExecutionNode aiAgent = mock(ExecutionNode.class);
            ExecutionNode trigger3 = mock(ExecutionNode.class);
            ExecutionNode getData = mock(ExecutionNode.class);
            ExecutionNode process = mock(ExecutionNode.class);

            // DAG1
            when(trigger1.getNodeId()).thenReturn("trigger:webhook");
            when(trigger1.getType()).thenReturn(NodeType.TRIGGER);
            when(trigger1.getAllChildNodes()).thenReturn(List.of());
            when(decision.getNodeId()).thenReturn("core:decision");
            when(decision.getType()).thenReturn(NodeType.DECISION);
            when(decision.getAllChildNodes()).thenReturn(List.of(sendEmail));
            when(sendEmail.getNodeId()).thenReturn("mcp:send_email");
            when(sendEmail.getType()).thenReturn(NodeType.MCP);
            when(sendEmail.canExecute(context)).thenReturn(true);

            when(trigger1.getNextNodes(any())).thenReturn(List.of(decision));
            when(decision.getNextNodes(any())).thenReturn(List.of(sendEmail));
            when(sendEmail.getNextNodes(any())).thenReturn(List.of());

            // DAG2
            when(trigger2.getNodeId()).thenReturn("trigger:chat");
            when(trigger2.getType()).thenReturn(NodeType.TRIGGER);
            when(trigger2.getAllChildNodes()).thenReturn(List.of());
            when(aiAgent.getNodeId()).thenReturn("agent:ai_agent");
            when(aiAgent.getType()).thenReturn(NodeType.AGENT);

            when(trigger2.getNextNodes(any())).thenReturn(List.of(aiAgent));

            // DAG3
            when(trigger3.getNodeId()).thenReturn("trigger:schedule");
            when(trigger3.getType()).thenReturn(NodeType.TRIGGER);
            when(trigger3.getAllChildNodes()).thenReturn(List.of());
            when(getData.getNodeId()).thenReturn("table:get_data");
            when(getData.getType()).thenReturn(NodeType.MCP);
            when(getData.getAllChildNodes()).thenReturn(List.of());
            when(process.getNodeId()).thenReturn("mcp:process");
            when(process.getType()).thenReturn(NodeType.MCP);
            when(process.canExecute(context)).thenReturn(true);

            when(trigger3.getNextNodes(any())).thenReturn(List.of(getData));
            when(getData.getNextNodes(any())).thenReturn(List.of(process));
            when(process.getNextNodes(any())).thenReturn(List.of());

            when(tree.getRootNodes()).thenReturn(List.of(trigger1, trigger2, trigger3));
            when(tree.getPlan()).thenReturn(plan);

            // DAG1: trigger and decision completed
            when(context.isCompleted("trigger:webhook")).thenReturn(true);
            when(context.getStepOutput("trigger:webhook")).thenReturn(Optional.of(Map.of()));
            when(context.isCompleted("core:decision")).thenReturn(true);
            when(context.getStepOutput("core:decision")).thenReturn(Optional.of(Map.of("selected_branch_index", 0)));
            when(context.isCompleted("mcp:send_email")).thenReturn(false);
            when(context.isStarted("mcp:send_email")).thenReturn(false);
            when(mergeNodeAnalyzer.isMergeNode(plan, "mcp:send_email")).thenReturn(false);

            // DAG2: trigger completed, agent running (started)
            when(context.isCompleted("trigger:chat")).thenReturn(true);
            when(context.getStepOutput("trigger:chat")).thenReturn(Optional.of(Map.of()));
            when(context.isCompleted("agent:ai_agent")).thenReturn(false);
            when(context.isStarted("agent:ai_agent")).thenReturn(true);

            // DAG3: trigger and getData completed, process not started
            when(context.isCompleted("trigger:schedule")).thenReturn(true);
            when(context.getStepOutput("trigger:schedule")).thenReturn(Optional.of(Map.of()));
            when(context.isCompleted("table:get_data")).thenReturn(true);
            when(context.getStepOutput("table:get_data")).thenReturn(Optional.of(Map.of("rows", List.of())));
            when(context.isCompleted("mcp:process")).thenReturn(false);
            when(context.isStarted("mcp:process")).thenReturn(false);
            when(mergeNodeAnalyzer.isMergeNode(plan, "mcp:process")).thenReturn(false);

            // When
            Set<String> readyNodes = calculator.calculateReadyNodes(context, tree);

            // Then
            assertTrue(readyNodes.contains("mcp:send_email"), "DAG1 send_email should be ready");
            assertFalse(readyNodes.contains("agent:ai_agent"), "DAG2 ai_agent should NOT be ready (already started/running)");
            assertTrue(readyNodes.contains("mcp:process"), "DAG3 process should be ready");
        }

        @Test
        @DisplayName("Should handle merge node in multi-DAG - only becomes ready when own predecessors complete")
        void shouldHandleMergeNodeInMultiDag() {
            // DAG1: trigger:webhook -> mcp:step_a -> core:merge (merge node)
            //                       -> mcp:step_b -> core:merge
            // DAG2: trigger:chat -> agent:reply (independent)
            ExecutionNode webhookTrigger = mock(ExecutionNode.class);
            ExecutionNode stepA = mock(ExecutionNode.class);
            ExecutionNode stepB = mock(ExecutionNode.class);
            ExecutionNode mergeNode = mock(ExecutionNode.class);
            ExecutionNode chatTrigger = mock(ExecutionNode.class);
            ExecutionNode reply = mock(ExecutionNode.class);

            when(webhookTrigger.getNodeId()).thenReturn("trigger:webhook");
            when(webhookTrigger.getType()).thenReturn(NodeType.TRIGGER);
            when(webhookTrigger.getAllChildNodes()).thenReturn(List.of());
            when(stepA.getNodeId()).thenReturn("mcp:step_a");
            when(stepA.getType()).thenReturn(NodeType.MCP);
            when(stepA.getAllChildNodes()).thenReturn(List.of());
            when(stepB.getNodeId()).thenReturn("mcp:step_b");
            when(stepB.getType()).thenReturn(NodeType.MCP);
            when(stepB.getAllChildNodes()).thenReturn(List.of());
            when(mergeNode.getNodeId()).thenReturn("core:merge");
            when(mergeNode.getType()).thenReturn(NodeType.MERGE);

            when(chatTrigger.getNodeId()).thenReturn("trigger:chat");
            when(chatTrigger.getType()).thenReturn(NodeType.TRIGGER);
            when(chatTrigger.getAllChildNodes()).thenReturn(List.of());
            when(reply.getNodeId()).thenReturn("agent:reply");
            when(reply.getType()).thenReturn(NodeType.AGENT);
            when(reply.canExecute(context)).thenReturn(true);

            // DAG1 edges
            when(webhookTrigger.getNextNodes(any())).thenReturn(List.of(stepA, stepB));
            when(stepA.getNextNodes(any())).thenReturn(List.of(mergeNode));
            when(stepB.getNextNodes(any())).thenReturn(List.of(mergeNode));

            // DAG2 edges
            when(chatTrigger.getNextNodes(any())).thenReturn(List.of(reply));
            when(reply.getNextNodes(any())).thenReturn(List.of());

            when(tree.getRootNodes()).thenReturn(List.of(webhookTrigger, chatTrigger));
            when(tree.getPlan()).thenReturn(plan);

            // DAG1: trigger completed, stepA completed, stepB NOT completed
            when(context.isCompleted("trigger:webhook")).thenReturn(true);
            when(context.getStepOutput("trigger:webhook")).thenReturn(Optional.of(Map.of()));
            when(context.isCompleted("mcp:step_a")).thenReturn(true);
            when(context.getStepOutput("mcp:step_a")).thenReturn(Optional.of(Map.of()));
            when(context.isCompleted("mcp:step_b")).thenReturn(false);
            when(context.isStarted("mcp:step_b")).thenReturn(false);
            when(context.isCompleted("core:merge")).thenReturn(false);
            when(context.isStarted("core:merge")).thenReturn(false);

            // Merge node setup
            when(mergeNodeAnalyzer.isMergeNode(plan, "core:merge")).thenReturn(true);
            when(mergeNodeAnalyzer.findPredecessorsFromEdges(plan, "core:merge"))
                .thenReturn(List.of("mcp:step_a", "mcp:step_b"));
            when(mergeNodeAnalyzer.isMergeNode(plan, "mcp:step_b")).thenReturn(false);
            when(stepB.canExecute(context)).thenReturn(true);

            // DAG2: trigger completed, reply not started
            when(context.isCompleted("trigger:chat")).thenReturn(true);
            when(context.getStepOutput("trigger:chat")).thenReturn(Optional.of(Map.of()));
            when(context.isCompleted("agent:reply")).thenReturn(false);
            when(context.isStarted("agent:reply")).thenReturn(false);
            when(mergeNodeAnalyzer.isMergeNode(plan, "agent:reply")).thenReturn(false);

            // When
            Set<String> readyNodes = calculator.calculateReadyNodes(context, tree);

            // Then
            assertTrue(readyNodes.contains("mcp:step_b"), "stepB should be ready (predecessor trigger completed)");
            assertFalse(readyNodes.contains("core:merge"), "merge should NOT be ready (stepB not completed yet)");
            assertTrue(readyNodes.contains("agent:reply"), "DAG2 reply should be ready independently");
        }

        @Test
        @DisplayName("BUG FIX: Firing DAG1 trigger should NOT block DAG2 trigger from being ready (with StateSnapshotService)")
        void firingDag1TriggerShouldNotBlockDag2Trigger() {
            // This test uses a real StateSnapshotService mock to reproduce the bug where
            // hasAnyNodeExecutedInEpoch() was blocking ALL triggers after ANY node completed.
            // The fix: hasTriggerBeenExecutedInEpoch() checks only the specific trigger.

            StateSnapshotService snapshotService = mock(StateSnapshotService.class);
            ReadyNodeCalculator calculatorWithService = new ReadyNodeCalculator(mergeNodeAnalyzer, snapshotService, null);

            // Two triggers
            ExecutionNode trigger1 = mock(ExecutionNode.class);
            ExecutionNode step1 = mock(ExecutionNode.class);
            ExecutionNode trigger2 = mock(ExecutionNode.class);
            ExecutionNode step2 = mock(ExecutionNode.class);

            when(trigger1.getNodeId()).thenReturn("trigger:webhook");
            when(trigger1.getType()).thenReturn(NodeType.TRIGGER);
            when(trigger1.isTriggerNode()).thenReturn(true);
            when(trigger1.getAllChildNodes()).thenReturn(List.of());
            when(step1.getNodeId()).thenReturn("mcp:step_a");
            when(step1.getType()).thenReturn(NodeType.MCP);
            when(step1.canExecute(context)).thenReturn(true);
            when(step1.getNextNodes(any())).thenReturn(List.of());

            when(trigger2.getNodeId()).thenReturn("trigger:chat");
            when(trigger2.getType()).thenReturn(NodeType.TRIGGER);
            when(trigger2.isTriggerNode()).thenReturn(true);
            when(trigger2.canExecute(context)).thenReturn(true);
            when(step2.getNodeId()).thenReturn("agent:reply");

            when(trigger1.getNextNodes(any())).thenReturn(List.of(step1));
            when(trigger2.getNextNodes(any())).thenReturn(List.of(step2));

            when(tree.getRootNodes()).thenReturn(List.of(trigger1, trigger2));
            when(tree.getPlan()).thenReturn(plan);
            when(context.runId()).thenReturn("run-123");

            // DAG1: trigger completed, step_a not started
            when(context.isCompleted("trigger:webhook")).thenReturn(true);
            when(context.getStepOutput("trigger:webhook")).thenReturn(Optional.of(Map.of("data", "payload")));
            when(context.isCompleted("mcp:step_a")).thenReturn(false);
            when(context.isStarted("mcp:step_a")).thenReturn(false);
            when(mergeNodeAnalyzer.isMergeNode(plan, "mcp:step_a")).thenReturn(false);

            // DAG2: trigger NOT started yet
            when(context.isCompleted("trigger:chat")).thenReturn(false);
            when(context.isStarted("trigger:chat")).thenReturn(false);
            when(mergeNodeAnalyzer.isMergeNode(plan, "trigger:chat")).thenReturn(false);

            // StateSnapshot: trigger:webhook is completed (DAG1 has been fired)
            // Build a real StateSnapshot with trigger:webhook completed in its own DAG
            StateSnapshot snapshot = StateSnapshot.empty()
                .markNodeCompleted("trigger:webhook", "trigger:webhook");
            when(snapshotService.getSnapshot("run-123")).thenReturn(snapshot);
            when(snapshotService.getCompletedNodeIds("run-123"))
                .thenReturn(Set.of("trigger:webhook"));

            // When
            Set<String> readyNodes = calculatorWithService.calculateReadyNodes(context, tree);

            // Then - CRITICAL: trigger:chat should STILL be ready even though trigger:webhook completed
            assertTrue(readyNodes.contains("mcp:step_a"), "DAG1 step_a should be ready (after trigger completed)");
            assertTrue(readyNodes.contains("trigger:chat"),
                "BUG FIX: DAG2 trigger:chat MUST be ready - firing DAG1 should NOT block other DAGs");
        }

        @Test
        @DisplayName("Same trigger should NOT be ready again after it was already fired in this epoch")
        void sameTriggerShouldNotBeReadyAfterFired() {
            // Ensures triggers don't re-fire after completion (original intent of the epoch check)
            StateSnapshotService snapshotService = mock(StateSnapshotService.class);
            ReadyNodeCalculator calculatorWithService = new ReadyNodeCalculator(mergeNodeAnalyzer, snapshotService, null);

            ExecutionNode trigger = mock(ExecutionNode.class);
            ExecutionNode step = mock(ExecutionNode.class);

            when(trigger.getNodeId()).thenReturn("trigger:webhook");
            when(trigger.getType()).thenReturn(NodeType.TRIGGER);
            when(trigger.isTriggerNode()).thenReturn(true);
            when(trigger.canExecute(context)).thenReturn(true);

            when(step.getNodeId()).thenReturn("mcp:step_a");
            when(step.getType()).thenReturn(NodeType.MCP);
            when(step.canExecute(context)).thenReturn(true);
            when(step.getNextNodes(any())).thenReturn(List.of());

            when(trigger.getNextNodes(any())).thenReturn(List.of(step));

            when(tree.getRootNodes()).thenReturn(List.of(trigger));
            when(tree.getPlan()).thenReturn(plan);
            when(context.runId()).thenReturn("run-456");

            // Trigger not in context (re-run scenario: context was reset but snapshot shows it ran)
            when(context.isCompleted("trigger:webhook")).thenReturn(false);
            when(context.isStarted("trigger:webhook")).thenReturn(false);

            // StateSnapshot says this trigger was already completed in this epoch
            // Build a real StateSnapshot with trigger:webhook completed in its DAG
            StateSnapshot snapshot = StateSnapshot.empty()
                .markNodeCompleted("trigger:webhook", "trigger:webhook")
                .markNodeCompleted("trigger:webhook", "mcp:step_a");
            when(snapshotService.getSnapshot("run-456")).thenReturn(snapshot);
            when(snapshotService.getCompletedNodeIds("run-456"))
                .thenReturn(Set.of("trigger:webhook", "mcp:step_a"));

            // When
            Set<String> readyNodes = calculatorWithService.calculateReadyNodes(context, tree);

            // Then - trigger should NOT be ready again (already fired in epoch)
            assertFalse(readyNodes.contains("trigger:webhook"),
                "Trigger should NOT be ready again after being fired in this epoch");
        }

        @Test
        @DisplayName("BUG FIX: Failed trigger should NOT be ready again in same epoch (prevents infinite re-execution)")
        void failedTriggerShouldNotBeReadyAgainInSameEpoch() {
            // Before fix: hasTriggerBeenExecutedInEpoch only checked completedNodeIds.
            // A failed trigger would NOT appear in completedNodeIds → method returned false
            // → trigger became "ready" again → infinite re-execution loop in SBS mode.
            // Fix: also check failedNodeIds.
            StateSnapshotService snapshotService = mock(StateSnapshotService.class);
            ReadyNodeCalculator calculatorWithService = new ReadyNodeCalculator(mergeNodeAnalyzer, snapshotService, null);

            ExecutionNode trigger = mock(ExecutionNode.class);
            when(trigger.getNodeId()).thenReturn("trigger:webhook");
            when(trigger.getType()).thenReturn(NodeType.TRIGGER);
            when(trigger.isTriggerNode()).thenReturn(true);
            when(trigger.canExecute(context)).thenReturn(true);
            when(trigger.getAllChildNodes()).thenReturn(List.of());

            when(tree.getRootNodes()).thenReturn(List.of(trigger));
            when(tree.getPlan()).thenReturn(plan);
            when(context.runId()).thenReturn("run-failed");

            // Trigger not in context (context was rebuilt after failure)
            when(context.isCompleted("trigger:webhook")).thenReturn(false);
            when(context.isStarted("trigger:webhook")).thenReturn(false);

            // StateSnapshot says this trigger FAILED in this epoch (e.g., validation error)
            StateSnapshot snapshot = StateSnapshot.empty()
                .markNodeFailed("trigger:webhook", "trigger:webhook");
            when(snapshotService.getSnapshot("run-failed")).thenReturn(snapshot);

            // When
            Set<String> readyNodes = calculatorWithService.calculateReadyNodes(context, tree);

            // Then - failed trigger should NOT be ready again in same epoch
            assertFalse(readyNodes.contains("trigger:webhook"),
                "BUG FIX: Failed trigger should NOT become ready again in same epoch (was causing infinite re-execution)");
        }

        @Test
        @DisplayName("BUG FIX: Failed chat trigger in shared DAG should not block other triggers")
        void failedChatTriggerInSharedDagShouldNotBlockOtherTriggers() {
            // Scenario: webhook + chat share a DAG. Chat fires but fails (message validation).
            // Manual/webhook should still be fireable.
            StateSnapshotService snapshotService = mock(StateSnapshotService.class);
            ReadyNodeCalculator calculatorWithService = new ReadyNodeCalculator(mergeNodeAnalyzer, snapshotService, null);

            ExecutionNode chatTrigger = mock(ExecutionNode.class);
            ExecutionNode webhookTrigger = mock(ExecutionNode.class);
            ExecutionNode step = mock(ExecutionNode.class);

            when(chatTrigger.getNodeId()).thenReturn("trigger:chat");
            when(chatTrigger.getType()).thenReturn(NodeType.TRIGGER);
            when(chatTrigger.isTriggerNode()).thenReturn(true);
            when(chatTrigger.getAllChildNodes()).thenReturn(List.of());

            when(webhookTrigger.getNodeId()).thenReturn("trigger:webhook");
            when(webhookTrigger.getType()).thenReturn(NodeType.TRIGGER);
            when(webhookTrigger.isTriggerNode()).thenReturn(true);
            when(webhookTrigger.canExecute(context)).thenReturn(true);
            when(webhookTrigger.getAllChildNodes()).thenReturn(List.of());

            when(step.getNodeId()).thenReturn("mcp:process");
            when(step.getType()).thenReturn(NodeType.MCP);

            when(tree.getRootNodes()).thenReturn(List.of(chatTrigger, webhookTrigger));
            when(tree.getPlan()).thenReturn(plan);
            when(context.runId()).thenReturn("run-shared-fail");

            // Chat trigger failed, webhook not fired yet
            when(context.isCompleted("trigger:chat")).thenReturn(false);
            when(context.isStarted("trigger:chat")).thenReturn(false);
            when(context.isCompleted("trigger:webhook")).thenReturn(false);
            when(context.isStarted("trigger:webhook")).thenReturn(false);

            // StateSnapshot: chat trigger failed in its epoch
            StateSnapshot snapshot = StateSnapshot.empty()
                .markNodeFailed("trigger:chat", "trigger:chat");
            when(snapshotService.getSnapshot("run-shared-fail")).thenReturn(snapshot);

            // When
            Set<String> readyNodes = calculatorWithService.calculateReadyNodes(context, tree);

            // Then
            assertFalse(readyNodes.contains("trigger:chat"),
                "Failed chat trigger should NOT be ready again");
            assertTrue(readyNodes.contains("trigger:webhook"),
                "Webhook trigger should still be ready (chat failure is isolated)");
        }
    }

    @Nested
    @DisplayName("Multi-DAG Stress Tests")
    class MultiDagStressTests {

        /**
         * Helper to create a trigger mock with all standard stubs.
         */
        private ExecutionNode makeTrigger(String id) {
            ExecutionNode node = mock(ExecutionNode.class);
            when(node.getNodeId()).thenReturn(id);
            when(node.getType()).thenReturn(NodeType.TRIGGER);
            when(node.isTriggerNode()).thenReturn(true);
            when(node.getAllChildNodes()).thenReturn(List.of());
            return node;
        }

        /**
         * Helper to create a step mock with all standard stubs.
         */
        private ExecutionNode makeStep(String id, NodeType type) {
            ExecutionNode node = mock(ExecutionNode.class);
            when(node.getNodeId()).thenReturn(id);
            when(node.getType()).thenReturn(type);
            when(node.getAllChildNodes()).thenReturn(List.of());
            when(node.getNextNodes(any())).thenReturn(List.of());
            return node;
        }

        @Test
        @DisplayName("STRESS: 5 DAGs with 10 triggers - only unfired triggers stay ready")
        void stress5DagsWith10NodesMixed() {
            StateSnapshotService snapshotService = mock(StateSnapshotService.class);
            ReadyNodeCalculator calc = new ReadyNodeCalculator(mergeNodeAnalyzer, snapshotService, null);

            // 5 triggers, 3 fired (completed), 2 not fired
            ExecutionNode t1 = makeTrigger("trigger:webhook_1");
            ExecutionNode t2 = makeTrigger("trigger:webhook_2");
            ExecutionNode t3 = makeTrigger("trigger:chat");
            ExecutionNode t4 = makeTrigger("trigger:schedule");
            ExecutionNode t5 = makeTrigger("trigger:datasource");

            // Each trigger has one successor
            ExecutionNode s1 = makeStep("mcp:step_1", NodeType.MCP);
            ExecutionNode s2 = makeStep("mcp:step_2", NodeType.MCP);
            ExecutionNode s3 = makeStep("agent:reply", NodeType.AGENT);
            ExecutionNode s4 = makeStep("table:cleanup", NodeType.MCP);
            ExecutionNode s5 = makeStep("mcp:sync", NodeType.MCP);

            when(t1.getNextNodes(any())).thenReturn(List.of(s1));
            when(t2.getNextNodes(any())).thenReturn(List.of(s2));
            when(t3.getNextNodes(any())).thenReturn(List.of(s3));
            when(t4.getNextNodes(any())).thenReturn(List.of(s4));
            when(t5.getNextNodes(any())).thenReturn(List.of(s5));

            when(tree.getRootNodes()).thenReturn(List.of(t1, t2, t3, t4, t5));
            when(tree.getPlan()).thenReturn(plan);
            when(context.runId()).thenReturn("run-stress");

            // Triggers 1, 2, 3 are completed - their successors are ready
            for (var pair : List.of(
                    Map.entry("trigger:webhook_1", "mcp:step_1"),
                    Map.entry("trigger:webhook_2", "mcp:step_2"),
                    Map.entry("trigger:chat", "agent:reply"))) {
                when(context.isCompleted(pair.getKey())).thenReturn(true);
                when(context.getStepOutput(pair.getKey())).thenReturn(Optional.of(Map.of()));
                when(context.isCompleted(pair.getValue())).thenReturn(false);
                when(context.isStarted(pair.getValue())).thenReturn(false);
                when(mergeNodeAnalyzer.isMergeNode(plan, pair.getValue())).thenReturn(false);
            }
            when(s1.canExecute(context)).thenReturn(true);
            when(s2.canExecute(context)).thenReturn(true);
            when(s3.canExecute(context)).thenReturn(true);

            // Triggers 4, 5 are NOT fired yet
            when(context.isCompleted("trigger:schedule")).thenReturn(false);
            when(context.isStarted("trigger:schedule")).thenReturn(false);
            when(context.isCompleted("trigger:datasource")).thenReturn(false);
            when(context.isStarted("trigger:datasource")).thenReturn(false);
            when(t4.canExecute(context)).thenReturn(true);
            when(t5.canExecute(context)).thenReturn(true);
            when(mergeNodeAnalyzer.isMergeNode(plan, "trigger:schedule")).thenReturn(false);
            when(mergeNodeAnalyzer.isMergeNode(plan, "trigger:datasource")).thenReturn(false);

            // Snapshot: 3 triggers completed - build a real StateSnapshot with per-DAG state
            StateSnapshot snapshot = StateSnapshot.empty()
                .markNodeCompleted("trigger:webhook_1", "trigger:webhook_1")
                .markNodeCompleted("trigger:webhook_2", "trigger:webhook_2")
                .markNodeCompleted("trigger:chat", "trigger:chat");
            when(snapshotService.getSnapshot("run-stress")).thenReturn(snapshot);
            when(snapshotService.getCompletedNodeIds("run-stress"))
                .thenReturn(Set.of("trigger:webhook_1", "trigger:webhook_2", "trigger:chat"));

            Set<String> ready = calc.calculateReadyNodes(context, tree);

            // 3 successors + 2 unfired triggers = 5 ready nodes
            assertEquals(5, ready.size());
            assertTrue(ready.contains("mcp:step_1"));
            assertTrue(ready.contains("mcp:step_2"));
            assertTrue(ready.contains("agent:reply"));
            assertTrue(ready.contains("trigger:schedule"), "Unfired trigger:schedule must be ready");
            assertTrue(ready.contains("trigger:datasource"), "Unfired trigger:datasource must be ready");
        }

        @Test
        @DisplayName("STRESS: Deep chain (8 nodes) - ready node is at the tip")
        void stressDeepChain8Nodes() {
            // trigger -> n1 -> n2 -> n3 -> n4 -> n5 -> n6 -> n7 (ready)
            ExecutionNode trigger = makeTrigger("trigger:deep");
            List<ExecutionNode> chain = new java.util.ArrayList<>();
            for (int i = 1; i <= 7; i++) {
                chain.add(makeStep("mcp:node_" + i, NodeType.MCP));
            }
            // Wire: trigger -> n1 -> n2 -> ... -> n7
            when(trigger.getNextNodes(any())).thenReturn(List.of(chain.get(0)));
            for (int i = 0; i < chain.size() - 1; i++) {
                when(chain.get(i).getNextNodes(any())).thenReturn(List.of(chain.get(i + 1)));
            }

            when(tree.getRootNodes()).thenReturn(List.of(trigger));
            when(tree.getPlan()).thenReturn(plan);

            // All completed up to n6, n7 is ready
            when(context.isCompleted("trigger:deep")).thenReturn(true);
            when(context.getStepOutput("trigger:deep")).thenReturn(Optional.of(Map.of()));
            for (int i = 0; i < 6; i++) {
                String id = "mcp:node_" + (i + 1);
                when(context.isCompleted(id)).thenReturn(true);
                when(context.getStepOutput(id)).thenReturn(Optional.of(Map.of()));
            }
            when(context.isCompleted("mcp:node_7")).thenReturn(false);
            when(context.isStarted("mcp:node_7")).thenReturn(false);
            when(chain.get(6).canExecute(context)).thenReturn(true);
            when(mergeNodeAnalyzer.isMergeNode(plan, "mcp:node_7")).thenReturn(false);

            Set<String> ready = calculator.calculateReadyNodes(context, tree);

            assertEquals(1, ready.size());
            assertTrue(ready.contains("mcp:node_7"));
        }

        @Test
        @DisplayName("STRESS: DAG1 fully complete, DAG2 deep chain partially complete - independent")
        void stressDag1CompleteDag2DeepPartial() {
            StateSnapshotService snapshotService = mock(StateSnapshotService.class);
            ReadyNodeCalculator calc = new ReadyNodeCalculator(mergeNodeAnalyzer, snapshotService, null);

            // DAG1: trigger -> step_a -> step_b (all complete, nothing ready)
            ExecutionNode t1 = makeTrigger("trigger:dag1");
            ExecutionNode s1a = makeStep("mcp:dag1_a", NodeType.MCP);
            ExecutionNode s1b = makeStep("mcp:dag1_b", NodeType.MCP);
            when(t1.getNextNodes(any())).thenReturn(List.of(s1a));
            when(s1a.getNextNodes(any())).thenReturn(List.of(s1b));

            // DAG2: trigger -> a -> b -> c -> d -> e (trigger+a+b complete, c ready)
            ExecutionNode t2 = makeTrigger("trigger:dag2");
            ExecutionNode s2a = makeStep("mcp:dag2_a", NodeType.MCP);
            ExecutionNode s2b = makeStep("mcp:dag2_b", NodeType.MCP);
            ExecutionNode s2c = makeStep("mcp:dag2_c", NodeType.MCP);
            ExecutionNode s2d = makeStep("mcp:dag2_d", NodeType.MCP);
            ExecutionNode s2e = makeStep("mcp:dag2_e", NodeType.MCP);
            when(t2.getNextNodes(any())).thenReturn(List.of(s2a));
            when(s2a.getNextNodes(any())).thenReturn(List.of(s2b));
            when(s2b.getNextNodes(any())).thenReturn(List.of(s2c));
            when(s2c.getNextNodes(any())).thenReturn(List.of(s2d));
            when(s2d.getNextNodes(any())).thenReturn(List.of(s2e));

            when(tree.getRootNodes()).thenReturn(List.of(t1, t2));
            when(tree.getPlan()).thenReturn(plan);
            when(context.runId()).thenReturn("run-deep");

            // DAG1: all completed
            for (String id : List.of("trigger:dag1", "mcp:dag1_a", "mcp:dag1_b")) {
                when(context.isCompleted(id)).thenReturn(true);
                when(context.getStepOutput(id)).thenReturn(Optional.of(Map.of()));
            }

            // DAG2: trigger + a + b completed, c not started
            for (String id : List.of("trigger:dag2", "mcp:dag2_a", "mcp:dag2_b")) {
                when(context.isCompleted(id)).thenReturn(true);
                when(context.getStepOutput(id)).thenReturn(Optional.of(Map.of()));
            }
            when(context.isCompleted("mcp:dag2_c")).thenReturn(false);
            when(context.isStarted("mcp:dag2_c")).thenReturn(false);
            when(s2c.canExecute(context)).thenReturn(true);
            when(mergeNodeAnalyzer.isMergeNode(plan, "mcp:dag2_c")).thenReturn(false);

            when(snapshotService.getCompletedNodeIds("run-deep"))
                .thenReturn(Set.of("trigger:dag1", "mcp:dag1_a", "mcp:dag1_b",
                                   "trigger:dag2", "mcp:dag2_a", "mcp:dag2_b"));

            Set<String> ready = calc.calculateReadyNodes(context, tree);

            assertEquals(1, ready.size());
            assertTrue(ready.contains("mcp:dag2_c"), "Only dag2_c should be ready");
        }

        @Test
        @DisplayName("STRESS: DAG with failed node - other DAG unaffected")
        void stressDagWithFailedNodeDoesNotAffectOther() {
            // DAG1: trigger -> step_fail (started, running/failed - not completed)
            // DAG2: trigger -> step_ok (ready)
            ExecutionNode t1 = makeTrigger("trigger:dag1");
            ExecutionNode fail = makeStep("mcp:fail", NodeType.MCP);
            when(t1.getNextNodes(any())).thenReturn(List.of(fail));

            ExecutionNode t2 = makeTrigger("trigger:dag2");
            ExecutionNode ok = makeStep("mcp:ok", NodeType.MCP);
            when(t2.getNextNodes(any())).thenReturn(List.of(ok));
            when(ok.canExecute(context)).thenReturn(true);

            when(tree.getRootNodes()).thenReturn(List.of(t1, t2));
            when(tree.getPlan()).thenReturn(plan);

            // DAG1: trigger completed, fail step started (running)
            when(context.isCompleted("trigger:dag1")).thenReturn(true);
            when(context.getStepOutput("trigger:dag1")).thenReturn(Optional.of(Map.of()));
            when(context.isCompleted("mcp:fail")).thenReturn(false);
            when(context.isStarted("mcp:fail")).thenReturn(true); // running

            // DAG2: trigger completed, ok step ready
            when(context.isCompleted("trigger:dag2")).thenReturn(true);
            when(context.getStepOutput("trigger:dag2")).thenReturn(Optional.of(Map.of()));
            when(context.isCompleted("mcp:ok")).thenReturn(false);
            when(context.isStarted("mcp:ok")).thenReturn(false);
            when(mergeNodeAnalyzer.isMergeNode(plan, "mcp:ok")).thenReturn(false);

            Set<String> ready = calculator.calculateReadyNodes(context, tree);

            assertTrue(ready.contains("mcp:ok"), "DAG2 step should be ready");
            assertFalse(ready.contains("mcp:fail"), "DAG1 failed step should NOT be ready (already started)");
            assertEquals(1, ready.size());
        }

        @Test
        @DisplayName("STRESS: Empty DAG (trigger with no successors) - trigger is ready, nothing else")
        void stressEmptyDag() {
            ExecutionNode t1 = makeTrigger("trigger:empty");
            when(t1.getNextNodes(any())).thenReturn(List.of());
            when(t1.canExecute(context)).thenReturn(true);

            ExecutionNode t2 = makeTrigger("trigger:real");
            ExecutionNode step = makeStep("mcp:step", NodeType.MCP);
            when(t2.getNextNodes(any())).thenReturn(List.of(step));
            when(t2.canExecute(context)).thenReturn(true);
            when(step.canExecute(context)).thenReturn(true);

            when(tree.getRootNodes()).thenReturn(List.of(t1, t2));
            when(tree.getPlan()).thenReturn(plan);

            // Neither trigger completed
            when(context.isCompleted("trigger:empty")).thenReturn(false);
            when(context.isStarted("trigger:empty")).thenReturn(false);
            when(context.isCompleted("trigger:real")).thenReturn(false);
            when(context.isStarted("trigger:real")).thenReturn(false);
            when(mergeNodeAnalyzer.isMergeNode(plan, "trigger:empty")).thenReturn(false);
            when(mergeNodeAnalyzer.isMergeNode(plan, "trigger:real")).thenReturn(false);

            Set<String> ready = calculator.calculateReadyNodes(context, tree);

            assertTrue(ready.contains("trigger:empty"), "Empty DAG trigger should be ready");
            assertTrue(ready.contains("trigger:real"), "Real DAG trigger should be ready");
            assertEquals(2, ready.size());
        }

        @Test
        @DisplayName("STRESS: All 5 triggers fired simultaneously - only successors are ready, no trigger re-fires")
        void stressAllTriggersAlreadyFired() {
            StateSnapshotService snapshotService = mock(StateSnapshotService.class);
            ReadyNodeCalculator calc = new ReadyNodeCalculator(mergeNodeAnalyzer, snapshotService, null);

            List<ExecutionNode> triggers = new java.util.ArrayList<>();
            List<ExecutionNode> steps = new java.util.ArrayList<>();
            Set<String> completedSet = new java.util.HashSet<>();

            for (int i = 0; i < 5; i++) {
                String tId = "trigger:t" + i;
                String sId = "mcp:s" + i;
                ExecutionNode t = makeTrigger(tId);
                ExecutionNode s = makeStep(sId, NodeType.MCP);
                when(t.getNextNodes(any())).thenReturn(List.of(s));
                when(s.canExecute(context)).thenReturn(true);

                // All triggers completed
                when(context.isCompleted(tId)).thenReturn(true);
                when(context.getStepOutput(tId)).thenReturn(Optional.of(Map.of()));
                when(context.isCompleted(sId)).thenReturn(false);
                when(context.isStarted(sId)).thenReturn(false);
                when(mergeNodeAnalyzer.isMergeNode(plan, sId)).thenReturn(false);

                triggers.add(t);
                steps.add(s);
                completedSet.add(tId);
            }

            when(tree.getRootNodes()).thenReturn(triggers);
            when(tree.getPlan()).thenReturn(plan);
            when(context.runId()).thenReturn("run-all-fired");
            when(snapshotService.getCompletedNodeIds("run-all-fired")).thenReturn(completedSet);

            Set<String> ready = calc.calculateReadyNodes(context, tree);

            // All 5 successors ready, no trigger re-fires
            assertEquals(5, ready.size());
            for (int i = 0; i < 5; i++) {
                assertTrue(ready.contains("mcp:s" + i), "Step s" + i + " should be ready");
                assertFalse(ready.contains("trigger:t" + i), "Trigger t" + i + " should NOT re-fire");
            }
        }

        @Test
        @DisplayName("STRESS: Two DAGs with implicit fork (trigger has 2 successors) - both successors ready")
        void stressImplicitForkInMultiDag() {
            // DAG1: trigger -> [step_a, step_b] (implicit fork)
            // DAG2: trigger -> step_c
            ExecutionNode t1 = makeTrigger("trigger:dag1");
            ExecutionNode sa = makeStep("mcp:a", NodeType.MCP);
            ExecutionNode sb = makeStep("mcp:b", NodeType.MCP);
            when(t1.getNextNodes(any())).thenReturn(List.of(sa, sb));
            when(sa.canExecute(context)).thenReturn(true);
            when(sb.canExecute(context)).thenReturn(true);

            ExecutionNode t2 = makeTrigger("trigger:dag2");
            ExecutionNode sc = makeStep("mcp:c", NodeType.MCP);
            when(t2.getNextNodes(any())).thenReturn(List.of(sc));
            when(sc.canExecute(context)).thenReturn(true);

            when(tree.getRootNodes()).thenReturn(List.of(t1, t2));
            when(tree.getPlan()).thenReturn(plan);

            // DAG1: trigger completed
            when(context.isCompleted("trigger:dag1")).thenReturn(true);
            when(context.getStepOutput("trigger:dag1")).thenReturn(Optional.of(Map.of()));
            when(context.isCompleted("mcp:a")).thenReturn(false);
            when(context.isStarted("mcp:a")).thenReturn(false);
            when(context.isCompleted("mcp:b")).thenReturn(false);
            when(context.isStarted("mcp:b")).thenReturn(false);
            when(mergeNodeAnalyzer.isMergeNode(plan, "mcp:a")).thenReturn(false);
            when(mergeNodeAnalyzer.isMergeNode(plan, "mcp:b")).thenReturn(false);

            // DAG2: trigger completed
            when(context.isCompleted("trigger:dag2")).thenReturn(true);
            when(context.getStepOutput("trigger:dag2")).thenReturn(Optional.of(Map.of()));
            when(context.isCompleted("mcp:c")).thenReturn(false);
            when(context.isStarted("mcp:c")).thenReturn(false);
            when(mergeNodeAnalyzer.isMergeNode(plan, "mcp:c")).thenReturn(false);

            Set<String> ready = calculator.calculateReadyNodes(context, tree);

            assertEquals(3, ready.size());
            assertTrue(ready.contains("mcp:a"));
            assertTrue(ready.contains("mcp:b"));
            assertTrue(ready.contains("mcp:c"));
        }

        @Test
        @DisplayName("STRESS: DAG with decision skips else branch - skipped successors NOT ready, other DAG unaffected")
        void stressDecisionSkipBranchDoesNotAffectOtherDag() {
            // DAG1: trigger -> decision (if taken) -> mcp:if_step (ready)
            //                                     -> mcp:else_step (skipped, NOT ready)
            // DAG2: trigger -> agent:reply (ready)
            ExecutionNode t1 = makeTrigger("trigger:dag1");
            ExecutionNode decision = makeStep("core:check", NodeType.DECISION);
            when(decision.isDecisionNode()).thenReturn(true);
            ExecutionNode ifStep = makeStep("mcp:if_step", NodeType.MCP);
            ExecutionNode elseStep = makeStep("mcp:else_step", NodeType.MCP);

            when(t1.getNextNodes(any())).thenReturn(List.of(decision));
            // Decision completed, selected if branch
            when(decision.getNextNodes(any())).thenReturn(List.of(ifStep));
            when(decision.getAllChildNodes()).thenReturn(List.of(ifStep, elseStep));
            when(ifStep.canExecute(context)).thenReturn(true);

            ExecutionNode t2 = makeTrigger("trigger:dag2");
            ExecutionNode reply = makeStep("agent:reply", NodeType.AGENT);
            when(t2.getNextNodes(any())).thenReturn(List.of(reply));
            when(reply.canExecute(context)).thenReturn(true);

            when(tree.getRootNodes()).thenReturn(List.of(t1, t2));
            when(tree.getPlan()).thenReturn(plan);

            // DAG1
            when(context.isCompleted("trigger:dag1")).thenReturn(true);
            when(context.getStepOutput("trigger:dag1")).thenReturn(Optional.of(Map.of()));
            when(context.isCompleted("core:check")).thenReturn(true);
            when(context.getStepOutput("core:check")).thenReturn(Optional.of(Map.of("selected_branch_index", 0)));
            when(context.isCompleted("mcp:if_step")).thenReturn(false);
            when(context.isStarted("mcp:if_step")).thenReturn(false);
            when(mergeNodeAnalyzer.isMergeNode(plan, "mcp:if_step")).thenReturn(false);

            // DAG2
            when(context.isCompleted("trigger:dag2")).thenReturn(true);
            when(context.getStepOutput("trigger:dag2")).thenReturn(Optional.of(Map.of()));
            when(context.isCompleted("agent:reply")).thenReturn(false);
            when(context.isStarted("agent:reply")).thenReturn(false);
            when(mergeNodeAnalyzer.isMergeNode(plan, "agent:reply")).thenReturn(false);

            Set<String> ready = calculator.calculateReadyNodes(context, tree);

            assertTrue(ready.contains("mcp:if_step"), "if_step should be ready");
            assertFalse(ready.contains("mcp:else_step"), "else_step should NOT be ready (not in getNextNodes)");
            assertTrue(ready.contains("agent:reply"), "DAG2 reply should be ready");
        }
    }

    @Nested
    @DisplayName("Branching Node Fallback (no output in context)")
    class BranchingNodeFallbackTests {

        /**
         * Helper to create a branching node mock (decision, switch, option, approval, classify).
         */
        private ExecutionNode makeBranchingNode(String id, NodeType type, boolean isDecision, boolean isSwitch, boolean isOption, boolean isApproval) {
            ExecutionNode node = mock(ExecutionNode.class);
            when(node.getNodeId()).thenReturn(id);
            when(node.getType()).thenReturn(type);
            when(node.isBranchingNode()).thenReturn(true);
            when(node.isDecisionNode()).thenReturn(isDecision);
            when(node.isSwitchNode()).thenReturn(isSwitch);
            when(node.isOptionNode()).thenReturn(isOption);
            when(node.isApprovalNode()).thenReturn(isApproval);
            // Synthetic result: getNextNodes returns empty (no selection info)
            when(node.getNextNodes(any())).thenReturn(List.of());
            return node;
        }

        private ExecutionNode makeStep(String id) {
            ExecutionNode node = mock(ExecutionNode.class);
            when(node.getNodeId()).thenReturn(id);
            when(node.getType()).thenReturn(NodeType.MCP);
            when(node.getAllChildNodes()).thenReturn(List.of());
            when(node.getNextNodes(any())).thenReturn(List.of());
            return node;
        }

        @Test
        @DisplayName("BUG FIX: Decision with no output - should only traverse executed branch, not re-enable skipped else")
        void decisionNoOutput_shouldOnlyTraverseExecutedBranch() {
            // Scenario: trigger -> decision -> [if_step (completed), else_step (pending)]
            // Decision output NOT in context (synthetic result) - simulates DB reload
            // After if_step completes, else_step should NOT become READY
            StateSnapshotService snapshotService = mock(StateSnapshotService.class);
            ReadyNodeCalculator calc = new ReadyNodeCalculator(mergeNodeAnalyzer, snapshotService, null);

            ExecutionNode trigger = mock(ExecutionNode.class);
            when(trigger.getNodeId()).thenReturn("trigger:manual");
            when(trigger.getType()).thenReturn(NodeType.TRIGGER);
            when(trigger.getAllChildNodes()).thenReturn(List.of());

            ExecutionNode decision = makeBranchingNode("core:if_else", NodeType.DECISION, true, false, false, false);
            ExecutionNode ifStep = makeStep("mcp:if_target");
            ExecutionNode elseStep = makeStep("mcp:else_target");
            when(decision.getAllChildNodes()).thenReturn(List.of(ifStep, elseStep));

            when(trigger.getNextNodes(any())).thenReturn(List.of(decision));

            when(tree.getRootNodes()).thenReturn(List.of(trigger));
            when(tree.getPlan()).thenReturn(plan);
            when(context.runId()).thenReturn("run-1");

            // Trigger: completed with output
            when(context.isCompleted("trigger:manual")).thenReturn(true);
            when(context.getStepOutput("trigger:manual")).thenReturn(Optional.of(Map.of("data", "ok")));

            // Decision: completed in snapshot but NO output in context (the bug scenario)
            when(context.isCompleted("core:if_else")).thenReturn(true);
            when(context.getStepOutput("core:if_else")).thenReturn(Optional.empty());
            when(snapshotService.getCompletedNodeIds("run-1"))
                .thenReturn(Set.of("trigger:manual", "core:if_else", "mcp:if_target"));

            // if_step: completed (the taken branch)
            when(context.isCompleted("mcp:if_target")).thenReturn(true);
            when(context.getStepOutput("mcp:if_target")).thenReturn(Optional.of(Map.of("result", "done")));

            // else_step: NOT started, NOT completed (the skipped branch)
            when(context.isCompleted("mcp:else_target")).thenReturn(false);
            when(context.isStarted("mcp:else_target")).thenReturn(false);
            when(elseStep.canExecute(context)).thenReturn(true);
            when(mergeNodeAnalyzer.isMergeNode(plan, "mcp:else_target")).thenReturn(false);

            // When
            Set<String> ready = calc.calculateReadyNodes(context, tree);

            // Then - else_step should NOT be ready (branch was not taken)
            assertFalse(ready.contains("mcp:else_target"),
                "BUG FIX: else branch must NOT become READY when decision output is missing from context");
            assertTrue(ready.isEmpty(),
                "No nodes should be ready - workflow should be detected as complete");
        }

        @Test
        @DisplayName("Switch with no output - should only traverse executed case, not re-enable default")
        void switchNoOutput_shouldOnlyTraverseExecutedCase() {
            StateSnapshotService snapshotService = mock(StateSnapshotService.class);
            ReadyNodeCalculator calc = new ReadyNodeCalculator(mergeNodeAnalyzer, snapshotService, null);

            ExecutionNode trigger = mock(ExecutionNode.class);
            when(trigger.getNodeId()).thenReturn("trigger:start");
            when(trigger.getType()).thenReturn(NodeType.TRIGGER);
            when(trigger.getAllChildNodes()).thenReturn(List.of());

            ExecutionNode switchNode = makeBranchingNode("core:router", NodeType.SWITCH, false, true, false, false);
            ExecutionNode case0Step = makeStep("mcp:case_a");
            ExecutionNode case1Step = makeStep("mcp:case_b");
            ExecutionNode defaultStep = makeStep("mcp:default_handler");
            when(switchNode.getAllChildNodes()).thenReturn(List.of(case0Step, case1Step, defaultStep));

            when(trigger.getNextNodes(any())).thenReturn(List.of(switchNode));

            when(tree.getRootNodes()).thenReturn(List.of(trigger));
            when(tree.getPlan()).thenReturn(plan);
            when(context.runId()).thenReturn("run-2");

            when(context.isCompleted("trigger:start")).thenReturn(true);
            when(context.getStepOutput("trigger:start")).thenReturn(Optional.of(Map.of()));

            // Switch completed, no output in context
            when(context.isCompleted("core:router")).thenReturn(true);
            when(context.getStepOutput("core:router")).thenReturn(Optional.empty());
            when(snapshotService.getCompletedNodeIds("run-2"))
                .thenReturn(Set.of("trigger:start", "core:router", "mcp:case_b"));

            // case_b: completed (the taken case)
            when(context.isCompleted("mcp:case_b")).thenReturn(true);
            when(context.getStepOutput("mcp:case_b")).thenReturn(Optional.of(Map.of()));

            // case_a and default: not started
            when(context.isCompleted("mcp:case_a")).thenReturn(false);
            when(context.isStarted("mcp:case_a")).thenReturn(false);
            when(context.isCompleted("mcp:default_handler")).thenReturn(false);
            when(context.isStarted("mcp:default_handler")).thenReturn(false);
            when(case0Step.canExecute(context)).thenReturn(true);
            when(defaultStep.canExecute(context)).thenReturn(true);
            when(mergeNodeAnalyzer.isMergeNode(plan, "mcp:case_a")).thenReturn(false);
            when(mergeNodeAnalyzer.isMergeNode(plan, "mcp:default_handler")).thenReturn(false);

            Set<String> ready = calc.calculateReadyNodes(context, tree);

            assertFalse(ready.contains("mcp:case_a"), "Non-taken case_a must NOT be ready");
            assertFalse(ready.contains("mcp:default_handler"), "Non-taken default must NOT be ready");
            assertTrue(ready.isEmpty(), "Workflow should be complete");
        }

        @Test
        @DisplayName("regression: single-item split switch traverses all branch targets so non-selected branches can persist SKIPPED")
        void singleItemSplitSwitchTraversesAllBranchesForSkipMaterialization() {
            ExecutionNode switchNode = makeBranchingNode("core:route_item", NodeType.SWITCH, false, true, false, false);
            ExecutionNode finance = makeStep("core:apply_finance");
            ExecutionNode ops = makeStep("core:apply_ops");
            ExecutionNode fallback = makeStep("core:apply_default");

            when(switchNode.getNextNodes(any())).thenReturn(List.of(finance));
            when(switchNode.getAllChildNodes()).thenReturn(List.of(finance, ops, fallback));

            when(tree.getRootNodes()).thenReturn(List.of(switchNode));
            when(tree.getPlan()).thenReturn(plan);

            when(context.isCompleted("core:route_item")).thenReturn(true);
            when(context.isStarted("core:route_item")).thenReturn(true);
            when(context.isSkipped("core:route_item")).thenReturn(false);
            when(context.isFailed("core:route_item")).thenReturn(false);
            when(context.getStepOutput("core:route_item")).thenReturn(Optional.of(Map.of(
                ExecutionMetadataKeys.SPLIT_ITEM_COUNT, 1,
                "selected_branch", "case_0",
                "selected_branch_index", 0
            )));

            for (ExecutionNode branch : List.of(finance, ops, fallback)) {
                String branchId = branch.getNodeId();
                when(context.isCompleted(branchId)).thenReturn(false);
                when(context.isStarted(branchId)).thenReturn(false);
                when(branch.canExecute(context)).thenReturn(true);
                when(mergeNodeAnalyzer.isMergeNode(plan, branchId)).thenReturn(false);
            }

            Set<String> ready = calculator.calculateReadyNodes(context, tree);

            assertEquals(Set.of("core:apply_finance", "core:apply_ops", "core:apply_default"), ready,
                "All switch branches in a one-item split must become ready so SplitAwareNodeExecutor "
                    + "can persist SKIPPED rows for non-selected branches");
        }

        @Test
        @DisplayName("regression: single-item split decision traverses all branch targets so non-selected branches can persist SKIPPED")
        void singleItemSplitDecisionTraversesAllBranchesForSkipMaterialization() {
            ExecutionNode decision = makeBranchingNode("core:check_item", NodeType.DECISION, true, false, false, false);
            ExecutionNode yes = makeStep("core:yes_path");
            ExecutionNode no = makeStep("core:no_path");

            when(decision.getNextNodes(any())).thenReturn(List.of(yes));
            when(decision.getAllChildNodes()).thenReturn(List.of(yes, no));

            when(tree.getRootNodes()).thenReturn(List.of(decision));
            when(tree.getPlan()).thenReturn(plan);

            when(context.isCompleted("core:check_item")).thenReturn(true);
            when(context.isStarted("core:check_item")).thenReturn(true);
            when(context.isSkipped("core:check_item")).thenReturn(false);
            when(context.isFailed("core:check_item")).thenReturn(false);
            when(context.getStepOutput("core:check_item")).thenReturn(Optional.of(Map.of(
                ExecutionMetadataKeys.SPLIT_ITEM_COUNT, 1,
                "selected_branch", "if",
                "selected_branch_index", 0
            )));

            for (ExecutionNode branch : List.of(yes, no)) {
                String branchId = branch.getNodeId();
                when(context.isCompleted(branchId)).thenReturn(false);
                when(context.isStarted(branchId)).thenReturn(false);
                when(branch.canExecute(context)).thenReturn(true);
                when(mergeNodeAnalyzer.isMergeNode(plan, branchId)).thenReturn(false);
            }

            Set<String> ready = calculator.calculateReadyNodes(context, tree);

            assertEquals(Set.of("core:yes_path", "core:no_path"), ready,
                "All decision branches in a one-item split must become ready so SplitAwareNodeExecutor "
                    + "can persist SKIPPED rows for non-selected branches");
        }

        @Test
        @DisplayName("regression: single-item split approval traverses all branch targets so non-selected branches can persist SKIPPED")
        void singleItemSplitApprovalTraversesAllBranchesForSkipMaterialization() {
            ExecutionNode approval = makeBranchingNode("core:manager_approval", NodeType.APPROVAL, false, false, false, true);
            ExecutionNode approved = makeStep("core:approved_path");
            ExecutionNode rejected = makeStep("core:rejected_path");
            ExecutionNode timeout = makeStep("core:timeout_path");

            when(approval.getNextNodes(any())).thenReturn(List.of(approved));
            when(approval.getAllChildNodes()).thenReturn(List.of(approved, rejected, timeout));

            when(tree.getRootNodes()).thenReturn(List.of(approval));
            when(tree.getPlan()).thenReturn(plan);

            when(context.isCompleted("core:manager_approval")).thenReturn(true);
            when(context.isStarted("core:manager_approval")).thenReturn(true);
            when(context.isSkipped("core:manager_approval")).thenReturn(false);
            when(context.isFailed("core:manager_approval")).thenReturn(false);
            when(context.getStepOutput("core:manager_approval")).thenReturn(Optional.of(Map.of(
                ExecutionMetadataKeys.SPLIT_ITEM_COUNT, 1,
                "selected_port", "approved",
                "selectedBranch", "approved"
            )));

            for (ExecutionNode branch : List.of(approved, rejected, timeout)) {
                String branchId = branch.getNodeId();
                when(context.isCompleted(branchId)).thenReturn(false);
                when(context.isStarted(branchId)).thenReturn(false);
                when(branch.canExecute(context)).thenReturn(true);
                when(mergeNodeAnalyzer.isMergeNode(plan, branchId)).thenReturn(false);
            }

            Set<String> ready = calculator.calculateReadyNodes(context, tree);

            assertEquals(Set.of("core:approved_path", "core:rejected_path", "core:timeout_path"), ready,
                "All approval branches in a one-item split must become ready so SplitAwareNodeExecutor "
                    + "can persist SKIPPED rows for non-selected branches");
        }

        @Test
        @DisplayName("regression: single-item split classify traverses all category targets so non-selected categories can persist SKIPPED")
        void singleItemSplitClassifyTraversesAllCategoriesForSkipMaterialization() {
            ExecutionNode classify = mock(ExecutionNode.class);
            ExecutionNode finance = makeStep("mcp:label_finance");
            ExecutionNode ops = makeStep("mcp:label_ops");
            ExecutionNode fallback = makeStep("mcp:label_default");

            when(classify.getNodeId()).thenReturn("agent:classify");
            when(classify.getType()).thenReturn(NodeType.AGENT);
            when(classify.isAgentNode()).thenReturn(true);
            when(classify.getNextNodes(any())).thenReturn(List.of(finance));
            when(classify.getAllCategoryTargetNodes()).thenReturn(List.of(finance, ops, fallback));

            when(tree.getRootNodes()).thenReturn(List.of(classify));
            when(tree.getPlan()).thenReturn(plan);

            when(context.isCompleted("agent:classify")).thenReturn(true);
            when(context.isStarted("agent:classify")).thenReturn(true);
            when(context.isSkipped("agent:classify")).thenReturn(false);
            when(context.isFailed("agent:classify")).thenReturn(false);
            when(context.getStepOutput("agent:classify")).thenReturn(Optional.of(Map.of(
                ExecutionMetadataKeys.NODE_TYPE, "CLASSIFY",
                ExecutionMetadataKeys.SPLIT_ITEM_COUNT, 1,
                "selected_category", "finance"
            )));

            for (ExecutionNode category : List.of(finance, ops, fallback)) {
                String categoryId = category.getNodeId();
                when(context.isCompleted(categoryId)).thenReturn(false);
                when(context.isStarted(categoryId)).thenReturn(false);
                when(category.canExecute(context)).thenReturn(true);
                when(mergeNodeAnalyzer.isMergeNode(plan, categoryId)).thenReturn(false);
            }

            Set<String> ready = calculator.calculateReadyNodes(context, tree);

            assertEquals(Set.of("mcp:label_finance", "mcp:label_ops", "mcp:label_default"), ready,
                "All classify targets in a one-item split must become ready so SplitAwareNodeExecutor "
                    + "can persist SKIPPED rows for non-selected categories");
        }

        @Test
        @DisplayName("regression: single-item split guardrail traverses pass and fail targets so non-selected branch can persist SKIPPED")
        void singleItemSplitGuardrailTraversesAllTargetsForSkipMaterialization() {
            ExecutionNode guardrail = mock(ExecutionNode.class);
            ExecutionNode pass = makeStep("mcp:pass_path");
            ExecutionNode fail = makeStep("mcp:fail_path");

            when(guardrail.getNodeId()).thenReturn("agent:safety_gate");
            when(guardrail.getType()).thenReturn(NodeType.AGENT);
            when(guardrail.isAgentNode()).thenReturn(true);
            when(guardrail.getNextNodes(any())).thenReturn(List.of(pass));
            when(guardrail.getBranchTargetsByPort()).thenReturn(Map.of(
                "pass", List.of(pass),
                "fail", List.of(fail)));

            when(tree.getRootNodes()).thenReturn(List.of(guardrail));
            when(tree.getPlan()).thenReturn(plan);

            when(context.isCompleted("agent:safety_gate")).thenReturn(true);
            when(context.isStarted("agent:safety_gate")).thenReturn(true);
            when(context.isSkipped("agent:safety_gate")).thenReturn(false);
            when(context.isFailed("agent:safety_gate")).thenReturn(false);
            when(context.getStepOutput("agent:safety_gate")).thenReturn(Optional.of(Map.of(
                ExecutionMetadataKeys.NODE_TYPE, "GUARDRAIL",
                ExecutionMetadataKeys.SPLIT_ITEM_COUNT, 1,
                "passed", true
            )));

            for (ExecutionNode target : List.of(pass, fail)) {
                String targetId = target.getNodeId();
                when(context.isCompleted(targetId)).thenReturn(false);
                when(context.isStarted(targetId)).thenReturn(false);
                when(target.canExecute(context)).thenReturn(true);
                when(mergeNodeAnalyzer.isMergeNode(plan, targetId)).thenReturn(false);
            }

            Set<String> ready = calculator.calculateReadyNodes(context, tree);

            assertEquals(Set.of("mcp:pass_path", "mcp:fail_path"), ready,
                "Both guardrail targets in a one-item split must become ready so SplitAwareNodeExecutor "
                    + "can persist SKIPPED rows for the non-selected pass/fail branch");
        }

        @Test
        @DisplayName("regression: split option traverses all choice targets instead of only the aggregate selected choice")
        void splitOptionTraversesAllChoiceTargets() {
            ExecutionNode optionNode = makeBranchingNode("core:choose_path", NodeType.OPTION, false, false, true, false);
            ExecutionNode highPath = makeStep("mcp:high_path");
            ExecutionNode lowPath = makeStep("mcp:low_path");

            when(optionNode.getNextNodes(any())).thenReturn(List.of(highPath));
            when(optionNode.getAllChildNodes()).thenReturn(List.of(highPath, lowPath));
            when(tree.getRootNodes()).thenReturn(List.of(optionNode));
            when(tree.getPlan()).thenReturn(plan);

            when(context.isCompleted("core:choose_path")).thenReturn(true);
            when(context.isStarted("core:choose_path")).thenReturn(true);
            when(context.isSkipped("core:choose_path")).thenReturn(false);
            when(context.isFailed("core:choose_path")).thenReturn(false);
            when(context.getStepOutput("core:choose_path")).thenReturn(Optional.of(Map.of(
                ExecutionMetadataKeys.NODE_TYPE, "OPTION",
                ExecutionMetadataKeys.SPLIT_ITEM_COUNT, 5,
                "selected_choice_index", 0
            )));

            for (ExecutionNode target : List.of(highPath, lowPath)) {
                String targetId = target.getNodeId();
                when(context.isCompleted(targetId)).thenReturn(false);
                when(context.isStarted(targetId)).thenReturn(false);
                when(target.canExecute(context)).thenReturn(true);
                when(mergeNodeAnalyzer.isMergeNode(plan, targetId)).thenReturn(false);
            }

            Set<String> ready = calculator.calculateReadyNodes(context, tree);

            assertEquals(Set.of("mcp:high_path", "mcp:low_path"), ready,
                "Option in split context must expose every choice target; routing is filtered per item later");
        }

        @Test
        @DisplayName("Generic branching node (option/approval/classify) with no output - follows executed path only")
        void genericBranchingNoOutput_followsExecutedPathOnly() {
            StateSnapshotService snapshotService = mock(StateSnapshotService.class);
            ReadyNodeCalculator calc = new ReadyNodeCalculator(mergeNodeAnalyzer, snapshotService, null);

            ExecutionNode trigger = mock(ExecutionNode.class);
            when(trigger.getNodeId()).thenReturn("trigger:start");
            when(trigger.getType()).thenReturn(NodeType.TRIGGER);
            when(trigger.getAllChildNodes()).thenReturn(List.of());

            // Option node (branching) - same logic applies to approval and classify
            ExecutionNode optionNode = makeBranchingNode("core:option", NodeType.OPTION, false, false, true, false);
            ExecutionNode choice0 = makeStep("mcp:choice_a");
            ExecutionNode choice1 = makeStep("mcp:choice_b");
            when(optionNode.getAllChildNodes()).thenReturn(List.of(choice0, choice1));

            when(trigger.getNextNodes(any())).thenReturn(List.of(optionNode));

            when(tree.getRootNodes()).thenReturn(List.of(trigger));
            when(tree.getPlan()).thenReturn(plan);
            when(context.runId()).thenReturn("run-3");

            when(context.isCompleted("trigger:start")).thenReturn(true);
            when(context.getStepOutput("trigger:start")).thenReturn(Optional.of(Map.of()));

            // Option completed, no output
            when(context.isCompleted("core:option")).thenReturn(true);
            when(context.getStepOutput("core:option")).thenReturn(Optional.empty());
            when(snapshotService.getCompletedNodeIds("run-3"))
                .thenReturn(Set.of("trigger:start", "core:option"));

            // choice_a: started (the taken choice, currently executing)
            when(context.isCompleted("mcp:choice_a")).thenReturn(false);
            when(context.isStarted("mcp:choice_a")).thenReturn(true);

            // choice_b: not started (skipped choice)
            when(context.isCompleted("mcp:choice_b")).thenReturn(false);
            when(context.isStarted("mcp:choice_b")).thenReturn(false);
            when(choice1.canExecute(context)).thenReturn(true);
            when(mergeNodeAnalyzer.isMergeNode(plan, "mcp:choice_b")).thenReturn(false);

            Set<String> ready = calc.calculateReadyNodes(context, tree);

            assertFalse(ready.contains("mcp:choice_b"), "Non-taken choice_b must NOT be ready");
            assertFalse(ready.contains("mcp:choice_a"), "choice_a is already started, not ready");
        }

        @Test
        @DisplayName("Decision with no output AND no child executed yet - should traverse all (fallback)")
        void decisionNoOutput_noChildExecuted_shouldTraverseAll() {
            // Edge case: decision completed in snapshot but no child started yet AND output missing.
            // This can happen in multi-epoch rerun. Should traverse all to find readiness.
            StateSnapshotService snapshotService = mock(StateSnapshotService.class);
            ReadyNodeCalculator calc = new ReadyNodeCalculator(mergeNodeAnalyzer, snapshotService, null);

            ExecutionNode trigger = mock(ExecutionNode.class);
            when(trigger.getNodeId()).thenReturn("trigger:start");
            when(trigger.getType()).thenReturn(NodeType.TRIGGER);
            when(trigger.getAllChildNodes()).thenReturn(List.of());

            ExecutionNode decision = makeBranchingNode("core:check", NodeType.DECISION, true, false, false, false);
            ExecutionNode ifStep = makeStep("mcp:if_step");
            ExecutionNode elseStep = makeStep("mcp:else_step");
            when(decision.getAllChildNodes()).thenReturn(List.of(ifStep, elseStep));

            when(trigger.getNextNodes(any())).thenReturn(List.of(decision));

            when(tree.getRootNodes()).thenReturn(List.of(trigger));
            when(tree.getPlan()).thenReturn(plan);
            when(context.runId()).thenReturn("run-4");

            when(context.isCompleted("trigger:start")).thenReturn(true);
            when(context.getStepOutput("trigger:start")).thenReturn(Optional.of(Map.of()));

            // Decision completed in snapshot, no output
            when(context.isCompleted("core:check")).thenReturn(true);
            when(context.getStepOutput("core:check")).thenReturn(Optional.empty());
            when(snapshotService.getCompletedNodeIds("run-4"))
                .thenReturn(Set.of("trigger:start", "core:check"));

            // BOTH children not started, not completed
            when(context.isCompleted("mcp:if_step")).thenReturn(false);
            when(context.isStarted("mcp:if_step")).thenReturn(false);
            when(context.isCompleted("mcp:else_step")).thenReturn(false);
            when(context.isStarted("mcp:else_step")).thenReturn(false);
            when(ifStep.canExecute(context)).thenReturn(true);
            when(elseStep.canExecute(context)).thenReturn(true);
            when(mergeNodeAnalyzer.isMergeNode(plan, "mcp:if_step")).thenReturn(false);
            when(mergeNodeAnalyzer.isMergeNode(plan, "mcp:else_step")).thenReturn(false);

            Set<String> ready = calc.calculateReadyNodes(context, tree);

            // Both children should be traversed (fallback: no executed children found)
            assertTrue(ready.contains("mcp:if_step"), "if_step should be ready (fallback: all children)");
            assertTrue(ready.contains("mcp:else_step"), "else_step should be ready (fallback: all children)");
        }

        @Test
        @DisplayName("BUG FIX: Decision with no output, one child SKIPPED and one PENDING - should traverse all (PENDING child is ready)")
        void decisionNoOutput_oneChildSkippedOnePending_shouldTraverseAll() {
            // This is the exact e2e scenario: decision executed, skip propagated to else_branch,
            // but if_branch hasn't executed yet. When context is rebuilt from DB, decision output
            // is missing. The fallback must NOT treat the SKIPPED else_branch as the "executed path"
            // because it would skip traversing the PENDING if_branch entirely.
            StateSnapshotService snapshotService = mock(StateSnapshotService.class);
            ReadyNodeCalculator calc = new ReadyNodeCalculator(mergeNodeAnalyzer, snapshotService, null);

            ExecutionNode trigger = mock(ExecutionNode.class);
            when(trigger.getNodeId()).thenReturn("trigger:start");
            when(trigger.getType()).thenReturn(NodeType.TRIGGER);
            when(trigger.getAllChildNodes()).thenReturn(List.of());

            ExecutionNode decision = makeBranchingNode("core:check", NodeType.DECISION, true, false, false, false);
            ExecutionNode ifStep = makeStep("mcp:if_branch");
            ExecutionNode elseStep = makeStep("mcp:else_branch");
            when(decision.getAllChildNodes()).thenReturn(List.of(ifStep, elseStep));

            when(trigger.getNextNodes(any())).thenReturn(List.of(decision));

            when(tree.getRootNodes()).thenReturn(List.of(trigger));
            when(tree.getPlan()).thenReturn(plan);
            when(context.runId()).thenReturn("run-skipped");

            when(context.isCompleted("trigger:start")).thenReturn(true);
            when(context.getStepOutput("trigger:start")).thenReturn(Optional.of(Map.of()));

            // Decision completed, no output (rebuilt from DB)
            when(context.isCompleted("core:check")).thenReturn(true);
            when(context.getStepOutput("core:check")).thenReturn(Optional.empty());
            when(snapshotService.getCompletedNodeIds("run-skipped"))
                .thenReturn(Set.of("trigger:start", "core:check", "mcp:else_branch"));

            // else_branch is SKIPPED: NodeStatus.SKIPPED. isCompleted is the "any terminal"
            // overload so it returns true too; isSkipped is the canonical signal.
            when(context.isCompleted("mcp:else_branch")).thenReturn(true);
            when(context.isStarted("mcp:else_branch")).thenReturn(true);
            when(context.isSkipped("mcp:else_branch")).thenReturn(true);
            when(context.getStepOutput("mcp:else_branch")).thenReturn(Optional.empty());

            // if_branch is PENDING: not started, not completed
            when(context.isCompleted("mcp:if_branch")).thenReturn(false);
            when(context.isStarted("mcp:if_branch")).thenReturn(false);
            when(ifStep.canExecute(context)).thenReturn(true);
            when(mergeNodeAnalyzer.isMergeNode(plan, "mcp:if_branch")).thenReturn(false);

            Set<String> ready = calc.calculateReadyNodes(context, tree);

            // The SKIPPED else_branch must be excluded from executedChildren,
            // so fallback traverses ALL children, and if_branch becomes READY
            assertTrue(ready.contains("mcp:if_branch"),
                "if_branch should be READY - SKIPPED else_branch must not be treated as executed path");
            assertFalse(ready.contains("mcp:else_branch"),
                "else_branch is SKIPPED (completed) - should NOT be in ready set");
        }

        @Test
        @DisplayName("Non-branching node (Fork) with no output - should still traverse ALL children")
        void forkNoOutput_shouldTraverseAllChildren() {
            // Fork is NOT a branching node - it executes ALL branches
            // The fix should NOT filter fork's children
            StateSnapshotService snapshotService = mock(StateSnapshotService.class);
            ReadyNodeCalculator calc = new ReadyNodeCalculator(mergeNodeAnalyzer, snapshotService, null);

            ExecutionNode trigger = mock(ExecutionNode.class);
            when(trigger.getNodeId()).thenReturn("trigger:start");
            when(trigger.getType()).thenReturn(NodeType.TRIGGER);
            when(trigger.getAllChildNodes()).thenReturn(List.of());

            ExecutionNode fork = mock(ExecutionNode.class);
            when(fork.getNodeId()).thenReturn("core:parallel");
            when(fork.getType()).thenReturn(NodeType.FORK);
            when(fork.isBranchingNode()).thenReturn(false); // Fork is NOT branching
            when(fork.isForkNode()).thenReturn(true);
            when(fork.getNextNodes(any())).thenReturn(List.of()); // empty for synthetic result

            ExecutionNode branch0 = makeStep("mcp:task_a");
            ExecutionNode branch1 = makeStep("mcp:task_b");
            when(fork.getAllChildNodes()).thenReturn(List.of(branch0, branch1));

            when(trigger.getNextNodes(any())).thenReturn(List.of(fork));

            when(tree.getRootNodes()).thenReturn(List.of(trigger));
            when(tree.getPlan()).thenReturn(plan);
            when(context.runId()).thenReturn("run-5");

            when(context.isCompleted("trigger:start")).thenReturn(true);
            when(context.getStepOutput("trigger:start")).thenReturn(Optional.of(Map.of()));

            // Fork completed, no output
            when(context.isCompleted("core:parallel")).thenReturn(true);
            when(context.getStepOutput("core:parallel")).thenReturn(Optional.empty());
            when(snapshotService.getCompletedNodeIds("run-5"))
                .thenReturn(Set.of("trigger:start", "core:parallel"));

            // branch0 completed, branch1 NOT started
            when(context.isCompleted("mcp:task_a")).thenReturn(true);
            when(context.getStepOutput("mcp:task_a")).thenReturn(Optional.of(Map.of()));
            when(context.isCompleted("mcp:task_b")).thenReturn(false);
            when(context.isStarted("mcp:task_b")).thenReturn(false);
            when(branch1.canExecute(context)).thenReturn(true);
            when(mergeNodeAnalyzer.isMergeNode(plan, "mcp:task_b")).thenReturn(false);

            Set<String> ready = calc.calculateReadyNodes(context, tree);

            // Fork traverses ALL children - task_b should be ready even though task_a is already done
            assertTrue(ready.contains("mcp:task_b"),
                "Fork must traverse ALL children - task_b should be ready");
        }
    }

    @Nested
    @DisplayName("Cycle Prevention")
    class CyclePreventionTests {

        @Test
        @DisplayName("Should NOT traverse children when branching node returns empty nextNodes with valid output (no branch selected)")
        void shouldNotTraverseChildrenWhenBranchingReturnsEmptyWithValidOutput() {
            // Given: classify node with valid output but no matching category → getNextNodes() returns empty
            ExecutionNode classifyNode = mock(ExecutionNode.class);
            ExecutionNode childA = mock(ExecutionNode.class);
            ExecutionNode childB = mock(ExecutionNode.class);

            when(classifyNode.getNodeId()).thenReturn("agent:classify");
            when(classifyNode.getType()).thenReturn(NodeType.AGENT);
            when(classifyNode.isBranchingNode()).thenReturn(true);
            when(classifyNode.getAllChildNodes()).thenReturn(List.of(childA, childB));

            when(childA.getNodeId()).thenReturn("mcp:label_a");
            when(childA.getType()).thenReturn(NodeType.MCP);
            when(childA.getAllChildNodes()).thenReturn(List.of());
            when(childB.getNodeId()).thenReturn("mcp:label_b");
            when(childB.getType()).thenReturn(NodeType.MCP);
            when(childB.getAllChildNodes()).thenReturn(List.of());

            // Classify is completed with valid output but returned empty nextNodes (no branch matched)
            NodeExecutionResult classifyResult = NodeExecutionResult.success("agent:classify",
                Map.of("selected_category", "none", "node_type", "classify"));
            when(classifyNode.getNextNodes(classifyResult)).thenReturn(List.of());

            when(tree.getRootNodes()).thenReturn(List.of(classifyNode));
            when(tree.getPlan()).thenReturn(plan);

            when(context.isCompleted("agent:classify")).thenReturn(true);
            when(context.getStepOutput("agent:classify")).thenReturn(Optional.of(classifyResult.output()));

            // When
            Set<String> readyNodes = calculator.calculateReadyNodes(context, tree);

            // Then: children should NOT be made ready (no branch selected)
            assertFalse(readyNodes.contains("mcp:label_a"));
            assertFalse(readyNodes.contains("mcp:label_b"));
        }

        @Test
        @DisplayName("Should not visit same node twice (cycle prevention)")
        void shouldNotVisitSameNodeTwice() {
            // Given
            ExecutionNode nodeA = mock(ExecutionNode.class);
            ExecutionNode nodeB = mock(ExecutionNode.class);

            when(nodeA.getNodeId()).thenReturn("mcp:nodeA");
            when(nodeA.getType()).thenReturn(NodeType.MCP);
            when(nodeA.getAllChildNodes()).thenReturn(List.of());
            when(nodeB.getNodeId()).thenReturn("mcp:nodeB");
            when(nodeB.getType()).thenReturn(NodeType.MCP);
            when(nodeB.getAllChildNodes()).thenReturn(List.of());

            // Create a cycle: A -> B -> A
            when(nodeA.getNextNodes(any())).thenReturn(List.of(nodeB));
            when(nodeB.getNextNodes(any())).thenReturn(List.of(nodeA));

            when(tree.getRootNodes()).thenReturn(List.of(nodeA));
            when(tree.getPlan()).thenReturn(plan);

            when(context.isCompleted("mcp:nodeA")).thenReturn(true);
            when(context.getStepOutput("mcp:nodeA")).thenReturn(Optional.of(Map.of()));
            when(context.isCompleted("mcp:nodeB")).thenReturn(true);
            when(context.getStepOutput("mcp:nodeB")).thenReturn(Optional.of(Map.of()));

            // When - should not cause infinite loop
            Set<String> readyNodes = calculator.calculateReadyNodes(context, tree);

            // Then - should complete without infinite loop
            assertNotNull(readyNodes);
        }
    }
}
