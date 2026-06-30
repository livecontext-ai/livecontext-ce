package com.apimarketplace.orchestrator.execution.v2.services;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionTree;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeType;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression test for the production data-corruption bug observed in run
 * {@code 656a4aed-47b6-4a9f-bfa7-9bff2f707cca}, epoch 2, item 0
 * (workflow "Gmail Auto-Labeler"):
 *
 * <p>Sequence:
 * <ol>
 *   <li>{@code agent:classify} routed item 0 to {@code category_6} (urgent).</li>
 *   <li>{@code mcp:apply_tech} (predecessor: {@code category_5}) was correctly SKIPPED.</li>
 *   <li>The skip-completion path persisted a non-empty <em>status envelope</em>
 *       to the context's step outputs:
 *       <pre>{@code
 *       {
 *         "output": {"_status": "SKIPPED", "_duration_ms": 0, "_display_name": "livecontext"},
 *         "statusValue": "skipped",
 *         "statusMessage": "No items routed to this branch",
 *         "graphNodeId": "mcp:apply_tech"
 *       }
 *       }</pre></li>
 *   <li>After {@code V2StepByStepContextManager.reconstructEpochScopedExecutionState},
 *       {@code ReadyNodeCalculator.processCompletedNode} was re-entered for
 *       {@code mcp:apply_tech}. The check at lines 312-348 keys off
 *       {@code outputOpt.isPresent()}: because the envelope is a non-empty Map, the
 *       branch fell through to "traverse successors". The downstream
 *       {@code table:record_tech} INSERTED a row labelled "Tech &amp; Dev" - but Gmail
 *       received the "Urgent" label for that same email. Data corruption.</li>
 * </ol>
 *
 * <p>The contract the calculator must hold: a node that completed with status
 * SKIPPED must not have its successors traversed, <em>regardless of whether the
 * stored output is empty or a status envelope</em>.
 *
 * <p>This file pins both sides of that contract:
 * <ul>
 *   <li>{@link #skippedNodeWithStatusEnvelopeDoesNotTraverseSuccessors} -
 *       reproduces the prod scenario and asserts {@code table:record_tech} is NOT
 *       added to the ready set.</li>
 *   <li>{@link #completedNodeWithRealOutputTraversesSuccessors} - locks in the
 *       positive case so a fix doesn't over-correct and break the happy path.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ReadyNodeCalculator - SKIPPED nodes with a status envelope must not traverse successors")
class ReadyNodeCalculatorSkippedEnvelopeTest {

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
        // The 2nd and 3rd ctor args (StateSnapshotService, DAGIndependenceValidator)
        // are not consulted on the SKIPPED branch we exercise here - null is enough.
        calculator = new ReadyNodeCalculator(mergeNodeAnalyzer, null, null);
        when(context.getAllStepOutputs()).thenReturn(Map.of());
    }

    @Test
    @DisplayName("Prod run 656a4aed item 0: SKIPPED apply_tech with status envelope must NOT cause record_tech to become ready")
    void skippedNodeWithStatusEnvelopeDoesNotTraverseSuccessors() {
        // Arrange - mirror the prod node graph: classify → category_5 → apply_tech → record_tech
        // ReadyNodeCalculator's tree traversal here starts from the SKIPPED apply_tech
        // (the rehydrated state has it marked completed-with-envelope) and we assert
        // the calculator does NOT propagate into table:record_tech.
        ExecutionNode applyTech = mock(ExecutionNode.class);
        ExecutionNode recordTech = mock(ExecutionNode.class);

        when(applyTech.getNodeId()).thenReturn("mcp:apply_tech");
        when(applyTech.getType()).thenReturn(NodeType.MCP);
        when(applyTech.getNextNodes(any())).thenReturn(List.of(recordTech));
        when(applyTech.getAllChildNodes()).thenReturn(List.of());

        when(recordTech.getNodeId()).thenReturn("table:record_tech");
        when(recordTech.getType()).thenReturn(NodeType.MCP);
        // record_tech has no predecessor blockers - it would otherwise be ready.
        when(recordTech.canExecute(context)).thenReturn(true);
        when(recordTech.getPredecessorIds()).thenReturn(List.of("mcp:apply_tech"));

        when(tree.getRootNodes()).thenReturn(List.of(applyTech));
        when(tree.getPlan()).thenReturn(plan);

        // apply_tech is a TERMINAL state - NodeStatus.SKIPPED - in ExecutionState.
        // ExecutionContext.isCompleted is overloaded as "any terminal state" so it
        // returns true for SKIPPED nodes too; the canonical SKIPPED signal is
        // isSkipped(nodeId), which the fix at ReadyNodeCalculator.processCompletedNode
        // gates on before deciding whether to traverse successors.
        when(context.isCompleted("mcp:apply_tech")).thenReturn(true);
        when(context.isStarted("mcp:apply_tech")).thenReturn(true);
        when(context.isFailed("mcp:apply_tech")).thenReturn(false);
        when(context.isSkipped("mcp:apply_tech")).thenReturn(true);

        // …and the step output is the SKIPPED status envelope that the prod
        // persistence path wrote. This is non-empty → outputOpt.isPresent() is true
        // → without the isSkipped gate, the old code at lines 312-348 fell through
        // to "traverse successors", which is exactly the data-corruption shape.
        Map<String, Object> skippedStatusEnvelope = Map.of(
                "output", Map.of(
                        "_status", "SKIPPED",
                        "_duration_ms", 0,
                        "_display_name", "livecontext"
                ),
                "statusValue", "skipped",
                "statusMessage", "No items routed to this branch",
                "graphNodeId", "mcp:apply_tech"
        );
        when(context.getStepOutput("mcp:apply_tech"))
                .thenReturn(Optional.of(skippedStatusEnvelope));

        when(context.isCompleted("table:record_tech")).thenReturn(false);
        when(context.isStarted("table:record_tech")).thenReturn(false);
        when(mergeNodeAnalyzer.isMergeNode(plan, "table:record_tech")).thenReturn(false);
        when(mergeNodeAnalyzer.isMergeNode(plan, "mcp:apply_tech")).thenReturn(false);

        // Act
        Set<String> readyNodes = calculator.calculateReadyNodes(context, tree);

        // Assert - the data-corruption guard: record_tech must stay out of the ready set
        // when its sole upstream predecessor was SKIPPED, even if that predecessor
        // happens to have persisted a non-empty status envelope as its "output".
        assertThat(readyNodes)
                .as("table:record_tech must NOT become ready when mcp:apply_tech was SKIPPED "
                        + "(prod 656a4aed-47b6-4a9f-bfa7-9bff2f707cca data corruption regression). "
                        + "If this fails, the SKIPPED-with-envelope check in "
                        + "ReadyNodeCalculator.processCompletedNode is missing.")
                .doesNotContain("table:record_tech");
    }

    @Test
    @DisplayName("Positive case: COMPLETED node with real output still traverses to its successors")
    void completedNodeWithRealOutputTraversesSuccessors() {
        // Arrange - same graph shape, but apply_tech genuinely COMPLETED with real
        // domain data (no SKIPPED envelope markers). The fix must not over-correct
        // and starve the happy path.
        ExecutionNode applyTech = mock(ExecutionNode.class);
        ExecutionNode recordTech = mock(ExecutionNode.class);

        when(applyTech.getNodeId()).thenReturn("mcp:apply_tech");
        when(applyTech.getType()).thenReturn(NodeType.MCP);
        when(applyTech.getNextNodes(any())).thenReturn(List.of(recordTech));
        when(applyTech.getAllChildNodes()).thenReturn(List.of());

        when(recordTech.getNodeId()).thenReturn("table:record_tech");
        when(recordTech.getType()).thenReturn(NodeType.MCP);
        when(recordTech.canExecute(context)).thenReturn(true);
        when(recordTech.getPredecessorIds()).thenReturn(List.of("mcp:apply_tech"));

        when(tree.getRootNodes()).thenReturn(List.of(applyTech));
        when(tree.getPlan()).thenReturn(plan);

        when(context.isCompleted("mcp:apply_tech")).thenReturn(true);
        when(context.isStarted("mcp:apply_tech")).thenReturn(true);
        when(context.isFailed("mcp:apply_tech")).thenReturn(false);
        when(context.isSkipped("mcp:apply_tech")).thenReturn(false);

        // Real Gmail labelling result - no _status / statusValue markers.
        Map<String, Object> realOutput = Map.of(
                "labeled_email_id", "msg-abc-123",
                "label", "Tech & Dev",
                "_status", "COMPLETED",
                "_duration_ms", 230
        );
        when(context.getStepOutput("mcp:apply_tech")).thenReturn(Optional.of(realOutput));

        when(context.isCompleted("table:record_tech")).thenReturn(false);
        when(context.isStarted("table:record_tech")).thenReturn(false);
        when(mergeNodeAnalyzer.isMergeNode(plan, "table:record_tech")).thenReturn(false);
        when(mergeNodeAnalyzer.isMergeNode(plan, "mcp:apply_tech")).thenReturn(false);

        // Act
        Set<String> readyNodes = calculator.calculateReadyNodes(context, tree);

        // Assert - happy path: record_tech MUST be ready after a genuine COMPLETED.
        assertThat(readyNodes)
                .as("table:record_tech must become ready when mcp:apply_tech is genuinely "
                        + "COMPLETED with domain output. If this fails, the SKIPPED-envelope "
                        + "fix over-corrected and broke the happy path.")
                .contains("table:record_tech");
    }

    @Test
    @DisplayName("SKIPPED carve-out: downstream merge node must still be evaluated for readiness")
    void skippedNodeStillEvaluatesDownstreamMergeForReadiness() {
        // Arrange - Decision/Switch routing-skip case: apply_tech SKIPPED, but a merge
        // node sits downstream and the OTHER predecessor (apply_urgent) just completed.
        // The merge must NOT be left stranded by the SKIPPED early-return - it should
        // be picked up by the SKIPPED-side carve-out symmetric to FAILED.
        // Mock BaseNode (concrete superclass of all execution nodes) so the carve-out
        // can fall into the BaseNode.getSuccessors() branch - ExecutionNode is the
        // interface, BaseNode is what processCompletedNode checks via instanceof.
        com.apimarketplace.orchestrator.execution.v2.nodes.BaseNode applyTech =
                mock(com.apimarketplace.orchestrator.execution.v2.nodes.BaseNode.class);
        ExecutionNode mergeNode = mock(ExecutionNode.class);
        com.apimarketplace.orchestrator.execution.v2.nodes.BaseNode applyUrgent =
                mock(com.apimarketplace.orchestrator.execution.v2.nodes.BaseNode.class);

        when(applyTech.getNodeId()).thenReturn("mcp:apply_tech");
        when(applyTech.getType()).thenReturn(NodeType.MCP);
        when(applyTech.getAllChildNodes()).thenReturn(List.of());
        when(applyTech.getSuccessors()).thenReturn(List.of(mergeNode));

        when(applyUrgent.getNodeId()).thenReturn("mcp:apply_urgent");
        when(applyUrgent.getType()).thenReturn(NodeType.MCP);
        when(applyUrgent.getAllChildNodes()).thenReturn(List.of());
        when(applyUrgent.getSuccessors()).thenReturn(List.of(mergeNode));
        when(applyUrgent.getNextNodes(any())).thenReturn(List.of(mergeNode));

        when(mergeNode.getNodeId()).thenReturn("core:merge");
        when(mergeNode.getType()).thenReturn(NodeType.MCP);
        when(mergeNode.canExecute(context)).thenReturn(true);
        when(mergeNode.getPredecessorIds()).thenReturn(List.of("mcp:apply_tech", "mcp:apply_urgent"));

        when(tree.getRootNodes()).thenReturn(List.of(applyTech, applyUrgent));
        when(tree.getPlan()).thenReturn(plan);

        when(context.isCompleted("mcp:apply_tech")).thenReturn(true);
        when(context.isStarted("mcp:apply_tech")).thenReturn(true);
        when(context.isFailed("mcp:apply_tech")).thenReturn(false);
        when(context.isSkipped("mcp:apply_tech")).thenReturn(true);
        when(context.getStepOutput("mcp:apply_tech")).thenReturn(Optional.of(Map.of(
                "output", Map.of("_status", "SKIPPED"),
                "statusValue", "skipped"
        )));

        when(context.isCompleted("mcp:apply_urgent")).thenReturn(true);
        when(context.isStarted("mcp:apply_urgent")).thenReturn(true);
        when(context.isFailed("mcp:apply_urgent")).thenReturn(false);
        when(context.isSkipped("mcp:apply_urgent")).thenReturn(false);
        when(context.getStepOutput("mcp:apply_urgent"))
                .thenReturn(Optional.of(Map.of("labeled_email_id", "msg-1")));

        when(context.isCompleted("core:merge")).thenReturn(false);
        when(context.isStarted("core:merge")).thenReturn(false);
        when(mergeNodeAnalyzer.isMergeNode(plan, "core:merge")).thenReturn(true);
        when(mergeNodeAnalyzer.isMergeNode(plan, "mcp:apply_tech")).thenReturn(false);
        when(mergeNodeAnalyzer.isMergeNode(plan, "mcp:apply_urgent")).thenReturn(false);

        // Act
        Set<String> readyNodes = calculator.calculateReadyNodes(context, tree);

        // Assert - the merge MUST be ready: SKIPPED on one predecessor + COMPLETED on
        // the other = all predecessors resolved. If this fails, the SKIPPED carve-out
        // for merges (symmetric to the FAILED one at lines 288-310) is missing.
        assertThat(readyNodes)
                .as("core:merge must be ready when one predecessor SKIPPED and the other "
                        + "COMPLETED. If this fails, the SKIPPED branch of "
                        + "ReadyNodeCalculator.processCompletedNode is not walking successors "
                        + "to discover merge nodes.")
                .contains("core:merge");
    }
}
