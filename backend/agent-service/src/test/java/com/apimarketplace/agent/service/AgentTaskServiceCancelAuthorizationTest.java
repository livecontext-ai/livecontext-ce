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

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Authorization guards on {@link AgentTaskService#cancelTask}. This is the one
 * delegation transition that distinguishes assignee from creator/reviewer: the
 * ASSIGNED agent must NOT be able to cancel (it has to report failure via
 * task_reject), and only the creator, the reviewer, the human creator, or the
 * tenant owner may cancel. A regression here is a privilege escalation across
 * the agent hierarchy (an assigned sub-agent cascade-cancelling a parent's task),
 * and the guards had no behavioural test - only mocked forwarding at the module
 * level and a path that deliberately bypasses them (ConversationStopCascadeService).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentTaskService.cancelTask - per-actor authorization")
class AgentTaskServiceCancelAuthorizationTest {

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
    private final UUID assigneeAgent = UUID.randomUUID();
    private final UUID reviewerAgent = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new AgentTaskService(taskRepository, noteRepository, eventRepository,
                agentRepository, executionRepository, taskBoardPublisher, conversationClient, self);
    }

    /** Agent-created task: created by one agent, assigned to a second, reviewed by a third. */
    private AgentTaskEntity agentTask() {
        AgentTaskEntity t = new AgentTaskEntity();
        t.setId(taskId);
        t.setTenantId(TENANT);
        t.setStatus(AgentTaskEntity.STATUS_IN_PROGRESS);
        t.setCreatedByAgentId(creatorAgent);
        t.setAssignedToAgentId(assigneeAgent);
        t.setReviewerAgentId(reviewerAgent);
        return t;
    }

    private void givenTask(AgentTaskEntity t) {
        when(taskRepository.findByIdAndTenantId(taskId, TENANT)).thenReturn(Optional.of(t));
    }

    @Test
    @DisplayName("the assigned agent CANNOT cancel - must use task_reject instead")
    void assigneeCannotCancel() {
        givenTask(agentTask());

        assertThatThrownBy(() -> service.cancelTask(TENANT, taskId, assigneeAgent, null, "stop"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("assigned agent cannot cancel");

        verify(taskRepository, never()).cascadingCancel(any(), any(), any());
    }

    @Test
    @DisplayName("an unrelated agent CANNOT cancel - only the creator or reviewer may")
    void unrelatedAgentCannotCancel() {
        givenTask(agentTask());

        assertThatThrownBy(() -> service.cancelTask(TENANT, taskId, UUID.randomUUID(), null, "stop"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only the task creator or reviewer");

        verify(taskRepository, never()).cascadingCancel(any(), any(), any());
    }

    @Test
    @DisplayName("an unrelated human CANNOT cancel another tenant's task")
    void unrelatedHumanCannotCancel() {
        givenTask(agentTask());

        assertThatThrownBy(() -> service.cancelTask(TENANT, taskId, null, "someone-else", "stop"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only the task creator or reviewer");

        verify(taskRepository, never()).cascadingCancel(any(), any(), any());
    }

    @Test
    @DisplayName("the creator agent CAN cancel")
    void creatorAgentCanCancel() {
        givenTask(agentTask());
        when(taskRepository.cascadingCancel(taskId, TENANT, "stop")).thenReturn(1);

        int cancelled = service.cancelTask(TENANT, taskId, creatorAgent, null, "stop");

        assertThat(cancelled).isEqualTo(1);
        verify(taskRepository).cascadingCancel(taskId, TENANT, "stop");
    }

    @Test
    @DisplayName("the reviewer agent CAN cancel")
    void reviewerCanCancel() {
        givenTask(agentTask());
        when(taskRepository.cascadingCancel(taskId, TENANT, "stop")).thenReturn(1);

        int cancelled = service.cancelTask(TENANT, taskId, reviewerAgent, null, "stop");

        assertThat(cancelled).isEqualTo(1);
        verify(taskRepository).cascadingCancel(taskId, TENANT, "stop");
    }

    @Test
    @DisplayName("the human creator CAN cancel their own task")
    void humanCreatorCanCancel() {
        AgentTaskEntity t = agentTask();
        t.setCreatedByAgentId(null);
        t.setCreatedByUserId("human-1");
        givenTask(t);
        when(taskRepository.cascadingCancel(taskId, TENANT, "stop")).thenReturn(1);

        int cancelled = service.cancelTask(TENANT, taskId, null, "human-1", "stop");

        assertThat(cancelled).isEqualTo(1);
    }

    @Test
    @DisplayName("the tenant owner CAN cancel even when neither creator nor reviewer")
    void tenantOwnerCanCancel() {
        givenTask(agentTask());
        when(taskRepository.cascadingCancel(taskId, TENANT, "stop")).thenReturn(1);

        int cancelled = service.cancelTask(TENANT, taskId, null, TENANT, "stop");

        assertThat(cancelled).isEqualTo(1);
    }

    @Test
    @DisplayName("an agent that is BOTH assignee and creator CAN cancel - the assignee block exempts the creator")
    void assigneeWhoIsAlsoCreatorCanCancel() {
        AgentTaskEntity t = agentTask();
        t.setCreatedByAgentId(assigneeAgent); // same agent created it AND is assigned to it
        givenTask(t);
        when(taskRepository.cascadingCancel(taskId, TENANT, "stop")).thenReturn(1);

        int cancelled = service.cancelTask(TENANT, taskId, assigneeAgent, null, "stop");

        assertThat(cancelled).isEqualTo(1);
        verify(taskRepository).cascadingCancel(taskId, TENANT, "stop");
    }
}
