package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.AgentTaskEntity;
import com.apimarketplace.agent.dto.UpdateTaskRequest;
import com.apimarketplace.agent.repository.AgentExecutionRepository;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.repository.AgentTaskEventRepository;
import com.apimarketplace.agent.repository.AgentTaskNoteRepository;
import com.apimarketplace.agent.repository.AgentTaskRepository;
import com.apimarketplace.common.classification.DataSensitivity;
import com.apimarketplace.conversation.client.ConversationClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CASA LC-066 (audit A #7): a task_update from an execution holding Gmail / Drive content ratchets
 * the task to RESTRICTED only when it writes prompt text (title or instructions), and only once
 * the authorization check and every validation passed, inside the update itself. Before the fix
 * the module ratcheted up front: a refused update, an invalid one, or a status-only drag still
 * tagged the task for good.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentTaskService.updateTask - LC-066 restricted ratchet")
class AgentTaskServiceUpdateRestrictedRatchetTest {

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
    private final UUID creatorAgent = UUID.randomUUID();
    private AgentTaskEntity task;

    @BeforeEach
    void setUp() {
        service = new AgentTaskService(taskRepository, noteRepository, eventRepository,
                agentRepository, executionRepository, taskBoardPublisher, conversationClient, self);
        task = new AgentTaskEntity();
        task.setId(taskId);
        task.setTenantId(TENANT);
        task.setStatus(AgentTaskEntity.STATUS_PENDING);
        task.setCreatedByAgentId(creatorAgent);
        when(taskRepository.findByIdAndTenantId(taskId, TENANT)).thenReturn(Optional.of(task));
    }

    private AgentTaskEntity update(UpdateTaskRequest req, DataSensitivity caller) {
        when(taskRepository.save(task)).thenReturn(task);
        return service.updateTask(TENANT, taskId, creatorAgent, null, req, caller);
    }

    @Test
    @DisplayName("a restricted caller writing the instructions tags the saved task RESTRICTED")
    void restrictedInstructionsUpdateTags() {
        AgentTaskEntity saved = update(new UpdateTaskRequest(null, null, "mail text", null),
                DataSensitivity.RESTRICTED);

        assertThat(saved.holdsRestrictedData()).isTrue();
    }

    @Test
    @DisplayName("a restricted caller writing the title tags the saved task RESTRICTED")
    void restrictedTitleUpdateTags() {
        AgentTaskEntity saved = update(new UpdateTaskRequest(null, "mail subject", null, null),
                DataSensitivity.RESTRICTED);

        assertThat(saved.holdsRestrictedData()).isTrue();
    }

    @Test
    @DisplayName("a restricted caller changing only the status does not tag the task")
    void restrictedStatusOnlyUpdateDoesNotTag() {
        AgentTaskEntity saved = update(new UpdateTaskRequest(null, null, null, null,
                null, null, null, AgentTaskEntity.STATUS_CANCELLED), DataSensitivity.RESTRICTED);

        assertThat(saved.getStatus()).isEqualTo(AgentTaskEntity.STATUS_CANCELLED);
        assertThat(saved.holdsRestrictedData()).isFalse();
    }

    @Test
    @DisplayName("an ordinary caller writing the instructions does not tag the task")
    void normalInstructionsUpdateDoesNotTag() {
        AgentTaskEntity saved = update(new UpdateTaskRequest(null, null, "plain text", null),
                DataSensitivity.NORMAL);

        assertThat(saved.holdsRestrictedData()).isFalse();
    }

    @Test
    @DisplayName("the legacy 5-argument update (REST board edits) never tags")
    void legacyOverloadDoesNotTag() {
        when(taskRepository.save(task)).thenReturn(task);

        AgentTaskEntity saved = service.updateTask(TENANT, taskId, creatorAgent, null,
                new UpdateTaskRequest(null, null, "plain text", null));

        assertThat(saved.holdsRestrictedData()).isFalse();
    }

    @Test
    @DisplayName("a refused update (caller is not the creator) does not tag the task")
    void refusedUpdateDoesNotTag() {
        assertThatThrownBy(() -> service.updateTask(TENANT, taskId, UUID.randomUUID(), null,
                new UpdateTaskRequest(null, null, "mail text", null), DataSensitivity.RESTRICTED))
                .isInstanceOf(IllegalStateException.class);

        assertThat(task.holdsRestrictedData()).isFalse();
        verify(taskRepository, never()).save(any());
    }

    @Test
    @DisplayName("an update failing a validation that runs after the text edit does not tag the task")
    void invalidUpdateDoesNotTag() {
        assertThatThrownBy(() -> service.updateTask(TENANT, taskId, creatorAgent, null,
                new UpdateTaskRequest(null, null, "mail text", null, null, null, null, null, 0),
                DataSensitivity.RESTRICTED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max_review_attempts");

        assertThat(task.holdsRestrictedData()).isFalse();
        verify(taskRepository, never()).save(any());
    }
}
