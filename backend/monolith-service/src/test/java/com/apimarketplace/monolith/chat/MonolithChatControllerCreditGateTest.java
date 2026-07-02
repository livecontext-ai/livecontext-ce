package com.apimarketplace.monolith.chat;

import com.apimarketplace.agent.factory.LLMProviderFactory;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.conversation.dto.ChatRequest;
import com.apimarketplace.conversation.service.ConversationHistoryService;
import com.apimarketplace.conversation.service.ai.ChatStreamingService;
import com.apimarketplace.conversation.streaming.StreamingOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Covers the CE chat credit gate short-circuit at the unit/MockMvc layer.
 *
 * <p>This 402 "Insufficient credits" path is UNREACHABLE in CE e2e because
 * {@code application-ce.yml} sets {@code credit.unlimited=true}, which makes
 * {@link CreditConsumptionClient#checkCredits(String, String)} always return {@code true}
 * (see {@code CreditConsumptionClient}: {@code if (!enabled) return true;}). The
 * e2e suite therefore never exercises the rejection branch - it must be proven
 * here at the controller layer with a mocked credit client.
 *
 * <p>Controller under test: {@link MonolithChatController}, mapped at
 * {@code POST /api/v3/chat} ({@code @RequestMapping("/api/v3/chat")} +
 * {@code @PostMapping}). The {@code userId} flows from the {@code X-User-ID}
 * request header. The gate is source-type-scoped (regression: an unscoped
 * total-balance check let a FREE user's monthly workflow-only credits admit
 * chat turns):
 * <pre>{@code
 *   if (!creditClient.checkCredits(userId, "CHAT_CONVERSATION")) {
 *       return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
 *               .body(Map.of("error", "Insufficient credits"));
 *   }
 * }</pre>
 *
 * <p>Mirrors the sibling {@code MonolithChatControllerTest}: standalone
 * construction (no Spring context), collaborators mocked with Mockito, the
 * controller method invoked directly, and the async streaming hand-off verified
 * with {@code timeout(...)} because it runs on a virtual thread.
 */
@DisplayName("MonolithChatController credit gate")
class MonolithChatControllerCreditGateTest {

    private final ConversationHistoryService conversationHistoryService = mock(ConversationHistoryService.class);
    private final ChatStreamingService chatStreamingService = mock(ChatStreamingService.class);
    private final CreditConsumptionClient creditClient = mock(CreditConsumptionClient.class);
    private final LLMProviderFactory llmProviderFactory = mock(LLMProviderFactory.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final MonolithChatController controller = new MonolithChatController(
            conversationHistoryService,
            chatStreamingService,
            creditClient,
            llmProviderFactory,
            redisTemplate,
            new ObjectMapper());

    private ChatRequest newChatRequest() {
        ChatRequest request = new ChatRequest();
        request.setMessage("Ping the CE agent");
        request.setProvider("deepseek");
        request.setModel("deepseek-chat");
        return request;
    }

    @Test
    @DisplayName("returns 402 {\"error\":\"Insufficient credits\"} and starts NO conversation/stream when checkCredits is false")
    void rejectsWithPaymentRequiredAndNoDownstreamSideEffectsWhenCreditsExhausted() {
        // Arrange - credit client denies; downstream collaborators must never run.
        ChatRequest request = newChatRequest();
        // resolveDefaultModel(userId) runs BEFORE the gate, so getAllModelsInfo() is stubbed.
        when(llmProviderFactory.getAllModelsInfo()).thenReturn(Map.of(
                "defaultProvider", "deepseek",
                "defaultModel", "deepseek-chat"));
        when(creditClient.checkCredits("user-broke", "CHAT_CONVERSATION")).thenReturn(false);

        // Act
        var response = controller.chat(request, "user-broke", "org-7", "OWNER", null);

        // Assert - 402 with the exact error body.
        assertThat(response.getStatusCode().value()).isEqualTo(402);
        assertThat(response.getBody()).containsEntry("error", "Insufficient credits");

        // The gate was actually consulted for this user.
        // Pinned sourceType: the chat gate MUST be scoped to CHAT_CONVERSATION.
        verify(creditClient).checkCredits("user-broke", "CHAT_CONVERSATION");

        // No conversation is created and no skill selection is persisted.
        verifyNoInteractions(conversationHistoryService);
        // No streaming is started and no stream_started event is published.
        verifyNoInteractions(chatStreamingService);
        verifyNoInteractions(redisTemplate);
    }

    @Test
    @DisplayName("positive control: when checkCredits is true the request proceeds past the gate (creates a conversation and starts the stream)")
    void proceedsPastGateWhenCreditsAvailable() {
        // Arrange - credit client allows; downstream collaborators should be reached.
        ChatRequest request = newChatRequest();
        when(llmProviderFactory.getAllModelsInfo()).thenReturn(Map.of(
                "defaultProvider", "deepseek",
                "defaultModel", "deepseek-chat"));
        when(creditClient.checkCredits("user-ok", "CHAT_CONVERSATION")).thenReturn(true);
        when(conversationHistoryService.createConversation(
                anyString(), any(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn("conv-ok");

        // Act
        var response = controller.chat(request, "user-ok", "org-7", "OWNER", null);

        // Assert - NOT 402; the request advanced past the gate.
        assertThat(response.getStatusCode().value()).isNotEqualTo(402);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).containsEntry("conversationId", "conv-ok");

        verify(creditClient).checkCredits("user-ok", "CHAT_CONVERSATION");
        // Reached the downstream collaborators that the gate would have blocked.
        verify(conversationHistoryService).createConversation(
                anyString(), any(), anyString(), anyString(), anyString(), any(), any());
        // Streaming hand-off runs on a virtual thread → wait for it.
        verify(chatStreamingService, timeout(1000))
                .processStreamingRequest(any(ChatRequest.class), any(StreamingOutput.class), any(String.class));
    }

    @Test
    @DisplayName("rejection short-circuits BEFORE conversation create even for an existing conversation id (no skill persistence)")
    void rejectsExistingConversationWithoutPersistingSkills() {
        // Arrange - an existing-conversation request that also carries a skill
        // selection; the gate must reject before persistDefaultSkillIds runs.
        ChatRequest request = newChatRequest();
        request.setConversationId("conv-existing");
        request.setDefaultSkillIds(java.util.List.of("skill-9"));
        when(llmProviderFactory.getAllModelsInfo()).thenReturn(Map.of(
                "defaultProvider", "deepseek",
                "defaultModel", "deepseek-chat"));
        when(creditClient.checkCredits("user-broke", "CHAT_CONVERSATION")).thenReturn(false);

        // Act
        var response = controller.chat(request, "user-broke", "org-7", "MEMBER", null);

        // Assert
        assertThat(response.getStatusCode().value()).isEqualTo(402);
        assertThat(response.getBody()).containsEntry("error", "Insufficient credits");
        // Pinned sourceType: the chat gate MUST be scoped to CHAT_CONVERSATION.
        verify(creditClient).checkCredits("user-broke", "CHAT_CONVERSATION");
        verify(conversationHistoryService, never()).persistDefaultSkillIds(any(), any(), any(), any());
        verifyNoInteractions(conversationHistoryService);
        verifyNoInteractions(chatStreamingService);
    }
}
