package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.AgentTaskEntity;
import com.apimarketplace.agent.repository.AgentExecutionRepository;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.repository.AgentTaskEventRepository;
import com.apimarketplace.agent.repository.AgentTaskNoteRepository;
import com.apimarketplace.agent.repository.AgentTaskRepository;
import com.apimarketplace.conversation.client.ConversationClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focused coverage for {@link AgentTaskService}'s execution-resolution query +
 * CAS methods that none of the 24 sibling test classes exercise:
 * {@code getCurrentInProgressTaskId}, {@code resolveTaskIdForExecution},
 * {@code findTaskForScope}, and the {@code promoteToInProgressIfPending} CAS.
 *
 * <p>Harness mirrors {@code AgentTaskServiceExecutionDispatchTest}: the 8-arg
 * constructor with {@code self} as a separate mock (the REQUIRES_NEW seam). With
 * no active transaction, {@code recordEvent} runs {@code self.doRecordEvent}
 * synchronously (a mock no-op here), and {@code findTaskByIdScoped} resolves
 * off-request via {@code findByIdAndTenantId}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentTaskService execution resolution (query + CAS)")
class AgentTaskServiceExecutionResolutionTest {

    private static final String TENANT = "tenant-1";
    private static final String ORG = "org-1";

    @Mock private AgentTaskRepository taskRepository;
    @Mock private AgentTaskNoteRepository noteRepository;
    @Mock private AgentTaskEventRepository eventRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private AgentExecutionRepository executionRepository;
    @Mock private TaskBoardPublisher taskBoardPublisher;
    @Mock private ConversationClient conversationClient;
    @Mock private AgentTaskService self;

    private AgentTaskService service;

    @BeforeEach
    void setUp() {
        service = new AgentTaskService(taskRepository, noteRepository, eventRepository,
                agentRepository, executionRepository, taskBoardPublisher, conversationClient, self);
    }

    private static AgentTaskEntity taskWithId(UUID id) {
        AgentTaskEntity t = org.mockito.Mockito.mock(AgentTaskEntity.class);
        org.mockito.Mockito.lenient().when(t.getId()).thenReturn(id);
        return t;
    }

    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("getCurrentInProgressTaskId")
    class CurrentInProgress {

        @Test
        @DisplayName("returns the id of the agent's most-recently-started in-progress task")
        void returnsInProgressId() {
            UUID agentId = UUID.randomUUID();
            UUID taskId = UUID.randomUUID();
            AgentTaskEntity task = taskWithId(taskId);
            when(taskRepository.findTopByTenantIdAndAssignedToAgentIdAndStatusOrderByStartedAtDesc(
                    TENANT, agentId, AgentTaskEntity.STATUS_IN_PROGRESS))
                    .thenReturn(Optional.of(task));

            assertThat(service.getCurrentInProgressTaskId(TENANT, agentId)).contains(taskId);
        }

        @Test
        @DisplayName("returns empty when the agent has no in-progress task")
        void emptyWhenNone() {
            UUID agentId = UUID.randomUUID();
            when(taskRepository.findTopByTenantIdAndAssignedToAgentIdAndStatusOrderByStartedAtDesc(
                    TENANT, agentId, AgentTaskEntity.STATUS_IN_PROGRESS))
                    .thenReturn(Optional.empty());

            assertThat(service.getCurrentInProgressTaskId(TENANT, agentId)).isEmpty();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("resolveTaskIdForExecution")
    class ResolveForExecution {

        @Test
        @DisplayName("prefers the in-progress task and never consults the started-fallback")
        void prefersInProgress() {
            UUID agentId = UUID.randomUUID();
            UUID taskId = UUID.randomUUID();
            AgentTaskEntity task = taskWithId(taskId);
            when(taskRepository.findTopByTenantIdAndAssignedToAgentIdAndStatusOrderByStartedAtDesc(
                    TENANT, agentId, AgentTaskEntity.STATUS_IN_PROGRESS))
                    .thenReturn(Optional.of(task));

            assertThat(service.resolveTaskIdForExecution(TENANT, agentId)).contains(taskId);
            verify(taskRepository, never())
                    .findTopByTenantIdAndAssignedToAgentIdAndStartedAtIsNotNullOrderByStartedAtDesc(anyString(), any());
        }

        @Test
        @DisplayName("falls back to the most-recently-started task when none is in progress")
        void fallsBackToStarted() {
            UUID agentId = UUID.randomUUID();
            UUID fallbackId = UUID.randomUUID();
            AgentTaskEntity fallback = taskWithId(fallbackId);
            when(taskRepository.findTopByTenantIdAndAssignedToAgentIdAndStatusOrderByStartedAtDesc(
                    TENANT, agentId, AgentTaskEntity.STATUS_IN_PROGRESS))
                    .thenReturn(Optional.empty());
            when(taskRepository.findTopByTenantIdAndAssignedToAgentIdAndStartedAtIsNotNullOrderByStartedAtDesc(
                    TENANT, agentId))
                    .thenReturn(Optional.of(fallback));

            assertThat(service.resolveTaskIdForExecution(TENANT, agentId)).contains(fallbackId);
        }

        @Test
        @DisplayName("returns empty when the agent has neither an in-progress nor a started task")
        void emptyWhenNeither() {
            UUID agentId = UUID.randomUUID();
            when(taskRepository.findTopByTenantIdAndAssignedToAgentIdAndStatusOrderByStartedAtDesc(
                    TENANT, agentId, AgentTaskEntity.STATUS_IN_PROGRESS))
                    .thenReturn(Optional.empty());
            when(taskRepository.findTopByTenantIdAndAssignedToAgentIdAndStartedAtIsNotNullOrderByStartedAtDesc(
                    TENANT, agentId))
                    .thenReturn(Optional.empty());

            assertThat(service.resolveTaskIdForExecution(TENANT, agentId)).isEmpty();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("findTaskForScope")
    class FindForScope {

        @Test
        @DisplayName("returns the org-scoped task when present")
        void returnsScopedTask() {
            UUID taskId = UUID.randomUUID();
            AgentTaskEntity task = taskWithId(taskId);
            when(taskRepository.findByIdAndOrganizationIdStrict(taskId, ORG)).thenReturn(Optional.of(task));

            assertThat(service.findTaskForScope(taskId, TENANT, ORG)).containsSame(task);
        }

        @Test
        @DisplayName("returns empty when the task is not in the caller's org")
        void emptyWhenOutOfScope() {
            UUID taskId = UUID.randomUUID();
            when(taskRepository.findByIdAndOrganizationIdStrict(taskId, ORG)).thenReturn(Optional.empty());

            assertThat(service.findTaskForScope(taskId, TENANT, ORG)).isEmpty();
        }

        @Test
        @DisplayName("an unscoped caller (null org) is rejected before any repository call")
        void rejectsNullOrg() {
            UUID taskId = UUID.randomUUID();
            assertThatThrownBy(() -> service.findTaskForScope(taskId, TENANT, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("organizationId required");
            verify(taskRepository, never()).findByIdAndOrganizationIdStrict(any(), any());
        }
    }

    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("promoteToInProgressIfPending (CAS)")
    class PromoteCas {

        @Test
        @DisplayName("a winning CAS records the transition, publishes the board update, and returns true")
        void promotesWhenPending() {
            UUID taskId = UUID.randomUUID();
            AgentTaskEntity task = taskWithId(taskId);
            when(taskRepository.promotePendingToInProgress(taskId, TENANT)).thenReturn(1);
            // off-request → findTaskByIdScoped resolves via findByIdAndTenantId
            when(taskRepository.findByIdAndTenantId(taskId, TENANT)).thenReturn(Optional.of(task));

            assertThat(service.promoteToInProgressIfPending(taskId, TENANT)).isTrue();

            verify(self).doRecordEvent(eq(taskId), anyString(), any(), any(), any(), any());
            verify(taskBoardPublisher).publishTaskUpdated(TENANT, task);
        }

        @Test
        @DisplayName("a winning CAS whose post-CAS re-read misses still records + returns true, but publishes nothing")
        void promotesButReadMissSkipsPublish() {
            UUID taskId = UUID.randomUUID();
            when(taskRepository.promotePendingToInProgress(taskId, TENANT)).thenReturn(1);
            // Row gone between the CAS and the re-read → ifPresent is skipped.
            when(taskRepository.findByIdAndTenantId(taskId, TENANT)).thenReturn(Optional.empty());

            assertThat(service.promoteToInProgressIfPending(taskId, TENANT)).isTrue();

            verify(self).doRecordEvent(eq(taskId), anyString(), any(), any(), any(), any());
            verify(taskBoardPublisher, never()).publishTaskUpdated(anyString(), any());
        }

        @Test
        @DisplayName("a losing CAS (already not pending) returns false and records/publishes nothing")
        void noopWhenNotPending() {
            UUID taskId = UUID.randomUUID();
            when(taskRepository.promotePendingToInProgress(taskId, TENANT)).thenReturn(0);

            assertThat(service.promoteToInProgressIfPending(taskId, TENANT)).isFalse();

            verify(self, never()).doRecordEvent(any(), anyString(), any(), any(), any(), any());
            verify(taskBoardPublisher, never()).publishTaskUpdated(anyString(), any());
            verify(taskRepository, never()).findByIdAndTenantId(any(), anyString());
        }
    }
}
