package com.apimarketplace.orchestrator.execution.v2.services;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.domain.workflow.NodePolicy;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.constants.ExecutionMetadataKeys;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionTree;
import com.apimarketplace.orchestrator.execution.v2.nodes.BaseNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.services.resume.MergeNodeAnalyzer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The readiness walk of the STEP_BY_STEP path (the one production runs take) past a node that failed
 * with {@code continueOnFailure}.
 *
 * <p>REGRESSION 2026-09-29: this walk never traversed past a FAILED node, so on execute / trigger /
 * cron runs a failed node's successors never became ready, whatever its policy said. The flag is
 * read from the node's own STORED result, never from the plan: a credit or plan gate refusal carries
 * no flag and must keep stopping everything below. Three stored shapes reach the calculator: the
 * in-memory result wrapped under {@code output} right after execution, a flat persisted output, and
 * a {@code NodeExecutionResult} kept as the step output.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ReadyNodeCalculator - continueOnFailure walks past a FAILED node")
class ReadyNodeCalculatorContinueOnFailureTest {

    private static final String TRIGGER = "trigger:start";
    private static final String CALL = "mcp:call";
    private static final String AFTER = "mcp:after";
    private static final String JOIN = "core:join";

    @Mock private MergeNodeAnalyzer mergeNodeAnalyzer;
    @Mock private ExecutionTree tree;
    @Mock private WorkflowPlan plan;

    private ReadyNodeCalculator calculator;
    private BaseNode call;
    private BaseNode after;
    private BaseNode join;

    @BeforeEach
    void setUp() {
        calculator = new ReadyNodeCalculator(mergeNodeAnalyzer, null, null);

        after = node(AFTER);
        when(after.canExecute(any())).thenReturn(true);
        join = node(JOIN);
        call = node(CALL);
        when(call.getSuccessors()).thenReturn(List.of(after, join));
        BaseNode trigger = node(TRIGGER);
        when(trigger.getNextNodes(any())).thenReturn(List.of(call));

        when(tree.getRootNodes()).thenReturn(List.of(trigger));
        when(tree.getPlan()).thenReturn(plan);
        when(mergeNodeAnalyzer.isMergeNode(any(), anyString())).thenReturn(false);
        when(mergeNodeAnalyzer.isMergeNode(plan, JOIN)).thenReturn(true);
        when(mergeNodeAnalyzer.findPredecessorsFromEdges(plan, JOIN)).thenReturn(List.of(CALL));
    }

    private static BaseNode node(String id) {
        BaseNode n = mock(BaseNode.class);
        when(n.getNodeId()).thenReturn(id);
        when(n.getPredecessorIds()).thenReturn(List.of());
        when(n.getSuccessors()).thenReturn(List.of());
        when(n.getNextNodes(any())).thenReturn(List.of());
        when(n.getAllChildNodes()).thenReturn(List.of());
        return n;
    }

    /** The trigger fired and {@link #CALL} failed; the caller shapes how CALL's output is stored. */
    private ExecutionContext afterTheFailure(NodeExecutionResult callResult) {
        return ExecutionContext.create("run-1", "wf-run-1", "tenant-1", "item-1", 0, TRIGGER, 1, 0, Map.of(), plan)
            .withResult(TRIGGER, NodeExecutionResult.success(TRIGGER, Map.of("fired", true)))
            .withResult(CALL, callResult);
    }

    private static NodeExecutionResult flaggedFailure() {
        return NodeExecutionResult.failureWithOutput(CALL, "provider down",
            Map.of(ExecutionMetadataKeys.POLICY_CONTINUE_ON_FAILURE, true, "error", "provider down"), 5L);
    }

    @Test
    @DisplayName("REGRESSION: right after execution (output wrapped under 'output'), the successor of a continued failure is ready")
    void inMemoryFlagMakesTheSuccessorReady() {
        ExecutionContext context = afterTheFailure(flaggedFailure());

        Set<String> ready = calculator.calculateReadyNodes(context, tree);

        assertThat(ready).contains(AFTER, JOIN);
    }

    @Test
    @DisplayName("REGRESSION: a split node whose first item went elsewhere (no flag on the output the context holds) reads its continuation from its rows")
    void continuationReadFromTheRowsWhenTheFirstItemWasNotRouted() {
        com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository rows =
            mock(com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository.class);
        java.util.List<Object[]> failedRows = new java.util.ArrayList<>();
        failedRows.add(new Object[] {1, Map.of(ExecutionMetadataKeys.POLICY_CONTINUE_ON_FAILURE, true)});
        when(rows.findFailedItemMetadataByEpoch("run-1", CALL, 1)).thenReturn(failedRows);
        calculator.setStepDataRepository(rows);
        // Item 0 was routed to another branch: the context holds its SKIPPED envelope, no flag.
        ExecutionContext context = afterTheFailure(NodeExecutionResult.failure(CALL, "provider down"))
            .withStepOutput(CALL, Map.of("output", Map.of("_status", "SKIPPED")));

        assertThat(calculator.calculateReadyNodes(context, tree)).contains(AFTER, JOIN);
    }

    @Test
    @DisplayName("rows that carry no flag leave a FAILED node stopping its successors")
    void unflaggedRowsDoNotContinue() {
        com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository rows =
            mock(com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository.class);
        java.util.List<Object[]> failedRows = new java.util.ArrayList<>();
        failedRows.add(new Object[] {1, Map.of("statusMessage", "provider down")});
        when(rows.findFailedItemMetadataByEpoch("run-1", CALL, 1)).thenReturn(failedRows);
        calculator.setStepDataRepository(rows);
        ExecutionContext context = afterTheFailure(NodeExecutionResult.failure(CALL, "provider down"));

        assertThat(calculator.calculateReadyNodes(context, tree)).doesNotContain(AFTER);
    }

    @Test
    @DisplayName("after a context rebuild (flat persisted output), the successor of a continued failure is ready")
    void persistedFlatFlagMakesTheSuccessorReady() {
        ExecutionContext context = afterTheFailure(NodeExecutionResult.failure(CALL, "provider down"))
            .withStepOutput(CALL, Map.of(ExecutionMetadataKeys.POLICY_CONTINUE_ON_FAILURE, true, "error", "provider down"));

        assertThat(calculator.calculateReadyNodes(context, tree)).contains(AFTER, JOIN);
    }

    @Test
    @DisplayName("a persisted output nested under 'output' is read too")
    void persistedNestedFlagMakesTheSuccessorReady() {
        ExecutionContext context = afterTheFailure(NodeExecutionResult.failure(CALL, "provider down"))
            .withStepOutput(CALL, Map.of("output", Map.of(ExecutionMetadataKeys.POLICY_CONTINUE_ON_FAILURE, true)));

        assertThat(calculator.calculateReadyNodes(context, tree)).contains(AFTER, JOIN);
    }

    @Test
    @DisplayName("a NodeExecutionResult kept as the step output, flagged in its metadata, is read too")
    void storedNodeExecutionResultFlagMakesTheSuccessorReady() {
        ExecutionContext context = mock(ExecutionContext.class);
        when(context.getAllStepOutputs()).thenReturn(Map.of());
        when(context.isCompleted(TRIGGER)).thenReturn(true);
        when(context.getStepOutput(TRIGGER)).thenReturn(Optional.of(Map.of("output", Map.of("fired", true))));
        when(context.isCompleted(CALL)).thenReturn(true);
        when(context.isFailed(CALL)).thenReturn(true);
        when(context.getStepOutput(CALL)).thenReturn(Optional.of(new NodeExecutionResult(CALL, NodeStatus.FAILED,
            Map.of(), Optional.of("provider down"),
            Map.of(ExecutionMetadataKeys.POLICY_CONTINUE_ON_FAILURE, true), 5L)));

        assertThat(calculator.calculateReadyNodes(context, tree)).contains(AFTER, JOIN);
    }

    @Test
    @DisplayName("an unflagged failure keeps the legacy walk: only the merge below is evaluated, the plain successor is not ready")
    void unflaggedFailureOnlyEvaluatesMerges() {
        ExecutionContext context = afterTheFailure(NodeExecutionResult.failure(CALL, "provider down"));

        assertThat(calculator.calculateReadyNodes(context, tree)).containsExactly(JOIN);
    }

    @Test
    @DisplayName("a gate refusal (credit / plan) carries no flag and stops everything below, even when the plan asks to continue")
    void gateRefusalIsNeverContinued() {
        // The plan says continueOnFailure, but the calculator reads the RESULT: a gate refusal is
        // substituted before the runner and never stamped, so an out-of-credit node stops the run.
        when(plan.getNodePolicy(CALL)).thenReturn(new NodePolicy(0, 0L, true));
        ExecutionContext context = afterTheFailure(NodeExecutionResult.failureWithOutput(CALL,
            "Out of credits", Map.of("error_code", "CREDIT_EXHAUSTED"), 0L));

        Set<String> ready = calculator.calculateReadyNodes(context, tree);

        assertThat(ready).doesNotContain(AFTER);
        assertThat(ready).containsExactly(JOIN);
    }

    @Test
    @DisplayName("the flag on a SUCCESSFUL node changes nothing (it is only read on a failure)")
    void flagOnASuccessIsInert() {
        when(call.getNextNodes(any())).thenReturn(List.of(after));
        ExecutionContext context = afterTheFailure(NodeExecutionResult.success(CALL,
            Map.of(ExecutionMetadataKeys.POLICY_CONTINUE_ON_FAILURE, true)));

        // A success follows its own routing (getNextNodes), not every successor.
        assertThat(calculator.calculateReadyNodes(context, tree)).containsExactly(AFTER);
    }
}
