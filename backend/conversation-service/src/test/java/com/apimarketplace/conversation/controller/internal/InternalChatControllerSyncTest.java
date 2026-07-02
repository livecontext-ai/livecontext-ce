package com.apimarketplace.conversation.controller.internal;

import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.conversation.controller.v3.chat.ChatStreamInitializer;
import com.apimarketplace.conversation.dto.ChatRequest;
import com.apimarketplace.conversation.dto.MessageDto;
import com.apimarketplace.conversation.service.ConversationExecutionLockService;
import com.apimarketplace.conversation.service.MessageService;
import com.apimarketplace.conversation.service.ai.AgentObservabilityClient;
import com.apimarketplace.conversation.service.ai.ConversationAgentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests for InternalChatController.chatSync - the sync entry point used by
 * schedule, webhook, agent task, and widget callers. Regression coverage for
 * the silent-402 bug where insufficient credits returned an empty conversation
 * with no indication of why nothing happened.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InternalChatController.chatSync")
class InternalChatControllerSyncTest {

    @Mock private ChatStreamInitializer streamInitializer;
    @Mock private ConversationAgentService agentService;
    @Mock private MessageService messageService;
    @Mock private CreditConsumptionClient creditClient;
    @Mock private AgentObservabilityClient observabilityClient;
    @Mock private ConversationExecutionLockService executionLockService;

    private InternalChatController controller;

    @BeforeEach
    void setUp() {
        lenient().when(executionLockService.withConversationLock(any(), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Supplier<ResponseEntity<Map<String, Object>>> action = invocation.getArgument(1);
                    return action.get();
                });
        controller = new InternalChatController(streamInitializer, agentService, messageService,
                creditClient, observabilityClient, executionLockService);
    }

    @Nested
    @DisplayName("Insufficient credits - regression: previously returned 402 with no DB side effects")
    class InsufficientCreditsTests {

        @Test
        @DisplayName("402 delegates to MessageService.persistAttemptAndError with the user prompt + Insufficient credits text")
        void insufficientCreditsPersistsAttemptAndError() {
            ChatRequest request = new ChatRequest();
            request.setConversationId("conv-1");
            request.setMessage("scheduled prompt body");
            request.setSource("SCHEDULE");

            when(creditClient.checkCredits("user-1", "CHAT_CONVERSATION")).thenReturn(false);

            ResponseEntity<Map<String, Object>> response = controller.chatSync(request, "user-1", "org-1");

            assertThat(response.getStatusCode().value()).isEqualTo(402);
            assertThat(response.getBody()).containsEntry("success", false)
                    .containsEntry("error", "Insufficient credits")
                    .containsEntry("conversationId", "conv-1");

            ArgumentCaptor<String> errCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageService).persistAttemptAndError(eq("conv-1"),
                    eq("scheduled prompt body"), errCaptor.capture());
            assertThat(errCaptor.getValue()).startsWith("[Error] Insufficient credits");

            // agentService.executeSync MUST NOT run on the 402 path - that's the
            // whole point of short-circuiting before the agent loop. But we DO
            // want publishFleetFailureNoExecution to fire so the Fleet view sees
            // the throttled attempt. Verify the precise interaction shape rather
            // than the broader verifyNoInteractions which conflates the two.
            verify(agentService, org.mockito.Mockito.never())
                    .executeSync(any(), any());
            verify(agentService).publishFleetFailureNoExecution(
                    org.mockito.ArgumentMatchers.isNull(),  // request.getAgentId() not set in this test
                    org.mockito.ArgumentMatchers.isNull(),  // request.getModel() not set
                    eq("SCHEDULE"),
                    org.mockito.ArgumentMatchers.isNull(),  // request.getTaskId() not set
                    eq("FAILED"), eq(0L));
            verify(executionLockService).withConversationLock(eq("conv-1"), any());
        }

        @Test
        @DisplayName("402 also records a FAILED execution so Agent Performance / Fleet show the throttled attempt with stop reason BUDGET_EXHAUSTED, threading the user prompt + assistant error message + provider+model so the model chip aggregate includes the row")
        void insufficientCreditsRecordsFailedExecution() {
            ChatRequest request = new ChatRequest();
            request.setConversationId("conv-1");
            request.setMessage("scheduled prompt");
            request.setSource("SCHEDULE");
            request.setAgentId("agent-1");
            request.setProvider("claude-code");
            request.setModel("claude-opus-4-7");

            when(creditClient.checkCredits("user-1", "CHAT_CONVERSATION")).thenReturn(false);

            controller.chatSync(request, "user-1", "org-1");

            ArgumentCaptor<String> assistantCaptor = ArgumentCaptor.forClass(String.class);
            verify(observabilityClient).recordFailureAsync(
                    eq("user-1"), eq("org-1"), eq("agent-1"), eq("SCHEDULE"), eq("conv-1"),
                    eq("BUDGET_EXHAUSTED"), eq("Insufficient credits"),
                    eq("scheduled prompt"), assistantCaptor.capture(),
                    eq("claude-code"), eq("claude-opus-4-7"));
            assertThat(assistantCaptor.getValue()).startsWith("[Error] Insufficient credits");
        }

        @Test
        @DisplayName("Blank conversationId returns 400 BEFORE the credit check (no persistence attempted)")
        void blankConversationIdReturns400BeforeCreditCheck() {
            ChatRequest request = new ChatRequest();
            request.setConversationId("");
            request.setMessage("hello");

            ResponseEntity<Map<String, Object>> response = controller.chatSync(request, "user-1", null);

            assertThat(response.getStatusCode().value()).isEqualTo(400);
            verifyNoInteractions(creditClient);
            verifyNoInteractions(messageService);
            verifyNoInteractions(agentService);
        }

        @Test
        @DisplayName("Null conversationId returns 400 - same shape as blank")
        void nullConversationIdReturns400() {
            ChatRequest request = new ChatRequest();
            request.setConversationId(null);
            request.setMessage("hello");

            ResponseEntity<Map<String, Object>> response = controller.chatSync(request, "user-1", null);

            assertThat(response.getStatusCode().value()).isEqualTo(400);
            verifyNoInteractions(creditClient);
            verifyNoInteractions(messageService);
            verifyNoInteractions(agentService);
        }

        // Persistence-failure-doesn't-mask-402 was previously tested by stubbing
        // messageService.addMessage to throw; now the persistence is encapsulated
        // in MessageService.persistAttemptAndError which has its own internal
        // try/catch. The test moves to MessageServiceTest (out of scope here) -
        // controller-side defense is no longer needed.
    }

    @Nested
    @DisplayName("Happy path - credits OK")
    class HappyPathTests {

        @Test
        @DisplayName("User message saved then executeSync dispatched")
        void happyPathSavesUserMessageThenDispatches() {
            ChatRequest request = new ChatRequest();
            request.setConversationId("conv-1");
            request.setMessage("hello");
            request.setSource("WEBHOOK");

            when(creditClient.checkCredits("user-1", "CHAT_CONVERSATION")).thenReturn(true);
            when(agentService.executeSync(any(), eq("conv-1")))
                    .thenReturn(Map.of("success", true, "content", "ok", "conversationId", "conv-1"));

            ResponseEntity<Map<String, Object>> response = controller.chatSync(request, "user-1", "org-1");

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).containsEntry("success", true);

            ArgumentCaptor<MessageDto> captor = ArgumentCaptor.forClass(MessageDto.class);
            verify(messageService).addMessage(eq("conv-1"), captor.capture());
            assertThat(captor.getValue().getRole()).isEqualTo("user");
            verify(agentService).executeSync(any(), eq("conv-1"));
            verify(executionLockService).withConversationLock(eq("conv-1"), any());
        }
    }
}
