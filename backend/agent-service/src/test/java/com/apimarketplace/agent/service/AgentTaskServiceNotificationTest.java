package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.AgentEntity;
import com.apimarketplace.agent.domain.AgentTaskEntity;
import com.apimarketplace.agent.dto.CreateTaskRequest;
import com.apimarketplace.agent.repository.AgentExecutionRepository;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.repository.AgentTaskEventRepository;
import com.apimarketplace.agent.repository.AgentTaskNoteRepository;
import com.apimarketplace.agent.repository.AgentTaskRepository;
import com.apimarketplace.agent.service.TaskBoardPublisher;
import com.apimarketplace.common.web.TenantResolver;
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
 * P6 - verifies AgentTaskService.assignTask emits AGENT_TASK_ASSIGNED via
 * NotificationClient when the task has an assignee. Backlog tasks (no
 * assignee) do not emit.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentTaskService - P6 AGENT_TASK_ASSIGNED emission")
class AgentTaskServiceNotificationTest {

    private static final String ORG = "org-1";

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

    @BeforeEach
    void setUp() throws Exception {
        service = new AgentTaskService(taskRepository, noteRepository, eventRepository,
                agentRepository, executionRepository, taskBoardPublisher, conversationClient, self);
        Field f = AgentTaskService.class.getDeclaredField("notificationClient");
        f.setAccessible(true);
        f.set(service, notificationClient);

        // The save returns the same entity with id assigned.
        lenient().when(taskRepository.save(any(AgentTaskEntity.class))).thenAnswer(inv -> {
            AgentTaskEntity t = inv.getArgument(0);
            if (t.getId() == null) t.setId(UUID.randomUUID());
            return t;
        });
        lenient().when(notificationClient.emit(any())).thenReturn(true);
    }

    private AgentEntity assignee(UUID id, String tenantId) {
        return assignee(id, tenantId, null);
    }

    private AgentEntity assignee(UUID id, String tenantId, String organizationId) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setTenantId(tenantId);
        a.setOrganizationId(organizationId);
        a.setName("Sub-Agent");
        return a;
    }

    @Test
    @DisplayName("assignTask with assignee → emits AGENT_TASK_ASSIGNED")
    void assignedTaskEmits() {
        UUID assigneeId = UUID.randomUUID();
        UUID callerId = UUID.randomUUID();
        when(agentRepository.findByIdAndOrganizationIdStrict(assigneeId, ORG))
                .thenReturn(Optional.of(assignee(assigneeId, "tenant-1", ORG)));

        CreateTaskRequest req = new CreateTaskRequest(
                assigneeId, null, "Process orders", "Run the daily order job",
                "normal", null, null, null, null);

        TenantResolver.runWithOrgScope(ORG, () -> service.assignTask("tenant-1", callerId, null, req));

        ArgumentCaptor<NotificationEmitRequest> captor = ArgumentCaptor.forClass(NotificationEmitRequest.class);
        verify(notificationClient).emit(captor.capture());
        NotificationEmitRequest emit = captor.getValue();
        assertThat(emit.getCategory()).isEqualTo("AGENT_TASK_ASSIGNED");
        assertThat(emit.getSeverity()).isEqualTo("info");
        assertThat(emit.getSubjectType()).isEqualTo("AGENT_TASK");
        assertThat(emit.getSubjectId()).isNotNull();
        assertThat(emit.getSourceId()).isEqualTo(emit.getSubjectId().toString());
        assertThat(emit.getTenantId()).isEqualTo("tenant-1");
        assertThat(emit.getOrganizationId()).isEqualTo(ORG);
        assertThat(emit.getPayload()).containsEntry("status", "pending");
        assertThat(emit.getPayload()).containsEntry("subjectName", "Process orders");
        assertThat(emit.getPayload()).containsEntry("assigneeAgentId", assigneeId.toString());
        assertThat(emit.getPayload()).containsEntry("priority", AgentTaskEntity.PRIORITY_NORMAL);
    }

    @Test
    @DisplayName("assignTask with no assignee (backlog) → DOES NOT emit")
    void backlogTaskDoesNotEmit() {
        CreateTaskRequest req = new CreateTaskRequest(
                null, null, "Anyone job", "Run me",
                "normal", null, null, null, null);

        service.assignTask("tenant-1", UUID.randomUUID(), null, req);

        verify(notificationClient, never()).emit(any());
    }

    @Test
    @DisplayName("NotificationClient unwired → assignTask still works, no NPE")
    void unwiredClientIsNoOp() throws Exception {
        AgentTaskService bare = new AgentTaskService(taskRepository, noteRepository, eventRepository,
                agentRepository, executionRepository, taskBoardPublisher, conversationClient, self);
        // notificationClient stays null

        UUID assigneeId = UUID.randomUUID();
        when(agentRepository.findById(assigneeId)).thenReturn(Optional.of(assignee(assigneeId, "tenant-1")));

        CreateTaskRequest req = new CreateTaskRequest(
                assigneeId, null, "Job", "Run it",
                "normal", null, null, null, null);

        // Must not throw
        bare.assignTask("tenant-1", UUID.randomUUID(), null, req);
    }
}
