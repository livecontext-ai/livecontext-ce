package com.apimarketplace.agent.service.execution;

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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentActivityPublisher")
class AgentActivityPublisherTest {

    @Mock private EventBus eventBus;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("publishToolCallStarted includes task-scoped activity fields on the agent channel")
    void publishToolCallStartedIncludesTaskScopedActivityFields() throws Exception {
        String agentId = UUID.randomUUID().toString();
        String executionId = UUID.randomUUID().toString();
        String taskId = UUID.randomUUID().toString();

        new AgentActivityPublisher(eventBus, objectMapper)
                .publishToolCallStarted(agentId, executionId, "task_claim", "call-1", taskId);

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(eventBus).publish(org.mockito.Mockito.eq("ws:agent:activity:" + agentId), messageCaptor.capture());

        Map<String, Object> payload = objectMapper.readValue(
                messageCaptor.getValue(), new TypeReference<>() {});
        assertThat(payload)
                .containsEntry("event", "tool_call_started")
                .containsEntry("agentEntityId", agentId)
                .containsEntry("executionId", executionId)
                .containsEntry("taskId", taskId)
                .containsEntry("toolName", "task_claim")
                .containsEntry("toolCallId", "call-1")
                .containsKey("timestamp");
    }

    @Test
    @DisplayName("publishExecutionCompleted includes metrics and omits taskId when no task context exists")
    void publishExecutionCompletedIncludesMetricsAndOmitsTaskIdWithoutContext() throws Exception {
        String agentId = UUID.randomUUID().toString();
        String executionId = UUID.randomUUID().toString();

        new AgentActivityPublisher(eventBus, objectMapper)
                .publishExecutionCompleted(agentId, executionId, "success", 1234, 7, 456L, null);

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(eventBus).publish(org.mockito.Mockito.eq("ws:agent:activity:" + agentId), messageCaptor.capture());

        Map<String, Object> payload = objectMapper.readValue(
                messageCaptor.getValue(), new TypeReference<>() {});
        assertThat(payload)
                .containsEntry("event", "execution_completed")
                .containsEntry("agentEntityId", agentId)
                .containsEntry("executionId", executionId)
                .containsEntry("status", "success")
                .containsEntry("totalTokens", 1234)
                .containsEntry("totalToolCalls", 7)
                .containsEntry("durationMs", 456);
        assertThat(payload).doesNotContainKey("taskId");
    }

    @Test
    @DisplayName("null agentEntityId events are ignored")
    void nullAgentEntityIdEventsAreIgnored() {
        new AgentActivityPublisher(eventBus, objectMapper)
                .publishExecutionStarted(null, "exec-1", "gpt-5", "schedule", "task-1");

        verify(eventBus, never()).publish(org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString());
    }
}
