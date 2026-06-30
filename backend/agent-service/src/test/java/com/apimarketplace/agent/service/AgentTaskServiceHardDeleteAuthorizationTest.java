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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Guards on {@link AgentTaskService#hardDeleteTask} - the only path that
 * PERMANENTLY destroys a task plus its events and notes and recurses into
 * children. Three protections, none of which had a behavioural test:
 * <ul>
 *   <li>tenant-owner-only (the sole authorization on permanent deletion);</li>
 *   <li>terminal-status-only (an in-flight task cannot be erased);</li>
 *   <li>no non-terminal child (don't orphan a running subtree).</li>
 * </ul>
 * A weakened owner check lets a non-owner irreversibly delete data; a slipped
 * terminal/child guard erases an in-flight subtree.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentTaskService.hardDeleteTask - owner-only + terminal + child guards")
class AgentTaskServiceHardDeleteAuthorizationTest {

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
    }

    /** No organization tag → children are looked up via the tenant-scoped query. */
    private AgentTaskEntity taskWithStatus(UUID id, String status) {
        AgentTaskEntity t = new AgentTaskEntity();
        t.setId(id);
        t.setTenantId(TENANT);
        t.setStatus(status);
        return t;
    }

    @Test
    @DisplayName("a non-owner cannot hard-delete - rejected before any lookup")
    void nonOwnerCannotHardDelete() {
        assertThatThrownBy(() -> service.hardDeleteTask(TENANT, taskId, "not-the-owner"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only the tenant owner");

        // Owner check is first: the task is never even fetched, nothing is deleted.
        verifyNoInteractions(taskRepository, eventRepository, noteRepository, executionRepository);
    }

    @Test
    @DisplayName("a null caller cannot hard-delete")
    void nullCallerCannotHardDelete() {
        assertThatThrownBy(() -> service.hardDeleteTask(TENANT, taskId, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only the tenant owner");

        verifyNoInteractions(taskRepository, eventRepository, noteRepository, executionRepository);
    }

    @Test
    @DisplayName("a non-terminal task cannot be hard-deleted")
    void nonTerminalTaskRejected() {
        when(taskRepository.findByIdAndTenantId(taskId, TENANT))
                .thenReturn(Optional.of(taskWithStatus(taskId, AgentTaskEntity.STATUS_IN_PROGRESS)));

        assertThatThrownBy(() -> service.hardDeleteTask(TENANT, taskId, TENANT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only terminal tasks");

        verify(taskRepository, never()).delete(any());
    }

    @Test
    @DisplayName("a terminal parent with a non-terminal child is NOT deleted")
    void nonTerminalChildBlocksDelete() {
        AgentTaskEntity parent = taskWithStatus(taskId, AgentTaskEntity.STATUS_COMPLETED);
        AgentTaskEntity child = taskWithStatus(UUID.randomUUID(), AgentTaskEntity.STATUS_IN_PROGRESS);
        when(taskRepository.findByIdAndTenantId(taskId, TENANT)).thenReturn(Optional.of(parent));
        when(taskRepository.findByTenantIdAndParentTaskIdOrderByCreatedAtAsc(TENANT, taskId))
                .thenReturn(List.of(child));

        assertThatThrownBy(() -> service.hardDeleteTask(TENANT, taskId, TENANT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("child task");

        verify(taskRepository, never()).delete(any());
    }

    @Test
    @DisplayName("the owner hard-deletes a terminal leaf - events, notes, and the task row are removed")
    void ownerDeletesTerminalLeaf() {
        AgentTaskEntity task = taskWithStatus(taskId, AgentTaskEntity.STATUS_COMPLETED);
        when(taskRepository.findByIdAndTenantId(taskId, TENANT)).thenReturn(Optional.of(task));
        when(taskRepository.findByTenantIdAndParentTaskIdOrderByCreatedAtAsc(TENANT, taskId))
                .thenReturn(List.of());

        int deleted = service.hardDeleteTask(TENANT, taskId, TENANT);

        assertThat(deleted).isEqualTo(1);
        verify(executionRepository).unlinkTaskId(taskId);
        verify(eventRepository).deleteByTaskId(taskId);
        verify(noteRepository).deleteByTaskId(taskId);
        verify(taskRepository).delete(task);
    }

    @Test
    @DisplayName("recursively deletes terminal children before the parent")
    void recursivelyDeletesTerminalChildren() {
        UUID childId = UUID.randomUUID();
        AgentTaskEntity parent = taskWithStatus(taskId, AgentTaskEntity.STATUS_COMPLETED);
        AgentTaskEntity child = taskWithStatus(childId, AgentTaskEntity.STATUS_CANCELLED);
        when(taskRepository.findByIdAndTenantId(taskId, TENANT)).thenReturn(Optional.of(parent));
        when(taskRepository.findByIdAndTenantId(childId, TENANT)).thenReturn(Optional.of(child));
        when(taskRepository.findByTenantIdAndParentTaskIdOrderByCreatedAtAsc(TENANT, taskId))
                .thenReturn(List.of(child));
        when(taskRepository.findByTenantIdAndParentTaskIdOrderByCreatedAtAsc(TENANT, childId))
                .thenReturn(List.of());

        int deleted = service.hardDeleteTask(TENANT, taskId, TENANT);

        assertThat(deleted).isEqualTo(2);
        verify(taskRepository).delete(parent);
        verify(taskRepository).delete(child);
    }
}
