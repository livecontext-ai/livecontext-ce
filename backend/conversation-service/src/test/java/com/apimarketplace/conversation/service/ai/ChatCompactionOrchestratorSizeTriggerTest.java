package com.apimarketplace.conversation.service.ai;

import com.apimarketplace.agent.summary.CompactionTrigger;
import com.apimarketplace.conversation.entity.Conversation;
import com.apimarketplace.conversation.entity.Message;
import com.apimarketplace.conversation.repository.ConversationRepository;
import com.apimarketplace.conversation.repository.MessageRepository;
import com.apimarketplace.conversation.service.ai.ColdSummarizerService.LlmJsonInvoker;
import com.apimarketplace.conversation.service.ai.ColdSummarizerService.SummarizeOutcome;
import com.apimarketplace.conversation.service.ai.ColdSummarizerService.SummarizeRequest;
import com.apimarketplace.conversation.streaming.StreamPubSubService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins how the orchestrator measures COLD growth for the size-based triggers.
 *
 * <p>The load-bearing test here is {@link #growthResetsOnceTheEnvelopeCoversEverything()}.
 * The COLD zone keeps its messages after a summary lands, so comparing the
 * ABSOLUTE COLD size against a threshold would stay true for ever and re-fire
 * on every later turn, indefinitely: no per-conversation per-day cap is
 * enforced anywhere in this codebase. Growth since the stored envelope's
 * coverage is the only measure that resets.
 */
@DisplayName("ChatCompactionOrchestrator - COLD growth measurement for size triggers")
class ChatCompactionOrchestratorSizeTriggerTest {

    private static final String CONV = "conv-size";
    private static final String TENANT = "tenant-42";

    /** ChatCompactionOrchestrator.CHARS_PER_TOKEN is 4, so this body is exactly 1000 tokens. */
    private static final int CHARS_PER_MESSAGE = 4000;
    private static final int TOKENS_PER_MESSAGE = 1000;

    private MessageRepository messageRepo;
    private ConversationRepository conversationRepo;
    private ColdSummarizerService summarizer;
    private CompactionDefaultsConfig config;
    private ChatCompactionOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        messageRepo = mock(MessageRepository.class);
        conversationRepo = mock(ConversationRepository.class);
        summarizer = mock(ColdSummarizerService.class);
        HttpLlmJsonInvoker httpInvoker = mock(HttpLlmJsonInvoker.class);
        StreamPubSubService streamPubSub = mock(StreamPubSubService.class);
        when(streamPubSub.publishCompactionDone(anyString(), anyString(), anyInt(), anyString(), any(Instant.class)))
                .thenReturn(Mono.empty());
        AgentConfigProvider agentConfigProvider = mock(AgentConfigProvider.class);
        when(agentConfigProvider.getCompactionOverride(anyString(), any()))
                .thenReturn(AgentConfigProvider.CompactionOverride.NONE);

        config = new CompactionDefaultsConfig();
        config.setEnabled(true);
        config.setHotWarmTurnWindow(5);   // 10 messages => COLD is [0..4]
        config.setCadenceTurns(3);

        orchestrator = new ChatCompactionOrchestrator(
                messageRepo, conversationRepo, summarizer, httpInvoker, config,
                streamPubSub, agentConfigProvider, new SimpleMeterRegistry());

        when(summarizer.summarize(any(SummarizeRequest.class), any(LlmJsonInvoker.class)))
                .thenReturn(new SummarizeOutcome.SkippedGate());
    }

    // -------------------------------------------------------------------------
    // Growth measurement
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("No prior envelope: the whole COLD zone counts as growth, matching the turn counter")
    void noEnvelopeCountsEverythingAsNew() {
        givenMessages(10);
        when(conversationRepo.findById(CONV)).thenReturn(Optional.of(conversationCovering(null)));

        SummarizeRequest req = captureRequest();

        // COLD is [0..4] = 5 messages.
        assertThat(req.currentColdTokens()).isEqualTo(5 * TOKENS_PER_MESSAGE);
        assertThat(req.newColdTokensSinceLastSummary()).isEqualTo(5 * TOKENS_PER_MESSAGE);
    }

    @Test
    @DisplayName("Prior envelope covering part of COLD: only what arrived after it counts as growth")
    void partialEnvelopeCountsOnlyTheTail() {
        givenMessages(10);
        when(conversationRepo.findById(CONV))
                .thenReturn(Optional.of(conversationCovering(List.of(0, 1, 2))));

        SummarizeRequest req = captureRequest();

        // COLD is [0..4]; the envelope covers 0,1,2 so only 3 and 4 are new.
        assertThat(req.currentColdTokens()).isEqualTo(5 * TOKENS_PER_MESSAGE);
        assertThat(req.newColdTokensSinceLastSummary()).isEqualTo(2 * TOKENS_PER_MESSAGE);
    }

    @Test
    @DisplayName("Envelope already covers all of COLD: growth is zero although COLD is still large")
    void growthResetsOnceTheEnvelopeCoversEverything() {
        // THE anti-thrash invariant. currentColdTokens stays high because the
        // messages survive summarisation; growth must nonetheless be 0, or a
        // size trigger would fire on this turn and on every turn after it.
        givenMessages(10);
        when(conversationRepo.findById(CONV))
                .thenReturn(Optional.of(conversationCovering(List.of(0, 1, 2, 3, 4))));

        SummarizeRequest req = captureRequest();

        assertThat(req.currentColdTokens()).isEqualTo(5 * TOKENS_PER_MESSAGE);
        assertThat(req.newColdTokensSinceLastSummary())
                .as("growth must reset after a summary, otherwise SIZE mode re-fires for ever")
                .isZero();
    }

    @Test
    @DisplayName("COLD shrank under the stored envelope: the whole zone counts as growth, not zero")
    void shrunkColdZoneAppliesFullPressure() {
        // The stored coverage points past the end of the current COLD zone, so
        // no index matches and a naive tally would leave growth at 0, holding
        // regeneration back exactly when the envelope can no longer be trusted.
        givenMessages(10);
        when(conversationRepo.findById(CONV))
                .thenReturn(Optional.of(conversationCovering(List.of(0, 1, 2, 3, 4, 5, 6, 7))));

        SummarizeRequest req = captureRequest();

        assertThat(req.newColdTokensSinceLastSummary()).isEqualTo(req.currentColdTokens());
        assertThat(req.newColdTokensSinceLastSummary()).isEqualTo(5 * TOKENS_PER_MESSAGE);
    }

    @Test
    @DisplayName("A shrunk COLD zone is flagged invalidated, so SIZE mode cannot refuse it for ever")
    void shrunkColdZoneIsFlaggedInvalidated() {
        // Raising the token count alone is not enough: it only unblocks the size
        // trigger when the zone happens to exceed the growth threshold. Here COLD
        // is 5 000 tokens against a 32 000 threshold, so without the flag SIZE
        // mode would mark the envelope stale and then refuse to regenerate it on
        // this turn and on every later one, leaving recall caveated for good.
        config.setTrigger("size");
        givenMessages(10);
        when(conversationRepo.findById(CONV))
                .thenReturn(Optional.of(conversationCovering(List.of(0, 1, 2, 3, 4, 5, 6, 7))));

        SummarizeRequest req = captureRequest();

        assertThat(req.newColdTokensSinceLastSummary())
                .as("the growth alone is below the threshold, so it cannot be what unblocks this")
                .isLessThan(req.sizeTriggerColdTokens());
        assertThat(req.envelopeInvalidated()).isTrue();
        verify(summarizer).markStale(CONV, "cold-shrink");
    }

    @Test
    @DisplayName("An intact envelope is not flagged invalidated")
    void intactEnvelopeIsNotInvalidated() {
        givenMessages(10);
        when(conversationRepo.findById(CONV))
                .thenReturn(Optional.of(conversationCovering(List.of(0, 1, 2))));

        assertThat(captureRequest().envelopeInvalidated()).isFalse();
    }

    @Test
    @DisplayName("A malformed envelope is untrusted, so SIZE mode can eventually replace it")
    void malformedEnvelopeIsUntrusted() {
        // A row whose turns_covered is missing or unusable is recalled
        // authoritatively while its growth tally reads against no coverage.
        // Before this it was flagged nowhere, so in SIZE mode nothing could ever
        // replace it while COLD sat between the floor and the threshold.
        givenMessages(10);
        Conversation conv = new Conversation();
        Map<String, Object> env = new HashMap<>();
        env.put("decisions", List.of("something"));   // present, no turns_covered
        conv.setSummaryCold(env);
        when(conversationRepo.findById(CONV)).thenReturn(Optional.of(conv));

        assertThat(captureRequest().envelopeInvalidated()).isTrue();
    }

    @Test
    @DisplayName("A row already marked stale stays untrusted on later turns, not just the turn that noticed")
    void persistedStaleFlagIsHonoured() {
        // The coverage is intact and in range, so the shrink branch is not taken
        // and the keyword is long gone; only the PERSISTED status can still say
        // this envelope must be replaced.
        givenMessages(10);
        Conversation conv = new Conversation();
        Map<String, Object> env = new HashMap<>();
        env.put("turns_covered", List.of(0, 1, 2));
        env.put("status", "stale");
        conv.setSummaryCold(env);
        when(conversationRepo.findById(CONV)).thenReturn(Optional.of(conv));

        assertThat(captureRequest().envelopeInvalidated()).isTrue();
    }

    @Test
    @DisplayName("A healthy envelope with intact coverage is not untrusted")
    void healthyEnvelopeIsTrusted() {
        givenMessages(10);
        Conversation conv = new Conversation();
        Map<String, Object> env = new HashMap<>();
        env.put("turns_covered", List.of(0, 1, 2));
        env.put("status", "ok");
        conv.setSummaryCold(env);
        when(conversationRepo.findById(CONV)).thenReturn(Optional.of(conv));

        assertThat(captureRequest().envelopeInvalidated()).isFalse();
    }

    @Test
    @DisplayName("No envelope at all is not untrusted: there is nothing to distrust")
    void absentEnvelopeIsNotUntrusted() {
        givenMessages(10);
        when(conversationRepo.findById(CONV)).thenReturn(Optional.of(conversationCovering(null)));

        assertThat(captureRequest().envelopeInvalidated()).isFalse();
    }

    // -------------------------------------------------------------------------
    // Which condition earned the spend
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Every outcome that invoked the LLM is counted, not only the one that persisted")
    void outcomeTagCoversEverySpend() {
        // Failed and SkippedStaleWrite both paid for a call and produced no
        // envelope, and they are precisely the repeat-fire cases an operator
        // hunts. Counting only Persisted would answer "did it persist", while
        // the metric claims to answer "what did it cost".
        assertThat(ChatCompactionOrchestrator.outcomeTag(
                new SummarizeOutcome.Persisted(null))).isEqualTo("persisted");
        assertThat(ChatCompactionOrchestrator.outcomeTag(
                new SummarizeOutcome.Failed("boom"))).isEqualTo("failed");
        assertThat(ChatCompactionOrchestrator.outcomeTag(
                new SummarizeOutcome.SkippedStaleWrite())).isEqualTo("discarded");
        // SkippedGate spent nothing here, so it must not inflate the counter.
        assertThat(ChatCompactionOrchestrator.outcomeTag(new SummarizeOutcome.SkippedGate())).isNull();
    }

    @Test
    @DisplayName("A keyword is attributed first because it alone fires in every mode")
    void triggerReasonAttributesKeywordFirst() {
        for (CompactionTrigger mode : CompactionTrigger.values()) {
            assertThat(ChatCompactionOrchestrator.triggerReason(mode, true, false, 99_999, 32_000))
                    .as("keyword fires in %s, so it owns the attribution there", mode)
                    .isEqualTo("keyword");
        }
    }

    @Test
    @DisplayName("An untrusted envelope is attributed only where it can actually decide")
    void triggerReasonAttributesInvalidationOnlyWhereItCounts() {
        // It substitutes for the SIZE condition and nothing else, so in TURNS it
        // is causally inert. Reporting it there told a default deployment that
        // stale envelopes drove its spend when the cadence did, and because the
        // flag is seeded from the PERSISTED status the misreport stuck for every
        // later summary of the same conversation.
        assertThat(ChatCompactionOrchestrator.triggerReason(
                CompactionTrigger.TURNS, false, true, 0, 32_000)).isEqualTo("cadence");
        assertThat(ChatCompactionOrchestrator.triggerReason(
                null, false, true, 0, 32_000)).isEqualTo("cadence");
        assertThat(ChatCompactionOrchestrator.triggerReason(
                CompactionTrigger.SIZE, false, true, 0, 32_000)).isEqualTo("invalidated");
    }

    @Test
    @DisplayName("Growth is attributed before the substitution, mirroring the order the gate tests them")
    void triggerReasonPrefersGrowthOverTheSubstitution() {
        // With both available the pass is already earned by growth before the
        // substitution is consulted, so blaming the stale envelope would name a
        // cause that had no say. The shrink branch makes this the common case:
        // it sets the flag AND reports the whole COLD zone as growth.
        assertThat(ChatCompactionOrchestrator.triggerReason(
                CompactionTrigger.SIZE, false, /*invalidated*/ true,
                /*growth*/ 40_000, 32_000)).isEqualTo("size");
        assertThat(ChatCompactionOrchestrator.triggerReason(
                CompactionTrigger.SIZE_OR_TURNS, false, true, 40_000, 32_000)).isEqualTo("size");
    }

    @Test
    @DisplayName("In SIZE_OR_TURNS the substitution can never be the cause, so it is never the label")
    void triggerReasonNeverBlamesTheSubstitutionInSizeOrTurns() {
        // The substitution requires the cadence to have elapsed, which is exactly
        // what the cadence branch of SIZE_OR_TURNS fires on, so the flag cannot
        // change the outcome there. Labelling it would invent a cause.
        assertThat(ChatCompactionOrchestrator.triggerReason(
                CompactionTrigger.SIZE_OR_TURNS, false, /*invalidated*/ true,
                /*growth*/ 10, 32_000)).isEqualTo("cadence");
    }

    @Test
    @DisplayName("Growth is reported only when the mode consults it and the threshold is met")
    void triggerReasonDistinguishesGrowthFromCadence() {
        assertThat(ChatCompactionOrchestrator.triggerReason(
                CompactionTrigger.SIZE_OR_TURNS, false, false, 32_000, 32_000)).isEqualTo("size");
        assertThat(ChatCompactionOrchestrator.triggerReason(
                CompactionTrigger.SIZE_OR_TURNS, false, false, 31_999, 32_000)).isEqualTo("cadence");
        // TURNS never consults growth, so a huge growth is still a cadence pass.
        assertThat(ChatCompactionOrchestrator.triggerReason(
                CompactionTrigger.TURNS, false, false, 99_999, 32_000)).isEqualTo("cadence");
        assertThat(ChatCompactionOrchestrator.triggerReason(
                null, false, false, 99_999, 32_000)).isEqualTo("cadence");
    }

    @Test
    @DisplayName("A per-conversation cadence upgrades a global SIZE, so the user setting still applies")
    void explicitCadenceReachesTheRequestAsSizeOrTurns() {
        // End-to-end through the orchestrator: the resolver rule must survive the
        // trip, not just hold in its own unit test.
        config.setTrigger("size");
        givenMessages(10);
        Conversation conv = conversationCovering(null);
        Map<String, Object> chatConfig = new HashMap<>();
        Map<String, Object> comp = new HashMap<>();
        comp.put("afterTurns", 3);
        chatConfig.put("compaction", comp);
        conv.setChatConfig(chatConfig);
        when(conversationRepo.findById(CONV)).thenReturn(Optional.of(conv));

        SummarizeRequest req = captureRequest();

        assertThat(req.trigger()).isEqualTo(CompactionTrigger.SIZE_OR_TURNS);
        assertThat(req.cadenceTurns()).isEqualTo(3);
    }

    @Test
    @DisplayName("Global SIZE with no per-scope cadence stays SIZE")
    void globalSizeStaysSizeWithoutAnExplicitCadence() {
        config.setTrigger("size");
        givenMessages(10);
        when(conversationRepo.findById(CONV)).thenReturn(Optional.of(conversationCovering(null)));

        assertThat(captureRequest().trigger()).isEqualTo(CompactionTrigger.SIZE);
    }

    @Test
    @DisplayName("Growth ignores the HOT+WARM window: a fat newest turn is not COLD yet")
    void growthExcludesTheHotWindow() {
        // Messages 5..9 are HOT; only 0..4 are COLD. A huge result that just
        // landed is not summarisable yet, and must not be counted as growth.
        List<Message> msgs = fixedSizeMessages(10);
        msgs.set(9, message("x".repeat(CHARS_PER_MESSAGE * 100)));
        when(messageRepo.findByConversationIdOrderByCreatedAtAsc(CONV)).thenReturn(msgs);
        when(conversationRepo.findById(CONV)).thenReturn(Optional.of(conversationCovering(null)));

        SummarizeRequest req = captureRequest();

        assertThat(req.newColdTokensSinceLastSummary()).isEqualTo(5 * TOKENS_PER_MESSAGE);
    }

    // -------------------------------------------------------------------------
    // Config wiring
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Default config sends TURNS, so an untouched deployment keeps cadence-only behaviour")
    void defaultConfigIsTurns() {
        givenMessages(10);
        when(conversationRepo.findById(CONV)).thenReturn(Optional.of(conversationCovering(null)));

        assertThat(captureRequest().trigger()).isEqualTo(CompactionTrigger.TURNS);
    }

    @Test
    @DisplayName("Configured mode and growth threshold both reach the SummarizeRequest")
    void configuredTriggerReachesTheRequest() {
        config.setTrigger("size-or-turns");
        config.setSizeTriggerColdTokens(12_345);
        givenMessages(10);
        when(conversationRepo.findById(CONV)).thenReturn(Optional.of(conversationCovering(null)));

        SummarizeRequest req = captureRequest();

        assertThat(req.trigger()).isEqualTo(CompactionTrigger.SIZE_OR_TURNS);
        assertThat(req.sizeTriggerColdTokens()).isEqualTo(12_345);
    }

    @Test
    @DisplayName("An unreadable mode degrades to TURNS rather than disabling compaction")
    void unknownTriggerDegradesToTurns() {
        config.setTrigger("siize");
        givenMessages(10);
        when(conversationRepo.findById(CONV)).thenReturn(Optional.of(conversationCovering(null)));

        assertThat(captureRequest().trigger()).isEqualTo(CompactionTrigger.TURNS);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private SummarizeRequest captureRequest() {
        orchestrator.afterTurn(CONV, "anthropic", "claude", TENANT, null);
        ArgumentCaptor<SummarizeRequest> captor = ArgumentCaptor.forClass(SummarizeRequest.class);
        verify(summarizer).summarize(captor.capture(), any(LlmJsonInvoker.class));
        return captor.getValue();
    }

    private void givenMessages(int n) {
        when(messageRepo.findByConversationIdOrderByCreatedAtAsc(CONV)).thenReturn(fixedSizeMessages(n));
    }

    /** Every message is exactly {@link #TOKENS_PER_MESSAGE} tokens, so tallies are exact. */
    private static List<Message> fixedSizeMessages(int n) {
        List<Message> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(message("m".repeat(CHARS_PER_MESSAGE)));
        }
        return out;
    }

    private static Message message(String body) {
        Message m = new Message();
        m.setRole(Message.MessageRole.USER);
        m.setContent(body);
        m.setCreatedAt(LocalDateTime.now());
        return m;
    }

    /** {@code null} coverage means "no usable prior envelope". */
    private static Conversation conversationCovering(List<Integer> turnsCovered) {
        Conversation c = new Conversation();
        if (turnsCovered != null) {
            Map<String, Object> env = new HashMap<>();
            env.put("turns_covered", turnsCovered);
            c.setSummaryCold(env);
        }
        return c;
    }
}
