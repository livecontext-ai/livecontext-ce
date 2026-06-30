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
 * Authorization guard on {@link AgentTaskService#updateTask}: only the creator
 * (agent or human) or the tenant owner may update a task. Sibling of the
 * cancel/hard-delete guards - the rejection branch was the one piece of the
 * delegation authorization surface still untested.
 *
 * <p>Pins the subtle {@code isTenantOwner = callingAgentId == null && …} clause
 * (line 1227): an AGENT caller cannot claim tenant ownership even by passing the
 * tenant string as its user id - only a human (no calling-agent) can.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentTaskService.updateTask - creator/owner-only authorization")
class AgentTaskServiceUpdateAuthorizationTest {

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

    @BeforeEach
    void setUp() {
        service = new AgentTaskService(taskRepository, noteRepository, eventRepository,
                agentRepository, executionRepository, taskBoardPublisher, conversationClient, self);
    }

    /** Task created by an agent, with no assignee (so the no-op update never triggers kickoff). */
    private AgentTaskEntity agentCreatedTask() {
        AgentTaskEntity t = new AgentTaskEntity();
        t.setId(taskId);
        t.setTenantId(TENANT);
        t.setStatus(AgentTaskEntity.STATUS_PENDING);
        t.setCreatedByAgentId(creatorAgent);
        return t;
    }

    private static UpdateTaskRequest noopRequest() {
        return new UpdateTaskRequest(null, null, null, null);
    }

    private void givenTask(AgentTaskEntity t) {
        when(taskRepository.findByIdAndTenantId(taskId, TENANT)).thenReturn(Optional.of(t));
    }

    @Test
    @DisplayName("an unrelated agent CANNOT update")
    void unrelatedAgentCannotUpdate() {
        givenTask(agentCreatedTask());

        assertThatThrownBy(() -> service.updateTask(TENANT, taskId, UUID.randomUUID(), null, noopRequest()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only the task creator or tenant owner may update");

        verify(taskRepository, never()).save(any());
    }

    @Test
    @DisplayName("an unrelated human CANNOT update another tenant's task")
    void unrelatedHumanCannotUpdate() {
        givenTask(agentCreatedTask());

        assertThatThrownBy(() -> service.updateTask(TENANT, taskId, null, "someone-else", noopRequest()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only the task creator or tenant owner may update");

        verify(taskRepository, never()).save(any());
    }

    @Test
    @DisplayName("an agent CANNOT claim tenant ownership by passing the tenant id as its user id")
    void agentCannotClaimTenantOwnershipViaUserId() {
        // callingAgentId is set AND callingUserId == tenantId: isTenantOwner is
        // false because of the `callingAgentId == null` clause, and the agent is
        // not the creator - so it must be rejected.
        givenTask(agentCreatedTask());

        assertThatThrownBy(() -> service.updateTask(TENANT, taskId, UUID.randomUUID(), TENANT, noopRequest()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only the task creator or tenant owner may update");

        verify(taskRepository, never()).save(any());
    }

    @Test
    @DisplayName("the creator agent CAN update")
    void creatorAgentCanUpdate() {
        AgentTaskEntity task = agentCreatedTask();
        givenTask(task);
        when(taskRepository.save(task)).thenReturn(task);

        AgentTaskEntity result = service.updateTask(TENANT, taskId, creatorAgent, null, noopRequest());

        assertThat(result).isSameAs(task);
        verify(taskRepository).save(task);
    }

    @Test
    @DisplayName("the tenant owner (human, no calling agent) CAN update")
    void tenantOwnerCanUpdate() {
        AgentTaskEntity task = agentCreatedTask();
        givenTask(task);
        when(taskRepository.save(task)).thenReturn(task);

        AgentTaskEntity result = service.updateTask(TENANT, taskId, null, TENANT, noopRequest());

        assertThat(result).isSameAs(task);
        verify(taskRepository).save(task);
    }
}
