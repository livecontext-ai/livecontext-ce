package com.apimarketplace.agent.domain;

import com.apimarketplace.agent.loop.CallPurpose;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Request for LLM completion.
 * Follows the common structure across all LLM providers.
 */
@Builder
public record CompletionRequest(
    /**
     * Tenant ID for rate limiting and usage tracking.
     * Required for per-tenant rate limiting (PER_TENANT or HYBRID strategy).
     */
    String tenantId,

    /**
     * The model to use (e.g., "gpt-4", "claude-3-sonnet", "gemini-pro")
     */
    String model,

    /**
     * System prompt that sets the behavior of the assistant
     */
    String systemPrompt,

    /**
     * The current user message
     */
    String userPrompt,

    /**
     * Conversation history for multi-turn conversations
     */
    List<Message> conversationHistory,

    /**
     * Temperature for randomness (0.0 = deterministic, 2.0 = very random)
     */
    Double temperature,

    /**
     * Maximum tokens to generate
     */
    Integer maxTokens,

    /**
     * Top-p (nucleus) sampling parameter
     */
    Double topP,

    /**
     * Frequency penalty to reduce repetition
     */
    Double frequencyPenalty,

    /**
     * Presence penalty to encourage topic diversity
     */
    Double presencePenalty,

    /**
     * Tools available for the LLM to call
     */
    List<ToolDefinition> tools,

    /**
     * Whether to stream the response
     */
    Boolean stream,

    /**
     * Additional provider-specific metadata
     */
    Map<String, Object> metadata,

    /**
     * Opt-in to provider-side "thinking" output (Gemini 2.5/3.x {@code includeThoughts}).
     * Defaults to {@code false} - thoughts are billed as a separate output counter
     * ({@code thoughtsTokenCount}) and are rarely consumed by callers, so the default
     * keeps output cost down. Set to {@code true} only when the caller actually reads
     * the reasoning payload. Ignored by non-Gemini providers.
     */
    Boolean includeThoughts,

    /**
     * Cap on provider-side reasoning tokens (Gemini 2.5/3.x {@code thinkingBudget}).
     * <ul>
     *   <li>{@code null} - leave unset; Gemini uses its dynamic budget (default).</li>
     *   <li>{@code 0} - disable thinking entirely. Saves up to ~70% of OUTPUT cost
     *   on fast-path tasks (tool routing, extraction) where reasoning adds no
     *   observable quality.</li>
     *   <li>Positive integer - hard cap on {@code thoughtsTokenCount}. Use on
     *   turns where quality matters but bounded latency/cost is required.</li>
     * </ul>
     * <p>Ignored by non-Gemini providers. Combine freely with {@link #includeThoughts}:
     * setting {@code thinkingBudget=0} will still honor an {@code includeThoughts=true}
     * opt-in, but the returned thought payload will simply be empty.
     */
    Integer thinkingBudget,

    /**
     * Coarse intent for reasoning-token spend (Stage 1b). Callers express
     * "fast / balanced / deep" via {@link ThinkingLevel} and the provider maps
     * it to its concrete budget knob. Today only Gemini 2.5/3.x honors it,
     * translating to {@code thinkingConfig.thinkingBudget}.
     *
     * <p>Precedence: explicit {@link #thinkingBudget} always wins - it is the
     * lower-level exact knob. {@code thinkingLevel} is used only when
     * {@code thinkingBudget} is {@code null}. Both {@code null} → provider
     * dynamic default (no cap).
     *
     * <p>Ignored by non-Gemini providers and by older Gemini models (pre-2.5)
     * that do not expose a reasoning budget.
     */
    ThinkingLevel thinkingLevel,

    /**
     * Semantic role of this turn (Stage 1b) - see {@link CallPurpose}. Today
     * this is purely a pass-through label; no provider reads it yet. It feeds
     * adaptive-budget callers that compute
     * {@link ThinkingLevel#auto(CallPurpose, int, int)} at request build time,
     * and leaves a stable slot for Stage 1b.2 Claude-side per-conversation
     * pinning. Until then, unset or any value behaves identically on the wire.
     *
     * <p>Log attribution ({@code [LLM_TURN].purpose}) is fed from
     * {@code AgentLoopContext.purpose()} - the same {@link CallPurpose} value
     * the caller passes here. Keeping both carriers on the same enum type
     * prevents silent drift between cost dashboards and adaptive tiering.
     */
    CallPurpose purpose,

    /**
     * Stage 1a.1 - layered system prompt as an ordered list of slices, so
     * Claude can close prompt-cache segments at discrete boundaries (Anthropic
     * honors up to 4 {@code cache_control} breakpoints per request). Callers
     * that want caching emit blocks in a stable order and mark at most two as
     * breakpoints; non-Claude providers ignore the breakpoints and concatenate
     * block text into their native {@code systemInstruction} / first system
     * message.
     *
     * <p>Precedence with {@link #systemPrompt}: if both are set,
     * {@code systemBlocks} wins on Claude (native array path) and the legacy
     * string is ignored; on non-Claude providers, {@link #effectiveSystemPrompt()}
     * concatenates the blocks, so setting only {@code systemBlocks} is
     * sufficient. Callers migrating should set {@code systemBlocks} and leave
     * {@code systemPrompt} null.
     *
     * <p>{@code null} or empty list → legacy {@link #systemPrompt} path.
     */
    List<SystemBlock> systemBlocks,

    /**
     * Stage 1b.2 - wall-clock timestamp of the previous assistant turn
     * in this conversation, used only by {@code ClaudeProvider} to
     * decide whether to emit the {@code clear_thinking_20251015}
     * context-management edit. The edit fires when the gap between
     * {@link Instant#now} and {@code lastTurnAt} exceeds one hour -
     * cold cache anyway, so dropping the prior thinking turns costs
     * nothing and shortens the replay.
     *
     * <p>{@code null} → treat as "not idle" (no edit emitted). Default
     * on every non-conversation caller. Non-Claude providers ignore
     * the field entirely; adding it here keeps the plumbing in one
     * place rather than branching per provider.
     */
    Instant lastTurnAt,

    /**
     * Resolved categorical reasoning-effort level in canonical wire form
     * ({@code "minimal"|"low"|"medium"|"high"|"xhigh"|"max"}, see
     * {@link ReasoningEffort}). Resolved upstream by
     * {@code ReasoningEffortResolver} (per-conversation override > per-agent >
     * per-model default) and carried verbatim from
     * {@code AgentLoopContext.reasoningEffort()}.
     *
     * <p>Honored by {@code ClaudeProvider} on models that support the Anthropic
     * {@code output_config.effort} parameter (Fable/Mythos, Opus 4.5+,
     * Sonnet 4.6+), clamped to the nearest level the model accepts. Bridge
     * providers receive the level on their own request DTO instead; other
     * direct providers ignore this field. {@code null} → parameter omitted →
     * provider default ({@code high} on the Anthropic API).
     */
    String reasoningEffort
) {
    /**
     * Create a simple request with just a prompt
     */
    public static CompletionRequest simple(String userPrompt) {
        return CompletionRequest.builder()
            .userPrompt(userPrompt)
            .build();
    }

    /**
     * Create a request with system prompt
     */
    public static CompletionRequest withSystem(String systemPrompt, String userPrompt) {
        return CompletionRequest.builder()
            .systemPrompt(systemPrompt)
            .userPrompt(userPrompt)
            .build();
    }

    /**
     * Check if streaming is enabled
     */
    @JsonIgnore
    public boolean isStreaming() {
        return Boolean.TRUE.equals(stream);
    }

    /**
     * Whether the caller explicitly opted in to Gemini thinking output.
     * Defaults to {@code false} when the field is unset.
     */
    @JsonIgnore
    public boolean wantsThoughts() {
        return Boolean.TRUE.equals(includeThoughts);
    }

    /**
     * Whether the caller provided a layered {@link #systemBlocks} list. When
     * {@code true}, Claude uses the native array-with-breakpoints path; other
     * providers should call {@link #effectiveSystemPrompt()} to fold the blocks
     * back into a single string.
     */
    @JsonIgnore
    public boolean hasSystemBlocks() {
        return systemBlocks != null && !systemBlocks.isEmpty();
    }

    /**
     * Returns the caller's system prompt as a single string, regardless of
     * whether it was supplied via the legacy {@link #systemPrompt} field or
     * the new {@link #systemBlocks} list. Concatenation uses {@code "\n\n"}
     * so semantic sections stay readable for providers that don't honor
     * breakpoints (Gemini, OpenAI, Ollama).
     *
     * <p>Precedence: {@code systemBlocks} wins when present (skipping blank
     * blocks so empty optional sections don't leave stray blank lines);
     * otherwise the legacy string is returned verbatim. Returns {@code null}
     * only when both inputs are unset - callers already tolerate null
     * (no system prompt at all).
     */
    @JsonIgnore
    public String effectiveSystemPrompt() {
        if (hasSystemBlocks()) {
            StringBuilder sb = new StringBuilder();
            for (SystemBlock block : systemBlocks) {
                if (block.isBlank()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append("\n\n");
                }
                sb.append(block.text());
            }
            return sb.toString();
        }
        return systemPrompt;
    }
}
