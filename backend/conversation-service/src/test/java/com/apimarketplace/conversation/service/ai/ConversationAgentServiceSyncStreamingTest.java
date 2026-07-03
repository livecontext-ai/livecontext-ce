package com.apimarketplace.conversation.service.ai;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.client.dto.execution.AgentExecutionRequestDto;
import com.apimarketplace.agent.client.dto.execution.AgentExecutionResponseDto;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import reactor.core.publisher.Mono;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Bridge sync (schedule/webhook/task) runs must stream live into the conversation exactly like an
 * interactive chat: the bridge bypasses agent-service, so {@code executeSync} EMULATES the
 * {@code ConversationRedisStreamingCallback} - register a reconnectable stream + emit
 * {@code stream_started} BEFORE dispatch, then {@code done}/{@code error} + finalize the state
 * AFTER. This guards that behavior and its gating (eligible sources only, bridge transport only).
 */
@DisplayName("ConversationAgentService - bridge sync conversation streaming")
class ConversationAgentServiceSyncStreamingTest {

    private AgentContextBuilder contextBuilder;
    private AgentClient agentClient;
    private BridgeClient bridgeClient;
    private CreditConsumptionClient creditClient;
    private StreamStateService stateService;
    private EventBus eventBus;
    private BridgeStreamHeartbeat heartbeat;
    private ConversationAgentService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        contextBuilder = mock(AgentContextBuilder.class);
        agentClient = mock(AgentClient.class);
        bridgeClient = mock(BridgeClient.class);
        creditClient = mock(CreditConsumptionClient.class);
        stateService = mock(StreamStateService.class);
        eventBus = mock(EventBus.class);

        when(creditClient.fetchBalance(anyString())).thenReturn(new java.math.BigDecimal("100"));
        // Reactive stream-state writes are best-effort .block()ed - return completed Monos.
        when(stateService.registerExternalStream(anyString(), anyString(), any(), any(), any()))
                .thenReturn(Mono.empty());
        when(stateService.complete(anyString())).thenReturn(Mono.just(true));
        when(stateService.error(anyString(), any())).thenReturn(Mono.just(true));

        service = new ConversationAgentService(
                contextBuilder,
                mock(AgentObservabilityClient.class),
                mock(AgentConfigProvider.class),
                creditClient,
                mock(MessageService.class),
                mock(PendingActionService.class),
                mock(ToolResultService.class),
                objectMapper,
                agentClient,
                stateService,
                eventBus,
                mock(HelpSeenRegistry.class),
                mock(MessageRepository.class),
                "http://localhost:8087"
        );

        setField(service, "bridgeEnabled", Boolean.TRUE);
        setField(service, "bridgeClient", bridgeClient);
        setField(service, "bridgeAccessEnforcer", mock(BridgeAccessEnforcer.class));
        heartbeat = mock(BridgeStreamHeartbeat.class);
        setField(service, "bridgeStreamHeartbeat", heartbeat);
    }

    @Test
    @DisplayName("eligible bridge run holds the stream:hb heartbeat across the dispatch (StreamTTL reap regression) and releases it at finalize")
    void bridgeScheduleHoldsHeartbeatAcrossDispatch() throws Exception {
        stubBridgeContext("claude-code", "claude-opus-4-8");
        when(bridgeClient.executeViaBridge(any())).thenReturn(successResponse("hello world"));

        service.executeSync(scheduleRequest("conv-hb"), "conv-hb");

        // Register BEFORE the blocking bridge call (the row registered by
        // registerExternalStream is reapable by the absolute-timeout pass the moment it
        // exists), release at the finalize funnel AFTER the run.
        InOrder order = inOrder(heartbeat, bridgeClient);
        order.verify(heartbeat).register(anyString());
        order.verify(bridgeClient).executeViaBridge(any());
        order.verify(heartbeat).unregister(anyString());
    }

    @Test
    @DisplayName("bridge failure path still releases the heartbeat (finalize funnel covers every terminal)")
    void bridgeFailureReleasesHeartbeat() throws Exception {
        stubBridgeContext("claude-code", "claude-opus-4-8");
        when(bridgeClient.executeViaBridge(any())).thenReturn(failureResponse("model exploded"));

        service.executeSync(scheduleRequest("conv-hb-fail"), "conv-hb-fail");

        verify(heartbeat).register(anyString());
        verify(heartbeat).unregister(anyString());
    }

    @Test
    @DisplayName("WIDGET source registers no stream row - so no heartbeat either (nothing reapable to shield)")
    void widgetBridgeDoesNotHeartbeat() throws Exception {
        stubBridgeContext("claude-code", "claude-opus-4-8");
        when(bridgeClient.executeViaBridge(any())).thenReturn(successResponse("widget reply"));

        service.executeSync(widgetRequest("conv-widget-hb"), "conv-widget-hb");

        verify(heartbeat, never()).register(anyString());
    }

    @Test
    @DisplayName("eligible bridge run (SCHEDULE) registers a real stream + emits stream_started BEFORE dispatch, then done + completes state")
    void bridgeScheduleEmulatesConversationStream() throws Exception {
        stubBridgeContext("claude-code", "claude-opus-4-8");
        when(bridgeClient.executeViaBridge(any())).thenReturn(successResponse("hello world"));

        Map<String, Object> result = service.executeSync(scheduleRequest("conv-sched"), "conv-sched");

        assertThat(result).containsEntry("success", true);

        // Register MUST happen before dispatch so a reconnect mid-run resolves the stream.
        InOrder order = inOrder(stateService, bridgeClient);
        order.verify(stateService).registerExternalStream(anyString(), eq("conv-sched"), eq("claude-opus-4-8"), eq("claude-code"), any());
        order.verify(bridgeClient).executeViaBridge(any());
        // Completion state advanced so a post-run reconnect sees a terminal event, not a stuck stream.
        verify(stateService).complete(anyString());
        verify(stateService, never()).error(anyString(), any());

        // The bridge receives a REAL (UUID) stream id, not the legacy "sync-" placeholder, so its
        // content/tool buffering lands under a registered, reconnectable stream.
        ArgumentCaptor<AgentExecutionRequestDto> dtoCap = ArgumentCaptor.forClass(AgentExecutionRequestDto.class);
        verify(bridgeClient).executeViaBridge(dtoCap.capture());
        String bridgeStreamId = dtoCap.getValue().streamChannelId();
        assertThat(bridgeStreamId).doesNotStartWith("sync-");
        assertThat(bridgeStreamId).hasSize(36); // UUID

        List<Map<String, Object>> wsEvents = capturedConversationEvents("conv-sched");
        Map<String, Object> started = wsEvents.stream()
                .filter(e -> e.containsKey("model") && e.containsKey("conversationId") && !e.containsKey("fullContent"))
                .findFirst().orElse(null);
        assertThat(started).as("stream_started event").isNotNull();
        assertThat(started).containsEntry("conversationId", "conv-sched").containsEntry("model", "claude-opus-4-8");
        assertThat(started.get("streamId")).isEqualTo(bridgeStreamId);

        Map<String, Object> done = wsEvents.stream()
                .filter(e -> e.containsKey("fullContent") && e.containsKey("totalTokens"))
                .findFirst().orElse(null);
        assertThat(done).as("done event").isNotNull();
        assertThat(done).containsEntry("fullContent", "hello world");
        assertThat(done.get("streamId")).isEqualTo(bridgeStreamId);
    }

    @Test
    @DisplayName("WIDGET source is NOT a browsable conversation - no stream registration, no events, legacy sync- id")
    void widgetBridgeDoesNotEmulateStream() throws Exception {
        stubBridgeContext("claude-code", "claude-opus-4-8");
        when(bridgeClient.executeViaBridge(any())).thenReturn(successResponse("widget reply"));

        service.executeSync(widgetRequest("conv-widget"), "conv-widget");

        verify(stateService, never()).registerExternalStream(anyString(), anyString(), any(), any(), any());
        verify(stateService, never()).complete(anyString());
        assertThat(capturedConversationEvents("conv-widget")).isEmpty();

        ArgumentCaptor<AgentExecutionRequestDto> dtoCap = ArgumentCaptor.forClass(AgentExecutionRequestDto.class);
        verify(bridgeClient).executeViaBridge(dtoCap.capture());
        assertThat(dtoCap.getValue().streamChannelId()).isEqualTo("sync-conv-widget");
    }

    @Test
    @DisplayName("non-bridge eligible run does NOT emulate - agent-service's callback owns the stream")
    void remoteScheduleDoesNotDoubleEmulate() throws Exception {
        stubBridgeContext("deepseek", "deepseek-chat"); // non-bridge provider
        when(agentClient.executeAgent(any())).thenReturn(successResponse("remote reply"));

        service.executeSync(scheduleRequest("conv-remote"), "conv-remote");

        // executeSync must not register/emit/finalize on the remote path (that would double the
        // callback's work and risk duplicate stream_started/done).
        verify(stateService, never()).registerExternalStream(anyString(), anyString(), any(), any(), any());
        verify(stateService, never()).complete(anyString());
        assertThat(capturedConversationEvents("conv-remote")).isEmpty();
        verify(agentClient).executeAgent(any());
        verify(bridgeClient, never()).executeViaBridge(any());
    }

    @Test
    @DisplayName("bridge run that fails (success=false) emits a stream error + errors the state, not done")
    void bridgeFailureEmitsErrorEvent() throws Exception {
        stubBridgeContext("claude-code", "claude-opus-4-8");
        when(bridgeClient.executeViaBridge(any())).thenReturn(failureResponse("model exploded"));

        service.executeSync(scheduleRequest("conv-fail"), "conv-fail");

        verify(stateService).error(anyString(), any());
        verify(stateService, never()).complete(anyString());

        List<Map<String, Object>> wsEvents = capturedConversationEvents("conv-fail");
        Map<String, Object> error = wsEvents.stream()
                .filter(e -> e.containsKey("error") && e.containsKey("errorCode"))
                .findFirst().orElse(null);
        assertThat(error).as("error event").isNotNull();
        assertThat(error).containsEntry("errorCode", "STREAM_ERROR");
        assertThat(wsEvents.stream().anyMatch(e -> e.containsKey("fullContent"))).isFalse();
    }

    @Test
    @DisplayName("null bridge response (transport failure / unreachable) finalizes the stream as error")
    void bridgeNullResponseFinalizesAsError() throws Exception {
        // The REAL bridge-failure path: BridgeClient.executeViaBridge swallows transport exceptions
        // and returns null (it never throws). So this - not a thrown exception - is what an
        // unreachable/erroring bridge produces, and the stream must still be finalized as error so
        // the live bubble is never left stuck "streaming".
        stubBridgeContext("claude-code", "claude-opus-4-8");
        when(bridgeClient.executeViaBridge(any())).thenReturn(null);

        Map<String, Object> result = service.executeSync(scheduleRequest("conv-null"), "conv-null");

        assertThat(result).containsEntry("success", false);
        verify(stateService).registerExternalStream(anyString(), eq("conv-null"), any(), any(), any());
        verify(stateService).error(anyString(), any());
        verify(stateService, never()).complete(anyString());
        assertThat(capturedConversationEvents("conv-null").stream()
                .anyMatch(e -> e.containsKey("error") && e.containsKey("errorCode"))).isTrue();
    }

    @Test
    @DisplayName("outer-catch safety net: an exception after registration still finalizes the stream as error")
    void postRegistrationExceptionFinalizesAsError() throws Exception {
        // In production BridgeClient returns null rather than throwing, so we force a post-registration
        // throw to prove the outer catch's defensive finalize: a started stream is never left stuck if
        // any collaborator after registration fails unexpectedly.
        stubBridgeContext("claude-code", "claude-opus-4-8");
        when(bridgeClient.executeViaBridge(any())).thenThrow(new RuntimeException("unexpected post-registration failure"));

        Map<String, Object> result = service.executeSync(scheduleRequest("conv-throw"), "conv-throw");

        assertThat(result).containsEntry("success", false);
        verify(stateService).registerExternalStream(anyString(), eq("conv-throw"), any(), any(), any());
        verify(stateService).error(anyString(), any());
        verify(stateService, never()).complete(anyString());
        assertThat(capturedConversationEvents("conv-throw").stream()
                .anyMatch(e -> e.containsKey("error") && e.containsKey("errorCode"))).isTrue();
    }

    @ParameterizedTest(name = "eligible source \"{0}\" registers + finalizes a reconnectable stream")
    @ValueSource(strings = {"WEBHOOK", "TASK", "TASK_REVIEW", "schedule", "Webhook"})
    @DisplayName("every eligible sync source (case-insensitive) emulates the conversation stream")
    void allEligibleSourcesEmulate(String source) throws Exception {
        stubBridgeContext("claude-code", "claude-opus-4-8");
        when(bridgeClient.executeViaBridge(any())).thenReturn(successResponse("ok"));
        String conv = "conv-" + source.toLowerCase(java.util.Locale.ROOT);
        ChatRequest request = scheduleRequest(conv);
        request.setSource(source); // mixed/lower case exercises the toUpperCase normalization

        service.executeSync(request, conv);

        verify(stateService).registerExternalStream(anyString(), eq(conv), any(), any(), any());
        verify(stateService).complete(anyString());
        assertThat(capturedConversationEvents(conv).stream()
                .anyMatch(e -> e.containsKey("model") && e.containsKey("conversationId") && !e.containsKey("fullContent")))
                .as("stream_started emitted").isTrue();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private void stubBridgeContext(String provider, String model) {
        AgentLoopContext context = AgentLoopContext.builder()
                .provider(provider)
                .model(model)
                .userPrompt("do the scheduled thing")
                .systemPrompt("you are an agent")
                .conversationHistory(Collections.emptyList())
                .tools(Collections.emptyList())
                .tenantId("user-1")
                .credentials(new java.util.HashMap<>()) // no __agentId__ → no fleet events to dedup
                .build();
        when(contextBuilder.build(any(ChatRequest.class), anyString(), anyString(), any())).thenReturn(context);
    }

    private ChatRequest scheduleRequest(String conversationId) {
        ChatRequest request = new ChatRequest();
        request.setUserId("user-1");
        request.setModel("claude-opus-4-8");
        request.setProvider("claude-code");
        request.setMessage("scheduled prompt");
        request.setSource("SCHEDULE");
        request.setConversationId(conversationId);
        return request;
    }

    private ChatRequest widgetRequest(String conversationId) {
        ChatRequest request = scheduleRequest(conversationId);
        request.setSource("WIDGET");
        return request;
    }

    private AgentExecutionResponseDto successResponse(String content) {
        return new AgentExecutionResponseDto(
                true, content, content, Collections.emptyList(), 1,
                Map.of("totalTokens", 10), null, 40L, "claude-code", "claude-opus-4-8",
                Collections.emptyList(), "end_turn",
                Map.of(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                null, null, null
        );
    }

    private AgentExecutionResponseDto failureResponse(String error) {
        return new AgentExecutionResponseDto(
                false, "", "", Collections.emptyList(), 1,
                Map.of("totalTokens", 0), error, 40L, "claude-code", "claude-opus-4-8",
                Collections.emptyList(), "ERROR",
                Map.of(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                null, null, null
        );
    }

    /** All events published on this conversation's WS channel, deserialized. */
    private List<Map<String, Object>> capturedConversationEvents(String conversationId) throws Exception {
        ArgumentCaptor<String> chan = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(eventBus, atLeast(0)).publish(chan.capture(), body.capture());
        List<String> chans = chan.getAllValues();
        List<String> bodies = body.getAllValues();
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < chans.size(); i++) {
            if (("ws:conversation:" + conversationId).equals(chans.get(i))) {
                @SuppressWarnings("unchecked")
                Map<String, Object> ev = objectMapper.readValue(bodies.get(i), Map.class);
                out.add(ev);
            }
        }
        return out;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }
}
