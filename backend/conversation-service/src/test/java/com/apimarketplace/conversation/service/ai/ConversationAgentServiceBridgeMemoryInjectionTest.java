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
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The chat path that never touches agent-service still gets long-term memory.
 *
 * <p>When the chat model is a CLI provider, this service posts to the bridge
 * itself: agent-service is not in the loop, so the injection that every other
 * execution receives on the way in cannot happen. That is the default for every
 * claude-code / codex chat, and it shipped without memory until this was added,
 * with nothing red to show for it - each path built a valid prompt and every run
 * was green.
 *
 * <p>A source scan cannot catch that class of bug: it can see that the call is
 * written, not that its result is used. So this drives the real method and reads
 * the prompt off the DTO the bridge is handed.
 */
@DisplayName("ConversationAgentService - the bridge chat path carries long-term memory")
class ConversationAgentServiceBridgeMemoryInjectionTest {

    private AgentContextBuilder contextBuilder;
    private AgentClient agentClient;
    private BridgeClient bridgeClient;
    private StreamingOutput streamOutput;
    private ConversationAgentService service;

    @BeforeEach
    void setUp() throws Exception {
        contextBuilder = mock(AgentContextBuilder.class);
        agentClient = mock(AgentClient.class);
        bridgeClient = mock(BridgeClient.class);
        streamOutput = mock(StreamingOutput.class);
        CreditConsumptionClient creditClient = mock(CreditConsumptionClient.class);

        when(streamOutput.getCurrentStreamId()).thenReturn("stream-mem-1");
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
        setField(service, "bridgeStreamHeartbeat", mock(BridgeStreamHeartbeat.class));
    }

    @Test
    @DisplayName("the enriched prompt, not the original, is what the bridge is asked to run")
    void theBridgeReceivesTheEnrichedPrompt() {
        stubContext("claude-code", "claude-opus-4-6");
        when(agentClient.appendMemoryBlock(anyString(), anyString(), any(), any()))
            .thenReturn("you are helpful\n\nPROMPT-WITH-MEMORY-BLOCK");
        when(bridgeClient.executeViaBridge(any(AgentExecutionRequestDto.class), any()))
            .thenReturn(successResponse("hello"));

        service.executeStreaming(chatRequest(), streamOutput, "conv-1");

        ArgumentCaptor<AgentExecutionRequestDto> dispatched =
            ArgumentCaptor.forClass(AgentExecutionRequestDto.class);
        verify(bridgeClient).executeViaBridge(dispatched.capture(), any());
        // Calling appendMemoryBlock and discarding the result would satisfy any
        // source-level guard and change nothing for the model. The assertion is on
        // what the bridge was actually handed.
        assertThat(dispatched.getValue().systemPrompt()).contains("PROMPT-WITH-MEMORY-BLOCK");
    }

    @Test
    @DisplayName("the workspace it looks memory up in is the one bound to the request")
    void itScopesTheLookupToTheRequestWorkspace() {
        stubContext("claude-code", "claude-opus-4-6");
        when(agentClient.appendMemoryBlock(anyString(), anyString(), any(), any()))
            .thenAnswer(inv -> inv.getArgument(0));
        when(bridgeClient.executeViaBridge(any(AgentExecutionRequestDto.class), any()))
            .thenReturn(successResponse("hello"));

        // NO runWithOrgScope. This dispatch runs on an async stream worker where the
        // request context is gone, so binding it here would test a situation the
        // production path does not have - and would keep passing if the code went
        // back to reading the thread.
        ChatRequest request = chatRequest();
        request.setOrgId("org-alpha");
        service.executeStreaming(request, streamOutput, "conv-1");

        // The workspace decides WHICH memories are recalled, so getting it wrong is
        // not a degraded run, it is the wrong workspace's facts (or none). It is
        // threaded from the ChatRequest, the way every other workspace-dependent call
        // in this class takes it.
        ArgumentCaptor<String> org = ArgumentCaptor.forClass(String.class);
        verify(agentClient).appendMemoryBlock(anyString(), anyString(), org.capture(), any());
        assertThat(org.getValue()).isEqualTo("org-alpha");
    }

    @Test
    @DisplayName("the prompt it enriches is the one the context builder produced")
    void itEnrichesTheAssembledPrompt() {
        stubContext("claude-code", "claude-opus-4-6");
        when(agentClient.appendMemoryBlock(anyString(), anyString(), any(), any()))
            .thenAnswer(inv -> inv.getArgument(0));
        when(bridgeClient.executeViaBridge(any(AgentExecutionRequestDto.class), any()))
            .thenReturn(successResponse("hello"));

        service.executeStreaming(chatRequest(), streamOutput, "conv-1");

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(agentClient).appendMemoryBlock(prompt.capture(), anyString(), any(), any());
        // Enriching something other than the assembled prompt - an empty string, a
        // stale copy - would still put a block on the wire and still lose everything
        // the rest of the prompt says.
        assertThat(prompt.getValue()).isEqualTo("you are helpful");
    }

    @Test
    @DisplayName("a memory lookup that fails leaves the chat running on the prompt it already had")
    void aFailedLookupDoesNotBreakTheChat() {
        stubContext("claude-code", "claude-opus-4-6");
        // THROWS. The previous version stubbed the call to echo its input, so nothing
        // ever failed and the test could not fail either - it asserted a resilience
        // property while exercising the happy path. The client does swallow its own
        // errors today; this pins that the service does not DEPEND on that, because
        // the call sits directly in front of a user's message.
        when(agentClient.appendMemoryBlock(anyString(), anyString(), any(), any()))
            .thenThrow(new RuntimeException("agent-service unreachable"));
        when(bridgeClient.executeViaBridge(any(AgentExecutionRequestDto.class), any()))
            .thenReturn(successResponse("hello"));

        service.executeStreaming(chatRequest(), streamOutput, "conv-1");

        ArgumentCaptor<AgentExecutionRequestDto> dispatched =
            ArgumentCaptor.forClass(AgentExecutionRequestDto.class);
        verify(bridgeClient).executeViaBridge(dispatched.capture(), any());
        assertThat(dispatched.getValue().systemPrompt()).isEqualTo("you are helpful");
    }

    @Test
    @DisplayName("a remote (non-bridge) chat does NOT enrich here, or the block would be added twice")
    void theRemotePathIsLeftAlone() {
        stubContext("deepseek", "deepseek-chat");
        when(agentClient.executeAgent(any(AgentExecutionRequestDto.class)))
            .thenReturn(successResponse("remote"));

        service.executeStreaming(chatRequest(), streamOutput, "conv-1");

        // agent-service appends the block for everything dispatched to it. Doing it
        // here as well would put two memory blocks in one prompt, and the second
        // would be the one the model reads last.
        verify(agentClient, never()).appendMemoryBlock(anyString(), anyString(), any(), any());
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
