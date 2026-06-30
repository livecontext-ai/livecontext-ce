package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.AgentTaskEntity;
import com.apimarketplace.agent.domain.AgentTaskEventEntity;
import com.apimarketplace.agent.dto.UpdateTaskRequest;
import com.apimarketplace.agent.repository.AgentExecutionRepository;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.repository.AgentTaskEventRepository;
import com.apimarketplace.agent.repository.AgentTaskNoteRepository;
import com.apimarketplace.agent.repository.AgentTaskRepository;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.conversation.client.ConversationClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentTaskService - user review decisions")
class AgentTaskServiceUserReviewDecisionTest {

    @Mock private AgentTaskRepository taskRepository;
    @Mock private AgentTaskNoteRepository noteRepository;
    @Mock private AgentTaskEventRepository eventRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private AgentExecutionRepository executionRepository;
    @Mock private TaskBoardPublisher taskBoardPublisher;
    @Mock private ConversationClient conversationClient;
    @Mock private AgentTaskService self;

    private AgentTaskService service;

    private static final String TENANT = "tenant-1";
    private static final String ORG = "org-1";

    @BeforeEach
    void setUp() {
        service = new AgentTaskService(taskRepository, noteRepository, eventRepository,
                agentRepository, executionRepository, taskBoardPublisher, conversationClient, self);
    }

    @Test
    @DisplayName("Approves reviewer-backed tasks as the user and returns no active reviewer lock")
    void approveTaskByUserClearsReviewerExecutionLock() {
        UUID taskId = UUID.randomUUID();
        UUID reviewerId = UUID.randomUUID();
        AgentTaskEntity completed = task(taskId, AgentTaskEntity.STATUS_COMPLETED, reviewerId, null);

        when(taskRepository.approveByTenantOwner(taskId, TENANT)).thenReturn(1);
        when(taskRepository.findByIdAndOrganizationIdStrict(taskId, ORG)).thenReturn(Optional.of(completed));

        AtomicReference<AgentTaskEntity> result = new AtomicReference<>();
        TenantResolver.runWithOrgScope(ORG, () -> result.set(service.approveTaskByUser(TENANT, taskId)));

        verify(taskRepository).approveByTenantOwner(taskId, TENANT);
        verify(self).recordEvent(eq(taskId), eq(AgentTaskEventEntity.EVT_APPROVED),
                isNull(), eq(TENANT), anyMap(), anyMap());
        verify(taskBoardPublisher).publishTaskUpdated(TENANT, completed);
        assertThat(result.get().getStatus()).isEqualTo(AgentTaskEntity.STATUS_COMPLETED);
        assertThat(result.get().getReviewerAgentId()).isEqualTo(reviewerId);
        assertThat(result.get().getReviewerExecutionId()).isNull();
    }

    @Test
    @DisplayName("Request Changes on reviewer-backed tasks as the user returns to in_progress without reviewer lock")
    void rejectReviewByUserClearsReviewerExecutionLock() {
        UUID taskId = UUID.randomUUID();
        UUID reviewerId = UUID.randomUUID();
        String reason = "needs changes";
        AgentTaskEntity inProgress = task(taskId, AgentTaskEntity.STATUS_IN_PROGRESS, reviewerId, null);

        when(taskRepository.rejectReviewByTenantOwner(taskId, TENANT, reason)).thenReturn(1);
        when(taskRepository.findByIdAndOrganizationIdStrict(taskId, ORG)).thenReturn(Optional.of(inProgress));

        AtomicReference<AgentTaskEntity> result = new AtomicReference<>();
        TenantResolver.runWithOrgScope(ORG, () -> result.set(service.rejectReviewByUser(TENANT, taskId, reason)));

        verify(taskRepository).rejectReviewByTenantOwner(taskId, TENANT, reason);
        verify(self).recordEvent(eq(taskId), eq(AgentTaskEventEntity.EVT_REVIEW_REJECTED),
                isNull(), eq(TENANT), anyMap(), anyMap());
        verify(taskBoardPublisher).publishTaskUpdated(TENANT, inProgress);
        assertThat(result.get().getStatus()).isEqualTo(AgentTaskEntity.STATUS_IN_PROGRESS);
        assertThat(result.get().getReviewerAgentId()).isEqualTo(reviewerId);
        assertThat(result.get().getReviewerExecutionId()).isNull();
    }

    @Test
    @DisplayName("Generic status updates away from review clear reviewer execution token")
    void updateTaskStatusAwayFromReviewClearsReviewerExecutionLock() {
        UUID taskId = UUID.randomUUID();
        UUID reviewerId = UUID.randomUUID();
        AgentTaskEntity inReview = task(taskId, AgentTaskEntity.STATUS_IN_REVIEW, reviewerId, UUID.randomUUID());
        inReview.setAssignedToAgentId(UUID.randomUUID());
        UpdateTaskRequest request = new UpdateTaskRequest(null, null, null, null, null, null, null,
                AgentTaskEntity.STATUS_COMPLETED, null);

        when(taskRepository.findByIdAndOrganizationIdStrict(taskId, ORG)).thenReturn(Optional.of(inReview));
        when(taskRepository.save(inReview)).thenReturn(inReview);

        AtomicReference<AgentTaskEntity> result = new AtomicReference<>();
        TenantResolver.runWithOrgScope(ORG, () ->
                result.set(service.updateTask(TENANT, taskId, null, TENANT, request)));

        assertThat(result.get().getStatus()).isEqualTo(AgentTaskEntity.STATUS_COMPLETED);
        assertThat(result.get().getReviewerExecutionId()).isNull();
        verify(taskRepository).save(inReview);
    }

    @Test
    @DisplayName("Removing reviewer clears reviewer execution token")
    void updateTaskRemoveReviewerClearsReviewerExecutionLock() {
        UUID taskId = UUID.randomUUID();
        UUID reviewerId = UUID.randomUUID();
        AgentTaskEntity inReview = task(taskId, AgentTaskEntity.STATUS_IN_REVIEW, reviewerId, UUID.randomUUID());
        UpdateTaskRequest request = new UpdateTaskRequest(null, null, null, null, null, null, true,
                null, null);

        when(taskRepository.findByIdAndOrganizationIdStrict(taskId, ORG)).thenReturn(Optional.of(inReview));
        when(taskRepository.save(inReview)).thenReturn(inReview);

        AtomicReference<AgentTaskEntity> result = new AtomicReference<>();
        TenantResolver.runWithOrgScope(ORG, () ->
                result.set(service.updateTask(TENANT, taskId, null, TENANT, request)));

        assertThat(result.get().getReviewerAgentId()).isNull();
        assertThat(result.get().getReviewerExecutionId()).isNull();
        verify(taskRepository).save(inReview);
    }

    @Test
    @DisplayName("Auto-fail after reviewer rejection cap returns failed task without reviewer lock")
    void autoFailAfterReviewerRejectionClearsReviewerExecutionLock() {
        UUID taskId = UUID.randomUUID();
        UUID reviewerId = UUID.randomUUID();
        String reason = "review attempts exhausted";
        AgentTaskEntity failed = task(taskId, AgentTaskEntity.STATUS_FAILED, reviewerId, null);

        when(taskRepository.failReviewIfReviewer(taskId, TENANT, reviewerId, reason)).thenReturn(1);
        when(taskRepository.findByIdAndOrganizationIdStrict(taskId, ORG)).thenReturn(Optional.of(failed));

        AtomicReference<AgentTaskEntity> result = new AtomicReference<>();
        TenantResolver.runWithOrgScope(ORG, () ->
                result.set(service.autoFailAfterReviewerRejection(taskId, TENANT, reviewerId, reason)));

        verify(taskRepository).failReviewIfReviewer(taskId, TENANT, reviewerId, reason);
        verify(self).recordEvent(eq(taskId), eq(AgentTaskEventEntity.EVT_AUTO_FAILED),
                eq(reviewerId), isNull(), anyMap(), anyMap());
        verify(taskBoardPublisher).publishTaskUpdated(TENANT, failed);
        assertThat(result.get().getStatus()).isEqualTo(AgentTaskEntity.STATUS_FAILED);
        assertThat(result.get().getReviewerAgentId()).isEqualTo(reviewerId);
        assertThat(result.get().getReviewerExecutionId()).isNull();
    }

    @Test
    @DisplayName("Auto-fail with a reviewer execution token only mutates the matching review cycle")
    void autoFailAfterReviewerRejectionUsesReviewerExecutionToken() {
        UUID taskId = UUID.randomUUID();
        UUID reviewerId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        String reason = "reviewer never decided";
        AgentTaskEntity failed = task(taskId, AgentTaskEntity.STATUS_FAILED, reviewerId, null);

        when(taskRepository.failReviewIfReviewerExecution(taskId, TENANT, reviewerId, executionId, reason))
                .thenReturn(1);
        when(taskRepository.findByIdAndOrganizationIdStrict(taskId, ORG)).thenReturn(Optional.of(failed));

        AtomicReference<AgentTaskEntity> result = new AtomicReference<>();
        TenantResolver.runWithOrgScope(ORG, () -> result.set(
                service.autoFailAfterReviewerRejection(taskId, TENANT, reviewerId, executionId, reason)));

        verify(taskRepository).failReviewIfReviewerExecution(taskId, TENANT, reviewerId, executionId, reason);
        verify(taskRepository, never()).failReviewIfReviewer(taskId, TENANT, reviewerId, reason);
        verify(self).recordEvent(eq(taskId), eq(AgentTaskEventEntity.EVT_AUTO_FAILED),
                eq(reviewerId), isNull(), anyMap(), anyMap());
        assertThat(result.get().getStatus()).isEqualTo(AgentTaskEntity.STATUS_FAILED);
        assertThat(result.get().getReviewerExecutionId()).isNull();
    }

    @Test
    @DisplayName("Reviewer approve with a stale execution token cannot resolve a later review cycle")
    void staleReviewerExecutionCannotApproveLaterReviewCycle() {
        UUID taskId = UUID.randomUUID();
        UUID reviewerId = UUID.randomUUID();
        UUID staleExecutionId = UUID.randomUUID();
        AgentTaskEntity currentReview = task(taskId, AgentTaskEntity.STATUS_IN_REVIEW, reviewerId, UUID.randomUUID());

        when(taskRepository.approveIfReviewerExecution(taskId, TENANT, reviewerId, staleExecutionId)).thenReturn(0);
        when(taskRepository.findByIdAndTenantId(taskId, TENANT)).thenReturn(Optional.of(currentReview));

        assertThatThrownBy(() -> service.approveTask(TENANT, taskId, reviewerId, staleExecutionId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lost a race");

        verify(taskRepository).approveIfReviewerExecution(taskId, TENANT, reviewerId, staleExecutionId);
        verify(taskRepository, never()).approveIfReviewer(taskId, TENANT, reviewerId);
    }

    @Test
    @DisplayName("Reviewer approve without an execution token cannot resolve a pre-lock review cycle")
    void noTokenReviewerCannotApprovePreLockReviewCycle() {
        UUID taskId = UUID.randomUUID();
        UUID reviewerId = UUID.randomUUID();

        assertThatThrownBy(() -> service.approveTask(TENANT, taskId, reviewerId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires a reviewer execution token");

        verify(taskRepository, never()).approveIfReviewer(taskId, TENANT, reviewerId);
        verify(taskRepository, never()).approveIfReviewerExecution(eq(taskId), eq(TENANT), eq(reviewerId),
                any(UUID.class));
    }

    @Test
    @DisplayName("Reviewer reject without an execution token cannot resolve a pre-lock review cycle")
    void noTokenReviewerCannotRejectPreLockReviewCycle() {
        UUID taskId = UUID.randomUUID();
        UUID reviewerId = UUID.randomUUID();
        String reason = "needs work";

        assertThatThrownBy(() -> service.rejectReview(TENANT, taskId, reviewerId, reason))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires a reviewer execution token");

        verify(self, never()).incrementReviewAttemptCount(taskId, TENANT, reviewerId);
        verify(taskRepository, never()).rejectReviewIfReviewer(taskId, TENANT, reviewerId, reason);
    }

    @Test
    @DisplayName("Reviewer task_complete reroute with a stale execution token cannot approve a later review cycle")
    void reviewerCompleteRerouteUsesReviewerExecutionToken() {
        UUID taskId = UUID.randomUUID();
        UUID reviewerId = UUID.randomUUID();
        UUID staleExecutionId = UUID.randomUUID();
        AgentTaskEntity currentReview = task(taskId, AgentTaskEntity.STATUS_IN_REVIEW, reviewerId, UUID.randomUUID());

        when(taskRepository.findByIdAndTenantId(taskId, TENANT)).thenReturn(Optional.of(currentReview));
        when(taskRepository.approveIfReviewerExecution(taskId, TENANT, reviewerId, staleExecutionId)).thenReturn(0);

        assertThatThrownBy(() -> service.completeTask(TENANT, taskId, reviewerId, "approved", false,
                staleExecutionId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lost a race");

        verify(taskRepository).approveIfReviewerExecution(taskId, TENANT, reviewerId, staleExecutionId);
        verify(taskRepository, never()).approveIfReviewer(taskId, TENANT, reviewerId);
    }

    @Test
    @DisplayName("Reviewer task_reject reroute with a stale execution token cannot reject a later review cycle")
    void reviewerRejectRerouteUsesReviewerExecutionToken() {
        UUID taskId = UUID.randomUUID();
        UUID reviewerId = UUID.randomUUID();
        UUID staleExecutionId = UUID.randomUUID();
        String reason = "needs work";
        AgentTaskEntity currentReview = task(taskId, AgentTaskEntity.STATUS_IN_REVIEW, reviewerId, UUID.randomUUID());

        when(taskRepository.findByIdAndTenantId(taskId, TENANT)).thenReturn(Optional.of(currentReview));
        when(self.incrementReviewAttemptCount(taskId, TENANT, reviewerId, staleExecutionId)).thenReturn(0);

        assertThatThrownBy(() -> service.rejectTask(TENANT, taskId, reviewerId, reason, staleExecutionId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lost a race");

        verify(self).incrementReviewAttemptCount(taskId, TENANT, reviewerId, staleExecutionId);
        verify(self, never()).incrementReviewAttemptCount(taskId, TENANT, reviewerId);
    }

    @Test
    @DisplayName("Reviewer reject with an execution token only mutates the matching review cycle")
    void rejectReviewUsesReviewerExecutionToken() {
        UUID taskId = UUID.randomUUID();
        UUID reviewerId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        String reason = "revise this";
        AgentTaskEntity inReview = task(taskId, AgentTaskEntity.STATUS_IN_REVIEW, reviewerId, executionId);
        inReview.setReviewAttemptCount(1);
        AgentTaskEntity inProgress = task(taskId, AgentTaskEntity.STATUS_IN_PROGRESS, reviewerId, null);

        when(self.incrementReviewAttemptCount(taskId, TENANT, reviewerId, executionId)).thenReturn(1);
        when(taskRepository.findByIdAndTenantId(taskId, TENANT))
                .thenReturn(Optional.of(inReview))
                .thenReturn(Optional.of(inProgress));
        when(taskRepository.rejectReviewIfReviewerExecution(taskId, TENANT, reviewerId, executionId, reason))
                .thenReturn(1);

        AgentTaskEntity result = service.rejectReview(TENANT, taskId, reviewerId, executionId, reason);

        verify(self).incrementReviewAttemptCount(taskId, TENANT, reviewerId, executionId);
        verify(taskRepository).rejectReviewIfReviewerExecution(taskId, TENANT, reviewerId, executionId, reason);
        verify(taskRepository, never()).rejectReviewIfReviewer(taskId, TENANT, reviewerId, reason);
        assertThat(result.getStatus()).isEqualTo(AgentTaskEntity.STATUS_IN_PROGRESS);
        assertThat(result.getReviewerExecutionId()).isNull();
    }

    @Test
    @DisplayName("Reviewer failure-to-act attempt increments are scoped to the current execution token")
    void reviewAttemptIncrementUsesReviewerExecutionToken() {
        UUID taskId = UUID.randomUUID();
        UUID reviewerId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();

        when(taskRepository.incrementReviewAttemptCountForExecution(taskId, TENANT, reviewerId, executionId))
                .thenReturn(0);

        int attemptCount = service.incrementReviewAttemptCount(taskId, TENANT, reviewerId, executionId);

        verify(taskRepository).incrementReviewAttemptCountForExecution(taskId, TENANT, reviewerId, executionId);
        verify(taskRepository, never()).incrementReviewAttemptCount(taskId, TENANT, reviewerId);
        assertThat(attemptCount).isZero();
    }

    private static AgentTaskEntity task(UUID id, String status, UUID reviewerId, UUID reviewerExecutionId) {
        AgentTaskEntity task = new AgentTaskEntity();
        task.setId(id);
        task.setTenantId(TENANT);
        task.setOrganizationId(ORG);
        task.setStatus(status);
        task.setTitle("Review task");
        task.setInstructions("Review the result");
        task.setPriority(AgentTaskEntity.PRIORITY_NORMAL);
        task.setReviewerAgentId(reviewerId);
        task.setReviewerExecutionId(reviewerExecutionId);
        return task;
    }
}
