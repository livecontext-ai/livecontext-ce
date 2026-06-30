package com.apimarketplace.agent.tokenizer;

import com.apimarketplace.agent.domain.CompletionRequest;
import com.apimarketplace.agent.domain.Message;
import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.domain.ToolParameter;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stage 1a.4 - Jtokkit-based token estimator using the {@code cl100k_base}
 * encoding (the one OpenAI's gpt-4 family and Claude's tokenizer are both
 * <em>closest</em> to). Accurate within ~5% for OpenAI on English prose, ~8-12%
 * for Claude, and ~15% for Gemini (which uses a different sentence-piece
 * tokenizer but no public Java port exists).
 *
 * <p>Used for:
 * <ul>
 *   <li>Rate-limit preflight: providers reserve estimated tokens before the API
 *   call; cl100k is close enough to the real count that rate limits no longer
 *   over-count by 30% (which chars/4 does on structured JSON).</li>
 *   <li>Budget cost preview in the agent loop (Stage 0 telemetry).</li>
 * </ul>
 *
 * <p>Thread-safe: Jtokkit's {@code Encoding} is thread-safe by design (per the
 * library's documented contract); one instance is held per process.
 *
 * <p>Selected by default when {@code ai.token-estimator.mode} is unset OR set to
 * {@code jtokkit}. To fall back to the chars/4 heuristic, set
 * {@code ai.token-estimator.mode=heuristic}.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "ai.token-estimator.mode", havingValue = "jtokkit", matchIfMissing = true)
public class JtokkitTokenEstimator implements TokenEstimator {

    private static final int DEFAULT_MAX_COMPLETION_TOKENS = 500;

    /**
     * Rough per-tool overhead: each tool adds {@code name + description + schema},
     * but we only tokenize {@code name + description} directly and approximate
     * the schema wrapper ({@code "type":"object","properties":{...},"required":[]})
     * at this many tokens per parameter. Calibrated against a 20-tool fixture.
     */
    private static final int TOKENS_PER_PARAMETER = 8;

    /** Fixed overhead per tool for the JSON schema wrapper + function entry. */
    private static final int TOKENS_PER_TOOL_ENVELOPE = 16;

    /**
     * Per-provider multiplicative correction applied to the cl100k_base prompt
     * count. cl100k was tuned for OpenAI; it under-counts Claude by ~8-12% and
     * Gemini by ~15% on typical mixed prose+JSON payloads. These multipliers
     * close the gap so rate-limit preflight stops leaving headroom on the table.
     *
     * <p>Applied to PROMPT tokens only - {@code maxTokens} is a caller-specified
     * cap and needs no correction.
     *
     * <p>Stage 1 defaults sourced from the Javadoc accuracy envelope above.
     * Monthly calibration (Stage 1a.4 piece D) will open PRs to bump these
     * values when production {@code usage.input_tokens} vs jtokkit estimate
     * drifts &gt; 10%.
     */
    static final double OPENAI_BIAS = 1.00;
    static final double CLAUDE_BIAS = 1.10;
    static final double GEMINI_BIAS = 1.15;

    // volatile so that threads hitting the estimator concurrently with
    // @PostConstruct lifecycle see a fully-published Encoding reference. Jtokkit's
    // Encoding itself is thread-safe (library contract); we only need to guarantee
    // the publication of this field.
    private volatile Encoding encoding;

    @PostConstruct
    void init() {
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        this.encoding = registry.getEncoding(EncodingType.CL100K_BASE);
        log.info("Jtokkit token estimator initialized (cl100k_base).");
    }

    @Override
    public int estimate(CompletionRequest request) {
        int promptTokens = 0;

        String systemForCount = request.effectiveSystemPrompt();
        if (systemForCount != null) {
            promptTokens += countTokens(systemForCount);
        }
        if (request.userPrompt() != null) {
            promptTokens += countTokens(request.userPrompt());
        }
        if (request.conversationHistory() != null) {
            for (Message msg : request.conversationHistory()) {
                if (msg.content() != null) {
                    promptTokens += countTokens(msg.content());
                }
            }
        }
        if (request.tools() != null) {
            for (ToolDefinition tool : request.tools()) {
                promptTokens += countToolTokens(tool);
            }
        }

        int maxTokens = request.maxTokens() != null ? request.maxTokens() : DEFAULT_MAX_COMPLETION_TOKENS;
        int biasedPromptTokens = (int) Math.round(promptTokens * biasFor(request.model()));
        return biasedPromptTokens + maxTokens;
    }

    /**
     * Provider-family detection from model string. Prefix match on well-known
     * families; unknown models fall through to the OpenAI baseline (1.0× bias),
     * which matches the cl100k tokenizer's own calibration and avoids inflating
     * counts for a family we don't recognize.
     */
    static double biasFor(String model) {
        if (model == null) return OPENAI_BIAS;
        String lower = model.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("claude-")) return CLAUDE_BIAS;
        if (lower.startsWith("gemini-")) return GEMINI_BIAS;
        return OPENAI_BIAS;
    }

    /** Safe token count for a nullable string; never negative. */
    int countTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return encoding.countTokens(text);
    }

    /**
     * Tokens for one tool = name + description + envelope + per-parameter cost.
     * We intentionally do NOT JSON-serialize the schema here - the serialization
     * cost (~1ms per call × thousands per pod) would dwarf the accuracy win. The
     * envelope constant covers {@code {"type":"function","function":{...}}} and
     * {@code {"type":"object","properties":{...}}} wrappers that are identical
     * across tools.
     */
    int countToolTokens(ToolDefinition tool) {
        int tokens = TOKENS_PER_TOOL_ENVELOPE;
        if (tool.name() != null) {
            tokens += countTokens(tool.name());
        }
        if (tool.description() != null) {
            tokens += countTokens(tool.description());
        }
        if (tool.parameters() != null) {
            for (ToolParameter param : tool.parameters()) {
                tokens += TOKENS_PER_PARAMETER;
                if (param.name() != null) {
                    tokens += countTokens(param.name());
                }
                if (param.description() != null) {
                    tokens += countTokens(param.description());
                }
            }
        }
        return tokens;
    }

    @Override
    public String name() {
        return "jtokkit";
    }
}
