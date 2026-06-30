package com.apimarketplace.conversation.controller.v3.chat;

import com.apimarketplace.conversation.dto.ChatRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Pre-flight token estimator for a chat turn.
 *
 * <p>Produces a conservative upper-bound estimate of the prompt + completion tokens
 * that the upcoming turn will spend on the LLM, used by the cost-aware chat budget
 * gate in {@link com.apimarketplace.conversation.controller.v3.ChatControllerV3}.
 *
 * <p>This is deliberately over-estimating rather than under-estimating: the goal is
 * to refuse a turn whose actual consumption would exceed the user's balance, so we
 * need to bound cost from ABOVE, not guess the median. Under-estimating would
 * reintroduce the 402-after-inference bug where the user gets a free answer.
 *
 * <p>Inputs used:
 * <ul>
 *   <li>Message body length + every history message body length, in characters.</li>
 *   <li>A per-turn system-prompt + tool-definitions overhead ({@code tokensOverhead}),
 *       because chat turns include tool/agent scaffolding that the client can't see
 *       but is charged for by the provider.</li>
 *   <li>A fixed worst-case completion ceiling ({@code maxCompletionTokens}).</li>
 * </ul>
 *
 * <p>All tunables are properties so ops can re-tune without a code change.
 */
@Component
public class ChatBudgetEstimator {

    /** Conservative chars-per-token. Actual English avg is ~4; use 3 so we bias high. */
    private static final double CHARS_PER_TOKEN = 3.0;

    /** Safety multiplier applied to character-derived token estimates. */
    private static final double SAFETY_MULTIPLIER = 1.3;

    private final int tokensOverhead;
    private final int maxCompletionTokens;
    private final int maxMessageChars;
    private final int maxHistoryChars;
    private final int maxHistoryMessages;

    @Autowired
    public ChatBudgetEstimator(
            @Value("${chat.budget.prompt-overhead-tokens:4000}") int tokensOverhead,
            @Value("${chat.budget.max-completion-tokens:8192}") int maxCompletionTokens,
            @Value("${chat.budget.max-message-chars:16000}") int maxMessageChars,
            @Value("${chat.budget.max-history-chars:64000}") int maxHistoryChars,
            @Value("${chat.budget.max-history-messages:100}") int maxHistoryMessages) {
        this.tokensOverhead = Math.max(0, tokensOverhead);
        this.maxCompletionTokens = Math.max(1, maxCompletionTokens);
        this.maxMessageChars = Math.max(1, maxMessageChars);
        this.maxHistoryChars = Math.max(0, maxHistoryChars);
        this.maxHistoryMessages = Math.max(0, maxHistoryMessages);
    }

    public ChatBudgetEstimator(int tokensOverhead, int maxCompletionTokens) {
        this(tokensOverhead, maxCompletionTokens, 16_000, 64_000, 100);
    }

    public PayloadValidation validatePayload(ChatRequest request) {
        if (request == null) {
            return PayloadValidation.invalid("Request body is required");
        }
        String message = request.getMessage();
        if (message != null && message.length() > maxMessageChars) {
            return PayloadValidation.invalid("Message is too large");
        }

        List<ChatRequest.ChatMessage> history = request.getConversationHistory();
        if (history == null || history.isEmpty()) {
            return PayloadValidation.ok();
        }
        if (history.size() > maxHistoryMessages) {
            return PayloadValidation.invalid("Conversation history has too many messages");
        }
        int historyChars = 0;
        for (ChatRequest.ChatMessage m : history) {
            if (m != null && m.getContent() != null) {
                historyChars += m.getContent().length();
                if (historyChars > maxHistoryChars) {
                    return PayloadValidation.invalid("Conversation history is too large");
                }
            }
        }
        return PayloadValidation.ok();
    }

    public Estimate estimate(ChatRequest request) {
        int chars = 0;
        if (request.getMessage() != null) {
            chars += request.getMessage().length();
        }
        List<ChatRequest.ChatMessage> history = request.getConversationHistory();
        if (history != null) {
            for (ChatRequest.ChatMessage m : history) {
                if (m != null && m.getContent() != null) {
                    chars += m.getContent().length();
                }
            }
        }
        int rawPromptTokens = (int) Math.ceil(chars / CHARS_PER_TOKEN);
        int safePromptTokens = (int) Math.ceil(rawPromptTokens * SAFETY_MULTIPLIER);
        int totalPromptTokens = safePromptTokens + tokensOverhead;

        return new Estimate(
                request.getProvider(),
                request.getModel(),
                totalPromptTokens,
                maxCompletionTokens);
    }

    public record Estimate(
            String provider,
            String model,
            int estimatedPromptTokens,
            int estimatedCompletionTokens
    ) {
    }

    public record PayloadValidation(boolean valid, String error) {
        public static PayloadValidation ok() {
            return new PayloadValidation(true, null);
        }

        public static PayloadValidation invalid(String error) {
            return new PayloadValidation(false, error);
        }
    }
}
