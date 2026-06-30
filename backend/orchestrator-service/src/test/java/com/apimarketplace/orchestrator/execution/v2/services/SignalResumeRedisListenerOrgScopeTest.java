package com.apimarketplace.orchestrator.execution.v2.services;

import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity;
import com.apimarketplace.orchestrator.repository.SignalWaitRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.streaming.context.RunContextRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression test for the 2026-05-20 prod-fire follow-up: cross-instance
 * Redis-delivered signal resolutions on a {@code redisMessageListenerContainer-N}
 * thread had no org context, so {@code SignalResumeService.resumeAfterSignal}
 * persisted {@code workflow_step_data} + storage rows that tripped the
 * @PrePersist fail-loud listener post-V263.
 *
 * <p>This test pins the contract: when {@code onMessage} runs on a thread with
 * NO prior org binding, {@code signalResumeService.resumeAfterSignal} is invoked
 * inside a {@code TenantResolver.runWithOrgScope} for the run's organizationId
 * (resolved via {@code WorkflowRunRepository.findByRunIdPublic}).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SignalResumeRedisListener - onMessage binds run.organizationId on the listener thread")
class SignalResumeRedisListenerOrgScopeTest {

    @Mock private RedisMessageListenerContainer listenerContainer;
    @Mock private RunContextRegistry runContextRegistry;
    @Mock private SignalWaitRepository signalWaitRepository;
    @Mock private SignalResumeService signalResumeService;
    @Mock private WorkflowRunRepository workflowRunRepository;

    private SignalResumeRedisListener listener;

    @BeforeEach
    void setUp() {
        listener = new SignalResumeRedisListener(
            listenerContainer, runContextRegistry, signalWaitRepository,
            signalResumeService, workflowRunRepository);
    }

    @Test
    @DisplayName("binds run.organizationId during resumeAfterSignal call (cross-instance signal resolve)")
    void bindsRunOrganizationIdDuringResume() {
        String runId = "run_test_signal_redis";
        String orgId = "00000000-0000-0000-0000-000000000000";
        long signalId = 42L;

        // RunContextRegistry says this instance owns the run.
        when(runContextRegistry.exists(runId)).thenReturn(true);

        SignalWaitEntity signal = new SignalWaitEntity();
        signal.setId(signalId);
        signal.setRunId(runId);
        signal.setNodeId("core:gate");
        when(signalWaitRepository.findById(signalId)).thenReturn(Optional.of(signal));

        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setRunIdPublic(runId);
        run.setOrganizationId(orgId);
        when(workflowRunRepository.findByRunIdPublic(runId)).thenReturn(Optional.of(run));

        AtomicReference<String> orgInsideResume = new AtomicReference<>();
        doAnswer(inv -> {
            orgInsideResume.set(TenantResolver.currentRequestOrganizationId());
            return null;
        }).when(signalResumeService).resumeAfterSignal(signal);

        Message message = mock(Message.class);
        when(message.getBody()).thenReturn(("42|core:gate|approved|" + runId + "|peer-instance").getBytes());

        // Pre-condition: listener thread unbound (mirrors redisMessageListenerContainer-N).
        assertThat(TenantResolver.currentRequestOrganizationId()).isNull();

        listener.onMessage(message, new byte[0]);

        assertThat(orgInsideResume.get())
            .as("orgId must be bound on the Redis listener thread before resumeAfterSignal")
            .isEqualTo(orgId);
        // ThreadLocal must be unbound after onMessage returns.
        assertThat(TenantResolver.currentRequestOrganizationId()).isNull();
    }

    @Test
    @DisplayName("falls back to unwrapped resume when run not found (back-compat / orphaned signal)")
    void fallsBackUnwrappedWhenRunMissing() {
        String runId = "run_missing";
        long signalId = 99L;

        when(runContextRegistry.exists(runId)).thenReturn(true);

        SignalWaitEntity signal = new SignalWaitEntity();
        signal.setId(signalId);
        signal.setRunId(runId);
        when(signalWaitRepository.findById(signalId)).thenReturn(Optional.of(signal));
        when(workflowRunRepository.findByRunIdPublic(runId)).thenReturn(Optional.empty());

        AtomicReference<String> orgInsideResume = new AtomicReference<>("sentinel");
        doAnswer(inv -> {
            orgInsideResume.set(TenantResolver.currentRequestOrganizationId());
            return null;
        }).when(signalResumeService).resumeAfterSignal(signal);

        Message message = mock(Message.class);
        when(message.getBody()).thenReturn(("99|core:gate|approved|" + runId + "|peer").getBytes());

        listener.onMessage(message, new byte[0]);

        // Missing run → unwrapped → ThreadLocal stays null inside the call.
        assertThat(orgInsideResume.get())
            .as("orgId must remain null when run not found (back-compat path)")
            .isNull();
    }
}
