package com.apimarketplace.agent.tokenizer;

import com.apimarketplace.agent.domain.CompletionRequest;

/**
 * Stage 1a.4 - pluggable token estimator used by {@code AbstractLLMProvider} for
 * rate-limit preflight, budget checks, and token-cost telemetry. The estimator
 * returns a <em>projected total</em> - the sum of prompt tokens (system prompt,
 * user prompt, conversation history, serialized tool schemas) plus reserved
 * completion tokens (the caller's {@code maxTokens} or the implementation's
 * default).
 *
 * <p>Implementations:
 * <ul>
 *   <li>{@link JtokkitTokenEstimator} - cl100k_base via Jtokkit, accurate within
 *   ~5% for OpenAI and within ~10% for Claude/Gemini on prose. Default.</li>
 *   <li>{@link HeuristicTokenEstimator} - legacy chars/4 approximation, kept for
 *   environments where Jtokkit is unavailable or disabled.</li>
 * </ul>
 *
 * <p>Implementations MUST be thread-safe: providers call this on every request.
 */
public interface TokenEstimator {

    /**
     * Estimate the total tokens (prompt + reserved completion) for {@code request}.
     *
     * @param request the completion request (never {@code null})
     * @return non-negative estimated token count; never negative, never throws
     */
    int estimate(CompletionRequest request);

    /**
     * Short identifier for telemetry ({@code "jtokkit"}, {@code "heuristic"}, …).
     */
    String name();
}
