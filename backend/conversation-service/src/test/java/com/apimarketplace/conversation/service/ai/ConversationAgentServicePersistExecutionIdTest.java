package com.apimarketplace.conversation.service.ai;

import com.apimarketplace.agent.client.dto.execution.AgentExecutionResponseDto;
import com.apimarketplace.conversation.dto.ChatRequest;
import com.apimarketplace.conversation.dto.MessageDto;
import com.apimarketplace.conversation.service.MessageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression for the Conversation Activity card: the assistant message a chat turn
 * persists MUST carry the turn's {@code executionId} (== agent_executions.id).
 *
 * <p>The card aggregates a conversation by execution and fetches each execution's
 * observability metrics (tokens / iterations / credits / status) via that id. Before
 * the fix {@code persistRemoteResults} saved the assistant message WITHOUT the
 * executionId, so {@code messages.execution_id} was null - the card could only
 * group by turn boundary and "full metrics always" never resolved (no id to query).
 *
 * <p>Exercises the real private {@code persistRemoteResults} via reflection and
 * captures the {@link MessageDto} handed to {@link MessageService#addMessage}.
 */
@DisplayName("ConversationAgentService.persistRemoteResults - stamps executionId on the assistant message")
class ConversationAgentServicePersistExecutionIdTest {

    private ConversationAgentService service;
    private MessageService messageService;
    private Method persistRemoteResults;

    @BeforeEach
    void setUp() throws Exception {
        messageService = Mockito.mock(MessageService.class);
        service = new ConversationAgentService(
            Mockito.mock(com.apimarketplace.conversation.service.ai.callback.AgentContextBuilder.class),
            Mockito.mock(AgentObservabilityClient.class),
            Mockito.mock(AgentConfigProvider.class),
            Mockito.mock(com.apimarketplace.common.credit.CreditConsumptionClient.class),
            messageService,
            Mockito.mock(com.apimarketplace.conversation.service.PendingActionService.class),
            Mockito.mock(com.apimarketplace.conversation.service.ToolResultService.class),
            new ObjectMapper(),
            Mockito.mock(com.apimarketplace.agent.client.AgentClient.class),
            Mockito.mock(com.apimarketplace.conversation.streaming.StreamStateService.class),
            Mockito.mock(com.apimarketplace.common.event.EventBus.class),
            Mockito.mock(com.apimarketplace.conversation.service.ai.schema.HelpSeenRegistry.class),
            Mockito.mock(com.apimarketplace.conversation.repository.MessageRepository.class),
            "http://localhost:8087"
        );
        persistRemoteResults = ConversationAgentService.class.getDeclaredMethod(
            "persistRemoteResults",
            ChatRequest.class, String.class, AgentExecutionResponseDto.class, String.class, String.class);
        persistRemoteResults.setAccessible(true);
    }

    private AgentExecutionResponseDto contentResponse() {
        // Minimal response with content so persistRemoteResults writes one assistant row.
        return new AgentExecutionResponseDto(
            true, null, "hello world", null, 1, null, null, 1000L,
            "deepseek", "deepseek-chat", null, null, null, null, null, null, null, null, null);
    }

    private MessageDto capturePersistedMessage(String executionId) throws Exception {
        ChatRequest request = Mockito.mock(ChatRequest.class);
        when(request.getUserId()).thenReturn("user-1");
        persistRemoteResults.invoke(service, request, "conv-1", contentResponse(), null, executionId);
        ArgumentCaptor<MessageDto> captor = ArgumentCaptor.forClass(MessageDto.class);
        verify(messageService).addMessage(eq("conv-1"), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("Assistant message is stamped with the turn's executionId (so the activity card can fetch its metrics)")
    void stampsExecutionIdOnAssistantMessage() throws Exception {
        MessageDto saved = capturePersistedMessage("exec-123");
        assertThat(saved.getRole()).isEqualTo("assistant");
        assertThat(saved.getExecutionId())
            .as("execution_id must be persisted so the Conversation Activity card groups by execution and resolves metrics")
            .isEqualTo("exec-123");
    }

    @Test
    @DisplayName("A null executionId is tolerated (turn still persists; grouping falls back to the turn boundary)")
    void nullExecutionIdIsTolerated() throws Exception {
        MessageDto saved = capturePersistedMessage(null);
        assertThat(saved.getExecutionId()).isNull();
        assertThat(saved.getContent()).isEqualTo("hello world");
    }
}
