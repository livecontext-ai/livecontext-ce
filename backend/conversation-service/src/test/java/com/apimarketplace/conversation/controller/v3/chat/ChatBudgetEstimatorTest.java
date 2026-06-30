package com.apimarketplace.conversation.controller.v3.chat;

import com.apimarketplace.conversation.dto.ChatRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ChatBudgetEstimator")
class ChatBudgetEstimatorTest {

    private final ChatBudgetEstimator estimator =
            new ChatBudgetEstimator(4000, 8192);

    @Test
    @DisplayName("returns provider/model straight from the request")
    void propagatesProviderAndModel() {
        ChatRequest req = new ChatRequest();
        req.setProvider("claude-code");
        req.setModel("claude-sonnet-4-6");
        req.setMessage("hi");

        ChatBudgetEstimator.Estimate e = estimator.estimate(req);

        assertThat(e.provider()).isEqualTo("claude-code");
        assertThat(e.model()).isEqualTo("claude-sonnet-4-6");
    }

    @Test
    @DisplayName("empty message still returns at least the prompt-overhead floor")
    void emptyMessageReturnsOverheadFloor() {
        ChatRequest req = new ChatRequest();
        req.setMessage(null);
        req.setProvider("openai");
        req.setModel("gpt-4");

        ChatBudgetEstimator.Estimate e = estimator.estimate(req);

        // No user text → estimate reduces to the configured overhead. This guarantees we never
        // pass 0 prompt tokens to the pricing service, which would defeat the cost gate.
        assertThat(e.estimatedPromptTokens()).isEqualTo(4000);
        assertThat(e.estimatedCompletionTokens()).isEqualTo(8192);
    }

    @Test
    @DisplayName("message chars are converted to tokens with safety multiplier + overhead")
    void appliesSafetyMultiplierAndOverhead() {
        ChatRequest req = new ChatRequest();
        // 300 chars / 3 chars-per-token = 100 raw tokens * 1.3 safety = 130, + 4000 overhead = 4130
        req.setMessage("x".repeat(300));
        req.setProvider("openai");
        req.setModel("gpt-4");

        ChatBudgetEstimator.Estimate e = estimator.estimate(req);

        assertThat(e.estimatedPromptTokens()).isEqualTo(4130);
    }

    @Test
    @DisplayName("includes conversation history in the prompt estimate")
    void includesConversationHistory() {
        ChatRequest req = new ChatRequest();
        req.setMessage("x".repeat(300));
        req.setProvider("openai");
        req.setModel("gpt-4");

        ChatRequest.ChatMessage hist1 = new ChatRequest.ChatMessage();
        hist1.setContent("y".repeat(300));
        ChatRequest.ChatMessage hist2 = new ChatRequest.ChatMessage();
        hist2.setContent("z".repeat(300));
        req.setConversationHistory(List.of(hist1, hist2));

        ChatBudgetEstimator.Estimate e = estimator.estimate(req);

        // 900 chars / 3 = 300 raw * 1.3 = 390 + 4000 overhead = 4390.
        // If history were ignored we'd get 4130 - the 260-token gap proves history was counted.
        assertThat(e.estimatedPromptTokens()).isEqualTo(4390);
    }

    @Test
    @DisplayName("tolerates null history entries and null content")
    void tolerantOfNulls() {
        ChatRequest req = new ChatRequest();
        req.setProvider("openai");
        req.setModel("gpt-4");
        req.setMessage("hello");

        ChatRequest.ChatMessage nullContent = new ChatRequest.ChatMessage();
        nullContent.setContent(null);
        req.setConversationHistory(java.util.Arrays.asList(nullContent, null));

        ChatBudgetEstimator.Estimate e = estimator.estimate(req);

        // No throw; estimate completes on a degraded-but-safe path.
        assertThat(e.estimatedPromptTokens()).isGreaterThanOrEqualTo(4000);
        assertThat(e.estimatedCompletionTokens()).isEqualTo(8192);
    }

    @Test
    @DisplayName("negative overhead config is clamped to zero")
    void clampsNegativeOverhead() {
        ChatBudgetEstimator badConfig = new ChatBudgetEstimator(-5, 8192);
        ChatRequest req = new ChatRequest();
        req.setMessage(null);

        ChatBudgetEstimator.Estimate e = badConfig.estimate(req);

        assertThat(e.estimatedPromptTokens()).isEqualTo(0);
    }

    @Test
    @DisplayName("zero/negative max-completion is clamped to at least 1")
    void clampsNonPositiveMaxCompletion() {
        ChatBudgetEstimator badConfig = new ChatBudgetEstimator(4000, 0);
        ChatRequest req = new ChatRequest();
        req.setMessage("hi");

        ChatBudgetEstimator.Estimate e = badConfig.estimate(req);

        assertThat(e.estimatedCompletionTokens()).isEqualTo(1);
    }

    @Test
    @DisplayName("rejects messages above the configured character cap")
    void rejectsOversizedMessage() {
        ChatBudgetEstimator capped = new ChatBudgetEstimator(4000, 8192, 10, 100, 10);
        ChatRequest req = new ChatRequest();
        req.setMessage("x".repeat(11));

        ChatBudgetEstimator.PayloadValidation validation = capped.validatePayload(req);

        assertThat(validation.valid()).isFalse();
        assertThat(validation.error()).isEqualTo("Message is too large");
    }

    @Test
    @DisplayName("rejects oversized conversation history before estimation")
    void rejectsOversizedHistory() {
        ChatBudgetEstimator capped = new ChatBudgetEstimator(4000, 8192, 100, 10, 10);
        ChatRequest.ChatMessage hist = new ChatRequest.ChatMessage();
        hist.setContent("x".repeat(11));
        ChatRequest req = new ChatRequest();
        req.setMessage("ok");
        req.setConversationHistory(List.of(hist));

        ChatBudgetEstimator.PayloadValidation validation = capped.validatePayload(req);

        assertThat(validation.valid()).isFalse();
        assertThat(validation.error()).isEqualTo("Conversation history is too large");
    }

    @Test
    @DisplayName("rejects too many history messages")
    void rejectsTooManyHistoryMessages() {
        ChatBudgetEstimator capped = new ChatBudgetEstimator(4000, 8192, 100, 1000, 1);
        ChatRequest.ChatMessage hist1 = new ChatRequest.ChatMessage();
        hist1.setContent("a");
        ChatRequest.ChatMessage hist2 = new ChatRequest.ChatMessage();
        hist2.setContent("b");
        ChatRequest req = new ChatRequest();
        req.setMessage("ok");
        req.setConversationHistory(List.of(hist1, hist2));

        ChatBudgetEstimator.PayloadValidation validation = capped.validatePayload(req);

        assertThat(validation.valid()).isFalse();
        assertThat(validation.error()).isEqualTo("Conversation history has too many messages");
    }
}
