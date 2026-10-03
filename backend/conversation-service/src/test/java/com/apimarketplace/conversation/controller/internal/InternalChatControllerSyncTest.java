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
    @Mock private com.apimarketplace.conversation.service.ConversationQueryService conversationQueryService;

    private InternalChatController controller;

    @BeforeEach
    void setUp() {
        lenient().when(executionLockService.withConversationLock(any(), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Supplier<ResponseEntity<Map<String, Object>>> action = invocation.getArgument(1);
                    return action.get();
                });
        // Default: the caller may write the conversation. The ownership tests below override it.
        lenient().when(conversationQueryService.isConversationInStrictScope(any(), any(), any())).thenReturn(true);
        controller = new InternalChatController(streamInitializer, agentService, messageService,
                creditClient, observabilityClient, executionLockService, conversationQueryService);
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

            when(creditClient.checkCredits("user-1", "CHAT_CONVERSATION", null, null)).thenReturn(false);

            ResponseEntity<Map<String, Object>> response = controller.chatSync(request, "user-1", "org-1", null, null);

            assertThat(response.getStatusCode().value()).isEqualTo(402);
            assertThat(response.getBody()).containsEntry("success", false)
                    .containsEntry("error", "Insufficient credits")
                    .containsEntry("conversationId", "conv-1");

            ArgumentCaptor<String> errCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageService).persistAttemptAndError(eq("conv-1"),
                    eq("scheduled prompt body"), errCaptor.capture(), org.mockito.ArgumentMatchers.isNull());
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
            // A refused task run is still the run its task points at.
            request.setExecutionId("6f7ccab2-d30e-42b7-a0e6-ff314726967a");
            request.setAgentId("agent-1");
            request.setProvider("claude-code");
            request.setModel("claude-opus-4-7");

            when(creditClient.checkCredits("user-1", "CHAT_CONVERSATION", "claude-code", "claude-opus-4-7")).thenReturn(false);

            controller.chatSync(request, "user-1", "org-1", null, null);

            ArgumentCaptor<String> assistantCaptor = ArgumentCaptor.forClass(String.class);
            verify(observabilityClient).recordFailureAsync(
                    eq("user-1"), eq("org-1"), eq("agent-1"), eq("SCHEDULE"), eq("conv-1"),
                    eq("BUDGET_EXHAUSTED"), eq("Insufficient credits"),
                    eq("scheduled prompt"), assistantCaptor.capture(),
                    eq("claude-code"), eq("claude-opus-4-7"), eq("6f7ccab2-d30e-42b7-a0e6-ff314726967a"));
            assertThat(assistantCaptor.getValue()).startsWith("[Error] Insufficient credits");
        }

        @Test
        @DisplayName("Blank conversationId returns 400 BEFORE the credit check (no persistence attempted)")
        void blankConversationIdReturns400BeforeCreditCheck() {
            ChatRequest request = new ChatRequest();
            request.setConversationId("");
            request.setMessage("hello");

            ResponseEntity<Map<String, Object>> response = controller.chatSync(request, "user-1", null, null, null);

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

            ResponseEntity<Map<String, Object>> response = controller.chatSync(request, "user-1", null, null, null);

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

            when(creditClient.checkCredits("user-1", "CHAT_CONVERSATION", null, null)).thenReturn(true);
            when(agentService.executeSync(any(), eq("conv-1")))
                    .thenReturn(Map.of("success", true, "content", "ok", "conversationId", "conv-1"));

            ResponseEntity<Map<String, Object>> response = controller.chatSync(request, "user-1", "org-1", null, null);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).containsEntry("success", true);

            ArgumentCaptor<MessageDto> captor = ArgumentCaptor.forClass(MessageDto.class);
            verify(messageService).addMessage(eq("conv-1"), captor.capture());
            assertThat(captor.getValue().getRole()).isEqualTo("user");
            verify(agentService).executeSync(any(), eq("conv-1"));
            verify(executionLockService).withConversationLock(eq("conv-1"), any());
        }
    }

    @Nested
    @DisplayName("LC-066: a delegated task written from a restricted execution")
    class Lc066RestrictedTaskMessage {

        private MessageDto storedUserMessage(String dataSensitivity) {
            ChatRequest request = new ChatRequest();
            request.setConversationId("conv-1");
            request.setMessage("task with mail text");
            request.setSource("TASK");
            request.setDataSensitivity(dataSensitivity);
            when(creditClient.checkCredits("user-1", "CHAT_CONVERSATION", null, null)).thenReturn(true);
            when(agentService.executeSync(any(), eq("conv-1"))).thenReturn(Map.of("success", true));

            controller.chatSync(request, "user-1", "org-1", null, null);

            ArgumentCaptor<MessageDto> captor = ArgumentCaptor.forClass(MessageDto.class);
            verify(messageService).addMessage(eq("conv-1"), captor.capture());
            return captor.getValue();
        }

        @Test
        @DisplayName("LC-066: a RESTRICTED task message is stored restricted, so the turn is tagged and provider-checked")
        void lc066RestrictedTaskMessageIsStoredRestricted() {
            assertThat(storedUserMessage("RESTRICTED").getDataSensitivity()).isEqualTo("RESTRICTED");
        }

        @Test
        @DisplayName("LC-066: an ordinary sync message carries no tag (classified as usual, no over-tagging)")
        void lc066OrdinaryMessageIsNotTagged() {
            assertThat(storedUserMessage(null).getDataSensitivity()).isNull();
            org.mockito.Mockito.reset(messageService, agentService, creditClient);
            assertThat(storedUserMessage("NORMAL").getDataSensitivity()).isNull();
        }

        @Test
        @DisplayName("LC-066: the 402 audit trail stores a RESTRICTED task prompt restricted too")
        void lc066RefusedRestrictedPromptIsStoredRestricted() {
            ChatRequest request = new ChatRequest();
            request.setConversationId("conv-1");
            request.setMessage("task with mail text");
            request.setDataSensitivity("RESTRICTED");
            when(creditClient.checkCredits("user-1", "CHAT_CONVERSATION", null, null)).thenReturn(false);

            controller.chatSync(request, "user-1", "org-1", null, null);

            verify(messageService).persistAttemptAndError(eq("conv-1"), eq("task with mail text"), any(),
                    eq("RESTRICTED"));
        }
    }

    @Nested
    @DisplayName("Security: role headers and conversation ownership")
    class SecurityTests {

        @Test
        @DisplayName("regression: orgRole / userRoles reach the agent from the headers, not from the request body")
        void rolesComeFromHeadersNotBody() throws Exception {
            // The body carries forged roles; Jackson must not bind them and the controller must
            // set the header values the trusted caller forwarded.
            ChatRequest request = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                    "{\"conversationId\":\"conv-1\",\"message\":\"hi\",\"orgRole\":\"OWNER\",\"userRoles\":\"admin\"}",
                    ChatRequest.class);
            when(creditClient.checkCredits("user-1", "CHAT_CONVERSATION", null, null)).thenReturn(true);
            when(agentService.executeSync(any(), eq("conv-1"))).thenReturn(Map.of("success", true));

            controller.chatSync(request, "user-1", "org-1", "VIEWER", "user");

            ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
            verify(agentService).executeSync(captor.capture(), eq("conv-1"));
            assertThat(captor.getValue().getOrgRole()).isEqualTo("VIEWER");
            assertThat(captor.getValue().getUserRoles()).isEqualTo("user");
        }

        @Test
        @DisplayName("regression: a caller that may not write the conversation gets 404 and nothing is written or executed")
        void foreignConversationRefused() {
            ChatRequest request = new ChatRequest();
            request.setConversationId("victim-conv");
            request.setMessage("inject");
            when(conversationQueryService.isConversationInStrictScope("victim-conv", "user-1", "org-1")).thenReturn(false);

            ResponseEntity<Map<String, Object>> response = controller.chatSync(request, "user-1", "org-1", null, null);

            assertThat(response.getStatusCode().value()).isEqualTo(404);
            assertThat(response.getBody()).containsEntry("success", false);
            verifyNoInteractions(messageService);
            verifyNoInteractions(agentService);
            verifyNoInteractions(creditClient);
            verifyNoInteractions(executionLockService);
        }

        @Test
        @DisplayName("regression: async internal chat on a conversation outside the header workspace is 404 (strict check through the real initializer)")
        void asyncForeignConversationRefused() {
            com.apimarketplace.conversation.streaming.StreamStateService stateService =
                    org.mockito.Mockito.mock(com.apimarketplace.conversation.streaming.StreamStateService.class);
            com.apimarketplace.conversation.streaming.StreamPubSubService pubSub =
                    org.mockito.Mockito.mock(com.apimarketplace.conversation.streaming.StreamPubSubService.class);
            // Streaming answers normally, so a missing guard shows up as a 200 on the victim's
            // conversation rather than as a mock NPE.
            org.mockito.Mockito.lenient().when(stateService.createStream(any(), any(), any(), any())).thenReturn(reactor.core.publisher.Mono.just(
                    com.apimarketplace.conversation.streaming.StreamMetadata.create("s-x", "user-1", "victim-conv", "gpt-4", null)));
            org.mockito.Mockito.lenient().when(pubSub.publish(any(), any())).thenReturn(reactor.core.publisher.Mono.just(1L));
            ChatStreamInitializer realInitializer = new ChatStreamInitializer(
                    org.mockito.Mockito.mock(com.apimarketplace.conversation.service.ai.ChatStreamingService.class),
                    org.mockito.Mockito.mock(com.apimarketplace.conversation.service.ConversationHistoryService.class),
                    stateService, pubSub, conversationQueryService);
            InternalChatController asyncController = new InternalChatController(realInitializer, agentService,
                    messageService, creditClient, observabilityClient, executionLockService, conversationQueryService);
            ChatRequest request = new ChatRequest();
            request.setMessage("hi");
            request.setModel("gpt-4");
            request.setConversationId("victim-conv");
            when(creditClient.checkCredits("user-1", "CHAT_CONVERSATION", null, "gpt-4")).thenReturn(true);
            when(conversationQueryService.isConversationInStrictScope("victim-conv", "user-1", "org-1"))
                    .thenReturn(false);

            ResponseEntity<Map<String, String>> response =
                    asyncController.chat(request, "user-1", "org-1", null, null).block();

            assertThat(response).isNotNull();
            assertThat(response.getStatusCode().value()).isEqualTo(404);
            verifyNoInteractions(messageService);
        }

        @Test
        @DisplayName("async internal chat also takes orgRole / userRoles from the headers")
        void asyncRolesComeFromHeaders() {
            ChatRequest request = new ChatRequest();
            request.setMessage("hi");
            when(creditClient.checkCredits("user-1", "CHAT_CONVERSATION", null, null)).thenReturn(false);

            controller.chat(request, "user-1", "org-1", "MEMBER", "user").block();

            assertThat(request.getOrgRole()).isEqualTo("MEMBER");
            assertThat(request.getUserRoles()).isEqualTo("user");
        }
    }
}
