package com.apimarketplace.conversation.service.ai;

import com.apimarketplace.agent.summary.ColdSummarizerPromptBuilder;
import com.apimarketplace.agent.summary.ColdSummarizerPromptBuilder.Turn;
import com.apimarketplace.agent.summary.ColdSummaryEnvelope;
import com.apimarketplace.agent.summary.ColdSummaryGate;
import com.apimarketplace.conversation.repository.ConversationRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Stage 5.4 - orchestrates a single COLD-summary pass for one conversation.
 *
 * <p><b>What it does, end-to-end.</b>
 * <ol>
 *   <li>Checks {@link ColdSummaryGate#shouldRegenerate} - size + cadence/keyword
 *       gates must both pass. Bypassing the gate is not supported; that would
 *       waste the summariser budget on conversations that don't need a pass.</li>
 *   <li>Acquires a per-conversation distributed lock via ShedLock keyed on
 *       {@code cold-summary-{conversationId}} so only one pod spends credits
 *       per conversation; if another pod already holds the lock this call
 *       returns {@link SummarizeOutcome.SkippedGate} without invoking the LLM.
 *       Cross-pod contention is counted via {@code cold_summary_lock_contended_total{result=skipped|acquired}}.</li>
 *   <li>Builds the prompt via {@link ColdSummarizerPromptBuilder}. System prompt
 *       pins the JSON schema; user prompt dumps the COLD-zone turns.</li>
 *   <li>Hands the prompt to the injected {@link LlmJsonInvoker} - the live API
 *       wire-up (Anthropic/Gemini/OpenAI provider pick + retry) is plugged in
 *       separately; this service is LLM-agnostic so its unit tests don't need
 *       a provider.</li>
 *   <li>Parses the returned JSON into a {@link ColdSummaryEnvelope}. Parse
 *       failures propagate; the caller is expected to treat summariser output
 *       as best-effort and leave the prior envelope in place on failure.</li>
 *   <li>Persists via {@link ConversationRepository#updateSummaryCold}. The
 *       envelope carries {@code generated_at} + {@code cold_tokens_at_generation}
 *       so the Stage 5.3 gate can answer "has the COLD zone grown enough since
 *       the last summary to justify another pass?" on the next turn.</li>
 *   <li>Releases the lock in a {@code finally}; crash-safe via
 *       {@link #LOCK_AT_MOST_FOR} TTL on the ShedLock row.</li>
 * </ol>
 *
 * <p><b>Non-goals.</b>
 * <ul>
 *   <li>Doesn't decide which model to use - that's
 *       {@code AgentCompactionModelResolver}, wired in by the caller.</li>
 *   <li>Doesn't charge credits - credit-ledger billing happens when the caller
 *       records an observability entry with {@code agentType = "compaction_summary"}
 *       ({@code AgentObservabilityService#resolveSourceType} maps that to
 *       {@code sourceType = "COMPACTION_SUMMARY"}).</li>
 *   <li>Doesn't handle invalidation (Stage 5.5) - that's a separate read-path
 *       concern driven by {@code ColdSummaryInvalidationKeywords}.</li>
 * </ul>
 *
 * <p><b>Why the {@link LlmJsonInvoker} strategy.</b> Conversation-service doesn't
 * currently host a direct LLM-call path (every chat turn routes through the
 * bridge / agent SDK). Rather than reach into the agent-loop machinery for a
 * side call, we accept a functional invoker. Stage 2 follow-up (#51) will wire
 * a real implementation that routes {@code (provider, model, messages) → JSON}
 * through the existing provider registry; until then the service is a
 * composition seam that unit-tests freely.
 */
@Slf4j
@Service
public class ColdSummarizerService {

    /**
     * Cap on lock lifetime; if a pod crashes mid-summary the row is
     * auto-released after this window so the next pod can retry. Sized
     * generously above p99 cold-summary latency (typically under 60s,
     * with headroom for slow providers). If an LLM call genuinely exceeds
     * this window the lock may release early and allow a second pod to
     * re-enter - last-write-wins on the JSONB column, so correctness is
     * preserved but one call's credits are wasted. The invoker is expected
     * to enforce its own timeout shorter than this cap.
     */
    static final Duration LOCK_AT_MOST_FOR = Duration.ofMinutes(2);

    private final ConversationRepository conversationRepository;
    private final ObjectMapper mapper;
    private final LockProvider lockProvider;
    private final Counter lockAcquired;
    private final Counter lockContended;
    private final Counter staleWriteSkipped;
    private final MeterRegistry meterRegistry;

    public ColdSummarizerService(ConversationRepository conversationRepository,
                                 ObjectMapper mapper,
                                 LockProvider lockProvider,
                                 MeterRegistry meterRegistry) {
        this.conversationRepository = Objects.requireNonNull(conversationRepository);
        this.mapper = Objects.requireNonNull(mapper);
        this.lockProvider = Objects.requireNonNull(lockProvider);
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
        this.lockAcquired = Counter.builder("cold_summary_lock_contended_total")
                .description("COLD-summary dedup lock outcomes across pods")
                .tag("result", "acquired")
                .register(meterRegistry);
        this.lockContended = Counter.builder("cold_summary_lock_contended_total")
                .description("COLD-summary dedup lock outcomes across pods")
                .tag("result", "skipped")
                .register(meterRegistry);
        this.staleWriteSkipped = Counter.builder("cold_summary_stale_write_skipped_total")
                .description("Envelope writes rejected by the monotone-recall guard "
                        + "(a racing writer already persisted broader coverage)")
                .register(meterRegistry);
    }

    /**
     * Run one summariser pass for {@code conversationId}. Returns the persisted
     * envelope on success; {@link SummarizeOutcome.SkippedGate} when either the
     * size/cadence gate refused <i>or</i> another pod held the dedup lock (use
     * the {@code cold_summary_lock_contended_total} counter to distinguish);
     * {@link SummarizeOutcome.Failed} when the LLM returned garbage or the DB
     * write failed.
     *
     * <p>The caller provides the current COLD-zone token count + turn window.
     * The service does not recompute either - zoning is the compactor's job
     * and a redundant re-scan would inflate the critical path.
     */
    public SummarizeOutcome summarize(SummarizeRequest req, LlmJsonInvoker invoker) {
        Objects.requireNonNull(req, "SummarizeRequest required");
        Objects.requireNonNull(invoker, "LlmJsonInvoker required");

        // ---- gate -----------------------------------------------------------
        // Check the gate BEFORE the distributed lock: obvious-skip paths
        // (small COLD zone, cadence not reached) must stay cheap - no DB
        // round-trip to the shedlock table.
        boolean shouldRun = ColdSummaryGate.shouldRegenerate(
                req.currentColdTokens(),
                req.modelColdCapTokens(),
                req.turnsSinceLastSummary(),
                req.cadenceTurns(),
                req.keywordHit()
        );
        if (!shouldRun) {
            log.debug("COLD summary skipped by gate: conv={}, coldTok={}, coldCap={}, " +
                    "turnsSince={}, cadence={}, kwHit={}",
                    req.conversationId(), req.currentColdTokens(), req.modelColdCapTokens(),
                    req.turnsSinceLastSummary(), req.cadenceTurns(), req.keywordHit());
            return new SummarizeOutcome.SkippedGate();
        }

        // ---- dedup lock -----------------------------------------------------
        // Key is scoped per conversation so unrelated conversations never
        // block each other. A fast agent running 5 turns back-to-back might
        // trigger two gate passes within seconds; only one pod should spend
        // the credits. If lock acquisition fails, the other pod is already
        // producing a fresh envelope - treat as SkippedGate (same caller
        // semantics: no envelope was written by *this* call).
        LockConfiguration lockConfig = new LockConfiguration(
                Instant.now(),
                "cold-summary-" + req.conversationId(),
                LOCK_AT_MOST_FOR,
                Duration.ZERO
        );
        Optional<SimpleLock> maybeLock = lockProvider.lock(lockConfig);
        if (maybeLock.isEmpty()) {
            lockContended.increment();
            log.debug("COLD summary lock contended: conv={} - another pod is summarising",
                    req.conversationId());
            return new SummarizeOutcome.SkippedGate();
        }
        lockAcquired.increment();
        SimpleLock lock = maybeLock.get();
        try {
            return runLocked(req, invoker);
        } finally {
            lock.unlock();
        }
    }

    private SummarizeOutcome runLocked(SummarizeRequest req, LlmJsonInvoker invoker) {
        // ---- prompt ---------------------------------------------------------
        String system = ColdSummarizerPromptBuilder.systemPrompt();
        String user = ColdSummarizerPromptBuilder.buildUserPrompt(req.coldTurns());

        // ---- invoke ---------------------------------------------------------
        String rawJson;
        try {
            rawJson = invoker.invoke(req.providerName(), req.modelName(), system, user);
        } catch (Exception e) {
            log.warn("COLD summary LLM call failed: conv={}, provider={}, model={}, err={}",
                    req.conversationId(), req.providerName(), req.modelName(), e.toString());
            return new SummarizeOutcome.Failed("llm-invoke: " + e.getMessage());
        }
        if (rawJson == null || rawJson.isBlank()) {
            return new SummarizeOutcome.Failed("empty-response");
        }

        // ---- parse → envelope ----------------------------------------------
        ColdSummaryEnvelope parsed;
        try {
            // The LLM emits a partial envelope (decisions/ids_resolved/... only,
            // per the system prompt). We decorate it with the metadata fields
            // the gate needs (generated_at, model, cold_tokens_at_generation,
            // turns_covered) before persisting.
            parsed = mapper.readValue(rawJson, ColdSummaryEnvelope.Partial.class).toEnvelope(
                    Instant.now().toString(),
                    req.modelName(),
                    req.currentColdTokens(),
                    req.turnsCovered()
            );
        } catch (JsonProcessingException e) {
            log.warn("COLD summary JSON parse failed: conv={}, err={}, json[0..200]={}",
                    req.conversationId(), e.getOriginalMessage(),
                    rawJson.substring(0, Math.min(200, rawJson.length())));
            return new SummarizeOutcome.Failed("parse: " + e.getOriginalMessage());
        }

        // ---- persist --------------------------------------------------------
        try {
            String envelopeJson = mapper.writeValueAsString(parsed);
            int coveredCount = req.turnsCovered().size();
            int rows = conversationRepository.updateSummaryCold(
                    req.conversationId(), envelopeJson, coveredCount);
            if (rows == 0) {
                // Two distinct causes share the 0-rows shape; disambiguate so
                // telemetry stays honest:
                //  - the monotone-recall guard rejected the write (a racing
                //    writer - e.g. after our ShedLock TTL expired under a slow
                //    LLM - already persisted broader coverage). The stored
                //    envelope is strictly better; dropping ours is correct.
                //  - the conversation row is missing entirely (should never
                //    happen - the caller just wrote a message to it).
                if (conversationRepository.existsById(req.conversationId())) {
                    staleWriteSkipped.increment();
                    log.info("COLD summary write skipped by monotone guard: conv={}, " +
                            "coveredCount={} <= stored coverage (racing writer won)",
                            req.conversationId(), coveredCount);
                    return new SummarizeOutcome.SkippedStaleWrite();
                }
                return new SummarizeOutcome.Failed("conversation-not-found");
            }
        } catch (JsonProcessingException e) {
            return new SummarizeOutcome.Failed("serialize: " + e.getOriginalMessage());
        }
        log.info("COLD summary persisted: conv={}, coldTok={}, turnsCovered={}, model={}",
                req.conversationId(), req.currentColdTokens(),
                req.turnsCovered().size(), req.modelName());
        return new SummarizeOutcome.Persisted(parsed);
    }

    /**
     * Clear the cached envelope for rollback / invalidation paths (Stage 5.5).
     * Idempotent: returns {@code false} if no envelope was stored.
     *
     * <p>Hard wipe - recall drops to nothing until the next regeneration.
     * Prefer {@link #markStale} for "this summary can no longer be trusted
     * verbatim" situations: it keeps caveated recall available and lets the
     * monotone write guard converge on the next pass.
     */
    public boolean invalidate(String conversationId) {
        Objects.requireNonNull(conversationId, "conversationId required");
        int rows = conversationRepository.clearSummaryCold(conversationId);
        if (rows > 0) {
            log.info("COLD summary invalidated: conv={}", conversationId);
        }
        return rows > 0;
    }

    /**
     * Flag the stored envelope as {@link ColdSummaryEnvelope#STATUS_STALE}
     * without dropping its content. Status-aware recall keeps rendering it -
     * with a caution caveat instead of the authoritative header - and the
     * monotone write guard accepts the next regeneration even when it covers
     * fewer turns (the convergence path after a legitimate history shrink).
     *
     * <p>Idempotent: the conditional UPDATE is a no-op when the row is
     * missing, has no envelope, or is already stale - only an actual
     * transition returns {@code true} and bumps the (reason-tagged) counter.
     *
     * <p><b>Known trade-off:</b> regeneration is still subject to the
     * {@link ColdSummaryGate} size floor (2k tokens), so a COLD zone that
     * shrank below it can stay stale-caveated indefinitely - there is
     * nothing big enough to summarise. That is intentional: a permanently
     * cautious header over a small zone beats an authoritative claim about
     * turns that no longer exist.
     */
    public boolean markStale(String conversationId, String reason) {
        Objects.requireNonNull(conversationId, "conversationId required");
        Objects.requireNonNull(reason, "reason required");
        int rows = conversationRepository.markSummaryColdStale(conversationId);
        if (rows > 0) {
            // Reason values are a small closed set chosen by callers
            // ("cold-shrink", "invalidation-regen-missed") - bounded cardinality.
            meterRegistry.counter("cold_summary_marked_stale_total", "reason", reason).increment();
            log.info("COLD summary marked stale: conv={}, reason={}", conversationId, reason);
        }
        return rows > 0;
    }

    // -------------------------------------------------------------------------
    // DTOs
    // -------------------------------------------------------------------------

    /**
     * Caller-provided summariser context. All fields required; no nulls.
     * The service doesn't reach into the entity layer because the compactor
     * already computed token counts and turn slices for its own purposes -
     * re-reading would double the scan.
     */
    public record SummarizeRequest(
            String conversationId,
            /** Per-model COLD-zone cap in tokens (Claude 4k, Gemini 2.5k, etc.). */
            int modelColdCapTokens,
            /** Current COLD-zone size in tokens, pre-summary. */
            int currentColdTokens,
            /** New turns since the previous envelope was generated. */
            int turnsSinceLastSummary,
            /** Caller's cadence config (&lt;=0 → default {@link ColdSummaryGate#DEFAULT_CADENCE_TURNS}). */
            int cadenceTurns,
            /** True when {@link ColdSummaryInvalidationKeywords} matched the latest user turn. */
            boolean keywordHit,
            List<Turn> coldTurns,
            List<Integer> turnsCovered,
            String providerName,
            String modelName
    ) {
        public SummarizeRequest {
            Objects.requireNonNull(conversationId, "conversationId");
            Objects.requireNonNull(coldTurns, "coldTurns");
            Objects.requireNonNull(turnsCovered, "turnsCovered");
            Objects.requireNonNull(providerName, "providerName");
            Objects.requireNonNull(modelName, "modelName");
        }
    }

    /** Result of a summariser pass. */
    public sealed interface SummarizeOutcome {
        /**
         * Nothing was written by this call. Either the size/cadence gate
         * refused, or another pod already held the per-conversation
         * dedup lock; inspect {@code cold_summary_lock_contended_total}
         * to distinguish.
         */
        record SkippedGate() implements SummarizeOutcome {}
        /** Envelope generated and persisted; returned for observability. */
        record Persisted(ColdSummaryEnvelope envelope) implements SummarizeOutcome {}
        /**
         * The LLM call succeeded but the monotone-recall guard rejected the
         * write: a racing writer (typically after this pod's lock TTL
         * expired under a slow provider) already persisted an envelope with
         * coverage at least as broad. The stored envelope is the better
         * one; this call's output was discarded. Counted in
         * {@code cold_summary_stale_write_skipped_total}.
         */
        record SkippedStaleWrite() implements SummarizeOutcome {}
        /** LLM or DB failure; reason is free-form for the log. */
        record Failed(String reason) implements SummarizeOutcome {}
    }

    /**
     * Functional seam for the LLM call. Implementations route the
     * {@code (provider, model, system, user)} prompt to the real provider and
     * return the raw JSON response. The stub returned by the summariser must
     * be the body of the LLM's response, stripped of fences if any (the prompt
     * asks for JSON-only but defensive callers should still trim).
     *
     * <p>Wired by Stage 2 follow-ups (#51).
     */
    @FunctionalInterface
    public interface LlmJsonInvoker {
        String invoke(String provider, String model, String system, String user);
    }
}
