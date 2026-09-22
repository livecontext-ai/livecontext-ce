package com.apimarketplace.conversation.controller.v3;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.conversation.controller.v3.chat.ChatBudgetEstimator;
import com.apimarketplace.conversation.controller.v3.chat.ChatStreamInitializer;
import com.apimarketplace.conversation.controller.v3.chat.StreamStopHandler;
import com.apimarketplace.conversation.dto.ChatRequest;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("ChatControllerV3")
@ExtendWith(MockitoExtension.class)
class ChatControllerV3Test {

    @Mock
    private ChatStreamInitializer streamInitializer;

    @Mock
    private StreamStopHandler stopHandler;

    @Mock
    private AgentClient agentClient;

    @Mock
    private CreditConsumptionClient creditClient;

    @Mock
    private ChatBudgetEstimator budgetEstimator;

    @InjectMocks
    private ChatControllerV3 chatControllerV3;

    private ChatBudgetEstimator.Estimate anEstimate(String provider, String model) {
        return new ChatBudgetEstimator.Estimate(provider, model, 4100, 8192);
    }

    @Nested
    @DisplayName("chatJson (WebSocket flow)")
    class ChatJsonTests {

        @Test
        @DisplayName("should return JSON response with conversationId, streamId, and model")
        void shouldReturnJsonResponse() {
            ChatRequest request = new ChatRequest();
            request.setMessage("Hello");
            request.setModel("gpt-4");
            request.setProvider("openai");

            when(budgetEstimator.estimate(any(ChatRequest.class)))
                    .thenReturn(anEstimate("openai", "gpt-4"));
            when(creditClient.checkChatBudget(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                    .thenReturn(true);

            ResponseEntity<Map<String, String>> expectedResponse = ResponseEntity.ok(Map.of(
                    "conversationId", "conv-123",
                    "streamId", "stream-456",
                    "model", "gpt-4"
            ));

            when(streamInitializer.initializeStreamAsync(any(ChatRequest.class), eq("user-1")))
                    .thenReturn(Mono.just(expectedResponse));

            Mono<ResponseEntity<Map<String, String>>> result = chatControllerV3.chatJson(request, "user-1", null, null, null);

            StepVerifier.create(result)
                    .assertNext(response -> {
                        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                        assertThat(response.getBody()).containsEntry("conversationId", "conv-123");
                        assertThat(response.getBody()).containsEntry("streamId", "stream-456");
                        assertThat(response.getBody()).containsEntry("model", "gpt-4");
                    })
                    .verifyComplete();

            verify(streamInitializer).initializeStreamAsync(any(ChatRequest.class), eq("user-1"));
        }

        @Test
        @DisplayName("should set userId on request")
        void shouldSetUserId() {
            ChatRequest request = new ChatRequest();
            request.setMessage("Hello");
            request.setModel("gpt-4");
            request.setProvider("openai");

            when(budgetEstimator.estimate(any(ChatRequest.class)))
                    .thenReturn(anEstimate("openai", "gpt-4"));
            when(creditClient.checkChatBudget(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                    .thenReturn(true);
            when(streamInitializer.initializeStreamAsync(any(), any()))
                    .thenReturn(Mono.just(ResponseEntity.ok(Map.of())));

            chatControllerV3.chatJson(request, "user-123", null, null, null).block();

            assertThat(request.getUserId()).isEqualTo("user-123");
        }

        @Test
        @DisplayName("should reject with 402 when estimated cost exceeds balance")
        void shouldRejectWhenEstimatedCostExceedsBalance() {
            ChatRequest request = new ChatRequest();
            request.setMessage("Hello");
            request.setModel("claude-sonnet-4-6");
            request.setProvider("claude-code");

            when(budgetEstimator.estimate(any(ChatRequest.class)))
                    .thenReturn(anEstimate("claude-code", "claude-sonnet-4-6"));
            when(creditClient.checkChatBudget(
                    eq("user-1"), eq("claude-code"), eq("claude-sonnet-4-6"), anyInt(), anyInt()))
                    .thenReturn(false);

            Mono<ResponseEntity<Map<String, String>>> result =
                    chatControllerV3.chatJson(request, "user-1", null, null, null);

            StepVerifier.create(result)
                    .assertNext(response -> {
                        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
                        assertThat(response.getBody()).containsEntry("error", "Insufficient credits");
                    })
                    .verifyComplete();

            // Critical: inference MUST NOT be dispatched when pre-flight says 'no'.
            // Otherwise the user gets a free answer and the ledger stays un-debited
            // - the regression this whole fix exists to prevent.
            verify(streamInitializer, never()).initializeStreamAsync(any(), any());
        }

        @Test
        @DisplayName("should reject oversized payload before budget check and stream dispatch")
        void shouldRejectOversizedPayloadBeforeBudgetAndStream() {
            ChatRequest request = new ChatRequest();
            request.setMessage("x".repeat(20_000));
            request.setModel("gpt-4");
            request.setProvider("openai");

            when(budgetEstimator.validatePayload(any(ChatRequest.class)))
                    .thenReturn(ChatBudgetEstimator.PayloadValidation.invalid("Message is too large"));

            Mono<ResponseEntity<Map<String, String>>> result =
                    chatControllerV3.chatJson(request, "user-1", null, null, null);

            StepVerifier.create(result)
                    .assertNext(response -> {
                        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
                        assertThat(response.getBody()).containsEntry("error", "Message is too large");
                    })
                    .verifyComplete();

            verify(budgetEstimator, never()).estimate(any(ChatRequest.class));
            verify(creditClient, never()).checkChatBudget(anyString(), anyString(), anyString(), anyInt(), anyInt());
            verify(streamInitializer, never()).initializeStreamAsync(any(), any());
        }

        @Test
        @DisplayName("should pass provider/model/estimated-tokens from estimator to credit client")
        void shouldForwardEstimateToCreditClient() {
            ChatRequest request = new ChatRequest();
            request.setMessage("Hello");
            request.setModel("gpt-4");
            request.setProvider("openai");

            when(budgetEstimator.estimate(any(ChatRequest.class)))
                    .thenReturn(new ChatBudgetEstimator.Estimate("openai", "gpt-4", 12345, 8192));
            when(creditClient.checkChatBudget(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                    .thenReturn(true);
            when(streamInitializer.initializeStreamAsync(any(), any()))
                    .thenReturn(Mono.just(ResponseEntity.ok(Map.of())));

            chatControllerV3.chatJson(request, "user-1", null, null, null).block();

            verify(creditClient).checkChatBudget("user-1", "openai", "gpt-4", 12345, 8192);
        }
    }

    @Nested
    @DisplayName("stopStream")
    class StopStreamTests {

        @Test
        @DisplayName("should return OK on successful stop")
        void shouldReturnOkOnSuccess() {
            StreamStopHandler.StopResult stopResult = new StreamStopHandler.StopResult(
                    true, "Stopped", "conv-1", "stream-1", 1, 0
            );
            Map<String, Object> responseMap = Map.of("success", true, "message", "Stopped");

            when(stopHandler.stopStream("user-1", "conv-1", "org-1")).thenReturn(stopResult);
            when(stopHandler.toResponseMap(stopResult)).thenReturn(responseMap);

            ResponseEntity<Map<String, Object>> response = chatControllerV3.stopStream(
                    "user-1", "org-1", Map.of("conversationId", "conv-1")
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("success", true);
        }

        @Test
        @DisplayName("should return bad request when conversation ID is missing")
        void shouldReturnBadRequestWhenMissing() {
            StreamStopHandler.StopResult stopResult = new StreamStopHandler.StopResult(
                    false, "Conversation ID is required", null, null, 0, 0
            );
            Map<String, Object> responseMap = Map.of("success", false, "message", "Conversation ID is required");

            when(stopHandler.stopStream("user-1", null, "org-1")).thenReturn(stopResult);
            when(stopHandler.toResponseMap(stopResult)).thenReturn(responseMap);

            Map<String, String> requestBody = new java.util.HashMap<>();
            requestBody.put("conversationId", null);

            ResponseEntity<Map<String, Object>> response = chatControllerV3.stopStream(
                    "user-1", "org-1", requestBody
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("should return 500 on other failure")
        void shouldReturn500OnOtherFailure() {
            StreamStopHandler.StopResult stopResult = new StreamStopHandler.StopResult(
                    false, "Internal error", "conv-1", null, 0, 0
            );
            Map<String, Object> responseMap = Map.of("success", false, "message", "Internal error");

            when(stopHandler.stopStream("user-1", "conv-1", "org-1")).thenReturn(stopResult);
            when(stopHandler.toResponseMap(stopResult)).thenReturn(responseMap);

            ResponseEntity<Map<String, Object>> response = chatControllerV3.stopStream(
                    "user-1", "org-1", Map.of("conversationId", "conv-1")
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Nested
    @DisplayName("getAvailableModels")
    class GetAvailableModelsTests {

        @Test
        @DisplayName("should return models info")
        void shouldReturnModelsInfo() {
            Map<String, Object> modelsInfo = Map.of(
                    "models", Map.of("gpt-4", Map.of("provider", "openai"))
            );
            when(agentClient.getModelsInfo(null, "user-1", "org-1", false, true)).thenReturn(modelsInfo);

            ResponseEntity<Map<String, Object>> response =
                    chatControllerV3.getAvailableModels(null, "user-1", "org-1", "USER");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsKey("models");
        }

        @Test
        @DisplayName("a signed-in ADMIN is neither a public read nor bridge-trimmed: the catalogue comes back whole")
        void signedInAdminGetsTheWholeCatalogue() {
            when(agentClient.getModelsInfo(null, "user-1", "org-1", false, false)).thenReturn(Map.of());

            chatControllerV3.getAvailableModels(null, "user-1", "org-1", "ADMIN");

            // An admin administers the execution links THROUGH this payload, so trimming the
            // bridges here would empty the panel that points a billed pair at a CLI.
            verify(agentClient).getModelsInfo(null, "user-1", "org-1", false, false);
        }

        @Test
        @DisplayName("the classification slice is forwarded, which is how a classify picker reaches a decision model")
        void forwardsTheClassificationCategory() {
            // The chat slice deliberately excludes decision models, and a client-side filter
            // can only subtract from what arrives. Forwarding this category is therefore the
            // only way the classify inspector ever sees one - re-hardcoding null here would
            // leave the whole frontend union as dead code.
            when(agentClient.getModelsInfo("classification", "user-1", "org-1", false, true))
                    .thenReturn(Map.of());

            chatControllerV3.getAvailableModels("classification", "user-1", "org-1", "USER");

            verify(agentClient).getModelsInfo("classification", "user-1", "org-1", false, true);
        }

        @Test
        @DisplayName("an unknown category is ignored rather than forwarded, and answers the chat slice")
        void unknownCategoryFallsBackToChat() {
            // This endpoint is reachable without a token and the eligibility rule downstream
            // is permissive for a category it does not recognise, so forwarding an arbitrary
            // value would have answered with the WHOLE catalogue to an anonymous caller.
            when(agentClient.getModelsInfo(null, null, null, true, false)).thenReturn(Map.of());

            chatControllerV3.getAvailableModels("../../anything", null, null, "USER");

            verify(agentClient).getModelsInfo(null, null, null, true, false);
        }

        @Test
        @DisplayName("a signed-in caller is NOT a public read, so the catalogue comes back whole")
        void signedInCallerIsNotAPublicRead() {
            when(agentClient.getModelsInfo(null, "user-1", "org-1", false, true)).thenReturn(Map.of());

            chatControllerV3.getAvailableModels(null, "user-1", "org-1", "USER");

            // The point here is publicRead, which stays false for a signed-in caller: a public
            // read also widens the catalogue to providers holding no key. The bridge trimming
            // next to it is a separate rule, asserted on its own below.
            verify(agentClient).getModelsInfo(null, "user-1", "org-1", false, true);
        }

        @Test
        @DisplayName("a signed-in NON-admin never receives the CLI bridges, and the server is what decides it")
        void signedInUserNeverReceivesTheBridges() {
            when(agentClient.getModelsInfo(null, "user-1", "org-1", false, true)).thenReturn(Map.of());

            chatControllerV3.getAvailableModels(null, "user-1", "org-1", "USER");

            // The CLIs run on the operator's own subscription and must never be named to an end
            // user. This was a frontend-only guarantee until 2026-09-18: the payload carried
            // them to every signed-in user and one picker that forgot the filtering hook would
            // have shown them. hideBridges without publicRead: lose the bridges, keep the
            // availability filter.
            verify(agentClient).getModelsInfo(null, "user-1", "org-1", false, true);
        }

        @Test
        @DisplayName("a missing role header is treated as an ordinary user, not as an admin")
        void anAbsentRoleHeaderIsNotAnAdmin() {
            when(agentClient.getModelsInfo(null, "user-1", "org-1", false, true)).thenReturn(Map.of());

            // What Spring passes when the gateway sent no X-User-Roles at all.
            chatControllerV3.getAvailableModels(null, "user-1", "org-1", "USER");

            verify(agentClient).getModelsInfo(null, "user-1", "org-1", false, true);
        }

        @Test
        @DisplayName("an anonymous caller IS a public read, and says so explicitly")
        void anonymousCallerDeclaresAPublicRead() {
            when(agentClient.getModelsInfo(null, null, null, true, false)).thenReturn(Map.of());

            chatControllerV3.getAvailableModels(null, null, null, "USER");

            // Declared here rather than inferred in agent-service from a missing X-User-ID: that
            // header is equally absent on internal calls made off a request thread, so inferring
            // would trim the catalogue for node validation on a scheduled run and not on a chat
            // request. This controller is the one place that genuinely knows.
            // hideBridges stays false: publicRead already drops them, and sending both would
            // say the same thing twice on the wire.
            verify(agentClient).getModelsInfo(null, null, null, true, false);
        }

        @Test
        @DisplayName("should handle error retrieving models")
        void shouldHandleError() {
            when(agentClient.getModelsInfo(null, "user-1", "org-1", false, true))
                    .thenThrow(new RuntimeException("Provider error"));

            ResponseEntity<Map<String, Object>> response =
                    chatControllerV3.getAvailableModels(null, "user-1", "org-1", "USER");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsKey("error");
        }
    }
}
