package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.AgentTaskEntity;
import com.apimarketplace.common.event.EventBus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("TaskBoardPublisher")
class TaskBoardPublisherTest {

    private static final String TENANT_ID = "tenant-1";
    private static final String ORG_ID = "org-1";

    @Mock private EventBus eventBus;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("publishTaskUpdated sends task payload to the tenant board channel with organizationId")
    void publishTaskUpdatedSendsTenantBoardPayloadWithOrganizationId() throws Exception {
        AgentTaskEntity task = task();

        new TaskBoardPublisher(eventBus, objectMapper).publishTaskUpdated(TENANT_ID, task);

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(eventBus).publish(org.mockito.Mockito.eq("ws:task:board:" + TENANT_ID), messageCaptor.capture());

        Map<String, Object> payload = objectMapper.readValue(
                messageCaptor.getValue(), new TypeReference<>() {});
        assertThat(payload)
                .containsEntry("event", "task_updated")
                .containsEntry("tenantId", TENANT_ID)
                .containsEntry("organizationId", ORG_ID)
                .containsKey("timestamp");

        @SuppressWarnings("unchecked")
        Map<String, Object> taskPayload = (Map<String, Object>) payload.get("task");
        assertThat(taskPayload)
                .containsEntry("id", task.getId().toString())
                .containsEntry("status", AgentTaskEntity.STATUS_IN_PROGRESS)
                .containsEntry("assignedToAgentId", task.getAssignedToAgentId().toString());
    }

    @Test
    @DisplayName("publishTaskDeleted sends taskId and keeps organizationId nullable")
    void publishTaskDeletedSendsTaskIdWithNullableOrganizationId() throws Exception {
        UUID taskId = UUID.randomUUID();

        new TaskBoardPublisher(eventBus, objectMapper).publishTaskDeleted(TENANT_ID, taskId.toString());

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(eventBus).publish(org.mockito.Mockito.eq("ws:task:board:" + TENANT_ID), messageCaptor.capture());

        Map<String, Object> payload = objectMapper.readValue(
                messageCaptor.getValue(), new TypeReference<>() {});
        assertThat(payload)
                .containsEntry("event", "task_deleted")
                .containsEntry("tenantId", TENANT_ID)
                .containsEntry("taskId", taskId.toString())
                .containsKey("organizationId");
        assertThat(payload.get("organizationId")).isNull();
    }

    @Test
    @DisplayName("publish failures are non-critical and do not bubble to callers")
    void publishFailuresDoNotBubbleToCallers() {
        doThrow(new IllegalStateException("redis unavailable")).when(eventBus).publish(anyString(), anyString());

        assertThatNoException().isThrownBy(() ->
                new TaskBoardPublisher(eventBus, objectMapper).publishTaskUpdated(TENANT_ID, task()));
    }

    private static AgentTaskEntity task() {
        AgentTaskEntity task = new AgentTaskEntity();
        task.setId(UUID.randomUUID());
        task.setTenantId(TENANT_ID);
        task.setOrganizationId(ORG_ID);
        task.setCreatedByUserId("creator");
        task.setAssignedToAgentId(UUID.randomUUID());
        task.setTitle("Investigate issue");
        task.setInstructions("Check the failed execution");
        task.setPriority(AgentTaskEntity.PRIORITY_HIGH);
        task.setStatus(AgentTaskEntity.STATUS_IN_PROGRESS);
        task.setDepth(0);
        return task;
    }
}
