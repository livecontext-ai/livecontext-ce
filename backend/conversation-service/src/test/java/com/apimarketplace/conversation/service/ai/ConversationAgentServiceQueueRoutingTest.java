package com.apimarketplace.conversation.service.ai;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.client.dto.execution.AgentExecutionRequestDto;
import com.apimarketplace.agent.client.dto.execution.AgentExecutionResponseDto;
import com.apimarketplace.agent.client.queue.AgentExecutionRequestMessage;
import com.apimarketplace.agent.client.queue.AgentQueueProducer;
import com.apimarketplace.agent.client.queue.RedisResultWaiter;
import com.apimarketplace.agent.loop.AgentLoopContext;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.common.event.EventBus;
import com.apimarketplace.conversation.dto.ChatRequest;
import com.apimarketplace.conversation.repository.MessageRepository;
import com.apimarketplace.conversation.service.MessageService;
import com.apimarketplace.conversation.service.PendingActionService;
import com.apimarketplace.conversation.service.ToolResultService;
import com.apimarketplace.conversation.service.ai.callback.AgentContextBuilder;
import com.apimarketplace.conversation.service.ai.schema.HelpSeenRegistry;
import com.apimarketplace.conversation.streaming.StreamStateService;
import com.apimarketplace.conversation.streaming.StreamingOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PR2 regression guard - chat conversation must dispatch via the Redis queue
 * when {@link AgentQueueProducer} and {@link RedisResultWaiter} are wired, and
 * fall back to direct HTTP via {@link AgentClient} otherwise.
 *
 * <p>The streaming SSE path is unaffected by the transport swap: agent-service's
 * {@code ConversationRedisStreamingCallback} publishes chunks to Redis pub/sub
 * during execution regardless of how the task was dispatched. This test only
 * verifies the dispatch leg.</p>
 */
@DisplayName("ConversationAgentService - Redis queue dispatch routing (PR2)")
class ConversationAgentServiceQueueRoutingTest {

    private AgentContextBuilder contextBuilder;
    private AgentClient agentClient;
    private AgentQueueProducer queueProducer;
    private RedisResultWaiter resultWaiter;
    private CreditConsumptionClient creditClient;
    private StreamingOutput streamOutput;
    private ConversationAgentService service;

    @BeforeEach
    void setUp() throws Exception {
        contextBuilder = mock(AgentContextBuilder.class);
        agentClient = mock(AgentClient.class);
        queueProducer = mock(AgentQueueProducer.class);
        resultWaiter = mock(RedisResultWaiter.class);
        creditClient = mock(CreditConsumptionClient.class);
        streamOutput = mock(StreamingOutput.class);

        when(streamOutput.getCurrentStreamId()).thenReturn("stream-q");
        when(creditClient.fetchBalance(anyString())).thenReturn(new java.math.BigDecimal("100"));

        service = new ConversationAgentService(
            contextBuilder,
            mock(AgentObservabilityClient.class),
            mock(AgentConfigProvider.class),
            creditClient,
            mock(MessageService.class),
            mock(PendingActionService.class),
            mock(ToolResultService.class),
            new ObjectMapper(),
            agentClient,
            mock(StreamStateService.class),
            mock(EventBus.class),
            mock(HelpSeenRegistry.class),
            mock(MessageRepository.class),
            "http://localhost:8087"
        );

        // bridge field-injected - leave null so we always exercise the remote
        // (non-bridge) dispatch branch in executeStreaming.
        setField(service, "bridgeEnabled", Boolean.FALSE);
        setField(service, "bridgeClient", null);
        setField(service, "bridgeAccessEnforcer", mock(BridgeAccessEnforcer.class));
    }

    @Test
    @DisplayName("With queueProducer + resultWaiter wired → enqueue + await; HTTP not called")
    void routesViaQueueWhenBeansPresent() throws Exception {
        setField(service, "queueProducer", queueProducer);
        setField(service, "resultWaiter", resultWaiter);

        ChatRequest request = new ChatRequest();
        request.setUserId("user-q");
        request.setModel("deepseek-chat");
        request.setProvider("deepseek");
        request.setMessage("hello");
        request.setUserRoles("ADMIN,USER");

        AgentLoopContext context = AgentLoopContext.builder()
            .provider("deepseek")
            .model("deepseek-chat")
            .userPrompt("hello")
            .systemPrompt("you are helpful")
            .conversationHistory(Collections.emptyList())
            .tools(Collections.emptyList())
            .tenantId("user-q")
            .userRoles("ADMIN,USER")
            .build();
        when(contextBuilder.build(any(ChatRequest.class), anyString(), anyString(), any())).thenReturn(context);

        AgentExecutionResponseDto queueResponse = new AgentExecutionResponseDto(
            true, "ok", "ok", Collections.emptyList(), 1,
            Map.of("promptTokens", 10, "completionTokens", 5, "totalTokens", 15),
            null, 50L, "deepseek", "deepseek-chat",
            Collections.emptyList(), "end_turn",
            Map.of(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
            null, null, null
        );
        when(resultWaiter.await(anyString(), eq(AgentExecutionResponseDto.class), any(Duration.class)))
            .thenReturn(queueResponse);

        service.executeStreaming(request, streamOutput, "conv-q");

        // queue path took the dispatch
        ArgumentCaptor<AgentExecutionRequestMessage> msgCaptor =
            ArgumentCaptor.forClass(AgentExecutionRequestMessage.class);
        verify(queueProducer).enqueue(msgCaptor.capture());
        AgentExecutionRequestMessage sent = msgCaptor.getValue();
        assertThat(sent.agentType()).isEqualTo("agent");
        assertThat(sent.provider()).isEqualTo("deepseek");
        assertThat(sent.model()).isEqualTo("deepseek-chat");
        assertThat(sent.tenantId()).isEqualTo("user-q");
        assertThat(sent.userRoles()).isEqualTo("ADMIN,USER");
        assertThat(sent.correlationId()).isNotBlank();
        assertThat(sent.runId()).isNull(); // chat path has no workflow run
        assertThat(sent.nodeId()).isNull();
        assertThat(sent.requestPayload()).isNotEmpty()
            .containsEntry("provider", "deepseek")
            .containsEntry("model", "deepseek-chat");

        verify(resultWaiter).await(eq(sent.correlationId()), eq(AgentExecutionResponseDto.class), any(Duration.class));
        // HTTP fallback NOT taken
        verify(agentClient, never()).executeAgent(any(AgentExecutionRequestDto.class));
    }

    @Test
    @DisplayName("executeSync (webhook/schedule) also routes via queue when beans present - must not silently skip")
    void executeSyncRoutesViaQueueWhenBeansPresent() throws Exception {
        setField(service, "queueProducer", queueProducer);
        setField(service, "resultWaiter", resultWaiter);

        ChatRequest request = new ChatRequest();
        request.setUserId("user-sync");
        request.setModel("deepseek-chat");
        request.setProvider("deepseek");
        request.setMessage("sync hello");
        request.setSource("webhook");
        request.setUserRoles("ADMIN,USER");

        AgentLoopContext context = AgentLoopContext.builder()
            .provider("deepseek")
            .model("deepseek-chat")
            .userPrompt("sync hello")
            .systemPrompt("you are helpful")
            .conversationHistory(Collections.emptyList())
            .tools(Collections.emptyList())
            .tenantId("user-sync")
            .build();
        when(contextBuilder.build(any(ChatRequest.class), anyString(), anyString(), any())).thenReturn(context);

        AgentExecutionResponseDto syncResponse = new AgentExecutionResponseDto(
            true, "sync ok", "sync ok", Collections.emptyList(), 1,
            Map.of("totalTokens", 20), null, 75L, "deepseek", "deepseek-chat",
            Collections.emptyList(), "end_turn",
            Map.of(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
            null, null, null
        );
        when(resultWaiter.await(anyString(), eq(AgentExecutionResponseDto.class), any(Duration.class)))
            .thenReturn(syncResponse);

        Map<String, Object> result = service.executeSync(request, "conv-sync");

        // executeSync MUST share the dispatchAgentExecution router - without this test a
        // future refactor could split the paths and silently regress webhook/schedule
        // back onto raw HTTP, defeating the unification goal.
        verify(queueProducer).enqueue(any(AgentExecutionRequestMessage.class));
        verify(resultWaiter).await(anyString(), eq(AgentExecutionResponseDto.class), any(Duration.class));
        verify(agentClient, never()).executeAgent(any(AgentExecutionRequestDto.class));

        assertThat(result).containsEntry("success", true)
                          .containsEntry("conversationId", "conv-sync");
    }

    @Test
    @DisplayName("All chat-derived sources route through the shared agent queue with task/org markers preserved")
    void allChatDerivedSourcesRouteThroughSharedAgentQueue() throws Exception {
        setField(service, "queueProducer", queueProducer);
        setField(service, "resultWaiter", resultWaiter);

        List<ChatQueueCase> cases = List.of(
            new ChatQueueCase("general-chat", null, null, null, "conv-general"),
            new ChatQueueCase("webhook-agent", "WEBHOOK", null, "agent-webhook", "conv-webhook"),
            new ChatQueueCase("scheduled-agent", "SCHEDULE", null, "agent-schedule", "conv-schedule"),
            new ChatQueueCase("task-agent", "TASK", "task-assignee", "agent-task", "conv-task"),
            new ChatQueueCase("reviewer-agent", "TASK_REVIEW", "task-review", "agent-reviewer", "conv-review"),
            new ChatQueueCase("widget-agent", "WIDGET", null, "agent-widget", "conv-widget")
        );

        when(contextBuilder.build(any(ChatRequest.class), anyString(), anyString(), any()))
            .thenAnswer(inv -> contextFor(inv.getArgument(0)));
        when(resultWaiter.await(anyString(), eq(AgentExecutionResponseDto.class), any(Duration.class)))
            .thenReturn(successfulQueueResponse("queued ok"));

        for (ChatQueueCase queueCase : cases) {
            Map<String, Object> result = service.executeSync(requestFor(queueCase), queueCase.conversationId());
            assertThat(result).containsEntry("success", true)
                .containsEntry("conversationId", queueCase.conversationId());
        }

        ArgumentCaptor<AgentExecutionRequestMessage> msgCaptor =
            ArgumentCaptor.forClass(AgentExecutionRequestMessage.class);
        verify(queueProducer, times(cases.size())).enqueue(msgCaptor.capture());
        verify(resultWaiter, times(cases.size()))
            .await(anyString(), eq(AgentExecutionResponseDto.class), any(Duration.class));
        verify(agentClient, never()).executeAgent(any(AgentExecutionRequestDto.class));

        List<AgentExecutionRequestMessage> messages = msgCaptor.getAllValues();
        assertThat(messages).hasSize(cases.size());

        for (int i = 0; i < cases.size(); i++) {
            ChatQueueCase queueCase = cases.get(i);
            AgentExecutionRequestMessage message = messages.get(i);
            Map<String, Object> payload = message.requestPayload();

            assertThat(message.agentType()).isEqualTo("agent");
            assertThat(message.userRoles()).as(queueCase.label()).isEqualTo("ADMIN,USER");
            assertThat(message.runId()).as(queueCase.label()).isNull();
            assertThat(message.nodeId()).as(queueCase.label()).isNull();
            assertThat(message.tenantId()).as(queueCase.label()).isEqualTo("tenant-" + queueCase.label());
            assertThat(payload)
                .containsEntry("provider", "deepseek")
                .containsEntry("model", "deepseek-chat")
                .containsEntry("tenantId", "tenant-" + queueCase.label())
                .containsEntry("conversationId", queueCase.conversationId())
                .containsEntry("streamingFormat", "conversation")
                .containsEntry("source", queueCase.source() != null ? queueCase.source() : "CHAT");

            @SuppressWarnings("unchecked")
            Map<String, Object> credentials = (Map<String, Object>) payload.get("credentials");
            assertThat(credentials).containsEntry("__orgId__", "org-" + queueCase.label());
            if (queueCase.agentId() != null) {
                assertThat(credentials).containsEntry("__agentId__", queueCase.agentId());
                assertThat(payload).containsEntry("agentEntityId", queueCase.agentId());
            }
            if (queueCase.taskId() != null) {
                assertThat(credentials).containsEntry("__taskId__", queueCase.taskId());
            } else {
                assertThat(credentials).doesNotContainKey("__taskId__");
            }
        }
    }

    @Test
    @DisplayName("Without queue beans (CE / dev) → falls back to direct HTTP via AgentClient")
    void fallsBackToHttpWhenQueueBeansAbsent() {
        // queueProducer and resultWaiter NOT set - they stay null (the production
        // default when scaling.agent.queue.enabled=false).

        ChatRequest request = new ChatRequest();
        request.setUserId("user-h");
        request.setModel("deepseek-chat");
        request.setProvider("deepseek");
        request.setMessage("hello");

        AgentLoopContext context = AgentLoopContext.builder()
            .provider("deepseek")
            .model("deepseek-chat")
            .userPrompt("hello")
            .systemPrompt("you are helpful")
            .conversationHistory(Collections.emptyList())
            .tools(Collections.emptyList())
            .tenantId("user-h")
            .build();
        when(contextBuilder.build(any(ChatRequest.class), anyString(), anyString(), any())).thenReturn(context);

        AgentExecutionResponseDto httpResponse = new AgentExecutionResponseDto(
            true, "ok", "ok", Collections.emptyList(), 1,
            Map.of("totalTokens", 15), null, 50L, "deepseek", "deepseek-chat",
            Collections.emptyList(), "end_turn",
            Map.of(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
            null, null, null
        );
        when(agentClient.executeAgent(any(AgentExecutionRequestDto.class))).thenReturn(httpResponse);

        service.executeStreaming(request, streamOutput, "conv-h");

        verify(agentClient).executeAgent(any(AgentExecutionRequestDto.class));
        // queue path NOT taken
        verify(queueProducer, never()).enqueue(any(AgentExecutionRequestMessage.class));
        verify(resultWaiter, never()).await(anyString(), any(), any(Duration.class));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** Reflection setter for @Autowired field-injected dependencies (no Spring boot). */
    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    private ChatRequest requestFor(ChatQueueCase queueCase) {
        ChatRequest request = new ChatRequest();
        request.setUserId("tenant-" + queueCase.label());
        request.setOrgId("org-" + queueCase.label());
        request.setModel("deepseek-chat");
        request.setProvider("deepseek");
        request.setMessage("message for " + queueCase.label());
        request.setSource(queueCase.source());
        request.setTaskId(queueCase.taskId());
        request.setAgentId(queueCase.agentId());
        request.setUserRoles("ADMIN,USER");
        return request;
    }

    private AgentLoopContext contextFor(ChatRequest request) {
        Map<String, Object> credentials = new HashMap<>();
        credentials.put("__orgId__", request.getOrgId());
        if (request.getAgentId() != null && !request.getAgentId().isBlank()) {
            credentials.put("__agentId__", request.getAgentId());
        }

        return AgentLoopContext.builder()
            .provider(request.getProvider())
            .model(request.getModel())
            .userPrompt(request.getMessage())
            .systemPrompt("you are helpful")
            .conversationHistory(Collections.emptyList())
            .tools(Collections.emptyList())
            .tenantId(request.getUserId())
            .credentials(credentials)
            .userRoles(request.getUserRoles())
            .build();
    }

    private AgentExecutionResponseDto successfulQueueResponse(String content) {
        return new AgentExecutionResponseDto(
            true, content, content, Collections.emptyList(), 1,
            Map.of("totalTokens", 20), null, 75L, "deepseek", "deepseek-chat",
            Collections.emptyList(), "end_turn",
            Map.of(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
            null, null, null
        );
    }

    private record ChatQueueCase(
        String label,
        String source,
        String taskId,
        String agentId,
        String conversationId
    ) {}
}
