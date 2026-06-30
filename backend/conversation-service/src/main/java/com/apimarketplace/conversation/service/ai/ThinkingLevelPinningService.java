package com.apimarketplace.conversation.service.ai;

import com.apimarketplace.agent.domain.ThinkingLevel;
import com.apimarketplace.agent.loop.CallPurpose;
import com.apimarketplace.conversation.repository.ConversationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;

/**
 * Stage 1b.1 - per-conversation pinning of {@link ThinkingLevel} for Claude.
 *
 * <p><b>Why pin.</b> Anthropic's prompt cache is invalidated when the
 * {@code thinking} parameter flips across turns (the cached prefix covers
 * system + messages; flipping thinking tokens invalidates that segment of
 * the cache, though the separate tools cache is preserved). The adaptive
 * resolver in {@code AgentLoopExecutor} otherwise maps "hi" → LOW and a
 * follow-up long question → HIGH within the same conversation, paying the
 * cache-invalidation cost on every flip. Pinning the first MAIN turn's
 * resolved tier holds the prefix stable for the rest of the conversation.
 *
 * <p><b>Scope.</b>
 * <ul>
 *   <li><b>Claude + MAIN</b> - read the pinned value; if absent, compute once
 *   from turn shape and persist (race-tolerant {@code UPDATE … WHERE
 *   thinking_level_pinned IS NULL}). Return the pinned tier.</li>
 *   <li><b>Claude + CLASSIFY/GUARDRAIL</b> - return {@link ThinkingLevel#MEDIUM}
 *   without persisting. These are short-lived side calls whose cache state
 *   is independent of the main conversation; pinning them would wrongly
 *   propagate MEDIUM back into the MAIN conversation on future turns.</li>
 *   <li><b>Non-Claude (gemini / openai / …)</b> - return {@code null}, which
 *   tells {@code AgentLoopExecutor.resolveAdaptiveThinkingLevel} to
 *   auto-resolve per-iteration. Gemini's {@code thinkingConfig} lives in
 *   {@code generationConfig} and does not invalidate {@code cachedContent};
 *   OpenAI ignores the field entirely - flipping is safe on both.</li>
 * </ul>
 *
 * <p><b>Detection.</b> Claude is identified by provider name {@code "anthropic"}
 * (the value {@code ClaudeProvider#getName()} returns) or model prefix
 * {@code claude-}. Both forms flow through callers today (some paths pass
 * the provider registry name, others pass the model string alone). The prefix
 * fallback may also match proxy routings that carry a {@code claude-*} model
 * string through a non-Anthropic backend; pinning in that case is a
 * <b>deliberate over-pin</b>. The cost asymmetry justifies it: under-pinning
 * (flipping thinking on a real Claude cache) invalidates a 100k-token prefix
 * on every turn; over-pinning (stabilising a tier we didn't need to stabilise)
 * is free - the resolver still returns a valid tier and no cache is harmed.
 *
 * <p><b>Persistence.</b> Column {@code conversation.conversations.thinking_level_pinned}
 * (V104 migration). VARCHAR(16) holding the {@link ThinkingLevel#name()}.
 * {@code null} = not yet pinned.
 *
 * <p><b>Race tolerance.</b> {@code pinThinkingLevelIfAbsent} only writes on
 * NULL, so concurrent first-turn requests (e.g., two retries) converge on
 * whichever writer's UPDATE lands first; later writers are silent no-ops
 * and subsequent reads see the already-pinned value. The lost-race re-read
 * only sees the winner's commit under {@code READ COMMITTED} isolation -
 * repeatable-read would still observe NULL. Postgres default (which this
 * service runs on) is {@code READ COMMITTED}, so the pattern holds.
 *
 * <p><b>Transactions.</b> The read is a single SELECT (no tx needed) and the
 * conditional write carries its own {@code @Transactional} at the repository
 * layer. No method-level transaction here - the common "already pinned" path
 * (every turn after the first) stays read-only and pays no BEGIN/COMMIT.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ThinkingLevelPinningService {

    private final ConversationRepository conversationRepository;

    /**
     * Resolve the effective {@link ThinkingLevel} for this turn, pinning on
     * the first Claude MAIN resolution.
     *
     * @param conversationId  conversation row id; {@code null} → no pinning
     *                        (one-shot call with no conversation backing it)
     * @param provider        provider registry name, e.g. {@code "anthropic"}
     * @param model           model string, e.g. {@code "claude-sonnet-4-5"}
     * @param purpose         {@link CallPurpose} of this turn; {@code null}
     *                        is treated as {@link CallPurpose#MAIN}
     * @param toolCount       number of tools attached to the request
     * @param userMsgChars    character length of the current user message
     * @return the tier to pin on {@code AgentLoopContext.thinkingLevel}, or
     *         {@code null} to let the loop auto-resolve per-iteration
     */
    public ThinkingLevel resolveAndPin(
            String conversationId,
            String provider,
            String model,
            CallPurpose purpose,
            int toolCount,
            int userMsgChars) {
        if (!isClaude(provider, model)) {
            // Non-Claude: let AgentLoopExecutor auto-resolve per iteration.
            return null;
        }

        CallPurpose effectivePurpose = CallPurpose.orDefault(purpose);
        if (effectivePurpose != CallPurpose.MAIN) {
            // CLASSIFY / GUARDRAIL: resolve fresh, don't pin.
            return ThinkingLevel.auto(effectivePurpose, toolCount, userMsgChars);
        }

        if (conversationId == null || conversationId.isBlank()) {
            // No conversation to pin against - resolve fresh. Happens for
            // one-shot bridge calls that skip the conversation entity.
            return ThinkingLevel.auto(effectivePurpose, toolCount, userMsgChars);
        }

        Optional<String> existing = conversationRepository.findThinkingLevelPinned(conversationId)
                .filter(Objects::nonNull);
        if (existing.isPresent()) {
            ThinkingLevel parsed = parse(existing.get());
            if (parsed != null) {
                log.debug("Claude MAIN turn on conv {} reusing pinned thinkingLevel={}", conversationId, parsed);
                return parsed;
            }
            // Unparseable value (should not happen - enum names only). Treat
            // as unpinned and fall through; the new write replaces it below.
            log.warn("Unparseable thinking_level_pinned={} on conv {} - re-resolving", existing.get(), conversationId);
        }

        // First MAIN turn on this Claude conversation: resolve + persist.
        ThinkingLevel resolved = ThinkingLevel.auto(effectivePurpose, toolCount, userMsgChars);
        int updated = conversationRepository.pinThinkingLevelIfAbsent(conversationId, resolved.name());
        if (updated == 0) {
            // Another writer landed first - re-read the winning value so we
            // honor whatever got persisted instead of what THIS turn computed.
            Optional<String> winner = conversationRepository.findThinkingLevelPinned(conversationId)
                    .filter(Objects::nonNull);
            ThinkingLevel fromDb = winner.map(ThinkingLevelPinningService::parse).orElse(resolved);
            log.debug("Claude MAIN turn on conv {} lost pin race - using {} (computed={})",
                    conversationId, fromDb, resolved);
            return fromDb != null ? fromDb : resolved;
        }
        log.info("Pinned thinkingLevel={} for Claude conversation {}", resolved, conversationId);
        return resolved;
    }

    /**
     * Identify Claude by either the explicit provider registry name or the
     * {@code claude-} model prefix. Both identifiers appear at different call
     * sites (some wired from provider registry, some only know the model
     * string) so we accept either. {@code null}/blank on both → false.
     */
    private static boolean isClaude(String provider, String model) {
        if (provider != null && "anthropic".equalsIgnoreCase(provider.trim())) {
            return true;
        }
        if (model != null) {
            String lower = model.toLowerCase(java.util.Locale.ROOT);
            return lower.startsWith("claude-");
        }
        return false;
    }

    /**
     * Parse a DB-stored tier name into the enum; returns {@code null} for
     * unrecognized values so callers can safely fall through.
     */
    private static ThinkingLevel parse(String name) {
        if (name == null || name.isBlank()) return null;
        try {
            return ThinkingLevel.valueOf(name.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
