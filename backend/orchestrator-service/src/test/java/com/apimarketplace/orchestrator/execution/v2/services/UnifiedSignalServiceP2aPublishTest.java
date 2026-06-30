package com.apimarketplace.orchestrator.execution.v2.services;

import com.apimarketplace.orchestrator.domain.execution.SignalConfig;
import com.apimarketplace.orchestrator.domain.execution.SignalType;
import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity;
import com.apimarketplace.orchestrator.repository.SignalWaitRepository;
import com.apimarketplace.orchestrator.services.events.SignalsCancelledEvent;
import com.apimarketplace.orchestrator.services.events.WorkflowApprovalPendingEvent;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2a notification-system: verifies {@link UnifiedSignalService} publishes
 * {@link WorkflowApprovalPendingEvent} on USER_APPROVAL registration and
 * {@link SignalsCancelledEvent} on cancel paths that bypass
 * {@code SignalResolvedEvent} (cancelByRun / cancelByDagAndEpoch /
 * cancelBlockingByDagAndEpoch / zombie guard).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UnifiedSignalService - P2a notification publishers")
class UnifiedSignalServiceP2aPublishTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-05-08T10:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneId.of("UTC"));

    @Mock private SignalWaitRepository signalWaitRepository;
    @Mock private StateSnapshotService stateSnapshotService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private SignalTimerScheduler timerScheduler;
    @Mock private SignalResumeRedisPublisher redisPublisher;

    private UnifiedSignalService service;

    @BeforeEach
    void setUp() {
        service = new UnifiedSignalService(
                signalWaitRepository, stateSnapshotService, eventPublisher,
                timerScheduler, redisPublisher, FIXED_CLOCK, null);
    }

    private SignalWaitEntity persistedSignal(long id, String runId) {
        SignalWaitEntity e = new SignalWaitEntity();
        e.setId(id);
        e.setRunId(runId);
        e.setCreatedAt(FIXED_NOW);
        e.setExpiresAt(FIXED_NOW.plusSeconds(3600));
        e.setBlocking(true);
        return e;
    }

    // ========================================================================
    // registerSignal - APPROVAL_PENDING publish
    // ========================================================================

    @Test
    @DisplayName("USER_APPROVAL registration publishes WorkflowApprovalPendingEvent")
    void publishesEventForUserApproval() {
        Map<String, Object> config = SignalConfig.userApproval(
                List.of("manager"), 1, Duration.ofHours(24));
        when(signalWaitRepository.saveAndFlush(any(SignalWaitEntity.class)))
                .thenAnswer(inv -> {
                    SignalWaitEntity e = inv.getArgument(0);
                    e.setId(99L);
                    return e;
                });

        service.registerSignal(
                "run_pub_xyz", "0", "core:approval_1", "trigger:manual", 0,
                SignalType.USER_APPROVAL, config, null);

        ArgumentCaptor<WorkflowApprovalPendingEvent> captor =
                ArgumentCaptor.forClass(WorkflowApprovalPendingEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        WorkflowApprovalPendingEvent ev = captor.getValue();
        assertThat(ev.runIdPublic()).isEqualTo("run_pub_xyz");
        assertThat(ev.signalWaitId()).isEqualTo(99L);
        assertThat(ev.epoch()).isEqualTo(0);
        assertThat(ev.createdAt()).isEqualTo(FIXED_NOW);
        assertThat(ev.expiresAt()).isEqualTo(FIXED_NOW.plus(Duration.ofHours(24)));
    }

    @ParameterizedTest
    @EnumSource(value = SignalType.class, names = {
            "WAIT_TIMER", "WEBHOOK_WAIT", "INTERFACE_SIGNAL", "AGENT_EXECUTION", "BROWSER_USER_TAKEOVER"
    })
    @DisplayName("Non-USER_APPROVAL signal types do NOT publish WorkflowApprovalPendingEvent")
    void doesNotPublishForNonUserApprovalSignal(SignalType type) {
        Map<String, Object> config = switch (type) {
            case WAIT_TIMER -> SignalConfig.timer(60_000);
            case WEBHOOK_WAIT -> SignalConfig.webhookWait("token-x", Duration.ofMinutes(10));
            case INTERFACE_SIGNAL -> SignalConfig.interfaceSignal("iface-123", Map.of());
            case AGENT_EXECUTION -> SignalConfig.agentExecution("corr-1", "classify",
                    "anthropic", "claude-sonnet-4", Duration.ofMinutes(5));
            case BROWSER_USER_TAKEOVER -> SignalConfig.browserTakeover(
                    "session-1", "run_pub_xyz", "core:browser_1", "cdp-token", Duration.ofMinutes(30));
            default -> Map.of();
        };
        when(signalWaitRepository.saveAndFlush(any(SignalWaitEntity.class)))
                .thenAnswer(inv -> {
                    SignalWaitEntity e = inv.getArgument(0);
                    e.setId(1L);
                    return e;
                });

        service.registerSignal(
                "run_pub_xyz", "0", "core:n1", "trigger:webhook", 0,
                type, config, null);

        verify(eventPublisher, never()).publishEvent(any(WorkflowApprovalPendingEvent.class));
    }

    // ========================================================================
    // cancelByRun - SignalsCancelledEvent publish
    // ========================================================================

    @Test
    @DisplayName("cancelByRun publishes SignalsCancelledEvent with USER_APPROVAL ids only")
    void cancelByRunPublishesUserApprovalIds() {
        when(signalWaitRepository.findActiveSignalIdsByRunId("run_x")).thenReturn(List.of(1L, 2L, 3L));
        when(signalWaitRepository.findActiveUserApprovalIdsByRunId("run_x")).thenReturn(List.of(2L));
        when(signalWaitRepository.cancelByRunId(eq("run_x"), any())).thenReturn(3);

        service.cancelByRun("run_x");

        ArgumentCaptor<SignalsCancelledEvent> captor =
                ArgumentCaptor.forClass(SignalsCancelledEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        SignalsCancelledEvent ev = captor.getValue();
        assertThat(ev.runIdPublic()).isEqualTo("run_x");
        assertThat(ev.userApprovalSignalIds()).containsExactly(2L);
    }

    @Test
    @DisplayName("cancelByRun does NOT publish when no USER_APPROVAL active")
    void cancelByRunNoOpWhenNoUserApprovals() {
        when(signalWaitRepository.findActiveSignalIdsByRunId("run_x")).thenReturn(List.of(1L, 2L));
        when(signalWaitRepository.findActiveUserApprovalIdsByRunId("run_x")).thenReturn(List.of());
        when(signalWaitRepository.cancelByRunId(eq("run_x"), any())).thenReturn(2);

        service.cancelByRun("run_x");

        verify(eventPublisher, never()).publishEvent(any(SignalsCancelledEvent.class));
    }

    @Test
    @DisplayName("cancelByRun does NOT publish when zero rows actually cancelled")
    void cancelByRunNoOpWhenZeroCancelled() {
        when(signalWaitRepository.findActiveSignalIdsByRunId("run_x")).thenReturn(List.of());
        when(signalWaitRepository.findActiveUserApprovalIdsByRunId("run_x")).thenReturn(List.of());
        when(signalWaitRepository.cancelByRunId(eq("run_x"), any())).thenReturn(0);

        service.cancelByRun("run_x");

        verify(eventPublisher, never()).publishEvent(any(SignalsCancelledEvent.class));
    }

    // ========================================================================
    // cancelByDagAndEpoch
    // ========================================================================

    @Test
    @DisplayName("cancelByDagAndEpoch publishes SignalsCancelledEvent with USER_APPROVAL ids")
    void cancelByDagAndEpochPublishesUserApprovalIds() {
        when(signalWaitRepository.findActiveSignalIdsByDagAndEpoch("run_x", "trigger:tg", 5))
                .thenReturn(List.of(1L, 2L));
        when(signalWaitRepository.findActiveUserApprovalIdsByDagAndEpoch("run_x", "trigger:tg", 5))
                .thenReturn(List.of(1L, 2L));
        when(signalWaitRepository.cancelByDagAndEpoch(eq("run_x"), eq("trigger:tg"), eq(5), any()))
                .thenReturn(2);

        service.cancelByDagAndEpoch("run_x", "trigger:tg", 5);

        ArgumentCaptor<SignalsCancelledEvent> captor =
                ArgumentCaptor.forClass(SignalsCancelledEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().userApprovalSignalIds()).containsExactly(1L, 2L);
    }

    // ========================================================================
    // cancelBlockingByDagAndEpoch
    // ========================================================================

    @Test
    @DisplayName("cancelBlockingByDagAndEpoch publishes SignalsCancelledEvent with USER_APPROVAL+blocking ids")
    void cancelBlockingByDagAndEpochPublishesUserApprovalIds() {
        when(signalWaitRepository.findActiveBlockingUserApprovalIdsByDagAndEpoch("run_x", "trigger:tg", 5))
                .thenReturn(List.of(7L));
        when(signalWaitRepository.cancelBlockingByDagAndEpoch(eq("run_x"), eq("trigger:tg"), eq(5), any()))
                .thenReturn(1);

        service.cancelBlockingByDagAndEpoch("run_x", "trigger:tg", 5);

        ArgumentCaptor<SignalsCancelledEvent> captor =
                ArgumentCaptor.forClass(SignalsCancelledEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        SignalsCancelledEvent ev = captor.getValue();
        assertThat(ev.runIdPublic()).isEqualTo("run_x");
        assertThat(ev.userApprovalSignalIds()).containsExactly(7L);
    }

    @Test
    @DisplayName("cancelBlockingByDagAndEpoch does NOT publish when no blocking USER_APPROVAL active")
    void cancelBlockingNoOpWhenNoBlockingUserApprovals() {
        when(signalWaitRepository.findActiveBlockingUserApprovalIdsByDagAndEpoch("run_x", "trigger:tg", 5))
                .thenReturn(List.of());
        when(signalWaitRepository.cancelBlockingByDagAndEpoch(eq("run_x"), eq("trigger:tg"), eq(5), any()))
                .thenReturn(0);

        service.cancelBlockingByDagAndEpoch("run_x", "trigger:tg", 5);

        verify(eventPublisher, never()).publishEvent(any(SignalsCancelledEvent.class));
    }

    @Test
    @DisplayName("cancelByDagAndEpoch does NOT publish when no USER_APPROVAL active")
    void cancelByDagAndEpochNoOpWhenNoUserApprovals() {
        when(signalWaitRepository.findActiveSignalIdsByDagAndEpoch("run_x", "trigger:tg", 5))
                .thenReturn(List.of(11L, 22L));
        when(signalWaitRepository.findActiveUserApprovalIdsByDagAndEpoch("run_x", "trigger:tg", 5))
                .thenReturn(List.of());
        when(signalWaitRepository.cancelByDagAndEpoch(eq("run_x"), eq("trigger:tg"), eq(5), any()))
                .thenReturn(2);

        service.cancelByDagAndEpoch("run_x", "trigger:tg", 5);

        verify(eventPublisher, never()).publishEvent(any(SignalsCancelledEvent.class));
    }
}
// NOTE: zombie-guard publish path (resolveSignal:284-298) is verified only via
// code review - wiring an isolated unit test would require deep mocks of
// StateSnapshot + DagState + the ZOMBIE_GUARD_MIN_AGE_MS clock advance. The
// path is reached on a rare race (see closeEpochIfCompleteForSbs / resetRunOnFailure
// epoch pruning) and is exercised end-to-end by the existing zombie regression
// in ReusableTriggerServiceSignalBlockingTest.

