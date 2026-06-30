package com.apimarketplace.orchestrator.trigger;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.*;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.execution.SignalType;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.StepByStepExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.scheduler.V2StepByStepScheduler;
import com.apimarketplace.orchestrator.execution.v2.services.V2StepByStepService;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowExecutionService;
import com.apimarketplace.orchestrator.services.WorkflowStreamingService;
import com.apimarketplace.orchestrator.services.credit.CreditBudgetService;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for parallel fork branch execution in ReusableTriggerService.
 *
 * Verifies that when multiple ready nodes exist (e.g., fork branches),
 * they are executed in parallel rather than sequentially.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReusableTriggerService - Parallel Fork Execution")
class ReusableTriggerServiceParallelExecutionTest {

    @Mock private WorkflowRunRepository runRepository;
    @Mock private TriggerEpochManager epochManager;
    @Mock private WorkflowStreamingService streamingService;
    @Mock private WorkflowExecutionService executionService;
    @Mock private com.apimarketplace.orchestrator.services.TriggerResolverService triggerResolverService;
    @Mock private StateSnapshotService stateSnapshotService;
    @Mock private EpochConcurrencyLimiter epochConcurrencyLimiter;
    @Mock private com.apimarketplace.orchestrator.trigger.queue.ExecutionQueue executionQueueService;
    @Mock private V2StepByStepService v2StepByStepService;
    @Mock private V2StepByStepScheduler v2StepByStepScheduler;
    @Mock private CreditConsumptionClient creditClient;
    @Mock private CreditBudgetService creditBudgetService;

    private ReusableTriggerService service;

    @BeforeEach
    void setUp() {
        service = new ReusableTriggerService(
                runRepository, mock(com.apimarketplace.orchestrator.repository.WorkflowRepository.class),
                mock(com.apimarketplace.orchestrator.repository.WorkflowPlanVersionRepository.class),
                epochManager, streamingService,
                executionService, triggerResolverService, stateSnapshotService,
                epochConcurrencyLimiter, executionQueueService, creditClient, creditBudgetService);

        // Inject @Autowired fields via reflection
        ReflectionTestUtils.setField(service, "v2StepByStepService", v2StepByStepService);
        ReflectionTestUtils.setField(service, "v2StepByStepScheduler", v2StepByStepScheduler);
        ReflectionTestUtils.setField(service, "parallelReadyExecutionEnabled", true);
        ReflectionTestUtils.setField(service, "self", service);
    }

    // ==================== Helper Methods ====================

    private WorkflowPlan buildPlan(List<Trigger> triggers) {
        return new WorkflowPlan(null, null, triggers, List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), Map.of());
    }

    private WorkflowPlan buildPlan(List<Trigger> triggers, List<Step> steps, List<Edge> edges) {
        return new WorkflowPlan(null, null, triggers, steps, List.of(),
                edges, List.of(), List.of(), List.of(), List.of(), Map.of());
    }

    private Step mcp(String label) {
        return new Step(label, "mcp", label, null, Map.of(), null, null, null);
    }

    private WorkflowExecution buildExecution(String runId, WorkflowPlan plan) {
        return new WorkflowExecution(runId, plan, Map.of());
    }

    private StepByStepExecutionResult successResult(String nodeId) {
        ExecutionContext ctx = ExecutionContext.create(
            "run", null, "tenant", "0", 0, "trigger:start", 0, 0,
            Map.of(), null);
        NodeExecutionResult nodeResult = NodeExecutionResult.success(nodeId, Map.of());
        return StepByStepExecutionResult.success(ctx, nodeResult, Set.of());
    }

    private StepByStepExecutionResult failureResult(String nodeId, String error) {
        ExecutionContext ctx = ExecutionContext.create(
            "run", null, "tenant", "0", 0, "trigger:start", 0, 0,
            Map.of(), null);
        NodeExecutionResult nodeResult = NodeExecutionResult.failure(nodeId, error);
        return new StepByStepExecutionResult(ctx, nodeResult, Set.of(), false);
    }

    private StepByStepExecutionResult awaitingSignalResult(String nodeId) {
        ExecutionContext ctx = ExecutionContext.create(
            "run", null, "tenant", "0", 0, "trigger:start", 0, 0,
            Map.of(), null);
        NodeExecutionResult nodeResult = NodeExecutionResult.awaitingSignal(
            nodeId, SignalType.INTERFACE_SIGNAL, Map.of());
        return new StepByStepExecutionResult(ctx, nodeResult, Set.of(), false);
    }

    private StepByStepExecutionResult asyncAgentYieldResult(String nodeId) {
        ExecutionContext ctx = ExecutionContext.create(
            "run", null, "tenant", "0", 0, "trigger:start", 0, 0,
            Map.of(), null);
        // Simulates AgentNode.java yielding an async agent-queue task.
        NodeExecutionResult nodeResult = NodeExecutionResult.awaitingSignal(
            nodeId, SignalType.AGENT_EXECUTION, Map.of("queue", "agent"));
        return new StepByStepExecutionResult(ctx, nodeResult, Set.of(), false);
    }

    /**
     * Invokes the private executeReadySteps method via reflection.
     */
    private boolean invokeExecuteReadySteps(String runId, Set<String> readySteps, int epoch, String triggerId) throws Exception {
        WorkflowPlan plan = buildPlan(List.of());
        WorkflowExecution execution = buildExecution(runId, plan);

        java.lang.reflect.Method method = ReusableTriggerService.class.getDeclaredMethod(
            "executeReadySteps", WorkflowExecution.class, Set.class, String.class, int.class, String.class);
        method.setAccessible(true);
        return (boolean) method.invoke(service, execution, readySteps, runId, epoch, triggerId);
    }

    private boolean invokeExecuteReadySteps(String runId, Set<String> readySteps, int epoch,
                                            String triggerId, WorkflowPlan plan) throws Exception {
        WorkflowExecution execution = buildExecution(runId, plan);

        java.lang.reflect.Method method = ReusableTriggerService.class.getDeclaredMethod(
            "executeReadySteps", WorkflowExecution.class, Set.class, String.class, int.class, String.class,
            WorkflowPlan.class);
        method.setAccessible(true);
        return (boolean) method.invoke(service, execution, readySteps, runId, epoch, triggerId, plan);
    }

    // ==================== Parallel Execution Tests ====================

    @Nested
    @DisplayName("Parallel fork branch execution")
    class ParallelForkTests {

        @Test
        @DisplayName("Should execute multiple ready nodes in parallel (fork branches)")
        void shouldExecuteForkBranchesInParallel() throws Exception {
            String runId = "run-parallel-1";
            Set<String> readySteps = Set.of("core:send_email", "core:user_approval", "core:sub_workflow");

            // Track thread names to verify parallel execution
            CopyOnWriteArrayList<String> threadNames = new CopyOnWriteArrayList<>();
            CountDownLatch allStarted = new CountDownLatch(3);

            // No pending split items
            when(v2StepByStepScheduler.getPendingItemIdsForNode(eq(runId), anyString()))
                .thenReturn(Set.of());

            // Each node takes some time and records its thread
            when(v2StepByStepService.executeNode(eq(runId), anyString(), eq("0"), eq(2), eq("trigger:start")))
                .thenAnswer(invocation -> {
                    String nodeId = invocation.getArgument(1);
                    threadNames.add(Thread.currentThread().getName());
                    allStarted.countDown();
                    // Small delay to ensure threads overlap
                    Thread.sleep(50);
                    return successResult(nodeId);
                });

            // No further ready nodes after first batch
            when(v2StepByStepService.getReadyNodes(runId, "0", 2, null)).thenReturn(Set.of());

            boolean hasFailures = invokeExecuteReadySteps(runId, readySteps, 2, "trigger:start");

            assertThat(hasFailures).isFalse();

            // Verify all 3 nodes were executed
            verify(v2StepByStepService, times(3)).executeNode(eq(runId), anyString(), eq("0"), eq(2), eq("trigger:start"));

            // Verify parallel execution: thread names should NOT all be the same
            // (they should be ForkJoinPool worker threads, not the test thread)
            assertThat(threadNames).hasSize(3);
            // At least one should be a worker thread (not the main/test thread)
            String testThread = Thread.currentThread().getName();
            assertThat(threadNames).anyMatch(name -> !name.equals(testThread));
        }

        @Test
        @DisplayName("Should execute single ready node sequentially (no parallel overhead)")
        void shouldExecuteSingleNodeSequentially() throws Exception {
            String runId = "run-single-1";
            Set<String> readySteps = Set.of("core:send_email");

            when(v2StepByStepScheduler.getPendingItemIdsForNode(eq(runId), anyString()))
                .thenReturn(Set.of());

            when(v2StepByStepService.executeNode(runId, "core:send_email", "0", 2, "trigger:start"))
                .thenReturn(successResult("core:send_email"));

            when(v2StepByStepService.getReadyNodes(runId, "0", 2, null)).thenReturn(Set.of());

            boolean hasFailures = invokeExecuteReadySteps(runId, readySteps, 2, "trigger:start");

            assertThat(hasFailures).isFalse();
            verify(v2StepByStepService, times(1)).executeNode(runId, "core:send_email", "0", 2, "trigger:start");
        }

        @Test
        @DisplayName("Should propagate failures from parallel branches")
        void shouldPropagateFailuresFromParallelBranches() throws Exception {
            String runId = "run-fail-1";
            Set<String> readySteps = Set.of("core:branch_a", "core:branch_b");

            when(v2StepByStepScheduler.getPendingItemIdsForNode(eq(runId), anyString()))
                .thenReturn(Set.of());

            when(v2StepByStepService.executeNode(runId, "core:branch_a", "0", 2, "trigger:start"))
                .thenReturn(successResult("core:branch_a"));
            when(v2StepByStepService.executeNode(runId, "core:branch_b", "0", 2, "trigger:start"))
                .thenReturn(failureResult("core:branch_b", "HTTP 500"));

            when(v2StepByStepService.getReadyNodes(runId, "0", 2, null)).thenReturn(Set.of());

            boolean hasFailures = invokeExecuteReadySteps(runId, readySteps, 2, "trigger:start");

            assertThat(hasFailures).isTrue();
            verify(v2StepByStepService, times(2)).executeNode(eq(runId), anyString(), eq("0"), eq(2), eq("trigger:start"));
        }

        @Test
        @DisplayName("Should stop after parallel batch when one branch awaits signal")
        void shouldStopWhenBranchAwaitsSignal() throws Exception {
            String runId = "run-signal-1";
            Set<String> readySteps = Set.of("core:branch_a", "interface:dashboard");

            when(v2StepByStepScheduler.getPendingItemIdsForNode(eq(runId), anyString()))
                .thenReturn(Set.of());

            when(v2StepByStepService.executeNode(runId, "core:branch_a", "0", 2, "trigger:start"))
                .thenReturn(successResult("core:branch_a"));
            when(v2StepByStepService.executeNode(runId, "interface:dashboard", "0", 2, "trigger:start"))
                .thenReturn(awaitingSignalResult("interface:dashboard"));

            // Should NOT be called - loop should stop after awaiting signal
            // (but getReadyNodes might still be called to check for next batch)

            boolean hasFailures = invokeExecuteReadySteps(runId, readySteps, 2, "trigger:start");

            assertThat(hasFailures).isFalse();
            // Both nodes should have executed (they ran in parallel)
            verify(v2StepByStepService, times(2)).executeNode(eq(runId), anyString(), eq("0"), eq(2), eq("trigger:start"));
        }

        @Test
        @DisplayName("Should handle exception in parallel branch without crashing other branches")
        void shouldHandleExceptionInParallelBranch() throws Exception {
            String runId = "run-exception-1";
            Set<String> readySteps = Set.of("core:branch_a", "core:branch_b", "core:branch_c");

            when(v2StepByStepScheduler.getPendingItemIdsForNode(eq(runId), anyString()))
                .thenReturn(Set.of());

            when(v2StepByStepService.executeNode(runId, "core:branch_a", "0", 2, "trigger:start"))
                .thenReturn(successResult("core:branch_a"));
            when(v2StepByStepService.executeNode(runId, "core:branch_b", "0", 2, "trigger:start"))
                .thenThrow(new RuntimeException("Simulated crash"));
            when(v2StepByStepService.executeNode(runId, "core:branch_c", "0", 2, "trigger:start"))
                .thenReturn(successResult("core:branch_c"));

            when(v2StepByStepService.getReadyNodes(runId, "0", 2, null)).thenReturn(Set.of());

            boolean hasFailures = invokeExecuteReadySteps(runId, readySteps, 2, "trigger:start");

            assertThat(hasFailures).isTrue();
            // All 3 should have been attempted
            verify(v2StepByStepService, times(3)).executeNode(eq(runId), anyString(), eq("0"), eq(2), eq("trigger:start"));
        }

        @Test
        @DisplayName("Should filter out trigger nodes from parallel execution")
        void shouldFilterOutTriggerNodes() throws Exception {
            String runId = "run-filter-1";
            // Mix of trigger and non-trigger ready steps
            Set<String> readySteps = Set.of("trigger:webhook", "core:branch_a", "core:branch_b");

            when(v2StepByStepScheduler.getPendingItemIdsForNode(eq(runId), anyString()))
                .thenReturn(Set.of());

            when(v2StepByStepService.executeNode(eq(runId), anyString(), eq("0"), eq(2), eq("trigger:start")))
                .thenAnswer(inv -> successResult(inv.getArgument(1)));

            when(v2StepByStepService.getReadyNodes(runId, "0", 2, null)).thenReturn(Set.of());

            boolean hasFailures = invokeExecuteReadySteps(runId, readySteps, 2, "trigger:start");

            assertThat(hasFailures).isFalse();
            // Only non-trigger nodes should be executed (2, not 3)
            verify(v2StepByStepService, times(2)).executeNode(eq(runId), anyString(), eq("0"), eq(2), eq("trigger:start"));
            // Specifically, trigger:webhook should NOT be executed
            verify(v2StepByStepService, never()).executeNode(runId, "trigger:webhook", "0", 2, "trigger:start");
        }

        @Test
        @DisplayName("Should execute split nodes sequentially then normal nodes in parallel")
        void shouldSeparateSplitFromNormalNodes() throws Exception {
            String runId = "run-mixed-1";
            Set<String> readySteps = Set.of("core:split_body", "core:branch_a", "core:branch_b");

            // split_body has pending items, others don't
            when(v2StepByStepScheduler.getPendingItemIdsForNode(runId, "core:split_body"))
                .thenReturn(Set.of("0.0", "0.1"));
            when(v2StepByStepScheduler.getPendingItemIdsForNode(runId, "core:branch_a"))
                .thenReturn(Set.of());
            when(v2StepByStepScheduler.getPendingItemIdsForNode(runId, "core:branch_b"))
                .thenReturn(Set.of());

            // Split execution
            when(v2StepByStepService.executeSplitItems(runId, "core:split_body", Set.of("0.0", "0.1")))
                .thenReturn(new V2StepByStepService.SplitExecutionResult(true, 2, Set.of(), false));

            // Normal branch execution
            when(v2StepByStepService.executeNode(eq(runId), anyString(), eq("0"), eq(2), eq("trigger:start")))
                .thenAnswer(inv -> successResult(inv.getArgument(1)));

            when(v2StepByStepService.getReadyNodes(runId, "0", 2, null)).thenReturn(Set.of());
            when(v2StepByStepScheduler.getPendingNodeIds(runId)).thenReturn(Set.of());

            boolean hasFailures = invokeExecuteReadySteps(runId, readySteps, 2, "trigger:start");

            assertThat(hasFailures).isFalse();
            // Split items executed
            verify(v2StepByStepService).executeSplitItems(runId, "core:split_body", Set.of("0.0", "0.1"));
            // Normal branches executed in parallel
            verify(v2StepByStepService, times(2)).executeNode(eq(runId), anyString(), eq("0"), eq(2), eq("trigger:start"));
        }

        @Test
        @DisplayName("Should continue to next wave after parallel batch completes")
        void shouldContinueToNextWaveAfterParallelBatch() throws Exception {
            String runId = "run-waves-1";
            Set<String> readySteps = Set.of("core:branch_a", "core:branch_b");

            when(v2StepByStepScheduler.getPendingItemIdsForNode(eq(runId), anyString()))
                .thenReturn(Set.of());

            // First wave: 2 parallel branches
            when(v2StepByStepService.executeNode(eq(runId), anyString(), eq("0"), eq(2), eq("trigger:start")))
                .thenAnswer(inv -> successResult(inv.getArgument(1)));

            // After first wave: merge node becomes ready
            when(v2StepByStepService.getReadyNodes(runId, "0", 2, null))
                .thenReturn(Set.of("core:merge"))
                .thenReturn(Set.of());

            boolean hasFailures = invokeExecuteReadySteps(runId, readySteps, 2, "trigger:start");

            assertThat(hasFailures).isFalse();
            // 2 parallel branches + 1 merge node = 3 total
            verify(v2StepByStepService, times(3)).executeNode(eq(runId), anyString(), eq("0"), eq(2), eq("trigger:start"));
        }

        @Test
        @DisplayName("Foreign ready nodes from a parallel trigger do not execute in the current epoch")
        void foreignReadyNodesFromParallelTriggerAreNotExecutedInCurrentEpoch() throws Exception {
            String runId = "run-multidag-foreign-initial";
            WorkflowPlan plan = buildPlan(
                List.of(
                    new Trigger("google_ads_start", "Google Ads Start", "single", "manual"),
                    new Trigger("sheets_start", "Sheets Start", "single", "manual"),
                    new Trigger("bigquery_start", "BigQuery Start", "single", "manual")
                ),
                List.of(
                    mcp("Search Stream Adwords"),
                    mcp("Get Values Spreadsheets Readonly"),
                    mcp("List Jobs Bigquery Readonly")
                ),
                List.of(
                    new Edge("trigger:google_ads_start", "mcp:search_stream_adwords"),
                    new Edge("trigger:sheets_start", "mcp:get_values_spreadsheets_readonly"),
                    new Edge("trigger:bigquery_start", "mcp:list_jobs_bigquery_readonly")
                )
            );
            Set<String> readySteps = new LinkedHashSet<>(List.of(
                "mcp:search_stream_adwords",
                "mcp:get_values_spreadsheets_readonly",
                "mcp:list_jobs_bigquery_readonly"
            ));

            when(v2StepByStepScheduler.getPendingItemIdsForNode(eq(runId), anyString()))
                .thenReturn(Set.of());
            when(v2StepByStepService.executeNode(eq(runId), anyString(), eq("0"), eq(5), eq("trigger:google_ads_start")))
                .thenAnswer(inv -> successResult(inv.getArgument(1)));
            when(v2StepByStepService.getReadyNodes(runId, "0", 5, null)).thenReturn(Set.of());

            boolean hasFailures = invokeExecuteReadySteps(
                runId, readySteps, 5, "trigger:google_ads_start", plan);

            assertThat(hasFailures).isFalse();
            verify(v2StepByStepService).executeNode(
                runId, "mcp:search_stream_adwords", "0", 5, "trigger:google_ads_start");
            verify(v2StepByStepService, never()).executeNode(
                runId, "mcp:get_values_spreadsheets_readonly", "0", 5, "trigger:google_ads_start");
            verify(v2StepByStepService, never()).executeNode(
                runId, "mcp:list_jobs_bigquery_readonly", "0", 5, "trigger:google_ads_start");
        }

        @Test
        @DisplayName("Foreign ready nodes from a later auto wave do not execute in the current epoch")
        void foreignReadyNodesFromLaterAutoWaveAreNotExecutedInCurrentEpoch() throws Exception {
            String runId = "run-multidag-foreign-next-wave";
            WorkflowPlan plan = buildPlan(
                List.of(
                    new Trigger("google_ads_start", "Google Ads Start", "single", "manual"),
                    new Trigger("sheets_start", "Sheets Start", "single", "manual")
                ),
                List.of(
                    mcp("Search Stream Adwords"),
                    mcp("Ads Followup"),
                    mcp("Get Values Spreadsheets Readonly")
                ),
                List.of(
                    new Edge("trigger:google_ads_start", "mcp:search_stream_adwords"),
                    new Edge("mcp:search_stream_adwords", "mcp:ads_followup"),
                    new Edge("trigger:sheets_start", "mcp:get_values_spreadsheets_readonly")
                )
            );
            Set<String> readySteps = Set.of("mcp:search_stream_adwords");

            when(v2StepByStepScheduler.getPendingItemIdsForNode(eq(runId), anyString()))
                .thenReturn(Set.of());
            when(v2StepByStepService.executeNode(eq(runId), anyString(), eq("0"), eq(6), eq("trigger:google_ads_start")))
                .thenAnswer(inv -> successResult(inv.getArgument(1)));
            when(v2StepByStepService.getReadyNodes(runId, "0", 6, null))
                .thenReturn(Set.of("mcp:ads_followup", "mcp:get_values_spreadsheets_readonly"))
                .thenReturn(Set.of());

            boolean hasFailures = invokeExecuteReadySteps(
                runId, readySteps, 6, "trigger:google_ads_start", plan);

            assertThat(hasFailures).isFalse();
            verify(v2StepByStepService).executeNode(
                runId, "mcp:search_stream_adwords", "0", 6, "trigger:google_ads_start");
            verify(v2StepByStepService).executeNode(
                runId, "mcp:ads_followup", "0", 6, "trigger:google_ads_start");
            verify(v2StepByStepService, never()).executeNode(
                runId, "mcp:get_values_spreadsheets_readonly", "0", 6, "trigger:google_ads_start");
        }

        @Test
        @DisplayName("Foreign pending split nodes from another DAG are not executed in the current epoch")
        void foreignPendingSplitNodesFromAnotherDagAreNotExecutedInCurrentEpoch() throws Exception {
            String runId = "run-multidag-foreign-pending-split";
            WorkflowPlan plan = buildPlan(
                List.of(
                    new Trigger("ads_start", "Ads Start", "single", "manual"),
                    new Trigger("sheets_start", "Sheets Start", "single", "manual")
                ),
                List.of(
                    mcp("Ads Seed"),
                    mcp("Ads Item"),
                    mcp("Sheets Seed"),
                    mcp("Sheets Item")
                ),
                List.of(
                    new Edge("trigger:ads_start", "mcp:ads_seed"),
                    new Edge("mcp:ads_seed", "core:ads_split"),
                    new Edge("core:ads_split", "mcp:ads_item"),
                    new Edge("trigger:sheets_start", "mcp:sheets_seed"),
                    new Edge("mcp:sheets_seed", "core:sheets_split"),
                    new Edge("core:sheets_split", "mcp:sheets_item")
                )
            );

            when(v2StepByStepScheduler.getPendingItemIdsForNode(eq(runId), anyString()))
                .thenReturn(Set.of());
            when(v2StepByStepScheduler.getPendingItemIdsForNode(runId, "mcp:ads_item"))
                .thenReturn(Set.of("0.0", "0.1", "0.2"));
            when(v2StepByStepService.executeNode(runId, "mcp:ads_seed", "0", 7, "trigger:ads_start"))
                .thenReturn(successResult("mcp:ads_seed"));
            when(v2StepByStepService.getReadyNodes(runId, "0", 7, null))
                .thenReturn(Set.of())
                .thenReturn(Set.of());
            when(v2StepByStepScheduler.getPendingNodeIds(runId))
                .thenReturn(new LinkedHashSet<>(List.of("mcp:ads_item", "mcp:sheets_item")))
                .thenReturn(Set.of());
            when(v2StepByStepService.executeSplitItems(runId, "mcp:ads_item", Set.of("0.0", "0.1", "0.2")))
                .thenReturn(new V2StepByStepService.SplitExecutionResult(true, 3, Set.of(), false));

            boolean hasFailures = invokeExecuteReadySteps(
                runId, Set.of("mcp:ads_seed"), 7, "trigger:ads_start", plan);

            assertThat(hasFailures).isFalse();
            verify(v2StepByStepService).executeNode(
                runId, "mcp:ads_seed", "0", 7, "trigger:ads_start");
            verify(v2StepByStepService).executeSplitItems(
                runId, "mcp:ads_item", Set.of("0.0", "0.1", "0.2"));
            verify(v2StepByStepService, never()).executeSplitItems(
                eq(runId), eq("mcp:sheets_item"), anySet());
            verify(v2StepByStepService, never()).executeNode(
                eq(runId), eq("mcp:sheets_item"), eq("0"), eq(7), eq("trigger:ads_start"));
        }

        @Test
        @DisplayName("Stress: noisy same-epoch multi-DAG ready waves only execute the fired trigger DAG")
        void noisySameEpochMultiDagReadyWavesOnlyExecuteTheFiredTriggerDag() throws Exception {
            String runId = "run-multidag-stress";
            List<Trigger> triggers = List.of(
                new Trigger("ads_start", "Ads Start", "single", "manual"),
                new Trigger("sheets_start", "Sheets Start", "single", "manual"),
                new Trigger("crm_start", "CRM Start", "single", "manual")
            );
            List<Step> steps = new ArrayList<>();
            List<Edge> edges = new ArrayList<>();
            steps.add(mcp("Ads Seed"));
            edges.add(new Edge("trigger:ads_start", "mcp:ads_seed"));
            for (int i = 0; i < 20; i++) {
                String adsNode = "mcp:ads_worker_" + i;
                String sheetsNode = "mcp:sheets_worker_" + i;
                String crmNode = "mcp:crm_worker_" + i;
                steps.add(mcp("Ads Worker " + i));
                steps.add(mcp("Sheets Worker " + i));
                steps.add(mcp("CRM Worker " + i));
                edges.add(new Edge("mcp:ads_seed", adsNode));
                edges.add(new Edge("trigger:sheets_start", sheetsNode));
                edges.add(new Edge("trigger:crm_start", crmNode));
            }
            for (int i = 0; i < 10; i++) {
                String adsFollowup = "mcp:ads_followup_" + i;
                String sheetsFollowup = "mcp:sheets_followup_" + i;
                steps.add(mcp("Ads Followup " + i));
                steps.add(mcp("Sheets Followup " + i));
                edges.add(new Edge("mcp:ads_worker_" + i, adsFollowup));
                edges.add(new Edge("mcp:sheets_worker_" + i, sheetsFollowup));
            }
            WorkflowPlan plan = buildPlan(triggers, steps, edges);

            Set<String> firstNoisyWave = new LinkedHashSet<>();
            for (int i = 0; i < 20; i++) {
                firstNoisyWave.add("mcp:ads_worker_" + i);
                firstNoisyWave.add("mcp:sheets_worker_" + i);
                firstNoisyWave.add("mcp:crm_worker_" + i);
            }
            Set<String> secondNoisyWave = new LinkedHashSet<>();
            for (int i = 0; i < 10; i++) {
                secondNoisyWave.add("mcp:ads_followup_" + i);
                secondNoisyWave.add("mcp:sheets_followup_" + i);
            }

            AtomicInteger concurrentCount = new AtomicInteger(0);
            AtomicInteger maxConcurrent = new AtomicInteger(0);
            CopyOnWriteArrayList<String> executedNodes = new CopyOnWriteArrayList<>();

            when(v2StepByStepScheduler.getPendingItemIdsForNode(eq(runId), anyString()))
                .thenReturn(Set.of());
            when(v2StepByStepScheduler.getPendingNodeIds(runId)).thenReturn(Set.of());
            when(v2StepByStepService.executeNode(eq(runId), anyString(), eq("0"), eq(8), eq("trigger:ads_start")))
                .thenAnswer(invocation -> {
                    String nodeId = invocation.getArgument(1);
                    executedNodes.add(nodeId);
                    int current = concurrentCount.incrementAndGet();
                    maxConcurrent.updateAndGet(max -> Math.max(max, current));
                    Thread.sleep(10);
                    concurrentCount.decrementAndGet();
                    return successResult(nodeId);
                });
            when(v2StepByStepService.getReadyNodes(runId, "0", 8, null))
                .thenReturn(firstNoisyWave)
                .thenReturn(secondNoisyWave)
                .thenReturn(Set.of());

            boolean hasFailures = invokeExecuteReadySteps(
                runId, Set.of("mcp:ads_seed"), 8, "trigger:ads_start", plan);

            assertThat(hasFailures).isFalse();
            assertThat(executedNodes).hasSize(31);
            assertThat(executedNodes).allMatch(nodeId -> nodeId.startsWith("mcp:ads_"));
            assertThat(maxConcurrent.get()).isGreaterThan(1);
            for (int i = 0; i < 20; i++) {
                verify(v2StepByStepService, never()).executeNode(
                    runId, "mcp:sheets_worker_" + i, "0", 8, "trigger:ads_start");
                verify(v2StepByStepService, never()).executeNode(
                    runId, "mcp:crm_worker_" + i, "0", 8, "trigger:ads_start");
            }
            for (int i = 0; i < 10; i++) {
                verify(v2StepByStepService, never()).executeNode(
                    runId, "mcp:sheets_followup_" + i, "0", 8, "trigger:ads_start");
            }
        }
    }

    @Nested
    @DisplayName("Async yield refire guard (T10/T11/T61 regression)")
    class AsyncYieldRefireGuard {

        /**
         * Regression test for the "classify/categorize spawn loop" bug (tickets T10/T11/T61).
         *
         * <p>Before the fix, an agent node yielding AWAITING_SIGNAL to the async Redis queue
         * would have {@code isSuccess() == false} AND {@code isFailure() == false}. The
         * executeReadySteps loop branched on {@code !result.isSuccess()} → marked
         * {@code hasFailures=true} → trigger refired → new epoch dispatched the same agent
         * task again. On load, this amplified dispatches ~20×.
         *
         * <p>After the fix, the loop gates on {@link StepByStepExecutionResult#isPending()}
         * first. A pending yield must NOT flip {@code hasFailures} and must NOT cause the
         * refire loop to retry the node.
         */
        @Test
        @DisplayName("Async agent yield (AWAITING_SIGNAL + AGENT_EXECUTION) must NOT flip hasFailures=true")
        void asyncAgentYieldMustNotFlipHasFailures() throws Exception {
            String runId = "run-async-agent-1";
            Set<String> readySteps = Set.of("agent:categorize_message");

            when(v2StepByStepScheduler.getPendingItemIdsForNode(eq(runId), anyString()))
                .thenReturn(Set.of());

            // Agent node yields AWAITING_SIGNAL (async queue path).
            when(v2StepByStepService.executeNode(runId, "agent:categorize_message", "0", 2, "trigger:start"))
                .thenReturn(asyncAgentYieldResult("agent:categorize_message"));

            // No further ready nodes.
            when(v2StepByStepService.getReadyNodes(runId, "0", 2, null)).thenReturn(Set.of());

            boolean hasFailures = invokeExecuteReadySteps(runId, readySteps, 2, "trigger:start");

            // Critical invariant: async yield must NOT be mis-classified as a failure.
            // Before the fix this was `true`, causing ReusableTriggerService to refire the trigger.
            assertThat(hasFailures).isFalse();

            // The node must have been executed exactly ONCE - no refire loop.
            verify(v2StepByStepService, times(1))
                .executeNode(runId, "agent:categorize_message", "0", 2, "trigger:start");
        }

        @Test
        @DisplayName("Mixed batch: async agent yield + real failure - only failure flips hasFailures")
        void mixedAsyncYieldAndFailure() throws Exception {
            String runId = "run-mixed-async-1";
            Set<String> readySteps = Set.of("agent:classify", "core:broken");

            when(v2StepByStepScheduler.getPendingItemIdsForNode(eq(runId), anyString()))
                .thenReturn(Set.of());

            when(v2StepByStepService.executeNode(runId, "agent:classify", "0", 2, "trigger:start"))
                .thenReturn(asyncAgentYieldResult("agent:classify"));
            when(v2StepByStepService.executeNode(runId, "core:broken", "0", 2, "trigger:start"))
                .thenReturn(failureResult("core:broken", "HTTP 500"));

            when(v2StepByStepService.getReadyNodes(runId, "0", 2, null)).thenReturn(Set.of());

            boolean hasFailures = invokeExecuteReadySteps(runId, readySteps, 2, "trigger:start");

            // The real failure flips hasFailures; the async yield does NOT contribute.
            assertThat(hasFailures).isTrue();
            verify(v2StepByStepService, times(2))
                .executeNode(eq(runId), anyString(), eq("0"), eq(2), eq("trigger:start"));
        }
    }

    @Nested
    @DisplayName("Concurrency verification")
    class ConcurrencyTests {

        @Test
        @DisplayName("Should verify true parallelism via overlapping execution windows")
        void shouldVerifyTrueParallelism() throws Exception {
            String runId = "run-concurrency-1";
            Set<String> readySteps = Set.of("core:branch_a", "core:branch_b", "core:branch_c");

            // Track concurrent execution count
            AtomicInteger concurrentCount = new AtomicInteger(0);
            AtomicInteger maxConcurrent = new AtomicInteger(0);

            when(v2StepByStepScheduler.getPendingItemIdsForNode(eq(runId), anyString()))
                .thenReturn(Set.of());

            when(v2StepByStepService.executeNode(eq(runId), anyString(), eq("0"), eq(2), eq("trigger:start")))
                .thenAnswer(invocation -> {
                    String nodeId = invocation.getArgument(1);
                    int current = concurrentCount.incrementAndGet();
                    maxConcurrent.updateAndGet(max -> Math.max(max, current));
                    // Hold the thread to ensure overlap
                    Thread.sleep(100);
                    concurrentCount.decrementAndGet();
                    return successResult(nodeId);
                });

            when(v2StepByStepService.getReadyNodes(runId, "0", 2, null)).thenReturn(Set.of());

            boolean hasFailures = invokeExecuteReadySteps(runId, readySteps, 2, "trigger:start");

            assertThat(hasFailures).isFalse();
            // With true parallelism, max concurrent should be > 1
            assertThat(maxConcurrent.get()).isGreaterThan(1);
        }
    }
}
