package com.apimarketplace.conversation.service.ai;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.client.dto.execution.AgentExecutionRequestDto;
import com.apimarketplace.agent.loop.AgentLoopContext;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.common.event.EventBus;
import com.apimarketplace.conversation.dto.ChatRequest;
import com.apimarketplace.conversation.dto.MessageDto;
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
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Coverage for {@code ConversationAgentService.executeSync}'s silent-failure
 * branches that recordFailureAsync wires into (commit 3ea5071f4):
 *
 * <ul>
 *   <li>response == null (bridge / queue transport returned nothing)</li>
 *   <li>catch(Exception) - anything thrown during dispatch or persistence</li>
 * </ul>
 *
 * Pre-fix these branches returned a {success=false} map with no DB side
 * effects - the conversation stayed empty and the dashboards showed nothing.
 * Post-fix both branches persist a typed [Error] assistant message AND call
 * AgentObservabilityClient.recordFailureAsync so the attempt shows up in
 * Agent Performance / Fleet with stop reason FAILED.
 */
@DisplayName("ConversationAgentService.executeSync - silent-failure branch coverage")
class ConversationAgentServiceExecuteSyncErrorTest {

    private AgentContextBuilder contextBuilder;
    private AgentObservabilityClient observabilityClient;
    private MessageService messageService;
    private BridgeClient bridgeClient;
    private BridgeAccessEnforcer bridgeAccessEnforcer;
    private CreditConsumptionClient creditClient;
    private AgentClient agentClient;
    private ConversationAgentService service;

    @BeforeEach
    void setUp() throws Exception {
        contextBuilder = mock(AgentContextBuilder.class);
        observabilityClient = mock(AgentObservabilityClient.class);
        messageService = mock(MessageService.class);
        bridgeClient = mock(BridgeClient.class);
        bridgeAccessEnforcer = mock(BridgeAccessEnforcer.class);
        creditClient = mock(CreditConsumptionClient.class);
        agentClient = mock(AgentClient.class);

        when(creditClient.fetchBalance(anyString())).thenReturn(new java.math.BigDecimal("100"));

        service = new ConversationAgentService(
            contextBuilder,
            observabilityClient,
            mock(AgentConfigProvider.class),
            creditClient,
            messageService,
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
        setField(service, "bridgeAccessEnforcer", bridgeAccessEnforcer);
    }

    @Test
    @DisplayName("response == null → persists assistant [Error] + records FAILED execution row")
    void responseNullPersistsErrorAndRecordsFailure() {
        ChatRequest request = bridgeRequest("agent-1", "SCHEDULE", "user-1", "org-1");

        // agentEntityId flows from credentials.__agentId__ (see buildExecutionRequest:615).
        java.util.Map<String, Object> credentials = new java.util.HashMap<>();
        credentials.put("__agentId__", "agent-1");

        AgentLoopContext context = AgentLoopContext.builder()
            .provider("claude-code").model("claude-opus-4-7")
            .userPrompt("scheduled prompt").systemPrompt("system")
            .conversationHistory(Collections.emptyList()).tools(Collections.emptyList())
            .credentials(credentials)
            .tenantId("user-1").build();
        when(contextBuilder.build(any(ChatRequest.class), anyString(), anyString(), any())).thenReturn(context);
        when(bridgeClient.executeViaBridge(any(AgentExecutionRequestDto.class))).thenReturn(null);

        Map<String, Object> result = service.executeSync(request, "conv-1");

        assertThat(result).containsEntry("success", false)
                .containsEntry("error", "Agent execution failed: no response")
                .containsEntry("conversationId", "conv-1");

        ArgumentCaptor<MessageDto> msgCaptor = ArgumentCaptor.forClass(MessageDto.class);
        verify(messageService).addMessage(eq("conv-1"), msgCaptor.capture());
        assertThat(msgCaptor.getValue().getRole()).isEqualTo("assistant");
        assertThat(msgCaptor.getValue().getContent()).startsWith("[Error]");

        verify(observabilityClient).recordFailureAsync(
                eq("user-1"), eq("org-1"), eq("agent-1"), eq("SCHEDULE"), eq("conv-1"),
                eq("FAILED"), eq("Agent execution failed: no response from agent transport"),
                eq("scheduled prompt"),
                eq("[Error] Agent execution failed: no response from agent transport"),
                eq("claude-code"), eq("claude-opus-4-7"));
    }

    @Test
    @DisplayName("bridge throws RuntimeException post-build → records FAILED with RESOLVED dto.provider/model (post-agent-override), not raw request.getProvider/getModel")
    void runtimeExceptionPostBuildUsesResolvedDtoProviderModel() {
        // Pin the dto-vs-request distinction: raw request says (claude-code, claude-opus-4-7),
        // but the agent override resolves to (deepseek, deepseek-chat). Pre-fix the catch
        // branch read request.getProvider/getModel and would have persisted "claude-code"/
        // "claude-opus-4-7" - wrong slug → wrong model chip bucket on the dashboard.
        ChatRequest request = bridgeRequest("agent-2", "WEBHOOK", "user-1", "org-1");

        AgentLoopContext context = AgentLoopContext.builder()
            .provider("deepseek").model("deepseek-chat")
            .userPrompt("webhook prompt").systemPrompt("system")
            .conversationHistory(Collections.emptyList()).tools(Collections.emptyList())
            .credentials(java.util.Map.of("__agentId__", "agent-2"))
            .tenantId("user-1").build();
        when(contextBuilder.build(any(ChatRequest.class), anyString(), anyString(), any())).thenReturn(context);
        // Bridge isn't a target for deepseek - exception fires on remote dispatch path,
        // but the exception still surfaces from inside executeSync's outer try after
        // buildExecutionRequest has populated resolvedDto.
        when(agentClient.executeAgent(any(AgentExecutionRequestDto.class)))
            .thenThrow(new RuntimeException("agent-service unreachable"));

        Map<String, Object> result = service.executeSync(request, "conv-2");

        assertThat(result).containsEntry("success", false)
                .containsEntry("conversationId", "conv-2");
        assertThat(result.get("error").toString()).contains("agent-service unreachable");

        ArgumentCaptor<MessageDto> msgCaptor = ArgumentCaptor.forClass(MessageDto.class);
        verify(messageService).addMessage(eq("conv-2"), msgCaptor.capture());
        assertThat(msgCaptor.getValue().getRole()).isEqualTo("assistant");
        assertThat(msgCaptor.getValue().getContent()).contains("agent-service unreachable");

        // CRITICAL: provider=deepseek + model=deepseek-chat (from RESOLVED dto, NOT
        // from the raw request which carries the pre-override claude-code values).
        verify(observabilityClient).recordFailureAsync(
                eq("user-1"), eq("org-1"), eq("agent-2"), eq("WEBHOOK"), eq("conv-2"),
                eq("FAILED"), eq("agent-service unreachable"),
                eq("prompt body"), eq("[Error] agent-service unreachable"),
                eq("deepseek"), eq("deepseek-chat"));
    }

    private ChatRequest bridgeRequest(String agentId, String source, String userId, String orgId) {
        ChatRequest req = new ChatRequest();
        req.setUserId(userId);
        req.setOrgId(orgId);
        req.setAgentId(agentId);
        req.setProvider("claude-code");
        req.setModel("claude-opus-4-7");
        req.setSource(source);
        req.setMessage("prompt body");
        return req;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = ConversationAgentService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
