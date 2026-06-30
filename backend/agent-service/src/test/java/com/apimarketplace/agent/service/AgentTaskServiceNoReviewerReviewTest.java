package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.AgentTaskEntity;
import com.apimarketplace.agent.repository.AgentExecutionRepository;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.repository.AgentTaskEventRepository;
import com.apimarketplace.agent.repository.AgentTaskNoteRepository;
import com.apimarketplace.agent.repository.AgentTaskRepository;
import com.apimarketplace.conversation.client.ConversationClient;
import com.apimarketplace.notification.client.NotificationClient;
import com.apimarketplace.notification.client.dto.NotificationEmitRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the "no reviewer → the task comes back to its creator" review flow:
 * <ul>
 *   <li>{@code completeTask} always parks in {@code in_review} (never auto-completes), even with
 *       no reviewer assigned.</li>
 *   <li>With no reviewer at all, an {@code AGENT_TASK_AWAITING_REVIEW} bell is emitted to the
 *       human creator (or, for an agent-created task, the tenant/workspace owner).</li>
 *   <li>A reviewer (agent or human) suppresses that creator notification.</li>
 *   <li>The creator/tenant owner resolves it via {@code approveTaskByUser} → {@code completed}
 *       or {@code rejectReviewByUser} → back to {@code in_progress}.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentTaskService - no-reviewer review returns to the creator")
class AgentTaskServiceNoReviewerReviewTest {

    @Mock private AgentTaskRepository taskRepository;
    @Mock private AgentTaskNoteRepository noteRepository;
    @Mock private AgentTaskEventRepository eventRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private AgentExecutionRepository executionRepository;
    @Mock private TaskBoardPublisher taskBoardPublisher;
    @Mock private ConversationClient conversationClient;
    @Mock private NotificationClient notificationClient;
    @Mock private AgentTaskService self;

    private AgentTaskService service;

    private static final String TENANT = "creator-user";
    private static final String ORG = "org-1";

    @BeforeEach
    void setUp() throws Exception {
        service = new AgentTaskService(taskRepository, noteRepository, eventRepository,
                agentRepository, executionRepository, taskBoardPublisher, conversationClient, self);
        Field f = AgentTaskService.class.getDeclaredField("notificationClient");
        f.setAccessible(true);
        f.set(service, notificationClient);
        lenient().when(notificationClient.emit(any())).thenReturn(true);
    }

    private AgentTaskEntity task(String status, UUID reviewerAgentId, String reviewerUserId,
                                 String createdByUserId) {
        AgentTaskEntity t = new AgentTaskEntity();
        t.setId(UUID.randomUUID());
        t.setTenantId(TENANT);
        t.setOrganizationId(ORG);
        t.setStatus(status);
        t.setTitle("Draft the report");
        t.setInstructions("Write it");
        t.setPriority(AgentTaskEntity.PRIORITY_NORMAL);
        t.setReviewerAgentId(reviewerAgentId);
        t.setReviewerUserId(reviewerUserId);
        t.setCreatedByUserId(createdByUserId);
        return t;
    }

    @Test
    @DisplayName("completeTask with no reviewer → in_review and notifies the human creator")
    void completeWithNoReviewerParksInReviewAndNotifiesCreator() {
        UUID assigneeId = UUID.randomUUID();
        AgentTaskEntity t = task(AgentTaskEntity.STATUS_IN_REVIEW, null, null, TENANT);
        when(taskRepository.findByIdAndTenantId(t.getId(), TENANT)).thenReturn(Optional.of(t));
        when(taskRepository.submitForReview(t.getId(), TENANT, assigneeId, "done")).thenReturn(1);

        AgentTaskEntity result = service.completeTask(TENANT, t.getId(), assigneeId, "done");

        assertThat(result.getStatus()).isEqualTo(AgentTaskEntity.STATUS_IN_REVIEW);

        ArgumentCaptor<NotificationEmitRequest> captor = ArgumentCaptor.forClass(NotificationEmitRequest.class);
        verify(notificationClient).emit(captor.capture());
        NotificationEmitRequest emit = captor.getValue();
        assertThat(emit.getCategory()).isEqualTo("AGENT_TASK_AWAITING_REVIEW");
        assertThat(emit.getTenantId()).isEqualTo(TENANT);          // recipient = the creator
        assertThat(emit.getOrganizationId()).isEqualTo(ORG);
        assertThat(emit.getSubjectType()).isEqualTo("AGENT_TASK");
        assertThat(emit.getSubjectId()).isEqualTo(t.getId());
        assertThat(emit.getPayload()).containsEntry("status", "in_review");
        assertThat(emit.getPayload()).containsEntry("subjectName", "Draft the report");
    }

    @Test
    @DisplayName("completeTask with no reviewer on an agent-created task → notifies the tenant/workspace owner")
    void completeWithNoReviewerAgentCreatedNotifiesTenantOwner() {
        UUID assigneeId = UUID.randomUUID();
        // Agent-created subtask: no human creator. Owner (== tenantId) must be notified.
        AgentTaskEntity t = task(AgentTaskEntity.STATUS_IN_REVIEW, null, null, null);
        when(taskRepository.findByIdAndTenantId(t.getId(), TENANT)).thenReturn(Optional.of(t));
        when(taskRepository.submitForReview(t.getId(), TENANT, assigneeId, "done")).thenReturn(1);

        service.completeTask(TENANT, t.getId(), assigneeId, "done");

        ArgumentCaptor<NotificationEmitRequest> captor = ArgumentCaptor.forClass(NotificationEmitRequest.class);
        verify(notificationClient).emit(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo(TENANT);  // tenant owner fallback
        assertThat(captor.getValue().getCategory()).isEqualTo("AGENT_TASK_AWAITING_REVIEW");
    }

    @Test
    @DisplayName("rejectTask (agent reports failure) with no reviewer → also notifies the creator")
    void rejectWithNoReviewerNotifiesCreator() {
        UUID assigneeId = UUID.randomUUID();
        AgentTaskEntity t = task(AgentTaskEntity.STATUS_IN_REVIEW, null, null, TENANT);
        when(taskRepository.findByIdAndTenantId(t.getId(), TENANT)).thenReturn(Optional.of(t));
        when(taskRepository.submitFailureForReview(t.getId(), TENANT, assigneeId, "broke")).thenReturn(1);

        service.rejectTask(TENANT, t.getId(), assigneeId, "broke");

        ArgumentCaptor<NotificationEmitRequest> captor = ArgumentCaptor.forClass(NotificationEmitRequest.class);
        verify(notificationClient).emit(captor.capture());
        assertThat(captor.getValue().getCategory()).isEqualTo("AGENT_TASK_AWAITING_REVIEW");
        assertThat(captor.getValue().getTenantId()).isEqualTo(TENANT);
    }

    @Test
    @DisplayName("completeTask WITH a human reviewer → does NOT emit the creator awaiting-review bell")
    void completeWithReviewerDoesNotNotifyCreator() {
        UUID assigneeId = UUID.randomUUID();
        // A human reviewer is assigned (notified at assign time) → no awaiting-review bell here.
        AgentTaskEntity t = task(AgentTaskEntity.STATUS_IN_REVIEW, null, "reviewer-user", TENANT);
        when(taskRepository.findByIdAndTenantId(t.getId(), TENANT)).thenReturn(Optional.of(t));
        when(taskRepository.submitForReview(t.getId(), TENANT, assigneeId, "done")).thenReturn(1);

        service.completeTask(TENANT, t.getId(), assigneeId, "done");

        verify(notificationClient, never()).emit(any());
    }

    @Test
    @DisplayName("approveTaskByUser by the creator/owner completes a reviewer-less task")
    void creatorApprovesNoReviewerTask() {
        AgentTaskEntity completed = task(AgentTaskEntity.STATUS_COMPLETED, null, null, TENANT);
        when(taskRepository.approveByTenantOwner(completed.getId(), TENANT)).thenReturn(1);
        when(taskRepository.findByIdAndTenantId(completed.getId(), TENANT)).thenReturn(Optional.of(completed));

        AgentTaskEntity result = service.approveTaskByUser(TENANT, completed.getId());

        assertThat(result.getStatus()).isEqualTo(AgentTaskEntity.STATUS_COMPLETED);
        verify(taskRepository).approveByTenantOwner(completed.getId(), TENANT);
    }

    @Test
    @DisplayName("rejectReviewByUser by the creator/owner sends a reviewer-less task back to in_progress")
    void creatorRejectsNoReviewerTask() {
        AgentTaskEntity backToProgress = task(AgentTaskEntity.STATUS_IN_PROGRESS, null, null, TENANT);
        when(taskRepository.rejectReviewByTenantOwner(backToProgress.getId(), TENANT, "needs more detail"))
                .thenReturn(1);
        when(taskRepository.findByIdAndTenantId(backToProgress.getId(), TENANT))
                .thenReturn(Optional.of(backToProgress));

        AgentTaskEntity result = service.rejectReviewByUser(TENANT, backToProgress.getId(), "needs more detail");

        assertThat(result.getStatus()).isEqualTo(AgentTaskEntity.STATUS_IN_PROGRESS);
        verify(taskRepository).rejectReviewByTenantOwner(backToProgress.getId(), TENANT, "needs more detail");
    }
}
