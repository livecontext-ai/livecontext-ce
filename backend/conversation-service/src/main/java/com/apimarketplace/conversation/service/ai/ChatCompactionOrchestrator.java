package com.apimarketplace.conversation.service.ai;

import com.apimarketplace.agent.summary.AgentCompactionModelResolver;
import com.apimarketplace.agent.summary.AgentCompactionModelResolver.ModelRef;
import com.apimarketplace.agent.summary.ColdSummarizerPromptBuilder.Turn;
import com.apimarketplace.agent.summary.ColdSummaryEnvelope;
import com.apimarketplace.agent.summary.ColdSummaryInvalidationKeywords;
import com.apimarketplace.agent.summary.CompactionConfigResolver;
import com.apimarketplace.conversation.entity.Conversation;
import com.apimarketplace.conversation.entity.Message;
import com.apimarketplace.conversation.repository.ConversationRepository;
import com.apimarketplace.conversation.repository.MessageRepository;
import com.apimarketplace.conversation.service.ai.ColdSummarizerService.LlmJsonInvoker;
import com.apimarketplace.conversation.service.ai.ColdSummarizerService.SummarizeOutcome;
import com.apimarketplace.conversation.service.ai.ColdSummarizerService.SummarizeRequest;
import com.apimarketplace.conversation.streaming.StreamPubSubService;
import com.apimarketplace.common.web.TenantResolver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Stage 2 follow-up (#51) - post-turn compaction hook for the chat pipeline.
 *
 * <p>Runs <em>after</em> every chat turn finishes persisting to compute
 * zone metrics, check the COLD-summary gate, and (if the gate passes)
 * dispatch a summarisation pass via {@link ColdSummarizerService}. The
 * caller - {@link ConversationAgentService#persistRemoteResults} - fires
 * and forgets; this orchestrator owns all the plumbing so the chat hot
 * path doesn't balloon.
 *
 * <h3>Zoning strategy</h3>
 *
 * The chat pipeline keeps messages in chronological order in
 * {@code conversation.messages}. For Stage 2 we use a <em>turn-count</em>
 * window (not token-weighted zones, which land in Task #52 / Stage 3
 * follow-ups):
 * <ul>
 *   <li>Last {@link CompactionDefaultsConfig#getHotWarmTurnWindow()} turns
 *       stay in HOT+WARM - they're the active working set the LLM sees in
 *       full.</li>
 *   <li>Everything older is COLD and handed to the summariser.</li>
 * </ul>
 *
 * A short conversation that never exceeds the HOT+WARM window never
 * triggers the summariser - the early-return below keeps the DB read
 * cost negligible in that case.
 *
 * <h3>Why {@code @Async}</h3>
 *
 * Summariser calls carry a p99 that can stretch past 60 s on slow
 * providers, and the {@link ColdSummarizerService} lock covers 2 min. A
 * user waiting for the next chat turn's network round-trip shouldn't
 * pay that. We dispatch the whole pipeline on the shared Spring
 * {@code @Async} executor (enabled at the application level via
 * {@code @EnableAsync}). Exceptions inside the async task are logged and
 * swallowed - a summariser hiccup must never surface to the chat caller.
 *
 * <h3>Per-provider COLD caps</h3>
 *
 * The gate formula {@code max(2000, 0.15 × cap)} needs a per-model cold
 * cap. We don't yet have a model-registry service in conversation-service,
 * so a small static map keyed on provider family covers the common cases.
 * Task #52 lifts this into a proper registry; until then a
 * {@link #COLD_CAP_BY_PROVIDER} miss falls back to
 * {@link #DEFAULT_COLD_CAP_TOKENS} and callers get the floor-gated
 * behaviour documented in {@link com.apimarketplace.agent.summary.ColdSummaryGate}.
 *
 * <h3>Tenant scoping</h3>
 *
 * The summariser invoker we hand to {@link ColdSummarizerService} is a
 * tenant-bound lambda that closes over the caller's tenantId so the
 * {@code X-User-ID} header reaches the downstream json-completion
 * endpoint for rate-limit attribution.
 */
@Slf4j
@Component
public class ChatCompactionOrchestrator {

    /**
     * Heuristic chars→tokens ratio for COLD-zone sizing. Matches
     * {@link com.apimarketplace.agent.tokenizer.HeuristicTokenEstimator}
     * (4 chars / token) so the gate's decision stays consistent with the
     * provider-level estimator. Stage 3 (Task #52) replaces this with
     * the real tokeniser once the registry lands.
     */
    static final int CHARS_PER_TOKEN = 4;

    /**
     * Fallback COLD cap in tokens when the chat provider isn't in the
     * static table. 4k is the Claude cap called out in {@code
     * ColdSummaryGate}'s doc - it makes the floor (2k) win, which is the
     * safe default: summariser still fires when needed, never too
     * aggressively.
     */
    static final int DEFAULT_COLD_CAP_TOKENS = 4000;

    /**
     * Per-provider COLD zone capacity in tokens. Picks are the same
     * values documented in {@link com.apimarketplace.agent.summary.ColdSummaryGate}
     * (Claude 4k, Gemini 2.5k). Keyed on lowercase provider name to
     * tolerate casing drift at call sites. Task #52 replaces this with
     * the model-registry lookup.
     */
    static final Map<String, Integer> COLD_CAP_BY_PROVIDER = Map.of(
            "anthropic", 4000,
            "google", 2500,
            "openai", 3500
    );

    /**
     * CLI-bridge providers (see {@code ConversationAgentService.isBridgeProvider})
     * carry their own provider tag distinct from the underlying API family.
     * Map them to the family name so the COLD-cap lookup hits the right
     * row instead of silently falling through to {@link #DEFAULT_COLD_CAP_TOKENS}
     * - a Gemini CLI request should land on the 2.5k cap like the API path,
     * not the 4k default.
     */
    static final Map<String, String> CLI_BRIDGE_PROVIDER_FAMILY = Map.of(
            "claude-code", "anthropic",
            "codex", "openai",
            "gemini-cli", "google",
            "mistral-vibe", "openai"   // closest API-family fallback for cap sizing
    );

    private final MessageRepository messageRepository;
    private final ConversationRepository conversationRepository;
    private final ColdSummarizerService coldSummarizer;
    private final HttpLlmJsonInvoker httpInvoker;
    private final CompactionDefaultsConfig config;
    private final StreamPubSubService streamPubSubService;
    private final AgentConfigProvider agentConfigProvider;
    private final Counter dispatchFailedCounter;

    public ChatCompactionOrchestrator(MessageRepository messageRepository,
                                      ConversationRepository conversationRepository,
                                      ColdSummarizerService coldSummarizer,
                                      HttpLlmJsonInvoker httpInvoker,
                                      CompactionDefaultsConfig config,
                                      StreamPubSubService streamPubSubService,
                                      AgentConfigProvider agentConfigProvider,
                                      MeterRegistry meterRegistry) {
        this.messageRepository = Objects.requireNonNull(messageRepository, "messageRepository");
        this.conversationRepository = Objects.requireNonNull(conversationRepository, "conversationRepository");
        this.coldSummarizer = Objects.requireNonNull(coldSummarizer, "coldSummarizer");
        this.httpInvoker = Objects.requireNonNull(httpInvoker, "httpInvoker");
        this.config = Objects.requireNonNull(config, "config");
        this.streamPubSubService = Objects.requireNonNull(streamPubSubService, "streamPubSubService");
        this.agentConfigProvider = Objects.requireNonNull(agentConfigProvider, "agentConfigProvider");
        Objects.requireNonNull(meterRegistry, "meterRegistry");
        this.dispatchFailedCounter = Counter.builder("cold_summary_dispatch_failed_total")
                .description("Compaction post-turn dispatch threw before reaching ColdSummarizerService")
                .register(meterRegistry);
    }

    /**
     * Fire-and-forget entry from the chat pipeline. Safe to call after every
     * persisted turn; returns immediately on the caller thread and schedules
     * the real work on the shared {@code @Async} executor. Any exception is
     * caught and logged - compaction must never break the chat response.
     */
    @Async
    public void afterTurnAsync(String conversationId, String chatProvider,
                               String chatModel, String tenantId, String streamId) {
        afterTurnAsync(conversationId, chatProvider, chatModel, tenantId, null, streamId);
    }

    @Async
    public void afterTurnAsync(String conversationId, String chatProvider,
                               String chatModel, String tenantId, String organizationId, String streamId) {
        try {
            TenantResolver.runWithOrgScope(organizationId,
                    () -> afterTurn(conversationId, chatProvider, chatModel, tenantId, streamId));
        } catch (Exception e) {
            // Bump the failure counter so an outage shows up in Prometheus
            // beyond the WARN log. Counter is unlabeled to keep cardinality
            // bounded - drill-down lives in the log line.
            dispatchFailedCounter.increment();
            log.warn("Compaction post-turn failed: conv={} err={}", conversationId, e.toString());
        }
    }

    /**
     * Synchronous overload - returns the {@link SummarizeOutcome} for
     * inspection. Tests call this directly; the async wrapper exists only
     * so the chat caller doesn't block.
     *
     * <p>Never throws on expected paths; returns {@link SummarizeOutcome.SkippedGate}
     * when compaction is disabled, the conversation is too short, or the
     * COLD zone is empty. Only unexpected exceptions (DB outage, etc.)
     * propagate; the async wrapper catches those.
     */
    public SummarizeOutcome afterTurn(String conversationId, String chatProvider,
                                      String chatModel, String tenantId, String streamId) {
        Objects.requireNonNull(conversationId, "conversationId");

        // ---- resolve EFFECTIVE compaction config (conversation > agent > YAML) ----
        // Replaces the old single global gate (config.isEnabled()): a user can now
        // enable/disable compaction, set its cadence AND pick the summariser model
        // per conversation, per agent, or via the workspace default. We load the
        // conversation once here and reuse it for the prior-coverage read below
        // (no double fetch).
        Conversation conversation = conversationRepository.findById(conversationId).orElse(null);
        ResolvedCompaction resolved = resolveEffectiveCompaction(conversation, tenantId);
        CompactionConfigResolver.Effective effective = resolved.effective();
        if (!effective.enabled()) {
            return new SummarizeOutcome.SkippedGate();
        }

        // ---- load all messages (ASC) --------------------------------------
        List<Message> all = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
        int window = Math.max(1, config.getHotWarmTurnWindow());
        if (all.size() <= window) {
            // conversation hasn't grown past the HOT+WARM window yet - cheapest path
            return new SummarizeOutcome.SkippedGate();
        }

        // ---- zone split: oldest are COLD ----------------------------------
        int coldEnd = all.size() - window;
        List<Message> coldMessages = all.subList(0, coldEnd);
        if (coldMessages.isEmpty()) {
            return new SummarizeOutcome.SkippedGate();
        }

        // ---- build Turn list + tally tokens -------------------------------
        // INVARIANT: turnsCovered is always the contiguous range 0..coldEnd-1.
        // Two downstream consumers rely on it agreeing with itself:
        // the monotone write guard compares coverage CARDINALITY
        // (jsonb_array_length) while shrink detection below compares the MAX
        // position - equivalent only while the list stays contiguous from 0.
        List<Turn> coldTurns = new ArrayList<>(coldMessages.size());
        List<Integer> turnsCovered = new ArrayList<>(coldMessages.size());
        int coldTokens = 0;
        for (int i = 0; i < coldMessages.size(); i++) {
            Message m = coldMessages.get(i);
            String body = m.getContent() == null ? "" : m.getContent();
            coldTokens += body.length() / CHARS_PER_TOKEN;
            coldTurns.add(new Turn(i, roleLabel(m.getRole()), body));
            turnsCovered.add(i);
        }

        // ---- turnsSinceLastSummary from prior envelope metadata -----------
        PriorCoverage prior = readPriorCoverage(conversation);
        int turnsSinceLastSummary;
        if (prior.maxTurn() < 0) {
            // No usable prior envelope → everything in COLD is new.
            turnsSinceLastSummary = coldMessages.size();
        } else if (prior.maxTurn() > coldMessages.size() - 1) {
            // The COLD zone shrank under the stored summary (window
            // reconfiguration, deleted/cancelled turns): its turns_covered
            // references positions that no longer exist, so its content can
            // no longer be trusted verbatim. Flag it stale - recall keeps the
            // content with a caution caveat, and the monotone write guard
            // accepts the next (smaller-coverage) regeneration - and apply
            // full cadence pressure so that regeneration happens promptly.
            coldSummarizer.markStale(conversationId, "cold-shrink");
            turnsSinceLastSummary = coldMessages.size();
        } else {
            // turns_covered holds 0-indexed positions inside the cold slice;
            // the delta to the current last COLD index counts the new turns
            // appended since the prior summary.
            turnsSinceLastSummary = Math.max(0, (coldMessages.size() - 1) - prior.maxTurn());
        }

        // ---- latest user turn (for keyword invalidation) ------------------
        // Scan the FULL message list (not just COLD) - a pivot keyword in the
        // newest HOT turn is what most frequently triggers invalidation.
        String latestUser = findLatestUserContent(all);
        boolean keywordHit = ColdSummaryInvalidationKeywords.matchesStrict(latestUser);

        // ---- summariser model (conversation > agent > YAML, resolved above) ----
        ModelRef summariserModel = resolved.summariserModel();

        // ---- cold cap from the chat provider ------------------------------
        int coldCap = resolveColdCap(chatProvider);

        // ---- build request + dispatch -------------------------------------
        SummarizeRequest req = new SummarizeRequest(
                conversationId,
                coldCap,
                coldTokens,
                turnsSinceLastSummary,
                effective.afterTurns(),
                keywordHit,
                coldTurns,
                turnsCovered,
                summariserModel.provider(),
                summariserModel.name()
        );

        // Tenant-bound adapter: close over tenantId so the X-User-ID hits the
        // json-completion endpoint without threading it through the LlmJsonInvoker
        // three-arg SPI (which intentionally stays tenant-agnostic).
        LlmJsonInvoker tenantAwareInvoker = (p, m, s, u) -> httpInvoker.invoke(p, m, s, u, tenantId);

        SummarizeOutcome outcome = coldSummarizer.summarize(req, tenantAwareInvoker);
        log.debug("Compaction outcome: conv={} outcome={} coldTok={} turnsSince={} kwHit={} summariser={}/{}",
                conversationId, outcome.getClass().getSimpleName(),
                coldTokens, turnsSinceLastSummary, keywordHit,
                summariserModel.provider(), summariserModel.name());
        if (outcome instanceof SummarizeOutcome.Persisted persisted) {
            publishCompactionDone(streamId, conversationId, persisted.envelope());
        } else if (keywordHit && prior.present()) {
            // The user explicitly corrected course ("actually, scratch
            // that", …) but THIS pass did not persist a regeneration (gate
            // skip, lock contention, LLM failure - or SkippedStaleWrite,
            // where a racing writer's envelope DID just land but may have
            // been generated before the correction turn). In all of these
            // the stored summary may assert things the user just walked
            // back, so downgrade it to stale: recall keeps the content with
            // a caution caveat instead of the authoritative "trust it / do
            // NOT redo work" header. Deliberately conservative - wrongly
            // caveating a fresh racer's envelope costs one cautious header;
            // wrongly trusting a walked-back one costs correctness. Heals on
            // the next persisted envelope (stamped active), though a COLD
            // zone that shrank below the size-gate floor may stay caveated -
            // still strictly better than a wrong authoritative claim.
            coldSummarizer.markStale(conversationId, "invalidation-regen-missed");
        }
        return outcome;
    }

    /**
     * Publish a user-facing {@code CompactionDone} event after a summary
     * envelope persists. Best-effort: because compaction runs on the
     * {@code @Async} executor, the originating stream may already be
     * terminal by the time this fires - Redis pub/sub silently drops events
     * with no subscribers, so we pass {@code streamId} through anyway and
     * clients that rejoin the stream (or subscribe early) still get the
     * event. If {@code streamId} is null (e.g. tests, sync entry points),
     * we skip emission - the persistent source of truth lives on
     * {@code conversation.summary_cold} and the {@code ConversationDto}
     * exposure so clients still see the marker on next fetch.
     */
    private void publishCompactionDone(String streamId, String conversationId,
                                       ColdSummaryEnvelope envelope) {
        if (streamId == null || streamId.isBlank()) {
            return;
        }
        int turnsCoveredCount = envelope.turnsCovered() == null ? 0 : envelope.turnsCovered().size();
        // generatedAt is @NonNull on the envelope (compact constructor enforces
        // it), so the only failure mode here is a malformed ISO-8601 string
        // from a weak summariser LLM. Fall back to "now" rather than fail the
        // fire-and-forget path - the server-persisted envelope still carries
        // the raw value verbatim for postmortem.
        Instant generatedAt;
        try {
            generatedAt = Instant.parse(envelope.generatedAt());
        } catch (Exception parseErr) {
            generatedAt = Instant.now();
        }
        try {
            // subscribe() with an error handler: async Mono.error(...) from a
            // Redis hiccup would otherwise land in reactor's onErrorDropped
            // (WARN-level log spam) instead of our own debug line. Publishing
            // is best-effort - the summary is already persisted; clients pick
            // it up from the conversation DTO on next read.
            streamPubSubService.publishCompactionDone(streamId, conversationId,
                            turnsCoveredCount, envelope.model(), generatedAt)
                    .subscribe(
                            receivers -> { /* no-op: success count is irrelevant here */ },
                            err -> log.debug("Compaction event emit failed (non-fatal): conv={} err={}",
                                    conversationId, err.toString())
                    );
        } catch (Exception e) {
            // Defensive: synchronous throw from publish() (e.g. serializer rejected
            // the event) is separately swallowed so compaction never breaks because
            // the UI nicety failed to build the payload.
            log.debug("Compaction event publish failed (non-fatal): conv={} err={}",
                    conversationId, e.toString());
        }
    }

    /**
     * Prior-envelope coverage facts the cadence/shrink logic needs.
     * {@code present} is true when an envelope row exists at all (even a
     * malformed one - that still matters for the keyword stale-marking
     * path); {@code maxTurn} is the highest covered turn index, or -1 when
     * no usable {@code turns_covered} list exists.
     */
    record PriorCoverage(boolean present, int maxTurn) {}

    private PriorCoverage readPriorCoverage(Conversation conv) {
        if (conv == null) return new PriorCoverage(false, -1);
        Map<String, Object> prior = conv.getSummaryCold();
        if (prior == null || prior.isEmpty()) return new PriorCoverage(false, -1);
        Object covered = prior.get("turns_covered");
        if (!(covered instanceof List<?> list) || list.isEmpty()) {
            return new PriorCoverage(true, -1);
        }
        int prevMaxTurn = -1;
        for (Object o : list) {
            if (o instanceof Number n) {
                prevMaxTurn = Math.max(prevMaxTurn, n.intValue());
            }
        }
        return new PriorCoverage(true, prevMaxTurn);
    }

    /**
     * Resolve the effective compaction config for this turn by layering the
     * per-conversation override ({@code conversation.chat_config.compaction.*}),
     * the per-agent override (fetched only when needed), and the YAML default.
     * Delegates the precedence to {@link CompactionConfigResolver}.
     *
     * <p>The per-agent RPC is paid ONLY when the conversation does not already pin
     * both fields and an agent is attached - most general chats skip it entirely.
     */
    /**
     * Effective enable/cadence pair plus the resolved summariser model for one
     * compaction pass. Bundled so the per-agent RPC is paid at most once per turn.
     */
    record ResolvedCompaction(CompactionConfigResolver.Effective effective, ModelRef summariserModel) {}

    private ResolvedCompaction resolveEffectiveCompaction(Conversation conversation, String tenantId) {
        Boolean convEnabled = null;
        Integer convAfterTurns = null;
        String convModelProvider = null;
        String convModelName = null;
        String agentId = null;
        if (conversation != null) {
            agentId = conversation.getAgentId();
            Map<String, Object> chatConfig = conversation.getChatConfig();
            if (chatConfig != null && chatConfig.get("compaction") instanceof Map<?, ?> comp) {
                if (comp.get("enabled") instanceof Boolean b) {
                    convEnabled = b;
                }
                if (comp.get("afterTurns") instanceof Number n && n.intValue() >= 1) {
                    convAfterTurns = n.intValue();
                }
                if (comp.get("modelProvider") instanceof String p && !p.isBlank()) {
                    convModelProvider = p.trim();
                }
                if (comp.get("modelName") instanceof String m && !m.isBlank()) {
                    convModelName = m.trim();
                }
                if (convModelProvider == null || convModelName == null) {
                    // Partial pair = unset for this tier (refuse to guess, mirrors
                    // AgentCompactionModelResolver's either-or rule).
                    convModelProvider = null;
                    convModelName = null;
                }
            }
        }

        Boolean agentEnabled = null;
        Integer agentAfterTurns = null;
        String agentModelProvider = null;
        String agentModelName = null;
        boolean convPinsModel = convModelProvider != null;
        if (agentId != null && !agentId.isBlank()
                && (convEnabled == null || convAfterTurns == null || !convPinsModel)) {
            AgentConfigProvider.CompactionOverride ov = agentConfigProvider.getCompactionOverride(agentId, tenantId);
            agentEnabled = ov.enabled();
            agentAfterTurns = ov.afterTurns();
            agentModelProvider = ov.modelProvider();
            agentModelName = ov.modelName();
        }

        // Summariser model ladder maps 1:1 onto the resolver's three tiers:
        // conversation override > agent compaction override > YAML default. The
        // agent's PRIMARY model is deliberately NOT a tier here (matches the
        // pre-feature chat behavior): the summariser stays on the cost-sensitive
        // platform default unless someone explicitly picked a compaction model.
        // Note: resolved even when compaction ends up disabled - the YAML defaults
        // are hardcoded non-blank (CompactionDefaultsConfig.ModelRef), so the
        // resolver's fail-loud on an all-blank ladder can only fire if a deployment
        // explicitly blanks conversation.compaction.compaction-model.*, which is a
        // config error we WANT surfaced (caught + counted by the async wrapper).
        ModelRef summariserModel = AgentCompactionModelResolver.resolve(
                convModelProvider, convModelName,
                agentModelProvider, agentModelName,
                config.getCompactionModel().getProvider(),
                config.getCompactionModel().getName());

        return new ResolvedCompaction(
                CompactionConfigResolver.resolve(
                        convEnabled, convAfterTurns,
                        agentEnabled, agentAfterTurns,
                        config.isEnabled(), config.getCadenceTurns()),
                summariserModel);
    }

    static int resolveColdCap(String chatProvider) {
        if (chatProvider == null || chatProvider.isBlank()) return DEFAULT_COLD_CAP_TOKENS;
        String key = chatProvider.toLowerCase();
        // CLI-bridge providers (claude-code, gemini-cli, codex, mistral-vibe) carry
        // their own tag that isn't in COLD_CAP_BY_PROVIDER; normalise to the
        // underlying API family so the cap matches the real context window.
        String normalised = CLI_BRIDGE_PROVIDER_FAMILY.getOrDefault(key, key);
        return COLD_CAP_BY_PROVIDER.getOrDefault(normalised, DEFAULT_COLD_CAP_TOKENS);
    }

    private static String roleLabel(Message.MessageRole role) {
        return role == null ? "user" : role.getValue();
    }

    private static String findLatestUserContent(List<Message> msgs) {
        for (int i = msgs.size() - 1; i >= 0; i--) {
            Message m = msgs.get(i);
            if (m.getRole() == Message.MessageRole.USER) {
                return m.getContent();
            }
        }
        return null;
    }
}
