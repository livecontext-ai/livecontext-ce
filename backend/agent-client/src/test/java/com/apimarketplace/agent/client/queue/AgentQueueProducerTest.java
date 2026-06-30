package com.apimarketplace.agent.client.queue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentQueueProducer - Redis enqueue contract")
class AgentQueueProducerTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ListOperations<String, String> listOps;

    private ObjectMapper objectMapper;
    private AgentQueueProducer producer;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        producer = new AgentQueueProducer(redisTemplate, objectMapper);
    }

    @Test
    @DisplayName("LPUSH to agent:queue:{agentType} with worker-compatible task JSON")
    void enqueueLpushesToCorrectQueueWithWorkerSchema() throws Exception {
        when(redisTemplate.opsForList()).thenReturn(listOps);

        AgentExecutionRequestMessage msg = AgentExecutionRequestMessage.create(
            "corr-1", "run-1", "node-1", "tenant-1", "agent",
            "deepseek", "deepseek-chat",
            Map.of("foo", "bar"));

        producer.enqueue(msg);

        ArgumentCaptor<String> queueKey = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(listOps).leftPush(queueKey.capture(), jsonCaptor.capture());

        assertThat(queueKey.getValue()).isEqualTo("agent:queue:agent");

        Map<String, Object> task = objectMapper.readValue(jsonCaptor.getValue(), new TypeReference<>() {});
        assertThat(task).containsEntry("correlationId", "corr-1")
                .containsEntry("agentType", "agent")
                .containsEntry("priority", 0);
        // requestPayload is itself a JSON-encoded string (worker contract)
        String payloadJson = (String) task.get("requestPayload");
        assertThat(objectMapper.readValue(payloadJson, new TypeReference<Map<String, Object>>() {}))
                .containsEntry("foo", "bar");

        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) task.get("metadata");
        assertThat(metadata)
                .containsEntry("runId", "run-1")
                .containsEntry("nodeId", "node-1")
                .containsEntry("tenantId", "tenant-1");
    }

    @Test
    @DisplayName("Null runId/nodeId become empty strings (chat path tolerated; cancel-check no-ops on empty)")
    void enqueueTolerateNullRunAndNodeIds() throws Exception {
        when(redisTemplate.opsForList()).thenReturn(listOps);

        AgentExecutionRequestMessage msg = AgentExecutionRequestMessage.create(
            "corr-chat", null, null, "tenant-chat", "agent",
            "deepseek", "deepseek-chat",
            Map.of());

        producer.enqueue(msg);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(listOps).leftPush(anyString(), jsonCaptor.capture());

        Map<String, Object> task = objectMapper.readValue(jsonCaptor.getValue(), new TypeReference<>() {});
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) task.get("metadata");
        // worker contract: empty string instead of null so Map.of works AND
        // AgentQueueWorkerService.isWorkflowCancelled treats it as "no run" → false
        assertThat(metadata)
                .containsEntry("runId", "")
                .containsEntry("nodeId", "")
                .containsEntry("tenantId", "tenant-chat");
    }

    @Test
    @DisplayName("User roles are stamped in task metadata for queued bridge policy checks")
    void enqueueStampsUserRolesInMetadata() throws Exception {
        when(redisTemplate.opsForList()).thenReturn(listOps);

        AgentExecutionRequestMessage msg = AgentExecutionRequestMessage.create(
            "corr-admin", "run-admin", "agent:admin", "tenant-admin", "agent",
            "claude-code", "claude-sonnet-4-6",
            Map.of("provider", "claude-code"),
            "ADMIN,USER");

        producer.enqueue(msg);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(listOps).leftPush(anyString(), jsonCaptor.capture());

        Map<String, Object> task = objectMapper.readValue(jsonCaptor.getValue(), new TypeReference<>() {});
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) task.get("metadata");
        assertThat(metadata).containsEntry("userRoles", "ADMIN,USER");
    }

    @Test
    @DisplayName("Routes classify and guardrail to their own queues")
    void enqueueRoutesByAgentType() {
        when(redisTemplate.opsForList()).thenReturn(listOps);

        producer.enqueue(AgentExecutionRequestMessage.create(
            "c1", "r1", "n1", "t1", "classify", "openai", "gpt-5", Map.of()));
        producer.enqueue(AgentExecutionRequestMessage.create(
            "g1", "r1", "n1", "t1", "guardrail", "openai", "gpt-5", Map.of()));

        verify(listOps).leftPush(eq("agent:queue:classify"), anyString());
        verify(listOps).leftPush(eq("agent:queue:guardrail"), anyString());
    }

    @Test
    @DisplayName("Queues all shared-queue execution origins to the correct FIFO lists")
    void enqueueRoutesAllExecutionOriginsWithFifoQueueOrder() throws Exception {
        when(redisTemplate.opsForList()).thenReturn(listOps);
        Map<String, ArrayDeque<String>> queues = new HashMap<>();
        doAnswer(inv -> {
            String queueKey = inv.getArgument(0);
            String taskJson = inv.getArgument(1);
            queues.computeIfAbsent(queueKey, ignored -> new ArrayDeque<>()).addFirst(taskJson);
            return 1L;
        }).when(listOps).leftPush(anyString(), anyString());

        List<AgentExecutionRequestMessage> messages = List.of(
            message("workflow-agent", "run-workflow", "agent:workflow", "tenant-prod", "agent",
                "google", "gemini-3-pro-preview",
                Map.of(
                    "origin", "workflow",
                    "streamingFormat", "workflow",
                    "provider", "google",
                    "model", "gemini-3-pro-preview",
                    "credentials", Map.of("__orgId__", "org-prod"))),
            message("chat-general", null, null, "tenant-prod", "agent",
                "google", "gemini-3-pro-preview",
                Map.of(
                    "origin", "chat_general",
                    "source", "CHAT",
                    "streamingFormat", "conversation",
                    "conversationId", "conv-chat",
                    "provider", "google",
                    "model", "gemini-3-pro-preview",
                    "credentials", Map.of("__orgId__", "org-prod"))),
            message("widget-agent", null, null, "tenant-prod", "agent",
                "google", "gemini-3-pro-preview",
                Map.of(
                    "origin", "widget",
                    "source", "WIDGET",
                    "streamingFormat", "conversation",
                    "conversationId", "conv-widget",
                    "provider", "google",
                    "model", "gemini-3-pro-preview",
                    "credentials", Map.of("__orgId__", "org-prod", "__agentId__", "agent-widget"))),
            message("webhook-agent", null, null, "tenant-prod", "agent",
                "google", "gemini-3-pro-preview",
                Map.of(
                    "origin", "webhook",
                    "source", "WEBHOOK",
                    "streamingFormat", "conversation",
                    "conversationId", "conv-webhook",
                    "provider", "google",
                    "model", "gemini-3-pro-preview",
                    "credentials", Map.of("__orgId__", "org-prod", "__agentId__", "agent-webhook"))),
            message("schedule-agent", null, null, "tenant-prod", "agent",
                "google", "gemini-3-pro-preview",
                Map.of(
                    "origin", "schedule",
                    "source", "SCHEDULE",
                    "streamingFormat", "conversation",
                    "conversationId", "conv-schedule",
                    "provider", "google",
                    "model", "gemini-3-pro-preview",
                    "credentials", Map.of("__orgId__", "org-prod", "__agentId__", "agent-schedule"))),
            message("task-agent", "run-task", "agent:task", "tenant-prod", "agent",
                "deepseek", "deepseek-chat",
                Map.of(
                    "origin", "task",
                    "taskId", "task-prod",
                    "provider", "deepseek",
                    "model", "deepseek-chat",
                    "credentials", Map.of("__orgId__", "org-prod", "__taskId__", "task-prod"))),
            message("task-reviewer", null, null, "tenant-prod", "agent",
                "deepseek", "deepseek-chat",
                Map.of(
                    "origin", "task_review",
                    "source", "TASK_REVIEW",
                    "streamingFormat", "conversation",
                    "conversationId", "conv-review",
                    "taskId", "task-review-prod",
                    "provider", "deepseek",
                    "model", "deepseek-chat",
                    "credentials", Map.of("__orgId__", "org-prod", "__taskId__", "task-review-prod"))),
            message("classify", "run-classify", "agent:classify", "tenant-prod", "classify",
                "mistral", "mistral-large-latest",
                Map.of(
                    "origin", "classify",
                    "provider", "mistral",
                    "model", "mistral-large-latest",
                    "credentials", Map.of("__orgId__", "org-prod"))),
            message("guardrail", "run-guardrail", "agent:guardrail", "tenant-prod", "guardrail",
                "google", "gemini-3-pro-preview",
                Map.of(
                    "origin", "guardrail",
                    "provider", "google",
                    "model", "gemini-3-pro-preview",
                    "credentials", Map.of("__orgId__", "org-prod")))
        );

        messages.forEach(producer::enqueue);

        assertThat(queues.keySet())
            .containsExactlyInAnyOrder("agent:queue:agent", "agent:queue:classify", "agent:queue:guardrail");

        List<Map<String, Object>> agentTasks = brpopAll(queues.get("agent:queue:agent"));
        assertThat(agentTasks).extracting(task -> task.get("correlationId"))
            .containsExactly(
                "corr-workflow-agent",
                "corr-chat-general",
                "corr-widget-agent",
                "corr-webhook-agent",
                "corr-schedule-agent",
                "corr-task-agent",
                "corr-task-reviewer");
        assertThat(agentTasks).extracting(task -> task.get("agentType"))
            .containsOnly("agent");
        assertThat(agentTasks).extracting(task -> requestPayload(task).get("origin"))
            .containsExactly(
                "workflow",
                "chat_general",
                "widget",
                "webhook",
                "schedule",
                "task",
                "task_review");

        @SuppressWarnings("unchecked")
        Map<String, Object> conversationMetadata = (Map<String, Object>) agentTasks.get(1).get("metadata");
        assertThat(conversationMetadata)
            .containsEntry("runId", "")
            .containsEntry("nodeId", "")
            .containsEntry("tenantId", "tenant-prod");

        List<Map<String, Object>> classifyTasks = brpopAll(queues.get("agent:queue:classify"));
        assertThat(classifyTasks).hasSize(1);
        assertThat(classifyTasks.get(0)).containsEntry("correlationId", "corr-classify")
            .containsEntry("agentType", "classify");
        assertThat(requestPayload(classifyTasks.get(0))).containsEntry("origin", "classify");

        List<Map<String, Object>> guardrailTasks = brpopAll(queues.get("agent:queue:guardrail"));
        assertThat(guardrailTasks).hasSize(1);
        assertThat(guardrailTasks.get(0)).containsEntry("correlationId", "corr-guardrail")
            .containsEntry("agentType", "guardrail");
        assertThat(requestPayload(guardrailTasks.get(0))).containsEntry("origin", "guardrail");
    }

    @Test
    @DisplayName("Redis failure surfaces as RuntimeException - caller must decide retry policy")
    void enqueueWrapsRedisFailure() {
        when(redisTemplate.opsForList()).thenReturn(listOps);
        doThrow(new RuntimeException("redis down"))
                .when(listOps).leftPush(anyString(), anyString());

        AgentExecutionRequestMessage msg = AgentExecutionRequestMessage.create(
            "corr-fail", "run-1", "node-1", "tenant-1", "agent",
            "deepseek", "deepseek-chat", Map.of());

        assertThatThrownBy(() -> producer.enqueue(msg))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to enqueue agent execution task");
    }

    private AgentExecutionRequestMessage message(
            String correlationSuffix,
            String runId,
            String nodeId,
            String tenantId,
            String agentType,
            String provider,
            String model,
            Map<String, Object> payload) {
        return AgentExecutionRequestMessage.create(
            "corr-" + correlationSuffix, runId, nodeId, tenantId, agentType, provider, model, payload);
    }

    private List<Map<String, Object>> brpopAll(ArrayDeque<String> queue) throws Exception {
        List<Map<String, Object>> tasks = new ArrayList<>();
        while (queue != null && !queue.isEmpty()) {
            tasks.add(objectMapper.readValue(queue.removeLast(), new TypeReference<>() {}));
        }
        return tasks;
    }

    private Map<String, Object> requestPayload(Map<String, Object> task) throws Exception {
        return objectMapper.readValue((String) task.get("requestPayload"), new TypeReference<>() {});
    }
}
