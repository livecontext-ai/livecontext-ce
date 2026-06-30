package com.apimarketplace.orchestrator.services.completion;

import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.conversation.client.ConversationClient;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.domain.workflow.Edge;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.workflow.StepExecutionResult;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.constants.ExecutionMetadataKeys;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.TriggerItem;
import com.apimarketplace.orchestrator.execution.v2.nodes.BaseNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeType;
import com.apimarketplace.orchestrator.execution.v2.services.NodeCompletionService;
import com.apimarketplace.orchestrator.execution.v2.state.BackEdgeState;
import com.apimarketplace.orchestrator.metrics.WorkflowMetrics;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowPersistenceService;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.orchestrator.services.merge.MergeIntegrationService;
import com.apimarketplace.orchestrator.services.persistence.StepPersistenceResult;
import com.apimarketplace.orchestrator.services.persistence.WorkflowEntityResolverService;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.services.state.TxScopedSnapshotCache;
import com.apimarketplace.orchestrator.services.streaming.NodeEventEmitterService;
import com.apimarketplace.orchestrator.services.streaming.bus.WorkflowEventPublisher;
import com.apimarketplace.orchestrator.services.streaming.state.RunningNodeTracker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase-2 attempt-vs-persistence tests against the REAL completion layer
 * (audit item "tests c"): a REAL {@link NodeCompletionService} → REAL
 * {@link StepCompletionOrchestrator} → REAL {@link StateSnapshotService}
 * (in-memory run row through a mocked repository, full-rewrite persistence
 * path), with a persistence fake that reproduces the
 * {@code idx_workflow_step_data_unique_v6} ON CONFLICT DO NOTHING semantics
 * ({@code step_alias, trigger_id, iteration, item_index, epoch, spawn, status}).
 *
 * <p>Pins the attempt-emission contract end to end:
 * <ul>
 *   <li>retry-then-success leaves {@code EpochState.failedNodeIds} clean and
 *       NodeCounts at completed=1/failed=0 - the attempt mutates NOTHING;</li>
 *   <li>each logical execution bills exactly ONE platform credit, including the
 *       retry-then-terminal-failure case where the terminal FAILED row dedupes
 *       onto the attempt row (persisted=false);</li>
 *   <li>loop context: attempts are WS-only - ONLY the terminal row reaches the
 *       persistence layer.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NodePolicy attempts - real StepCompletionOrchestrator + StateSnapshotService")
class NodePolicyAttemptPersistenceIntegrationTest {

    private static final String RUN_ID = "run-attempt-1";
    private static final UUID WF_RUN_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String TENANT = "tenant-1";
    private static final String TRIGGER_ID = "trigger:start";
    private static final String NODE_ID = "mcp:flaky";
    private static final int EPOCH = 1;

    @Mock private WorkflowRunRepository runRepository;
    @Mock private WorkflowEpochService workflowEpochService;
    @Mock private WorkflowEventPublisher eventPublisher;
    @Mock private StorageBreakdownService breakdownService;
    @Mock private WorkflowPersistenceService persistenceService;
    @Mock private MergeIntegrationService mergeIntegrationService;
    @Mock private WorkflowEntityResolverService entityResolverService;
    @Mock private CreditConsumptionClient creditClient;
    @Mock private NodeEventEmitterService nodeEventEmitterService;
    @Mock private RunningNodeTracker runningNodeTracker;
    @Mock private ConversationClient conversationClient;
    @Mock private WorkflowExecution execution;
    @Mock private WorkflowPlan plan;
    @Mock private ExecutionContext context;

    private ObjectMapper mapper;
    private WorkflowRunEntity runEntity;
    private StateSnapshotService stateSnapshotService;
    private StepCompletionOrchestrator orchestrator;
    private NodeCompletionService nodeCompletionService;
    private BaseNode node;

    /** v6-unique-index fake: rows that actually landed (insert order preserved). */
    private final Set<String> persistedRowKeys = new HashSet<>();
    private final List<String> persistedRowsInOrder = new ArrayList<>();
    private final List<String> insertAttempts = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        WorkflowMetrics workflowMetrics = new WorkflowMetrics(meterRegistry);

        // In-memory run row: loadFreshForUpdate returns the SAME entity instance, and
        // saveSnapshotFullRewrite keeps it coherent (run.setStateSnapshot after the
        // native UPDATE) - so successive mutations see each other's writes.
        runEntity = new WorkflowRunEntity();
        runEntity.setRunIdPublic(RUN_ID);
        runEntity.setTenantId(TENANT);
        runEntity.setStatus(RunStatus.RUNNING);
        runEntity.setStateSnapshot(mapper.writeValueAsString(
            StateSnapshot.empty().addReadyNode(TRIGGER_ID, NODE_ID, EPOCH)));

        lenient().when(runRepository.findByRunIdPublicForUpdate(RUN_ID)).thenReturn(Optional.of(runEntity));
        lenient().when(runRepository.updateSnapshotAndSeq(eq(RUN_ID), anyString())).thenReturn(1);
        lenient().when(runRepository.findStateSnapshotByRunIdPublic(RUN_ID))
            .thenAnswer(inv -> Optional.ofNullable(runEntity.getStateSnapshot()));

        stateSnapshotService = new StateSnapshotService(runRepository, mapper, workflowEpochService,
            eventPublisher, breakdownService, new TxScopedSnapshotCache(runRepository, meterRegistry),
            workflowMetrics);

        // Persistence fake - REAL v6 dedup semantics, spawn pinned to 0:
        // UNIQUE (workflow_run_id, step_alias, trigger_id, iteration, item_index, epoch, spawn, status)
        lenient().when(persistenceService.recordStep(any(), anyString(), anyString(), anyString(),
                any(StepExecutionResult.class), anyInt(), any()))
            .thenAnswer(inv -> {
                String nodeId = inv.getArgument(1);
                StepExecutionResult res = inv.getArgument(4);
                int epoch = inv.getArgument(5);
                String triggerId = inv.getArgument(6);
                Object iteration = res.output() != null ? res.output().get("iteration") : null;
                Object itemIndex = res.output() != null ? res.output().get("item_index") : null;
                String key = String.join("|", nodeId, String.valueOf(triggerId),
                    String.valueOf(iteration), String.valueOf(itemIndex),
                    String.valueOf(epoch), "0", res.status().name());
                insertAttempts.add(key);
                if (persistedRowKeys.add(key)) {
                    persistedRowsInOrder.add(key);
                    return StepPersistenceResult.success(UUID.randomUUID());
                }
                return StepPersistenceResult.notPersisted(); // ON CONFLICT DO NOTHING
            });

        orchestrator = new StepCompletionOrchestrator(
            persistenceService, eventPublisher, mergeIntegrationService, stateSnapshotService,
            workflowEpochService, entityResolverService, creditClient, workflowMetrics);

        nodeCompletionService = new NodeCompletionService(
            orchestrator, nodeEventEmitterService, eventPublisher, runningNodeTracker, conversationClient);

        lenient().when(execution.getRunId()).thenReturn(RUN_ID);
        lenient().when(execution.getWorkflowRunId()).thenReturn(WF_RUN_ID);
        lenient().when(execution.getPlan()).thenReturn(plan);
        lenient().when(plan.getTenantId()).thenReturn(TENANT);
        lenient().when(entityResolverService.getCurrentSpawnFromRun(WF_RUN_ID)).thenReturn(0);

        lenient().when(context.triggerId()).thenReturn(TRIGGER_ID);
        lenient().when(context.epoch()).thenReturn(EPOCH);
        lenient().when(context.getGlobalDataKeys()).thenReturn(Set.of()); // non-loop by default

        node = mock(BaseNode.class);
        lenient().when(node.getNodeId()).thenReturn(NODE_ID);
        lenient().when(node.getType()).thenReturn(NodeType.MCP);
    }

    private TriggerItem item() {
        return new TriggerItem("item-1", 0, Map.of());
    }

    private NodeExecutionResult annotatedFailure(int attempt, int maxAttempts) {
        Map<String, Object> output = new HashMap<>();
        output.put(ExecutionMetadataKeys.POLICY_ATTEMPT, attempt);
        output.put(ExecutionMetadataKeys.POLICY_MAX_ATTEMPTS, maxAttempts);
        Map<String, Object> metadata = new HashMap<>(output);
        return new NodeExecutionResult(NODE_ID, NodeStatus.FAILED, output,
            Optional.of("transient #" + attempt), metadata, 5L);
    }

    private NodeExecutionResult annotatedSuccess(int attempt, int maxAttempts) {
        Map<String, Object> output = new HashMap<>(Map.of("done", true));
        output.put(ExecutionMetadataKeys.POLICY_ATTEMPT, attempt);
        output.put(ExecutionMetadataKeys.POLICY_MAX_ATTEMPTS, maxAttempts);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(ExecutionMetadataKeys.POLICY_ATTEMPT, attempt);
        metadata.put(ExecutionMetadataKeys.POLICY_MAX_ATTEMPTS, maxAttempts);
        return new NodeExecutionResult(NODE_ID, NodeStatus.COMPLETED, output,
            Optional.empty(), metadata, 7L);
    }

    private StateSnapshot snapshot() {
        return stateSnapshotService.getSnapshot(RUN_ID);
    }

    @Test
    @DisplayName("retry-then-success: the attempt mutates NOTHING; terminal success leaves failedNodeIds clean, counts completed=1/failed=0, bills exactly once")
    void retryThenSuccessLeavesFailedNodeIdsCleanAndBillsOnce() {
        // ── Attempt 1 (FAILED, non-final) through the attempt pipeline ──
        nodeCompletionService.emitNodeFailedAttempt(execution, node, annotatedFailure(1, 2), item(), 0, context);

        // Mid-state: the attempt row persisted (non-loop), but the snapshot is UNTOUCHED.
        StateSnapshot afterAttempt = snapshot();
        assertThat(afterAttempt.getEpochState(TRIGGER_ID, EPOCH).getFailedNodeIds())
            .as("non-final attempt must NOT enter failedNodeIds (append-only set)")
            .isEmpty();
        assertThat(afterAttempt.getNodeCounts(NODE_ID).failed())
            .as("non-final attempt must NOT increment NodeCounts.failed").isZero();
        assertThat(persistedRowsInOrder).containsExactly(
            NODE_ID + "|" + TRIGGER_ID + "|0|0|" + EPOCH + "|0|FAILED");
        verify(creditClient, times(0)).consumeCreditsAsync(
            anyString(), anyString(), anyString(), isNull(), isNull(), isNull(), isNull());
        verify(workflowEpochService, times(0)).recordNodeCount(anyString(), anyInt(), anyString(), anyString(), any());

        // ── Attempt 2 (terminal COMPLETED) through the normal completion pipeline ──
        nodeCompletionService.emitNodeComplete(execution, node, annotatedSuccess(2, 2), item(), 0, context);

        StateSnapshot terminal = snapshot();
        assertThat(terminal.getEpochState(TRIGGER_ID, EPOCH).getFailedNodeIds())
            .as("retry-then-success ends with a CLEAN epoch state").isEmpty();
        assertThat(terminal.getEpochState(TRIGGER_ID, EPOCH).getCompletedNodeIds()).contains(NODE_ID);
        assertThat(terminal.getNodeCounts(NODE_ID).completed()).isEqualTo(1);
        assertThat(terminal.getNodeCounts(NODE_ID).failed()).isZero();

        // DB history: one FAILED attempt row + the terminal COMPLETED row (distinct status slot)
        assertThat(persistedRowsInOrder).containsExactly(
            NODE_ID + "|" + TRIGGER_ID + "|0|0|" + EPOCH + "|0|FAILED",
            NODE_ID + "|" + TRIGGER_ID + "|0|0|" + EPOCH + "|0|COMPLETED");

        // Billing + per-epoch counter: exactly once, on the terminal attempt
        verify(creditClient, times(1)).consumeCreditsAsync(
            eq(TENANT), eq("WORKFLOW_NODE"), anyString(), isNull(), isNull(), isNull(), isNull());
        verify(workflowEpochService, times(1)).recordNodeCount(
            eq(RUN_ID), eq(EPOCH), eq(NODE_ID), eq("COMPLETED"), eq(TRIGGER_ID));
    }

    @Test
    @DisplayName("retry-then-terminal-failure: terminal FAILED row dedupes onto the attempt row (v6 index) yet bills exactly once and marks failedNodeIds once")
    void retryThenTerminalFailureBillsExactlyOnceDespiteRowDedup() {
        // Attempt 1 (non-final) - persists THE single FAILED row slot
        nodeCompletionService.emitNodeFailedAttempt(execution, node, annotatedFailure(1, 2), item(), 0, context);
        // Attempt 2 (terminal failure) - same v6 tuple (status=FAILED) → ON CONFLICT dropped
        nodeCompletionService.emitNodeComplete(execution, node, annotatedFailure(2, 2), item(), 0, context);

        // The terminal insert was attempted and deduped
        assertThat(insertAttempts).hasSize(2);
        assertThat(persistedRowsInOrder).containsExactly(
            NODE_ID + "|" + TRIGGER_ID + "|0|0|" + EPOCH + "|0|FAILED");

        // Snapshot reflects ONE terminal failure (not one per attempt)
        StateSnapshot terminal = snapshot();
        assertThat(terminal.getEpochState(TRIGGER_ID, EPOCH).getFailedNodeIds()).containsExactly(NODE_ID);
        assertThat(terminal.getNodeCounts(NODE_ID).failed()).isEqualTo(1);
        assertThat(terminal.getNodeCounts(NODE_ID).completed()).isZero();

        // Billing invariant: ONE credit for the logical execution - the deduped
        // terminal failure must still bill (it is the only billing point; the
        // attempt path never bills). Pre-fix this was ZERO (persisted=false skipped
        // billing) - would fail on the unpatched orchestrator.
        verify(creditClient, times(1)).consumeCreditsAsync(
            eq(TENANT), eq("WORKFLOW_NODE"), anyString(), isNull(), isNull(), isNull(), isNull());
    }

    @Test
    @DisplayName("loop context: attempts are WS-only (no persistence call) - ONLY the terminal row reaches the persistence layer, billed once")
    void loopContextAttemptIsWsOnlyAndOnlyTerminalRowPersists() {
        // Loop context: active (non-terminated) back-edge state owned by this node,
        // iteration=1 - same shape NodeCompletionService.extractCurrentIteration reads.
        String edgeId = NODE_ID + "->core:loop:iterate";
        BackEdgeState loopState = BackEdgeState.create(edgeId, 5, "true").incrementIteration();
        when(context.getGlobalDataKeys()).thenReturn(Set.of("back_edge_state:" + edgeId));
        when(context.getGlobalData("back_edge_state:" + edgeId)).thenReturn(Optional.of(loopState));
        when(context.plan()).thenReturn(plan);
        when(plan.getIterateEdgesForSource(NODE_ID)).thenReturn(List.of(new Edge(NODE_ID, "core:loop:iterate")));

        // Attempt 1 (non-final, loop) - WS-only: NOTHING reaches the persistence layer
        nodeCompletionService.emitNodeFailedAttempt(execution, node, annotatedFailure(1, 2), item(), 0, context);
        assertThat(insertAttempts)
            .as("loop-context attempts must not even attempt a step_data INSERT").isEmpty();
        // ...but the attempt was NOT silent: its step event went out
        verify(eventPublisher, times(1)).emitStep(eq(RUN_ID), eq(NODE_ID), any(), any());

        // Terminal failure of this iteration - the ONLY persisted row, stamped iteration=1
        // and carrying the final attempt annotation (policy_attempt=2/2), so loop-history
        // reconstruction sees the true terminal state instead of "attempt 1/2".
        nodeCompletionService.emitNodeComplete(execution, node, annotatedFailure(2, 2), item(), 0, context);

        assertThat(persistedRowsInOrder).containsExactly(
            NODE_ID + "|" + TRIGGER_ID + "|1|0|" + EPOCH + "|0|FAILED");
        StateSnapshot terminal = snapshot();
        assertThat(terminal.getEpochState(TRIGGER_ID, EPOCH).getFailedNodeIds()).containsExactly(NODE_ID);
        assertThat(terminal.getNodeCounts(NODE_ID).failed()).isEqualTo(1);

        // Terminal row persisted normally → billed once via the persisted branch
        verify(creditClient, times(1)).consumeCreditsAsync(
            eq(TENANT), eq("WORKFLOW_NODE"), anyString(), isNull(), isNull(), isNull(), isNull());
    }
}
