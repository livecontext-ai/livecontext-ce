package com.apimarketplace.conversation.service.ai;

import com.apimarketplace.agent.summary.ColdSummaryEnvelope;
import com.apimarketplace.agent.summary.ColdSummaryGate;
import com.apimarketplace.agent.summary.CompactionTrigger;
import com.apimarketplace.conversation.entity.Conversation;
import com.apimarketplace.conversation.entity.Message;
import com.apimarketplace.conversation.repository.ConversationRepository;
import com.apimarketplace.conversation.repository.MessageRepository;
import com.apimarketplace.conversation.service.ai.ColdSummarizerService.LlmJsonInvoker;
import com.apimarketplace.conversation.service.ai.ColdSummarizerService.SummarizeOutcome;
import com.apimarketplace.conversation.service.ai.ColdSummarizerService.SummarizeRequest;
import com.apimarketplace.conversation.streaming.StreamPubSubService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Exercises the counter through the REAL registry rather than as a pure
 * function: the series name, the tag pair and the "which outcomes count" rule
 * are what an operator actually reads, and a pure-function test on
 * {@code triggerReason} verifies none of them.
 *
 * <p>The clamp is likewise checked end to end. Asserting it only on the config
 * bean cannot tell whether the orchestrator sends the clamped value or the raw
 * one, which is the thing that decides behaviour.
 */
@DisplayName("Compaction telemetry and clamp, through the real registry")
class CompactionTriggerTelemetryTest {

    private static final String CONV = "conv-metrics";
    private static final String TENANT = "tenant-42";
    private static final int CHARS_PER_MESSAGE = 4000;

    private MessageRepository messageRepo;
    private ConversationRepository conversationRepo;
    private ColdSummarizerService summarizer;
    private CompactionDefaultsConfig config;
    private SimpleMeterRegistry registry;
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
        config.setHotWarmTurnWindow(5);
        config.setCadenceTurns(3);

        registry = new SimpleMeterRegistry();
        orchestrator = new ChatCompactionOrchestrator(
                messageRepo, conversationRepo, summarizer, httpInvoker, config,
                streamPubSub, agentConfigProvider, registry);

        when(messageRepo.findByConversationIdOrderByCreatedAtAsc(CONV)).thenReturn(messages(10));
        when(conversationRepo.findById(CONV)).thenReturn(Optional.of(new Conversation()));
    }

    @Test
    @DisplayName("A persisted pass registers one series carrying both the reason and the outcome")
    void persistedPassIsCounted() {
        givenOutcome(new SummarizeOutcome.Persisted(null));

        orchestrator.afterTurn(CONV, "anthropic", "claude", TENANT, null);

        assertThat(count("cadence", "persisted")).isEqualTo(1.0);
        assertThat(seriesCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("A failed pass is counted too: the call was made and charged, it simply produced nothing")
    void failedPassIsCounted() {
        givenOutcome(new SummarizeOutcome.Failed("provider down"));

        orchestrator.afterTurn(CONV, "anthropic", "claude", TENANT, null);

        assertThat(count("cadence", "failed")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("A discarded write is counted: the monotone guard rejected output the LLM was paid for")
    void discardedWriteIsCounted() {
        givenOutcome(new SummarizeOutcome.SkippedStaleWrite());

        orchestrator.afterTurn(CONV, "anthropic", "claude", TENANT, null);

        assertThat(count("cadence", "discarded")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("A gate refusal registers NO series: nothing was spent, so a cost counter must stay silent")
    void gateRefusalIsNotCounted() {
        givenOutcome(new SummarizeOutcome.SkippedGate());

        orchestrator.afterTurn(CONV, "anthropic", "claude", TENANT, null);

        assertThat(seriesCount())
                .as("counting a refusal would make sum(cold_summary_fired_total) stop meaning cost")
                .isZero();
    }

    @Test
    @DisplayName("An untrusted envelope is attributed to the cadence in TURNS, where it cannot decide anything")
    void turnsAttributionIsNotInvalidated() {
        // The flag is seeded from the PERSISTED stale status, so a wrong label
        // here would stick to the conversation for every later summary.
        Conversation stale = new Conversation();
        stale.setSummaryCold(new java.util.HashMap<>(java.util.Map.of(
                "turns_covered", List.of(0, 1, 2),
                "status", ColdSummaryEnvelope.STATUS_STALE)));
        when(conversationRepo.findById(CONV)).thenReturn(Optional.of(stale));
        givenOutcome(new SummarizeOutcome.Persisted(null));

        orchestrator.afterTurn(CONV, "anthropic", "claude", TENANT, null);

        assertThat(count("cadence", "persisted")).isEqualTo(1.0);
        assertThat(count("invalidated", "persisted")).isZero();
    }

    @Test
    @DisplayName("Tag cardinality stays bounded at reason x outcome, whatever the traffic")
    void cardinalityStaysBounded() {
        givenOutcome(new SummarizeOutcome.Persisted(null));
        for (int i = 0; i < 25; i++) {
            orchestrator.afterTurn(CONV, "anthropic", "claude", TENANT, null);
        }

        assertThat(seriesCount()).isEqualTo(1);
        assertThat(count("cadence", "persisted")).isEqualTo(25.0);
    }

    // -------------------------------------------------------------------------
    // Clamp, end to end
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("The orchestrator sends the CLAMPED threshold, not the raw configured value")
    void clampReachesTheRequest() {
        // Asserting the clamp on the config bean alone would still pass if the
        // orchestrator read getSizeTriggerColdTokens() instead.
        config.setSizeTriggerColdTokens(10);
        config.setTrigger("size");
        givenOutcome(new SummarizeOutcome.SkippedGate());

        SummarizeRequest req = capture();

        assertThat(req.sizeTriggerColdTokens()).isEqualTo(ColdSummaryGate.MIN_COLD_TOKENS_FLOOR);
        assertThat(req.trigger()).isEqualTo(CompactionTrigger.SIZE);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private SummarizeRequest capture() {
        org.mockito.ArgumentCaptor<SummarizeRequest> captor =
                org.mockito.ArgumentCaptor.forClass(SummarizeRequest.class);
        orchestrator.afterTurn(CONV, "anthropic", "claude", TENANT, null);
        org.mockito.Mockito.verify(summarizer).summarize(captor.capture(), any(LlmJsonInvoker.class));
        return captor.getValue();
    }

    private void givenOutcome(SummarizeOutcome outcome) {
        when(summarizer.summarize(any(SummarizeRequest.class), any(LlmJsonInvoker.class)))
                .thenReturn(outcome);
    }

    private double count(String reason, String outcome) {
        Counter c = registry.find("cold_summary_fired_total")
                .tag("reason", reason).tag("outcome", outcome).counter();
        return c == null ? 0.0 : c.count();
    }

    private long seriesCount() {
        return registry.find("cold_summary_fired_total").counters().size();
    }

    private static List<Message> messages(int n) {
        List<Message> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Message m = new Message();
            m.setRole(Message.MessageRole.USER);
            m.setContent("m".repeat(CHARS_PER_MESSAGE));
            m.setCreatedAt(LocalDateTime.now());
            out.add(m);
        }
        return out;
    }
}
