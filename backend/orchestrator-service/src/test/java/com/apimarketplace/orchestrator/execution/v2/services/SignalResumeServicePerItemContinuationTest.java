package com.apimarketplace.orchestrator.execution.v2.services;

import com.apimarketplace.common.storage.service.StorageService;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.SignalResolution;
import com.apimarketplace.orchestrator.domain.execution.SignalType;
import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.execution.v2.cache.ExecutionCacheManager;
import com.apimarketplace.orchestrator.execution.v2.engine.BackEdgeHandler;
import com.apimarketplace.orchestrator.execution.v2.engine.StepByStepExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.split.SplitContextManager;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.context.ReadinessContextCache;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.services.streaming.state.RunningNodeTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Per-item continuation gate in {@code SignalResumeService.resumeAutoModeUnderLock}
 * (approval {@code continuationMode=per_item} in a split context): a per-item signal
 * WALKS on every resolution and SEALS only on the last one; all_items signals never
 * touch the {@link PerItemContinuationService}; a missing (optional) bean degrades to
 * the pre-feature all_items barrier without NPE.
 *
 * <p>Fixture mirrors {@code SignalResumeServiceTest} (constructor + reflection-injected
 * {@code @Autowired} fields).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SignalResumeService - per-item continuation gate")
class SignalResumeServicePerItemContinuationTest {

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
    @Mock private PerItemContinuationService mockPerItemContinuationService;

    private SignalResumeService resumeService;

    private static final String RUN_ID = "run-pic";
    private static final String NODE_ID = "core:item_approval";
    private static final String TRIGGER_ID = "trigger:start";
    private static final int EPOCH = 1;

    @BeforeEach
    void setUp() throws Exception {
        resumeService = new SignalResumeService(
            mockRedis, mockRunRepository, mockSplitContextManager,
            mockStorageService, mockStateSnapshotService,
            mockStepDataRepository, mockExecutionCacheManager, mockNodeSearchService,
            mockRunningNodeTracker, mockWorkflowEpochService);

        setField("v2StepByStepService", mockStepByStepService);
        setField("stepByStepContextManager", mockStepByStepContextManager);
        setField("backEdgeHandler", mockBackEdgeHandler);
        setField("eventService", mockEventService);
        setField("signalService", mockSignalService);
        setField("creditClient", mockCreditClient);
        setField("readinessCache", mockReadinessCache);
        setField("perItemContinuationService", mockPerItemContinuationService);

        // Redis dedup + distributed lock: always acquired
        lenient().when(mockRedis.opsForValue()).thenReturn(mockValueOps);
        lenient().when(mockValueOps.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class)))
            .thenReturn(true);
        // Default: blocking signals active so tryDeferredReset is a no-op
        lenient().when(mockSignalService.hasBlockingSignalsForDagAndEpoch(any(), any(), anyInt()))
            .thenReturn(true);
    }

    private void setField(String fieldName, Object value) throws Exception {
        Field field = SignalResumeService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(resumeService, value);
    }

    /** Split-context per-item USER_APPROVAL signal, APPROVED. */
    private SignalWaitEntity approvalSignal(String itemId, Map<String, Object> splitItemData) {
        SignalWaitEntity signal = mock(SignalWaitEntity.class);
        lenient().when(signal.getRunId()).thenReturn(RUN_ID);
        lenient().when(signal.getNodeId()).thenReturn(NODE_ID);
        lenient().when(signal.getItemId()).thenReturn(itemId);
        lenient().when(signal.getSignalType()).thenReturn(SignalType.USER_APPROVAL);
        lenient().when(signal.getResolution()).thenReturn(SignalResolution.APPROVED);
        lenient().when(signal.getSplitItemData()).thenReturn(splitItemData);
        lenient().when(signal.getEpoch()).thenReturn(EPOCH);
        lenient().when(signal.getDagTriggerId()).thenReturn(TRIGGER_ID);
        lenient().when(signal.getCreatedAt()).thenReturn(Instant.parse("2026-07-14T10:00:00Z"));
        lenient().when(signal.getResolvedAt()).thenReturn(Instant.parse("2026-07-14T10:00:01Z"));
        return signal;
    }

    private WorkflowRunEntity runningRun() {
        WorkflowRunEntity run = mock(WorkflowRunEntity.class);
        lenient().when(run.getStatus()).thenReturn(RunStatus.RUNNING);
        lenient().when(run.isStepByStepMode()).thenReturn(false);
        lenient().when(run.getTenantId()).thenReturn("tenant-1");
        lenient().when(run.getId()).thenReturn(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        lenient().when(run.getRunIdPublic()).thenReturn(RUN_ID);
        lenient().when(mockRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
        lenient().when(mockStorageService.saveJsonWithContext(
            anyString(), anyMap(), anyString(), any(), any(), anyString(), anyString(),
            anyInt(), anyInt(), any(), anyString()))
            .thenReturn(UUID.fromString("22222222-2222-2222-2222-222222222222"));
        return run;
    }

    private void stubSplitContext() {
        when(mockSignalService.isSplitContextNode(RUN_ID, NODE_ID, EPOCH)).thenReturn(true);
    }

    /** All sibling signals resolved AND every per-item approval output persisted. */
    private void stubLastResolution() {
        when(mockSignalService.getActiveSignals(RUN_ID, EPOCH)).thenReturn(List.of());
        when(mockSignalService.getSignalCountForNodeEpoch(RUN_ID, NODE_ID, EPOCH)).thenReturn(3L);
        when(mockStepDataRepository.countByRunIdAndNormalizedKeyAndEpochAndStatus(
            RUN_ID, NODE_ID, EPOCH, "COMPLETED")).thenReturn(3L);
    }

    private StepByStepExecutionResult successResult() {
        StepByStepExecutionResult result = mock(StepByStepExecutionResult.class);
        lenient().when(result.workflowComplete()).thenReturn(false);
        lenient().when(result.readyNodes()).thenReturn(Set.of());
        lenient().when(result.isPending()).thenReturn(false);
        lenient().when(result.isSuccess()).thenReturn(true);
        return result;
    }

    @Test
    @DisplayName("per_item signal with siblings still pending: walks the resolved item, does NOT seal, successors stay suppressed")
    void perItemSignalWithSiblingsRemainingWalksWithoutSealing() {
        SignalWaitEntity signal = approvalSignal("1", null);
        runningRun();
        stubSplitContext();
        when(mockPerItemContinuationService.isPerItemContinuation(signal)).thenReturn(true);
        SignalWaitEntity pendingSibling = mock(SignalWaitEntity.class);
        when(pendingSibling.getNodeId()).thenReturn(NODE_ID);
        when(mockSignalService.getActiveSignals(RUN_ID, EPOCH)).thenReturn(List.of(pendingSibling));
        // ReadyNodeCalculator surfaced a successor -> the split gate must SUPPRESS it.
        when(mockStepByStepService.getReadyNodes(RUN_ID, "1", EPOCH))
            .thenReturn(Set.of("core:approved_path"));

        resumeService.resumeAfterSignal(signal);

        verify(mockPerItemContinuationService).walkResolvedItem(signal);
        verify(mockPerItemContinuationService, never()).sealRegion(any(), anyInt());
        // The barrier semantics are unchanged: no successor executes on this resume.
        verify(mockStepByStepService, never()).executeNode(anyString(), anyString(), any(), anyInt(), anyString());
        verify(mockStepByStepService, never()).executeNode(anyString(), anyString(), any(), anyInt());
    }

    @Test
    @DisplayName("per_item signal, LAST resolution (no remaining actives + outputs persisted): walk AND seal both run")
    void perItemSignalLastResolutionWalksAndSeals() {
        // splitItemData carries the full items list (4) - the seal count must prefer it
        // over the per-item signal count (3): items an upstream branch routed away from
        // the approval still count for skip-record parity.
        SignalWaitEntity signal = approvalSignal("2",
            Map.of("workflowItemIndex", 7, "items", List.of("a", "b", "c", "d")));
        runningRun();
        stubSplitContext();
        stubLastResolution();
        when(mockPerItemContinuationService.isPerItemContinuation(signal)).thenReturn(true);
        when(mockStepByStepService.getReadyNodes(RUN_ID, "2", EPOCH))
            .thenReturn(Set.of("core:join"))
            .thenReturn(Set.of());
        StepByStepExecutionResult joinResult = successResult();
        when(mockStepByStepService.executeNode(RUN_ID, "core:join", "7", EPOCH, TRIGGER_ID))
            .thenReturn(joinResult);

        resumeService.resumeAfterSignal(signal);

        verify(mockPerItemContinuationService).walkResolvedItem(signal);
        verify(mockPerItemContinuationService).sealRegion(signal, 4);
        // The readiness cache is invalidated again after the seal (node-level marks changed).
        verify(mockReadinessCache, atLeast(2)).invalidateRun(RUN_ID);
        // The frontier still executes through the normal all-resolved fan-out at parent scope.
        verify(mockStepByStepService).executeNode(RUN_ID, "core:join", "7", EPOCH, TRIGGER_ID);
    }

    @Test
    @DisplayName("seal item count falls back to the per-item signal count when splitItemData has no items list")
    void sealItemCountFallsBackToSignalCount() {
        SignalWaitEntity signal = approvalSignal("2", Map.of("workflowItemIndex", 7));
        runningRun();
        stubSplitContext();
        stubLastResolution();
        when(mockPerItemContinuationService.isPerItemContinuation(signal)).thenReturn(true);
        when(mockStepByStepService.getReadyNodes(RUN_ID, "2", EPOCH))
            .thenReturn(Set.of("core:join"))
            .thenReturn(Set.of());
        StepByStepExecutionResult joinResult = successResult();
        when(mockStepByStepService.executeNode(RUN_ID, "core:join", "7", EPOCH, TRIGGER_ID))
            .thenReturn(joinResult);

        resumeService.resumeAfterSignal(signal);

        verify(mockPerItemContinuationService).sealRegion(signal, 3);
    }

    @Test
    @DisplayName("REGRESSION: all_items signal (isPerItemContinuation=false) never touches walk or seal - existing fan-out untouched")
    void allItemsSignalNeverWalksNorSeals() {
        SignalWaitEntity signal = approvalSignal("2", Map.of("workflowItemIndex", 7));
        runningRun();
        stubSplitContext();
        stubLastResolution();
        when(mockPerItemContinuationService.isPerItemContinuation(signal)).thenReturn(false);
        when(mockStepByStepService.getReadyNodes(RUN_ID, "2", EPOCH))
            .thenReturn(Set.of("core:join"))
            .thenReturn(Set.of());
        StepByStepExecutionResult joinResult = successResult();
        when(mockStepByStepService.executeNode(RUN_ID, "core:join", "7", EPOCH, TRIGGER_ID))
            .thenReturn(joinResult);

        resumeService.resumeAfterSignal(signal);

        verify(mockPerItemContinuationService, never()).walkResolvedItem(any());
        verify(mockPerItemContinuationService, never()).sealRegion(any(), anyInt());
        // Pre-feature behavior preserved: the last resolution still fans out at parent scope.
        verify(mockStepByStepService).executeNode(RUN_ID, "core:join", "7", EPOCH, TRIGGER_ID);
    }

    @Test
    @DisplayName("REGRESSION: all_items signal with siblings pending keeps the existing defer behavior (no walk, no successor)")
    void allItemsSignalWithSiblingsPendingKeepsDeferBehavior() {
        SignalWaitEntity signal = approvalSignal("1", null);
        runningRun();
        stubSplitContext();
        when(mockPerItemContinuationService.isPerItemContinuation(signal)).thenReturn(false);
        SignalWaitEntity pendingSibling = mock(SignalWaitEntity.class);
        when(pendingSibling.getNodeId()).thenReturn(NODE_ID);
        when(mockSignalService.getActiveSignals(RUN_ID, EPOCH)).thenReturn(List.of(pendingSibling));
        when(mockStepByStepService.getReadyNodes(RUN_ID, "1", EPOCH)).thenReturn(Set.of());

        resumeService.resumeAfterSignal(signal);

        verify(mockPerItemContinuationService, never()).walkResolvedItem(any());
        verify(mockPerItemContinuationService, never()).sealRegion(any(), anyInt());
        verify(mockStepByStepService, never()).executeNode(anyString(), anyString(), any(), anyInt(), anyString());
    }

    @Test
    @DisplayName("null PerItemContinuationService bean (@Autowired required=false): no NPE, all_items behavior preserved")
    void nullPerItemContinuationBeanDegradesToAllItemsWithoutNpe() throws Exception {
        setField("perItemContinuationService", null);
        SignalWaitEntity signal = approvalSignal("2", Map.of("workflowItemIndex", 7));
        runningRun();
        stubSplitContext();
        stubLastResolution();
        when(mockStepByStepService.getReadyNodes(RUN_ID, "2", EPOCH))
            .thenReturn(Set.of("core:join"))
            .thenReturn(Set.of());
        StepByStepExecutionResult joinResult = successResult();
        when(mockStepByStepService.executeNode(RUN_ID, "core:join", "7", EPOCH, TRIGGER_ID))
            .thenReturn(joinResult);

        assertDoesNotThrow(() -> resumeService.resumeAfterSignal(signal));

        verify(mockStepByStepService).executeNode(RUN_ID, "core:join", "7", EPOCH, TRIGGER_ID);
    }
}
