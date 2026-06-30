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
            when(agentClient.getModelsInfo(null, "user-1", "org-1")).thenReturn(modelsInfo);

            ResponseEntity<Map<String, Object>> response = chatControllerV3.getAvailableModels("user-1", "org-1");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsKey("models");
        }

        @Test
        @DisplayName("should handle error retrieving models")
        void shouldHandleError() {
            when(agentClient.getModelsInfo(null, "user-1", "org-1"))
                    .thenThrow(new RuntimeException("Provider error"));

            ResponseEntity<Map<String, Object>> response = chatControllerV3.getAvailableModels("user-1", "org-1");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsKey("error");
        }
    }
}
