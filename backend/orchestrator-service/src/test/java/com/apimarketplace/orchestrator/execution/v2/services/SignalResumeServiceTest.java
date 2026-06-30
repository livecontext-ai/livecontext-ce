package com.apimarketplace.orchestrator.execution.v2.services;

import com.apimarketplace.common.storage.service.StorageService;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity;
import com.apimarketplace.orchestrator.domain.WorkflowStepDataEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.cache.RedisCacheKeys;
import com.apimarketplace.orchestrator.execution.v2.cache.ExecutionCacheManager;
import com.apimarketplace.orchestrator.execution.v2.split.SplitContextManager;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.orchestrator.services.streaming.state.RunningNodeTracker;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.apimarketplace.orchestrator.utils.ExecutionConstants;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.orchestrator.services.resume.WorkflowResumeService;
import com.apimarketplace.orchestrator.services.resume.WorkflowRunState;
import com.apimarketplace.orchestrator.services.context.ReadinessContextCache;
import com.apimarketplace.orchestrator.services.streaming.SnapshotService;
import com.apimarketplace.orchestrator.trigger.ReusableTriggerService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.apimarketplace.orchestrator.domain.execution.SignalResolution;
import com.apimarketplace.orchestrator.domain.execution.SignalType;
import com.apimarketplace.orchestrator.domain.workflow.Edge;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.cache.ExecutionCacheManager.LoadedExecution;
import com.apimarketplace.orchestrator.execution.v2.engine.BackEdgeHandler;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionTree;
import com.apimarketplace.orchestrator.execution.v2.engine.StepByStepExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SignalResumeService.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SignalResumeService")
class SignalResumeServiceTest {

    @Mock private StringRedisTemplate mockRedis;
    @SuppressWarnings("unchecked")
    @Mock private ValueOperations<String, String> mockValueOps;
    @Mock private WorkflowRunRepository mockRunRepository;
    @Mock private SplitContextManager mockSplitContextManager;
    @Mock private StorageService mockStorageService;
    @Mock private StateSnapshotService mockStateSnapshotService;
    @Mock private WorkflowStepDataRepository mockStepDataRepository;
    @Mock private ExecutionCacheManager mockExecutionCacheManager;
    @Mock private NodeSearchService mockNodeSearchService;
    @Mock private RunningNodeTracker mockRunningNodeTracker;
    @Mock private WorkflowEpochService mockWorkflowEpochService;
    @Mock private V2StepByStepService mockStepByStepService;
    @Mock private V2StepByStepContextManager mockStepByStepContextManager;
    @Mock private BackEdgeHandler mockBackEdgeHandler;
    @Mock private V2ExecutionEventService mockEventService;
    @Mock private UnifiedSignalService mockSignalService;
    @Mock private CreditConsumptionClient mockCreditClient;
    @Mock private ReadinessContextCache mockReadinessCache;

    private SignalResumeService resumeService;

    @BeforeEach
    void setUp() throws Exception {
        resumeService = new SignalResumeService(
            mockRedis, mockRunRepository, mockSplitContextManager,
            mockStorageService, mockStateSnapshotService,
            mockStepDataRepository, mockExecutionCacheManager, mockNodeSearchService,
            mockRunningNodeTracker, mockWorkflowEpochService);

        // Inject @Autowired fields via reflection
        setField("v2StepByStepService", mockStepByStepService);
        setField("stepByStepContextManager", mockStepByStepContextManager);
        setField("backEdgeHandler", mockBackEdgeHandler);
        setField("eventService", mockEventService);
        setField("signalService", mockSignalService);
        setField("creditClient", mockCreditClient);
        setField("readinessCache", mockReadinessCache);

        // Stub Redis dedup: always allow (first caller wins)
        lenient().when(mockRedis.opsForValue()).thenReturn(mockValueOps);
        lenient().when(mockValueOps.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class))).thenReturn(true);

        // Default: blocking signals active, so tryDeferredReset is a no-op in unrelated tests
        lenient().when(mockSignalService.hasBlockingSignalsForDagAndEpoch(any(), any(), anyInt())).thenReturn(true);
    }

    private void setField(String fieldName, Object value) throws Exception {
        Field field = SignalResumeService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(resumeService, value);
    }

    private SignalWaitEntity createMockSignal(String runId, String nodeId, String itemId) {
        SignalWaitEntity signal = mock(SignalWaitEntity.class);
        when(signal.getRunId()).thenReturn(runId);
        when(signal.getNodeId()).thenReturn(nodeId);
        when(signal.getItemId()).thenReturn(itemId);
        lenient().when(signal.getResolution()).thenReturn(
            com.apimarketplace.orchestrator.domain.execution.SignalResolution.COMPLETED);
        lenient().when(signal.getSignalType()).thenReturn(
            com.apimarketplace.orchestrator.domain.execution.SignalType.WAIT_TIMER);
        return signal;
    }

    @Test
    @DisplayName("regression: local async signal resume binds run organization before resumeAfterSignal")
    void localAsyncSignalResumeBindsRunOrganizationBeforeResumeAfterSignal() {
        String runId = "run-local-signal-org";
        String orgId = "org-local-signal";
        String orgRole = "OWNER";

        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setRunIdPublic(runId);
        run.setTenantId("tenant-42");
        run.setOrganizationId(orgId);
        run.setOrganizationRole(orgRole);
        when(mockRunRepository.findByRunIdPublic(runId)).thenReturn(Optional.of(run));

        SignalWaitEntity signal = new SignalWaitEntity();
        signal.setId(4242L);
        signal.setRunId(runId);
        signal.setNodeId("core:manager_approval");

        AtomicReference<String> orgInsideResume = new AtomicReference<>();
        AtomicReference<String> roleInsideResume = new AtomicReference<>();
        SignalResumeService scopedService = new SignalResumeService(
            mockRedis, mockRunRepository, mockSplitContextManager,
            mockStorageService, mockStateSnapshotService,
            mockStepDataRepository, mockExecutionCacheManager, mockNodeSearchService,
            mockRunningNodeTracker, mockWorkflowEpochService) {
            @Override
            public void resumeAfterSignal(SignalWaitEntity resolvedSignal) {
                orgInsideResume.set(com.apimarketplace.common.web.TenantResolver.currentRequestOrganizationId());
                roleInsideResume.set(com.apimarketplace.common.web.TenantResolver.currentRequestOrganizationRole());
            }
        };

        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        assertThat(com.apimarketplace.common.web.TenantResolver.currentRequestOrganizationId()).isNull();
        assertThat(com.apimarketplace.common.web.TenantResolver.currentRequestOrganizationRole()).isNull();

        scopedService.onSignalResolved(new SignalResolvedEvent(this, signal));

        assertThat(orgInsideResume.get())
            .as("local @TransactionalEventListener async path must bind the run org before resuming")
            .isEqualTo(orgId);
        assertThat(roleInsideResume.get())
            .as("local async signal resume must also bind the run workspace role")
            .isEqualTo(orgRole);
        assertThat(com.apimarketplace.common.web.TenantResolver.currentRequestOrganizationId()).isNull();
        assertThat(com.apimarketplace.common.web.TenantResolver.currentRequestOrganizationRole()).isNull();
        assertThat(org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()).isNull();
    }

    @Nested
    @DisplayName("resumeAfterSignal")
    class ResumeAfterSignal {

        @Test
        @DisplayName("should return early when run not found")
        void shouldReturnEarlyWhenRunNotFound() {
            SignalWaitEntity signal = createMockSignal("run-1", "core:wait", "item-0");
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.empty());

            resumeService.resumeAfterSignal(signal);

            verify(mockStepByStepService, never()).getReadyNodes(any(), any(), anyInt());
        }

        @Test
        @DisplayName("should return early when run not in resumable state for non-resumable signal type")
        void shouldReturnEarlyWhenNotResumable() {
            // WEBHOOK_WAIT from a terminal state is NOT resumable
            SignalWaitEntity signal = createMockSignal("run-1", "core:wait", "item-0");
            when(signal.getSignalType()).thenReturn(
                com.apimarketplace.orchestrator.domain.execution.SignalType.WEBHOOK_WAIT);

            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.COMPLETED);
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            resumeService.resumeAfterSignal(signal);

            verify(mockStepByStepService, never()).getReadyNodes(any(), any(), anyInt());
        }

        @Test
        @DisplayName("should reopen FAILED run for USER_APPROVAL signal (safety net)")
        void shouldReopenFailedRunForUserApproval() {
            SignalWaitEntity signal = createMockSignal("run-1", "core:user_approval", "0");
            when(signal.getSignalType()).thenReturn(
                com.apimarketplace.orchestrator.domain.execution.SignalType.USER_APPROVAL);
            when(signal.getResolution()).thenReturn(
                com.apimarketplace.orchestrator.domain.execution.SignalResolution.APPROVED);
            when(signal.getSplitItemData()).thenReturn(null);
            lenient().when(signal.getDagTriggerId()).thenReturn("trigger:webhook");

            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.FAILED);
            when(run.isStepByStepMode()).thenReturn(false);
            when(run.getRunIdPublic()).thenReturn("run-1");
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            // After reopen, getReadyNodes is called
            when(mockStepByStepService.getReadyNodes("run-1", "0", 0)).thenReturn(Set.of());

            resumeService.resumeAfterSignal(signal);

            // Run should be reopened to RUNNING
            verify(run).setStatus(RunStatus.RUNNING);
            verify(mockRunRepository, atLeastOnce()).save(run);
            // Should proceed to get ready nodes
            verify(mockStepByStepService).getReadyNodes("run-1", "0", 0);
        }

        @Test
        @DisplayName("should reopen FAILED run for WAIT_TIMER signal (safety net)")
        void shouldReopenFailedRunForWaitTimer() {
            SignalWaitEntity signal = createMockSignal("run-1", "core:wait", "0");
            when(signal.getSplitItemData()).thenReturn(null);
            lenient().when(signal.getDagTriggerId()).thenReturn("trigger:webhook");

            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.FAILED);
            when(run.isStepByStepMode()).thenReturn(false);
            when(run.getRunIdPublic()).thenReturn("run-1");
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            when(mockStepByStepService.getReadyNodes("run-1", "0", 0)).thenReturn(Set.of());

            resumeService.resumeAfterSignal(signal);

            // Run should be reopened to RUNNING
            verify(run).setStatus(RunStatus.RUNNING);
            verify(mockStepByStepService).getReadyNodes("run-1", "0", 0);
        }

        @Test
        @DisplayName("should proceed when run is RUNNING")
        void shouldProceedWhenRunning() {
            SignalWaitEntity signal = createMockSignal("run-1", "core:wait", "item-0");
            when(signal.getSplitItemData()).thenReturn(null);
            lenient().when(signal.getDagTriggerId()).thenReturn("trigger:webhook");

            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.RUNNING);
            when(run.isStepByStepMode()).thenReturn(false);
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            // No ready nodes
            when(mockStepByStepService.getReadyNodes("run-1", "item-0", 0)).thenReturn(Set.of());

            resumeService.resumeAfterSignal(signal);

            verify(mockStepByStepService).getReadyNodes("run-1", "item-0", 0);
        }

        @Test
        @DisplayName("should invalidate readiness cache before calculating successors after signal resolution")
        void shouldInvalidateReadinessCacheBeforeCalculatingSuccessorsAfterSignalResolution() {
            SignalWaitEntity signal = createMockSignal("run-1", "core:wait", "item-0");
            when(signal.getSplitItemData()).thenReturn(null);
            lenient().when(signal.getDagTriggerId()).thenReturn("trigger:webhook");

            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.RUNNING);
            when(run.isStepByStepMode()).thenReturn(false);
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            when(mockStepByStepService.getReadyNodes("run-1", "item-0", 0)).thenReturn(Set.of());

            resumeService.resumeAfterSignal(signal);

            var inOrder = inOrder(mockReadinessCache, mockStepByStepService);
            inOrder.verify(mockReadinessCache).invalidateRun("run-1");
            inOrder.verify(mockStepByStepService).getReadyNodes("run-1", "item-0", 0);
        }

        @Test
        @DisplayName("should advance loop back-edge after signal resolution when only trigger is ready")
        void shouldAdvanceLoopBackEdgeAfterSignalResolution() {
            SignalWaitEntity signal = createMockSignal("run-1", "core:iteration_gate", "0");
            when(signal.getSignalType()).thenReturn(SignalType.USER_APPROVAL);
            when(signal.getResolution()).thenReturn(SignalResolution.APPROVED);
            when(signal.getSplitItemData()).thenReturn(null);
            when(signal.getEpoch()).thenReturn(1);
            when(signal.getDagTriggerId()).thenReturn("trigger:start");

            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.RUNNING);
            when(run.isStepByStepMode()).thenReturn(false);
            when(run.getTenantId()).thenReturn("tenant-1");
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            WorkflowPlan plan = mock(WorkflowPlan.class);
            Edge iterateEdge = new Edge("core:iteration_gate:approved", "core:repeat_twice:iterate");
            when(plan.getIterateEdgesForSource("core:iteration_gate")).thenReturn(List.of(iterateEdge));
            WorkflowExecution execution = new WorkflowExecution("run-1", plan, Map.of());
            ExecutionTree tree = mock(ExecutionTree.class);
            LoadedExecution loaded = new LoadedExecution(tree, execution);
            when(mockExecutionCacheManager.loadTreeAndExecution("run-1")).thenReturn(loaded);

            ExecutionNode signalNode = mock(ExecutionNode.class);
            when(signalNode.isBranchingNode()).thenReturn(true);
            when(mockNodeSearchService.buildNodeMapFromAllRoots(tree))
                .thenReturn(Map.of("core:iteration_gate", signalNode));

            ExecutionContext context = mock(ExecutionContext.class);
            when(context.triggerData()).thenReturn(Map.of());
            when(context.itemIndex()).thenReturn(0);
            when(context.triggerId()).thenReturn("trigger:start");
            when(context.getGlobalDataKeys()).thenReturn(Set.of());
            when(context.getGlobalData(anyString())).thenReturn(Optional.empty());
            when(mockStepByStepContextManager.getOrCreateContextWithTriggerData(
                eq("run-1:0"), eq(tree), eq("0"), eq(0), eq("core:iteration_gate"), eq(1), eq("trigger:start")))
                .thenReturn(context);

            StepByStepExecutionResult backEdgeResult = mock(StepByStepExecutionResult.class);
            when(backEdgeResult.context()).thenReturn(context);
            when(backEdgeResult.readyNodes()).thenReturn(Set.of("core:iteration_gate"));
            when(mockBackEdgeHandler.executeBackEdgeIteration(
                eq(signalNode), eq("core:iteration_gate"), any(), eq(context), eq(execution),
                eq(mockEventService), any(), eq(0), anyMap()))
                .thenReturn(backEdgeResult);

            StepByStepExecutionResult pendingResult = mock(StepByStepExecutionResult.class);
            when(pendingResult.isPending()).thenReturn(true);
            when(mockStepByStepService.getReadyNodes("run-1", "0", 1)).thenReturn(Set.of("trigger:start"));
            when(mockStepByStepService.executeNode("run-1", "core:iteration_gate", "0", 1, "trigger:start"))
                .thenReturn(pendingResult);

            resumeService.resumeAfterSignal(signal);

            verify(mockBackEdgeHandler).executeBackEdgeIteration(
                eq(signalNode), eq("core:iteration_gate"), any(), eq(context), eq(execution),
                eq(mockEventService), any(), eq(0), anyMap());
            verify(mockStepByStepService).executeNode("run-1", "core:iteration_gate", "0", 1, "trigger:start");
        }

        @Test
        @DisplayName("should skip duplicate signal output persistence when item output already exists")
        void shouldSkipDuplicateSignalOutputPersistenceWhenItemOutputAlreadyExists() {
            SignalWaitEntity signal = createMockSignal("run-1", "core:user_approval", "1");
            when(signal.getSignalType()).thenReturn(SignalType.USER_APPROVAL);
            when(signal.getResolution()).thenReturn(SignalResolution.APPROVED);
            when(signal.getSplitItemData()).thenReturn(null);
            when(signal.getEpoch()).thenReturn(1);
            when(signal.getDagTriggerId()).thenReturn("trigger:start");

            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.RUNNING);
            when(run.isStepByStepMode()).thenReturn(false);
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            when(mockStepDataRepository.existsByRunIdAndNormalizedKeyAndEpochAndItemIndexAndStatus(
                "run-1", "core:user_approval", 1, 1, "COMPLETED"))
                .thenReturn(true);
            when(mockStepByStepService.getReadyNodes("run-1", "1", 1)).thenReturn(Set.of());

            resumeService.resumeAfterSignal(signal);

            verify(mockStorageService, never()).saveJsonWithContext(
                any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any(), any());
            verify(mockStepDataRepository, never()).save(any());
            verify(mockEventService, never()).emitBranchingEdgesForSignalNode(
                any(), any(), anyInt(), any(), anyInt(), any(), anyBoolean());
        }

        @Test
        @DisplayName("should persist repeated loop signal output with next step iteration")
        void shouldPersistRepeatedLoopSignalOutputWithNextStepIteration() {
            Instant secondYield = Instant.parse("2026-05-15T08:00:02Z");
            SignalWaitEntity signal = createMockSignal("run-1", "core:iteration_gate", "0");
            when(signal.getSignalType()).thenReturn(SignalType.USER_APPROVAL);
            when(signal.getResolution()).thenReturn(SignalResolution.APPROVED);
            when(signal.getCreatedAt()).thenReturn(secondYield);
            when(signal.getSplitItemData()).thenReturn(null);
            when(signal.getEpoch()).thenReturn(1);
            when(signal.getDagTriggerId()).thenReturn("trigger:start");

            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.RUNNING);
            when(run.isStepByStepMode()).thenReturn(false);
            when(run.getTenantId()).thenReturn("tenant-1");
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            when(mockStepDataRepository.existsByRunIdAndNormalizedKeyAndEpochAndItemIndexAndStatusAndStartTime(
                "run-1", "core:iteration_gate", 1, 0, "COMPLETED", secondYield))
                .thenReturn(false);
            when(mockStepDataRepository.countByRunIdAndNormalizedKeyAndEpochAndItemIndexAndStatus(
                "run-1", "core:iteration_gate", 1, 0, "COMPLETED"))
                .thenReturn(1L);
            when(mockStepByStepService.getReadyNodes("run-1", "0", 1)).thenReturn(Set.of());

            resumeService.resumeAfterSignal(signal);

            verify(mockStepDataRepository).save(argThat(entity ->
                "core:iteration_gate".equals(entity.getNormalizedKey())
                    && Integer.valueOf(1).equals(entity.getIteration())
                    && Integer.valueOf(0).equals(entity.getItemIndex())
            ));
        }

        @Test
        @DisplayName("should update ready nodes when run is PAUSED in SBS mode")
        void shouldUpdateReadyNodesWhenPausedSbs() {
            SignalWaitEntity signal = createMockSignal("run-1", "core:wait", "item-0");
            when(signal.getSplitItemData()).thenReturn(null);
            lenient().when(signal.getDagTriggerId()).thenReturn("trigger:webhook");
            lenient().when(signal.getEpoch()).thenReturn(0);

            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.PAUSED);
            when(run.isStepByStepMode()).thenReturn(true);
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            when(mockStepByStepService.getReadyNodes("run-1", "item-0", 0)).thenReturn(Set.of());

            resumeService.resumeAfterSignal(signal);

            // SBS PAUSED: signal resolution is persisted AND ready nodes are updated
            verify(mockStepByStepService).getReadyNodes("run-1", "item-0", 0);
            verify(mockSignalService, atLeastOnce()).clearSignalResumePending(signal);
        }

        @Test
        @DisplayName("should defer execution when run is PAUSED in AUTO mode")
        void shouldDeferWhenPausedAuto() {
            SignalWaitEntity signal = createMockSignal("run-1", "core:wait", "item-0");
            when(signal.getSplitItemData()).thenReturn(null);
            lenient().when(signal.getDagTriggerId()).thenReturn("trigger:webhook");

            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.PAUSED);
            when(run.isStepByStepMode()).thenReturn(false);
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            resumeService.resumeAfterSignal(signal);

            // AUTO PAUSED: signal resolution is persisted but execution is deferred
            verify(mockStepByStepService, never()).getReadyNodes(any(), any(), anyInt());
            verify(mockSignalService, atLeastOnce()).clearSignalResumePending(signal);
        }

        @Test
        @DisplayName("should proceed when run is WAITING_TRIGGER")
        void shouldProceedWhenWaitingTrigger() {
            SignalWaitEntity signal = createMockSignal("run-1", "core:wait", "item-0");
            when(signal.getSplitItemData()).thenReturn(null);
            lenient().when(signal.getDagTriggerId()).thenReturn("trigger:webhook");

            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.WAITING_TRIGGER);
            when(run.isStepByStepMode()).thenReturn(false);
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            when(mockStepByStepService.getReadyNodes("run-1", "item-0", 0)).thenReturn(Set.of());

            resumeService.resumeAfterSignal(signal);

            verify(mockStepByStepService).getReadyNodes("run-1", "item-0", 0);
        }

        @Test
        @DisplayName("should restore split context when signal has split item data")
        void shouldRestoreSplitContext() {
            SignalWaitEntity signal = createMockSignal("run-1", "core:wait", "item-0");
            Map<String, Object> splitData = Map.of("splitNodeId", "core:split", "items", java.util.List.of("a"));
            when(signal.getSplitItemData()).thenReturn(splitData);
            lenient().when(signal.getDagTriggerId()).thenReturn("trigger:webhook");

            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.RUNNING);
            when(run.isStepByStepMode()).thenReturn(false);
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            when(mockStepByStepService.getReadyNodes("run-1", "item-0", 0)).thenReturn(Set.of());

            resumeService.resumeAfterSignal(signal);

            verify(mockSplitContextManager).restoreContext("run-1", "core:wait", splitData);
        }

        @Test
        @DisplayName("should not restore split context when split data is null")
        void shouldNotRestoreWhenSplitDataNull() {
            SignalWaitEntity signal = createMockSignal("run-1", "core:wait", "item-0");
            when(signal.getSplitItemData()).thenReturn(null);
            lenient().when(signal.getDagTriggerId()).thenReturn("trigger:webhook");

            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.RUNNING);
            when(run.isStepByStepMode()).thenReturn(false);
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            when(mockStepByStepService.getReadyNodes("run-1", "item-0", 0)).thenReturn(Set.of());

            resumeService.resumeAfterSignal(signal);

            verify(mockSplitContextManager, never()).restoreContext(any(), any(), any());
        }

        @Test
        @DisplayName("should return early when v2StepByStepService is null")
        void shouldReturnWhenServiceNull() throws Exception {
            setField("v2StepByStepService", null);

            SignalWaitEntity signal = createMockSignal("run-1", "core:wait", "item-0");
            lenient().when(signal.getSplitItemData()).thenReturn(null);

            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.RUNNING);
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            resumeService.resumeAfterSignal(signal);

            // v2StepByStepService is null, so getReadyNodes should not be called
            verify(mockStepByStepService, never()).getReadyNodes(any(), any(), anyInt());
        }
    }

    @Nested
    @DisplayName("onSignalResolved")
    class OnSignalResolved {

        @Test
        @DisplayName("should delegate to resumeAfterSignal")
        void shouldDelegateToResumeAfterSignal() {
            SignalWaitEntity signal = createMockSignal("run-1", "core:wait", "item-0");
            when(signal.getSplitItemData()).thenReturn(null);
            lenient().when(signal.getDagTriggerId()).thenReturn("trigger:webhook");

            SignalResolvedEvent event = new SignalResolvedEvent(this, signal);

            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.RUNNING);
            when(run.isStepByStepMode()).thenReturn(false);
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));
            when(mockStepByStepService.getReadyNodes("run-1", "item-0", 0)).thenReturn(Set.of());
            // Global lenient default: hasBlockingSignalsForDagAndEpoch returns true,
            // so tryDeferredReset is a no-op

            resumeService.onSignalResolved(event);

            // onSignalResolved first restores the run-owned request context, then
            // resumeAfterSignal validates the resumable run state.
            verify(mockRunRepository, times(2)).findByRunIdPublic("run-1");
        }

        @Test
        @DisplayName("should handle exception in onSignalResolved gracefully")
        void shouldHandleExceptionGracefully() {
            SignalWaitEntity signal = createMockSignal("run-1", "core:wait", "item-0");
            SignalResolvedEvent event = new SignalResolvedEvent(this, signal);

            when(mockRunRepository.findByRunIdPublic("run-1"))
                .thenThrow(new RuntimeException("DB error"));

            // Should not throw
            resumeService.onSignalResolved(event);
        }
    }

    @Nested
    @DisplayName("step-by-step mode resume")
    class StepByStepModeResume {

        @Test
        @DisplayName("should use step-by-step resume when run is in SBS mode")
        void shouldUseStepByStepResume() {
            SignalWaitEntity signal = createMockSignal("run-1", "core:wait", "item-0");
            when(signal.getSplitItemData()).thenReturn(null);

            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.RUNNING);
            when(run.isStepByStepMode()).thenReturn(true);
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            when(mockStepByStepService.getReadyNodes("run-1", "item-0", 0)).thenReturn(Set.of("mcp:step1"));

            resumeService.resumeAfterSignal(signal);

            // In SBS mode, should get ready nodes but not execute them in a loop
            verify(mockStepByStepService).getReadyNodes("run-1", "item-0", 0);
            verify(mockStepByStepService, never()).executeNode(any(), any(), any(), anyInt());
            verify(mockSignalService, atLeastOnce()).clearSignalResumePending(signal);
        }

        @Test
        @DisplayName("should handle empty ready nodes in step-by-step mode")
        void shouldHandleEmptyReadyNodesInSBS() {
            SignalWaitEntity signal = createMockSignal("run-1", "core:wait", "item-0");
            when(signal.getSplitItemData()).thenReturn(null);

            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.RUNNING);
            when(run.isStepByStepMode()).thenReturn(true);
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            when(mockStepByStepService.getReadyNodes("run-1", "item-0", 0)).thenReturn(Set.of());

            resumeService.resumeAfterSignal(signal);

            verify(mockStepByStepService, never()).executeNode(any(), any(), any(), anyInt());
            verify(mockSignalService, atLeastOnce()).clearSignalResumePending(signal);
        }
    }

    @Nested
    @DisplayName("auto mode resume")
    class AutoModeResume {

        @Test
        @DisplayName("should filter out trigger nodes from ready set")
        void shouldFilterTriggerNodes() {
            SignalWaitEntity signal = createMockSignal("run-1", "core:wait", "item-0");
            when(signal.getSplitItemData()).thenReturn(null);
            lenient().when(signal.getDagTriggerId()).thenReturn("trigger:webhook");

            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.RUNNING);
            when(run.isStepByStepMode()).thenReturn(false);
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            // First call returns only trigger nodes
            when(mockStepByStepService.getReadyNodes("run-1", "item-0", 0))
                .thenReturn(Set.of("trigger:webhook"));

            lenient().when(mockSignalService.hasActiveSignalsForDag("run-1", "trigger:webhook"))
                .thenReturn(false);

            resumeService.resumeAfterSignal(signal);

            // Trigger nodes should be filtered, so executeNode should not be called
            verify(mockStepByStepService, never()).executeNode(any(), any(), any(), anyInt());
        }

        @Test
        @DisplayName("regression: approval signal executes selected successor when ready calculation only returns trigger")
        void shouldExecuteApprovalSuccessorWhenReadyCalculationOnlyReturnsTrigger() {
            SignalWaitEntity signal = createMockSignal("run-approval", "core:manager_approval", "0");
            when(signal.getSignalType()).thenReturn(SignalType.USER_APPROVAL);
            when(signal.getResolution()).thenReturn(SignalResolution.APPROVED);
            when(signal.getSplitItemData()).thenReturn(null);
            when(signal.getEpoch()).thenReturn(1);
            when(signal.getDagTriggerId()).thenReturn("trigger:start");
            when(signal.getCreatedAt()).thenReturn(Instant.parse("2026-05-21T15:00:00Z"));
            when(signal.getResolvedAt()).thenReturn(Instant.parse("2026-05-21T15:00:01Z"));

            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.RUNNING);
            when(run.isStepByStepMode()).thenReturn(false);
            when(run.getTenantId()).thenReturn("tenant-1");
            when(run.getId()).thenReturn(UUID.fromString("11111111-1111-1111-1111-111111111111"));
            when(run.getOrganizationId()).thenReturn("org-1");
            when(mockRunRepository.findByRunIdPublic("run-approval")).thenReturn(Optional.of(run));
            when(mockStorageService.saveJsonWithContext(
                anyString(), anyMap(), anyString(), any(), any(), anyString(), anyString(),
                anyInt(), anyInt(), any(), anyString()))
                .thenReturn(UUID.fromString("22222222-2222-2222-2222-222222222222"));

            WorkflowPlan plan = mock(WorkflowPlan.class);
            when(plan.getIterateEdgesForSource("core:manager_approval")).thenReturn(List.of());
            WorkflowExecution execution = new WorkflowExecution("run-approval", plan, Map.of());
            ExecutionTree tree = mock(ExecutionTree.class);
            when(mockExecutionCacheManager.loadTreeAndExecution("run-approval"))
                .thenReturn(new LoadedExecution(tree, execution));

            ExecutionNode approval = mock(ExecutionNode.class);
            when(approval.isBranchingNode()).thenReturn(true);
            ExecutionNode approvedPath = mock(ExecutionNode.class);
            when(approvedPath.getNodeId()).thenReturn("core:approved_path");
            when(approval.getNextNodes(argThat(result ->
                result != null && "approved".equals(result.output().get("selected_port")))))
                .thenReturn(List.of(approvedPath));
            when(mockNodeSearchService.buildNodeMapFromAllRoots(tree))
                .thenReturn(Map.of("core:manager_approval", approval));
            ExecutionContext readinessContext = mock(ExecutionContext.class);
            when(mockStepByStepContextManager.getOrCreateContextWithTriggerData(
                startsWith("run-approval:0:signal-successor-fallback:1:trigger:start"),
                eq(tree),
                eq("0"),
                eq(0),
                eq("core:manager_approval"),
                eq(1),
                eq("trigger:start")))
                .thenReturn(readinessContext);
            when(approvedPath.canExecute(readinessContext)).thenReturn(true);

            StepByStepExecutionResult executed = mock(StepByStepExecutionResult.class);
            when(executed.isPending()).thenReturn(false);
            when(executed.isSuccess()).thenReturn(true);
            when(mockStepByStepService.getReadyNodes("run-approval", "0", 1))
                .thenReturn(Set.of("trigger:start"), Set.of());
            when(mockStepByStepService.executeNode(
                "run-approval", "core:approved_path", "0", 1, "trigger:start"))
                .thenReturn(executed);

            resumeService.resumeAfterSignal(signal);

            verify(mockStepByStepService).executeNode(
                "run-approval", "core:approved_path", "0", 1, "trigger:start");
            verify(mockStepByStepService, never()).executeNode(
                eq("run-approval"), startsWith("trigger:"), any(), anyInt(), anyString());
            verify(mockSignalService, atLeastOnce()).clearSignalResumePending(signal);
        }

        @Test
        @DisplayName("regression: signal successor fallback does not bypass readiness for merge successors")
        void signalSuccessorFallbackRequiresSuccessorReadiness() {
            SignalWaitEntity signal = createMockSignal("run-approval-merge", "core:manager_approval", "0");
            when(signal.getSignalType()).thenReturn(SignalType.USER_APPROVAL);
            when(signal.getResolution()).thenReturn(SignalResolution.APPROVED);
            when(signal.getSplitItemData()).thenReturn(null);
            when(signal.getEpoch()).thenReturn(1);
            when(signal.getDagTriggerId()).thenReturn("trigger:start");
            when(signal.getCreatedAt()).thenReturn(Instant.parse("2026-05-21T15:00:00Z"));
            when(signal.getResolvedAt()).thenReturn(Instant.parse("2026-05-21T15:00:01Z"));

            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.RUNNING);
            when(run.isStepByStepMode()).thenReturn(false);
            when(run.getTenantId()).thenReturn("tenant-1");
            when(run.getId()).thenReturn(UUID.fromString("11111111-1111-1111-1111-111111111111"));
            when(mockRunRepository.findByRunIdPublic("run-approval-merge")).thenReturn(Optional.of(run));
            when(mockStorageService.saveJsonWithContext(
                anyString(), anyMap(), anyString(), any(), any(), anyString(), anyString(),
                anyInt(), anyInt(), any(), anyString()))
                .thenReturn(UUID.fromString("22222222-2222-2222-2222-222222222222"));

            WorkflowPlan plan = mock(WorkflowPlan.class);
            when(plan.getIterateEdgesForSource("core:manager_approval")).thenReturn(List.of());
            WorkflowExecution execution = new WorkflowExecution("run-approval-merge", plan, Map.of());
            ExecutionTree tree = mock(ExecutionTree.class);
            when(mockExecutionCacheManager.loadTreeAndExecution("run-approval-merge"))
                .thenReturn(new LoadedExecution(tree, execution));

            ExecutionNode approval = mock(ExecutionNode.class);
            when(approval.isBranchingNode()).thenReturn(true);
            ExecutionNode merge = mock(ExecutionNode.class);
            when(merge.getNodeId()).thenReturn("core:join");
            when(approval.getNextNodes(any(NodeExecutionResult.class))).thenReturn(List.of(merge));
            when(mockNodeSearchService.buildNodeMapFromAllRoots(tree))
                .thenReturn(Map.of("core:manager_approval", approval));
            ExecutionContext readinessContext = mock(ExecutionContext.class);
            when(mockStepByStepContextManager.getOrCreateContextWithTriggerData(
                startsWith("run-approval-merge:0:signal-successor-fallback:1:trigger:start"),
                eq(tree),
                eq("0"),
                eq(0),
                eq("core:manager_approval"),
                eq(1),
                eq("trigger:start")))
                .thenReturn(readinessContext);
            when(merge.canExecute(readinessContext)).thenReturn(false);

            when(mockStepByStepService.getReadyNodes("run-approval-merge", "0", 1))
                .thenReturn(Set.of("trigger:start"));

            resumeService.resumeAfterSignal(signal);

            verify(mockStepByStepService, never()).executeNode(
                eq("run-approval-merge"), eq("core:join"), any(), anyInt(), anyString());
        }

        @Test
        @DisplayName("BUG FIX: signal-successor fallback does NOT re-execute a successor already FAILED in the epoch (orphan re-delivery)")
        void signalSuccessorFallbackSkipsAlreadyFailedSuccessor() {
            // Same shape as shouldExecuteApprovalSuccessorWhenReadyCalculationOnlyReturnsTrigger
            // (canExecute=true, so PRE-FIX the fallback would execute the successor), but the
            // successor is already FAILED in this epoch - the state left by a first resume whose
            // successor (e.g. an unconfigured send_email / http_request) failed at node level.
            // A re-delivery of the SAME resolved signal (SignalRecoveryService re-driving an
            // orphaned RESOLVED-but-RUNNING signal after a restart, past the 5-minute dedup TTL)
            // reaches this same fallback. findResolvedSignalSuccessors gates only on canExecute
            // (predecessor completion), so pre-fix it re-derives the FAILED node and re-runs it,
            // duplicating the side effect. The new filterOutAlreadyTerminalNodes on the fallback
            // must drop it.
            SignalWaitEntity signal = createMockSignal("run-approval-orphan-refire", "core:manager_approval", "0");
            when(signal.getSignalType()).thenReturn(SignalType.USER_APPROVAL);
            when(signal.getResolution()).thenReturn(SignalResolution.APPROVED);
            when(signal.getSplitItemData()).thenReturn(null);
            when(signal.getEpoch()).thenReturn(1);
            when(signal.getDagTriggerId()).thenReturn("trigger:start");
            when(signal.getCreatedAt()).thenReturn(Instant.parse("2026-05-21T15:00:00Z"));
            when(signal.getResolvedAt()).thenReturn(Instant.parse("2026-05-21T15:00:01Z"));

            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.RUNNING);
            when(run.isStepByStepMode()).thenReturn(false);
            when(run.getTenantId()).thenReturn("tenant-1");
            when(run.getId()).thenReturn(UUID.fromString("11111111-1111-1111-1111-111111111111"));
            when(mockRunRepository.findByRunIdPublic("run-approval-orphan-refire")).thenReturn(Optional.of(run));
            when(mockStorageService.saveJsonWithContext(
                anyString(), anyMap(), anyString(), any(), any(), anyString(), anyString(),
                anyInt(), anyInt(), any(), anyString()))
                .thenReturn(UUID.fromString("22222222-2222-2222-2222-222222222222"));

            WorkflowPlan plan = mock(WorkflowPlan.class);
            when(plan.getIterateEdgesForSource("core:manager_approval")).thenReturn(List.of());
            WorkflowExecution execution = new WorkflowExecution("run-approval-orphan-refire", plan, Map.of());
            ExecutionTree tree = mock(ExecutionTree.class);
            when(mockExecutionCacheManager.loadTreeAndExecution("run-approval-orphan-refire"))
                .thenReturn(new LoadedExecution(tree, execution));

            ExecutionNode approval = mock(ExecutionNode.class);
            when(approval.isBranchingNode()).thenReturn(true);
            ExecutionNode approvedPath = mock(ExecutionNode.class);
            when(approvedPath.getNodeId()).thenReturn("core:approved_path");
            when(approval.getNextNodes(argThat(result ->
                result != null && "approved".equals(result.output().get("selected_port")))))
                .thenReturn(List.of(approvedPath));
            when(mockNodeSearchService.buildNodeMapFromAllRoots(tree))
                .thenReturn(Map.of("core:manager_approval", approval));
            ExecutionContext readinessContext = mock(ExecutionContext.class);
            when(mockStepByStepContextManager.getOrCreateContextWithTriggerData(
                startsWith("run-approval-orphan-refire:0:signal-successor-fallback:1:trigger:start"),
                eq(tree), eq("0"), eq(0), eq("core:manager_approval"), eq(1), eq("trigger:start")))
                .thenReturn(readinessContext);
            // Predecessor completion satisfied: pre-fix this alone would re-run the node.
            when(approvedPath.canExecute(readinessContext)).thenReturn(true);

            // The successor is already terminal (FAILED) in epoch 1 of this trigger's DAG. The
            // filter consults StateSnapshot.getTerminalNodeIds (the trigger/epoch gating itself is
            // tested in StateSnapshotTest); here it must drop the FAILED successor from the fallback.
            var snapshot = mock(com.apimarketplace.orchestrator.domain.execution.StateSnapshot.class);
            when(snapshot.getTerminalNodeIds("trigger:start", 1)).thenReturn(Set.of("core:approved_path"));
            when(mockStateSnapshotService.getSnapshot("run-approval-orphan-refire")).thenReturn(snapshot);

            when(mockStepByStepService.getReadyNodes("run-approval-orphan-refire", "0", 1))
                .thenReturn(Set.of("trigger:start"), Set.of());

            resumeService.resumeAfterSignal(signal);

            // The already-FAILED successor must NOT be re-executed (no duplicated side effect).
            verify(mockStepByStepService, never()).executeNode(
                eq("run-approval-orphan-refire"), eq("core:approved_path"), any(), anyInt(), anyString());
            verify(mockStepByStepService, never()).executeNode(
                eq("run-approval-orphan-refire"), eq("core:approved_path"), any(), anyInt());
            // The fallback consulted the snapshot to make the terminal decision.
            verify(mockStateSnapshotService, atLeastOnce()).getSnapshot("run-approval-orphan-refire");
        }

        @Test
        @DisplayName("should handle null ready nodes")
        void shouldHandleNullReadyNodes() {
            SignalWaitEntity signal = createMockSignal("run-1", "core:wait", "item-0");
            when(signal.getSplitItemData()).thenReturn(null);
            lenient().when(signal.getDagTriggerId()).thenReturn("trigger:webhook");

            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.RUNNING);
            when(run.isStepByStepMode()).thenReturn(false);
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            when(mockStepByStepService.getReadyNodes("run-1", "item-0", 0)).thenReturn(null);

            resumeService.resumeAfterSignal(signal);

            verify(mockStepByStepService, never()).executeNode(any(), any(), any(), anyInt());
        }
    }

    @Nested
    @DisplayName("signal node credit consumption")
    class SignalNodeCreditConsumption {

        @Test
        @DisplayName("should consume 1 credit when signal resolves for user approval node")
        void shouldConsumeCreditForUserApproval() {
            SignalWaitEntity signal = createMockSignal("run-credit-1", "core:user_approval", "0");
            when(signal.getSignalType()).thenReturn(
                com.apimarketplace.orchestrator.domain.execution.SignalType.USER_APPROVAL);
            when(signal.getResolution()).thenReturn(
                com.apimarketplace.orchestrator.domain.execution.SignalResolution.APPROVED);
            when(signal.getSplitItemData()).thenReturn(null);
            lenient().when(signal.getDagTriggerId()).thenReturn("trigger:webhook");

            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.RUNNING);
            when(run.isStepByStepMode()).thenReturn(false);
            when(run.getTenantId()).thenReturn("42");
            when(mockRunRepository.findByRunIdPublic("run-credit-1")).thenReturn(Optional.of(run));

            when(mockStepByStepService.getReadyNodes("run-credit-1", "0", 0)).thenReturn(Set.of());

            resumeService.resumeAfterSignal(signal);

            // Verify credit was consumed with WORKFLOW_NODE type and runId:nodeId:epoch:spawn format
            verify(mockCreditClient).consumeCreditsAsync(
                eq("42"), eq("WORKFLOW_NODE"), eq("run-credit-1:core:user_approval:0:0"),
                isNull(), isNull(), isNull(), isNull());
        }

        @Test
        @DisplayName("should consume 1 credit when signal resolves for interface node")
        void shouldConsumeCreditForInterfaceNode() {
            SignalWaitEntity signal = createMockSignal("run-credit-2", "interface:my_page", "0");
            when(signal.getSignalType()).thenReturn(
                com.apimarketplace.orchestrator.domain.execution.SignalType.INTERFACE_SIGNAL);
            when(signal.getResolution()).thenReturn(
                com.apimarketplace.orchestrator.domain.execution.SignalResolution.CONTINUE);
            when(signal.getSplitItemData()).thenReturn(null);
            lenient().when(signal.getDagTriggerId()).thenReturn("trigger:webhook");

            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.RUNNING);
            when(run.isStepByStepMode()).thenReturn(false);
            when(run.getTenantId()).thenReturn("99");
            when(mockRunRepository.findByRunIdPublic("run-credit-2")).thenReturn(Optional.of(run));

            when(mockStepByStepService.getReadyNodes("run-credit-2", "0", 0)).thenReturn(Set.of());

            resumeService.resumeAfterSignal(signal);

            verify(mockCreditClient).consumeCreditsAsync(
                eq("99"), eq("WORKFLOW_NODE"), eq("run-credit-2:interface:my_page:0:0"),
                isNull(), isNull(), isNull(), isNull());
        }

        @Test
        @DisplayName("should not consume credit when tenantId is blank")
        void shouldNotConsumeCreditWhenTenantIdBlank() {
            SignalWaitEntity signal = createMockSignal("run-credit-3", "core:wait", "0");
            when(signal.getSplitItemData()).thenReturn(null);
            lenient().when(signal.getDagTriggerId()).thenReturn("trigger:webhook");

            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.RUNNING);
            when(run.isStepByStepMode()).thenReturn(false);
            when(run.getTenantId()).thenReturn("");
            when(mockRunRepository.findByRunIdPublic("run-credit-3")).thenReturn(Optional.of(run));

            when(mockStepByStepService.getReadyNodes("run-credit-3", "0", 0)).thenReturn(Set.of());

            resumeService.resumeAfterSignal(signal);

            verify(mockCreditClient, never()).consumeCreditsAsync(any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("should include epoch and spawn in sourceId for subsequent epochs/spawns")
        void shouldIncludeEpochAndSpawnInSourceId() {
            SignalWaitEntity signal = createMockSignal("run-credit-5", "core:user_approval", "0");
            when(signal.getSignalType()).thenReturn(
                com.apimarketplace.orchestrator.domain.execution.SignalType.USER_APPROVAL);
            when(signal.getResolution()).thenReturn(
                com.apimarketplace.orchestrator.domain.execution.SignalResolution.APPROVED);
            when(signal.getSplitItemData()).thenReturn(null);
            lenient().when(signal.getDagTriggerId()).thenReturn("trigger:webhook");

            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.RUNNING);
            when(run.isStepByStepMode()).thenReturn(false);
            when(run.getTenantId()).thenReturn("42");
            when(mockRunRepository.findByRunIdPublic("run-credit-5")).thenReturn(Optional.of(run));

            // Simulate epoch 3, spawn 2 (user re-ran the step twice in the 3rd epoch)
            // consumeCreditForSignalNode uses resolvedSignal.getEpoch() for the epoch in sourceId
            when(signal.getEpoch()).thenReturn(3);
            when(run.getMetadata()).thenReturn(Map.of("lastRerunSpawn", 2));

            when(mockStepByStepService.getReadyNodes("run-credit-5", "0", 3)).thenReturn(Set.of());

            resumeService.resumeAfterSignal(signal);

            // Verify sourceId includes epoch 3 and spawn 2
            verify(mockCreditClient).consumeCreditsAsync(
                eq("42"), eq("WORKFLOW_NODE"), eq("run-credit-5:core:user_approval:3:2"),
                isNull(), isNull(), isNull(), isNull());
        }

        @Test
        @DisplayName("should not consume credit when creditClient is null")
        void shouldNotConsumeCreditWhenCreditClientNull() throws Exception {
            setField("creditClient", null);

            SignalWaitEntity signal = createMockSignal("run-credit-4", "core:wait", "0");
            when(signal.getSplitItemData()).thenReturn(null);
            lenient().when(signal.getDagTriggerId()).thenReturn("trigger:webhook");

            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.RUNNING);
            when(run.isStepByStepMode()).thenReturn(false);
            when(run.getTenantId()).thenReturn("42");
            when(mockRunRepository.findByRunIdPublic("run-credit-4")).thenReturn(Optional.of(run));

            when(mockStepByStepService.getReadyNodes("run-credit-4", "0", 0)).thenReturn(Set.of());

            // Should not throw
            resumeService.resumeAfterSignal(signal);

            verify(mockCreditClient, never()).consumeCreditsAsync(any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Round-9 regression: bind run.organizationId on ThreadLocal before consumeCreditsAsync (no HTTP request context)")
        void shouldBindRunOrgIdOnThreadLocalBeforeConsumeCredits() {
            // Round-9 bug shape: SignalResumeRedisListener.onMessage and
            // SignalRecoveryService scheduled sweep call resumeAfterSignal from
            // a thread with NO HTTP request context. Pre-fix:
            // TenantResolver.currentRequestOrganizationId() in CreditConsumptionClient
            // returned null, the producer-side requireOrgId threw, the outer
            // SignalResumeService.consumeCreditForSignalNode catch swallowed
            // the throw as WARN, and the credit was silently dropped (money leak).
            // Fix: wrap creditClient.consumeCreditsAsync in
            // TenantResolver.runWithOrgScope(run.organizationId, ...) so the
            // producer-side resolver sees the run-derived orgId.
            SignalWaitEntity signal = createMockSignal("run-credit-org", "core:user_approval", "0");
            when(signal.getSignalType()).thenReturn(
                com.apimarketplace.orchestrator.domain.execution.SignalType.USER_APPROVAL);
            when(signal.getResolution()).thenReturn(
                com.apimarketplace.orchestrator.domain.execution.SignalResolution.APPROVED);
            when(signal.getSplitItemData()).thenReturn(null);
            lenient().when(signal.getDagTriggerId()).thenReturn("trigger:webhook");

            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.RUNNING);
            when(run.isStepByStepMode()).thenReturn(false);
            when(run.getTenantId()).thenReturn("42");
            when(run.getOrganizationId()).thenReturn("org-from-run-entity");
            when(mockRunRepository.findByRunIdPublic("run-credit-org")).thenReturn(Optional.of(run));

            when(mockStepByStepService.getReadyNodes("run-credit-org", "0", 0)).thenReturn(Set.of());

            // Capture the ThreadLocal at the exact moment consumeCreditsAsync is invoked.
            // Without runWithOrgScope, this would be null (no HTTP request); with the
            // fix, it must be the run entity's organizationId.
            String[] orgAtConsumeTime = new String[1];
            org.mockito.Mockito.doAnswer(inv -> {
                orgAtConsumeTime[0] =
                    com.apimarketplace.common.web.TenantResolver.currentRequestOrganizationId();
                return null;
            }).when(mockCreditClient).consumeCreditsAsync(
                any(), any(), any(), any(), any(), any(), any());

            // Sanity: ThreadLocal is clean before invocation (no ambient HTTP context).
            org.assertj.core.api.Assertions
                .assertThat(com.apimarketplace.common.web.TenantResolver.currentRequestOrganizationId())
                .as("test precondition: no ambient org binding")
                .isNull();

            resumeService.resumeAfterSignal(signal);

            org.assertj.core.api.Assertions.assertThat(orgAtConsumeTime[0])
                .as("consumeCreditsAsync must run inside runWithOrgScope(run.organizationId)")
                .isEqualTo("org-from-run-entity");

            // ThreadLocal must be cleared after runWithOrgScope returns
            // (else neighbor calls on the worker thread inherit the binding).
            org.assertj.core.api.Assertions
                .assertThat(com.apimarketplace.common.web.TenantResolver.currentRequestOrganizationId())
                .as("runWithOrgScope must clean up ThreadLocal post-invocation")
                .isNull();
        }
    }

    @Nested
    @DisplayName("deferred reset on last node in DAG (no successors)")
    class DeferredResetOnLastNode {

        @Mock private ReusableTriggerService mockReusableTriggerService;
        @Mock private WorkflowResumeService mockWorkflowResumeService;
        @Mock private SnapshotService mockSnapshotService;
        @Mock private com.apimarketplace.orchestrator.trigger.ErrorTriggerDispatchService mockErrorTriggerDispatchService;

        @BeforeEach
        void injectDeferredResetDeps() throws Exception {
            setField("reusableTriggerService", mockReusableTriggerService);
            setField("resumeService", mockWorkflowResumeService);
            setField("snapshotService", mockSnapshotService);
            setField("errorTriggerDispatchService", mockErrorTriggerDispatchService);

            // Default: snapshot returns empty failedNodeIds → hasFailures=false.
            // Tests that need hasFailures=true override this stub.
            var defaultEpoch = mock(com.apimarketplace.orchestrator.domain.execution.EpochState.class);
            lenient().when(defaultEpoch.getFailedNodeIds()).thenReturn(Set.of());
            var defaultSnapshot = mock(com.apimarketplace.orchestrator.domain.execution.StateSnapshot.class);
            lenient().when(defaultSnapshot.getEpochState(anyString(), anyInt())).thenReturn(defaultEpoch);
            lenient().when(mockStateSnapshotService.getSnapshot(anyString())).thenReturn(defaultSnapshot);
        }

        private SignalWaitEntity createInterfaceSignal(String runId, String nodeId, String itemId, String triggerId, int epoch) {
            SignalWaitEntity signal = mock(SignalWaitEntity.class);
            when(signal.getRunId()).thenReturn(runId);
            when(signal.getNodeId()).thenReturn(nodeId);
            when(signal.getItemId()).thenReturn(itemId);
            lenient().when(signal.getResolution()).thenReturn(
                com.apimarketplace.orchestrator.domain.execution.SignalResolution.CONTINUE);
            lenient().when(signal.getSignalType()).thenReturn(
                com.apimarketplace.orchestrator.domain.execution.SignalType.INTERFACE_SIGNAL);
            lenient().when(signal.getDagTriggerId()).thenReturn(triggerId);
            lenient().when(signal.getEpoch()).thenReturn(epoch);
            lenient().when(signal.getSplitItemData()).thenReturn(null);
            return signal;
        }

        private void stubWorkflow(WorkflowRunEntity run) {
            WorkflowEntity workflow = new WorkflowEntity();
            workflow.setId(UUID.randomUUID());
            lenient().when(run.getWorkflow()).thenReturn(workflow);
        }

        @Test
        @DisplayName("should trigger deferred reset when interface is last node and no blocking signals remain")
        void shouldTriggerDeferredResetWhenInterfaceIsLastNode() {
            // Scenario: trigger → download_file → interface(__continue) - no successors
            String runId = "run-last-node";
            String triggerId = "trigger:start";
            SignalWaitEntity signal = createInterfaceSignal(runId, "interface:show_image", "0", triggerId, 0);

            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.RUNNING);
            when(run.isStepByStepMode()).thenReturn(false);
            lenient().when(run.getTenantId()).thenReturn("tenant-1");
            stubWorkflow(run);
            when(mockRunRepository.findByRunIdPublic(runId)).thenReturn(Optional.of(run));

            // No ready nodes - interface was the last node
            when(mockStepByStepService.getReadyNodes(runId, "0", 0)).thenReturn(Set.of());

            // Override default: no blocking signals remain (the interface signal is already resolved)
            when(mockSignalService.hasBlockingSignalsForDagAndEpoch(runId, triggerId, 0)).thenReturn(false);

            // Mock performDeferredReset dependencies
            // Phase G.1: SignalResume.performDeferredReset reads run.getPlan() directly.
            when(run.getPlan()).thenReturn(Map.of("triggers", List.of(), "edges", List.of()));

            resumeService.resumeAfterSignal(signal);

            // Verify deferred reset was triggered
            verify(mockSignalService, atLeastOnce()).clearSignalResumePending(signal);
            verify(mockSignalService).hasBlockingSignalsForDagAndEpoch(runId, triggerId, 0);
            verify(mockReusableTriggerService).resetForNextCycle(
                eq(run), any(), any(), eq(runId), any(), eq(triggerId), eq(false), eq(0));
        }

        @Test
        @DisplayName("should NOT trigger deferred reset when blocking signals still active")
        void shouldNotTriggerDeferredResetWhenBlockingSignalsActive() {
            String runId = "run-still-blocked";
            String triggerId = "trigger:start";
            SignalWaitEntity signal = createInterfaceSignal(runId, "interface:page1", "0", triggerId, 0);

            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.RUNNING);
            when(run.isStepByStepMode()).thenReturn(false);
            when(mockRunRepository.findByRunIdPublic(runId)).thenReturn(Optional.of(run));

            // No ready nodes
            when(mockStepByStepService.getReadyNodes(runId, "0", 0)).thenReturn(Set.of());

            // Another blocking signal still active (e.g., a fork with two interface nodes)
            when(mockSignalService.hasBlockingSignalsForDagAndEpoch(runId, triggerId, 0)).thenReturn(true);

            resumeService.resumeAfterSignal(signal);

            // Should check for blocking signals but NOT trigger deferred reset
            verify(mockSignalService).hasBlockingSignalsForDagAndEpoch(runId, triggerId, 0);
            verify(mockReusableTriggerService, never()).resetForNextCycle(
                any(), any(), any(), any(), any(), any(), anyBoolean(), anyInt());
        }

        @Test
        @DisplayName("should trigger deferred reset when all ready nodes are triggers (filtered out)")
        void shouldTriggerDeferredResetWhenOnlyTriggerNodesReady() {
            String runId = "run-only-triggers";
            String triggerId = "trigger:webhook";
            SignalWaitEntity signal = createInterfaceSignal(runId, "interface:dashboard", "0", triggerId, 0);

            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.RUNNING);
            when(run.isStepByStepMode()).thenReturn(false);
            lenient().when(run.getTenantId()).thenReturn("tenant-1");
            stubWorkflow(run);
            when(mockRunRepository.findByRunIdPublic(runId)).thenReturn(Optional.of(run));

            // Ready nodes are all triggers - they get filtered out
            when(mockStepByStepService.getReadyNodes(runId, "0", 0)).thenReturn(Set.of("trigger:webhook"));

            // Override default: no blocking signals
            when(mockSignalService.hasBlockingSignalsForDagAndEpoch(runId, triggerId, 0)).thenReturn(false);

            // Phase G.1: SignalResume.performDeferredReset reads run.getPlan() directly.
            when(run.getPlan()).thenReturn(Map.of("triggers", List.of(), "edges", List.of()));

            resumeService.resumeAfterSignal(signal);

            // Trigger nodes filtered out → empty ready set → deferred reset
            verify(mockReusableTriggerService).resetForNextCycle(
                eq(run), any(), any(), eq(runId), any(), eq(triggerId), eq(false), eq(0));
        }

        @Test
        @DisplayName("should NOT trigger deferred reset when reopened from terminal state")
        void shouldNotTriggerDeferredResetWhenReopenedFromTerminal() {
            String runId = "run-terminal-reopen";
            String triggerId = "trigger:start";
            SignalWaitEntity signal = createInterfaceSignal(runId, "interface:show_image", "0", triggerId, 0);
            // Interface signal can reopen terminal runs
            when(signal.getSignalType()).thenReturn(
                com.apimarketplace.orchestrator.domain.execution.SignalType.INTERFACE_SIGNAL);

            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.COMPLETED);
            when(run.isStepByStepMode()).thenReturn(false);
            when(run.getRunIdPublic()).thenReturn(runId);
            when(mockRunRepository.findByRunIdPublic(runId)).thenReturn(Optional.of(run));

            // No ready nodes
            when(mockStepByStepService.getReadyNodes(runId, "0", 0)).thenReturn(Set.of());

            resumeService.resumeAfterSignal(signal);

            // Reopened from terminal → refinalizeAfterInterfaceResume, NOT deferred reset
            verify(mockReusableTriggerService, never()).resetForNextCycle(
                any(), any(), any(), any(), any(), any(), anyBoolean(), anyInt());
            // Should re-save the original terminal status
            verify(mockRunRepository, atLeastOnce()).save(run);
        }

        @Test
        @DisplayName("should trigger deferred reset for epoch > 0")
        void shouldTriggerDeferredResetForHigherEpoch() {
            String runId = "run-epoch-3";
            String triggerId = "trigger:webhook";
            int epoch = 3;
            SignalWaitEntity signal = createInterfaceSignal(runId, "interface:show_image", "0", triggerId, epoch);

            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.RUNNING);
            when(run.isStepByStepMode()).thenReturn(false);
            lenient().when(run.getTenantId()).thenReturn("tenant-1");
            stubWorkflow(run);
            when(mockRunRepository.findByRunIdPublic(runId)).thenReturn(Optional.of(run));

            // No ready nodes
            when(mockStepByStepService.getReadyNodes(runId, "0", epoch)).thenReturn(Set.of());

            // Override default: no blocking signals in epoch 3
            when(mockSignalService.hasBlockingSignalsForDagAndEpoch(runId, triggerId, epoch)).thenReturn(false);

            // Phase G.1: SignalResume.performDeferredReset reads run.getPlan() directly.
            when(run.getPlan()).thenReturn(Map.of("triggers", List.of(), "edges", List.of()));

            resumeService.resumeAfterSignal(signal);

            // Verify deferred reset is called with correct epoch
            verify(mockSignalService).hasBlockingSignalsForDagAndEpoch(runId, triggerId, epoch);
            verify(mockReusableTriggerService).resetForNextCycle(
                eq(run), any(), any(), eq(runId), any(), eq(triggerId), eq(false), eq(epoch));
        }

        @Test
        @DisplayName("deferred reset passes hasFailures=true when epoch has failed nodes")
        void deferredResetDetectsEpochFailuresFromStateSnapshot() {
            String runId = "run-async-fail";
            String triggerId = "trigger:chat";
            int epoch = 1;
            SignalWaitEntity signal = createInterfaceSignal(runId, "interface:display", "0", triggerId, epoch);

            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.RUNNING);
            when(run.isStepByStepMode()).thenReturn(false);
            lenient().when(run.getTenantId()).thenReturn("tenant-1");
            stubWorkflow(run);
            when(mockRunRepository.findByRunIdPublic(runId)).thenReturn(Optional.of(run));

            when(mockStepByStepService.getReadyNodes(runId, "0", epoch)).thenReturn(Set.of());
            when(mockSignalService.hasBlockingSignalsForDagAndEpoch(runId, triggerId, epoch)).thenReturn(false);
            when(run.getPlan()).thenReturn(Map.of("triggers", List.of(), "edges", List.of()));

            // Stub StateSnapshot with a failed node in the epoch
            var epochState = mock(com.apimarketplace.orchestrator.domain.execution.EpochState.class);
            when(epochState.getFailedNodeIds()).thenReturn(Set.of("agent:broken"));
            var snapshot = mock(com.apimarketplace.orchestrator.domain.execution.StateSnapshot.class);
            when(snapshot.getEpochState(triggerId, epoch)).thenReturn(epochState);
            when(mockStateSnapshotService.getSnapshot(runId)).thenReturn(snapshot);

            resumeService.resumeAfterSignal(signal);

            verify(mockReusableTriggerService).resetForNextCycle(
                eq(run), any(), any(), eq(runId), any(), eq(triggerId), eq(true), eq(epoch));
            verify(mockErrorTriggerDispatchService).dispatchEpochFailure(any());
        }

        @Test
        @DisplayName("deferred reset defaults to hasFailures=true when snapshot read fails")
        void deferredResetDefaultsTrueOnSnapshotReadFailure() {
            String runId = "run-snap-err";
            String triggerId = "trigger:schedule";
            int epoch = 0;
            SignalWaitEntity signal = createInterfaceSignal(runId, "interface:report", "0", triggerId, epoch);

            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.RUNNING);
            when(run.isStepByStepMode()).thenReturn(false);
            lenient().when(run.getTenantId()).thenReturn("tenant-1");
            stubWorkflow(run);
            when(mockRunRepository.findByRunIdPublic(runId)).thenReturn(Optional.of(run));

            when(mockStepByStepService.getReadyNodes(runId, "0", epoch)).thenReturn(Set.of());
            when(mockSignalService.hasBlockingSignalsForDagAndEpoch(runId, triggerId, epoch)).thenReturn(false);
            when(run.getPlan()).thenReturn(Map.of("triggers", List.of(), "edges", List.of()));

            // Snapshot read throws (DB error, parse error, etc.)
            when(mockStateSnapshotService.getSnapshot(runId)).thenThrow(new RuntimeException("DB timeout"));

            resumeService.resumeAfterSignal(signal);

            // Fail-safe: defaults to true so bell notification fires even if snapshot unreadable
            verify(mockReusableTriggerService).resetForNextCycle(
                eq(run), any(), any(), eq(runId), any(), eq(triggerId), eq(true), eq(epoch));
            verify(mockErrorTriggerDispatchService).dispatchEpochFailure(any());
        }

        @Test
        @DisplayName("deferred reset passes hasFailures=false when epoch has no failed nodes")
        void deferredResetPassesFalseWhenNoFailures() {
            String runId = "run-clean";
            String triggerId = "trigger:webhook";
            int epoch = 0;
            SignalWaitEntity signal = createInterfaceSignal(runId, "interface:dashboard", "0", triggerId, epoch);

            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.RUNNING);
            when(run.isStepByStepMode()).thenReturn(false);
            lenient().when(run.getTenantId()).thenReturn("tenant-1");
            stubWorkflow(run);
            when(mockRunRepository.findByRunIdPublic(runId)).thenReturn(Optional.of(run));

            when(mockStepByStepService.getReadyNodes(runId, "0", epoch)).thenReturn(Set.of());
            when(mockSignalService.hasBlockingSignalsForDagAndEpoch(runId, triggerId, epoch)).thenReturn(false);
            when(run.getPlan()).thenReturn(Map.of("triggers", List.of(), "edges", List.of()));

            // Stub StateSnapshot with NO failed nodes
            var epochState = mock(com.apimarketplace.orchestrator.domain.execution.EpochState.class);
            when(epochState.getFailedNodeIds()).thenReturn(Set.of());
            var snapshot = mock(com.apimarketplace.orchestrator.domain.execution.StateSnapshot.class);
            when(snapshot.getEpochState(triggerId, epoch)).thenReturn(epochState);
            when(mockStateSnapshotService.getSnapshot(runId)).thenReturn(snapshot);

            resumeService.resumeAfterSignal(signal);

            verify(mockReusableTriggerService).resetForNextCycle(
                eq(run), any(), any(), eq(runId), any(), eq(triggerId), eq(false), eq(epoch));
            verify(mockErrorTriggerDispatchService, never()).dispatchEpochFailure(any());
        }
    }

    @Nested
    @DisplayName("filterOutAwaitingSignalNodes - epoch scoping")
    class FilterOutAwaitingSignalNodesTests {

        @SuppressWarnings("unchecked")
        private Set<String> invokeFilter(Set<String> readyNodes, String runId, int epoch) throws Exception {
            Method method = SignalResumeService.class.getDeclaredMethod(
                "filterOutAwaitingSignalNodes", Set.class, String.class, int.class);
            method.setAccessible(true);
            return (Set<String>) method.invoke(resumeService, readyNodes, runId, epoch);
        }

        @Test
        @DisplayName("should NOT filter out node when signal is from a different epoch")
        void shouldNotFilterWhenSignalFromDifferentEpoch() throws Exception {
            // interface:dashboard has a PENDING signal in epoch 2, but we're executing epoch 3.
            // The epoch-scoped query for epoch 3 returns empty - the stale signal is invisible.
            when(mockSignalService.getActiveSignals("run-1", 3)).thenReturn(List.of());

            Set<String> readyNodes = Set.of("interface:dashboard", "core:merge");
            Set<String> result = invokeFilter(readyNodes, "run-1", 3);

            assertThat(result).containsExactlyInAnyOrder("interface:dashboard", "core:merge");
        }

        @Test
        @DisplayName("should filter out node when signal IS in the same epoch")
        void shouldFilterWhenSignalInSameEpoch() throws Exception {
            SignalWaitEntity activeSignal = mock(SignalWaitEntity.class);
            when(activeSignal.getNodeId()).thenReturn("interface:dashboard");
            when(mockSignalService.getActiveSignals("run-1", 3)).thenReturn(List.of(activeSignal));

            Set<String> readyNodes = Set.of("interface:dashboard", "core:merge");
            Set<String> result = invokeFilter(readyNodes, "run-1", 3);

            assertThat(result).containsExactly("core:merge");
        }

        @Test
        @DisplayName("should return all nodes when no active signals exist")
        void shouldReturnAllWhenNoSignals() throws Exception {
            when(mockSignalService.getActiveSignals("run-1", 2)).thenReturn(List.of());

            Set<String> readyNodes = Set.of("core:a", "core:b", "interface:dashboard");
            Set<String> result = invokeFilter(readyNodes, "run-1", 2);

            assertThat(result).containsExactlyInAnyOrder("core:a", "core:b", "interface:dashboard");
        }

        @Test
        @DisplayName("should fallback to run-wide query when epoch is negative")
        void shouldFallbackWhenEpochNegative() throws Exception {
            SignalWaitEntity signal = mock(SignalWaitEntity.class);
            when(signal.getNodeId()).thenReturn("core:wait");
            when(mockSignalService.getActiveSignals("run-1")).thenReturn(List.of(signal));

            Set<String> readyNodes = Set.of("core:wait", "core:next");
            Set<String> result = invokeFilter(readyNodes, "run-1", -1);

            assertThat(result).containsExactly("core:next");
            verify(mockSignalService).getActiveSignals("run-1");
            verify(mockSignalService, never()).getActiveSignals(eq("run-1"), anyInt());
        }

        @Test
        @DisplayName("should return empty set unchanged")
        void shouldReturnEmptyUnchanged() throws Exception {
            Set<String> result = invokeFilter(Set.of(), "run-1", 3);
            assertThat(result).isEmpty();
            verify(mockSignalService, never()).getActiveSignals(any());
            verify(mockSignalService, never()).getActiveSignals(any(), anyInt());
        }
    }

    @Nested
    @DisplayName("filterOutAlreadyTerminalNodes - resolve-all re-trigger guard")
    @MockitoSettings(strictness = Strictness.LENIENT)
    class FilterOutAlreadyTerminalNodesTests {

        @SuppressWarnings("unchecked")
        private Set<String> invokeFilter(Set<String> nodes, String runId, SignalWaitEntity signal, int epoch) throws Exception {
            Method method = SignalResumeService.class.getDeclaredMethod(
                "filterOutAlreadyTerminalNodes", Set.class, String.class, SignalWaitEntity.class, int.class);
            method.setAccessible(true);
            return (Set<String>) method.invoke(resumeService, nodes, runId, signal, epoch);
        }

        @Test
        @DisplayName("BUG FIX: drops successors the snapshot reports terminal for the signal's trigger+epoch")
        void dropsTerminalSuccessors() throws Exception {
            SignalWaitEntity signal = mock(SignalWaitEntity.class);
            when(signal.getDagTriggerId()).thenReturn("trigger:start");

            var snapshot = mock(com.apimarketplace.orchestrator.domain.execution.StateSnapshot.class);
            when(snapshot.getTerminalNodeIds("trigger:start", 2))
                .thenReturn(Set.of("core:send_mail", "core:done", "core:skipped"));
            when(mockStateSnapshotService.getSnapshot("run-1")).thenReturn(snapshot);

            // A re-run of the resolve-all burst sees the successor as already terminal:
            // it must NOT be re-triggered (the duplicate send_email / HTTP bug).
            Set<String> result = invokeFilter(
                Set.of("core:send_mail", "core:done", "core:skipped", "core:fresh"), "run-1", signal, 2);

            assertThat(result).containsExactly("core:fresh");
        }

        @Test
        @DisplayName("keeps a successor the snapshot does NOT report terminal (first resume runs it)")
        void keepsNonTerminalSuccessor() throws Exception {
            SignalWaitEntity signal = mock(SignalWaitEntity.class);
            when(signal.getDagTriggerId()).thenReturn("trigger:start");

            var snapshot = mock(com.apimarketplace.orchestrator.domain.execution.StateSnapshot.class);
            when(snapshot.getTerminalNodeIds("trigger:start", 2)).thenReturn(Set.of());
            when(mockStateSnapshotService.getSnapshot("run-1")).thenReturn(snapshot);

            Set<String> result = invokeFilter(Set.of("core:send_mail"), "run-1", signal, 2);

            assertThat(result).containsExactly("core:send_mail");
        }

        @Test
        @DisplayName("delegates the terminal-set lookup to the snapshot with the signal's DAG trigger + epoch")
        void passesSignalTriggerAndEpoch() throws Exception {
            SignalWaitEntity signal = mock(SignalWaitEntity.class);
            when(signal.getDagTriggerId()).thenReturn("trigger:webhook");

            var snapshot = mock(com.apimarketplace.orchestrator.domain.execution.StateSnapshot.class);
            when(snapshot.getTerminalNodeIds("trigger:webhook", 3)).thenReturn(Set.of("core:x"));
            when(mockStateSnapshotService.getSnapshot("run-1")).thenReturn(snapshot);

            // The trigger/epoch gating itself lives on StateSnapshot.getTerminalNodeIds (tested in
            // StateSnapshotTest); here we only prove the filter passes the signal's values through.
            Set<String> result = invokeFilter(Set.of("core:x", "core:y"), "run-1", signal, 3);

            assertThat(result).containsExactly("core:y");
            verify(snapshot).getTerminalNodeIds("trigger:webhook", 3);
        }

        @Test
        @DisplayName("returns the empty set unchanged without touching the snapshot")
        void emptyInputUnchanged() throws Exception {
            SignalWaitEntity signal = mock(SignalWaitEntity.class);
            Set<String> result = invokeFilter(Set.of(), "run-1", signal, 2);
            assertThat(result).isEmpty();
            verify(mockStateSnapshotService, never()).getSnapshot(any());
        }

        @Test
        @DisplayName("fails open (returns the input) if the snapshot read throws")
        void failsOpenOnSnapshotError() throws Exception {
            SignalWaitEntity signal = mock(SignalWaitEntity.class);
            when(signal.getDagTriggerId()).thenReturn("trigger:start");
            when(mockStateSnapshotService.getSnapshot("run-1")).thenThrow(new RuntimeException("boom"));

            Set<String> result = invokeFilter(Set.of("core:send_mail"), "run-1", signal, 2);
            assertThat(result).containsExactly("core:send_mail");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // buildSignalResolutionOutput - agent result flattening
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("buildSignalResolutionOutput - agent async")
    class BuildSignalResolutionOutputAgentAsync {

        @SuppressWarnings("unchecked")
        private Map<String, Object> invokeBuild(SignalWaitEntity signal) throws Exception {
            Method method = SignalResumeService.class.getDeclaredMethod(
                "buildSignalResolutionOutput", SignalWaitEntity.class);
            method.setAccessible(true);
            return (Map<String, Object>) method.invoke(resumeService, signal);
        }

        @Test
        @DisplayName("should flatten agent result map for AGENT_COMPLETED")
        void shouldFlattenAgentResultForCompleted() throws Exception {
            SignalWaitEntity signal = mock(SignalWaitEntity.class);
            when(signal.getSignalType()).thenReturn(SignalType.AGENT_EXECUTION);
            when(signal.getResolution()).thenReturn(SignalResolution.AGENT_COMPLETED);
            when(signal.getResolvedAt()).thenReturn(Instant.now());
            when(signal.getResolvedBy()).thenReturn("batched-signal-resolver");

            // Mimic AgentResultMessage.toResolutionData() structure
            Map<String, Object> resolutionData = new HashMap<>();
            resolutionData.put("correlationId", "corr-123");
            resolutionData.put("agentType", "guardrail");
            resolutionData.put("success", true);
            resolutionData.put("result", Map.of("passed", true, "response", "Content is safe"));
            when(signal.getResolutionData()).thenReturn(resolutionData);

            Map<String, Object> payload = invokeBuild(signal);

            // Payload is wrapped: {"output": {...}}
            assertThat(payload).containsKey("output");
            Map<String, Object> output = (Map<String, Object>) payload.get("output");

            // Agent result fields should be at top level (flattened)
            assertThat(output).containsEntry("passed", true);
            assertThat(output).containsEntry("response", "Content is safe");
            // Original top-level fields preserved
            assertThat(output).containsEntry("correlationId", "corr-123");
            assertThat(output).containsEntry("agentType", "guardrail");
            assertThat(output).containsEntry("success", true);
        }

        @Test
        @DisplayName("should flatten classify result with selected_category")
        void shouldFlattenClassifyResult() throws Exception {
            SignalWaitEntity signal = mock(SignalWaitEntity.class);
            when(signal.getSignalType()).thenReturn(SignalType.AGENT_EXECUTION);
            when(signal.getResolution()).thenReturn(SignalResolution.AGENT_COMPLETED);
            when(signal.getResolvedAt()).thenReturn(Instant.now());
            when(signal.getResolvedBy()).thenReturn("batched-signal-resolver");

            Map<String, Object> resolutionData = new HashMap<>();
            resolutionData.put("correlationId", "corr-456");
            resolutionData.put("agentType", "classify");
            resolutionData.put("success", true);
            resolutionData.put("result", Map.of("selected_category", "billing", "confidence", 0.95));
            when(signal.getResolutionData()).thenReturn(resolutionData);

            Map<String, Object> payload = invokeBuild(signal);
            Map<String, Object> output = (Map<String, Object>) payload.get("output");

            assertThat(output).containsEntry("selected_category", "billing");
            assertThat(output).containsEntry("confidence", 0.95);
        }

        @Test
        @DisplayName("should use putIfAbsent to avoid overwriting top-level keys")
        void shouldNotOverwriteTopLevelKeys() throws Exception {
            SignalWaitEntity signal = mock(SignalWaitEntity.class);
            when(signal.getSignalType()).thenReturn(SignalType.AGENT_EXECUTION);
            when(signal.getResolution()).thenReturn(SignalResolution.AGENT_COMPLETED);
            when(signal.getResolvedAt()).thenReturn(Instant.now());
            when(signal.getResolvedBy()).thenReturn("system");

            // "success" exists at both levels - top-level should win
            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("success", false); // agent-specific success
            resultMap.put("passed", true);

            Map<String, Object> resolutionData = new HashMap<>();
            resolutionData.put("success", true); // overall execution success
            resolutionData.put("result", resultMap);
            when(signal.getResolutionData()).thenReturn(resolutionData);

            Map<String, Object> payload = invokeBuild(signal);
            Map<String, Object> output = (Map<String, Object>) payload.get("output");

            // Top-level "success" (true) should NOT be overwritten by nested "success" (false)
            assertThat(output).containsEntry("success", true);
            // But "passed" should be present from flattening
            assertThat(output).containsEntry("passed", true);
        }

        @Test
        @DisplayName("should handle AGENT_FAILED with null result map")
        void shouldHandleFailedWithNullResult() throws Exception {
            SignalWaitEntity signal = mock(SignalWaitEntity.class);
            when(signal.getSignalType()).thenReturn(SignalType.AGENT_EXECUTION);
            when(signal.getResolution()).thenReturn(SignalResolution.AGENT_FAILED);
            when(signal.getResolvedAt()).thenReturn(Instant.now());
            when(signal.getResolvedBy()).thenReturn("system");

            Map<String, Object> resolutionData = new HashMap<>();
            resolutionData.put("success", false);
            resolutionData.put("errorMessage", "Agent timed out");
            // No "result" key at all
            when(signal.getResolutionData()).thenReturn(resolutionData);

            Map<String, Object> payload = invokeBuild(signal);
            Map<String, Object> output = (Map<String, Object>) payload.get("output");

            assertThat(output).containsEntry("success", false);
            assertThat(output).containsEntry("errorMessage", "Agent timed out");
            assertThat(output).containsEntry("resolution", "AGENT_FAILED");
        }

        @Test
        @DisplayName("should NOT flatten for non-agent resolutions")
        void shouldNotFlattenForNonAgentResolutions() throws Exception {
            SignalWaitEntity signal = mock(SignalWaitEntity.class);
            when(signal.getSignalType()).thenReturn(SignalType.WAIT_TIMER);
            when(signal.getResolution()).thenReturn(SignalResolution.COMPLETED);
            when(signal.getResolvedAt()).thenReturn(Instant.now());
            when(signal.getResolvedBy()).thenReturn("system");
            when(signal.getResolutionData()).thenReturn(null);

            Map<String, Object> payload = invokeBuild(signal);
            Map<String, Object> output = (Map<String, Object>) payload.get("output");

            assertThat(output).containsEntry("resolution", "COMPLETED");
            assertThat(output).doesNotContainKey("passed");
            assertThat(output).doesNotContainKey("selected_category");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // #W1: buildSignalResolutionOutput - WAIT_TIMER timestamp contract
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("#W1 buildSignalResolutionOutput - WAIT_TIMER timestamps")
    class BuildSignalResolutionOutputWaitTimer {

        @SuppressWarnings("unchecked")
        private Map<String, Object> invokeBuild(SignalWaitEntity signal) throws Exception {
            Method method = SignalResumeService.class.getDeclaredMethod(
                "buildSignalResolutionOutput", SignalWaitEntity.class);
            method.setAccessible(true);
            return (Map<String, Object>) method.invoke(resumeService, signal);
        }

        @Test
        @DisplayName("WAIT_TIMER resume output matches inline Wait contract (waited_ms, started_at, completed_at, status, node_type, item_*)")
        void waitTimerOutputMatchesInlineContract() throws Exception {
            Instant waitStart = Instant.parse("2026-04-16T10:00:00Z");
            Instant waitEnd = Instant.parse("2026-04-16T10:00:07Z");

            SignalWaitEntity signal = mock(SignalWaitEntity.class);
            when(signal.getSignalType()).thenReturn(SignalType.WAIT_TIMER);
            when(signal.getResolution()).thenReturn(SignalResolution.COMPLETED);
            when(signal.getCreatedAt()).thenReturn(waitStart);
            when(signal.getResolvedAt()).thenReturn(waitEnd);
            when(signal.getResolvedBy()).thenReturn("system");
            when(signal.getItemId()).thenReturn("3");
            // SignalConfig.timer() stores "durationMs" in the config map
            Map<String, Object> cfg = new HashMap<>();
            cfg.put("type", "WAIT_TIMER");
            cfg.put("durationMs", 7000L);
            when(signal.getSignalConfig()).thenReturn(cfg);

            Map<String, Object> payload = invokeBuild(signal);
            Map<String, Object> output = (Map<String, Object>) payload.get("output");

            // Full inline contract (WaitNode.buildWaitOutput)
            assertThat(output)
                .containsEntry("started_at", waitStart.toString())
                .containsEntry("completed_at", waitEnd.toString())
                .containsEntry("status", "completed")
                .containsEntry("waited_ms", 7000L)
                .containsEntry("node_type", "WAIT")
                .containsEntry("item_id", "3")
                .containsEntry("item_index", 3)
                .containsEntry("itemIndex", 3);
            assertThat(output.get("resolved_params")).isInstanceOf(Map.class);
            assertThat((Map<String, Object>) output.get("resolved_params"))
                .containsEntry("duration", 7000L);
        }

        @Test
        @DisplayName("WAIT_TIMER with missing signalConfig falls back to durationMs=0 (helper still emits keys)")
        void waitTimerMissingSignalConfig() throws Exception {
            Instant waitStart = Instant.parse("2026-04-16T10:00:00Z");
            Instant waitEnd = Instant.parse("2026-04-16T10:00:05Z");

            SignalWaitEntity signal = mock(SignalWaitEntity.class);
            when(signal.getSignalType()).thenReturn(SignalType.WAIT_TIMER);
            when(signal.getResolution()).thenReturn(SignalResolution.COMPLETED);
            when(signal.getCreatedAt()).thenReturn(waitStart);
            when(signal.getResolvedAt()).thenReturn(waitEnd);
            when(signal.getResolvedBy()).thenReturn("system");
            when(signal.getSignalConfig()).thenReturn(null); // missing config
            when(signal.getItemId()).thenReturn(null);

            Map<String, Object> payload = invokeBuild(signal);
            Map<String, Object> output = (Map<String, Object>) payload.get("output");

            // Key set is still complete - durationMs defaults to 0 rather than throwing
            assertThat(output)
                .containsEntry("waited_ms", 0L)
                .containsEntry("status", "completed")
                .containsEntry("node_type", "WAIT")
                .containsEntry("item_index", 0)
                .containsEntry("itemIndex", 0);
            assertThat(output).containsKey("item_id");
            assertThat(output.get("item_id")).isNull();
        }

        @Test
        @DisplayName("WAIT_TIMER with null createdAt omits started_at (helper guards null) but still emits completed_at")
        void waitTimerWithNullCreatedAt() throws Exception {
            Instant waitEnd = Instant.parse("2026-04-16T10:00:07Z");

            SignalWaitEntity signal = mock(SignalWaitEntity.class);
            when(signal.getSignalType()).thenReturn(SignalType.WAIT_TIMER);
            when(signal.getResolution()).thenReturn(SignalResolution.COMPLETED);
            when(signal.getCreatedAt()).thenReturn(null);
            when(signal.getResolvedAt()).thenReturn(waitEnd);
            when(signal.getResolvedBy()).thenReturn("system");
            Map<String, Object> cfg = new HashMap<>();
            cfg.put("durationMs", 5000L);
            when(signal.getSignalConfig()).thenReturn(cfg);
            when(signal.getItemId()).thenReturn("0");

            Map<String, Object> payload = invokeBuild(signal);
            Map<String, Object> output = (Map<String, Object>) payload.get("output");

            assertThat(output)
                .doesNotContainKey("started_at")
                .containsEntry("completed_at", waitEnd.toString())
                .containsEntry("waited_ms", 5000L)
                .containsEntry("status", "completed");
        }

        @Test
        @DisplayName("WAIT_TIMER with null resolvedAt omits completed_at (helper guards null)")
        void waitTimerWithNullResolvedAt() throws Exception {
            Instant waitStart = Instant.parse("2026-04-16T10:00:00Z");

            SignalWaitEntity signal = mock(SignalWaitEntity.class);
            when(signal.getSignalType()).thenReturn(SignalType.WAIT_TIMER);
            when(signal.getResolution()).thenReturn(SignalResolution.COMPLETED);
            when(signal.getCreatedAt()).thenReturn(waitStart);
            // resolvedAt is null - unusual but code must not NPE. The outer
            // `output.put("resolved_at", ...)` path falls back to Instant.now(),
            // but the helper's completed_at must be omitted (null-guarded).
            when(signal.getResolvedAt()).thenReturn(null);
            when(signal.getResolvedBy()).thenReturn("system");
            Map<String, Object> cfg = new HashMap<>();
            cfg.put("durationMs", 4000L);
            when(signal.getSignalConfig()).thenReturn(cfg);
            when(signal.getItemId()).thenReturn("0");

            Map<String, Object> payload = invokeBuild(signal);
            Map<String, Object> output = (Map<String, Object>) payload.get("output");

            assertThat(output)
                .containsEntry("started_at", waitStart.toString())
                .doesNotContainKey("completed_at")
                .containsEntry("waited_ms", 4000L)
                .containsEntry("status", "completed");
        }

        @Test
        @DisplayName("non-WAIT_TIMER signals do NOT emit Wait contract keys (agent/approval paths unchanged)")
        void nonWaitTimerDoesNotEmitWaitContract() throws Exception {
            SignalWaitEntity signal = mock(SignalWaitEntity.class);
            when(signal.getSignalType()).thenReturn(SignalType.USER_APPROVAL);
            when(signal.getResolution()).thenReturn(SignalResolution.APPROVED);
            when(signal.getResolvedAt()).thenReturn(Instant.now());
            when(signal.getResolvedBy()).thenReturn("alice");

            Map<String, Object> payload = invokeBuild(signal);
            Map<String, Object> output = (Map<String, Object>) payload.get("output");

            assertThat(output)
                .doesNotContainKey("started_at")
                .doesNotContainKey("completed_at")
                .doesNotContainKey("waited_ms")
                .doesNotContainKey("node_type")
                // USER_APPROVAL does not add the "status" key - only WAIT_TIMER branch does
                .doesNotContainKey("status");
        }
    }

    // ========================================================================
    // Redis Dedup & Distributed Lock - Horizontal Scaling
    // ========================================================================

    @Nested
    @DisplayName("Redis dedup and distributed lock")
    @MockitoSettings(strictness = Strictness.LENIENT)
    class RedisDistributedTests {

        @Test
        @DisplayName("should skip duplicate signal when Redis dedup returns false")
        void shouldSkipDuplicateSignal() {
            SignalWaitEntity signal = createMockSignal("run-1", "core:wait", "0");
            // Dedup returns false → signal already processed by another instance
            // Override the global lenient stub for this specific test
            lenient().when(mockValueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

            resumeService.resumeAfterSignal(signal);

            // Should not even look up the run - dedup short-circuits
            verify(mockRunRepository, never()).findByRunIdPublic(any());
        }

        @Test
        @DisplayName("regression: dedup key includes run id so reused signal ids do not block resume")
        void shouldScopeDedupKeyByRunIdAndSignalId() {
            SignalWaitEntity signal = createMockSignal("run-a", "core:wait", "0");
            when(signal.getId()).thenReturn(42L);
            when(mockRunRepository.findByRunIdPublic("run-a")).thenReturn(Optional.empty());

            resumeService.resumeAfterSignal(signal);

            verify(mockValueOps).setIfAbsent(
                eq(RedisCacheKeys.signalDedup("run-a", 42L)),
                eq("1"),
                any(Duration.class));
        }

        @Test
        @DisplayName("should proceed when Redis dedup throws exception (graceful degradation)")
        void shouldProceedWhenRedisDedupFails() {
            SignalWaitEntity signal = createMockSignal("run-1", "core:wait", "0");
            // Redis dedup throws → graceful degradation, proceed to run lookup
            lenient().when(mockValueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("Connection refused"));
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.empty());

            resumeService.resumeAfterSignal(signal);

            // Should still attempt to find the run (graceful degradation)
            verify(mockRunRepository).findByRunIdPublic("run-1");
        }

        @Test
        @DisplayName("should proceed when Redis dedup returns null (Redis edge case)")
        void shouldProceedWhenRedisDedupReturnsNull() {
            SignalWaitEntity signal = createMockSignal("run-1", "core:wait", "0");
            // Redis returns null (unexpected) - !Boolean.TRUE.equals(null) is true, so treated as duplicate
            // null means SETNX returned null which means key already existed or error.
            // The current code treats null as "not first caller" - this is correct behavior.
            lenient().when(mockValueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(null);

            resumeService.resumeAfterSignal(signal);

            // null treated as "already processed" - consistent with Redis semantics
            verify(mockRunRepository, never()).findByRunIdPublic(any());
        }

        @Test
        @DisplayName("signal-resume lock waits through contention and uses long TTL")
        void signalResumeLockWaitsThroughContentionAndUsesLongTtl() throws Exception {
            lenient().when(mockValueOps.setIfAbsent(eq("signal-lock"), eq("owner-1"), eq(Duration.ofMinutes(70))))
                .thenReturn(false, true);

            boolean acquired = invokeTryAcquireDistributedLock(
                "signal-lock", "owner-1", Duration.ofMinutes(70), Duration.ofMillis(1));

            assertThat(acquired).isTrue();
            verify(mockValueOps, times(2))
                .setIfAbsent("signal-lock", "owner-1", Duration.ofMinutes(70));
        }

        @Test
        @DisplayName("signal-resume lock reports failure on Redis error instead of allowing unlocked execution")
        void signalResumeLockReportsFailureOnRedisError() throws Exception {
            lenient().when(mockValueOps.setIfAbsent(eq("signal-lock"), eq("owner-1"), any(Duration.class)))
                .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("Connection refused"));

            boolean acquired = invokeTryAcquireDistributedLock(
                "signal-lock", "owner-1", Duration.ofMinutes(70), Duration.ofMillis(1));

            assertThat(acquired).isFalse();
        }

        private boolean invokeTryAcquireDistributedLock(
                String key, String owner, Duration ttl, Duration warnInterval) throws Exception {
            Method method = SignalResumeService.class.getDeclaredMethod(
                "tryAcquireDistributedLock", String.class, String.class, Duration.class, Duration.class);
            method.setAccessible(true);
            return (boolean) method.invoke(resumeService, key, owner, ttl, warnInterval);
        }
    }

    @Nested
    @DisplayName("splitSignalsAllResolved")
    @MockitoSettings(strictness = Strictness.LENIENT)
    class SplitSignalsAllResolvedTests {

        @Test
        @DisplayName("should pass parent itemId when all split signals resolved for a node")
        void shouldPassParentItemIdWhenAllSplitSignalsResolved() {
            // Setup: last split item's signal resolves (itemId="4"), no remaining active signals
            SignalWaitEntity signal = createMockSignal("run-1", "core:user_approval", "4");
            when(signal.getSignalType()).thenReturn(SignalType.USER_APPROVAL);
            when(signal.getResolution()).thenReturn(SignalResolution.APPROVED);
            when(signal.getSplitItemData()).thenReturn(Map.of("workflowItemIndex", 7));
            when(signal.getEpoch()).thenReturn(1);
            when(signal.getDagTriggerId()).thenReturn("trigger:start");

            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.RUNNING);
            when(run.isStepByStepMode()).thenReturn(false);
            when(run.getRunIdPublic()).thenReturn("run-1");
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            // Split context: multiple signals registered for this node in epoch 1
            when(mockSignalService.isSplitContextNode("run-1", "core:user_approval", 1)).thenReturn(true);
            when(mockSignalService.getActiveSignals("run-1", 1)).thenReturn(List.of());
            when(mockSignalService.getSignalCountForNodeEpoch("run-1", "core:user_approval", 1))
                .thenReturn(3L);
            when(mockStepDataRepository.countByRunIdAndNormalizedKeyAndEpochAndStatus(
                "run-1", "core:user_approval", 1, "COMPLETED"))
                .thenReturn(3L);

            // Ready nodes: first call returns core:wait, subsequent calls return empty (after execution)
            when(mockStepByStepService.getReadyNodes("run-1", "4", 1))
                .thenReturn(Set.of("core:wait"))
                .thenReturn(Set.of());

            // Execute the successor at the parent workflow item scope.
            var mockResult = mock(com.apimarketplace.orchestrator.execution.v2.engine.StepByStepExecutionResult.class);
            when(mockResult.workflowComplete()).thenReturn(false);
            when(mockResult.readyNodes()).thenReturn(Set.of());
            when(mockResult.isPending()).thenReturn(false);
            when(mockResult.isSuccess()).thenReturn(true);
            when(mockStepByStepService.executeNode(eq("run-1"), eq("core:wait"), eq("7"), eq(1), eq("trigger:start")))
                .thenReturn(mockResult);

            resumeService.resumeAfterSignal(signal);

            // Key assertion: executeNode called with parent itemId (not the signal sub-item "4").
            verify(mockStepByStepService).executeNode("run-1", "core:wait", "7", 1, "trigger:start");
        }

        @Test
        @DisplayName("should derive parent itemId from scoped split signal item when workflowItemIndex is absent")
        void shouldDeriveParentItemIdFromScopedSplitSignalItem() {
            SignalWaitEntity signal = createMockSignal("run-legacy-split", "core:user_approval", "7.4");
            when(signal.getSignalType()).thenReturn(SignalType.USER_APPROVAL);
            when(signal.getResolution()).thenReturn(SignalResolution.APPROVED);
            when(signal.getSplitItemData()).thenReturn(null);
            when(signal.getEpoch()).thenReturn(1);
            when(signal.getDagTriggerId()).thenReturn("trigger:start");

            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.RUNNING);
            when(run.isStepByStepMode()).thenReturn(false);
            when(run.getRunIdPublic()).thenReturn("run-legacy-split");
            when(mockRunRepository.findByRunIdPublic("run-legacy-split")).thenReturn(Optional.of(run));

            when(mockSignalService.isSplitContextNode("run-legacy-split", "core:user_approval", 1)).thenReturn(true);
            when(mockSignalService.getActiveSignals("run-legacy-split", 1)).thenReturn(List.of());
            when(mockSignalService.getSignalCountForNodeEpoch("run-legacy-split", "core:user_approval", 1))
                .thenReturn(3L);
            when(mockStepDataRepository.countByRunIdAndNormalizedKeyAndEpochAndStatus(
                "run-legacy-split", "core:user_approval", 1, "COMPLETED"))
                .thenReturn(3L);

            when(mockStepByStepService.getReadyNodes("run-legacy-split", "7.4", 1))
                .thenReturn(Set.of("core:wait"))
                .thenReturn(Set.of());

            var mockResult = mock(StepByStepExecutionResult.class);
            when(mockResult.workflowComplete()).thenReturn(false);
            when(mockResult.readyNodes()).thenReturn(Set.of());
            when(mockResult.isPending()).thenReturn(false);
            when(mockResult.isSuccess()).thenReturn(true);
            when(mockStepByStepService.executeNode("run-legacy-split", "core:wait", "7", 1, "trigger:start"))
                .thenReturn(mockResult);

            resumeService.resumeAfterSignal(signal);

            verify(mockStepByStepService).executeNode("run-legacy-split", "core:wait", "7", 1, "trigger:start");
        }

        @Test
        @DisplayName("should keep original itemId when other split signals still pending")
        void shouldKeepItemIdWhenSignalsStillPending() {
            // Setup: signal resolves for item 2, but items 3 and 4 still pending
            SignalWaitEntity signal = createMockSignal("run-1", "core:user_approval", "2");
            when(signal.getSignalType()).thenReturn(SignalType.USER_APPROVAL);
            when(signal.getResolution()).thenReturn(SignalResolution.APPROVED);
            when(signal.getSplitItemData()).thenReturn(null);
            when(signal.getEpoch()).thenReturn(1);
            when(signal.getDagTriggerId()).thenReturn("trigger:start");

            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.RUNNING);
            when(run.isStepByStepMode()).thenReturn(false);
            when(run.getRunIdPublic()).thenReturn("run-1");
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            // Still has remaining active signals
            SignalWaitEntity remaining1 = mock(SignalWaitEntity.class);
            SignalWaitEntity remaining2 = mock(SignalWaitEntity.class);
            when(mockSignalService.getActiveSignalsForNode("run-1", "core:user_approval"))
                .thenReturn(List.of(remaining1, remaining2));

            // No ready nodes (other items still pending)
            when(mockStepByStepService.getReadyNodes("run-1", "2", 1))
                .thenReturn(Set.of());

            resumeService.resumeAfterSignal(signal);

            // Should NOT call executeNode at all (no ready nodes, other signals pending)
            verify(mockStepByStepService, never()).executeNode(
                anyString(), anyString(), any(), anyInt(), anyString());
        }

        @Test
        @DisplayName("should suppress ready successors when other split signals still pending")
        void shouldSuppressReadySuccessorsWhenSignalsStillPending() {
            SignalWaitEntity signal = createMockSignal("run-1", "core:user_approval", "2");
            when(signal.getSignalType()).thenReturn(SignalType.USER_APPROVAL);
            when(signal.getResolution()).thenReturn(SignalResolution.APPROVED);
            when(signal.getSplitItemData()).thenReturn(null);
            when(signal.getEpoch()).thenReturn(1);
            when(signal.getDagTriggerId()).thenReturn("trigger:start");

            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.RUNNING);
            when(run.isStepByStepMode()).thenReturn(false);
            when(run.getRunIdPublic()).thenReturn("run-1");
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            SignalWaitEntity remaining = mock(SignalWaitEntity.class);
            when(remaining.getNodeId()).thenReturn("core:user_approval");

            when(mockSignalService.isSplitContextNode("run-1", "core:user_approval", 1)).thenReturn(true);
            when(mockSignalService.getActiveSignals("run-1", 1)).thenReturn(List.of(remaining));

            when(mockStepByStepService.getReadyNodes("run-1", "2", 1))
                .thenReturn(Set.of("mcp:after_approval"));

            resumeService.resumeAfterSignal(signal);

            verify(mockStepByStepService, never()).executeNode(
                anyString(), anyString(), any(), anyInt(), anyString());
        }

        @Test
        @DisplayName("should wait for every split signal output before executing successors")
        void shouldWaitForEverySplitSignalOutputBeforeExecutingSuccessors() {
            SignalWaitEntity signal = createMockSignal("run-1", "core:user_approval", "2");
            when(signal.getSignalType()).thenReturn(SignalType.USER_APPROVAL);
            when(signal.getResolution()).thenReturn(SignalResolution.APPROVED);
            when(signal.getSplitItemData()).thenReturn(null);
            when(signal.getEpoch()).thenReturn(1);
            when(signal.getDagTriggerId()).thenReturn("trigger:start");

            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.RUNNING);
            when(run.isStepByStepMode()).thenReturn(false);
            when(run.getRunIdPublic()).thenReturn("run-1");
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            when(mockSignalService.isSplitContextNode("run-1", "core:user_approval", 1)).thenReturn(true);
            when(mockSignalService.getActiveSignals("run-1", 1)).thenReturn(List.of());
            when(mockSignalService.getSignalCountForNodeEpoch("run-1", "core:user_approval", 1))
                .thenReturn(3L);
            when(mockStepDataRepository.countByRunIdAndNormalizedKeyAndEpochAndStatus(
                "run-1", "core:user_approval", 1, "COMPLETED"))
                .thenReturn(2L);
            when(mockSignalService.hasBlockingSignalsForDagAndEpoch("run-1", "trigger:start", 1))
                .thenReturn(false);

            when(mockStepByStepService.getReadyNodes("run-1", "2", 1))
                .thenReturn(Set.of("mcp:after_approval"));

            resumeService.resumeAfterSignal(signal);

            verify(mockStepByStepService, never()).executeNode(
                anyString(), anyString(), any(), anyInt(), anyString());
            verify(mockSignalService, never()).hasBlockingSignalsForDagAndEpoch(
                "run-1", "trigger:start", 1);
        }

        @Test
        @DisplayName("should persist missing resolved split signal outputs before executing successors")
        void shouldPersistMissingResolvedSplitSignalOutputsBeforeExecutingSuccessors() {
            SignalWaitEntity signal = createMockSignal("run-1", "core:user_approval", "2");
            when(signal.getSignalType()).thenReturn(SignalType.USER_APPROVAL);
            when(signal.getResolution()).thenReturn(SignalResolution.APPROVED);
            when(signal.getSplitItemData()).thenReturn(null);
            when(signal.getEpoch()).thenReturn(1);
            when(signal.getDagTriggerId()).thenReturn("trigger:start");

            SignalWaitEntity item0 = createMockSignal("run-1", "core:user_approval", "0");
            when(item0.getSignalType()).thenReturn(SignalType.USER_APPROVAL);
            when(item0.getResolution()).thenReturn(SignalResolution.APPROVED);
            when(item0.getSplitItemData()).thenReturn(null);
            when(item0.getEpoch()).thenReturn(1);
            when(item0.getDagTriggerId()).thenReturn("trigger:start");

            SignalWaitEntity missingItem1 = createMockSignal("run-1", "core:user_approval", "1");
            when(missingItem1.getSignalType()).thenReturn(SignalType.USER_APPROVAL);
            when(missingItem1.getResolution()).thenReturn(SignalResolution.APPROVED);
            when(missingItem1.getSplitItemData()).thenReturn(null);
            when(missingItem1.getEpoch()).thenReturn(1);
            when(missingItem1.getDagTriggerId()).thenReturn("trigger:start");

            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.RUNNING);
            when(run.isStepByStepMode()).thenReturn(false);
            when(run.getRunIdPublic()).thenReturn("run-1");
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            when(mockSignalService.isSplitContextNode("run-1", "core:user_approval", 1)).thenReturn(true);
            when(mockSignalService.getActiveSignals("run-1", 1)).thenReturn(List.of());
            when(mockSignalService.getSignalCountForNodeEpoch("run-1", "core:user_approval", 1))
                .thenReturn(3L);
            when(mockStepDataRepository.countByRunIdAndNormalizedKeyAndEpochAndStatus(
                "run-1", "core:user_approval", 1, "COMPLETED"))
                .thenReturn(2L)
                .thenReturn(3L);
            when(mockSignalService.getResolvedSignalsForNodeEpoch("run-1", "core:user_approval", 1))
                .thenReturn(List.of(item0, missingItem1, signal));
            when(mockStepDataRepository.findCompletedItemIndicesByEpoch("run-1", "core:user_approval", 1))
                .thenReturn(List.of(0, 2));

            when(mockStepByStepService.getReadyNodes("run-1", "2", 1))
                .thenReturn(Set.of("mcp:after_approval"))
                .thenReturn(Set.of());

            var mockResult = mock(StepByStepExecutionResult.class);
            when(mockResult.workflowComplete()).thenReturn(false);
            when(mockResult.readyNodes()).thenReturn(Set.of());
            when(mockResult.isPending()).thenReturn(false);
            when(mockResult.isSuccess()).thenReturn(true);
            when(mockStepByStepService.executeNode("run-1", "mcp:after_approval", "0", 1, "trigger:start"))
                .thenReturn(mockResult);

            resumeService.resumeAfterSignal(signal);

            verify(mockSignalService).getResolvedSignalsForNodeEpoch("run-1", "core:user_approval", 1);
            verify(mockStepByStepService).executeNode("run-1", "mcp:after_approval", "0", 1, "trigger:start");
        }

        /**
         * Regression for CE-WF-OUTPUT-015 (2026-06-10): split → per-item USER_APPROVAL →
         * approved/rejected paths. After the split gate suppressed successors (sibling item
         * signals still pending), the signal-successor FALLBACK still ran and - because the
         * remaining siblings had just resolved on the HTTP thread, flipping the approval
         * node to COMPLETED in EpochState mid-resume - saw canExecute=true and executed
         * core:approved_path ONCE as a single non-split node with this resume's itemId.
         * The later all-resolved fan-out (parent itemId → one split-aware execution) then
         * hit the idempotent-skip guard: 2 approved items produced only 1 approved_path
         * completion. The fallback must honor the split-context deferral.
         */
        @Test
        @DisplayName("regression: split sibling signals pending - successor fallback must not fire (suppress branch)")
        void splitSiblingSignalsPendingMustNotTriggerSuccessorFallbackWhenReadyNodesSuppressed() {
            SignalWaitEntity signal = createMockSignal("run-split-fb", "core:item_approval", "1");
            when(signal.getSignalType()).thenReturn(SignalType.USER_APPROVAL);
            when(signal.getResolution()).thenReturn(SignalResolution.APPROVED);
            when(signal.getSplitItemData()).thenReturn(null);
            when(signal.getEpoch()).thenReturn(1);
            when(signal.getDagTriggerId()).thenReturn("trigger:start");
            when(signal.getCreatedAt()).thenReturn(Instant.parse("2026-06-10T18:14:25Z"));
            when(signal.getResolvedAt()).thenReturn(Instant.parse("2026-06-10T18:14:26Z"));

            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.RUNNING);
            when(run.isStepByStepMode()).thenReturn(false);
            when(run.getTenantId()).thenReturn("tenant-1");
            when(run.getId()).thenReturn(UUID.fromString("11111111-1111-1111-1111-111111111111"));
            when(run.getRunIdPublic()).thenReturn("run-split-fb");
            when(mockRunRepository.findByRunIdPublic("run-split-fb")).thenReturn(Optional.of(run));
            when(mockStorageService.saveJsonWithContext(
                anyString(), anyMap(), anyString(), any(), any(), anyString(), anyString(),
                anyInt(), anyInt(), any(), anyString()))
                .thenReturn(UUID.fromString("22222222-2222-2222-2222-222222222222"));

            // Split gate: this node is split-context AND sibling item signals are still PENDING
            SignalWaitEntity pendingSibling = mock(SignalWaitEntity.class);
            when(pendingSibling.getNodeId()).thenReturn("core:item_approval");
            when(mockSignalService.isSplitContextNode("run-split-fb", "core:item_approval", 1)).thenReturn(true);
            when(mockSignalService.getActiveSignals("run-split-fb", 1)).thenReturn(List.of(pendingSibling));

            // ReadyNodeCalculator already surfaced a successor → SUPPRESS branch
            when(mockStepByStepService.getReadyNodes("run-split-fb", "1", 1))
                .thenReturn(Set.of("core:approved_path"));

            // Fallback fully wired LIVE, exactly like prod: the approval node routes to
            // approved_path and the readiness context says canExecute=true (the sibling
            // resolutions already flipped the node to COMPLETED in EpochState).
            WorkflowPlan plan = mock(WorkflowPlan.class);
            lenient().when(plan.getIterateEdgesForSource("core:item_approval")).thenReturn(List.of());
            WorkflowExecution execution = new WorkflowExecution("run-split-fb", plan, Map.of());
            ExecutionTree tree = mock(ExecutionTree.class);
            when(mockExecutionCacheManager.loadTreeAndExecution("run-split-fb"))
                .thenReturn(new LoadedExecution(tree, execution));
            ExecutionNode approval = mock(ExecutionNode.class);
            when(approval.isBranchingNode()).thenReturn(true);
            ExecutionNode approvedPath = mock(ExecutionNode.class);
            when(approvedPath.getNodeId()).thenReturn("core:approved_path");
            when(approval.getNextNodes(any(NodeExecutionResult.class))).thenReturn(List.of(approvedPath));
            when(mockNodeSearchService.buildNodeMapFromAllRoots(tree))
                .thenReturn(Map.of("core:item_approval", approval));
            ExecutionContext readinessContext = mock(ExecutionContext.class);
            when(mockStepByStepContextManager.getOrCreateContextWithTriggerData(
                startsWith("run-split-fb:1:signal-successor-fallback:1:trigger:start"),
                eq(tree), eq("1"), eq(1), eq("core:item_approval"), eq(1), eq("trigger:start")))
                .thenReturn(readinessContext);
            when(approvedPath.canExecute(readinessContext)).thenReturn(true);

            // Pre-fix failure mode: the fallback executed the successor single-node.
            StepByStepExecutionResult executed = mock(StepByStepExecutionResult.class);
            lenient().when(executed.isPending()).thenReturn(false);
            lenient().when(executed.isSuccess()).thenReturn(true);
            lenient().when(mockStepByStepService.executeNode(
                anyString(), anyString(), any(), anyInt(), anyString())).thenReturn(executed);

            resumeService.resumeAfterSignal(signal);

            // The per-item resume must defer ENTIRELY to the last sibling's fan-out resume:
            // no successor may execute here - not via ready nodes, not via the fallback.
            verify(mockStepByStepService, never()).executeNode(anyString(), anyString(), any(), anyInt(), anyString());
            verify(mockStepByStepService, never()).executeNode(anyString(), anyString(), any(), anyInt());
        }

        @Test
        @DisplayName("regression: split sibling signals pending - successor fallback must not fire (defer branch, no ready nodes)")
        void splitSiblingSignalsPendingMustNotTriggerSuccessorFallbackWhenNoReadyNodes() {
            SignalWaitEntity signal = createMockSignal("run-split-fb2", "core:item_approval", "2");
            when(signal.getSignalType()).thenReturn(SignalType.USER_APPROVAL);
            when(signal.getResolution()).thenReturn(SignalResolution.APPROVED);
            when(signal.getSplitItemData()).thenReturn(null);
            when(signal.getEpoch()).thenReturn(1);
            when(signal.getDagTriggerId()).thenReturn("trigger:start");
            when(signal.getCreatedAt()).thenReturn(Instant.parse("2026-06-10T18:14:25Z"));
            when(signal.getResolvedAt()).thenReturn(Instant.parse("2026-06-10T18:14:26Z"));

            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.RUNNING);
            when(run.isStepByStepMode()).thenReturn(false);
            when(run.getTenantId()).thenReturn("tenant-1");
            when(run.getId()).thenReturn(UUID.fromString("11111111-1111-1111-1111-111111111111"));
            when(run.getRunIdPublic()).thenReturn("run-split-fb2");
            when(mockRunRepository.findByRunIdPublic("run-split-fb2")).thenReturn(Optional.of(run));
            when(mockStorageService.saveJsonWithContext(
                anyString(), anyMap(), anyString(), any(), any(), anyString(), anyString(),
                anyInt(), anyInt(), any(), anyString()))
                .thenReturn(UUID.fromString("22222222-2222-2222-2222-222222222222"));

            SignalWaitEntity pendingSibling = mock(SignalWaitEntity.class);
            when(pendingSibling.getNodeId()).thenReturn("core:item_approval");
            when(mockSignalService.isSplitContextNode("run-split-fb2", "core:item_approval", 1)).thenReturn(true);
            when(mockSignalService.getActiveSignals("run-split-fb2", 1)).thenReturn(List.of(pendingSibling));

            // ReadyNodeCalculator surfaced nothing → DEFER branch
            when(mockStepByStepService.getReadyNodes("run-split-fb2", "2", 1)).thenReturn(Set.of());

            WorkflowPlan plan = mock(WorkflowPlan.class);
            lenient().when(plan.getIterateEdgesForSource("core:item_approval")).thenReturn(List.of());
            WorkflowExecution execution = new WorkflowExecution("run-split-fb2", plan, Map.of());
            ExecutionTree tree = mock(ExecutionTree.class);
            when(mockExecutionCacheManager.loadTreeAndExecution("run-split-fb2"))
                .thenReturn(new LoadedExecution(tree, execution));
            ExecutionNode approval = mock(ExecutionNode.class);
            when(approval.isBranchingNode()).thenReturn(true);
            ExecutionNode approvedPath = mock(ExecutionNode.class);
            when(approvedPath.getNodeId()).thenReturn("core:approved_path");
            when(approval.getNextNodes(any(NodeExecutionResult.class))).thenReturn(List.of(approvedPath));
            when(mockNodeSearchService.buildNodeMapFromAllRoots(tree))
                .thenReturn(Map.of("core:item_approval", approval));
            ExecutionContext readinessContext = mock(ExecutionContext.class);
            when(mockStepByStepContextManager.getOrCreateContextWithTriggerData(
                startsWith("run-split-fb2:2:signal-successor-fallback:1:trigger:start"),
                eq(tree), eq("2"), eq(2), eq("core:item_approval"), eq(1), eq("trigger:start")))
                .thenReturn(readinessContext);
            when(approvedPath.canExecute(readinessContext)).thenReturn(true);

            StepByStepExecutionResult executed = mock(StepByStepExecutionResult.class);
            lenient().when(executed.isPending()).thenReturn(false);
            lenient().when(executed.isSuccess()).thenReturn(true);
            lenient().when(mockStepByStepService.executeNode(
                anyString(), anyString(), any(), anyInt(), anyString())).thenReturn(executed);

            resumeService.resumeAfterSignal(signal);

            verify(mockStepByStepService, never()).executeNode(anyString(), anyString(), any(), anyInt(), anyString());
            verify(mockStepByStepService, never()).executeNode(anyString(), anyString(), any(), anyInt());
        }
    }

    @Nested
    @DisplayName("split-context skip suppression in edge emission")
    @MockitoSettings(strictness = Strictness.LENIENT)
    class SplitContextSkipSuppression {

        /**
         * Creates a USER_APPROVAL signal for the given item in a split context.
         */
        private SignalWaitEntity createApprovalSignal(String runId, String nodeId, String itemId,
                                                       SignalResolution resolution, String triggerId, int epoch) {
            SignalWaitEntity signal = mock(SignalWaitEntity.class);
            when(signal.getRunId()).thenReturn(runId);
            when(signal.getNodeId()).thenReturn(nodeId);
            when(signal.getItemId()).thenReturn(itemId);
            when(signal.getSignalType()).thenReturn(SignalType.USER_APPROVAL);
            when(signal.getResolution()).thenReturn(resolution);
            when(signal.getDagTriggerId()).thenReturn(triggerId);
            when(signal.getEpoch()).thenReturn(epoch);
            when(signal.getSplitItemData()).thenReturn(null);
            return signal;
        }

        private void setupRunningRun(String runId) {
            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.RUNNING);
            when(run.isStepByStepMode()).thenReturn(false);
            when(run.getRunIdPublic()).thenReturn(runId);
            when(run.getTenantId()).thenReturn("tenant-1");
            when(mockRunRepository.findByRunIdPublic(runId)).thenReturn(Optional.of(run));
        }

        private void setupExecutionTree(String runId, String nodeId, boolean branching) throws Exception {
            ExecutionNode node = mock(ExecutionNode.class);
            when(node.getNodeId()).thenReturn(nodeId);
            when(node.isBranchingNode()).thenReturn(branching);

            ExecutionTree tree = mock(ExecutionTree.class);
            WorkflowExecution execution = mock(WorkflowExecution.class);
            when(execution.getRunId()).thenReturn(runId);

            LoadedExecution loaded = new LoadedExecution(tree, execution);
            when(mockExecutionCacheManager.loadTreeAndExecution(runId)).thenReturn(loaded);
            when(mockNodeSearchService.buildNodeMapFromAllRoots(tree)).thenReturn(Map.of(nodeId, node));
        }

        @Test
        @DisplayName("should suppress skip propagation for rejected item in split context (split has 5 signals)")
        void shouldSuppressSkipPropagationInSplitContext() throws Exception {
            String runId = "run-split-1";
            String nodeId = "core:user_approval";
            String triggerId = "trigger:start";

            // Signal: item 2 was rejected
            SignalWaitEntity signal = createApprovalSignal(runId, nodeId, "0.2",
                SignalResolution.REJECTED, triggerId, 0);

            setupRunningRun(runId);
            setupExecutionTree(runId, nodeId, true);

            // Split context: 5 signals total for this node (one per item)
            when(mockSignalService.isSplitContextNode(runId, nodeId, 0)).thenReturn(true);

            // No ready nodes after this signal
            when(mockStepByStepService.getReadyNodes(runId, "0.2", 0)).thenReturn(Set.of());

            resumeService.resumeAfterSignal(signal);

            // Verify emitBranchingEdgesForSignalNode was called WITH suppressSkipPropagation=true
            verify(mockEventService).emitBranchingEdgesForSignalNode(
                any(), any(), eq(2), any(), eq(0), eq(triggerId), eq(true));
        }

        @Test
        @DisplayName("should NOT suppress skip propagation for single (non-split) approval")
        void shouldNotSuppressSkipPropagationForNonSplitApproval() throws Exception {
            String runId = "run-single-1";
            String nodeId = "core:user_approval";
            String triggerId = "trigger:start";

            // Signal: single approval rejected (no split)
            SignalWaitEntity signal = createApprovalSignal(runId, nodeId, "0",
                SignalResolution.REJECTED, triggerId, 0);

            setupRunningRun(runId);
            setupExecutionTree(runId, nodeId, true);

            // Not split context: only 1 signal for this node
            when(mockSignalService.isSplitContextNode(runId, nodeId, 0)).thenReturn(false);

            when(mockStepByStepService.getReadyNodes(runId, "0", 0)).thenReturn(Set.of());

            resumeService.resumeAfterSignal(signal);

            // Verify emitBranchingEdgesForSignalNode was called WITHOUT suppressSkipPropagation
            verify(mockEventService).emitBranchingEdgesForSignalNode(
                any(), any(), eq(0), any(), eq(0), eq(triggerId), eq(false));
        }

        @Test
        @DisplayName("should suppress skip propagation for approved item in split context too")
        void shouldSuppressSkipPropagationForApprovedItemInSplitContext() throws Exception {
            String runId = "run-split-2";
            String nodeId = "core:user_approval";
            String triggerId = "trigger:start";

            // Signal: item 0 was approved (in a split with 3 items)
            SignalWaitEntity signal = createApprovalSignal(runId, nodeId, "0.0",
                SignalResolution.APPROVED, triggerId, 0);

            setupRunningRun(runId);
            setupExecutionTree(runId, nodeId, true);

            // Split context: multiple signals
            when(mockSignalService.isSplitContextNode(runId, nodeId, 0)).thenReturn(true);

            when(mockStepByStepService.getReadyNodes(runId, "0.0", 0)).thenReturn(Set.of());

            resumeService.resumeAfterSignal(signal);

            // Even approved items in split context should suppress skip propagation
            verify(mockEventService).emitBranchingEdgesForSignalNode(
                any(), any(), eq(0), any(), eq(0), eq(triggerId), eq(true));
        }

        @Test
        @DisplayName("regression: USER_APPROVAL split output preserves split_item_count when exactly one item yielded")
        void singleItemSplitApprovalPersistsSplitItemCount() throws Exception {
            String runId = "run-split-single-approval";
            String nodeId = "core:user_approval";
            String triggerId = "trigger:start";

            SignalWaitEntity signal = createApprovalSignal(runId, nodeId, "0",
                SignalResolution.APPROVED, triggerId, 0);
            when(signal.getSignalConfig()).thenReturn(Map.of(
                "type", SignalType.USER_APPROVAL.name(),
                "requiredApprovals", 1,
                "timeoutMs", 86400000L,
                "webhookToken", "secret-webhook-token",
                "cdpToken", "secret-cdp-token"));
            when(signal.getSplitItemData()).thenReturn(Map.of("split_id", "core:split_items", "item_index", 0));

            setupRunningRun(runId);
            setupExecutionTree(runId, nodeId, true);

            when(mockSignalService.isSplitContextNode(runId, nodeId, 0)).thenReturn(false);
            when(mockSignalService.getSignalCountForNodeEpoch(runId, nodeId, 0)).thenReturn(1L);
            when(mockStepByStepService.getReadyNodes(runId, "0", 0)).thenReturn(Set.of());

            resumeService.resumeAfterSignal(signal);

            org.mockito.ArgumentCaptor<Map<String, Object>> outputCaptor =
                org.mockito.ArgumentCaptor.forClass(Map.class);
            verify(mockStorageService).saveJsonWithContext(
                eq("tenant-1"),
                outputCaptor.capture(),
                eq(ExecutionConstants.CONTENT_TYPE_JSON),
                isNull(),
                isNull(),
                eq(runId),
                eq(nodeId),
                eq(0),
                eq(0),
                isNull(),
                eq("SIGNAL"));

            assertThat(outputCaptor.getValue().get("output"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("split_item_count", 1)
                .containsEntry("selected_port", "approved");

            org.mockito.ArgumentCaptor<WorkflowStepDataEntity> stepCaptor =
                org.mockito.ArgumentCaptor.forClass(WorkflowStepDataEntity.class);
            verify(mockStepDataRepository).save(stepCaptor.capture());
            assertThat(stepCaptor.getValue().getInputData())
                .as("completed signal rows must keep resolved signal params for the inspector input/params tabs")
                .containsEntry("signal_type", SignalType.USER_APPROVAL.name())
                .containsEntry("trigger_id", triggerId)
                .containsKey("signal_config");
            @SuppressWarnings("unchecked")
            Map<String, Object> savedSignalConfig =
                (Map<String, Object>) stepCaptor.getValue().getInputData().get("signal_config");
            assertThat(savedSignalConfig)
                .containsEntry("requiredApprovals", 1)
                .containsEntry("timeoutMs", 86400000L)
                .doesNotContainKeys("webhookToken", "cdpToken");
        }

        @Test
        @DisplayName("regression: cancelled approval signal persists SKIPPED row and does not emit selected-branch edges")
        void cancelledApprovalPersistsSkippedRowAndDoesNotEmitSelectedBranchEdges() throws Exception {
            String runId = "run-cancel-approval";
            String nodeId = "core:manager_approval";
            String triggerId = "trigger:start";

            SignalWaitEntity signal = createApprovalSignal(runId, nodeId, "0",
                SignalResolution.CANCELLED, triggerId, 0);
            when(signal.getCreatedAt()).thenReturn(Instant.parse("2026-05-21T15:00:00Z"));
            when(signal.getResolvedAt()).thenReturn(Instant.parse("2026-05-21T15:00:01Z"));
            when(signal.getSignalConfig()).thenReturn(Map.of("type", SignalType.USER_APPROVAL.name()));

            setupRunningRun(runId);
            setupExecutionTree(runId, nodeId, true);
            when(mockStepByStepService.getReadyNodes(runId, "0", 0)).thenReturn(Set.of());

            resumeService.resumeAfterSignal(signal);

            org.mockito.ArgumentCaptor<WorkflowStepDataEntity> stepCaptor =
                org.mockito.ArgumentCaptor.forClass(WorkflowStepDataEntity.class);
            verify(mockStepDataRepository).save(stepCaptor.capture());
            assertThat(stepCaptor.getValue().getStatus()).isEqualTo("SKIPPED");
            assertThat(stepCaptor.getValue().getSkipReason()).isEqualTo("Signal cancelled");
            assertThat(stepCaptor.getValue().getTriggerId()).isEqualTo(triggerId);
            verify(mockWorkflowEpochService).recordNodeCount(runId, 0, nodeId, "SKIPPED", triggerId);
            verify(mockStateSnapshotService, never()).ensureNodeCompletedInEpoch(runId, triggerId, 0, nodeId);
            verify(mockEventService, never()).emitBranchingEdgesForSignalNode(
                any(), any(), anyInt(), any(), anyInt(), any(), anyBoolean());
        }

        @Test
        @DisplayName("should suppress skip propagation for linear interface node in split context")
        void shouldSuppressSkipPropagationForLinearInterfaceInSplitContext() throws Exception {
            String runId = "run-split-iface";
            String nodeId = "interface:form";
            String triggerId = "trigger:start";

            // Interface signal with CONTINUE resolution (linear, not branching)
            SignalWaitEntity signal = mock(SignalWaitEntity.class);
            when(signal.getRunId()).thenReturn(runId);
            when(signal.getNodeId()).thenReturn(nodeId);
            when(signal.getItemId()).thenReturn("0.1");
            when(signal.getSignalType()).thenReturn(SignalType.INTERFACE_SIGNAL);
            when(signal.getResolution()).thenReturn(SignalResolution.CONTINUE);
            when(signal.getDagTriggerId()).thenReturn(triggerId);
            when(signal.getEpoch()).thenReturn(0);
            when(signal.getSplitItemData()).thenReturn(null);

            setupRunningRun(runId);
            setupExecutionTree(runId, nodeId, false); // linear, not branching

            // Split context
            when(mockSignalService.isSplitContextNode(runId, nodeId, 0)).thenReturn(true);

            when(mockStepByStepService.getReadyNodes(runId, "0.1", 0)).thenReturn(Set.of());

            resumeService.resumeAfterSignal(signal);

            // Linear path also passes suppressSkipPropagation=true in split context
            verify(mockEventService).emitBranchingEdgesForSignalNode(
                any(), any(), eq(1), any(), eq(0), eq(triggerId), eq(true));
        }

        @Test
        @DisplayName("should suppress skip propagation for all-rejected items in split context (self-correcting)")
        void shouldSuppressSkipForAllRejectedItems() throws Exception {
            // When ALL items are rejected, skip propagation is still suppressed.
            // SplitAwareNodeExecutor will handle this: getRoutedItemIndices() returns empty
            // → NodeExecutionResult.skipped() is returned (self-correcting).
            String runId = "run-all-reject";
            String nodeId = "core:user_approval";
            String triggerId = "trigger:start";

            // Last rejected item (all 3 were rejected)
            SignalWaitEntity signal = createApprovalSignal(runId, nodeId, "0.2",
                SignalResolution.REJECTED, triggerId, 0);

            setupRunningRun(runId);
            setupExecutionTree(runId, nodeId, true);

            // Split context: 3 signals
            when(mockSignalService.isSplitContextNode(runId, nodeId, 0)).thenReturn(true);

            when(mockStepByStepService.getReadyNodes(runId, "0.2", 0)).thenReturn(Set.of());

            resumeService.resumeAfterSignal(signal);

            // Even for the last rejected item, suppress is still true
            verify(mockEventService).emitBranchingEdgesForSignalNode(
                any(), any(), eq(2), any(), eq(0), eq(triggerId), eq(true));
        }

        @Test
        @DisplayName("WAIT_TIMER emits edges so frontend statusCount transitions 0 → 1 (regression: 2026-05-06)")
        void shouldEmitEdgesForWaitTimer() throws Exception {
            // The previous version of this test asserted the OPPOSITE - WAIT_TIMER was
            // gated out because emitting `NodeExecutionResult.success(nodeId, Map.of())`
            // would re-execute the wait infinitely. Two prerequisites were fixed in the
            // same series of commits (2026-05-06):
            //   (a) L1-cache pollution in UnifiedSignalService.resolveSignal's
            //       closed-epoch zombie guard (findById → findEpochInfoById projection)
            //   (b) cross-epoch keepInAwaiting in hasRemainingSignals (run+node-wide →
            //       epoch-scoped countActiveByRunIdAndNodeIdAndDagAndEpoch)
            // With those fixed, WAIT_TIMER must emit so the wait→successor edge counter
            // ticks to 1 in the frontend's status view.
            String runId = "run-timer-1";
            String nodeId = "core:wait";

            SignalWaitEntity signal = createMockSignal(runId, nodeId, "0");
            when(signal.getSplitItemData()).thenReturn(null);
            when(signal.getDagTriggerId()).thenReturn("trigger:start");

            setupRunningRun(runId);
            // The emit path needs a loaded execution tree; mock it as a non-branching wait node.
            setupExecutionTree(runId, nodeId, false);

            when(mockStepByStepService.getReadyNodes(runId, "0", 0)).thenReturn(Set.of());

            resumeService.resumeAfterSignal(signal);

            // Lock the contract: emit must fire, AND the result MUST carry the wait nodeId
            // with status=COMPLETED (i.e. constructed via NodeExecutionResult.success(...)).
            // The 2026-05-05 incident note documents that prior to fixes #4 and #5 in this
            // series, this exact `success(nodeId, Map.of())` call would have triggered a
            // fresh ready-node calc that re-executes the wait. This unit-level assertion
            // locks the gate-shape: any new gate that skips WAIT_TIMER or emits a non-
            // COMPLETED result here would break the frontend statusCount tick AND silently
            // re-open the loop the prerequisites guarded against.
            org.mockito.ArgumentCaptor<com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult>
                resultCaptor = org.mockito.ArgumentCaptor.forClass(
                    com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult.class);
            verify(mockEventService, atLeastOnce()).emitBranchingEdgesForSignalNode(
                any(), any(), anyInt(), resultCaptor.capture(), anyInt(), any(), anyBoolean());
            var result = resultCaptor.getValue();
            assertThat(result).as("Emit result must not be null").isNotNull();
            assertThat(result.nodeId()).as("Result must carry the wait node id").isEqualTo(nodeId);
            assertThat(result.status())
                .as("Result must be COMPLETED (so successor edges tick to completed)")
                .isEqualTo(com.apimarketplace.orchestrator.domain.execution.NodeStatus.COMPLETED);
            assertThat(result.output())
                .as("Output map must be present (empty allowed; load-bearing because the previous gate skipped on emptiness)")
                .isNotNull();
        }

        @Test
        @DisplayName("P2.3.1 - markCompleted threads resolvedSignal.getEpoch() into the per-epoch Redis key (non-zero epoch)")
        void shouldThreadSignalEpochIntoMarkCompleted() throws Exception {
            // Pin SignalResumeService:619 - when persistSignalResolutionOutput clears the
            // running count after a signal resolves, the epoch source MUST be the SIGNAL'S
            // own epoch (resolvedSignal.getEpoch()), not the run's "current" epoch.
            //
            // Why this matters under parallel-epoch reusable triggers:
            //   - Run is at currentEpoch=10, but a USER_APPROVAL signal raised in epoch=3
            //     can still resolve.
            //   - emitNodeStart() marked the node as running under
            //     {orchestrator:running:runId:3}.
            //   - If we cleared under epoch=10 instead, epoch=3's running count stays >0
            //     forever, blocking deferred reset and leaking memory in Redis.
            String runId = "run-epoch-thread-1";
            String nodeId = "core:user_approval";
            String triggerId = "trigger:webhook";
            int signalEpoch = 5;

            SignalWaitEntity signal = createApprovalSignal(runId, nodeId, "0",
                SignalResolution.APPROVED, triggerId, signalEpoch);

            setupRunningRun(runId);
            setupExecutionTree(runId, nodeId, true);

            when(mockSignalService.isSplitContextNode(runId, nodeId, signalEpoch)).thenReturn(false);
            when(mockStepByStepService.getReadyNodes(runId, "0", signalEpoch)).thenReturn(Set.of());

            resumeService.resumeAfterSignal(signal);

            // The contract: markCompleted must use signalEpoch (5), not 0 (run's "current"),
            // not -1, not the global epoch. Mark/markCompleted symmetry under the
            // SAME per-epoch Redis key shape is the load-bearing invariant for the
            // §3.6.1 deferred-reset gate.
            verify(mockRunningNodeTracker).markCompleted(runId, signalEpoch, nodeId);
        }
    }

    @Nested
    @DisplayName("Plan v4 §10 STALE_OWNERSHIP validation (#10b wiring)")
    class StaleOwnershipValidation {

        private com.apimarketplace.orchestrator.heartbeat.InstanceHeartbeatService mockHeartbeat;

        @org.junit.jupiter.api.BeforeEach
        void wireHeartbeat() throws Exception {
            mockHeartbeat = mock(com.apimarketplace.orchestrator.heartbeat.InstanceHeartbeatService.class);
            setField("heartbeatService", mockHeartbeat);
        }

        @Test
        @DisplayName("claimed_by==null → standard path (no STALE check, pre-#10 semantics)")
        void unclaimedSignalProceedsNormally() {
            SignalWaitEntity signal = createMockSignal("run-1", "core:wait", "item-0");
            when(signal.getClaimedBy()).thenReturn(null);
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.empty());  // short-circuit on missing run

            resumeService.resumeAfterSignal(signal);

            // No releaseSignalAsStale call - claimed_by was null.
            verify(mockHeartbeat, never()).releaseSignalAsStale(anyLong(), anyLong());
            // Did try to look up the run, meaning we got past the STALE check.
            verify(mockRunRepository).findByRunIdPublic("run-1");
        }

        @Test
        @DisplayName("claimed_generation=0 user claim proceeds normally (regression: approval resolved by user id skipped as peer)")
        void userClaimWithZeroGenerationProceedsNormally() {
            SignalWaitEntity signal = createMockSignal("run-1", "core:approval", "0");
            when(signal.getClaimedBy()).thenReturn("103");
            when(signal.getClaimedGeneration()).thenReturn(0L);
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.empty());

            resumeService.resumeAfterSignal(signal);

            verify(mockHeartbeat, never()).releaseSignalAsStale(anyLong(), anyLong());
            verify(mockRunRepository).findByRunIdPublic("run-1");
        }

        @Test
        @DisplayName("claimed_by != me - peer owns it, skip without release (peer will resume)")
        void peerClaimedSignalSkipped() {
            SignalWaitEntity signal = createMockSignal("run-1", "core:wait", "item-0");
            when(signal.getClaimedBy()).thenReturn("orch-peer-xyz");
            when(signal.getId()).thenReturn(42L);
            when(signal.getClaimedGeneration()).thenReturn(7L);
            when(mockHeartbeat.getInstanceId()).thenReturn("orch-me");

            resumeService.resumeAfterSignal(signal);

            // No release: this is a peer's signal, we just skip
            verify(mockHeartbeat, never()).releaseSignalAsStale(anyLong(), anyLong());
            // Run lookup never happens - we returned before reaching that
            verify(mockRunRepository, never()).findByRunIdPublic(any());
        }

        @Test
        @DisplayName("claimed_by == me AND claimed_generation < live → STALE_OWNERSHIP, release, skip")
        void selfClaimAtStaleGenReleasesAndSkips() {
            SignalWaitEntity signal = createMockSignal("run-1", "core:wait", "item-0");
            when(signal.getClaimedBy()).thenReturn("orch-me");
            when(signal.getId()).thenReturn(42L);
            when(signal.getClaimedGeneration()).thenReturn(3L);  // pre-restart claim
            when(mockHeartbeat.getInstanceId()).thenReturn("orch-me");
            when(mockHeartbeat.getCurrentGeneration()).thenReturn(5L);  // bumped on restart

            resumeService.resumeAfterSignal(signal);

            verify(mockHeartbeat).releaseSignalAsStale(42L, 3L);
            verify(mockRunRepository, never()).findByRunIdPublic(any());  // returned before run lookup
        }

        @Test
        @DisplayName("claimed_by == me AND claimed_generation == live → proceed normally")
        void selfClaimAtLiveGenProceeds() {
            SignalWaitEntity signal = createMockSignal("run-1", "core:wait", "item-0");
            when(signal.getClaimedBy()).thenReturn("orch-me");
            when(signal.getClaimedGeneration()).thenReturn(5L);
            when(mockHeartbeat.getInstanceId()).thenReturn("orch-me");
            when(mockHeartbeat.getCurrentGeneration()).thenReturn(5L);
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.empty());  // short-circuit

            resumeService.resumeAfterSignal(signal);

            verify(mockHeartbeat, never()).releaseSignalAsStale(anyLong(), anyLong());
            verify(mockRunRepository).findByRunIdPublic("run-1");  // proceeded past STALE check
        }
    }

    /**
     * Concurrent-sibling races (CE-SIGRACE-006/007 + CE-WAIT-002, 2026-06-12).
     *
     * <p>When N signals of the same (dag, epoch) resolve in the same instant (same-duration
     * timers, identical approval timeouts, concurrent HTTP resolutions), all are DB-RESOLVED
     * and EpochState-COMPLETED before the first resume acquires the per-run lock. Pre-fix,
     * the first resume executed every sibling's successors AND fired the deferred epoch
     * reset while sibling resumes had persisted nothing; each later sibling re-reset the
     * epoch (observed live: 3 resets for one epoch, completed counts wiped).
     */
    @Nested
    @DisplayName("concurrent sibling resumes (premature deferred reset + sibling successors)")
    @MockitoSettings(strictness = Strictness.LENIENT)
    class ConcurrentSiblingResumes {

        @Mock private ReusableTriggerService mockReusableTriggerService;
        @Mock private WorkflowResumeService mockWorkflowResumeService;
        @Mock private SnapshotService mockSnapshotService;

        @BeforeEach
        void injectResetDeps() throws Exception {
            setField("reusableTriggerService", mockReusableTriggerService);
            setField("resumeService", mockWorkflowResumeService);
            setField("snapshotService", mockSnapshotService);

            var defaultEpoch = mock(com.apimarketplace.orchestrator.domain.execution.EpochState.class);
            lenient().when(defaultEpoch.getFailedNodeIds()).thenReturn(Set.of());
            var defaultSnapshot = mock(com.apimarketplace.orchestrator.domain.execution.StateSnapshot.class);
            lenient().when(defaultSnapshot.getEpochState(anyString(), anyInt())).thenReturn(defaultEpoch);
            lenient().when(mockStateSnapshotService.getSnapshot(anyString())).thenReturn(defaultSnapshot);

            // Resolution outputs are already persisted - these tests pin sequencing, not persistence.
            lenient().when(mockStepDataRepository.existsByRunIdAndNormalizedKeyAndEpochAndItemIndexAndStatusAndStartTime(
                anyString(), anyString(), anyInt(), anyInt(), anyString(), any(Instant.class))).thenReturn(true);
        }

        private SignalWaitEntity signal(String runId, String nodeId, String itemId, SignalType type, long id) {
            SignalWaitEntity signal = mock(SignalWaitEntity.class);
            when(signal.getId()).thenReturn(id);
            when(signal.getRunId()).thenReturn(runId);
            when(signal.getNodeId()).thenReturn(nodeId);
            when(signal.getItemId()).thenReturn(itemId);
            when(signal.getSignalType()).thenReturn(type);
            when(signal.getResolution()).thenReturn(
                type == SignalType.USER_APPROVAL ? SignalResolution.APPROVED : SignalResolution.COMPLETED);
            when(signal.getDagTriggerId()).thenReturn("trigger:start");
            when(signal.getEpoch()).thenReturn(1);
            when(signal.getSplitItemData()).thenReturn(null);
            when(signal.getCreatedAt()).thenReturn(Instant.parse("2026-06-12T15:00:00Z"));
            return signal;
        }

        private WorkflowRunEntity runningRun(String runId) {
            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.RUNNING);
            when(run.isStepByStepMode()).thenReturn(false);
            when(run.getRunIdPublic()).thenReturn(runId);
            when(run.getTenantId()).thenReturn("tenant-1");
            when(run.getId()).thenReturn(UUID.fromString("11111111-1111-1111-1111-111111111111"));
            when(run.getPlan()).thenReturn(Map.of("triggers", List.of(), "edges", List.of()));
            WorkflowEntity workflow = new WorkflowEntity();
            workflow.setId(UUID.randomUUID());
            when(run.getWorkflow()).thenReturn(workflow);
            when(mockRunRepository.findByRunIdPublic(runId)).thenReturn(Optional.of(run));
            return run;
        }

        private void stubTree(String runId, Map<String, ExecutionNode> nodeMap) {
            WorkflowPlan plan = mock(WorkflowPlan.class);
            when(plan.getIterateEdgesForSource(anyString())).thenReturn(List.of());
            WorkflowExecution execution = new WorkflowExecution(runId, plan, Map.of());
            ExecutionTree tree = mock(ExecutionTree.class);
            when(mockExecutionCacheManager.loadTreeAndExecution(runId)).thenReturn(new LoadedExecution(tree, execution));
            when(mockNodeSearchService.buildNodeMapFromAllRoots(tree)).thenReturn(nodeMap);
        }

        @Test
        @DisplayName("regression CE-SIGRACE-006/007: deferred reset is skipped while a sibling signal's resume is still finalizing")
        void deferredResetSkippedWhileSiblingResumeStillFinalizing() {
            String runId = "run-sibling-pending";
            SignalWaitEntity signal = signal(runId, "core:gate_a", "0", SignalType.USER_APPROVAL, 6L);
            runningRun(runId);
            stubTree(runId, Map.of());
            when(mockStepByStepService.getReadyNodes(runId, "0", 1)).thenReturn(Set.of());

            // Sibling gate_b resolved concurrently: no PENDING signal left in the DB,
            // but its async resume has not finalized (resume-pending marker present).
            when(mockSignalService.hasBlockingSignalsForDagAndEpoch(runId, "trigger:start", 1)).thenReturn(false);
            when(mockSignalService.hasPendingSignalResumesForDagAndEpoch(runId, "trigger:start", 1)).thenReturn(true);

            resumeService.resumeAfterSignal(signal);

            // Pre-fix: hasBlockingSignals=false was the only gate → premature reset here.
            verify(mockReusableTriggerService, never()).resetForNextCycle(
                any(), any(), any(), any(), any(), any(), anyBoolean(), anyInt());
        }

        @Test
        @DisplayName("the last sibling resume (no pending markers left) performs the single deferred reset")
        void lastSiblingResumePerformsTheSingleDeferredReset() {
            String runId = "run-last-sibling";
            SignalWaitEntity signal = signal(runId, "core:gate_b", "0", SignalType.USER_APPROVAL, 7L);
            runningRun(runId);
            stubTree(runId, Map.of());
            when(mockStepByStepService.getReadyNodes(runId, "0", 1)).thenReturn(Set.of());

            when(mockSignalService.hasBlockingSignalsForDagAndEpoch(runId, "trigger:start", 1)).thenReturn(false);
            when(mockSignalService.hasPendingSignalResumesForDagAndEpoch(runId, "trigger:start", 1)).thenReturn(false);

            resumeService.resumeAfterSignal(signal);

            // Own marker must be dropped BEFORE consulting the sibling markers, otherwise
            // the last resume would see itself and nobody would ever reset.
            var order = inOrder(mockSignalService, mockReusableTriggerService);
            order.verify(mockSignalService).clearSignalResumePending(signal);
            order.verify(mockSignalService).hasPendingSignalResumesForDagAndEpoch(runId, "trigger:start", 1);
            order.verify(mockReusableTriggerService).resetForNextCycle(
                any(), any(), any(), eq(runId), any(), eq("trigger:start"), eq(false), eq(1));
        }

        @Test
        @DisplayName("two concurrent resolutions → exactly one reset, by the second (last) resume")
        void firstResumeDefersResetSecondResumePerformsIt() {
            String runId = "run-two-siblings";
            SignalWaitEntity gateA = signal(runId, "core:gate_a", "0", SignalType.USER_APPROVAL, 8L);
            SignalWaitEntity gateB = signal(runId, "core:gate_b", "0", SignalType.USER_APPROVAL, 9L);
            runningRun(runId);
            stubTree(runId, Map.of());
            when(mockStepByStepService.getReadyNodes(runId, "0", 1)).thenReturn(Set.of());
            when(mockSignalService.hasBlockingSignalsForDagAndEpoch(runId, "trigger:start", 1)).thenReturn(false);

            // Both signals DB-RESOLVED up-front (the live race shape). The per-run lock
            // serializes the two resumes; the marker set empties only when B's resume
            // clears its own marker.
            when(mockSignalService.hasPendingSignalResumesForDagAndEpoch(runId, "trigger:start", 1))
                .thenReturn(true)   // during A's resume: B still pending
                .thenReturn(false); // during B's resume: nobody left

            resumeService.resumeAfterSignal(gateA);
            resumeService.resumeAfterSignal(gateB);

            verify(mockReusableTriggerService, times(1)).resetForNextCycle(
                any(), any(), any(), eq(runId), any(), eq("trigger:start"), eq(false), eq(1));
        }

        @Test
        @DisplayName("regression CE-SIGRACE-006: a ready successor of a pending sibling signal is left to that sibling's resume")
        void readySuccessorOfPendingSiblingSignalIsLeftToItsOwnResume() {
            String runId = "run-sibling-successor";
            SignalWaitEntity gateA = signal(runId, "core:gate_a", "0", SignalType.USER_APPROVAL, 10L);
            runningRun(runId);

            // Tree: after_a ← gate_a (ours), after_b ← gate_b (sibling, resume pending).
            ExecutionNode afterA = mock(ExecutionNode.class);
            when(afterA.getNodeId()).thenReturn("core:after_a");
            when(afterA.getPredecessorIds()).thenReturn(List.of("core:gate_a"));
            ExecutionNode afterB = mock(ExecutionNode.class);
            when(afterB.getNodeId()).thenReturn("core:after_b");
            when(afterB.getPredecessorIds()).thenReturn(List.of("core:gate_b"));
            stubTree(runId, Map.of("core:after_a", afterA, "core:after_b", afterB));

            // EpochState already shows gate_b COMPLETED (resolver-thread CAS) → both
            // successors come back "ready" to the first resume.
            when(mockStepByStepService.getReadyNodes(runId, "0", 1))
                .thenReturn(Set.of("core:after_a", "core:after_b"), Set.of());
            when(mockSignalService.getPendingResumeNodeIds(runId, "trigger:start", 1, 10L))
                .thenReturn(new HashSet<>(Set.of("core:gate_b")));

            StepByStepExecutionResult executed = mock(StepByStepExecutionResult.class);
            when(executed.isPending()).thenReturn(false);
            when(executed.isSuccess()).thenReturn(true);
            when(mockStepByStepService.executeNode(anyString(), anyString(), any(), anyInt(), anyString()))
                .thenReturn(executed);

            resumeService.resumeAfterSignal(gateA);

            // Our successor runs; the sibling's successor is deferred to the sibling's resume.
            verify(mockStepByStepService).executeNode(runId, "core:after_a", "0", 1, "trigger:start");
            verify(mockStepByStepService, never()).executeNode(eq(runId), eq("core:after_b"), any(), anyInt(), anyString());
        }

        @Test
        @DisplayName("regression CE-WAIT-002: a split WAIT_TIMER with sibling item timers still pending defers successor execution")
        void splitWaitTimerDefersSuccessorsWhileSiblingItemsPending() {
            String runId = "run-split-wait";
            SignalWaitEntity waitItem1 = signal(runId, "core:wait_i", "1", SignalType.WAIT_TIMER, 11L);
            runningRun(runId);
            ExecutionNode afterI = mock(ExecutionNode.class);
            when(afterI.getNodeId()).thenReturn("core:after_i");
            when(afterI.getPredecessorIds()).thenReturn(List.of("core:wait_i"));
            ExecutionNode waitNode = mock(ExecutionNode.class);
            when(waitNode.getNodeId()).thenReturn("core:wait_i");
            when(waitNode.isBranchingNode()).thenReturn(false);
            stubTree(runId, Map.of("core:wait_i", waitNode, "core:after_i", afterI));

            // Split context with a sibling item's WAIT_TIMER still active.
            when(mockSignalService.isSplitContextNode(runId, "core:wait_i", 1)).thenReturn(true);
            SignalWaitEntity siblingPending = mock(SignalWaitEntity.class);
            when(siblingPending.getNodeId()).thenReturn("core:wait_i");
            when(mockSignalService.getActiveSignals(runId, 1)).thenReturn(List.of(siblingPending));

            // ReadyNodeCalculator surfaces the successor (item 0 already routed it).
            when(mockStepByStepService.getReadyNodes(runId, "1", 1)).thenReturn(Set.of("core:after_i"));

            resumeService.resumeAfterSignal(waitItem1);

            // Pre-fix, WAIT_TIMER was excluded from the split gate (canBeSplitSignal) and the
            // first expiry executed core:after_i once as a single non-split node - the live
            // CE-WAIT-002 shape (after_i COMPLETED item 0, SKIPPED items 1-2).
            verify(mockStepByStepService, never()).executeNode(anyString(), anyString(), any(), anyInt(), anyString());
            verify(mockStepByStepService, never()).executeNode(anyString(), anyString(), any(), anyInt());
        }

        @Test
        @DisplayName("CE-WAIT-002: the LAST split item's timer fans out successors at the parent item scope")
        void splitWaitTimerLastItemFansOutAtParentItemScope() {
            String runId = "run-split-wait-last";
            SignalWaitEntity waitItem2 = signal(runId, "core:wait_i", "2", SignalType.WAIT_TIMER, 12L);
            runningRun(runId);
            ExecutionNode afterI = mock(ExecutionNode.class);
            when(afterI.getNodeId()).thenReturn("core:after_i");
            when(afterI.getPredecessorIds()).thenReturn(List.of("core:wait_i"));
            ExecutionNode waitNode = mock(ExecutionNode.class);
            when(waitNode.getNodeId()).thenReturn("core:wait_i");
            when(waitNode.isBranchingNode()).thenReturn(false);
            when(waitNode.getSuccessors()).thenReturn(List.of(afterI));
            stubTree(runId, Map.of("core:wait_i", waitNode, "core:after_i", afterI));

            // Last sibling: no remaining actives, all 3 per-item outputs persisted.
            when(mockSignalService.isSplitContextNode(runId, "core:wait_i", 1)).thenReturn(true);
            when(mockSignalService.getActiveSignals(runId, 1)).thenReturn(List.of());
            when(mockSignalService.getSignalCountForNodeEpoch(runId, "core:wait_i", 1)).thenReturn(3L);
            when(mockStepDataRepository.countByRunIdAndNormalizedKeyAndEpochAndStatus(
                runId, "core:wait_i", 1, "COMPLETED")).thenReturn(3L);

            // ReadyNodeCalculator returns empty for items 1..N-1 (node already has output).
            when(mockStepByStepService.getReadyNodes(runId, "2", 1)).thenReturn(Set.of());

            StepByStepExecutionResult executed = mock(StepByStepExecutionResult.class);
            when(executed.isPending()).thenReturn(false);
            when(executed.isSuccess()).thenReturn(true);
            when(mockStepByStepService.executeNode(anyString(), anyString(), any(), anyInt(), anyString()))
                .thenReturn(executed);

            resumeService.resumeAfterSignal(waitItem2);

            // The successor must execute ONCE at the parent item scope ("0", not "2") so
            // SplitAwareNodeExecutor rehydrates the sealed split context and runs ALL items.
            verify(mockStepByStepService).executeNode(runId, "core:after_i", "0", 1, "trigger:start");
        }

        @Test
        @DisplayName("regression: the split fan-out cannot resurrect a successor gated by an independent pending sibling")
        void splitFanOutCannotResurrectSuccessorGatedByIndependentPendingSibling() {
            // Re-derivation site #3: the last split item's fan-out re-derives successors via
            // findSplitContextSuccessors. A successor ALSO fed by an independent sibling
            // signal (merge joining the split continuation and a concurrent gate) must be
            // left to that sibling's resume.
            String runId = "run-split-merge-sibling";
            SignalWaitEntity waitItem2 = signal(runId, "core:wait_i", "2", SignalType.WAIT_TIMER, 16L);
            runningRun(runId);
            ExecutionNode joinNode = mock(ExecutionNode.class);
            when(joinNode.getNodeId()).thenReturn("core:join");
            when(joinNode.getPredecessorIds()).thenReturn(List.of("core:wait_i", "core:gate_x"));
            ExecutionNode waitNode = mock(ExecutionNode.class);
            when(waitNode.getNodeId()).thenReturn("core:wait_i");
            when(waitNode.isBranchingNode()).thenReturn(false);
            when(waitNode.getSuccessors()).thenReturn(List.of(joinNode));
            stubTree(runId, Map.of("core:wait_i", waitNode, "core:join", joinNode));

            when(mockSignalService.isSplitContextNode(runId, "core:wait_i", 1)).thenReturn(true);
            when(mockSignalService.getActiveSignals(runId, 1)).thenReturn(List.of());
            when(mockSignalService.getSignalCountForNodeEpoch(runId, "core:wait_i", 1)).thenReturn(3L);
            when(mockStepDataRepository.countByRunIdAndNormalizedKeyAndEpochAndStatus(
                runId, "core:wait_i", 1, "COMPLETED")).thenReturn(3L);
            when(mockStepByStepService.getReadyNodes(runId, "2", 1)).thenReturn(Set.of());

            // An INDEPENDENT sibling signal node's resume is still finalizing.
            when(mockSignalService.getPendingResumeNodeIds(runId, "trigger:start", 1, 16L))
                .thenReturn(new HashSet<>(Set.of("core:gate_x")));

            resumeService.resumeAfterSignal(waitItem2);

            verify(mockStepByStepService, never()).executeNode(eq(runId), eq("core:join"), any(), anyInt(), anyString());
            verify(mockStepByStepService, never()).executeNode(eq(runId), eq("core:join"), any(), anyInt());
        }

        @Test
        @DisplayName("regression: the sibling-successor guard strips PORT-QUALIFIED predecessor refs (core:gate_b:approved)")
        void siblingSuccessorGuardStripsPortQualifiedPredecessorRefs() {
            // Approval successors are wired with port-qualified predecessor refs
            // (ApprovalNodeWirer: "core:gate_b" + ":approved") and have NO bare-ref twin.
            // An exact-string match would silently never defer them - the headline
            // concurrent-approvals scenario (CE-SIGRACE-003/006 topology).
            String runId = "run-port-qualified";
            SignalWaitEntity gateA = signal(runId, "core:gate_a", "0", SignalType.USER_APPROVAL, 13L);
            runningRun(runId);

            ExecutionNode afterA = mock(ExecutionNode.class);
            when(afterA.getNodeId()).thenReturn("core:after_a");
            when(afterA.getPredecessorIds()).thenReturn(List.of("core:gate_a:approved"));
            ExecutionNode afterB = mock(ExecutionNode.class);
            when(afterB.getNodeId()).thenReturn("core:after_b");
            when(afterB.getPredecessorIds()).thenReturn(List.of("core:gate_b:approved"));
            stubTree(runId, Map.of("core:after_a", afterA, "core:after_b", afterB));

            when(mockStepByStepService.getReadyNodes(runId, "0", 1))
                .thenReturn(Set.of("core:after_a", "core:after_b"), Set.of());
            when(mockSignalService.getPendingResumeNodeIds(runId, "trigger:start", 1, 13L))
                .thenReturn(new HashSet<>(Set.of("core:gate_b")));

            StepByStepExecutionResult executed = mock(StepByStepExecutionResult.class);
            when(executed.isPending()).thenReturn(false);
            when(executed.isSuccess()).thenReturn(true);
            when(mockStepByStepService.executeNode(anyString(), anyString(), any(), anyInt(), anyString()))
                .thenReturn(executed);

            resumeService.resumeAfterSignal(gateA);

            verify(mockStepByStepService).executeNode(runId, "core:after_a", "0", 1, "trigger:start");
            verify(mockStepByStepService, never()).executeNode(eq(runId), eq("core:after_b"), any(), anyInt(), anyString());
        }

        @Test
        @DisplayName("regression: the in-loop ready refresh re-applies the sibling-successor guard")
        void loopReadyRefreshReappliesSiblingSuccessorGuard() {
            // The while-loop re-derives ready nodes after each execution; the sibling's
            // successor (sibling COMPLETED in EpochState, successor without output) leaks
            // back in on the second iteration unless the guard is re-applied there.
            String runId = "run-loop-refresh";
            SignalWaitEntity gateA = signal(runId, "core:gate_a", "0", SignalType.USER_APPROVAL, 14L);
            runningRun(runId);

            ExecutionNode afterA = mock(ExecutionNode.class);
            when(afterA.getNodeId()).thenReturn("core:after_a");
            when(afterA.getPredecessorIds()).thenReturn(List.of("core:gate_a"));
            ExecutionNode afterB = mock(ExecutionNode.class);
            when(afterB.getNodeId()).thenReturn("core:after_b");
            when(afterB.getPredecessorIds()).thenReturn(List.of("core:gate_b"));
            stubTree(runId, Map.of("core:after_a", afterA, "core:after_b", afterB));

            // Entry: only our successor. In-loop refresh: the sibling's successor appears.
            when(mockStepByStepService.getReadyNodes(runId, "0", 1))
                .thenReturn(Set.of("core:after_a"), Set.of("core:after_b"), Set.of());
            when(mockSignalService.getPendingResumeNodeIds(runId, "trigger:start", 1, 14L))
                .thenReturn(new HashSet<>(Set.of("core:gate_b")));

            StepByStepExecutionResult executed = mock(StepByStepExecutionResult.class);
            when(executed.isPending()).thenReturn(false);
            when(executed.isSuccess()).thenReturn(true);
            when(mockStepByStepService.executeNode(anyString(), anyString(), any(), anyInt(), anyString()))
                .thenReturn(executed);

            resumeService.resumeAfterSignal(gateA);

            verify(mockStepByStepService).executeNode(runId, "core:after_a", "0", 1, "trigger:start");
            verify(mockStepByStepService, never()).executeNode(eq(runId), eq("core:after_b"), any(), anyInt(), anyString());
        }

        @Test
        @DisplayName("regression: the back-edge fallback cannot resurrect a successor deferred by the sibling guard")
        void backEdgeFallbackCannotResurrectDeferredSiblingSuccessor() {
            // When the ready set is empty, the back-edge fallback re-derives nodes from the
            // EpochState - where the sibling is already COMPLETED. Without re-filtering, a
            // merge fed by our node AND a pending sibling runs before the sibling's resume
            // persisted its output.
            String runId = "run-fallback-filter";
            SignalWaitEntity gateA = signal(runId, "core:gate_a", "0", SignalType.USER_APPROVAL, 15L);
            runningRun(runId);

            ExecutionNode joinNode = mock(ExecutionNode.class);
            when(joinNode.getNodeId()).thenReturn("core:join");
            when(joinNode.getPredecessorIds()).thenReturn(List.of("core:gate_a", "core:gate_b"));
            ExecutionNode gateNode = mock(ExecutionNode.class);
            when(gateNode.getNodeId()).thenReturn("core:gate_a");
            when(gateNode.isBranchingNode()).thenReturn(true);
            stubTree(runId, Map.of("core:gate_a", gateNode, "core:join", joinNode));

            // Iterate edge so the back-edge fallback engages.
            WorkflowPlan plan = mock(WorkflowPlan.class);
            Edge iterateEdge = new Edge("core:gate_a:approved", "core:loop:iterate");
            when(plan.getIterateEdgesForSource("core:gate_a")).thenReturn(List.of(iterateEdge));
            WorkflowExecution execution = new WorkflowExecution(runId, plan, Map.of());
            ExecutionTree tree = mock(ExecutionTree.class);
            when(mockExecutionCacheManager.loadTreeAndExecution(runId)).thenReturn(new LoadedExecution(tree, execution));
            when(mockNodeSearchService.buildNodeMapFromAllRoots(tree))
                .thenReturn(Map.of("core:gate_a", gateNode, "core:join", joinNode));

            ExecutionContext context = mock(ExecutionContext.class);
            when(context.triggerData()).thenReturn(Map.of());
            when(context.itemIndex()).thenReturn(0);
            when(context.triggerId()).thenReturn("trigger:start");
            when(context.getGlobalDataKeys()).thenReturn(Set.of());
            when(context.getGlobalData(anyString())).thenReturn(Optional.empty());
            when(mockStepByStepContextManager.getOrCreateContextWithTriggerData(
                anyString(), eq(tree), eq("0"), eq(0), eq("core:gate_a"), eq(1), eq("trigger:start")))
                .thenReturn(context);

            // Back-edge fallback re-derives the merge node the entry guard would defer.
            StepByStepExecutionResult backEdgeResult = mock(StepByStepExecutionResult.class);
            when(backEdgeResult.context()).thenReturn(context);
            when(backEdgeResult.readyNodes()).thenReturn(Set.of("core:join"));
            when(mockBackEdgeHandler.executeBackEdgeIteration(
                eq(gateNode), eq("core:gate_a"), any(), eq(context), eq(execution),
                eq(mockEventService), any(), eq(0), anyMap()))
                .thenReturn(backEdgeResult);

            when(mockStepByStepService.getReadyNodes(runId, "0", 1)).thenReturn(Set.of());
            when(mockSignalService.getPendingResumeNodeIds(runId, "trigger:start", 1, 15L))
                .thenReturn(new HashSet<>(Set.of("core:gate_b")));

            resumeService.resumeAfterSignal(gateA);

            verify(mockStepByStepService, never()).executeNode(eq(runId), eq("core:join"), any(), anyInt(), anyString());
            verify(mockStepByStepService, never()).executeNode(eq(runId), eq("core:join"), any(), anyInt());
        }
    }
}
