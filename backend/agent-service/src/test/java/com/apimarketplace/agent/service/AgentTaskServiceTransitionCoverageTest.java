package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.AgentTaskEntity;
import com.apimarketplace.agent.dto.UpdateTaskRequest;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers two lifecycle edges left untested by the main suite:
 * <ul>
 *   <li>{@code updateTask} status-transition validation (invalid status, in_progress/in_review
 *       requires an assignee, and the terminal→non-terminal reopen that must clear the stale result).</li>
 *   <li>{@code completeTask} role-reroute: a reviewer calling {@code task_complete} on an
 *       {@code in_review} task is rerouted to approve (bug #8), not pushed back through submit-for-review.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentTaskService - lifecycle transition coverage")
class AgentTaskServiceTransitionCoverageTest {

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

    @BeforeEach
    void setUp() {
        service = new AgentTaskService(taskRepository, noteRepository, eventRepository,
                agentRepository, executionRepository, taskBoardPublisher, conversationClient, self);
        lenient().when(taskRepository.save(any(AgentTaskEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private AgentTaskEntity task(UUID id, String status) {
        AgentTaskEntity t = new AgentTaskEntity();
        t.setId(id);
        t.setTenantId(TENANT);
        t.setStatus(status);
        t.setTitle("Task");
        t.setInstructions("Do it");
        t.setPriority(AgentTaskEntity.PRIORITY_NORMAL);
        return t;
    }

    private UpdateTaskRequest statusUpdate(String status) {
        return new UpdateTaskRequest(null, null, null, null, null, null, null, status, null, null, null);
    }

    @Nested
    @DisplayName("updateTask status transitions")
    class UpdateTransitions {

        @Test
        @DisplayName("an unknown target status is rejected")
        void invalidStatusRejected() {
            UUID id = UUID.randomUUID();
            when(taskRepository.findByIdAndTenantId(id, TENANT)).thenReturn(Optional.of(task(id, AgentTaskEntity.STATUS_PENDING)));

            // Caller is the tenant owner (callingAgentId == null && callingUserId == tenantId).
            assertThatThrownBy(() -> service.updateTask(TENANT, id, null, TENANT, statusUpdate("bogus")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("invalid status");
        }

        @Test
        @DisplayName("moving to in_progress without an assignee is rejected")
        void inProgressWithoutAssigneeRejected() {
            UUID id = UUID.randomUUID();
            when(taskRepository.findByIdAndTenantId(id, TENANT)).thenReturn(Optional.of(task(id, AgentTaskEntity.STATUS_PENDING)));

            assertThatThrownBy(() -> service.updateTask(TENANT, id, null, TENANT,
                    statusUpdate(AgentTaskEntity.STATUS_IN_PROGRESS)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("requires an assignee");
        }

        @Test
        @DisplayName("reopening a terminal task clears the stale result")
        void reopenFromTerminalClearsResult() {
            UUID id = UUID.randomUUID();
            AgentTaskEntity completed = task(id, AgentTaskEntity.STATUS_COMPLETED);
            completed.setResult("a stale result from the previous run");
            when(taskRepository.findByIdAndTenantId(id, TENANT)).thenReturn(Optional.of(completed));

            // pending is non-terminal and needs no assignee → a clean reopen.
            AgentTaskEntity result = service.updateTask(TENANT, id, null, TENANT,
                    statusUpdate(AgentTaskEntity.STATUS_PENDING));

            assertThat(result.getStatus()).isEqualTo(AgentTaskEntity.STATUS_PENDING);
            assertThat(result.getResult()).isNull();
        }
    }

    @Nested
    @DisplayName("completeTask role-reroute")
    class CompleteReroute {

        @Test
        @DisplayName("a reviewer calling task_complete on an in_review task is rerouted to approve")
        void reviewerCompleteReroutesToApprove() {
            UUID id = UUID.randomUUID();
            UUID reviewer = UUID.randomUUID();
            UUID reviewerExecutionId = UUID.randomUUID();
            AgentTaskEntity inReview = task(id, AgentTaskEntity.STATUS_IN_REVIEW);
            inReview.setReviewerAgentId(reviewer);
            when(taskRepository.findByIdAndTenantId(id, TENANT)).thenReturn(Optional.of(inReview));
            // A reviewer agent resolves reviews with its execution token → approveIfReviewerExecution.
            when(taskRepository.approveIfReviewerExecution(id, TENANT, reviewer, reviewerExecutionId)).thenReturn(1);

            service.completeTask(TENANT, id, reviewer, "looks good", false, reviewerExecutionId);

            // Rerouted to the reviewer-approve CAS - NOT pushed back through submit-for-review.
            verify(taskRepository).approveIfReviewerExecution(id, TENANT, reviewer, reviewerExecutionId);
            verify(taskRepository, never()).submitForReview(any(), any(), any(), any());
        }
    }
}
