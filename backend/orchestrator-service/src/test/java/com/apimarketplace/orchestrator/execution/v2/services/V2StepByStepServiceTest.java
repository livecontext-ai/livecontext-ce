package com.apimarketplace.orchestrator.execution.v2.services;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.SignalType;
import com.apimarketplace.orchestrator.domain.workflow.ExecutionMode;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.cache.ExecutionCacheManager;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionTree;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionTreeBuilder;
import com.apimarketplace.orchestrator.execution.v2.engine.StepByStepExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.engine.UnifiedExecutionEngine;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.scheduler.V2StepByStepScheduler;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for V2StepByStepService.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("V2StepByStepService")
class V2StepByStepServiceTest {

    @Mock private UnifiedExecutionEngine mockEngine;
    @Mock private ExecutionTreeBuilder mockTreeBuilder;
    @Mock private V2ExecutionEventService mockEventService;
    @Mock private V2StepByStepScheduler mockScheduler;
    @Mock private V2StepByStepContextManager mockContextManager;
    @Mock private V2TriggerLoadingService mockTriggerLoadingService;
    @Mock private WorkflowRunRepository mockRunRepository;
    @Mock private ExecutionCacheManager mockCacheManager;
    @Mock private StateSnapshotService mockStateSnapshotService;
    @Mock private WorkflowExecution mockExecution;
    @Mock private WorkflowPlan mockPlan;
    @Mock private ExecutionTree mockTree;
    @Mock private ExecutionContext mockContext;

    private V2StepByStepService service;

    @BeforeEach
    void setUp() {
        service = new V2StepByStepService(
            mockEngine, mockTreeBuilder, mockEventService, mockScheduler,
            mockContextManager, mockTriggerLoadingService, mockRunRepository,
            mockCacheManager, mockStateSnapshotService
        );
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private ExecutionCacheManager.LoadedExecution validLoadedExecution() {
        // Use real record instance instead of mock to avoid UnfinishedStubbing
        // when called inside another when().thenReturn() chain
        return new ExecutionCacheManager.LoadedExecution(mockTree, mockExecution);
    }

    /**
     * Set up common mocks for executeNodeInternal on a non-trigger node.
     * Returns the engine result that executeSingleNode will produce.
     */
    private StepByStepExecutionResult setupExecuteNodeMocks(
            String nodeId, String itemId,
            NodeExecutionResult nodeResult,
            Set<String> engineReadyNodes,
            Set<String> snapshotReadyNodes) {

        when(mockCacheManager.loadTreeAndExecution("run-1")).thenReturn(validLoadedExecution());

        when(mockContextManager.getOrCreateContextWithTriggerData(
            eq("run-1:" + itemId), eq(mockTree), eq(itemId), anyInt(), eq(nodeId)
        )).thenReturn(mockContext);
        lenient().when(mockContext.triggerData()).thenReturn(Map.of());
        lenient().when(mockContext.triggerId()).thenReturn("trigger:start");
        lenient().when(mockContext.epoch()).thenReturn(0);
        lenient().when(mockContext.getGlobalDataKeys()).thenReturn(Set.of());

        StepByStepExecutionResult engineResult = new StepByStepExecutionResult(
            mockContext, nodeResult, engineReadyNodes, engineReadyNodes.isEmpty());
        when(mockEngine.executeSingleNode(
            eq(nodeId), eq(mockTree), eq(mockContext),
            eq(mockExecution), eq(mockEventService), any()
        )).thenReturn(engineResult);

        lenient().when(mockStateSnapshotService.getReadyNodeIds("run-1")).thenReturn(snapshotReadyNodes);

        return engineResult;
    }

    private WorkflowRunEntity createRunEntity(RunStatus status) {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setRunIdPublic("run-1");
        run.setStatus(status);
        run.setUpdatedAt(Instant.now());
        run.setMetadata(new HashMap<>());
        return run;
    }

    // =========================================================================
    // EXISTING TESTS (unchanged)
    // =========================================================================

    @Nested
    @DisplayName("initializeStepByStep")
    class InitializeStepByStep {

        private static final UUID WORKFLOW_RUN_ID = UUID.fromString("00000000-0000-0000-0000-00000000002a");

        @Test
        @DisplayName("should build tree and return initial ready nodes")
        void shouldBuildTreeAndReturnReadyNodes() {
            when(mockExecution.getRunId()).thenReturn("run-1");
            when(mockExecution.getWorkflowRunId()).thenReturn(WORKFLOW_RUN_ID);
            when(mockPlan.getTenantId()).thenReturn("tenant-1");

            when(mockTreeBuilder.build(eq("run-1"), eq(WORKFLOW_RUN_ID.toString()), eq("tenant-1"), eq(mockPlan), isNull(), isNull())).thenReturn(mockTree);
            when(mockTree.withExecutionMode(ExecutionMode.STEP_BY_STEP)).thenReturn(mockTree);
            when(mockEngine.getInitialReadyNodes(mockTree)).thenReturn(Set.of("trigger:start"));

            Map<String, Object> result = service.initializeStepByStep(mockExecution, mockPlan);

            assertEquals("run-1", result.get("runId"));
            assertEquals(Set.of("trigger:start"), result.get("readyNodes"));
            assertEquals(false, result.get("workflowComplete"));
            assertEquals("STEP_BY_STEP", result.get("mode"));

            verify(mockEventService).initializeExecution(mockExecution, true);
            verify(mockEventService).emitStepByStepReady(mockExecution, Set.of("trigger:start"), null, false);
        }

        @Test
        @DisplayName("should persist initial ready nodes to StateSnapshot for webhook dispatch")
        void shouldPersistInitialReadyNodesToSnapshot() {
            when(mockExecution.getRunId()).thenReturn("run-1");
            when(mockExecution.getWorkflowRunId()).thenReturn(WORKFLOW_RUN_ID);
            when(mockPlan.getTenantId()).thenReturn("tenant-1");

            when(mockTreeBuilder.build(eq("run-1"), eq(WORKFLOW_RUN_ID.toString()), eq("tenant-1"), eq(mockPlan), isNull(), isNull())).thenReturn(mockTree);
            when(mockTree.withExecutionMode(ExecutionMode.STEP_BY_STEP)).thenReturn(mockTree);
            when(mockEngine.getInitialReadyNodes(mockTree))
                .thenReturn(Set.of("trigger:webhook_a", "trigger:webhook_b"));

            service.initializeStepByStep(mockExecution, mockPlan);

            // Verify initial ready nodes are persisted to StateSnapshot
            verify(mockStateSnapshotService).initializeSnapshot("run-1");
            verify(mockStateSnapshotService).updateReadyNodes("run-1",
                Set.of("trigger:webhook_a", "trigger:webhook_b"));
        }

        @Test
        @DisplayName("should use runId when workflowRunId is null")
        void shouldUseRunIdWhenWorkflowRunIdNull() {
            when(mockExecution.getRunId()).thenReturn("run-1");
            when(mockExecution.getWorkflowRunId()).thenReturn(null);
            when(mockPlan.getTenantId()).thenReturn("tenant-1");

            when(mockTreeBuilder.build(eq("run-1"), eq("run-1"), eq("tenant-1"), eq(mockPlan), isNull(), isNull())).thenReturn(mockTree);
            when(mockTree.withExecutionMode(ExecutionMode.STEP_BY_STEP)).thenReturn(mockTree);
            when(mockEngine.getInitialReadyNodes(mockTree)).thenReturn(Set.of());

            Map<String, Object> result = service.initializeStepByStep(mockExecution, mockPlan);

            assertNotNull(result);
        }

        @Test
        @DisplayName("threads workflow run org scope into the step-by-step execution tree")
        void threadsWorkflowRunOrgScopeIntoExecutionTree() {
            when(mockExecution.getRunId()).thenReturn("run-1");
            when(mockExecution.getWorkflowRunId()).thenReturn(WORKFLOW_RUN_ID);
            when(mockPlan.getTenantId()).thenReturn("tenant-1");
            WorkflowRunEntity run = createRunEntity(RunStatus.WAITING_TRIGGER);
            run.setOrganizationId("org-acme");
            run.setOrganizationRole("OWNER");
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            when(mockTreeBuilder.build(
                eq("run-1"),
                eq(WORKFLOW_RUN_ID.toString()),
                eq("tenant-1"),
                eq(mockPlan),
                eq("org-acme"),
                eq("OWNER"))).thenReturn(mockTree);
            when(mockTree.withExecutionMode(ExecutionMode.STEP_BY_STEP)).thenReturn(mockTree);
            when(mockEngine.getInitialReadyNodes(mockTree)).thenReturn(Set.of("trigger:start"));

            service.initializeStepByStep(mockExecution, mockPlan, "trigger:start");

            verify(mockTreeBuilder).build(
                eq("run-1"),
                eq(WORKFLOW_RUN_ID.toString()),
                eq("tenant-1"),
                eq(mockPlan),
                eq("org-acme"),
                eq("OWNER"));
        }
    }

    @Nested
    @DisplayName("cacheTriggerPayload")
    class CacheTriggerPayload {

        @Test
        @DisplayName("should cache non-empty payload")
        void shouldCacheNonEmptyPayload() {
            Map<String, Object> payload = Map.of("data", "test_data");

            service.cacheTriggerPayload("run-1", payload);

            verify(mockContextManager).cacheTriggerItems(eq("run-1"), anyList());
        }

        @Test
        @DisplayName("should cache concurrent reusable trigger payloads by epoch")
        void shouldCacheConcurrentReusableTriggerPayloadsByEpoch() {
            Map<String, Object> payload = Map.of("name", "Ada", "index", 0);

            service.cacheTriggerPayload("run-1", 3, payload);

            verify(mockContextManager).cacheTriggerItems(
                eq("run-1"),
                eq(3),
                argThat(items -> items.size() == 1
                    && "Ada".equals(items.get(0).get("name"))
                    && Integer.valueOf(0).equals(items.get(0).get("index"))));
            verify(mockContextManager, never()).cacheTriggerItems(eq("run-1"), anyList());
        }

        @Test
        @DisplayName("should not cache null payload")
        void shouldNotCacheNull() {
            service.cacheTriggerPayload("run-1", null);

            verify(mockContextManager, never()).cacheTriggerItems(any(), any());
        }

        @Test
        @DisplayName("should not cache empty payload")
        void shouldNotCacheEmpty() {
            service.cacheTriggerPayload("run-1", Map.of());

            verify(mockContextManager, never()).cacheTriggerItems(any(), any());
        }
    }

    @Nested
    @DisplayName("getReadyNodes")
    class GetReadyNodes {

        @Test
        @DisplayName("should return empty set when tree is null")
        void shouldReturnEmptyWhenTreeNull() {
            when(mockContextManager.getTree("run-1")).thenReturn(null);

            Set<String> result = service.getReadyNodes("run-1", "0");

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should calculate ready nodes from engine")
        void shouldCalculateReadyNodes() {
            // Mock a root node for the per-DAG iteration
            ExecutionNode mockRootNode = mock(ExecutionNode.class);
            when(mockRootNode.getNodeId()).thenReturn("trigger:start");

            when(mockContextManager.getTree("run-1")).thenReturn(mockTree);
            when(mockTree.getRootNodes()).thenReturn(List.of(mockRootNode));
            when(mockContextManager.getOrCreateContextWithTriggerData(
                eq("run-1:0:dag:trigger:start"), eq(mockTree), eq("0"), eq(0), eq("trigger:start")
            )).thenReturn(mockContext);
            when(mockEngine.calculateReadyNodes(mockContext, mockTree))
                .thenReturn(Set.of("mcp:step1", "mcp:step2"));

            Set<String> result = service.getReadyNodes("run-1", "0");

            assertEquals(2, result.size());
            assertTrue(result.contains("mcp:step1"));
            assertTrue(result.contains("mcp:step2"));
        }
    }

    @Nested
    @DisplayName("executeNode")
    class ExecuteNode {

        @Test
        @DisplayName("should throw when execution not found")
        void shouldThrowWhenExecutionNotFound() {
            when(mockCacheManager.loadTreeAndExecution("run-1")).thenReturn(null);

            assertThrows(IllegalStateException.class, () ->
                service.executeNode("run-1", "mcp:step1", "0"));
        }

        @Test
        @DisplayName("should throw when tree is null in loaded execution")
        void shouldThrowWhenTreeNull() {
            var loaded = new ExecutionCacheManager.LoadedExecution(null, mockExecution);
            when(mockCacheManager.loadTreeAndExecution("run-1")).thenReturn(loaded);

            assertThrows(IllegalStateException.class, () ->
                service.executeNode("run-1", "mcp:step1", "0"));
        }
    }

@Nested
    @DisplayName("Idempotency fast-path Redis overlay (P2.2 site 5)")
    class IdempotencyFastPathRedisOverlay {

        @org.mockito.Mock
        private com.apimarketplace.orchestrator.services.streaming.state.RunningNodeTracker mockRunningNodeTracker;

        @org.junit.jupiter.api.BeforeEach
        void wireRedisTracker() {
            // Field injection (P2.2 site 5 introduced) - mirror the @Autowired(required=false)
            // wiring by reflection so the @BeforeEach in the outer class doesn't have to know
            // about it.
            org.springframework.test.util.ReflectionTestUtils.setField(
                    service, "runningNodeTracker", mockRunningNodeTracker);
        }

        /**
         * Builds a minimal StateSnapshot whose epoch has empty running/completed/skipped
         * sets and is NOT split-aware - the exact post-P2.3 scenario.
         */
        private com.apimarketplace.orchestrator.domain.execution.StateSnapshot snapshotWithEmptyEpoch(
                String triggerId, int epoch) {
            com.apimarketplace.orchestrator.domain.execution.EpochState es =
                    new com.apimarketplace.orchestrator.domain.execution.EpochState(
                            Set.of(triggerId), Set.of(), Set.of(),
                            Set.of(), Set.of(), Set.of(),
                            Map.of(), Map.of(), Map.of(), Instant.now());
            Map<Integer, com.apimarketplace.orchestrator.domain.execution.EpochState> epochs = new HashMap<>();
            epochs.put(epoch, es);
            com.apimarketplace.orchestrator.domain.execution.DagState dag =
                    new com.apimarketplace.orchestrator.domain.execution.DagState(
                            epoch, 0, 1, epochs, Set.of(epoch));
            return com.apimarketplace.orchestrator.domain.execution.StateSnapshot.empty()
                    .withDagState(triggerId, dag);
        }

        @Test
        @DisplayName("Redis says running + JSONB empty + epoch≥0 + triggerId≠null → idempotent skip (post-P2.3 load-bearing path)")
        void redisRunningWithEmptyJsonbTriggersIdempotentSkip() {
            // This is the EXACT divergence case P2.3 elision will create: JSONB is
            // unconditionally empty for runningNodeIds, and Redis is the only source
            // of "this node is currently running on a peer instance". The fast-path
            // MUST honor Redis to avoid double-executing the node.
            String runId = "run-overlay-1";
            String triggerId = "trigger:my_webhook";
            String nodeId = "mcp:step1";
            int epoch = 0;

            when(mockStateSnapshotService.getSnapshot(runId))
                    .thenReturn(snapshotWithEmptyEpoch(triggerId, epoch));
            when(mockRunningNodeTracker.getRunningCountsAcrossEpochs(runId))
                    .thenReturn(Map.of(nodeId, 1));

            StepByStepExecutionResult result = service.executeNode(runId, nodeId, "0", epoch, triggerId, null);

            // Idempotent-skip: result has no execution, just a benign success marker.
            assertNotNull(result.nodeResult());
            assertTrue((Boolean) result.nodeResult().output().get("idempotent_skip"));
            // The slow-path (cacheManager.loadTreeAndExecution) MUST NOT have been invoked
            // because the fast-path short-circuited before reaching ensureInitialized.
            verify(mockCacheManager, never()).loadTreeAndExecution(anyString());
        }

        @Test
        @DisplayName("Redis empty + JSONB empty → fast-path falls through (slow-path executes normally)")
        void redisEmptyAndJsonbEmptyFallsThrough() {
            // Negative control: when neither JSONB nor Redis flags the node as running,
            // the fast-path skip MUST NOT fire - the slow-path runs as usual.
            String runId = "run-overlay-2";
            String triggerId = "trigger:my_webhook";
            String nodeId = "mcp:step1";
            int epoch = 0;

            when(mockStateSnapshotService.getSnapshot(runId))
                    .thenReturn(snapshotWithEmptyEpoch(triggerId, epoch));
            when(mockRunningNodeTracker.getRunningCountsAcrossEpochs(runId))
                    .thenReturn(Map.of());
            when(mockCacheManager.loadTreeAndExecution(runId)).thenReturn(null);

            // The fast-path returns false → slow-path begins → ensureInitialized throws
            // (no execution loaded). That confirms we did NOT short-circuit on the fast-path.
            assertThrows(IllegalStateException.class,
                    () -> service.executeNode(runId, nodeId, "0", epoch, triggerId, null));

            verify(mockRunningNodeTracker).getRunningCountsAcrossEpochs(runId);
        }

        @Test
        @DisplayName("Redis tracker bean absent → fast-path uses JSONB only (legacy unit-test wiring)")
        void absentTrackerFallsBackToJsonbOnly() {
            // When the tracker bean is null (e.g. in tests without Redis), the fast-path
            // overlay simply skips the Redis check. JSONB still drives the alreadyRunning
            // decision pre-elision.
            org.springframework.test.util.ReflectionTestUtils.setField(service, "runningNodeTracker", null);
            String runId = "run-overlay-3";
            String triggerId = "trigger:my_webhook";
            String nodeId = "mcp:step1";
            int epoch = 0;

            when(mockStateSnapshotService.getSnapshot(runId))
                    .thenReturn(snapshotWithEmptyEpoch(triggerId, epoch));
            when(mockCacheManager.loadTreeAndExecution(runId)).thenReturn(null);

            assertThrows(IllegalStateException.class,
                    () -> service.executeNode(runId, nodeId, "0", epoch, triggerId, null));
        }
    }

    @Nested
    @DisplayName("isInitialized")
    class IsInitialized {

        @Test
        @DisplayName("should delegate to contextManager.hasTree")
        void shouldDelegate() {
            when(mockContextManager.hasTree("run-1")).thenReturn(true);
            assertTrue(service.isInitialized("run-1"));

            when(mockContextManager.hasTree("run-2")).thenReturn(false);
            assertFalse(service.isInitialized("run-2"));
        }
    }

    @Nested
    @DisplayName("cleanup")
    class Cleanup {

        @Test
        @DisplayName("should delegate to all sub-services")
        void shouldDelegateToAllServices() {
            service.cleanup("run-1");

            verify(mockContextManager).cleanup("run-1");
            verify(mockScheduler).cleanup("run-1");
            verify(mockEventService).cleanupExecution("run-1");
        }
    }

    @Nested
    @DisplayName("SplitExecutionResult")
    class SplitExecutionResultTest {

        @Test
        @DisplayName("should store all fields correctly")
        void shouldStoreAllFields() {
            V2StepByStepService.SplitExecutionResult result =
                new V2StepByStepService.SplitExecutionResult(
                    true, 3, Set.of("mcp:next"), false
                );

            assertTrue(result.allSuccess());
            assertEquals(3, result.itemsExecuted());
            assertEquals(Set.of("mcp:next"), result.readyNodes());
            assertFalse(result.anyWorkflowComplete());
        }

        @Test
        @DisplayName("should represent failed execution")
        void shouldRepresentFailedExecution() {
            V2StepByStepService.SplitExecutionResult result =
                new V2StepByStepService.SplitExecutionResult(
                    false, 2, Set.of(), true
                );

            assertFalse(result.allSuccess());
            assertEquals(2, result.itemsExecuted());
            assertTrue(result.readyNodes().isEmpty());
            assertTrue(result.anyWorkflowComplete());
        }
    }

    // =========================================================================
    // NEW TESTS: ExecuteNodeInternal Full Flow
    // =========================================================================

    @Nested
    @DisplayName("executeNodeInternal - full flow")
    class ExecuteNodeInternalFullFlow {

        @Test
        @DisplayName("should execute regular node and return merged ready nodes")
        void shouldExecuteRegularNodeAndReturnReadyNodes() {
            NodeExecutionResult nodeResult = NodeExecutionResult.success("mcp:step1", Map.of("data", "value"));
            setupExecuteNodeMocks("mcp:step1", "0", nodeResult, Set.of("mcp:step2"), Set.of());

            StepByStepExecutionResult result = service.executeNode("run-1", "mcp:step1", "0");

            assertTrue(result.isSuccess());
            assertTrue(result.readyNodes().contains("mcp:step2"));
            assertFalse(result.workflowComplete());

            verify(mockEngine).executeSingleNode(
                eq("mcp:step1"), eq(mockTree), eq(mockContext),
                eq(mockExecution), eq(mockEventService), any());
            verify(mockEventService).emitStepByStepReady(
                eq(mockExecution), anySet(), eq("mcp:step1"), eq(false));
        }

        @Test
        @DisplayName("should merge ready nodes from engine and snapshot (multi-DAG)")
        void shouldMergeReadyNodesFromEngineAndSnapshot() {
            NodeExecutionResult nodeResult = NodeExecutionResult.success("mcp:step1", Map.of());
            // Engine returns step2, snapshot returns step3 (from a different DAG)
            setupExecuteNodeMocks("mcp:step1", "0", nodeResult,
                Set.of("mcp:step2"), Set.of("mcp:step3"));

            StepByStepExecutionResult result = service.executeNode("run-1", "mcp:step1", "0");

            assertTrue(result.readyNodes().contains("mcp:step2"));
            assertTrue(result.readyNodes().contains("mcp:step3"));
            assertEquals(2, result.readyNodes().size());
            assertFalse(result.workflowComplete());
        }

        @Test
        @DisplayName("should handle split context nodes (mcp:step@0.1)")
        void shouldHandleSplitContextNodes() {
            NodeExecutionResult nodeResult = NodeExecutionResult.success("mcp:step1", Map.of());
            // Engine returns a split context node "mcp:next@0.1"
            setupExecuteNodeMocks("mcp:step1", "0", nodeResult,
                Set.of("mcp:next@0.1"), Set.of());

            StepByStepExecutionResult result = service.executeNode("run-1", "mcp:step1", "0");

            // Split context should mark as pending: markAsPending(runId, itemId, baseNode)
            verify(mockScheduler).markAsPending("run-1", "0.1", "mcp:next");
            // Base node "mcp:next" should be in ready set (not the @-qualified name)
            assertTrue(result.readyNodes().contains("mcp:next"));
        }

        @Test
        @DisplayName("should set workflow NOT complete when awaiting signal")
        void shouldSetWorkflowNotCompleteWhenAwaitingSignal() {
            NodeExecutionResult nodeResult = NodeExecutionResult.awaitingSignal(
                "mcp:step1", SignalType.USER_APPROVAL, Map.of());
            // Engine returns empty ready nodes + snapshot also empty
            setupExecuteNodeMocks("mcp:step1", "0", nodeResult, Set.of(), Set.of());

            StepByStepExecutionResult result = service.executeNode("run-1", "mcp:step1", "0");

            // Despite empty ready nodes, workflow should NOT be marked complete
            assertFalse(result.workflowComplete());
        }

        @Test
        @DisplayName("should use explicit epoch when provided via 4-arg executeNode")
        void shouldUseExplicitEpochWhenProvided() {
            when(mockCacheManager.loadTreeAndExecution("run-1")).thenReturn(validLoadedExecution());

            // For explicit epoch, the 7-arg getOrCreateContextWithTriggerData is used (triggerId=null)
            when(mockContextManager.getOrCreateContextWithTriggerData(
                eq("run-1:0"), eq(mockTree), eq("0"), eq(0), eq("mcp:step1"), eq(5), isNull()
            )).thenReturn(mockContext);
            lenient().when(mockContext.triggerData()).thenReturn(Map.of());
            lenient().when(mockContext.triggerId()).thenReturn("trigger:start");
            lenient().when(mockContext.epoch()).thenReturn(5);
            lenient().when(mockContext.getGlobalDataKeys()).thenReturn(Set.of());

            NodeExecutionResult nodeResult = NodeExecutionResult.success("mcp:step1", Map.of());
            StepByStepExecutionResult engineResult = new StepByStepExecutionResult(
                mockContext, nodeResult, Set.of("mcp:step2"), false);
            when(mockEngine.executeSingleNode(
                eq("mcp:step1"), eq(mockTree), eq(mockContext),
                eq(mockExecution), eq(mockEventService), any()
            )).thenReturn(engineResult);
            when(mockStateSnapshotService.getReadyNodeIds("run-1")).thenReturn(Set.of());

            StepByStepExecutionResult result = service.executeNode("run-1", "mcp:step1", "0", 5);

            assertTrue(result.isSuccess());
            // Verify the 7-arg overload was used (triggerId=null since 4-arg executeNode has no triggerId)
            verify(mockContextManager).getOrCreateContextWithTriggerData(
                anyString(), eq(mockTree), eq("0"), eq(0), eq("mcp:step1"), eq(5), isNull());
        }

        @Test
        @DisplayName("should use implicit epoch when called via 3-arg executeNode")
        void shouldUseImplicitEpochWhenMinusOne() {
            NodeExecutionResult nodeResult = NodeExecutionResult.success("mcp:step1", Map.of());
            setupExecuteNodeMocks("mcp:step1", "0", nodeResult, Set.of("mcp:step2"), Set.of());

            StepByStepExecutionResult result = service.executeNode("run-1", "mcp:step1", "0");

            assertTrue(result.isSuccess());
            // Verify the 5-arg overload was used (no explicit epoch)
            verify(mockContextManager).getOrCreateContextWithTriggerData(
                anyString(), eq(mockTree), eq("0"), eq(0), eq("mcp:step1"));
        }

        @Test
        @DisplayName("should cache global data from result context when keys non-empty")
        void shouldCacheGlobalDataFromResultContext() {
            when(mockCacheManager.loadTreeAndExecution("run-1")).thenReturn(validLoadedExecution());
            when(mockContextManager.getOrCreateContextWithTriggerData(
                eq("run-1:0"), eq(mockTree), eq("0"), eq(0), eq("mcp:step1")
            )).thenReturn(mockContext);
            lenient().when(mockContext.triggerData()).thenReturn(Map.of());
            lenient().when(mockContext.triggerId()).thenReturn("trigger:start");
            lenient().when(mockContext.epoch()).thenReturn(0);

            // Result context has global data
            ExecutionContext resultCtx = mock(ExecutionContext.class);
            when(resultCtx.getGlobalDataKeys()).thenReturn(Set.of("loopState"));
            when(resultCtx.getGlobalData("loopState")).thenReturn(Optional.of(Map.of("iteration", 3)));

            NodeExecutionResult nodeResult = NodeExecutionResult.success("mcp:step1", Map.of());
            StepByStepExecutionResult engineResult = new StepByStepExecutionResult(
                resultCtx, nodeResult, Set.of("mcp:step2"), false);
            when(mockEngine.executeSingleNode(
                eq("mcp:step1"), eq(mockTree), eq(mockContext),
                eq(mockExecution), eq(mockEventService), any()
            )).thenReturn(engineResult);
            when(mockStateSnapshotService.getReadyNodeIds("run-1")).thenReturn(Set.of());

            service.executeNode("run-1", "mcp:step1", "0");

            // Verify global data was cached (no explicit epoch → 2-arg updateGlobalData)
            verify(mockContextManager).updateGlobalData(eq("run-1"), anyMap());
        }
    }

    // =========================================================================
    // NEW TESTS: Workflow Completion
    // =========================================================================

    @Nested
    @DisplayName("executeNodeInternal - workflow completion")
    class ExecuteNodeInternalWorkflowCompletion {

        @Test
        @DisplayName("should persist COMPLETED status for normal workflow")
        void shouldPersistCompletedForNormalWorkflow() {
            NodeExecutionResult nodeResult = NodeExecutionResult.success("mcp:step1", Map.of());
            // Both engine and snapshot return empty → workflow complete
            setupExecuteNodeMocks("mcp:step1", "0", nodeResult, Set.of(), Set.of());

            // No reusable trigger → normal workflow path
            // mockTree.plan() returns null by default → getReusableTriggerId returns null

            WorkflowRunEntity runEntity = createRunEntity(RunStatus.RUNNING);
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(runEntity));

            service.executeNode("run-1", "mcp:step1", "0");

            assertEquals(RunStatus.COMPLETED, runEntity.getStatus());
            assertNotNull(runEntity.getEndedAt());
            verify(mockRunRepository).save(runEntity);
            verify(mockEventService).emitWorkflowComplete(mockExecution, true, "Workflow completed");
        }

        @Test
        @DisplayName("should persist FAILED status for failed node in normal workflow")
        void shouldPersistFailedForNormalWorkflowFailure() {
            NodeExecutionResult nodeResult = NodeExecutionResult.failure("mcp:step1", "Connection timeout");
            setupExecuteNodeMocks("mcp:step1", "0", nodeResult, Set.of(), Set.of());

            WorkflowRunEntity runEntity = createRunEntity(RunStatus.RUNNING);
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(runEntity));

            service.executeNode("run-1", "mcp:step1", "0");

            assertEquals(RunStatus.FAILED, runEntity.getStatus());
            verify(mockRunRepository).save(runEntity);
        }

        @Test
        @DisplayName("should re-add trigger to readyNodes and stay RUNNING for SBS reusable trigger")
        void shouldPersistCompletedForSbsReusableTrigger() {
            NodeExecutionResult nodeResult = NodeExecutionResult.success("mcp:step1", Map.of());
            setupExecuteNodeMocks("mcp:step1", "0", nodeResult, Set.of(), Set.of());

            // Set up reusable trigger detection
            // plan/triggers/isReusableTrigger are lenient: only reached when actualWorkflowComplete=true
            Trigger trigger = new Trigger("t1", "start", "single", "webhook");
            lenient().when(mockTree.plan()).thenReturn(mockPlan);
            lenient().when(mockPlan.getTriggers()).thenReturn(List.of(trigger));
            lenient().when(mockTriggerLoadingService.isReusableTrigger(mockPlan, "trigger:start")).thenReturn(true);
            when(mockTree.executionMode()).thenReturn(ExecutionMode.STEP_BY_STEP);

            WorkflowRunEntity runEntity = createRunEntity(RunStatus.RUNNING);

            service.executeNode("run-1", "mcp:step1", "0");

            // SBS mode: when no successor nodes remain, the service re-adds the trigger ("trigger:start")
            // to readyNodes so the user can re-fire it. This means actualWorkflowComplete=false,
            // so the run status is never updated and emitWorkflowComplete is never called.
            assertEquals(RunStatus.RUNNING, runEntity.getStatus());
            verify(mockRunRepository, never()).save(any());
            verify(mockEventService, never()).emitWorkflowComplete(any(), anyBoolean(), anyString());
            // emitStepByStepReady is called with the re-added trigger as a ready node
            verify(mockEventService).emitStepByStepReady(eq(mockExecution), eq(Set.of("trigger:start")), eq("mcp:step1"), eq(false));
        }

        @Test
        @DisplayName("should skip epoch management for AUTO reusable trigger")
        void shouldSkipEpochManagementForAutoReusableTrigger() {
            NodeExecutionResult nodeResult = NodeExecutionResult.success("mcp:step1", Map.of());
            setupExecuteNodeMocks("mcp:step1", "0", nodeResult, Set.of(), Set.of());

            // Reusable trigger with AUTO mode
            Trigger trigger = new Trigger("t1", "start", "single", "webhook");
            when(mockTree.plan()).thenReturn(mockPlan);
            when(mockPlan.getTriggers()).thenReturn(List.of(trigger));
            when(mockTriggerLoadingService.isReusableTrigger(mockPlan, "trigger:start")).thenReturn(true);
            when(mockTree.executionMode()).thenReturn(ExecutionMode.AUTOMATIC);

            service.executeNode("run-1", "mcp:step1", "0");

            // AUTO + reusable: only emit complete, NO runRepository.save
            verify(mockRunRepository, never()).save(any());
            verify(mockEventService).emitWorkflowComplete(mockExecution, true, "Workflow completed");
        }

        @Test
        @DisplayName("should mark epoch ended on completion for normal workflow")
        void shouldMarkEpochEndedOnCompletion() {
            NodeExecutionResult nodeResult = NodeExecutionResult.success("mcp:step1", Map.of());
            setupExecuteNodeMocks("mcp:step1", "0", nodeResult, Set.of(), Set.of());

            WorkflowRunEntity runEntity = createRunEntity(RunStatus.RUNNING);
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(runEntity));

            service.executeNode("run-1", "mcp:step1", "0");
        }
    }

    // =========================================================================
    // NEW TESTS: Handle Trigger Node
    // =========================================================================

    @Nested
    @DisplayName("handleTriggerNode")
    class HandleTriggerNodeTest {

        /**
         * Sets up mocks for trigger node execution via executeNode("run-1", triggerNodeId, "0").
         * After handleTriggerNode falls through (returns null), the normal execution continues.
         */
        private void setupTriggerFallthroughMocks(String triggerNodeId) {
            when(mockCacheManager.loadTreeAndExecution("run-1")).thenReturn(validLoadedExecution());

            // Context for normal execution after handleTriggerNode falls through
            when(mockContextManager.getOrCreateContextWithTriggerData(
                eq("run-1:0"), eq(mockTree), eq("0"), eq(0), eq(triggerNodeId)
            )).thenReturn(mockContext);
            lenient().when(mockContext.triggerData()).thenReturn(Map.of());
            lenient().when(mockContext.triggerId()).thenReturn(triggerNodeId);
            lenient().when(mockContext.epoch()).thenReturn(0);
            lenient().when(mockContext.getGlobalDataKeys()).thenReturn(Set.of());

            NodeExecutionResult nodeResult = NodeExecutionResult.success(triggerNodeId, Map.of());
            StepByStepExecutionResult engineResult = new StepByStepExecutionResult(
                mockContext, nodeResult, Set.of("mcp:step1"), false);
            when(mockEngine.executeSingleNode(
                eq(triggerNodeId), eq(mockTree), eq(mockContext),
                eq(mockExecution), eq(mockEventService), any()
            )).thenReturn(engineResult);
            lenient().when(mockStateSnapshotService.getReadyNodeIds("run-1")).thenReturn(Set.of());
        }

        @Test
        @DisplayName("should execute immediately for SBS reusable trigger")
        void shouldExecuteImmediatelyForSbsReusableTrigger() {
            setupTriggerFallthroughMocks("trigger:webhook");

            when(mockTriggerLoadingService.isReusableTrigger(any(), eq("trigger:webhook"))).thenReturn(true);
            when(mockTriggerLoadingService.getTriggerType(any(), eq("trigger:webhook"))).thenReturn("webhook");
            when(mockTree.executionMode()).thenReturn(ExecutionMode.STEP_BY_STEP);

            StepByStepExecutionResult result = service.executeNode("run-1", "trigger:webhook", "0");

            // SBS mode: no WAITING_TRIGGER, falls through to normal execution
            assertTrue(result.isSuccess());
            verify(mockTriggerLoadingService).loadTriggerItemsIfNeeded(
                eq("run-1"), eq(mockTree), eq(0), eq("trigger:webhook"), eq(mockExecution));
            verify(mockRunRepository, never()).save(any()); // No status update to WAITING_TRIGGER
        }

        @Test
        @DisplayName("should set WAITING_TRIGGER for AUTO mode when trigger not received")
        void shouldSetWaitingTriggerForAutoModeNotReceived() {
            when(mockCacheManager.loadTreeAndExecution("run-1")).thenReturn(validLoadedExecution());

            when(mockTriggerLoadingService.isReusableTrigger(any(), eq("trigger:webhook"))).thenReturn(true);
            when(mockTriggerLoadingService.getTriggerType(any(), eq("trigger:webhook"))).thenReturn("webhook");
            when(mockTree.executionMode()).thenReturn(ExecutionMode.AUTOMATIC);

            // Run entity has PENDING status → trigger not yet received
            WorkflowRunEntity runEntity = createRunEntity(RunStatus.PENDING);
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(runEntity));

            // Context for the waitingForTrigger result
            when(mockContextManager.getOrCreateContextWithTriggerData(
                eq("run-1:0"), eq(mockTree), eq("0"), eq(0), eq("trigger:webhook")
            )).thenReturn(mockContext);

            StepByStepExecutionResult result = service.executeNode("run-1", "trigger:webhook", "0");

            assertTrue(result.isWaitingForTrigger());
            assertEquals(RunStatus.WAITING_TRIGGER, runEntity.getStatus());
            verify(mockRunRepository).save(runEntity);
        }

        @Test
        @DisplayName("should continue execution for AUTO mode when trigger already received")
        void shouldContinueForAutoModeTriggerAlreadyReceived() {
            setupTriggerFallthroughMocks("trigger:webhook");

            when(mockTriggerLoadingService.isReusableTrigger(any(), eq("trigger:webhook"))).thenReturn(true);
            when(mockTriggerLoadingService.getTriggerType(any(), eq("trigger:webhook"))).thenReturn("webhook");
            when(mockTree.executionMode()).thenReturn(ExecutionMode.AUTOMATIC);

            // Run entity has RUNNING status → trigger already received
            WorkflowRunEntity runEntity = createRunEntity(RunStatus.RUNNING);
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(runEntity));

            StepByStepExecutionResult result = service.executeNode("run-1", "trigger:webhook", "0");

            assertTrue(result.isSuccess());
            verify(mockTriggerLoadingService).loadTriggerItemsIfNeeded(
                eq("run-1"), eq(mockTree), eq(0), eq("trigger:webhook"), eq(mockExecution));
        }

        @Test
        @DisplayName("should load data immediately for datasource trigger")
        void shouldLoadImmediatelyForDatasourceTrigger() {
            setupTriggerFallthroughMocks("trigger:my_datasource");

            when(mockTriggerLoadingService.isReusableTrigger(any(), eq("trigger:my_datasource"))).thenReturn(true);
            when(mockTriggerLoadingService.getTriggerType(any(), eq("trigger:my_datasource"))).thenReturn("datasource");

            StepByStepExecutionResult result = service.executeNode("run-1", "trigger:my_datasource", "0");

            // Datasource triggers bypass waiting - load immediately
            assertTrue(result.isSuccess());
            verify(mockTriggerLoadingService).loadTriggerItemsIfNeeded(
                eq("run-1"), eq(mockTree), eq(0), eq("trigger:my_datasource"), eq(mockExecution));
        }

        @Test
        @DisplayName("should load trigger items for non-reusable trigger")
        void shouldLoadTriggerItemsForNonReusableTrigger() {
            setupTriggerFallthroughMocks("trigger:schedule");

            when(mockTriggerLoadingService.isReusableTrigger(any(), eq("trigger:schedule"))).thenReturn(false);

            StepByStepExecutionResult result = service.executeNode("run-1", "trigger:schedule", "0");

            assertTrue(result.isSuccess());
            verify(mockTriggerLoadingService).loadTriggerItemsIfNeeded(
                eq("run-1"), eq(mockTree), eq(0), eq("trigger:schedule"), eq(mockExecution));
        }
    }

    // =========================================================================
    // NEW TESTS: getReadyNodes with explicit epoch
    // =========================================================================

    @Nested
    @DisplayName("getReadyNodes with epoch")
    class GetReadyNodesWithEpoch {

        @Test
        @DisplayName("should pass explicit epoch to context creation")
        void shouldPassExplicitEpochToContext() {
            ExecutionNode mockRootNode = mock(ExecutionNode.class);
            when(mockRootNode.getNodeId()).thenReturn("trigger:start");

            when(mockContextManager.getTree("run-1")).thenReturn(mockTree);
            when(mockTree.getRootNodes()).thenReturn(List.of(mockRootNode));
            when(mockContextManager.getOrCreateContextWithTriggerData(
                eq("run-1:0:dag:trigger:start:epoch:3"), eq(mockTree), eq("0"), eq(0), eq("trigger:start"), eq(3)
            )).thenReturn(mockContext);
            when(mockEngine.calculateReadyNodes(mockContext, mockTree)).thenReturn(Set.of("mcp:step1"));

            Set<String> result = service.getReadyNodes("run-1", "0", 3);

            assertEquals(Set.of("mcp:step1"), result);
            // Verify the 6-arg overload was used with epoch=3
            verify(mockContextManager).getOrCreateContextWithTriggerData(
                contains(":epoch:3"), eq(mockTree), eq("0"), eq(0), eq("trigger:start"), eq(3));
        }

        @Test
        @DisplayName("should iterate all root nodes for multi-DAG ready calculation")
        void shouldIterateAllRootNodesForMultiDag() {
            ExecutionNode root1 = mock(ExecutionNode.class);
            ExecutionNode root2 = mock(ExecutionNode.class);
            when(root1.getNodeId()).thenReturn("trigger:webhook_a");
            when(root2.getNodeId()).thenReturn("trigger:webhook_b");

            ExecutionContext ctx1 = mock(ExecutionContext.class);
            ExecutionContext ctx2 = mock(ExecutionContext.class);

            when(mockContextManager.getTree("run-1")).thenReturn(mockTree);
            when(mockTree.getRootNodes()).thenReturn(List.of(root1, root2));

            when(mockContextManager.getOrCreateContextWithTriggerData(
                contains("dag:trigger:webhook_a"), eq(mockTree), eq("0"), eq(0), eq("trigger:webhook_a")
            )).thenReturn(ctx1);
            when(mockContextManager.getOrCreateContextWithTriggerData(
                contains("dag:trigger:webhook_b"), eq(mockTree), eq("0"), eq(0), eq("trigger:webhook_b")
            )).thenReturn(ctx2);

            when(mockEngine.calculateReadyNodes(ctx1, mockTree)).thenReturn(Set.of("mcp:dag1_step"));
            when(mockEngine.calculateReadyNodes(ctx2, mockTree)).thenReturn(Set.of("mcp:dag2_step"));

            Set<String> result = service.getReadyNodes("run-1", "0");

            assertEquals(2, result.size());
            assertTrue(result.contains("mcp:dag1_step"));
            assertTrue(result.contains("mcp:dag2_step"));
        }

        @Test
        @DisplayName("should return empty when tree is null for epoch variant")
        void shouldReturnEmptyForEpochVariantWhenTreeNull() {
            when(mockContextManager.getTree("run-1")).thenReturn(null);

            Set<String> result = service.getReadyNodes("run-1", "0", 5);

            assertTrue(result.isEmpty());
        }
    }

    // =========================================================================
    // NEW TESTS: executeSplitItems
    // =========================================================================

    @Nested
    @DisplayName("executeSplitItems")
    class ExecuteSplitItemsTest {

        private StepByStepExecutionResult makeResult(boolean success, Set<String> readyNodes, boolean complete) {
            NodeExecutionResult nr = success
                ? NodeExecutionResult.success("mcp:step1", Map.of())
                : NodeExecutionResult.failure("mcp:step1", "Error");
            return new StepByStepExecutionResult(mockContext, nr, readyNodes, complete);
        }

        private void setupSplitItemMocks(String itemId, StepByStepExecutionResult result) {
            int itemIndex = Integer.parseInt(itemId.split("\\.")[itemId.split("\\.").length - 1]);

            when(mockCacheManager.loadTreeAndExecution("run-1")).thenReturn(validLoadedExecution());
            when(mockContextManager.getOrCreateContextWithTriggerData(
                eq("run-1:" + itemId), eq(mockTree), eq(itemId), eq(itemIndex), eq("mcp:step1")
            )).thenReturn(mockContext);
            lenient().when(mockContext.triggerData()).thenReturn(Map.of());
            lenient().when(mockContext.triggerId()).thenReturn("trigger:start");
            lenient().when(mockContext.epoch()).thenReturn(0);
            lenient().when(mockContext.getGlobalDataKeys()).thenReturn(Set.of());

            when(mockEngine.executeSingleNode(
                eq("mcp:step1"), eq(mockTree), eq(mockContext),
                eq(mockExecution), eq(mockEventService), any()
            )).thenReturn(result);
            lenient().when(mockStateSnapshotService.getReadyNodeIds("run-1")).thenReturn(Set.of());
        }

        @Test
        @DisplayName("should execute all items and collect results")
        void shouldExecuteAllItemsAndCollectResults() {
            NodeExecutionResult nr = NodeExecutionResult.success("mcp:step1", Map.of());
            StepByStepExecutionResult itemResult = new StepByStepExecutionResult(
                mockContext, nr, Set.of("mcp:next"), false);

            // Setup mocks for both items - use any() matchers since both go through the same path
            when(mockCacheManager.loadTreeAndExecution("run-1")).thenReturn(validLoadedExecution());
            when(mockContextManager.getOrCreateContextWithTriggerData(
                anyString(), eq(mockTree), anyString(), anyInt(), eq("mcp:step1")
            )).thenReturn(mockContext);
            lenient().when(mockContext.triggerData()).thenReturn(Map.of());
            lenient().when(mockContext.triggerId()).thenReturn("trigger:start");
            lenient().when(mockContext.epoch()).thenReturn(0);
            lenient().when(mockContext.getGlobalDataKeys()).thenReturn(Set.of());
            when(mockEngine.executeSingleNode(
                eq("mcp:step1"), eq(mockTree), eq(mockContext),
                eq(mockExecution), eq(mockEventService), any()
            )).thenReturn(itemResult);
            when(mockStateSnapshotService.getReadyNodeIds("run-1")).thenReturn(Set.of());

            Set<String> pendingItems = new LinkedHashSet<>(List.of("0.0", "0.1", "0.2"));
            V2StepByStepService.SplitExecutionResult result =
                service.executeSplitItems("run-1", "mcp:step1", pendingItems);

            assertEquals(3, result.itemsExecuted());
            assertTrue(result.allSuccess());
            assertTrue(result.readyNodes().contains("mcp:next"));
            // removePending called twice per item: once from executeSplitItems, once from executeNode (itemId contains ".")
            verify(mockScheduler, times(2)).removePending("run-1", "0.0", "mcp:step1");
            verify(mockScheduler, times(2)).removePending("run-1", "0.1", "mcp:step1");
            verify(mockScheduler, times(2)).removePending("run-1", "0.2", "mcp:step1");
        }

        @Test
        @DisplayName("should track allSuccess as false when any item fails")
        void shouldTrackAllSuccessCorrectly() {
            // First call succeeds, second fails
            NodeExecutionResult successNr = NodeExecutionResult.success("mcp:step1", Map.of());
            NodeExecutionResult failNr = NodeExecutionResult.failure("mcp:step1", "Timeout");
            StepByStepExecutionResult successResult = new StepByStepExecutionResult(
                mockContext, successNr, Set.of(), false);
            StepByStepExecutionResult failResult = new StepByStepExecutionResult(
                mockContext, failNr, Set.of(), false);

            when(mockCacheManager.loadTreeAndExecution("run-1")).thenReturn(validLoadedExecution());
            when(mockContextManager.getOrCreateContextWithTriggerData(
                anyString(), eq(mockTree), anyString(), anyInt(), eq("mcp:step1")
            )).thenReturn(mockContext);
            lenient().when(mockContext.triggerData()).thenReturn(Map.of());
            lenient().when(mockContext.triggerId()).thenReturn("trigger:start");
            lenient().when(mockContext.epoch()).thenReturn(0);
            lenient().when(mockContext.getGlobalDataKeys()).thenReturn(Set.of());
            when(mockEngine.executeSingleNode(
                eq("mcp:step1"), eq(mockTree), eq(mockContext),
                eq(mockExecution), eq(mockEventService), any()
            )).thenReturn(successResult).thenReturn(failResult);
            when(mockStateSnapshotService.getReadyNodeIds("run-1")).thenReturn(Set.of());

            Set<String> pendingItems = new LinkedHashSet<>(List.of("0.0", "0.1"));
            V2StepByStepService.SplitExecutionResult result =
                service.executeSplitItems("run-1", "mcp:step1", pendingItems);

            assertEquals(2, result.itemsExecuted());
            assertFalse(result.allSuccess());
        }

        @Test
        @DisplayName("should track anyWorkflowComplete when last item completes workflow")
        void shouldTrackAnyWorkflowComplete() {
            NodeExecutionResult nr = NodeExecutionResult.success("mcp:step1", Map.of());
            StepByStepExecutionResult completeResult = new StepByStepExecutionResult(
                mockContext, nr, Set.of(), true);

            when(mockCacheManager.loadTreeAndExecution("run-1")).thenReturn(validLoadedExecution());
            when(mockContextManager.getOrCreateContextWithTriggerData(
                anyString(), eq(mockTree), anyString(), anyInt(), eq("mcp:step1")
            )).thenReturn(mockContext);
            lenient().when(mockContext.triggerData()).thenReturn(Map.of());
            lenient().when(mockContext.triggerId()).thenReturn("trigger:start");
            lenient().when(mockContext.epoch()).thenReturn(0);
            lenient().when(mockContext.getGlobalDataKeys()).thenReturn(Set.of());
            when(mockEngine.executeSingleNode(
                eq("mcp:step1"), eq(mockTree), eq(mockContext),
                eq(mockExecution), eq(mockEventService), any()
            )).thenReturn(completeResult);
            when(mockStateSnapshotService.getReadyNodeIds("run-1")).thenReturn(Set.of());

            Set<String> pendingItems = new LinkedHashSet<>(List.of("0.0"));
            V2StepByStepService.SplitExecutionResult result =
                service.executeSplitItems("run-1", "mcp:step1", pendingItems);

            assertTrue(result.anyWorkflowComplete());
        }
    }

    // =========================================================================
    // NEW TESTS: initializeStepByStep with triggerId
    // =========================================================================

    @Nested
    @DisplayName("initializeStepByStep with triggerId")
    class InitializeStepByStepWithTriggerId {

        private static final UUID WORKFLOW_RUN_ID = UUID.fromString("00000000-0000-0000-0000-00000000002a");

        @Test
        @DisplayName("should use DAG-scoped updateReadyNodes when triggerId provided")
        void shouldUseDagScopedUpdateWhenTriggerIdProvided() {
            when(mockExecution.getRunId()).thenReturn("run-1");
            when(mockExecution.getWorkflowRunId()).thenReturn(WORKFLOW_RUN_ID);
            when(mockPlan.getTenantId()).thenReturn("tenant-1");

            when(mockTreeBuilder.build(eq("run-1"), eq(WORKFLOW_RUN_ID.toString()), eq("tenant-1"), eq(mockPlan), isNull(), isNull()))
                .thenReturn(mockTree);
            when(mockTree.withExecutionMode(ExecutionMode.STEP_BY_STEP)).thenReturn(mockTree);
            when(mockEngine.getInitialReadyNodes(mockTree)).thenReturn(Set.of("trigger:webhook"));

            service.initializeStepByStep(mockExecution, mockPlan, "trigger:webhook");

            // DAG-scoped: 4-arg updateReadyNodes with triggerId and epoch=0
            verify(mockStateSnapshotService).updateReadyNodes("run-1", "trigger:webhook", 0,
                Set.of("trigger:webhook"));
            // Must NOT call the 2-arg flat version
            verify(mockStateSnapshotService, never()).updateReadyNodes(eq("run-1"), anySet());
        }

        @Test
        @DisplayName("should use flat updateReadyNodes when triggerId is null")
        void shouldUseFlatUpdateWhenTriggerIdNull() {
            when(mockExecution.getRunId()).thenReturn("run-1");
            when(mockExecution.getWorkflowRunId()).thenReturn(WORKFLOW_RUN_ID);
            when(mockPlan.getTenantId()).thenReturn("tenant-1");

            when(mockTreeBuilder.build(eq("run-1"), eq(WORKFLOW_RUN_ID.toString()), eq("tenant-1"), eq(mockPlan), isNull(), isNull()))
                .thenReturn(mockTree);
            when(mockTree.withExecutionMode(ExecutionMode.STEP_BY_STEP)).thenReturn(mockTree);
            when(mockEngine.getInitialReadyNodes(mockTree)).thenReturn(Set.of("trigger:start"));

            // 2-arg init (no triggerId) → delegates to 3-arg with null
            service.initializeStepByStep(mockExecution, mockPlan);

            // Flat: 2-arg updateReadyNodes
            verify(mockStateSnapshotService).updateReadyNodes("run-1", Set.of("trigger:start"));
            // Must NOT call the 4-arg DAG-scoped version
            verify(mockStateSnapshotService, never()).updateReadyNodes(eq("run-1"), anyString(), anyInt(), anySet());
        }
    }

    // =========================================================================
    // StopOnError - WorkflowStoppedException catch in executeNode
    // =========================================================================

    @Nested
    @DisplayName("StopOnError in step-by-step mode")
    class StopOnErrorStepByStep {

        @Test
        @DisplayName("should catch WorkflowStoppedException and return failure with empty ready nodes")
        void shouldCatchStoppedExceptionAndReturnFailure() {
            when(mockCacheManager.loadTreeAndExecution("run-1")).thenReturn(validLoadedExecution());

            when(mockContextManager.getOrCreateContextWithTriggerData(
                eq("run-1:0"), eq(mockTree), eq("0"), anyInt(), eq("core:halt")
            )).thenReturn(mockContext);
            lenient().when(mockContext.triggerData()).thenReturn(Map.of());
            lenient().when(mockContext.triggerId()).thenReturn("trigger:start");
            lenient().when(mockContext.epoch()).thenReturn(0);
            lenient().when(mockContext.getGlobalDataKeys()).thenReturn(Set.of());

            // Engine throws WorkflowStoppedException (like a real StopOnError node)
            NodeExecutionResult failResult = NodeExecutionResult.failureWithOutput(
                "core:halt", "Critical failure",
                Map.of("error_message", "Critical failure", "error_code", "ERR_001", "status", "failed"), 0L);
            var stoppedException = new com.apimarketplace.orchestrator.execution.v2.engine.WorkflowStoppedException(
                "core:halt", "Critical failure", "ERR_001", failResult);

            when(mockEngine.executeSingleNode(
                eq("core:halt"), eq(mockTree), eq(mockContext),
                eq(mockExecution), eq(mockEventService), any()
            )).thenThrow(stoppedException);

            StepByStepExecutionResult result = service.executeNode("run-1", "core:halt", "0");

            // Should return failure result, not throw
            assertNotNull(result);
            assertFalse(result.isSuccess());
            assertTrue(result.readyNodes().isEmpty());
            assertFalse(result.workflowComplete());
            assertEquals(failResult, result.nodeResult());
        }
    }
}
