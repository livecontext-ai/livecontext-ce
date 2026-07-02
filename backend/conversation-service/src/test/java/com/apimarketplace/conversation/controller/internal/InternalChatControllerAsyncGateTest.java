package com.apimarketplace.conversation.controller.internal;

import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.conversation.controller.v3.chat.ChatStreamInitializer;
import com.apimarketplace.conversation.dto.ChatRequest;
import com.apimarketplace.conversation.service.ConversationExecutionLockService;
import com.apimarketplace.conversation.service.MessageService;
import com.apimarketplace.conversation.service.ai.AgentObservabilityClient;
import com.apimarketplace.conversation.service.ai.ConversationAgentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests for InternalChatController.chat - the ASYNC internal entry point
 * (widgets, service-to-service fire-and-forget). Companion to
 * {@link InternalChatControllerSyncTest}; pins that the async gate carries the
 * same CHAT_CONVERSATION source-type scoping as the sync one, so a FREE user
 * holding monthly workflow-only credits but no PAYG top-up is refused before
 * any stream is initialized.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InternalChatController.chat (async)")
class InternalChatControllerAsyncGateTest {

    @Mock private ChatStreamInitializer streamInitializer;
    @Mock private ConversationAgentService agentService;
    @Mock private MessageService messageService;
    @Mock private CreditConsumptionClient creditClient;
    @Mock private AgentObservabilityClient observabilityClient;
    @Mock private ConversationExecutionLockService executionLockService;

    private InternalChatController controller;

    @BeforeEach
    void setUp() {
        controller = new InternalChatController(streamInitializer, agentService, messageService,
                creditClient, observabilityClient, executionLockService);
    }

    @Test
    @DisplayName("refused scoped check returns 402 and never initializes the stream - regression: the async gate was unscoped (total balance) pre-fix")
    void asyncGateRefusalReturns402WithoutStreamInit() {
        ChatRequest request = new ChatRequest();
        request.setMessage("widget prompt");
        when(creditClient.checkCredits("user-9",
                CreditConsumptionClient.SOURCE_TYPE_CHAT_CONVERSATION)).thenReturn(false);

        Mono<ResponseEntity<Map<String, String>>> mono = controller.chat(request, "user-9", "org-1");
        ResponseEntity<Map<String, String>> response = mono.block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().value()).isEqualTo(402);
        assertThat(response.getBody()).containsEntry("error", "Insufficient credits");
        verifyNoInteractions(streamInitializer);
    }

    @Test
    @DisplayName("allowed scoped check delegates to the stream initializer")
    void asyncGateAllowedInitializesStream() {
        ChatRequest request = new ChatRequest();
        request.setMessage("widget prompt");
        when(creditClient.checkCredits("user-9",
                CreditConsumptionClient.SOURCE_TYPE_CHAT_CONVERSATION)).thenReturn(true);
        Mono<ResponseEntity<Map<String, String>>> initialized =
                Mono.just(ResponseEntity.ok(Map.of("streamId", "s-1")));
        when(streamInitializer.initializeStreamAsync(any(ChatRequest.class), eq("user-9")))
                .thenReturn(initialized);

        ResponseEntity<Map<String, String>> response =
                controller.chat(request, "user-9", "org-1").block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(streamInitializer).initializeStreamAsync(any(ChatRequest.class), eq("user-9"));
    }
}
