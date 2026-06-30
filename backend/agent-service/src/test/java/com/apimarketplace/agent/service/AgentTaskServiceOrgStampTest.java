package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.AgentTaskEntity;
import com.apimarketplace.agent.domain.AgentTaskEventEntity;
import com.apimarketplace.agent.domain.AgentTaskNoteEntity;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
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
 * Regression coverage for V281 org_id stamping on agent_task_notes / agent_task_events.
 *
 * <p>Pre-V281, subtables carried no organization_id - defense was purely at the controller
 * layer (parent-task scope gate). Post-V281 the column is NOT NULL and {@code AgentTaskService}
 * must stamp it from the parent task on every insert. This test pins:
 *   - note insert stamps parent task's organization_id
 *   - event insert fetches parent task and stamps its organization_id
 *   - event insert with a missing parent throws (NOT NULL invariant cannot be silently bypassed)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentTaskService - V281 org_id stamping on notes + events")
class AgentTaskServiceOrgStampTest {

    private static final String ORG = "org-A";
    private static final UUID TASK_ID = UUID.randomUUID();

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
        lenient().when(noteRepository.save(any())).thenAnswer(inv -> {
            AgentTaskNoteEntity n = inv.getArgument(0);
            if (n.getId() == null) n.setId(UUID.randomUUID());
            return n;
        });
        lenient().when(eventRepository.save(any())).thenAnswer(inv -> {
            AgentTaskEventEntity e = inv.getArgument(0);
            if (e.getId() == null) e.setId(1L);
            return e;
        });
    }

    private AgentTaskEntity taskInOrg(UUID id, String orgId) {
        AgentTaskEntity t = new AgentTaskEntity();
        t.setId(id);
        t.setTenantId("tenant-1");
        t.setOrganizationId(orgId);
        t.setTitle("T");
        return t;
    }

    @Test
    @DisplayName("doRecordEvent stamps organization_id from parent task")
    void doRecordEventStampsOrgFromParent() {
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(taskInOrg(TASK_ID, ORG)));

        service.doRecordEvent(TASK_ID, AgentTaskEventEntity.EVT_STARTED,
                UUID.randomUUID(), null, null, Map.of());

        ArgumentCaptor<AgentTaskEventEntity> captor = ArgumentCaptor.forClass(AgentTaskEventEntity.class);
        verify(eventRepository).save(captor.capture());
        AgentTaskEventEntity saved = captor.getValue();
        assertThat(saved.getOrganizationId())
                .as("event must inherit parent task org so V281 NOT NULL holds")
                .isEqualTo(ORG);
        assertThat(saved.getTaskId()).isEqualTo(TASK_ID);
    }

    @Test
    @DisplayName("doRecordEvent throws IllegalStateException when parent task is missing (NOT NULL cannot be silently bypassed)")
    void doRecordEventThrowsOnMissingParent() {
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.doRecordEvent(TASK_ID,
                AgentTaskEventEntity.EVT_COMPLETED, null, "user-x", null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("parent task missing");
        verify(eventRepository, never()).save(any());
    }

    @Test
    @DisplayName("doRecordEvent throws when parent task has null organization_id (post-V263 invariant)")
    void doRecordEventThrowsWhenParentOrgIsNull() {
        AgentTaskEntity orphanedOrg = taskInOrg(TASK_ID, null);
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(orphanedOrg));

        // Asserts the SPECIFIC message so a future refactor that collapses both branches into
        // a single Optional.map(...).orElseThrow doesn't regress the oncall debuggability.
        assertThatThrownBy(() -> service.doRecordEvent(TASK_ID,
                AgentTaskEventEntity.EVT_FAILED, null, null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("null organization_id")
                .hasMessageContaining("post-V263 invariant");
        verify(eventRepository, never()).save(any());
    }

    // ===== addNote stamping =====

    @Test
    @DisplayName("addNote stamps organization_id from parent task (V281 NOT NULL invariant)")
    void addNoteStampsOrgFromParent() {
        AgentTaskEntity parent = taskInOrg(TASK_ID, ORG);
        // addNote calls findTaskByIdScoped under the hood; emulate the lookup. The internal
        // lookup variants live behind multiple helper paths - use the most direct repo finder
        // that returns the task entity for the (taskId, tenantId) pair.
        when(taskRepository.findByIdAndTenantId(TASK_ID, "tenant-1"))
                .thenReturn(Optional.of(parent));
        // self.recordEvent (the NOTE_ADDED audit row) is called via the self-proxy; stub the
        // proxy so the recording side-effect is a no-op for this test.

        service.addNote("tenant-1", TASK_ID, UUID.randomUUID(), null, "first note");

        ArgumentCaptor<AgentTaskNoteEntity> captor = ArgumentCaptor.forClass(AgentTaskNoteEntity.class);
        verify(noteRepository).save(captor.capture());
        AgentTaskNoteEntity saved = captor.getValue();
        assertThat(saved.getOrganizationId())
                .as("note must inherit parent task org so V281 NOT NULL holds")
                .isEqualTo(ORG);
        assertThat(saved.getTaskId()).isEqualTo(TASK_ID);
        assertThat(saved.getContent()).isEqualTo("first note");
    }
}
