package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.AgentTaskEntity;
import com.apimarketplace.agent.repository.AgentExecutionRepository;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.repository.AgentTaskEventRepository;
import com.apimarketplace.agent.repository.AgentTaskNoteRepository;
import com.apimarketplace.agent.repository.AgentTaskRepository;
import com.apimarketplace.common.classification.DataSensitivity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CASA LC-066: {@code task_complete}, {@code task_reject} and {@code task_reject_review} from a
 * restricted execution tag the task RESTRICTED in the same transaction as the text they write,
 * and only once the caller's own state transition went through. A refused call (wrong state,
 * wrong role, lost race) tags nothing: before this, the delegation tool ratcheted the task ahead
 * of the call, so a refused or blank call still tagged it for good.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentTaskService - LC-066 ratchet on task_complete / task_reject / task_reject_review")
class AgentTaskServiceRestrictedRatchetTest {

    private static final String TENANT = "tenant-1";

    @Mock private AgentTaskRepository taskRepository;
    @Mock private AgentTaskNoteRepository noteRepository;
    @Mock private AgentTaskEventRepository eventRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private AgentExecutionRepository executionRepository;
    @Mock private AgentTaskService self;

    private AgentTaskService service;
    private final UUID taskId = UUID.randomUUID();
    private final UUID assignee = UUID.randomUUID();
    private final UUID reviewer = UUID.randomUUID();
    private final UUID reviewerExecution = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // No board publisher and no conversation client: nothing is dispatched after the write.
        service = new AgentTaskService(taskRepository, noteRepository, eventRepository,
                agentRepository, executionRepository, null, null, self);
    }

    private AgentTaskEntity task(String status) {
        AgentTaskEntity t = new AgentTaskEntity();
        t.setId(taskId);
        t.setTenantId(TENANT);
        t.setStatus(status);
        t.setAssignedToAgentId(assignee);
        t.setReviewerAgentId(reviewer);
        return t;
    }

    // --- task_complete ------------------------------------------------------------------------

    @Test
    @DisplayName("LC-066: a restricted task_complete tags the task right after its submission succeeds, before the reload")
    void restrictedCompleteTagsAfterSubmission() {
        when(taskRepository.findByIdAndTenantId(taskId, TENANT)).thenReturn(Optional.of(task("in_progress")));
        when(taskRepository.submitForReview(taskId, TENANT, assignee, "mail summary")).thenReturn(1);

        service.completeTask(TENANT, taskId, assignee, "mail summary", false, null, DataSensitivity.RESTRICTED);

        InOrder order = inOrder(taskRepository);
        order.verify(taskRepository).submitForReview(taskId, TENANT, assignee, "mail summary");
        order.verify(taskRepository).markRestricted(taskId);
        // The reloaded entity (handed to the reviewer kickoff) is read after the tag.
        order.verify(taskRepository).findByIdAndTenantId(taskId, TENANT);
    }

    @Test
    @DisplayName("LC-066 regression: a refused task_complete (submission matched no row) tags nothing")
    void refusedCompleteDoesNotTag() {
        when(taskRepository.findByIdAndTenantId(taskId, TENANT)).thenReturn(Optional.of(task("completed")));
        when(taskRepository.submitForReview(taskId, TENANT, assignee, "mail summary")).thenReturn(0);

        assertThatThrownBy(() -> service.completeTask(TENANT, taskId, assignee, "mail summary", false, null,
                DataSensitivity.RESTRICTED)).isInstanceOf(IllegalStateException.class);

        verify(taskRepository, never()).markRestricted(any());
    }

    @Test
    @DisplayName("LC-066 regression: a task_complete over the size limit is refused before anything is tagged")
    void oversizedCompleteDoesNotTag() {
        String huge = "x".repeat(AgentTaskService.MAX_RESULT_BYTES + 1);

        assertThatThrownBy(() -> service.completeTask(TENANT, taskId, assignee, huge, false, null,
                DataSensitivity.RESTRICTED)).isInstanceOf(IllegalArgumentException.class);

        verify(taskRepository, never()).markRestricted(any());
    }

    @Test
    @DisplayName("LC-066: an ordinary task_complete tags nothing")
    void normalCompleteDoesNotTag() {
        when(taskRepository.findByIdAndTenantId(taskId, TENANT)).thenReturn(Optional.of(task("in_progress")));
        when(taskRepository.submitForReview(taskId, TENANT, assignee, "done")).thenReturn(1);

        service.completeTask(TENANT, taskId, assignee, "done", false, null, DataSensitivity.NORMAL);

        verify(taskRepository, never()).markRestricted(any());
    }

    // --- task_reject --------------------------------------------------------------------------

    @Test
    @DisplayName("LC-066: a restricted task_reject tags the task right after its failure report succeeds")
    void restrictedRejectTagsAfterSubmission() {
        when(taskRepository.findByIdAndTenantId(taskId, TENANT)).thenReturn(Optional.of(task("in_progress")));
        when(taskRepository.submitFailureForReview(taskId, TENANT, assignee, "mail says no")).thenReturn(1);

        service.rejectTask(TENANT, taskId, assignee, "mail says no", null, DataSensitivity.RESTRICTED);

        InOrder order = inOrder(taskRepository);
        order.verify(taskRepository).submitFailureForReview(taskId, TENANT, assignee, "mail says no");
        order.verify(taskRepository).markRestricted(taskId);
    }

    @Test
    @DisplayName("LC-066 regression: a refused task_reject (failure report matched no row) tags nothing")
    void refusedRejectDoesNotTag() {
        when(taskRepository.findByIdAndTenantId(taskId, TENANT)).thenReturn(Optional.of(task("completed")));
        when(taskRepository.submitFailureForReview(taskId, TENANT, assignee, "mail says no")).thenReturn(0);

        assertThatThrownBy(() -> service.rejectTask(TENANT, taskId, assignee, "mail says no", null,
                DataSensitivity.RESTRICTED)).isInstanceOf(IllegalStateException.class);

        verify(taskRepository, never()).markRestricted(any());
    }

    // --- task_reject_review -------------------------------------------------------------------

    @Test
    @DisplayName("LC-066: a restricted task_reject_review tags the task right after the rejection succeeds")
    void restrictedRejectReviewTagsAfterRejection() {
        when(self.incrementReviewAttemptCount(taskId, TENANT, reviewer, reviewerExecution)).thenReturn(1);
        when(taskRepository.findByIdAndTenantId(taskId, TENANT)).thenReturn(Optional.of(task("in_review")));
        when(taskRepository.rejectReviewIfReviewerExecution(taskId, TENANT, reviewer, reviewerExecution, "redo"))
                .thenReturn(1);

        service.rejectReview(TENANT, taskId, reviewer, reviewerExecution, "redo", DataSensitivity.RESTRICTED);

        InOrder order = inOrder(taskRepository);
        order.verify(taskRepository).rejectReviewIfReviewerExecution(taskId, TENANT, reviewer, reviewerExecution, "redo");
        order.verify(taskRepository).markRestricted(taskId);
    }

    @Test
    @DisplayName("LC-066: a restricted task_reject_review that hits the attempt cap tags the auto-failed task")
    void restrictedRejectReviewAutoFailTags() {
        AgentTaskEntity atCap = task("in_review");
        atCap.setMaxReviewAttempts(1);
        when(self.incrementReviewAttemptCount(taskId, TENANT, reviewer, reviewerExecution)).thenReturn(1);
        when(taskRepository.findByIdAndTenantId(taskId, TENANT)).thenReturn(Optional.of(atCap));
        when(self.autoFailAfterReviewerRejection(any(), any(), any(), any(), any())).thenReturn(task("failed"));

        service.rejectReview(TENANT, taskId, reviewer, reviewerExecution, "redo", DataSensitivity.RESTRICTED);

        verify(taskRepository).markRestricted(taskId);
    }

    @Test
    @DisplayName("LC-066 regression: a task_reject_review by a non-reviewer (no attempt counted) tags nothing")
    void refusedRejectReviewDoesNotTag() {
        when(self.incrementReviewAttemptCount(taskId, TENANT, reviewer, reviewerExecution)).thenReturn(0);
        when(taskRepository.findByIdAndTenantId(taskId, TENANT)).thenReturn(Optional.of(task("in_progress")));

        assertThatThrownBy(() -> service.rejectReview(TENANT, taskId, reviewer, reviewerExecution, "redo",
                DataSensitivity.RESTRICTED)).isInstanceOf(IllegalStateException.class);

        verify(taskRepository, never()).markRestricted(any());
    }

    @Test
    @DisplayName("LC-066 regression: a task_reject_review without a reviewer execution token is refused and tags nothing")
    void tokenlessRejectReviewDoesNotTag() {
        assertThatThrownBy(() -> service.rejectReview(TENANT, taskId, reviewer, null, "redo",
                DataSensitivity.RESTRICTED)).isInstanceOf(IllegalStateException.class);

        verify(taskRepository, never()).markRestricted(any());
    }

    @Test
    @DisplayName("LC-066: a reviewer calling task_reject on an in_review task is rerouted with its classification")
    void rerouteKeepsClassification() {
        when(taskRepository.findByIdAndTenantId(taskId, TENANT)).thenReturn(Optional.of(task("in_review")));
        when(self.incrementReviewAttemptCount(taskId, TENANT, reviewer, reviewerExecution)).thenReturn(1);
        when(taskRepository.rejectReviewIfReviewerExecution(taskId, TENANT, reviewer, reviewerExecution, "redo"))
                .thenReturn(1);

        service.rejectTask(TENANT, taskId, reviewer, "redo", reviewerExecution, DataSensitivity.RESTRICTED);

        verify(taskRepository).markRestricted(taskId);
        verify(taskRepository, never()).submitFailureForReview(any(), any(), any(), any());
    }
}
