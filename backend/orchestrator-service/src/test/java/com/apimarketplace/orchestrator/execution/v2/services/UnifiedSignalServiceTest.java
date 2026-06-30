package com.apimarketplace.orchestrator.execution.v2.services;

import com.apimarketplace.orchestrator.domain.execution.DagState;
import com.apimarketplace.orchestrator.domain.execution.SignalConfig;
import com.apimarketplace.orchestrator.domain.execution.SignalResolution;
import com.apimarketplace.orchestrator.domain.execution.SignalType;
import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity;
import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity.SignalWaitStatus;
import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.cache.RedisCacheKeys;
import com.apimarketplace.orchestrator.repository.SignalWaitRepository;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UnifiedSignalService")
class UnifiedSignalServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-02-04T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneId.of("UTC"));

    @Mock private SignalWaitRepository signalWaitRepository;
    @Mock private StateSnapshotService stateSnapshotService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private SignalTimerScheduler timerScheduler;
    @Mock private SignalResumeRedisPublisher redisPublisher;
    @Mock private StringRedisTemplate redisTemplate;
    @SuppressWarnings("unchecked")
    @Mock private SetOperations<String, String> setOperations;

    private UnifiedSignalService service;

    @BeforeEach
    void setUp() {
        // Pass null for @Lazy self - in unit tests, self-invocation goes through 'this' directly
        // (no Spring proxy), so @Transactional self-invocation is not an issue in tests.
        service = new UnifiedSignalService(
            signalWaitRepository, stateSnapshotService, eventPublisher, timerScheduler, redisPublisher, FIXED_CLOCK, null);
    }

    @Nested
    @DisplayName("registerSignal()")
    class RegisterSignalTests {

        @Test
        @DisplayName("Should register a WAIT_TIMER signal with correct expiration and schedule in-memory timer")
        void shouldRegisterTimerSignal() {
            long durationMs = 60_000;
            Map<String, Object> config = SignalConfig.timer(durationMs);

            when(signalWaitRepository.saveAndFlush(any(SignalWaitEntity.class)))
                .thenAnswer(inv -> {
                    SignalWaitEntity e = inv.getArgument(0);
                    e.setId(1L);
                    return e;
                });

            SignalWaitEntity result = service.registerSignal(
                "run-1", "0", "core:wait_1", "trigger:webhook", 5,
                SignalType.WAIT_TIMER, config, null);

            assertNotNull(result);
            assertEquals("run-1", result.getRunId());
            assertEquals("core:wait_1", result.getNodeId());
            assertEquals(SignalType.WAIT_TIMER, result.getSignalType());
            assertEquals(SignalWaitStatus.PENDING, result.getStatus());
            assertEquals(5, result.getEpoch());
            assertEquals(FIXED_NOW, result.getCreatedAt());
            assertEquals(FIXED_NOW.plusMillis(durationMs), result.getExpiresAt());

            verify(signalWaitRepository).saveAndFlush(any(SignalWaitEntity.class));
            verify(stateSnapshotService).markNodeAwaitingSignal("run-1", "trigger:webhook", 5, "core:wait_1");
            verify(timerScheduler).schedule(1L, FIXED_NOW.plusMillis(durationMs));
        }

        @Test
        @DisplayName("Should register a USER_APPROVAL signal with timeout and schedule in-memory timer")
        void shouldRegisterApprovalSignal() {
            long timeoutMs = 86400000L;
            Map<String, Object> config = SignalConfig.userApproval(
                List.of("manager"), 1, java.time.Duration.ofMillis(timeoutMs));

            when(signalWaitRepository.saveAndFlush(any(SignalWaitEntity.class)))
                .thenAnswer(inv -> {
                    SignalWaitEntity e = inv.getArgument(0);
                    e.setId(2L);
                    return e;
                });

            SignalWaitEntity result = service.registerSignal(
                "run-2", "0", "core:approval_1", "trigger:manual", 0,
                SignalType.USER_APPROVAL, config, null);

            assertNotNull(result);
            assertEquals(SignalType.USER_APPROVAL, result.getSignalType());
            assertEquals(FIXED_NOW.plusMillis(timeoutMs), result.getExpiresAt());
            verify(timerScheduler).schedule(2L, FIXED_NOW.plusMillis(timeoutMs));
        }

        @Test
        @DisplayName("Should NOT schedule timer when no expiration")
        void shouldNotScheduleTimerWhenNoExpiration() {
            // INTERFACE_SIGNAL has no timeout concept → no expiresAt → no timer
            Map<String, Object> config = SignalConfig.interfaceSignal("iface-123", Map.of());

            when(signalWaitRepository.saveAndFlush(any(SignalWaitEntity.class)))
                .thenAnswer(inv -> {
                    SignalWaitEntity e = inv.getArgument(0);
                    e.setId(3L);
                    return e;
                });

            service.registerSignal(
                "run-3", "0", "interface:form", "trigger:manual", 0,
                SignalType.INTERFACE_SIGNAL, config, null);

            verify(timerScheduler, never()).schedule(anyLong(), any(Instant.class));
        }

        @Test
        @DisplayName("Should default itemId to '0' when null")
        void shouldDefaultItemId() {
            Map<String, Object> config = SignalConfig.timer(5000);

            when(signalWaitRepository.saveAndFlush(any(SignalWaitEntity.class)))
                .thenAnswer(inv -> {
                    SignalWaitEntity e = inv.getArgument(0);
                    e.setId(4L);
                    return e;
                });

            SignalWaitEntity result = service.registerSignal(
                "run-4", null, "core:wait_1", null, 0,
                SignalType.WAIT_TIMER, config, null);

            assertEquals("0", result.getItemId());
        }

        @Test
        @DisplayName("Should persist splitItemData when provided")
        void shouldPersistSplitItemData() {
            Map<String, Object> config = SignalConfig.timer(10000);
            Map<String, Object> splitData = Map.of(
                "splitNodeId", "core:my_split",
                "itemIndex", 3);

            when(signalWaitRepository.saveAndFlush(any(SignalWaitEntity.class)))
                .thenAnswer(inv -> {
                    SignalWaitEntity e = inv.getArgument(0);
                    e.setId(5L);
                    return e;
                });

            SignalWaitEntity result = service.registerSignal(
                "run-5", "0", "core:wait_1", null, 0,
                SignalType.WAIT_TIMER, config, splitData);

            assertNotNull(result.getSplitItemData());
            assertEquals("core:my_split", result.getSplitItemData().get("splitNodeId"));
        }

        @Test
        @DisplayName("Should NOT persist splitItemData when null")
        void shouldNotPersistNullSplitItemData() {
            Map<String, Object> config = SignalConfig.timer(5000);

            when(signalWaitRepository.saveAndFlush(any(SignalWaitEntity.class)))
                .thenAnswer(inv -> {
                    SignalWaitEntity e = inv.getArgument(0);
                    e.setId(6L);
                    return e;
                });

            SignalWaitEntity result = service.registerSignal(
                "run-6", "0", "core:wait_1", null, 0,
                SignalType.WAIT_TIMER, config, null);

            assertNull(result.getSplitItemData());
        }
    }

    @Nested
    @DisplayName("resolveSignal()")
    class ResolveSignalTests {

        @Test
        @DisplayName("Should resolve signal with claim-before-process pattern and cancel in-memory timer")
        void shouldResolveSignalSuccessfully() {
            Long signalId = 10L;

            // Step 1: Claim succeeds
            when(signalWaitRepository.claimSignal(eq(signalId), any(Instant.class), anyString()))
                .thenReturn(1);
            // Step 2: Resolve succeeds
            when(signalWaitRepository.resolveClaimedSignal(eq(signalId), eq("COMPLETED"), any(Instant.class), anyString()))
                .thenReturn(1);
            // Step 3: Reload entity
            SignalWaitEntity entity = new SignalWaitEntity();
            entity.setId(signalId);
            entity.setRunId("run-1");
            entity.setNodeId("core:wait_1");
            entity.setStatus(SignalWaitStatus.RESOLVED);
            when(signalWaitRepository.findById(signalId)).thenReturn(Optional.of(entity));
            when(signalWaitRepository.save(entity)).thenReturn(entity);

            boolean result = service.resolveSignal(signalId, SignalResolution.COMPLETED, Map.of(), "poller");

            assertTrue(result);
            verify(timerScheduler).cancel(signalId);
            // createdAt is null on entity → waitDurationMs = 0
            verify(stateSnapshotService).resolveAwaitingSignal("run-1", "core:wait_1", 0L);
            verify(eventPublisher).publishEvent(any(SignalResolvedEvent.class));
        }

        @Test
        @DisplayName("Should return false when signal already claimed")
        void shouldReturnFalseWhenAlreadyClaimed() {
            Long signalId = 11L;
            when(signalWaitRepository.claimSignal(eq(signalId), any(Instant.class), anyString()))
                .thenReturn(0); // already claimed

            boolean result = service.resolveSignal(signalId, SignalResolution.COMPLETED, Map.of(), "poller");

            assertFalse(result);
            verify(timerScheduler).cancel(signalId);
            verify(stateSnapshotService, never()).resolveAwaitingSignal(anyString(), anyString(), anyLong());
            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("Should return false when resolve fails after claim")
        void shouldReturnFalseWhenResolveFails() {
            Long signalId = 12L;
            when(signalWaitRepository.claimSignal(eq(signalId), any(Instant.class), anyString()))
                .thenReturn(1); // claim succeeds
            when(signalWaitRepository.resolveClaimedSignal(eq(signalId), eq("APPROVED"), any(Instant.class), anyString()))
                .thenReturn(0); // resolve fails

            boolean result = service.resolveSignal(signalId, SignalResolution.APPROVED, Map.of(), "user-1");

            assertFalse(result);
            verify(stateSnapshotService, never()).resolveAwaitingSignal(anyString(), anyString(), anyLong());
        }

        @Test
        @DisplayName("Should store resolution data on entity")
        void shouldStoreResolutionData() {
            Long signalId = 13L;
            Map<String, Object> resolutionData = Map.of("comment", "Looks good");

            when(signalWaitRepository.claimSignal(eq(signalId), any(), anyString())).thenReturn(1);
            when(signalWaitRepository.resolveClaimedSignal(eq(signalId), anyString(), any(), anyString())).thenReturn(1);

            SignalWaitEntity entity = new SignalWaitEntity();
            entity.setId(signalId);
            entity.setRunId("run-1");
            entity.setNodeId("core:approval_1");
            when(signalWaitRepository.findById(signalId)).thenReturn(Optional.of(entity));
            when(signalWaitRepository.save(entity)).thenReturn(entity);

            service.resolveSignal(signalId, SignalResolution.APPROVED, resolutionData, "user-1");

            ArgumentCaptor<SignalWaitEntity> captor = ArgumentCaptor.forClass(SignalWaitEntity.class);
            verify(signalWaitRepository).save(captor.capture());
            assertEquals(resolutionData, captor.getValue().getResolutionData());
        }

        @Test
        @DisplayName("Should publish SignalResolvedEvent on successful resolution")
        void shouldPublishEvent() {
            Long signalId = 14L;
            when(signalWaitRepository.claimSignal(eq(signalId), any(), anyString())).thenReturn(1);
            when(signalWaitRepository.resolveClaimedSignal(eq(signalId), anyString(), any(), anyString())).thenReturn(1);

            SignalWaitEntity entity = new SignalWaitEntity();
            entity.setId(signalId);
            entity.setRunId("run-1");
            entity.setNodeId("core:wait_1");
            when(signalWaitRepository.findById(signalId)).thenReturn(Optional.of(entity));
            when(signalWaitRepository.save(entity)).thenReturn(entity);

            service.resolveSignal(signalId, SignalResolution.COMPLETED, Map.of(), "poller");

            ArgumentCaptor<SignalResolvedEvent> eventCaptor = ArgumentCaptor.forClass(SignalResolvedEvent.class);
            verify(eventPublisher).publishEvent(eventCaptor.capture());
            assertEquals(signalId, eventCaptor.getValue().getResolvedSignal().getId());
        }

        @Test
        @DisplayName("regression: resolved blocking signal marks pending resume before event so reusable trigger cannot close epoch")
        void resolvedBlockingSignalMarksPendingResumeBeforeEvent() {
            Long signalId = 300L;
            String key = RedisCacheKeys.signalResumePending("run-race", "trigger:start", 1);
            ReflectionTestUtils.setField(service, "redisTemplate", redisTemplate);

            when(redisTemplate.opsForSet()).thenReturn(setOperations);
            when(signalWaitRepository.claimSignal(eq(signalId), any(), anyString())).thenReturn(1);
            when(signalWaitRepository.findResumeInfoById(signalId)).thenReturn(Optional.of(
                new SignalWaitRepository.ResumeInfo("run-race", "trigger:start", 1, true)));
            when(signalWaitRepository.resolveClaimedSignal(eq(signalId), eq("APPROVED"), any(), anyString())).thenReturn(1);

            SignalWaitEntity entity = new SignalWaitEntity();
            entity.setId(signalId);
            entity.setRunId("run-race");
            entity.setNodeId("core:manager_approval");
            entity.setItemId("0");
            entity.setDagTriggerId("trigger:start");
            entity.setEpoch(1);
            entity.setSignalType(SignalType.USER_APPROVAL);
            entity.setBlocking(true);
            entity.setCreatedAt(FIXED_NOW.minusSeconds(2));
            when(signalWaitRepository.findById(signalId)).thenReturn(Optional.of(entity));
            when(signalWaitRepository.save(any(SignalWaitEntity.class))).thenAnswer(inv -> inv.getArgument(0));
            when(signalWaitRepository.countActiveByRunIdAndNodeIdAndDagAndEpoch(
                "run-race", "core:manager_approval", "trigger:start", 1))
                .thenReturn(0L);

            boolean resolved = service.resolveSignal(signalId, SignalResolution.APPROVED, Map.of(), "user-1");

            assertTrue(resolved);
            var order = inOrder(signalWaitRepository, setOperations, eventPublisher);
            order.verify(signalWaitRepository).claimSignal(eq(signalId), any(), eq("user-1"));
            order.verify(setOperations).add(key, String.valueOf(signalId));
            order.verify(signalWaitRepository).resolveClaimedSignal(eq(signalId), eq("APPROVED"), any(), eq("user-1"));
            order.verify(eventPublisher).publishEvent(any(SignalResolvedEvent.class));
            verify(redisTemplate).expire(eq(key), eq(Duration.ofMinutes(2)));
        }

        @Test
        @DisplayName("regression: cancelled approval signal marks awaiting node SKIPPED instead of COMPLETED")
        void cancelledApprovalSignalSkipsAwaitingNodeInsteadOfCompletingIt() {
            Long signalId = 301L;
            when(signalWaitRepository.claimSignal(eq(signalId), any(), anyString())).thenReturn(1);
            when(signalWaitRepository.resolveClaimedSignal(eq(signalId), eq("CANCELLED"), any(), anyString()))
                .thenReturn(1);

            SignalWaitEntity entity = new SignalWaitEntity();
            entity.setId(signalId);
            entity.setRunId("run-cancel");
            entity.setNodeId("core:manager_approval");
            entity.setDagTriggerId("trigger:start");
            entity.setEpoch(4);
            entity.setSignalType(SignalType.USER_APPROVAL);
            entity.setCreatedAt(FIXED_NOW.minusSeconds(5));
            when(signalWaitRepository.findById(signalId)).thenReturn(Optional.of(entity));
            when(signalWaitRepository.save(any(SignalWaitEntity.class))).thenAnswer(inv -> inv.getArgument(0));

            boolean resolved = service.resolveSignal(signalId, SignalResolution.CANCELLED, Map.of(), "user-1");

            assertTrue(resolved);
            verify(stateSnapshotService).skipAwaitingSignal(
                "run-cancel", "trigger:start", 4, "core:manager_approval");
            verify(stateSnapshotService, never()).resolveAwaitingSignal(
                anyString(), anyString(), anyInt(), anyString(), anyLong(), anyBoolean());
            verify(eventPublisher).publishEvent(any(SignalResolvedEvent.class));
        }
    }

    @Nested
    @DisplayName("resolveSignal() - keepInAwaiting scope (regression: cross-epoch wait-stuck, 2026-05-06)")
    class KeepInAwaitingScopeTests {

        @Test
        @DisplayName("hasRemainingSignals is scoped to (run, node, dag, epoch) - sibling signal in OTHER epoch must NOT keep this epoch's wait stuck (regression run_<id>)")
        void otherEpochSignalDoesNotKeepThisEpochWaitInAwaiting() {
            Long signalId = 200L;

            SignalWaitEntity entity = new SignalWaitEntity();
            entity.setId(signalId);
            entity.setRunId("run-X");
            entity.setNodeId("core:wait");
            entity.setDagTriggerId("trigger:manuala");
            entity.setEpoch(4); // resolving epoch 4
            entity.setSignalType(SignalType.WAIT_TIMER);
            entity.setStatus(SignalWaitStatus.PENDING);
            entity.setCreatedAt(FIXED_NOW.minusSeconds(60));

            // Guard projection (closed-epoch zombie check). Epoch 4 is still active
            // → guard returns false → resolution proceeds.
            when(signalWaitRepository.findEpochInfoById(signalId)).thenReturn(Optional.of(
                new com.apimarketplace.orchestrator.repository.SignalWaitRepository.EpochInfo(
                    "run-X", "trigger:manuala", 4, FIXED_NOW.minusSeconds(60))));
            when(stateSnapshotService.getSnapshot("run-X"))
                .thenReturn(new StateSnapshot(3, 0L,
                    Map.of("trigger:manuala", DagState.initial().openEpoch(4)),
                    null, null, null, null, null, null, null, null, null, null, null, null, null));

            when(signalWaitRepository.claimSignal(eq(signalId), any(Instant.class), anyString())).thenReturn(1);
            when(signalWaitRepository.resolveClaimedSignal(eq(signalId), anyString(), any(Instant.class), anyString())).thenReturn(1);
            when(signalWaitRepository.findById(signalId)).thenReturn(Optional.of(entity));
            when(signalWaitRepository.save(any(SignalWaitEntity.class))).thenAnswer(inv -> inv.getArgument(0));

            // Critical mock: epoch 4 has 0 active siblings.
            // (A sibling in epoch 3 - the prod regression - would NOT count here because
            // the new query is scoped to dagTriggerId+epoch.)
            when(signalWaitRepository.countActiveByRunIdAndNodeIdAndDagAndEpoch(
                    "run-X", "core:wait", "trigger:manuala", 4))
                .thenReturn(0L);

            service.resolveSignal(signalId, SignalResolution.COMPLETED, Map.of(), "poller");

            // The fix: keepInAwaiting MUST be false → wait moves from awaiting to completed.
            verify(stateSnapshotService).resolveAwaitingSignal(
                eq("run-X"), eq("trigger:manuala"), eq(4), eq("core:wait"),
                anyLong(), eq(false));
        }

        @Test
        @DisplayName("Split context: sibling signals in SAME epoch DO keep wait in awaiting (one item resolves, others still pending)")
        void splitContextSiblingsKeepWaitInAwaiting() {
            Long signalId = 201L;

            SignalWaitEntity entity = new SignalWaitEntity();
            entity.setId(signalId);
            entity.setRunId("run-Y");
            entity.setNodeId("agent:classify");
            entity.setItemId("0");
            entity.setDagTriggerId("trigger:webhook");
            entity.setEpoch(5);
            entity.setSignalType(SignalType.AGENT_EXECUTION);
            entity.setStatus(SignalWaitStatus.PENDING);
            entity.setCreatedAt(FIXED_NOW.minusSeconds(30));

            when(signalWaitRepository.findEpochInfoById(signalId)).thenReturn(Optional.of(
                new com.apimarketplace.orchestrator.repository.SignalWaitRepository.EpochInfo(
                    "run-Y", "trigger:webhook", 5, FIXED_NOW.minusSeconds(30))));
            when(stateSnapshotService.getSnapshot("run-Y"))
                .thenReturn(new StateSnapshot(3, 0L,
                    Map.of("trigger:webhook", DagState.initial().openEpoch(5)),
                    null, null, null, null, null, null, null, null, null, null, null, null, null));

            when(signalWaitRepository.claimSignal(eq(signalId), any(Instant.class), anyString())).thenReturn(1);
            when(signalWaitRepository.resolveClaimedSignal(eq(signalId), anyString(), any(Instant.class), anyString())).thenReturn(1);
            when(signalWaitRepository.findById(signalId)).thenReturn(Optional.of(entity));
            when(signalWaitRepository.save(any(SignalWaitEntity.class))).thenAnswer(inv -> inv.getArgument(0));

            // 4 sibling items still active in the same (dag, epoch) - split fan-out.
            when(signalWaitRepository.countActiveByRunIdAndNodeIdAndDagAndEpoch(
                    "run-Y", "agent:classify", "trigger:webhook", 5))
                .thenReturn(4L);

            service.resolveSignal(signalId, SignalResolution.COMPLETED, Map.of(), "user");

            // keepInAwaiting MUST be true so split context keeps node in awaiting until ALL items resolve.
            verify(stateSnapshotService).resolveAwaitingSignal(
                eq("run-Y"), eq("trigger:webhook"), eq(5), eq("agent:classify"),
                anyLong(), eq(true));
        }

        @Test
        @DisplayName("Old run+node-wide findActiveByRunIdAndNodeId is NOT consulted as the keepInAwaiting gate (regression-of-regression: ensures the cross-epoch fix isn't silently reverted)")
        void doesNotUseLegacyRunWideQueryAsKeepInAwaitingGate() {
            // The pre-fix code used `findActiveByRunIdAndNodeId(runId, nodeId)` - which
            // pulled in signals from OTHER epochs of the same wait node and erroneously
            // kept the resolving epoch's wait in awaiting. The fix scopes to (run, node,
            // dag, epoch) via countActiveByRunIdAndNodeIdAndDagAndEpoch. This test
            // guards against a refactor that re-introduces the old query for this gate
            // (e.g. a "DRY" extraction that pulls back the broader query). The new
            // epoch-scoped count MAY still be called by other paths; what matters is
            // that the OLD method is NOT consulted from the keepInAwaiting decision in
            // resolveSignal.
            Long signalId = 202L;
            SignalWaitEntity entity = new SignalWaitEntity();
            entity.setId(signalId);
            entity.setRunId("run-Z"); entity.setNodeId("core:wait"); entity.setItemId("0");
            entity.setDagTriggerId("trigger:cron"); entity.setEpoch(11);
            entity.setSignalType(SignalType.WAIT_TIMER);
            entity.setStatus(SignalWaitStatus.PENDING);
            entity.setCreatedAt(FIXED_NOW.minusSeconds(60));

            when(signalWaitRepository.findEpochInfoById(signalId)).thenReturn(Optional.of(
                new com.apimarketplace.orchestrator.repository.SignalWaitRepository.EpochInfo(
                    "run-Z", "trigger:cron", 11, FIXED_NOW.minusSeconds(60))));
            when(stateSnapshotService.getSnapshot("run-Z"))
                .thenReturn(new StateSnapshot(3, 0L,
                    Map.of("trigger:cron", DagState.initial().openEpoch(11)),
                    null, null, null, null, null, null, null, null, null, null, null, null, null));
            when(signalWaitRepository.claimSignal(eq(signalId), any(Instant.class), anyString())).thenReturn(1);
            when(signalWaitRepository.resolveClaimedSignal(eq(signalId), anyString(), any(Instant.class), anyString())).thenReturn(1);
            when(signalWaitRepository.findById(signalId)).thenReturn(Optional.of(entity));
            when(signalWaitRepository.save(any(SignalWaitEntity.class))).thenAnswer(inv -> inv.getArgument(0));
            when(signalWaitRepository.countActiveByRunIdAndNodeIdAndDagAndEpoch(
                    "run-Z", "core:wait", "trigger:cron", 11))
                .thenReturn(0L);

            service.resolveSignal(signalId, SignalResolution.COMPLETED, Map.of(), "poller");

            // Critical assertion: the legacy run+node-wide query MUST NOT be called for
            // the keepInAwaiting decision. (It may be called by hasRemainingSignals
            // checks elsewhere in the codebase, but those aren't on this path.)
            verify(signalWaitRepository, never())
                .findActiveByRunIdAndNodeId(anyString(), anyString());
            // And the new epoch-scoped query MUST have been called.
            verify(signalWaitRepository).countActiveByRunIdAndNodeIdAndDagAndEpoch(
                "run-Z", "core:wait", "trigger:cron", 11);
        }
    }

    @Nested
    @DisplayName("registerSignal() - race-tolerant insert (regression: parallel-fire uq_signal_waits violation, 2026-05-06)")
    class InsertRaceRecoveryTests {

        @Test
        @DisplayName("insertOrFindExisting returns the saveAndFlush result on the happy path")
        void happyPathReturnsSaveResult() {
            SignalWaitEntity input = new SignalWaitEntity();
            input.setRunId("run-x"); input.setNodeId("core:wait"); input.setItemId("0"); input.setEpoch(1);

            SignalWaitEntity saved = new SignalWaitEntity();
            saved.setId(42L);
            saved.setRunId("run-x"); saved.setNodeId("core:wait"); saved.setItemId("0"); saved.setEpoch(1);
            when(signalWaitRepository.saveAndFlush(input)).thenReturn(saved);

            SignalWaitEntity result = service.insertOrFindExisting(input, null);

            assertSame(saved, result);
            verify(signalWaitRepository, never())
                .findByRunIdAndNodeIdAndItemIdAndEpoch(anyString(), anyString(), anyString(), anyInt());
            // No stale row to replace → no delete at all on the happy path.
            verify(signalWaitRepository, never()).delete(any(SignalWaitEntity.class));
        }

        @Test
        @DisplayName("Recovers from DataIntegrityViolationException by returning the row inserted by the winning thread")
        void recoversFromUniqueViolationByReturningWinner() {
            SignalWaitEntity loser = new SignalWaitEntity();
            loser.setRunId("run-r"); loser.setNodeId("core:wait"); loser.setItemId("0"); loser.setEpoch(7);

            SignalWaitEntity winner = new SignalWaitEntity();
            winner.setId(99L);
            winner.setRunId("run-r"); winner.setNodeId("core:wait"); winner.setItemId("0"); winner.setEpoch(7);

            when(signalWaitRepository.saveAndFlush(loser))
                .thenThrow(new DataIntegrityViolationException("uq_signal_waits violation"));
            when(signalWaitRepository.findByRunIdAndNodeIdAndItemIdAndEpoch("run-r", "core:wait", "0", 7))
                .thenReturn(Optional.of(winner));

            SignalWaitEntity result = service.insertOrFindExisting(loser, null);

            assertSame(winner, result);
            assertEquals(99L, result.getId());
        }

        @Test
        @DisplayName("Re-throws DataIntegrityViolationException when winner row cannot be located (truly broken state)")
        void rethrowsWhenNoWinnerFound() {
            SignalWaitEntity loser = new SignalWaitEntity();
            loser.setRunId("run-r"); loser.setNodeId("core:wait"); loser.setItemId("0"); loser.setEpoch(7);

            DataIntegrityViolationException violation =
                new DataIntegrityViolationException("uq_signal_waits violation");
            when(signalWaitRepository.saveAndFlush(loser)).thenThrow(violation);
            when(signalWaitRepository.findByRunIdAndNodeIdAndItemIdAndEpoch("run-r", "core:wait", "0", 7))
                .thenReturn(Optional.empty());

            DataIntegrityViolationException ex = assertThrows(DataIntegrityViolationException.class,
                () -> service.insertOrFindExisting(loser, null));
            assertSame(violation, ex);
        }
    }

    @Nested
    @DisplayName("registerSignal() - stale resolved row replacement (regression: rerun→re-step lock-timeout self-deadlock, 2026-06-11)")
    class StaleResolvedRowReplacementTests {

        /**
         * Bug: a step rerun cancels the downstream approval's signal (status=CANCELLED).
         * Re-stepping the approval re-enters registerSignal, which used to DELETE the
         * stale row in the OUTER transaction and then INSERT the replacement inside the
         * REQUIRES_NEW insertOrFindExisting - the inner INSERT blocked on the outer's
         * uncommitted DELETE of the same (run,node,item,epoch) unique key until Postgres
         * lock_timeout killed it (node FAILED, signal never re-registered). The fix moves
         * the delete INTO insertOrFindExisting so delete+insert share one transaction.
         */
        @Test
        @DisplayName("regression: registerSignal never deletes the stale CANCELLED row in the outer transaction")
        void staleRowIsNotDeletedInOuterTransaction() {
            SignalWaitEntity stale = new SignalWaitEntity();
            stale.setId(7L);
            stale.setRunId("run-rr"); stale.setNodeId("core:boss_ok"); stale.setItemId("0"); stale.setEpoch(1);
            stale.setStatus(SignalWaitStatus.CANCELLED);

            when(signalWaitRepository.findByRunIdAndNodeIdAndItemIdAndEpoch("run-rr", "core:boss_ok", "0", 1))
                .thenReturn(Optional.of(stale));
            // self == service in unit tests, so insertOrFindExisting runs inline: emulate
            // its internal findById(staleId) + delete + save sequence.
            when(signalWaitRepository.findById(7L)).thenReturn(Optional.of(stale));
            when(signalWaitRepository.saveAndFlush(any(SignalWaitEntity.class)))
                .thenAnswer(inv -> {
                    SignalWaitEntity e = inv.getArgument(0);
                    e.setId(8L);
                    return e;
                });

            SignalWaitEntity result = service.registerSignal(
                "run-rr", "0", "core:boss_ok", "trigger:start", 1,
                SignalType.USER_APPROVAL,
                SignalConfig.userApproval(List.of("owner"), 1, Duration.ofMinutes(10)), null);

            // The replacement happened: stale row deleted exactly once (inside
            // insertOrFindExisting, i.e. AFTER the findById re-load), fresh row saved.
            org.mockito.InOrder inOrder = inOrder(signalWaitRepository);
            inOrder.verify(signalWaitRepository).findByRunIdAndNodeIdAndItemIdAndEpoch("run-rr", "core:boss_ok", "0", 1);
            inOrder.verify(signalWaitRepository).findById(7L);
            inOrder.verify(signalWaitRepository).delete(stale);
            inOrder.verify(signalWaitRepository).saveAndFlush(any(SignalWaitEntity.class));
            assertEquals(8L, result.getId());
            assertEquals(SignalWaitStatus.PENDING, result.getStatus());
        }

        @Test
        @DisplayName("Stale row vanished before the inner delete (concurrent replacement) → delete skipped, insert proceeds")
        void vanishedStaleRowIsSkipped() {
            SignalWaitEntity input = new SignalWaitEntity();
            input.setRunId("run-rr"); input.setNodeId("core:boss_ok"); input.setItemId("0"); input.setEpoch(1);

            when(signalWaitRepository.findById(7L)).thenReturn(Optional.empty());
            when(signalWaitRepository.saveAndFlush(input)).thenAnswer(inv -> {
                SignalWaitEntity e = inv.getArgument(0);
                e.setId(9L);
                return e;
            });

            SignalWaitEntity result = service.insertOrFindExisting(input, 7L);

            assertEquals(9L, result.getId());
            verify(signalWaitRepository, never()).delete(any(SignalWaitEntity.class));
        }

        @Test
        @DisplayName("Existing PENDING signal still short-circuits (idempotent path untouched by the fix)")
        void pendingSignalStillIdempotent() {
            SignalWaitEntity pending = new SignalWaitEntity();
            pending.setId(5L);
            pending.setRunId("run-rr"); pending.setNodeId("core:boss_ok"); pending.setItemId("0"); pending.setEpoch(1);
            pending.setStatus(SignalWaitStatus.PENDING);

            when(signalWaitRepository.findByRunIdAndNodeIdAndItemIdAndEpoch("run-rr", "core:boss_ok", "0", 1))
                .thenReturn(Optional.of(pending));

            SignalWaitEntity result = service.registerSignal(
                "run-rr", "0", "core:boss_ok", "trigger:start", 1,
                SignalType.USER_APPROVAL,
                SignalConfig.userApproval(List.of("owner"), 1, Duration.ofMinutes(10)), null);

            assertSame(pending, result);
            verify(signalWaitRepository, never()).delete(any(SignalWaitEntity.class));
            verify(signalWaitRepository, never()).saveAndFlush(any(SignalWaitEntity.class));
        }
    }

    @Nested
    @DisplayName("resolveSignal() - closed-epoch zombie guard (regression: WAIT_TIMER infinite loop, 2026-05-06)")
    class ClosedEpochZombieGuardTests {

        /**
         * Build a StateSnapshot whose only DAG has the given epoch in its activeEpochs.
         */
        private StateSnapshot snapshotWithActiveEpoch(String dagTriggerId, int epoch) {
            DagState dag = DagState.initial().openEpoch(epoch);
            return new StateSnapshot(
                3, 0L, Map.of(dagTriggerId, dag),
                null, null, null, null, null, null, null, null, null, null, null, null, null);
        }

        @Test
        @DisplayName("Hard-cancels signal without claim/resolve when owning epoch is no longer active (orphan from previous trigger fire)")
        void closedEpochSignalIsHardCancelledNotResolved() {
            Long signalId = 99L;

            SignalWaitEntity zombie = new SignalWaitEntity();
            zombie.setId(signalId);
            zombie.setRunId("run-zomb");
            zombie.setDagTriggerId("trigger:webhook");
            zombie.setEpoch(2);
            zombie.setSignalType(SignalType.WAIT_TIMER);
            zombie.setStatus(SignalWaitStatus.PENDING);

            when(signalWaitRepository.findEpochInfoById(signalId)).thenReturn(Optional.of(
                new com.apimarketplace.orchestrator.repository.SignalWaitRepository.EpochInfo(
                    "run-zomb", "trigger:webhook", 2, FIXED_NOW.minusSeconds(60))));
            // Empty snapshot → no DAGs → epoch 2 not in activeEpochs (closed/pruned)
            when(stateSnapshotService.getSnapshot("run-zomb")).thenReturn(StateSnapshot.empty());
            when(signalWaitRepository.cancelById(eq(signalId), any(Instant.class))).thenReturn(1);

            boolean result = service.resolveSignal(signalId, SignalResolution.COMPLETED, Map.of(), "poller");

            assertFalse(result);
            verify(timerScheduler).cancel(signalId);
            verify(signalWaitRepository).cancelById(signalId, FIXED_NOW);
            // Critical: zombie path must NOT trigger claim/resolve/event/snapshot mutation
            verify(signalWaitRepository, never()).claimSignal(anyLong(), any(), anyString());
            verify(signalWaitRepository, never()).resolveClaimedSignal(anyLong(), anyString(), any(), anyString());
            verify(eventPublisher, never()).publishEvent(any());
            verify(stateSnapshotService, never()).resolveAwaitingSignal(anyString(), anyString(), anyInt(), anyString(), anyLong(), anyBoolean());
        }

        @Test
        @DisplayName("Resolves normally when owning epoch is still active (regression guard: do not over-cancel legit fresh signals)")
        void activeEpochSignalIsResolvedNormally() {
            Long signalId = 100L;

            SignalWaitEntity active = new SignalWaitEntity();
            active.setId(signalId);
            active.setRunId("run-active");
            active.setNodeId("core:wait_x");
            active.setDagTriggerId("trigger:webhook");
            active.setEpoch(7);
            active.setSignalType(SignalType.WAIT_TIMER);
            active.setStatus(SignalWaitStatus.PENDING);

            // The guard reads the projection (no L1 cache pollution); resolveSignal step 3
            // separately calls findById to reload the entity for the event publish.
            when(signalWaitRepository.findEpochInfoById(signalId)).thenReturn(Optional.of(
                new com.apimarketplace.orchestrator.repository.SignalWaitRepository.EpochInfo(
                    "run-active", "trigger:webhook", 7, FIXED_NOW.minusSeconds(60))));
            when(signalWaitRepository.findById(signalId)).thenReturn(Optional.of(active));
            when(stateSnapshotService.getSnapshot("run-active"))
                .thenReturn(snapshotWithActiveEpoch("trigger:webhook", 7));

            when(signalWaitRepository.claimSignal(eq(signalId), any(Instant.class), anyString())).thenReturn(1);
            when(signalWaitRepository.resolveClaimedSignal(eq(signalId), eq("COMPLETED"), any(Instant.class), anyString())).thenReturn(1);
            when(signalWaitRepository.save(any(SignalWaitEntity.class))).thenAnswer(inv -> inv.getArgument(0));
            // Sibling signals scoped to (run, node, dag, epoch) - none active for this fresh resolution.
            when(signalWaitRepository.countActiveByRunIdAndNodeIdAndDagAndEpoch(
                    "run-active", "core:wait_x", "trigger:webhook", 7))
                .thenReturn(0L);

            boolean result = service.resolveSignal(signalId, SignalResolution.COMPLETED, Map.of(), "poller");

            assertTrue(result);
            verify(signalWaitRepository, never()).cancelById(anyLong(), any());
            verify(signalWaitRepository).claimSignal(eq(signalId), any(Instant.class), anyString());
            verify(eventPublisher).publishEvent(any(SignalResolvedEvent.class));
        }

        @Test
        @DisplayName("Does NOT zombie-cancel a freshly-registered signal whose snapshot mutation has not yet committed (regression: registerSignal/timer-fire race)")
        void freshSignalSkippedEvenWhenEpochAbsentFromSnapshot() {
            Long signalId = 102L;

            SignalWaitEntity fresh = new SignalWaitEntity();
            fresh.setId(signalId);
            fresh.setRunId("run-fresh");
            fresh.setNodeId("core:wait_y");
            fresh.setDagTriggerId("trigger:webhook");
            fresh.setEpoch(9);
            // 500ms ago - well within the 2s age gate
            fresh.setCreatedAt(FIXED_NOW.minusMillis(500));
            fresh.setSignalType(SignalType.WAIT_TIMER);
            fresh.setStatus(SignalWaitStatus.PENDING);

            when(signalWaitRepository.findEpochInfoById(signalId)).thenReturn(Optional.of(
                new com.apimarketplace.orchestrator.repository.SignalWaitRepository.EpochInfo(
                    "run-fresh", "trigger:webhook", 9, FIXED_NOW.minusMillis(500))));
            when(signalWaitRepository.findById(signalId)).thenReturn(Optional.of(fresh));
            // Note: snapshot is empty - epoch 9 is NOT in activeEpochs. Without the age
            // gate this would falsely look like a zombie. With the gate, the guard skips
            // because the signal is too young (createdAt = now - 500ms < 2000ms).
            when(signalWaitRepository.claimSignal(eq(signalId), any(Instant.class), anyString())).thenReturn(1);
            when(signalWaitRepository.resolveClaimedSignal(eq(signalId), anyString(), any(Instant.class), anyString())).thenReturn(1);
            when(signalWaitRepository.save(any(SignalWaitEntity.class))).thenAnswer(inv -> inv.getArgument(0));
            when(signalWaitRepository.countActiveByRunIdAndNodeIdAndDagAndEpoch(
                    "run-fresh", "core:wait_y", "trigger:webhook", 9))
                .thenReturn(0L);

            boolean result = service.resolveSignal(signalId, SignalResolution.COMPLETED, Map.of(), "redis-timer");

            assertTrue(result);
            verify(signalWaitRepository, never()).cancelById(anyLong(), any());
            // Snapshot lookup must not even be attempted while inside the age gate window
            verify(stateSnapshotService, never()).getSnapshot(anyString());
        }

        @Test
        @DisplayName("Skips zombie guard when entity has no dagTriggerId (legacy / pre-multi-DAG signal)")
        void legacySignalWithoutDagTriggerIdIsResolvedNormally() {
            Long signalId = 101L;

            SignalWaitEntity legacy = new SignalWaitEntity();
            legacy.setId(signalId);
            legacy.setRunId("run-legacy");
            legacy.setNodeId("core:wait_legacy");
            // No dagTriggerId, epoch=0 default → guard returns false (conservative)
            legacy.setSignalType(SignalType.WAIT_TIMER);
            legacy.setStatus(SignalWaitStatus.PENDING);

            when(signalWaitRepository.findById(signalId)).thenReturn(Optional.of(legacy));
            when(signalWaitRepository.claimSignal(eq(signalId), any(Instant.class), anyString())).thenReturn(1);
            when(signalWaitRepository.resolveClaimedSignal(eq(signalId), anyString(), any(Instant.class), anyString())).thenReturn(1);
            when(signalWaitRepository.save(any(SignalWaitEntity.class))).thenAnswer(inv -> inv.getArgument(0));
            // Legacy signal has no dagTriggerId, so the new countActive...DagAndEpoch query
            // is never called (the resolveSignal branch checks dagTriggerId != null first).

            boolean result = service.resolveSignal(signalId, SignalResolution.COMPLETED, Map.of(), "poller");

            assertTrue(result);
            verify(signalWaitRepository, never()).cancelById(anyLong(), any());
            // Snapshot lookup should not even be triggered when dagTriggerId is null
            verify(stateSnapshotService, never()).getSnapshot(anyString());
        }
    }

    @Nested
    @DisplayName("cancelByRun()")
    class CancelTests {

        @Test
        @DisplayName("Should cancel all signals and in-memory timers for a run")
        void shouldCancelByRun() {
            when(signalWaitRepository.findActiveSignalIdsByRunId("run-1"))
                .thenReturn(List.of(1L, 2L, 3L));
            when(signalWaitRepository.cancelByRunId(eq("run-1"), any(Instant.class)))
                .thenReturn(3);

            int cancelled = service.cancelByRun("run-1");

            assertEquals(3, cancelled);
            verify(signalWaitRepository).cancelByRunId("run-1", FIXED_NOW);
            verify(timerScheduler).cancelAll(List.of(1L, 2L, 3L));
        }

        @Test
        @DisplayName("Should cancel signals and in-memory timers by DAG and epoch")
        void shouldCancelByDagAndEpoch() {
            when(signalWaitRepository.findActiveSignalIdsByDagAndEpoch(
                "run-1", "trigger:webhook", 5))
                .thenReturn(List.of(10L, 11L));
            when(signalWaitRepository.cancelByDagAndEpoch(
                eq("run-1"), eq("trigger:webhook"), eq(5), any(Instant.class)))
                .thenReturn(2);

            int cancelled = service.cancelByDagAndEpoch("run-1", "trigger:webhook", 5);

            assertEquals(2, cancelled);
            verify(timerScheduler).cancelAll(List.of(10L, 11L));
        }
    }

    @Nested
    @DisplayName("cancelForNodes() - surgical per-node cancel (step rerun)")
    class CancelForNodesTests {

        private SignalWaitEntity activeSignal(long id, String nodeId, SignalType type, int epoch) {
            SignalWaitEntity s = mock(SignalWaitEntity.class);
            lenient().when(s.getId()).thenReturn(id);
            lenient().when(s.getNodeId()).thenReturn(nodeId);
            lenient().when(s.getSignalType()).thenReturn(type);
            lenient().when(s.getEpoch()).thenReturn(epoch);
            return s;
        }

        @Test
        @DisplayName("cancels only signals whose node is in the reset set, leaves siblings untouched")
        void cancelsOnlyMatchingNodes() {
            // step_c is downstream of the rerun target; sibling_branch is a parallel
            // branch NOT being reset - its approval must survive.
            SignalWaitEntity downstream = activeSignal(10L, "mcp:step_c", SignalType.USER_APPROVAL, 1);
            SignalWaitEntity sibling = activeSignal(11L, "mcp:sibling_branch", SignalType.USER_APPROVAL, 1);
            when(signalWaitRepository.findActiveByRunId("run-1"))
                .thenReturn(List.of(downstream, sibling));
            when(signalWaitRepository.cancelById(eq(10L), any(Instant.class))).thenReturn(1);

            int cancelled = service.cancelForNodes("run-1",
                java.util.Set.of("mcp:step_a", "mcp:step_b", "mcp:step_c"), 1);

            assertEquals(1, cancelled);
            verify(signalWaitRepository).cancelById(10L, FIXED_NOW);
            verify(signalWaitRepository, never()).cancelById(eq(11L), any());
            verify(timerScheduler).cancelAll(List.of(10L));
        }

        @Test
        @DisplayName("regression: a reset node's signal in an OLDER active epoch survives the rerun cancel (multi-epoch SBS)")
        void epochFilterPreservesOlderEpochSignals() {
            // The rerun resets only the current epoch (2); the same node's approval from
            // still-active epoch 1 must keep working - cancelling it would strand epoch 1's
            // wait (its awaitingSignalNodeIds entry survives the reset untouched).
            SignalWaitEntity currentEpochSignal = activeSignal(40L, "mcp:step_b", SignalType.USER_APPROVAL, 2);
            SignalWaitEntity olderEpochSignal = activeSignal(41L, "mcp:step_b", SignalType.USER_APPROVAL, 1);
            when(signalWaitRepository.findActiveByRunId("run-1"))
                .thenReturn(List.of(currentEpochSignal, olderEpochSignal));
            when(signalWaitRepository.cancelById(eq(40L), any(Instant.class))).thenReturn(1);

            int cancelled = service.cancelForNodes("run-1", java.util.Set.of("mcp:step_b"), 2);

            assertEquals(1, cancelled);
            verify(signalWaitRepository).cancelById(40L, FIXED_NOW);
            verify(signalWaitRepository, never()).cancelById(eq(41L), any());
        }

        @Test
        @DisplayName("negative epoch cancels matching signals across all epochs")
        void negativeEpochCancelsAllEpochs() {
            SignalWaitEntity e1 = activeSignal(50L, "mcp:step_b", SignalType.WAIT_TIMER, 1);
            SignalWaitEntity e2 = activeSignal(51L, "mcp:step_b", SignalType.WAIT_TIMER, 2);
            when(signalWaitRepository.findActiveByRunId("run-1")).thenReturn(List.of(e1, e2));
            when(signalWaitRepository.cancelById(anyLong(), any(Instant.class))).thenReturn(1);

            int cancelled = service.cancelForNodes("run-1", java.util.Set.of("mcp:step_b"), -1);

            assertEquals(2, cancelled);
            verify(signalWaitRepository).cancelById(50L, FIXED_NOW);
            verify(signalWaitRepository).cancelById(51L, FIXED_NOW);
        }

        @Test
        @DisplayName("publishes SignalsCancelledEvent only for USER_APPROVAL signals (bell cleanup)")
        void publishesEventForUserApprovals() {
            SignalWaitEntity approval = activeSignal(20L, "mcp:step_b", SignalType.USER_APPROVAL, 0);
            SignalWaitEntity timer = activeSignal(21L, "mcp:step_c", SignalType.WAIT_TIMER, 0);
            when(signalWaitRepository.findActiveByRunId("run-1"))
                .thenReturn(List.of(approval, timer));
            when(signalWaitRepository.cancelById(anyLong(), any(Instant.class))).thenReturn(1);

            int cancelled = service.cancelForNodes("run-1",
                java.util.Set.of("mcp:step_b", "mcp:step_c"), 0);

            assertEquals(2, cancelled);
            ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher).publishEvent(eventCaptor.capture());
            var event = (com.apimarketplace.orchestrator.services.events.SignalsCancelledEvent) eventCaptor.getValue();
            assertEquals(List.of(20L), event.userApprovalSignalIds(),
                "only the USER_APPROVAL signal id belongs in the bell-cleanup event");
        }

        @Test
        @DisplayName("no-op for empty node set and for runs without active signals")
        void noopOnEmptyInputs() {
            assertEquals(0, service.cancelForNodes("run-1", java.util.Set.of(), 0));
            assertEquals(0, service.cancelForNodes("run-1", null, 0));
            verify(signalWaitRepository, never()).findActiveByRunId(any());

            when(signalWaitRepository.findActiveByRunId("run-2")).thenReturn(List.of());
            assertEquals(0, service.cancelForNodes("run-2", java.util.Set.of("mcp:step_a"), 0));
            verify(signalWaitRepository, never()).cancelById(anyLong(), any());
            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("already-resolved race (cancelById returns 0) is not counted and not timer-cancelled")
        void resolvedRaceNotCounted() {
            SignalWaitEntity racing = activeSignal(30L, "mcp:step_b", SignalType.USER_APPROVAL, 0);
            when(signalWaitRepository.findActiveByRunId("run-1")).thenReturn(List.of(racing));
            // Signal resolved between the read and the cancel - UPDATE matches 0 rows.
            when(signalWaitRepository.cancelById(eq(30L), any(Instant.class))).thenReturn(0);

            int cancelled = service.cancelForNodes("run-1", java.util.Set.of("mcp:step_b"), 0);

            assertEquals(0, cancelled);
            verify(timerScheduler).cancelAll(List.of());
            verify(eventPublisher, never()).publishEvent(any());
        }
    }

    @Nested
    @DisplayName("rescheduleSignal()")
    class RescheduleTests {

        @Test
        @DisplayName("Should update DB + reschedule in-memory timer")
        void shouldRescheduleSuccessfully() {
            Long signalId = 20L;
            Instant newExpiry = FIXED_NOW.plusSeconds(300);

            when(signalWaitRepository.updateExpiresAt(signalId, newExpiry)).thenReturn(1);

            boolean result = service.rescheduleSignal(signalId, newExpiry);

            assertTrue(result);
            verify(signalWaitRepository).updateExpiresAt(signalId, newExpiry);
            verify(timerScheduler).schedule(signalId, newExpiry);
        }

        @Test
        @DisplayName("Should return false when signal is no longer PENDING")
        void shouldReturnFalseWhenNotPending() {
            Long signalId = 21L;
            Instant newExpiry = FIXED_NOW.plusSeconds(600);

            when(signalWaitRepository.updateExpiresAt(signalId, newExpiry)).thenReturn(0);

            boolean result = service.rescheduleSignal(signalId, newExpiry);

            assertFalse(result);
            verify(timerScheduler, never()).schedule(anyLong(), any());
            verify(timerScheduler, never()).cancel(anyLong());
        }

        @Test
        @DisplayName("Should cancel timer when rescheduled with null expiresAt")
        void shouldCancelTimerWhenNullExpiry() {
            Long signalId = 22L;

            when(signalWaitRepository.updateExpiresAt(signalId, null)).thenReturn(1);

            boolean result = service.rescheduleSignal(signalId, null);

            assertTrue(result);
            verify(timerScheduler).cancel(signalId);
            verify(timerScheduler, never()).schedule(anyLong(), any());
        }
    }

    @Nested
    @DisplayName("hasActiveSignals()")
    class QueryTests {

        @Test
        @DisplayName("Should check active signals for run")
        void shouldCheckActiveSignals() {
            when(signalWaitRepository.hasActiveSignals("run-1")).thenReturn(true);

            assertTrue(service.hasActiveSignals("run-1"));
        }

        @Test
        @DisplayName("Should check active signals for specific DAG")
        void shouldCheckActiveSignalsForDag() {
            when(signalWaitRepository.hasActiveSignalsForDag("run-1", "trigger:webhook"))
                .thenReturn(true);

            assertTrue(service.hasActiveSignalsForDag("run-1", "trigger:webhook"));
        }

        @Test
        @DisplayName("Should fall back to run-wide check when dagTriggerId is null")
        void shouldFallBackWhenDagTriggerIdNull() {
            when(signalWaitRepository.hasActiveSignals("run-1")).thenReturn(false);

            assertFalse(service.hasActiveSignalsForDag("run-1", null));
            verify(signalWaitRepository).hasActiveSignals("run-1");
            verify(signalWaitRepository, never()).hasActiveSignalsForDag(anyString(), anyString());
        }

        @Test
        @DisplayName("Should return active signals list")
        void shouldReturnActiveSignals() {
            SignalWaitEntity e1 = new SignalWaitEntity();
            e1.setId(1L);
            e1.setNodeId("core:wait_1");
            SignalWaitEntity e2 = new SignalWaitEntity();
            e2.setId(2L);
            e2.setNodeId("core:approval_1");

            when(signalWaitRepository.findActiveByRunId("run-1")).thenReturn(List.of(e1, e2));

            List<SignalWaitEntity> active = service.getActiveSignals("run-1");
            assertEquals(2, active.size());
        }

        @Test
        @DisplayName("isSplitContextNode should return true when multiple signals exist for a node in the same epoch")
        void shouldDetectSplitContext() {
            when(signalWaitRepository.countAllByRunIdAndNodeIdAndEpoch("run-1", "core:user_approval", 0))
                .thenReturn(5L);

            assertTrue(service.isSplitContextNode("run-1", "core:user_approval", 0));
        }

        @Test
        @DisplayName("isSplitContextNode should return false when only one signal exists (non-split)")
        void shouldReturnFalseForNonSplitContext() {
            when(signalWaitRepository.countAllByRunIdAndNodeIdAndEpoch("run-1", "core:user_approval", 0))
                .thenReturn(1L);

            assertFalse(service.isSplitContextNode("run-1", "core:user_approval", 0));
        }

        @Test
        @DisplayName("isSplitContextNode should return false when no signals exist")
        void shouldReturnFalseWhenNoSignals() {
            when(signalWaitRepository.countAllByRunIdAndNodeIdAndEpoch("run-1", "core:wait", 0))
                .thenReturn(0L);

            assertFalse(service.isSplitContextNode("run-1", "core:wait", 0));
        }

        @Test
        @DisplayName("isSplitContextNode should be epoch-scoped (different epochs don't count)")
        void shouldBeEpochScoped() {
            // Epoch 0 has 1 signal, epoch 1 has 1 signal - not split context in either epoch
            when(signalWaitRepository.countAllByRunIdAndNodeIdAndEpoch("run-1", "core:user_approval", 0))
                .thenReturn(1L);
            when(signalWaitRepository.countAllByRunIdAndNodeIdAndEpoch("run-1", "core:user_approval", 1))
                .thenReturn(1L);

            assertFalse(service.isSplitContextNode("run-1", "core:user_approval", 0));
            assertFalse(service.isSplitContextNode("run-1", "core:user_approval", 1));
        }
    }

    @Nested
    @DisplayName("pollExpiredTimers (DB safety net for WAIT_TIMER)")
    class PollExpiredTimersTests {

        @Test
        @DisplayName("Queries findExpiredTimers with current instant and batch size 50 (regression: 2026-05-05 RedisTimer silent fail)")
        void shouldQueryExpiredTimersOnTick() {
            // Plan v3 #9 - batch bumped from 50 to 500. The V181 covering index
            // (status, signal_type, expires_at, id) keeps the scan index-only;
            // SKIP LOCKED prevents dup-claim across replicas, so the larger
            // limit raises throughput without serialization risk.
            when(signalWaitRepository.findExpiredTimers(eq(FIXED_NOW), eq(500)))
                .thenReturn(List.of());

            service.pollExpiredTimers();

            verify(signalWaitRepository).findExpiredTimers(FIXED_NOW, 500);
        }

        @Test
        @DisplayName("No-op when signal maintenance pollers are disabled by profile")
        void shouldNoOpWhenSignalMaintenancePollersDisabled() {
            ReflectionTestUtils.setField(service, "signalMaintenancePollersEnabled", false);

            service.pollExpiredTimers();
            service.recoverStaleClaims();

            verifyNoInteractions(signalWaitRepository);
        }

        @Test
        @DisplayName("No-op when no expired timers found")
        void shouldNoOpWhenEmpty() {
            when(signalWaitRepository.findExpiredTimers(any(), anyInt()))
                .thenReturn(List.of());

            assertDoesNotThrow(() -> service.pollExpiredTimers());

            verify(signalWaitRepository, never()).claimSignal(anyLong(), any(), anyString());
        }

        @Test
        @DisplayName("Swallows exceptions to keep the @Scheduled task alive across ticks")
        void shouldSwallowExceptions() {
            when(signalWaitRepository.findExpiredTimers(any(), anyInt()))
                .thenThrow(new RuntimeException("DB down"));

            assertDoesNotThrow(() -> service.pollExpiredTimers());
        }
    }

    @Nested
    @DisplayName("getPendingResumeNodeIds() - concurrent-sibling guard source (CE-SIGRACE-006/007)")
    class GetPendingResumeNodeIdsTests {

        @org.junit.jupiter.api.BeforeEach
        void wireRedis() {
            org.springframework.test.util.ReflectionTestUtils.setField(service, "redisTemplate", redisTemplate);
            when(redisTemplate.opsForSet()).thenReturn(setOperations);
        }

        @Test
        @DisplayName("maps pending sibling signal ids to their node ids, excluding the caller's own signal")
        void mapsPendingSiblingsToNodeIdsExcludingOwnSignal() {
            String key = RedisCacheKeys.signalResumePending("run-1", "trigger:start", 1);
            when(setOperations.members(key)).thenReturn(java.util.Set.of("6", "7"));

            SignalWaitEntity sibling = new SignalWaitEntity();
            sibling.setId(6L);
            sibling.setNodeId("core:gate_b");
            when(signalWaitRepository.findAllById(List.of(6L))).thenReturn(List.of(sibling));

            java.util.Set<String> nodeIds = service.getPendingResumeNodeIds("run-1", "trigger:start", 1, 7L);

            assertEquals(java.util.Set.of("core:gate_b"), nodeIds);
            // Reading active markers must refresh the protection window so a long-running
            // first resume cannot outlive the TTL and re-open the premature-reset race.
            verify(redisTemplate).expire(eq(key), eq(java.time.Duration.ofMinutes(2)));
        }

        @Test
        @DisplayName("returns empty when only the caller's own signal is pending")
        void returnsEmptyWhenOnlyOwnSignalPending() {
            String key = RedisCacheKeys.signalResumePending("run-1", "trigger:start", 1);
            when(setOperations.members(key)).thenReturn(java.util.Set.of("7"));

            java.util.Set<String> nodeIds = service.getPendingResumeNodeIds("run-1", "trigger:start", 1, 7L);

            assertTrue(nodeIds.isEmpty());
            verify(signalWaitRepository, never()).findAllById(any());
        }

        @Test
        @DisplayName("fails open (empty set) when Redis errors so the resume guard never blocks execution")
        void failsOpenOnRedisError() {
            when(setOperations.members(anyString())).thenThrow(new RuntimeException("redis down"));

            java.util.Set<String> nodeIds = service.getPendingResumeNodeIds("run-1", "trigger:start", 1, 7L);

            assertTrue(nodeIds.isEmpty());
        }
    }

}
