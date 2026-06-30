package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.AgentTaskEntity;
import com.apimarketplace.agent.domain.AgentTaskEventEntity;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Soft-delete / restore / purge for the task board's Deleted column.
 * <ul>
 *   <li>{@code softDeleteTask} → status='deleted', deleted_at + previous_status stamped,
 *       execution locks detached WITHOUT reverting status, creator/reviewer/owner-only,
 *       idempotent; allowed from any status (not just terminal, unlike hardDelete).</li>
 *   <li>{@code restoreTask} → back to previous_status (falling back to pending), only from
 *       the trash, never restores to an assignee-less in_progress/in_review.</li>
 *   <li>{@code purgeDeletedTask} / {@code purgeExpiredDeletedTaskById} → permanent removal
 *       guarded to trashed rows; {@code purgeExpiredDeletedTasks} sweeps by cutoff.</li>
 *   <li>{@code updateTask} refuses a direct status='deleted' and clears the soft-delete
 *       bookkeeping when a card is dragged back out of the trash.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentTaskService - soft delete, restore, purge")
class AgentTaskServiceSoftDeleteTest {

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
    private final UUID taskId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new AgentTaskService(taskRepository, noteRepository, eventRepository,
                agentRepository, executionRepository, taskBoardPublisher, conversationClient, self);
        // save() echoes its argument so assertions can read the mutated entity.
        lenient().when(taskRepository.save(any(AgentTaskEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private AgentTaskEntity task(String status) {
        AgentTaskEntity t = new AgentTaskEntity();
        t.setId(taskId);
        t.setTenantId(TENANT);
        t.setStatus(status);
        t.setCreatedByUserId("creator");
        return t;
    }

    // ── soft delete ──────────────────────────────────────────────────

    @Nested
    @DisplayName("softDeleteTask")
    class SoftDelete {

        @Test
        @DisplayName("trashes a task: status='deleted', deleted_at + previous_status stamped, exec locks cleared")
        void trashesTask() {
            AgentTaskEntity t = task(AgentTaskEntity.STATUS_IN_PROGRESS);
            t.setAssigneeExecutionId(UUID.randomUUID());
            t.setReviewerExecutionId(UUID.randomUUID());
            when(taskRepository.findByIdAndTenantId(taskId, TENANT)).thenReturn(Optional.of(t));

            AgentTaskEntity result = service.softDeleteTask(TENANT, taskId, null, TENANT);

            assertThat(result.getStatus()).isEqualTo(AgentTaskEntity.STATUS_DELETED);
            assertThat(result.getPreviousStatus()).isEqualTo(AgentTaskEntity.STATUS_IN_PROGRESS);
            assertThat(result.getDeletedAt()).isNotNull();
            // Cleared WITHOUT forceUnlock (which would have reverted status to pending).
            assertThat(result.getAssigneeExecutionId()).isNull();
            assertThat(result.getReviewerExecutionId()).isNull();
            verify(taskRepository, never()).forceUnlockAssigneeExecution(any());
            verify(taskRepository, never()).forceUnlockReviewerExecution(any());
            verify(self).recordEvent(eq(taskId), eq(AgentTaskEventEntity.EVT_DELETED), any(), eq(TENANT), any(), any());
            verify(taskRepository).save(t);
        }

        @Test
        @DisplayName("the task's creator agent (not the assignee) may trash it")
        void creatorAgentCanTrash() {
            UUID creator = UUID.randomUUID();
            AgentTaskEntity t = task(AgentTaskEntity.STATUS_PENDING);
            t.setCreatedByUserId(null);
            t.setCreatedByAgentId(creator);
            when(taskRepository.findByIdAndTenantId(taskId, TENANT)).thenReturn(Optional.of(t));

            AgentTaskEntity result = service.softDeleteTask(TENANT, taskId, creator, null);

            assertThat(result.getStatus()).isEqualTo(AgentTaskEntity.STATUS_DELETED);
        }

        @Test
        @DisplayName("the task's reviewer agent may trash it")
        void reviewerCanTrash() {
            UUID reviewer = UUID.randomUUID();
            AgentTaskEntity t = task(AgentTaskEntity.STATUS_IN_REVIEW);
            t.setCreatedByUserId(null);
            t.setReviewerAgentId(reviewer);
            when(taskRepository.findByIdAndTenantId(taskId, TENANT)).thenReturn(Optional.of(t));

            AgentTaskEntity result = service.softDeleteTask(TENANT, taskId, reviewer, null);

            assertThat(result.getStatus()).isEqualTo(AgentTaskEntity.STATUS_DELETED);
        }

        @Test
        @DisplayName("is idempotent - an already-trashed task is returned unchanged with no save")
        void idempotent() {
            AgentTaskEntity t = task(AgentTaskEntity.STATUS_DELETED);
            t.setDeletedAt(Instant.parse("2026-06-01T00:00:00Z"));
            when(taskRepository.findByIdAndTenantId(taskId, TENANT)).thenReturn(Optional.of(t));

            AgentTaskEntity result = service.softDeleteTask(TENANT, taskId, null, TENANT);

            assertThat(result.getStatus()).isEqualTo(AgentTaskEntity.STATUS_DELETED);
            verify(taskRepository, never()).save(any());
            verify(self, never()).recordEvent(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("the assigned agent alone cannot trash a task (must use task_reject)")
        void assigneeCannotTrash() {
            UUID assignee = UUID.randomUUID();
            AgentTaskEntity t = task(AgentTaskEntity.STATUS_IN_PROGRESS);
            t.setCreatedByUserId(null);
            t.setAssignedToAgentId(assignee);
            when(taskRepository.findByIdAndTenantId(taskId, TENANT)).thenReturn(Optional.of(t));

            assertThatThrownBy(() -> service.softDeleteTask(TENANT, taskId, assignee, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("assigned agent cannot delete");
            verify(taskRepository, never()).save(any());
        }

        @Test
        @DisplayName("a non-stakeholder cannot trash a task")
        void strangerCannotTrash() {
            AgentTaskEntity t = task(AgentTaskEntity.STATUS_PENDING);
            when(taskRepository.findByIdAndTenantId(taskId, TENANT)).thenReturn(Optional.of(t));

            assertThatThrownBy(() -> service.softDeleteTask(TENANT, taskId, UUID.randomUUID(), null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("creator, reviewer, or tenant owner");
        }

        @Test
        @DisplayName("a missing task id is rejected with task-not-found")
        void notFound() {
            when(taskRepository.findByIdAndTenantId(taskId, TENANT)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.softDeleteTask(TENANT, taskId, null, TENANT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("task not found");
        }

        @org.junit.jupiter.params.ParameterizedTest
        @org.junit.jupiter.params.provider.ValueSource(strings = {"pending", "in_review", "completed", "cancelled"})
        @DisplayName("records the origin column in previous_status for every starting status, never force-unlocking")
        void previousStatusForEveryOrigin(String origin) {
            AgentTaskEntity t = task(origin);
            when(taskRepository.findByIdAndTenantId(taskId, TENANT)).thenReturn(Optional.of(t));

            AgentTaskEntity result = service.softDeleteTask(TENANT, taskId, null, TENANT);

            assertThat(result.getStatus()).isEqualTo(AgentTaskEntity.STATUS_DELETED);
            assertThat(result.getPreviousStatus()).isEqualTo(origin);
            verify(taskRepository, never()).forceUnlockAssigneeExecution(any());
            verify(taskRepository, never()).forceUnlockReviewerExecution(any());
        }
    }

    // ── restore ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("restoreTask")
    class Restore {

        @Test
        @DisplayName("returns a trashed task to its previous column and clears soft-delete state")
        void restoresToPrevious() {
            AgentTaskEntity t = task(AgentTaskEntity.STATUS_DELETED);
            t.setPreviousStatus(AgentTaskEntity.STATUS_COMPLETED);
            t.setDeletedAt(Instant.parse("2026-06-01T00:00:00Z"));
            when(taskRepository.findByIdAndTenantId(taskId, TENANT)).thenReturn(Optional.of(t));

            AgentTaskEntity result = service.restoreTask(TENANT, taskId, null, TENANT);

            assertThat(result.getStatus()).isEqualTo(AgentTaskEntity.STATUS_COMPLETED);
            assertThat(result.getDeletedAt()).isNull();
            assertThat(result.getPreviousStatus()).isNull();
            verify(self).recordEvent(eq(taskId), eq(AgentTaskEventEntity.EVT_RESTORED), any(), eq(TENANT), any(), any());
        }

        @Test
        @DisplayName("falls back to pending when previous_status is absent")
        void fallsBackToPending() {
            AgentTaskEntity t = task(AgentTaskEntity.STATUS_DELETED);
            t.setPreviousStatus(null);
            when(taskRepository.findByIdAndTenantId(taskId, TENANT)).thenReturn(Optional.of(t));

            AgentTaskEntity result = service.restoreTask(TENANT, taskId, null, TENANT);

            assertThat(result.getStatus()).isEqualTo(AgentTaskEntity.STATUS_PENDING);
        }

        @Test
        @DisplayName("does not restore to an assignee-less in_progress - degrades to pending")
        void degradesAssigneelessActive() {
            AgentTaskEntity t = task(AgentTaskEntity.STATUS_DELETED);
            t.setPreviousStatus(AgentTaskEntity.STATUS_IN_PROGRESS); // but no assignee set
            when(taskRepository.findByIdAndTenantId(taskId, TENANT)).thenReturn(Optional.of(t));

            AgentTaskEntity result = service.restoreTask(TENANT, taskId, null, TENANT);

            assertThat(result.getStatus()).isEqualTo(AgentTaskEntity.STATUS_PENDING);
        }

        @Test
        @DisplayName("rejects restoring a task that is not in the trash")
        void rejectsNonDeleted() {
            AgentTaskEntity t = task(AgentTaskEntity.STATUS_COMPLETED);
            when(taskRepository.findByIdAndTenantId(taskId, TENANT)).thenReturn(Optional.of(t));

            assertThatThrownBy(() -> service.restoreTask(TENANT, taskId, null, TENANT))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("only a task in the Deleted column");
            verify(taskRepository, never()).save(any());
        }

        @Test
        @DisplayName("restores to in_progress (NOT degraded) when an assignee is still set")
        void keepsInProgressWhenAssigneePresent() {
            AgentTaskEntity t = task(AgentTaskEntity.STATUS_DELETED);
            t.setPreviousStatus(AgentTaskEntity.STATUS_IN_PROGRESS);
            t.setAssignedToAgentId(UUID.randomUUID());
            when(taskRepository.findByIdAndTenantId(taskId, TENANT)).thenReturn(Optional.of(t));

            AgentTaskEntity result = service.restoreTask(TENANT, taskId, null, TENANT);

            assertThat(result.getStatus()).isEqualTo(AgentTaskEntity.STATUS_IN_PROGRESS);
        }

        @org.junit.jupiter.params.ParameterizedTest
        @org.junit.jupiter.params.provider.ValueSource(strings = {"garbage", "deleted"})
        @DisplayName("falls back to pending when previous_status holds an invalid value (incl. a stray 'deleted')")
        void invalidPreviousStatusFallsBackToPending(String corrupt) {
            AgentTaskEntity t = task(AgentTaskEntity.STATUS_DELETED);
            t.setPreviousStatus(corrupt); // neither is in VALID_STATUSES
            when(taskRepository.findByIdAndTenantId(taskId, TENANT)).thenReturn(Optional.of(t));

            AgentTaskEntity result = service.restoreTask(TENANT, taskId, null, TENANT);

            assertThat(result.getStatus()).isEqualTo(AgentTaskEntity.STATUS_PENDING);
        }

        @Test
        @DisplayName("a non-stakeholder cannot restore a trashed task (auth mirrors soft-delete)")
        void strangerCannotRestore() {
            AgentTaskEntity t = task(AgentTaskEntity.STATUS_DELETED);
            t.setPreviousStatus(AgentTaskEntity.STATUS_PENDING);
            when(taskRepository.findByIdAndTenantId(taskId, TENANT)).thenReturn(Optional.of(t));

            assertThatThrownBy(() -> service.restoreTask(TENANT, taskId, UUID.randomUUID(), null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("creator, reviewer, or tenant owner");
            verify(taskRepository, never()).save(any());
        }

        @Test
        @DisplayName("the task's reviewer agent may restore it")
        void reviewerCanRestore() {
            UUID reviewer = UUID.randomUUID();
            AgentTaskEntity t = task(AgentTaskEntity.STATUS_DELETED);
            t.setCreatedByUserId(null);
            t.setReviewerAgentId(reviewer);
            t.setPreviousStatus(AgentTaskEntity.STATUS_COMPLETED);
            when(taskRepository.findByIdAndTenantId(taskId, TENANT)).thenReturn(Optional.of(t));

            AgentTaskEntity result = service.restoreTask(TENANT, taskId, reviewer, null);

            assertThat(result.getStatus()).isEqualTo(AgentTaskEntity.STATUS_COMPLETED);
        }

        @Test
        @DisplayName("the assigned agent alone cannot restore a task")
        void assigneeCannotRestore() {
            UUID assignee = UUID.randomUUID();
            AgentTaskEntity t = task(AgentTaskEntity.STATUS_DELETED);
            t.setCreatedByUserId(null);
            t.setPreviousStatus(AgentTaskEntity.STATUS_PENDING);
            t.setAssignedToAgentId(assignee);
            when(taskRepository.findByIdAndTenantId(taskId, TENANT)).thenReturn(Optional.of(t));

            assertThatThrownBy(() -> service.restoreTask(TENANT, taskId, assignee, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("assigned agent cannot");
            verify(taskRepository, never()).save(any());
        }
    }

    // ── purge (manual + retention) ────────────────────────────────────

    @Nested
    @DisplayName("purge")
    class Purge {

        @Test
        @DisplayName("purgeDeletedTask hard-removes a trashed row (events, notes, executions) - owner only")
        void purgesTrashedRow() {
            AgentTaskEntity t = task(AgentTaskEntity.STATUS_DELETED);
            when(taskRepository.findByIdAndTenantId(taskId, TENANT)).thenReturn(Optional.of(t));

            int purged = service.purgeDeletedTask(TENANT, taskId, TENANT);

            assertThat(purged).isEqualTo(1);
            verify(executionRepository).unlinkTaskId(taskId);
            verify(eventRepository).deleteByTaskId(taskId);
            verify(noteRepository).deleteByTaskId(taskId);
            verify(taskRepository).delete(t);
        }

        @Test
        @DisplayName("purgeDeletedTask refuses a non-owner")
        void purgeOwnerOnly() {
            assertThatThrownBy(() -> service.purgeDeletedTask(TENANT, taskId, "intruder"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("only the tenant owner");
            verify(taskRepository, never()).delete(any());
        }

        @Test
        @DisplayName("purgeDeletedTask refuses a task that is not in the trash")
        void purgeOnlyTrashed() {
            AgentTaskEntity t = task(AgentTaskEntity.STATUS_CANCELLED);
            when(taskRepository.findByIdAndTenantId(taskId, TENANT)).thenReturn(Optional.of(t));

            assertThatThrownBy(() -> service.purgeDeletedTask(TENANT, taskId, TENANT))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("only a task in the Deleted column");
            verify(taskRepository, never()).delete(any());
        }

        @Test
        @DisplayName("purgeExpiredDeletedTaskById purges a still-trashed row, skips one restored in the meantime")
        void perIdRecheck() {
            AgentTaskEntity trashed = task(AgentTaskEntity.STATUS_DELETED);
            when(taskRepository.findById(taskId)).thenReturn(Optional.of(trashed));
            assertThat(service.purgeExpiredDeletedTaskById(taskId)).isTrue();
            // Full retention-path cleanup (shared purgeRow): executions unlinked, events + notes + row removed.
            verify(executionRepository).unlinkTaskId(taskId);
            verify(eventRepository).deleteByTaskId(taskId);
            verify(noteRepository).deleteByTaskId(taskId);
            verify(taskRepository).delete(trashed);

            UUID restoredId = UUID.randomUUID();
            AgentTaskEntity restored = task(AgentTaskEntity.STATUS_PENDING);
            restored.setId(restoredId);
            when(taskRepository.findById(restoredId)).thenReturn(Optional.of(restored));
            assertThat(service.purgeExpiredDeletedTaskById(restoredId)).isFalse();
            verify(taskRepository, never()).delete(restored);
        }

        @Test
        @DisplayName("purgeExpiredDeletedTaskById returns false (no delete) when the row was already purged")
        void perIdRowAlreadyGone() {
            when(taskRepository.findById(taskId)).thenReturn(Optional.empty());
            assertThat(service.purgeExpiredDeletedTaskById(taskId)).isFalse();
            verify(taskRepository, never()).delete(any());
        }

        @Test
        @DisplayName("purgeExpiredDeletedTasks keeps sweeping when one id throws - counts only the survivors")
        void sweepContinuesOnError() {
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            when(taskRepository.findExpiredDeletedIds(any(Instant.class), any(Pageable.class)))
                    .thenReturn(List.of(a, b));
            when(self.purgeExpiredDeletedTaskById(a)).thenThrow(new RuntimeException("row blew up"));
            when(self.purgeExpiredDeletedTaskById(b)).thenReturn(true);

            int purged = service.purgeExpiredDeletedTasks(30, 200);

            assertThat(purged).isEqualTo(1); // a threw, b succeeded - sweep did not abort
        }

        @Test
        @DisplayName("purgeDeletedTask rejects a missing task id")
        void purgeNotFound() {
            when(taskRepository.findByIdAndTenantId(taskId, TENANT)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.purgeDeletedTask(TENANT, taskId, TENANT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("task not found");
        }

        @Test
        @DisplayName("purgeExpiredDeletedTasks sweeps ids older than (now − retentionDays) and counts successes")
        void sweepCounts() {
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            Instant before = Instant.now();
            ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
            when(taskRepository.findExpiredDeletedIds(cutoff.capture(), any(Pageable.class)))
                    .thenReturn(List.of(a, b));
            when(self.purgeExpiredDeletedTaskById(a)).thenReturn(true);
            when(self.purgeExpiredDeletedTaskById(b)).thenReturn(false); // restored between scan and purge

            int purged = service.purgeExpiredDeletedTasks(30, 200);

            assertThat(purged).isEqualTo(1);
            // Cutoff is ~30 days before now (allow a small window around the call).
            assertThat(cutoff.getValue())
                    .isAfter(before.minus(Duration.ofDays(30)).minus(Duration.ofMinutes(1)))
                    .isBefore(before.minus(Duration.ofDays(30)).plus(Duration.ofMinutes(1)));
        }
    }

    // ── updateTask guards ─────────────────────────────────────────────

    @Nested
    @DisplayName("updateTask soft-delete guards")
    class UpdateGuards {

        @Test
        @DisplayName("refuses a direct status='deleted' transition (must use the delete action)")
        void refusesDirectDeletedStatus() {
            AgentTaskEntity t = task(AgentTaskEntity.STATUS_PENDING);
            when(taskRepository.findByIdAndTenantId(taskId, TENANT)).thenReturn(Optional.of(t));
            UpdateTaskRequest req = new UpdateTaskRequest(null, null, null, null, null, null, null,
                    AgentTaskEntity.STATUS_DELETED);

            assertThatThrownBy(() -> service.updateTask(TENANT, taskId, null, TENANT, req))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot set status to 'deleted' directly");
            verify(taskRepository, never()).save(any());
        }

        @Test
        @DisplayName("dragging a card out of the trash to a column clears deleted_at + previous_status")
        void clearsSoftDeleteOnDragOut() {
            AgentTaskEntity t = task(AgentTaskEntity.STATUS_DELETED);
            t.setPreviousStatus(AgentTaskEntity.STATUS_COMPLETED);
            t.setDeletedAt(Instant.parse("2026-06-01T00:00:00Z"));
            when(taskRepository.findByIdAndTenantId(taskId, TENANT)).thenReturn(Optional.of(t));
            UpdateTaskRequest req = new UpdateTaskRequest(null, null, null, null, null, null, null,
                    AgentTaskEntity.STATUS_PENDING);

            AgentTaskEntity result = service.updateTask(TENANT, taskId, null, TENANT, req);

            assertThat(result.getStatus()).isEqualTo(AgentTaskEntity.STATUS_PENDING);
            assertThat(result.getDeletedAt()).isNull();
            assertThat(result.getPreviousStatus()).isNull();
        }

        @Test
        @DisplayName("dragging out of the trash to a NON-pending column (in_review w/ assignee) also clears the soft-delete state")
        void clearsSoftDeleteOnDragOutToNonPending() {
            AgentTaskEntity t = task(AgentTaskEntity.STATUS_DELETED);
            t.setPreviousStatus(AgentTaskEntity.STATUS_IN_PROGRESS);
            t.setDeletedAt(Instant.parse("2026-06-01T00:00:00Z"));
            t.setAssignedToAgentId(UUID.randomUUID()); // in_review requires an assignee
            when(taskRepository.findByIdAndTenantId(taskId, TENANT)).thenReturn(Optional.of(t));
            UpdateTaskRequest req = new UpdateTaskRequest(null, null, null, null, null, null, null,
                    AgentTaskEntity.STATUS_IN_REVIEW);

            AgentTaskEntity result = service.updateTask(TENANT, taskId, null, TENANT, req);

            assertThat(result.getStatus()).isEqualTo(AgentTaskEntity.STATUS_IN_REVIEW);
            assertThat(result.getDeletedAt()).isNull();
            assertThat(result.getPreviousStatus()).isNull();
        }

        @Test
        @DisplayName("a status change on a NON-trashed task never clears deleted_at (clear is gated on leaving 'deleted')")
        void liveTaskUpdateLeavesDeletedFieldsAlone() {
            // Defensive odd state: a non-deleted row carrying a stray deleted_at must NOT be
            // wiped by a normal transition - proves the clear is gated on oldStatus=='deleted'.
            AgentTaskEntity t = task(AgentTaskEntity.STATUS_COMPLETED);
            Instant stray = Instant.parse("2026-06-01T00:00:00Z");
            t.setDeletedAt(stray);
            t.setPreviousStatus("x");
            when(taskRepository.findByIdAndTenantId(taskId, TENANT)).thenReturn(Optional.of(t));
            UpdateTaskRequest req = new UpdateTaskRequest(null, null, null, null, null, null, null,
                    AgentTaskEntity.STATUS_PENDING);

            AgentTaskEntity result = service.updateTask(TENANT, taskId, null, TENANT, req);

            assertThat(result.getStatus()).isEqualTo(AgentTaskEntity.STATUS_PENDING);
            assertThat(result.getDeletedAt()).isEqualTo(stray);
            assertThat(result.getPreviousStatus()).isEqualTo("x");
        }
    }
}
