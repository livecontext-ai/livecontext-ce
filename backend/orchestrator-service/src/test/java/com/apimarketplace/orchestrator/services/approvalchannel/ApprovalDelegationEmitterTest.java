package com.apimarketplace.orchestrator.services.approvalchannel;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.ApprovalChannelDeliveryEntity;
import com.apimarketplace.orchestrator.domain.execution.ApprovalChannelDeliveryEntity.DeliveryStatus;
import com.apimarketplace.orchestrator.domain.execution.SignalResolution;
import com.apimarketplace.orchestrator.domain.execution.SignalType;
import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity;
import com.apimarketplace.orchestrator.execution.v2.services.SignalResolvedEvent;
import com.apimarketplace.orchestrator.repository.ApprovalChannelDeliveryRepository;
import com.apimarketplace.orchestrator.repository.SignalWaitRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.events.SignalsCancelledEvent;
import com.apimarketplace.orchestrator.services.events.WorkflowApprovalPendingEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ApprovalDelegationEmitter}: each test exercises one
 * routing branch or one failure-isolation guarantee of the AFTER_COMMIT
 * approval-to-channel pipeline. Every listener must be a strict no-op for
 * non-delegated approvals and must swallow (count, log) any channel failure.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ApprovalDelegationEmitter")
class ApprovalDelegationEmitterTest {

    private static final long SIGNAL_ID = 55L;
    private static final String RUN_ID = "run_pub_abc";
    private static final String TENANT_ID = "tenant-1";

    @Mock private SignalWaitRepository signalWaitRepository;
    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private WorkflowRepository workflowRepository;
    @Mock private ApprovalChannelDeliveryRepository deliveryRepository;
    @Mock private ApprovalChannelNotifierRegistry registry;
    @Mock private ApprovalChannelNotifier notifier;

    private MeterRegistry meterRegistry;
    private ApprovalDelegationEmitter emitter;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        emitter = new ApprovalDelegationEmitter(signalWaitRepository, workflowRunRepository,
                workflowRepository, deliveryRepository, registry, meterRegistry);
    }

    private WorkflowApprovalPendingEvent pendingEvent() {
        return new WorkflowApprovalPendingEvent(RUN_ID, SIGNAL_ID, 0, Instant.now(), null);
    }

    private SignalWaitEntity signalWithConfig(Map<String, Object> signalConfig) {
        SignalWaitEntity signal = new SignalWaitEntity();
        signal.setId(SIGNAL_ID);
        signal.setRunId(RUN_ID);
        signal.setNodeId("core:manager_approval");
        signal.setSignalType(SignalType.USER_APPROVAL);
        signal.setSignalConfig(signalConfig);
        return signal;
    }

    private Map<String, Object> delegatedConfig(String channel) {
        Map<String, Object> config = new HashMap<>();
        config.put("type", SignalType.USER_APPROVAL.name());
        config.put("delegation", Map.of(
                "channel", channel,
                "credentialId", 42,
                "chatId", "123456"));
        return config;
    }

    private WorkflowRunEntity runWithTenant(String tenantId) {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setTenantId(tenantId);
        run.setRunIdPublic(RUN_ID);
        return run;
    }

    private double errorCount(String type) {
        return meterRegistry.counter("approval.delegation.errors", "type", type).count();
    }

    @Nested
    @DisplayName("onApprovalPending")
    class OnApprovalPending {

        @Test
        @DisplayName("non-delegated approval signal: the registry is never queried (common in-app path stays untouched)")
        void nonDelegatedSignalNeverQueriesRegistry() {
            Map<String, Object> plainConfig = new HashMap<>();
            plainConfig.put("type", SignalType.USER_APPROVAL.name());
            when(signalWaitRepository.findById(SIGNAL_ID))
                    .thenReturn(Optional.of(signalWithConfig(plainConfig)));

            emitter.onApprovalPending(pendingEvent());

            verifyNoInteractions(registry);
            verifyNoInteractions(workflowRunRepository);
        }

        @Test
        @DisplayName("signal not found: no-op, no registry lookup")
        void missingSignalIsNoOp() {
            when(signalWaitRepository.findById(SIGNAL_ID)).thenReturn(Optional.empty());

            emitter.onApprovalPending(pendingEvent());

            verifyNoInteractions(registry);
        }

        @Test
        @DisplayName("unknown channel: counted as UnknownChannel, no notifier called, no run lookup")
        void unknownChannelCountedAndSkipped() {
            when(signalWaitRepository.findById(SIGNAL_ID))
                    .thenReturn(Optional.of(signalWithConfig(delegatedConfig("carrier-pigeon"))));
            when(registry.forChannel("carrier-pigeon")).thenReturn(Optional.empty());

            emitter.onApprovalPending(pendingEvent());

            assertThat(errorCount("UnknownChannel")).isEqualTo(1.0);
            verifyNoInteractions(workflowRunRepository);
        }

        @Test
        @DisplayName("happy path: notifyPending receives the parsed delegation config and the loaded run (workflow-less run -> null name)")
        void happyPathCallsNotifyPending() {
            SignalWaitEntity signal = signalWithConfig(delegatedConfig("telegram"));
            WorkflowRunEntity run = runWithTenant(TENANT_ID);
            when(signalWaitRepository.findById(SIGNAL_ID)).thenReturn(Optional.of(signal));
            when(registry.forChannel("telegram")).thenReturn(Optional.of(notifier));
            when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

            emitter.onApprovalPending(pendingEvent());

            ArgumentCaptor<ApprovalDelegationConfig> configCaptor =
                    ArgumentCaptor.forClass(ApprovalDelegationConfig.class);
            verify(notifier).notifyPending(eq(signal), configCaptor.capture(), eq(run), isNull());
            ApprovalDelegationConfig config = configCaptor.getValue();
            assertThat(config.channel()).isEqualTo("telegram");
            assertThat(config.credentialId()).isEqualTo(42L);
            assertThat(config.chatId()).isEqualTo("123456");
            assertThat(errorCount("UnknownChannel")).isZero();
        }

        // ── M2 regression: workflow-name resolution must NEVER navigate the detached
        // run's lazy workflow proxy (async thread, no session). Pre-fix, the notifier
        // read run.getWorkflow().getName() and a LazyInitializationException was
        // silently swallowed, killing the whole channel message. ──

        @Test
        @DisplayName("regression M2: lazy workflow proxy (getName throws, getId works) -> notifyPending still receives the repository-resolved name")
        void lazyWorkflowProxyStillResolvesNameViaRepository() {
            java.util.UUID workflowId = java.util.UUID.fromString("00000000-0000-0000-0000-0000000000aa");
            WorkflowEntity detachedProxy = mock(WorkflowEntity.class);
            when(detachedProxy.getId()).thenReturn(workflowId);
            // Detached-proxy behavior: id readable without initialization, name is NOT.
            // lenient: post-fix the emitter must never even TRY getName() on the proxy
            // (asserted below); pre-fix code called it and blew up with this exception.
            org.mockito.Mockito.lenient().when(detachedProxy.getName()).thenThrow(
                    new org.hibernate.LazyInitializationException("could not initialize proxy - no Session"));
            WorkflowRunEntity run = runWithTenant(TENANT_ID);
            run.setWorkflow(detachedProxy);
            WorkflowEntity fresh = new WorkflowEntity();
            fresh.setId(workflowId);
            fresh.setName("Refund Flow");
            when(workflowRepository.findById(workflowId)).thenReturn(Optional.of(fresh));

            when(signalWaitRepository.findById(SIGNAL_ID))
                    .thenReturn(Optional.of(signalWithConfig(delegatedConfig("telegram"))));
            when(registry.forChannel("telegram")).thenReturn(Optional.of(notifier));
            when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

            emitter.onApprovalPending(pendingEvent());

            verify(notifier).notifyPending(any(), any(), eq(run), eq("Refund Flow"));
            verify(detachedProxy, never()).getName();
            assertThat(errorCount("LazyInitializationException")).isZero();
        }

        @Test
        @DisplayName("regression M2: workflow lookup returns empty -> notifyPending receives a null name and nothing throws")
        void missingWorkflowRowYieldsNullName() {
            java.util.UUID workflowId = java.util.UUID.fromString("00000000-0000-0000-0000-0000000000bb");
            WorkflowEntity proxy = mock(WorkflowEntity.class);
            when(proxy.getId()).thenReturn(workflowId);
            WorkflowRunEntity run = runWithTenant(TENANT_ID);
            run.setWorkflow(proxy);
            when(workflowRepository.findById(workflowId)).thenReturn(Optional.empty());

            when(signalWaitRepository.findById(SIGNAL_ID))
                    .thenReturn(Optional.of(signalWithConfig(delegatedConfig("telegram"))));
            when(registry.forChannel("telegram")).thenReturn(Optional.of(notifier));
            when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

            assertThatCode(() -> emitter.onApprovalPending(pendingEvent()))
                    .doesNotThrowAnyException();

            verify(notifier).notifyPending(any(), any(), eq(run), isNull());
        }

        @Test
        @DisplayName("regression M2: workflow repository lookup throwing -> notifyPending receives a null name (message still sent)")
        void workflowLookupFailureYieldsNullName() {
            java.util.UUID workflowId = java.util.UUID.fromString("00000000-0000-0000-0000-0000000000cc");
            WorkflowEntity proxy = mock(WorkflowEntity.class);
            when(proxy.getId()).thenReturn(workflowId);
            WorkflowRunEntity run = runWithTenant(TENANT_ID);
            run.setWorkflow(proxy);
            when(workflowRepository.findById(workflowId))
                    .thenThrow(new RuntimeException("db down"));

            when(signalWaitRepository.findById(SIGNAL_ID))
                    .thenReturn(Optional.of(signalWithConfig(delegatedConfig("telegram"))));
            when(registry.forChannel("telegram")).thenReturn(Optional.of(notifier));
            when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

            assertThatCode(() -> emitter.onApprovalPending(pendingEvent()))
                    .doesNotThrowAnyException();

            verify(notifier).notifyPending(any(), any(), eq(run), isNull());
        }

        @Test
        @DisplayName("run not found: counted as RunNotFound, notifier never called")
        void runNotFoundCountedAndSkipped() {
            when(signalWaitRepository.findById(SIGNAL_ID))
                    .thenReturn(Optional.of(signalWithConfig(delegatedConfig("telegram"))));
            when(registry.forChannel("telegram")).thenReturn(Optional.of(notifier));
            when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.empty());

            emitter.onApprovalPending(pendingEvent());

            assertThat(errorCount("RunNotFound")).isEqualTo(1.0);
            verify(notifier, never()).notifyPending(any(), any(), any(), any());
        }

        @Test
        @DisplayName("run without tenant: counted as RunNotFound, notifier never called")
        void runWithoutTenantCountedAndSkipped() {
            when(signalWaitRepository.findById(SIGNAL_ID))
                    .thenReturn(Optional.of(signalWithConfig(delegatedConfig("telegram"))));
            when(registry.forChannel("telegram")).thenReturn(Optional.of(notifier));
            when(workflowRunRepository.findByRunIdPublic(RUN_ID))
                    .thenReturn(Optional.of(runWithTenant(null)));

            emitter.onApprovalPending(pendingEvent());

            assertThat(errorCount("RunNotFound")).isEqualTo(1.0);
            verify(notifier, never()).notifyPending(any(), any(), any(), any());
        }

        @Test
        @DisplayName("a throwing notifier is swallowed and counted, never propagated to the caller")
        void notifierExceptionSwallowedAndCounted() {
            SignalWaitEntity signal = signalWithConfig(delegatedConfig("telegram"));
            when(signalWaitRepository.findById(SIGNAL_ID)).thenReturn(Optional.of(signal));
            when(registry.forChannel("telegram")).thenReturn(Optional.of(notifier));
            when(workflowRunRepository.findByRunIdPublic(RUN_ID))
                    .thenReturn(Optional.of(runWithTenant(TENANT_ID)));
            doThrow(new IllegalStateException("telegram down"))
                    .when(notifier).notifyPending(any(), any(), any(), any());

            assertThatCode(() -> emitter.onApprovalPending(pendingEvent()))
                    .doesNotThrowAnyException();

            assertThat(errorCount("IllegalStateException")).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("onSignalResolved")
    class OnSignalResolved {

        private ApprovalChannelDeliveryEntity sentDelivery() {
            ApprovalChannelDeliveryEntity delivery = new ApprovalChannelDeliveryEntity();
            delivery.setSignalWaitId(SIGNAL_ID);
            delivery.setChannel("telegram");
            delivery.setStatus(DeliveryStatus.SENT);
            return delivery;
        }

        @Test
        @DisplayName("non-approval signal resolution is a no-op (no delivery lookup)")
        void nonApprovalSignalIsNoOp() {
            SignalWaitEntity signal = new SignalWaitEntity();
            signal.setId(SIGNAL_ID);
            signal.setSignalType(SignalType.WAIT_TIMER);

            emitter.onSignalResolved(new SignalResolvedEvent(this, signal));

            verifyNoInteractions(deliveryRepository);
            verifyNoInteractions(registry);
        }

        @Test
        @DisplayName("null resolved signal is a no-op")
        void nullSignalIsNoOp() {
            emitter.onSignalResolved(new SignalResolvedEvent(this, null));

            verifyNoInteractions(deliveryRepository);
        }

        @Test
        @DisplayName("approval resolution fans out onResolved to every SENT delivery of that signal")
        void approvalResolutionFansOutToSentDeliveries() {
            SignalWaitEntity signal = new SignalWaitEntity();
            signal.setId(SIGNAL_ID);
            signal.setSignalType(SignalType.USER_APPROVAL);
            signal.setResolution(SignalResolution.APPROVED);
            signal.setResolvedBy("telegram:777");
            ApprovalChannelDeliveryEntity delivery = sentDelivery();
            when(deliveryRepository.findBySignalWaitIdInAndStatus(
                    List.of(SIGNAL_ID), DeliveryStatus.SENT)).thenReturn(List.of(delivery));
            when(registry.forChannel("telegram")).thenReturn(Optional.of(notifier));

            emitter.onSignalResolved(new SignalResolvedEvent(this, signal));

            verify(notifier).onResolved(delivery, SignalResolution.APPROVED, "telegram:777");
        }

        @Test
        @DisplayName("approval resolution with no SENT delivery calls no notifier (in-app-only approval)")
        void noSentDeliveryCallsNoNotifier() {
            SignalWaitEntity signal = new SignalWaitEntity();
            signal.setId(SIGNAL_ID);
            signal.setSignalType(SignalType.USER_APPROVAL);
            when(deliveryRepository.findBySignalWaitIdInAndStatus(any(), eq(DeliveryStatus.SENT)))
                    .thenReturn(List.of());

            emitter.onSignalResolved(new SignalResolvedEvent(this, signal));

            verifyNoInteractions(registry);
        }

        @Test
        @DisplayName("a repository failure is swallowed and counted")
        void repositoryFailureSwallowed() {
            SignalWaitEntity signal = new SignalWaitEntity();
            signal.setId(SIGNAL_ID);
            signal.setSignalType(SignalType.USER_APPROVAL);
            when(deliveryRepository.findBySignalWaitIdInAndStatus(any(), any()))
                    .thenThrow(new RuntimeException("db down"));

            assertThatCode(() -> emitter.onSignalResolved(new SignalResolvedEvent(this, signal)))
                    .doesNotThrowAnyException();

            assertThat(errorCount("RuntimeException")).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("onSignalsCancelled")
    class OnSignalsCancelled {

        @Test
        @DisplayName("empty cancelled-approval list is a no-op (no delivery lookup)")
        void emptyIdListIsNoOp() {
            emitter.onSignalsCancelled(new SignalsCancelledEvent(RUN_ID, List.of()));

            verifyNoInteractions(deliveryRepository);
        }

        @Test
        @DisplayName("cancellation fans out onCancelled to the SENT deliveries of the cancelled signals")
        void cancellationFansOutToSentDeliveries() {
            ApprovalChannelDeliveryEntity delivery = new ApprovalChannelDeliveryEntity();
            delivery.setSignalWaitId(SIGNAL_ID);
            delivery.setChannel("telegram");
            delivery.setStatus(DeliveryStatus.SENT);
            when(deliveryRepository.findBySignalWaitIdInAndStatus(
                    List.of(SIGNAL_ID), DeliveryStatus.SENT)).thenReturn(List.of(delivery));
            when(registry.forChannel("telegram")).thenReturn(Optional.of(notifier));

            emitter.onSignalsCancelled(new SignalsCancelledEvent(RUN_ID, List.of(SIGNAL_ID)));

            verify(notifier).onCancelled(delivery);
        }

        @Test
        @DisplayName("a throwing notifier during cancellation is swallowed and counted")
        void cancellationNotifierExceptionSwallowed() {
            ApprovalChannelDeliveryEntity delivery = new ApprovalChannelDeliveryEntity();
            delivery.setSignalWaitId(SIGNAL_ID);
            delivery.setChannel("telegram");
            delivery.setStatus(DeliveryStatus.SENT);
            when(deliveryRepository.findBySignalWaitIdInAndStatus(any(), any()))
                    .thenReturn(List.of(delivery));
            when(registry.forChannel("telegram")).thenReturn(Optional.of(notifier));
            doThrow(new IllegalStateException("edit failed")).when(notifier).onCancelled(any());

            assertThatCode(() -> emitter.onSignalsCancelled(
                    new SignalsCancelledEvent(RUN_ID, List.of(SIGNAL_ID))))
                    .doesNotThrowAnyException();

            assertThat(errorCount("IllegalStateException")).isEqualTo(1.0);
        }
    }
}
