package com.apimarketplace.orchestrator.execution.v2.services;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.execution.v2.nodes.BaseNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.DecisionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeType;
import com.apimarketplace.orchestrator.services.streaming.EdgeStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("EdgeStatusEmitter")
class EdgeStatusEmitterTest {

    @Mock private EdgeStatusService edgeStatusService;
    @Mock private V2SkipPropagationService skipPropagationService;
    @Mock private WorkflowExecution execution;

    private EdgeStatusEmitter emitter;

    @BeforeEach
    void setUp() {
        emitter = new EdgeStatusEmitter(edgeStatusService, skipPropagationService);
    }

    @Nested
    @DisplayName("emitIncomingEdges()")
    class EmitIncomingEdgesTests {
        @Test
        @DisplayName("Should not mark incoming edges (handled by source)")
        void shouldNotMarkIncomingEdges() {
            BaseNode node = mock(BaseNode.class);
            when(node.getNodeId()).thenReturn("mcp:step1");

            emitter.emitIncomingEdges(execution, node, 0);

            verifyNoInteractions(edgeStatusService);
        }
    }

    @Nested
    @DisplayName("emitOutgoingEdges()")
    class EmitOutgoingEdgesTests {
        @Test
        @DisplayName("Should emit RUNNING then COMPLETED for successors")
        void shouldEmitRunningThenCompletedForSuccessors() {
            BaseNode successor = mock(BaseNode.class);
            when(successor.getNodeId()).thenReturn("mcp:step2");

            BaseNode node = mock(BaseNode.class);
            when(node.getNodeId()).thenReturn("mcp:step1");
            lenient().when(node.getType()).thenReturn(NodeType.MCP);
            when(node.getNextNodes(any())).thenReturn(List.of(successor));

            NodeExecutionResult result = NodeExecutionResult.success("mcp:step1", Map.of());
            emitter.emitOutgoingEdges(execution, node, 0, null, result, false, 0, null);

            verify(edgeStatusService).markEdgeRunning(execution, "mcp:step1", "mcp:step2", 0, null);
            verify(edgeStatusService).markEdgeCompleted(execution, "mcp:step1", "mcp:step2", 0, null);
        }

        @Test
        @DisplayName("Should mark edge as skipped on failure but NOT propagate skip to successor nodes")
        void shouldMarkEdgeSkippedOnFailureWithoutPropagation() {
            // IMPORTANT: When a node fails, edges are marked as SKIPPED but successor nodes are NOT
            // marked as SKIPPED. SKIPPED is reserved for nodes explicitly not selected by decision branches.
            // Successors of FAILED nodes remain PENDING - they simply cannot execute because parent failed.
            BaseNode successor = mock(BaseNode.class);
            when(successor.getNodeId()).thenReturn("mcp:step2");

            BaseNode node = mock(BaseNode.class);
            when(node.getNodeId()).thenReturn("mcp:step1");
            lenient().when(node.getType()).thenReturn(NodeType.MCP);
            lenient().when(node.getNextNodes(any())).thenReturn(List.of(successor));
            when(node.getSuccessors()).thenReturn(List.of(successor));

            NodeExecutionResult result = NodeExecutionResult.failure("mcp:step1", "Error");
            emitter.emitOutgoingEdges(execution, node, 0, null, result, false, 0, null);

            // Edge is marked as SKIPPED
            verify(edgeStatusService).markEdgeSkipped(execution, "mcp:step1", "mcp:step2", 0, null);
            // But skip is NOT propagated to successor nodes
            verify(skipPropagationService, never()).persistAndPropagateSkip(any(), any(), any(), anyInt(), anyInt(), any());
        }

        @Test
        @DisplayName("Should pass iteration to edge status")
        void shouldPassIterationToEdgeStatus() {
            BaseNode successor = mock(BaseNode.class);
            when(successor.getNodeId()).thenReturn("mcp:step2");

            BaseNode node = mock(BaseNode.class);
            when(node.getNodeId()).thenReturn("mcp:step1");
            lenient().when(node.getType()).thenReturn(NodeType.MCP);
            when(node.getNextNodes(any())).thenReturn(List.of(successor));

            NodeExecutionResult result = NodeExecutionResult.success("mcp:step1", Map.of());
            emitter.emitOutgoingEdges(execution, node, 0, 3, result, false, 0, null);

            verify(edgeStatusService).markEdgeRunning(execution, "mcp:step1", "mcp:step2", 0, 3);
            verify(edgeStatusService).markEdgeCompleted(execution, "mcp:step1", "mcp:step2", 0, 3);
        }
    }

    @Nested
    @DisplayName("emitBranchingNodeEdges()")
    class EmitBranchingNodeEdgesTests {
        @Test
        @DisplayName("Should emit COMPLETED for selected branch")
        void shouldEmitCompletedForSelectedBranch() {
            BaseNode selectedNode = mock(BaseNode.class);
            when(selectedNode.getNodeId()).thenReturn("mcp:if_step");

            DecisionNode decision = mock(DecisionNode.class);
            when(decision.getNodeId()).thenReturn("core:check");
            when(decision.getType()).thenReturn(NodeType.DECISION);
            when(decision.getNextNodes(any())).thenReturn(List.of(selectedNode));
            when(decision.getSkippedChildNodes(any())).thenReturn(List.of());

            NodeExecutionResult result = NodeExecutionResult.success("core:check", Map.of("selectedIndex", 0));
            emitter.emitBranchingNodeEdges(execution, decision, 0, null, result, false, 0, null);

            verify(edgeStatusService).markEdgeRunning(execution, "core:check", "mcp:if_step", 0, null);
            verify(edgeStatusService).markEdgeCompleted(execution, "core:check", "mcp:if_step", 0, null);
        }

        @Test
        @DisplayName("Should emit SKIPPED for non-selected branches and propagate skip")
        void shouldEmitSkippedForNonSelectedBranches() {
            BaseNode selectedNode = mock(BaseNode.class);
            when(selectedNode.getNodeId()).thenReturn("mcp:if_step");

            BaseNode skippedNode = mock(BaseNode.class);
            when(skippedNode.getNodeId()).thenReturn("mcp:else_step");

            DecisionNode decision = mock(DecisionNode.class);
            when(decision.getNodeId()).thenReturn("core:check");
            when(decision.getType()).thenReturn(NodeType.DECISION);
            when(decision.getNextNodes(any())).thenReturn(List.of(selectedNode));
            when(decision.getSkippedChildNodes(any())).thenReturn(List.of(skippedNode));
            when(decision.shouldPropagateSkipOnBranching()).thenReturn(true); // Default behavior for DecisionNode

            NodeExecutionResult result = NodeExecutionResult.success("core:check", Map.of("selectedIndex", 0));
            emitter.emitBranchingNodeEdges(execution, decision, 0, null, result, false, 0, null);

            verify(edgeStatusService).markEdgeSkipped(execution, "core:check", "mcp:else_step", 0, null);
            verify(skipPropagationService).persistAndPropagateSkip(execution, skippedNode, "core:check", 0, 0, null, false);
        }

        @Test
        @DisplayName("Should preserve loop iteration when propagating skipped branch")
        void shouldPreserveLoopIterationWhenPropagatingSkippedBranch() {
            BaseNode selectedNode = mock(BaseNode.class);
            when(selectedNode.getNodeId()).thenReturn("mcp:if_step");

            BaseNode skippedNode = mock(BaseNode.class);
            when(skippedNode.getNodeId()).thenReturn("mcp:else_step");

            DecisionNode decision = mock(DecisionNode.class);
            when(decision.getNodeId()).thenReturn("core:check");
            when(decision.getType()).thenReturn(NodeType.DECISION);
            when(decision.getNextNodes(any())).thenReturn(List.of(selectedNode));
            when(decision.getSkippedChildNodes(any())).thenReturn(List.of(skippedNode));
            when(decision.shouldPropagateSkipOnBranching()).thenReturn(true);

            NodeExecutionResult result = NodeExecutionResult.success("core:check", Map.of("selectedIndex", 0));
            emitter.emitBranchingNodeEdges(execution, decision, 0, 2, result, false, 4, "trigger:cron");

            verify(edgeStatusService).markEdgeSkipped(execution, "core:check", "mcp:else_step", 0, 2);
            verify(skipPropagationService).persistAndPropagateSkip(
                execution, skippedNode, "core:check", 0, 2, 4, "trigger:cron", false);
        }

        @Test
        @DisplayName("Should propagate skip when no branch selected even if shouldPropagateSkipOnBranching is false")
        void shouldPropagateSkipWhenNoBranchSelected() {
            // Classify nodes return false for shouldPropagateSkipOnBranching() because
            // different split items can select different categories. However, when NO category
            // matched at all (getNextNodes returns empty), ALL children must be skipped.
            BaseNode childA = mock(BaseNode.class);
            when(childA.getNodeId()).thenReturn("mcp:category_a");

            BaseNode childB = mock(BaseNode.class);
            when(childB.getNodeId()).thenReturn("mcp:category_b");

            ExecutionNode classifyNode = mock(ExecutionNode.class);
            when(classifyNode.getNodeId()).thenReturn("agent:classify1");
            when(classifyNode.getType()).thenReturn(NodeType.AGENT);
            // No branch selected - empty list
            when(classifyNode.getNextNodes(any())).thenReturn(List.of());
            // All children are skipped
            when(classifyNode.getSkippedChildNodes(any())).thenReturn(List.of(childA, childB));
            // Classify returns false - normally suppresses propagation
            when(classifyNode.shouldPropagateSkipOnBranching()).thenReturn(false);
            // No port info → legacy path
            when(classifyNode.getBranchTargetsByPort()).thenReturn(Map.of());

            NodeExecutionResult result = NodeExecutionResult.success("agent:classify1", Map.of());
            emitter.emitBranchingNodeEdges(execution, classifyNode, 0, null, result, false, 0, null);

            // Both children should have edges marked SKIPPED
            verify(edgeStatusService).markEdgeSkipped(execution, "agent:classify1", "mcp:category_a", 0, null);
            verify(edgeStatusService).markEdgeSkipped(execution, "agent:classify1", "mcp:category_b", 0, null);
            // Skip propagation should fire despite shouldPropagateSkipOnBranching() == false.
            // Non-split callsite → splitScope=false → falls into the global skip path.
            verify(skipPropagationService).persistAndPropagateSkip(eq(execution), eq(childA), eq("agent:classify1"), eq(0), eq(0), isNull(), eq(false));
            verify(skipPropagationService).persistAndPropagateSkip(eq(execution), eq(childB), eq("agent:classify1"), eq(0), eq(0), isNull(), eq(false));
        }

        @Test
        @DisplayName("Should NOT propagate skip to successors when suppressSkipPropagation is true (split context)")
        void shouldNotPropagateSkipWhenSuppressed() {
            // In split context, different items may select different branches.
            // suppressSkipPropagation=true means edge is still marked SKIPPED visually,
            // but target nodes are NOT marked SKIPPED in EpochState.
            BaseNode selectedNode = mock(BaseNode.class);
            when(selectedNode.getNodeId()).thenReturn("core:wait");

            BaseNode skippedNode = mock(BaseNode.class);
            when(skippedNode.getNodeId()).thenReturn("mcp:rejected_path");

            DecisionNode approval = mock(DecisionNode.class);
            when(approval.getNodeId()).thenReturn("core:user_approval");
            when(approval.getType()).thenReturn(NodeType.DECISION);
            when(approval.getNextNodes(any())).thenReturn(List.of(selectedNode));
            when(approval.getSkippedChildNodes(any())).thenReturn(List.of(skippedNode));
            when(approval.shouldPropagateSkipOnBranching()).thenReturn(true);

            NodeExecutionResult result = NodeExecutionResult.success("core:user_approval", Map.of("selectedIndex", 0));
            // suppressSkipPropagation = TRUE (split context)
            emitter.emitBranchingNodeEdges(execution, approval, 0, null, result, true, 0, null);

            // Edge is still marked SKIPPED (visual feedback)
            verify(edgeStatusService).markEdgeSkipped(execution, "core:user_approval", "mcp:rejected_path", 0, null);
            // But skip propagation to the target node is SUPPRESSED
            verify(skipPropagationService, never()).persistAndPropagateSkip(any(), any(), any(), anyInt(), anyInt(), any());
        }

        @Test
        @DisplayName("Should NOT propagate skip via legacy path when suppressSkipPropagation is true")
        void shouldNotPropagateSkipViaLegacyPathWhenSuppressed() {
            // Legacy path: when getBranchTargetsByPort() returns empty map, fallback uses
            // target-based emission. Verify suppressSkipPropagation works on this path too.
            BaseNode selectedNode = mock(BaseNode.class);
            when(selectedNode.getNodeId()).thenReturn("core:wait");

            BaseNode skippedNode = mock(BaseNode.class);
            when(skippedNode.getNodeId()).thenReturn("mcp:rejected_path");

            ExecutionNode approval = mock(ExecutionNode.class);
            when(approval.getNodeId()).thenReturn("core:user_approval");
            when(approval.getType()).thenReturn(NodeType.AGENT);
            when(approval.isBranchingNode()).thenReturn(true);
            when(approval.getNextNodes(any())).thenReturn(List.of(selectedNode));
            when(approval.getSkippedChildNodes(any())).thenReturn(List.of(skippedNode));
            when(approval.shouldPropagateSkipOnBranching()).thenReturn(true);
            // No port info → legacy path
            when(approval.getBranchTargetsByPort()).thenReturn(Map.of());

            NodeExecutionResult result = NodeExecutionResult.success("core:user_approval", Map.of());
            // suppressSkipPropagation = TRUE (split context)
            emitter.emitOutgoingEdges(execution, approval, 0, null, result, true, 0, null);

            // Edge marked SKIPPED but propagation suppressed
            verify(edgeStatusService).markEdgeSkipped(execution, "core:user_approval", "mcp:rejected_path", 0, null);
            verify(skipPropagationService, never()).persistAndPropagateSkip(any(), any(), any(), anyInt(), anyInt(), any());
        }

        @Test
        @DisplayName("splitScope=true forwards to persistAndPropagateSkip - per-item, not global (Gmail regression)")
        void splitScopeForwardsToPerItemSkipPropagation() {
            // Reproduction of the Gmail Auto-Labeler bug: split → classify → apply_X,
            // one item routes to an unwired port (classify "Other" category with no edge).
            // noBranchSelected overrides shouldPropagateSkipOnBranching()=false → propagation fires.
            // Without splitScope, this would poison EpochState.skippedNodeIds globally and
            // block sibling items from executing their correctly-routed branch.
            BaseNode applyTech = mock(BaseNode.class);
            when(applyTech.getNodeId()).thenReturn("mcp:apply_tech");

            BaseNode applyFinance = mock(BaseNode.class);
            when(applyFinance.getNodeId()).thenReturn("mcp:apply_finance");

            ExecutionNode classifyNode = mock(ExecutionNode.class);
            when(classifyNode.getNodeId()).thenReturn("agent:classify");
            when(classifyNode.getType()).thenReturn(NodeType.AGENT);
            when(classifyNode.isBranchingNode()).thenReturn(true);
            when(classifyNode.getNextNodes(any())).thenReturn(List.of()); // no match → per-item skip everywhere
            when(classifyNode.getSkippedChildNodes(any())).thenReturn(List.of(applyTech, applyFinance));
            when(classifyNode.shouldPropagateSkipOnBranching()).thenReturn(false);
            when(classifyNode.getBranchTargetsByPort()).thenReturn(Map.of()); // legacy path

            NodeExecutionResult result = NodeExecutionResult.success("agent:classify", Map.of());

            // Split context → splitScope=true
            emitter.emitOutgoingEdges(execution, classifyNode, 3, null, result, false, 1, "trigger:cron", true);

            // Per-item scope must forward into persistAndPropagateSkip: any future caller that
            // unconditionally passes splitScope=false for split-scoped items would regress the Gmail bug.
            verify(skipPropagationService).persistAndPropagateSkip(
                eq(execution), eq(applyTech), eq("agent:classify"), eq(3), eq(1), eq("trigger:cron"), eq(true));
            verify(skipPropagationService).persistAndPropagateSkip(
                eq(execution), eq(applyFinance), eq("agent:classify"), eq(3), eq(1), eq("trigger:cron"), eq(true));
        }

        @Test
        @DisplayName("Port-qualified path forwards splitScope into persistAndPropagateSkip")
        void portQualifiedPathForwardsSplitScope() {
            // Classify with port info (category_0, category_5, …), item picks category_5.
            // category_0 edge is not selected → edge marked SKIPPED → per-item skip propagation.
            BaseNode applyTech = mock(BaseNode.class);
            when(applyTech.getNodeId()).thenReturn("mcp:apply_tech");

            BaseNode applyFinance = mock(BaseNode.class);
            when(applyFinance.getNodeId()).thenReturn("mcp:apply_finance");

            ExecutionNode classifyNode = mock(ExecutionNode.class);
            when(classifyNode.getNodeId()).thenReturn("agent:classify");
            when(classifyNode.getType()).thenReturn(NodeType.AGENT);
            when(classifyNode.isBranchingNode()).thenReturn(true);
            when(classifyNode.getNextNodes(any())).thenReturn(List.of(applyTech)); // category_5 selected
            // Guardrail-style: does propagate on non-selected branches (we reuse this to hit the non-suppressed branch)
            when(classifyNode.shouldPropagateSkipOnBranching()).thenReturn(true);
            when(classifyNode.getBranchTargetsByPort()).thenReturn(Map.of(
                "category_0", List.of(applyFinance),
                "category_5", List.of(applyTech)
            ));
            when(classifyNode.getSelectedPort(any())).thenReturn("category_5");

            NodeExecutionResult result = NodeExecutionResult.success("agent:classify", Map.of());
            emitter.emitOutgoingEdges(execution, classifyNode, 1, null, result, false, 1, "trigger:cron", true);

            // category_0 is not selected → SKIPPED edge + per-item skip propagation with splitScope=true
            verify(skipPropagationService).persistAndPropagateSkip(
                eq(execution), eq(applyFinance), eq("agent:classify"), eq(1), eq(1), eq("trigger:cron"), eq(true));
            // category_5 is selected → no skip propagation for applyTech
            verify(skipPropagationService, never()).persistAndPropagateSkip(
                eq(execution), eq(applyTech), anyString(), anyInt(), anyInt(), any(), anyBoolean());
        }

        @Test
        @DisplayName("Classify NON-split: unselected category branch IS skip-propagated (merge-orphan fix)")
        void classifyNonSplitPropagatesSkipToUnselectedBranch() {
            // Ops Health Judge prod bug: a NON-split classify selects one category; pre-fix the
            // unselected branches were never persisted SKIPPED (shouldPropagate=false) so a
            // downstream merge ORPHANED forever (log_verdict + noc_console never ran). With
            // splitScope=false (single item) the unselected branch MUST be skip-propagated.
            BaseNode handleA = mock(BaseNode.class);
            when(handleA.getNodeId()).thenReturn("mcp:handle_a");
            BaseNode handleB = mock(BaseNode.class);
            when(handleB.getNodeId()).thenReturn("mcp:handle_b");

            ExecutionNode classifyNode = mock(ExecutionNode.class);
            when(classifyNode.getNodeId()).thenReturn("agent:classify");
            when(classifyNode.getType()).thenReturn(NodeType.AGENT);
            when(classifyNode.isBranchingNode()).thenReturn(true);
            when(classifyNode.getNextNodes(any())).thenReturn(List.of(handleA)); // category_0 selected
            when(classifyNode.shouldPropagateSkipOnBranching()).thenReturn(false); // classify
            when(classifyNode.getBranchTargetsByPort()).thenReturn(Map.of(
                "category_0", List.of(handleA),
                "category_1", List.of(handleB)
            ));
            when(classifyNode.getSelectedPort(any())).thenReturn("category_0");

            NodeExecutionResult result = NodeExecutionResult.success("agent:classify", Map.of());
            // splitScope=false → single item, non-split.
            emitter.emitOutgoingEdges(execution, classifyNode, 0, null, result, false, 1, "trigger:cron", false);

            // The unselected category_1 branch MUST be skip-propagated so the downstream merge resolves.
            verify(skipPropagationService).persistAndPropagateSkip(
                eq(execution), eq(handleB), eq("agent:classify"), eq(0), eq(1), eq("trigger:cron"), eq(false));
            // The selected branch is never skip-propagated.
            verify(skipPropagationService, never()).persistAndPropagateSkip(
                eq(execution), eq(handleA), anyString(), anyInt(), anyInt(), any(), anyBoolean());
        }

        @Test
        @DisplayName("Classify SPLIT with a selected category: unselected branch NOT globally skip-propagated (per-item preserved)")
        void classifySplitDoesNotGloballyPropagateWhenCategorySelected() {
            // Guards the fix's blast radius: inside a split, different items pick different
            // categories, so a selected-category classify must NOT globally propagate the skip
            // (that would wrongly skip category_1 for OTHER items). Unchanged by the !splitScope fix.
            BaseNode handleA = mock(BaseNode.class);
            when(handleA.getNodeId()).thenReturn("mcp:handle_a");
            BaseNode handleB = mock(BaseNode.class);
            when(handleB.getNodeId()).thenReturn("mcp:handle_b");

            ExecutionNode classifyNode = mock(ExecutionNode.class);
            when(classifyNode.getNodeId()).thenReturn("agent:classify");
            when(classifyNode.getType()).thenReturn(NodeType.AGENT);
            when(classifyNode.isBranchingNode()).thenReturn(true);
            when(classifyNode.getNextNodes(any())).thenReturn(List.of(handleA)); // category_0 selected (non-empty)
            when(classifyNode.shouldPropagateSkipOnBranching()).thenReturn(false);
            when(classifyNode.getBranchTargetsByPort()).thenReturn(Map.of(
                "category_0", List.of(handleA),
                "category_1", List.of(handleB)
            ));
            when(classifyNode.getSelectedPort(any())).thenReturn("category_0");

            NodeExecutionResult result = NodeExecutionResult.success("agent:classify", Map.of());
            // splitScope=true → per-item; no GLOBAL skip propagation for the unselected branch.
            emitter.emitOutgoingEdges(execution, classifyNode, 2, null, result, false, 1, "trigger:cron", true);

            verify(skipPropagationService, never()).persistAndPropagateSkip(
                eq(execution), eq(handleB), anyString(), anyInt(), anyInt(), any(), anyBoolean());
        }

        @Test
        @DisplayName("Should not skip node that is also selected")
        void shouldNotSkipNodeThatIsAlsoSelected() {
            BaseNode node = mock(BaseNode.class);
            when(node.getNodeId()).thenReturn("mcp:common_step");

            DecisionNode decision = mock(DecisionNode.class);
            when(decision.getNodeId()).thenReturn("core:check");
            when(decision.getType()).thenReturn(NodeType.DECISION);
            when(decision.getNextNodes(any())).thenReturn(List.of(node));
            when(decision.getSkippedChildNodes(any())).thenReturn(List.of(node));

            NodeExecutionResult result = NodeExecutionResult.success("core:check", Map.of());
            emitter.emitBranchingNodeEdges(execution, decision, 0, null, result, false, 0, null);

            verify(edgeStatusService, never()).markEdgeSkipped(any(), any(), eq("mcp:common_step"), anyInt(), any());
        }
    }

    @Nested
    @DisplayName("emitOutgoingEdges() - SKIPPED result handling")
    class EmitOutgoingEdgesSkippedTests {

        @Test
        @DisplayName("Should mark outgoing edges as SKIPPED when node result is SKIPPED")
        void shouldMarkEdgesSkippedWhenResultIsSkipped() {
            BaseNode successor = mock(BaseNode.class);
            when(successor.getNodeId()).thenReturn("core:wait_copy");

            BaseNode node = mock(BaseNode.class);
            when(node.getNodeId()).thenReturn("core:wait");
            lenient().when(node.getType()).thenReturn(NodeType.WAIT);
            when(node.getSuccessors()).thenReturn(List.of(successor));

            NodeExecutionResult result = NodeExecutionResult.skipped("core:wait", "No items routed to this branch");
            emitter.emitOutgoingEdges(execution, node, 0, null, result, false, 0, null);

            // Edge should be SKIPPED, not COMPLETED
            verify(edgeStatusService).markEdgeSkipped(execution, "core:wait", "core:wait_copy", 0, null);
            verify(edgeStatusService, never()).markEdgeCompleted(any(), any(), any(), anyInt(), any());
            verify(edgeStatusService, never()).markEdgeRunning(any(), any(), any(), anyInt(), any());
        }

        @Test
        @DisplayName("Should mark all successor edges as SKIPPED when node is SKIPPED")
        void shouldMarkAllSuccessorEdgesSkipped() {
            BaseNode succ1 = mock(BaseNode.class);
            when(succ1.getNodeId()).thenReturn("mcp:branch_a");
            BaseNode succ2 = mock(BaseNode.class);
            when(succ2.getNodeId()).thenReturn("mcp:branch_b");

            BaseNode node = mock(BaseNode.class);
            when(node.getNodeId()).thenReturn("mcp:step1");
            lenient().when(node.getType()).thenReturn(NodeType.MCP);
            when(node.getSuccessors()).thenReturn(List.of(succ1, succ2));

            NodeExecutionResult result = NodeExecutionResult.skipped("mcp:step1", "Skipped");
            emitter.emitOutgoingEdges(execution, node, 0, null, result, false, 0, null);

            verify(edgeStatusService).markEdgeSkipped(execution, "mcp:step1", "mcp:branch_a", 0, null);
            verify(edgeStatusService).markEdgeSkipped(execution, "mcp:step1", "mcp:branch_b", 0, null);
            verify(edgeStatusService, never()).markEdgeCompleted(any(), any(), any(), anyInt(), any());
        }

        @Test
        @DisplayName("Should not treat successful result as SKIPPED")
        void shouldNotTreatSuccessAsSkipped() {
            BaseNode successor = mock(BaseNode.class);
            when(successor.getNodeId()).thenReturn("mcp:step2");

            BaseNode node = mock(BaseNode.class);
            when(node.getNodeId()).thenReturn("mcp:step1");
            lenient().when(node.getType()).thenReturn(NodeType.MCP);
            when(node.getNextNodes(any())).thenReturn(List.of(successor));

            NodeExecutionResult result = NodeExecutionResult.success("mcp:step1", Map.of());
            emitter.emitOutgoingEdges(execution, node, 0, null, result, false, 0, null);

            // Should be COMPLETED, not SKIPPED
            verify(edgeStatusService).markEdgeCompleted(execution, "mcp:step1", "mcp:step2", 0, null);
            verify(edgeStatusService, never()).markEdgeSkipped(any(), any(), eq("mcp:step2"), anyInt(), any());
        }
    }
}
