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

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ConversationAgentService observability config")
class ConversationAgentServiceObservabilityConfigTest {

    private AgentContextBuilder contextBuilder;
    private AgentObservabilityClient observabilityClient;
    private AgentConfigProvider agentConfigProvider;
    private CreditConsumptionClient creditClient;
    private AgentClient agentClient;
    private ConversationAgentService service;

    @BeforeEach
    void setUp() throws Exception {
        contextBuilder = mock(AgentContextBuilder.class);
        observabilityClient = mock(AgentObservabilityClient.class);
        agentConfigProvider = mock(AgentConfigProvider.class);
        creditClient = mock(CreditConsumptionClient.class);
        agentClient = mock(AgentClient.class);

        when(creditClient.fetchBalance(anyString())).thenReturn(new BigDecimal("100"));

        service = new ConversationAgentService(
            contextBuilder,
            observabilityClient,
            agentConfigProvider,
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

        setField(service, "bridgeEnabled", Boolean.FALSE);
    }

    @Test
    @DisplayName("Scheduled org-scoped executions reload agent config with orgId before recording observability")
    void scheduledOrgScopedExecutionRecordsConfigSnapshotWithOrgId() {
        ChatRequest request = new ChatRequest();
        request.setUserId("user-1");
        request.setOrgId("org-1");
        request.setAgentId("agent-1");
        request.setProvider("deepseek");
        request.setModel("deepseek-chat");
        request.setSource("SCHEDULE");
        request.setMessage("scheduled prompt");

        AgentLoopContext context = AgentLoopContext.builder()
            .provider("deepseek")
            .model("deepseek-chat")
            .userPrompt("scheduled prompt")
            .systemPrompt("system prompt")
            .conversationHistory(List.of())
            .tools(List.of())
            .credentials(Map.of("__agentId__", "agent-1"))
            .tenantId("user-1")
            .maxTokens(320)
            .maxIterations(4)
            .executionId("exec-1")
            .build();
        when(contextBuilder.build(any(ChatRequest.class), eq("conv-1"), anyString(), anyString()))
            .thenReturn(context);

        AgentConfigProvider.AgentConfig agentConfig = new AgentConfigProvider.AgentConfig(
            "agent-1",
            "Scheduled Agent",
            "system prompt",
            "deepseek",
            "deepseek-chat",
            0.0,
            320,
            4,
            null,
            null,
            null,
            null,
            null,
            null
        );
        when(agentConfigProvider.getAgentConfig("agent-1", "user-1", "org-1", null))
            .thenReturn(agentConfig);

        AgentExecutionResponseDto response = new AgentExecutionResponseDto(
            true,
            "done",
            "done",
            List.of(),
            3,
            Map.of("promptTokens", 10, "completionTokens", 5, "totalTokens", 15),
            null,
            100L,
            "deepseek",
            "deepseek-chat",
            List.of(),
            "COMPLETED",
            Map.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            null
        );
        when(agentClient.executeAgent(any(AgentExecutionRequestDto.class))).thenReturn(response);

        service.executeSync(request, "conv-1");

        verify(agentConfigProvider).getAgentConfig("agent-1", "user-1", "org-1", null);
        verify(observabilityClient).recordAsync(
            eq("user-1"),
            eq("org-1"),
            eq("agent-1"),
            eq(response),
            eq("system prompt"),
            eq("scheduled prompt"),
            eq("conv-1"),
            eq(agentConfig),
            eq("SCHEDULE"),
            isNull(),
            eq("exec-1")
        );
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = ConversationAgentService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
