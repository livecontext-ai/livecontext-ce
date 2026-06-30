package com.apimarketplace.agent.tokenizer;

import com.apimarketplace.agent.domain.CompletionRequest;
import com.apimarketplace.agent.domain.Message;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Legacy chars/4 estimator. Selected when {@code ai.token-estimator.mode=heuristic}.
 * Kept for:
 * <ul>
 *   <li>Environments without the Jtokkit dependency on the classpath.</li>
 *   <li>Fallback when {@link JtokkitTokenEstimator} initialization fails.</li>
 *   <li>Differential tests that need the pre-1a.4 estimate as a reference line.</li>
 * </ul>
 *
 * <p>Known biases: over-counts for ASCII English (real ratio ~3.3 chars/token),
 * under-counts for Chinese / Japanese / emoji-heavy content, and severely
 * under-counts JSON tool schemas (real ratio ~2.5 chars/token).
 */
@Component
@ConditionalOnProperty(name = "ai.token-estimator.mode", havingValue = "heuristic")
public class HeuristicTokenEstimator implements TokenEstimator {

    private static final int DEFAULT_MAX_COMPLETION_TOKENS = 500;
    private static final int APPROX_TOOL_SCHEMA_CHARS = 200;
    private static final int CHARS_PER_TOKEN = 4;

    @Override
    public int estimate(CompletionRequest request) {
        int totalChars = 0;

        String systemForCount = request.effectiveSystemPrompt();
        if (systemForCount != null) {
            totalChars += systemForCount.length();
        }
        if (request.userPrompt() != null) {
            totalChars += request.userPrompt().length();
        }
        if (request.conversationHistory() != null) {
            for (Message msg : request.conversationHistory()) {
                if (msg.content() != null) {
                    totalChars += msg.content().length();
                }
            }
        }
        if (request.tools() != null) {
            totalChars += request.tools().size() * APPROX_TOOL_SCHEMA_CHARS;
        }

        int promptTokens = totalChars / CHARS_PER_TOKEN;
        int maxTokens = request.maxTokens() != null ? request.maxTokens() : DEFAULT_MAX_COMPLETION_TOKENS;
        return promptTokens + maxTokens;
    }

    @Override
    public String name() {
        return "heuristic";
    }
}
