package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.domain.AgentExecutionEntity;
import com.apimarketplace.agent.repository.AgentExecutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link AgentActivitySnapshotService} - the shared late-subscribe replay that
 * re-emits {@code execution_started} for an agent's currently-RUNNING executions. Used by
 * both the cloud gateway path (InternalAgentController) and the CE monolith WS path
 * (MonolithWsActionHandler), so the behaviour is pinned once here.
 *
 * <p>Closes the gap where a client subscribing to {@code agent:activity:{id}} mid-run -
 * especially for a bridge/CLI agent, whose only lifecycle events are started/completed -
 * would never learn the agent is busy.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentActivitySnapshotService")
class AgentActivitySnapshotServiceTest {

    @Mock private AgentExecutionRepository agentExecutionRepository;
    @Mock private AgentActivityPublisher agentActivityPublisher;

    private AgentActivitySnapshotService service;

    @BeforeEach
    void setUp() {
        service = new AgentActivitySnapshotService(agentExecutionRepository, agentActivityPublisher);
    }

    private AgentExecutionEntity running(UUID id, UUID agentId, String model, String source, UUID taskId) {
        AgentExecutionEntity e = new AgentExecutionEntity();
        e.setId(id);
        e.setAgentEntityId(agentId);
        e.setStatus("RUNNING");
        e.setModel(model);
        e.setSource(source);
        e.setTaskId(taskId);
        e.setStartedAt(Instant.now());
        return e;
    }

    @Test
    @DisplayName("re-publishes execution_started for each RUNNING execution (id→executionId, model, source, taskId)")
    void republishesExecutionStartedForRunningExecutions() {
        UUID agentId = UUID.randomUUID();
        UUID execId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        when(agentExecutionRepository.findRunningByAgentEntityIdSince(eq(agentId), any(Instant.class)))
                .thenReturn(List.of(running(execId, agentId, "claude-opus-4-8", "CONVERSATION", taskId)));

        int count = service.publishRunningSnapshot(agentId);

        assertThat(count).isEqualTo(1);
        verify(agentActivityPublisher).publishExecutionStarted(
                agentId.toString(), execId.toString(), "claude-opus-4-8", "CONVERSATION", taskId.toString());
    }

    @Test
    @DisplayName("re-publishes for EVERY running execution when an agent has more than one in flight")
    void republishesForEveryRunningExecution() {
        UUID agentId = UUID.randomUUID();
        UUID execA = UUID.randomUUID();
        UUID execB = UUID.randomUUID();
        when(agentExecutionRepository.findRunningByAgentEntityIdSince(eq(agentId), any(Instant.class)))
                .thenReturn(List.of(
                        running(execA, agentId, "claude-opus-4-8", "CONVERSATION", null),
                        running(execB, agentId, "deepseek-chat", "WORKFLOW", null)));

        assertThat(service.publishRunningSnapshot(agentId)).isEqualTo(2);
        verify(agentActivityPublisher).publishExecutionStarted(
                agentId.toString(), execA.toString(), "claude-opus-4-8", "CONVERSATION", null);
        verify(agentActivityPublisher).publishExecutionStarted(
                agentId.toString(), execB.toString(), "deepseek-chat", "WORKFLOW", null);
    }

    @Test
    @DisplayName("passes a recency cutoff in the recent past so crashed-pod RUNNING leftovers are excluded")
    void usesRecencyCutoff() {
        UUID agentId = UUID.randomUUID();
        when(agentExecutionRepository.findRunningByAgentEntityIdSince(eq(agentId), any(Instant.class)))
                .thenReturn(List.of());

        Instant before = Instant.now();
        service.publishRunningSnapshot(agentId);

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(agentExecutionRepository).findRunningByAgentEntityIdSince(eq(agentId), cutoff.capture());
        assertThat(cutoff.getValue()).isBefore(before);
        assertThat(cutoff.getValue()).isAfter(before.minus(20, ChronoUnit.MINUTES));
    }

    @Test
    @DisplayName("no RUNNING executions → publishes nothing, returns 0")
    void noRunningExecutionsPublishesNothing() {
        UUID agentId = UUID.randomUUID();
        when(agentExecutionRepository.findRunningByAgentEntityIdSince(eq(agentId), any(Instant.class)))
                .thenReturn(List.of());

        assertThat(service.publishRunningSnapshot(agentId)).isZero();
        verifyNoInteractions(agentActivityPublisher);
    }

    @Test
    @DisplayName("null taskId on the execution is forwarded as null (agent running outside any task)")
    void forwardsNullTaskId() {
        UUID agentId = UUID.randomUUID();
        UUID execId = UUID.randomUUID();
        when(agentExecutionRepository.findRunningByAgentEntityIdSince(eq(agentId), any(Instant.class)))
                .thenReturn(List.of(running(execId, agentId, "deepseek-chat", "WORKFLOW", null)));

        service.publishRunningSnapshot(agentId);

        verify(agentActivityPublisher).publishExecutionStarted(
                agentId.toString(), execId.toString(), "deepseek-chat", "WORKFLOW", null);
    }

    @Test
    @DisplayName("null agentId is a no-op - no query, no publish, returns 0")
    void nullAgentIdIsNoOp() {
        assertThat(service.publishRunningSnapshot(null)).isZero();
        verifyNoInteractions(agentExecutionRepository);
        verifyNoInteractions(agentActivityPublisher);
    }
}
