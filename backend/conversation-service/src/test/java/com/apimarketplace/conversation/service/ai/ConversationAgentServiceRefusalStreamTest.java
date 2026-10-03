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

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CASA LC-056 re-audit: agent-service refuses a bridge run before dispatch when the budget is
 * spent, and answers with the reason in {@code error} only. The chat then received an EMPTY reply
 * (sendDone("")): a blank assistant message, and no word about the budget.
 */
@DisplayName("ConversationAgentService - a refused remote run is shown, not answered blank")
class ConversationAgentServiceRefusalStreamTest {

    private AgentContextBuilder contextBuilder;
    private AgentClient agentClient;
    private StreamingOutput streamOutput;
    private ConversationAgentService service;

    @BeforeEach
    void setUp() throws Exception {
        contextBuilder = mock(AgentContextBuilder.class);
        agentClient = mock(AgentClient.class);
        streamOutput = mock(StreamingOutput.class);
        CreditConsumptionClient creditClient = mock(CreditConsumptionClient.class);
        when(streamOutput.getCurrentStreamId()).thenReturn("stream-r");
        when(creditClient.fetchBalance(anyString())).thenReturn(new java.math.BigDecimal("100"));

        service = new ConversationAgentService(contextBuilder, mock(AgentObservabilityClient.class),
            mock(AgentConfigProvider.class), creditClient, mock(MessageService.class),
            mock(PendingActionService.class), mock(ToolResultService.class), new ObjectMapper(), agentClient,
            mock(StreamStateService.class), mock(EventBus.class), mock(HelpSeenRegistry.class),
            mock(MessageRepository.class), "http://localhost:8087");
        setField("bridgeEnabled", Boolean.FALSE);
        setField("bridgeClient", null);
        setField("bridgeAccessEnforcer", mock(BridgeAccessEnforcer.class));
        when(contextBuilder.build(any(ChatRequest.class), anyString(), anyString(), any()))
            .thenReturn(AgentLoopContext.builder().provider("anthropic").model("claude-fable-5").userPrompt("hi")
                .systemPrompt("s").conversationHistory(Collections.emptyList()).tools(Collections.emptyList())
                .tenantId("user-r").userRoles("USER").build());
    }

    private void setField(String name, Object value) throws Exception {
        Field field = ConversationAgentService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(service, value);
    }

    private static ChatRequest request() {
        ChatRequest request = new ChatRequest();
        request.setUserId("user-r");
        request.setProvider("anthropic");
        request.setModel("claude-fable-5");
        request.setMessage("hi");
        request.setUserRoles("USER");
        return request;
    }

    /** What agent-service answers: refusedBeforeDispatch marks a run it never sent anywhere. */
    private static AgentExecutionResponseDto failed(String error, String stopReason, boolean refusedBeforeDispatch) {
        Map<String, Object> metrics = refusedBeforeDispatch
            ? Map.of(AgentExecutionResponseDto.REFUSED_BEFORE_DISPATCH, true) : Map.of();
        return new AgentExecutionResponseDto(false, null, null, List.of(), 0, Map.of(), error, 5L,
            "anthropic", "claude-fable-5", List.of(), stopReason, metrics, List.of(), List.of(), List.of(),
            List.of(), List.of(), "agent");
    }

    @Test
    @DisplayName("regression: a budget refusal reaches the chat as an error, not as an empty reply")
    void budgetRefusalIsShownAsAnError() {
        when(agentClient.executeAgent(any(AgentExecutionRequestDto.class))).thenReturn(failed(
            "Agent credit budget exhausted: 100.0 of 100.0 credits already used in this budget window, so no agent turn can run.",
            "BUDGET_EXHAUSTED", true));

        service.executeStreaming(request(), streamOutput, "conv-r");

        verify(streamOutput).sendError(org.mockito.ArgumentMatchers.startsWith("Agent credit budget exhausted"));
        verify(streamOutput, never()).sendDone(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a failure the producer already streamed (not a refusal before dispatch) is not reported twice")
    void streamedFailureIsNotReportedAgain() {
        when(agentClient.executeAgent(any(AgentExecutionRequestDto.class)))
            .thenReturn(failed("LLM provider timeout", "ERROR", false));

        service.executeStreaming(request(), streamOutput, "conv-r");

        verify(streamOutput, never()).sendError(anyString());
        verify(streamOutput).sendDone(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a run the user stopped is not reported as an error")
    void userStopIsNotAnError() {
        when(agentClient.executeAgent(any(AgentExecutionRequestDto.class)))
            .thenReturn(failed("Stopped by user", "STOPPED_BY_USER", false));

        service.executeStreaming(request(), streamOutput, "conv-r");

        verify(streamOutput, never()).sendError(anyString());
        verify(streamOutput).sendDone(any(), any(), any(), any(), any());
    }
}
