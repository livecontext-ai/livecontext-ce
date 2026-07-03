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
import com.apimarketplace.conversation.streaming.StreamingOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression: the DIRECT bridge dispatch on the interactive chat path
 * ({@code executeStreaming} → {@code executeViaBridge}) bypasses agent-service, so it
 * must hold the {@code stream:hb} heartbeat ITSELF for the duration of the blocking
 * bridge call ({@link BridgeStreamHeartbeat}). Without it the conversation Stream row's
 * frozen {@code updated_at} plus the missing heartbeat made StreamTTLService's
 * absolute-timeout pass interrupt a HEALTHY direct claude-code/codex chat at the TTL
 * (~10 min in cloud) even while actively streaming.
 */
@DisplayName("ConversationAgentService - direct bridge dispatch holds the stream heartbeat")
class ConversationAgentServiceBridgeHeartbeatWiringTest {

    private AgentContextBuilder contextBuilder;
    private AgentClient agentClient;
    private BridgeClient bridgeClient;
    private BridgeStreamHeartbeat heartbeat;
    private StreamingOutput streamOutput;
    private ConversationAgentService service;

    @BeforeEach
    void setUp() throws Exception {
        contextBuilder = mock(AgentContextBuilder.class);
        agentClient = mock(AgentClient.class);
        bridgeClient = mock(BridgeClient.class);
        heartbeat = mock(BridgeStreamHeartbeat.class);
        streamOutput = mock(StreamingOutput.class);
        CreditConsumptionClient creditClient = mock(CreditConsumptionClient.class);

        when(streamOutput.getCurrentStreamId()).thenReturn("stream-hb-1");
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
        setField(service, "bridgeEnabled", Boolean.TRUE);
        setField(service, "bridgeClient", bridgeClient);
        setField(service, "bridgeAccessEnforcer", mock(BridgeAccessEnforcer.class));
        setField(service, "bridgeStreamHeartbeat", heartbeat);
    }

    @Test
    @DisplayName("bridge chat run: heartbeat registered BEFORE the blocking bridge call, released after")
    void bridgeChatIsHeartbeatProtectedForTheWholeDispatch() {
        stubContext("claude-code", "claude-opus-4-6");
        when(bridgeClient.executeViaBridge(any(AgentExecutionRequestDto.class)))
            .thenReturn(successResponse("hello"));

        service.executeStreaming(chatRequest(), streamOutput, "conv-1");

        InOrder order = inOrder(heartbeat, bridgeClient);
        order.verify(heartbeat).register("stream-hb-1");
        order.verify(bridgeClient).executeViaBridge(any(AgentExecutionRequestDto.class));
        order.verify(heartbeat).unregister("stream-hb-1");
    }

    @Test
    @DisplayName("the heartbeat is released even when the bridge call throws (no leaked shield)")
    void heartbeatReleasedWhenBridgeThrows() {
        stubContext("claude-code", "claude-opus-4-6");
        when(bridgeClient.executeViaBridge(any(AgentExecutionRequestDto.class)))
            .thenThrow(new RuntimeException("bridge unreachable"));

        service.executeStreaming(chatRequest(), streamOutput, "conv-1");

        verify(heartbeat).register("stream-hb-1");
        verify(heartbeat).unregister("stream-hb-1");
    }

    @Test
    @DisplayName("remote (non-bridge) chat never touches the heartbeat - agent-service's registry owns that run")
    void remoteChatDoesNotTouchHeartbeat() {
        stubContext("deepseek", "deepseek-chat");
        when(agentClient.executeAgent(any(AgentExecutionRequestDto.class)))
            .thenReturn(successResponse("remote"));

        service.executeStreaming(chatRequest(), streamOutput, "conv-1");

        verify(heartbeat, never()).register(anyString());
        verify(heartbeat, never()).unregister(anyString());
    }

    private void stubContext(String provider, String model) {
        AgentLoopContext context = AgentLoopContext.builder()
            .provider(provider)
            .model(model)
            .userPrompt("hello")
            .systemPrompt("you are helpful")
            .conversationHistory(Collections.emptyList())
            .tools(Collections.emptyList())
            .tenantId("user-1")
            .build();
        when(contextBuilder.build(any(ChatRequest.class), anyString(), anyString(), any())).thenReturn(context);
    }

    private ChatRequest chatRequest() {
        ChatRequest request = new ChatRequest();
        request.setUserId("user-1");
        request.setProvider("claude-code");
        request.setModel("claude-opus-4-6");
        request.setMessage("hello");
        return request;
    }

    private AgentExecutionResponseDto successResponse(String content) {
        return new AgentExecutionResponseDto(
            true, content, content, Collections.emptyList(), 1,
            Map.of("promptTokens", 1, "completionTokens", 1, "totalTokens", 2),
            null, 5L, "claude-code", "claude-opus-4-6",
            Collections.emptyList(), "end_turn",
            Map.of(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
            null, null, null);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }
}
