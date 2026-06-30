package com.apimarketplace.conversation.service.ai;

import com.apimarketplace.agent.summary.ColdSummarizerPromptBuilder.Turn;
import com.apimarketplace.agent.summary.ColdSummaryEnvelope;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Stage 2 follow-up (#51) - behaviour pins for {@link ChatCompactionOrchestrator}.
 *
 * <p>Coverage matrix:
 * <ul>
 *   <li>Master enable-flag is honoured - disabled config → {@link SummarizeOutcome.SkippedGate}
 *       and zero downstream calls.</li>
 *   <li>Short conversations (≤ HOT+WARM window) skip without a summariser hit.</li>
 *   <li>Long conversations populate the {@link SummarizeRequest} correctly:
 *       cold-zone splitting, cold-token tally, turn indices, role labels,
 *       summariser provider/model from YAML defaults.</li>
 *   <li>{@code turnsSinceLastSummary} reflects prior envelope's
 *       {@code turns_covered} - no envelope → full COLD size; envelope present
 *       → delta from the last covered turn.</li>
 *   <li>Keyword invalidation triggers on the LATEST USER message, not just
 *       what's in the COLD zone.</li>
 *   <li>The tenant-bound invoker forwards {@code tenantId} through the
 *       {@link HttpLlmJsonInvoker} five-arg overload (X-User-ID at the wire).</li>
 *   <li>Per-provider COLD cap resolution: anthropic → 4000, google → 2500,
 *       unknown / null → default 4000.</li>
 *   <li>{@code afterTurnAsync} swallows exceptions - chat must never break
 *       because the summariser tripped.</li>
 * </ul>
 */
@DisplayName("ChatCompactionOrchestrator - post-turn hook")
class ChatCompactionOrchestratorTest {

    private static final String CONV = "conv-123";
    private static final String TENANT = "tenant-42";

    private MessageRepository messageRepo;
    private ConversationRepository conversationRepo;
    private ColdSummarizerService summarizer;
    private HttpLlmJsonInvoker httpInvoker;
    private CompactionDefaultsConfig config;
    private StreamPubSubService streamPubSub;
    private AgentConfigProvider agentConfigProvider;
    private SimpleMeterRegistry meterRegistry;

    private ChatCompactionOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        messageRepo = mock(MessageRepository.class);
        conversationRepo = mock(ConversationRepository.class);
        summarizer = mock(ColdSummarizerService.class);
        httpInvoker = mock(HttpLlmJsonInvoker.class);
        streamPubSub = mock(StreamPubSubService.class);
        // Return an empty Mono so the orchestrator's .subscribe() call is safe in tests.
        when(streamPubSub.publishCompactionDone(anyString(), anyString(), anyInt(), anyString(), any(Instant.class)))
                .thenReturn(Mono.empty());
        config = new CompactionDefaultsConfig();
        config.setEnabled(true);           // default is false (opt-in per env); flip for tests
        config.setHotWarmTurnWindow(5);    // tiny window so tests stay short
        config.setCadenceTurns(3);
        agentConfigProvider = mock(AgentConfigProvider.class);
        // Default: agent has no compaction override (callers fall back to conv/YAML).
        when(agentConfigProvider.getCompactionOverride(anyString(), any()))
                .thenReturn(AgentConfigProvider.CompactionOverride.NONE);
        meterRegistry = new SimpleMeterRegistry();
        // YAML defaults (anthropic/claude-haiku-4-5) are already set by the config ctor
        orchestrator = new ChatCompactionOrchestrator(
                messageRepo, conversationRepo, summarizer, httpInvoker, config,
                streamPubSub, agentConfigProvider, meterRegistry);

        // Default summariser stub - overridden when tests need a specific outcome.
        when(summarizer.summarize(any(SummarizeRequest.class), any(LlmJsonInvoker.class)))
                .thenReturn(new SummarizeOutcome.SkippedGate());
    }

    // -------------------------------------------------------------------------
    // Master switch
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Globally disabled with no per-scope override - SkippedGate before loading messages")
    void disabledWithNoOverrideSkipsBeforeMessages() {
        config.setEnabled(false);
        when(conversationRepo.findById(CONV)).thenReturn(Optional.of(emptyConversation()));

        SummarizeOutcome outcome = orchestrator.afterTurn(CONV, "anthropic", "claude", TENANT, null);

        assertThat(outcome).isInstanceOf(SummarizeOutcome.SkippedGate.class);
        // The conversation is read to check for a per-scope override, but the heavier
        // message load is skipped once the effective config resolves to disabled.
        verify(messageRepo, never()).findByConversationIdOrderByCreatedAtAsc(any());
        verify(summarizer, never()).summarize(any(), any());
    }

    // -------------------------------------------------------------------------
    // Per-scope effective config (V350): conversation > agent > YAML
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Per-conversation enabled=true overrides a globally-disabled config - compaction runs")
    void perConversationEnableOverridesGlobalDisable() {
        config.setEnabled(false); // global master off
        when(messageRepo.findByConversationIdOrderByCreatedAtAsc(CONV)).thenReturn(messages(8));
        when(conversationRepo.findById(CONV))
                .thenReturn(Optional.of(conversationWithCompaction(true, null, null)));

        orchestrator.afterTurn(CONV, "anthropic", "claude", TENANT, null);

        // The per-conversation override flips it on even though the YAML default is off.
        verify(summarizer).summarize(any(SummarizeRequest.class), any(LlmJsonInvoker.class));
    }

    @Test
    @DisplayName("Per-conversation enabled=false overrides a globally-enabled config - SkippedGate")
    void perConversationDisableOverridesGlobalEnable() {
        // config.enabled stays true (set in setUp)
        when(conversationRepo.findById(CONV))
                .thenReturn(Optional.of(conversationWithCompaction(false, null, null)));

        SummarizeOutcome outcome = orchestrator.afterTurn(CONV, "anthropic", "claude", TENANT, null);

        assertThat(outcome).isInstanceOf(SummarizeOutcome.SkippedGate.class);
        verify(messageRepo, never()).findByConversationIdOrderByCreatedAtAsc(any());
        verify(summarizer, never()).summarize(any(), any());
    }

    @Test
    @DisplayName("Per-conversation afterTurns flows into the SummarizeRequest cadence (overrides YAML cadence)")
    void perConversationAfterTurnsFlowsIntoRequest() {
        when(messageRepo.findByConversationIdOrderByCreatedAtAsc(CONV)).thenReturn(messages(8));
        when(conversationRepo.findById(CONV))
                .thenReturn(Optional.of(conversationWithCompaction(null, 7, null)));

        orchestrator.afterTurn(CONV, "anthropic", "claude", TENANT, null);

        ArgumentCaptor<SummarizeRequest> captor = ArgumentCaptor.forClass(SummarizeRequest.class);
        verify(summarizer).summarize(captor.capture(), any(LlmJsonInvoker.class));
        assertThat(captor.getValue().cadenceTurns()).isEqualTo(7); // not the YAML 3
    }

    @Test
    @DisplayName("Per-agent override is consulted when the conversation is unset, and drives enable + cadence")
    void perAgentOverrideUsedWhenConversationUnset() {
        config.setEnabled(false); // global off; only the agent enables it
        when(messageRepo.findByConversationIdOrderByCreatedAtAsc(CONV)).thenReturn(messages(8));
        when(conversationRepo.findById(CONV))
                .thenReturn(Optional.of(conversationWithCompaction(null, null, "agent-7")));
        when(agentConfigProvider.getCompactionOverride(eq("agent-7"), any()))
                .thenReturn(new AgentConfigProvider.CompactionOverride(true, 9));

        orchestrator.afterTurn(CONV, "anthropic", "claude", TENANT, null);

        ArgumentCaptor<SummarizeRequest> captor = ArgumentCaptor.forClass(SummarizeRequest.class);
        verify(summarizer).summarize(captor.capture(), any(LlmJsonInvoker.class));
        assertThat(captor.getValue().cadenceTurns()).isEqualTo(9);
    }

    @Test
    @DisplayName("Agent RPC is skipped when the conversation already pins BOTH enable + cadence")
    void agentFetchSkippedWhenConversationPinsBothFields() {
        when(messageRepo.findByConversationIdOrderByCreatedAtAsc(CONV)).thenReturn(messages(8));
        when(conversationRepo.findById(CONV))
                .thenReturn(Optional.of(conversationWithCompaction(true, 4, "agent-7")));

        orchestrator.afterTurn(CONV, "anthropic", "claude", TENANT, null);

        // Both fields resolved at the conversation tier → no per-agent fetch.
        verify(agentConfigProvider, never()).getCompactionOverride(anyString(), any());
        ArgumentCaptor<SummarizeRequest> captor = ArgumentCaptor.forClass(SummarizeRequest.class);
        verify(summarizer).summarize(captor.capture(), any(LlmJsonInvoker.class));
        assertThat(captor.getValue().cadenceTurns()).isEqualTo(4);
    }

    // -------------------------------------------------------------------------
    // Window short-circuit
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Conversation at-or-below HOT+WARM window skips without summariser hit")
    void belowWindowSkips() {
        when(messageRepo.findByConversationIdOrderByCreatedAtAsc(CONV))
                .thenReturn(messages(5)); // window=5, exactly at boundary

        SummarizeOutcome outcome = orchestrator.afterTurn(CONV, "anthropic", "claude", TENANT, null);

        assertThat(outcome).isInstanceOf(SummarizeOutcome.SkippedGate.class);
        verify(summarizer, never()).summarize(any(), any());
    }

    @Test
    @DisplayName("Window transition: size == window+1 → cold=1 (first message past the boundary fires)")
    void firstMessagePastWindowFires() {
        // Pins the `<=` vs `<` distinction - bump of one over the window must
        // produce a single-COLD-turn request, not another SkippedGate.
        when(messageRepo.findByConversationIdOrderByCreatedAtAsc(CONV))
                .thenReturn(messages(6)); // window=5 → cold=1
        when(conversationRepo.findById(CONV)).thenReturn(Optional.of(emptyConversation()));

        orchestrator.afterTurn(CONV, "anthropic", "claude", TENANT, null);

        ArgumentCaptor<SummarizeRequest> captor = ArgumentCaptor.forClass(SummarizeRequest.class);
        verify(summarizer).summarize(captor.capture(), any(LlmJsonInvoker.class));
        assertThat(captor.getValue().coldTurns()).hasSize(1);
        assertThat(captor.getValue().turnsCovered()).containsExactly(0);
    }

    // -------------------------------------------------------------------------
    // Happy path: full request shape
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Builds request with cold split, token tally, default summariser model, default cold cap")
    void happyPathBuildsCorrectRequest() {
        // 8 messages, window=5 → cold = first 3
        when(messageRepo.findByConversationIdOrderByCreatedAtAsc(CONV))
                .thenReturn(messages(8));
        when(conversationRepo.findById(CONV)).thenReturn(Optional.of(emptyConversation()));

        orchestrator.afterTurn(CONV, "anthropic", "claude-sonnet-4-6", TENANT, null);

        ArgumentCaptor<SummarizeRequest> captor = ArgumentCaptor.forClass(SummarizeRequest.class);
        verify(summarizer).summarize(captor.capture(), any(LlmJsonInvoker.class));
        SummarizeRequest req = captor.getValue();

        assertThat(req.conversationId()).isEqualTo(CONV);
        assertThat(req.modelColdCapTokens()).isEqualTo(4000);   // anthropic default cap
        assertThat(req.coldTurns()).hasSize(3);
        assertThat(req.turnsCovered()).containsExactly(0, 1, 2);
        assertThat(req.providerName()).isEqualTo("anthropic");  // YAML default
        assertThat(req.modelName()).isEqualTo("claude-haiku-4-5");
        assertThat(req.cadenceTurns()).isEqualTo(3);            // from config
        assertThat(req.turnsSinceLastSummary()).isEqualTo(3);   // no prior envelope
        // currentColdTokens = sum of (chars/4) over the 3 cold messages.
        // Each message body: "msg-N" (~5 chars) → 1 token each → 3 total.
        assertThat(req.currentColdTokens()).isEqualTo(3);
    }

    @Test
    @DisplayName("Role labels project Message.MessageRole.value() - assistant/tool/user/system")
    void roleLabelsProjected() {
        List<Message> msgs = new ArrayList<>();
        msgs.add(messageWithRole(Message.MessageRole.USER, "u"));
        msgs.add(messageWithRole(Message.MessageRole.ASSISTANT, "a"));
        msgs.add(messageWithRole(Message.MessageRole.TOOL, "t"));
        // pad with HOT+WARM messages
        for (int i = 0; i < 5; i++) msgs.add(messageWithRole(Message.MessageRole.USER, "hot"));
        when(messageRepo.findByConversationIdOrderByCreatedAtAsc(CONV)).thenReturn(msgs);
        when(conversationRepo.findById(CONV)).thenReturn(Optional.of(emptyConversation()));

        orchestrator.afterTurn(CONV, "anthropic", "claude", TENANT, null);

        ArgumentCaptor<SummarizeRequest> captor = ArgumentCaptor.forClass(SummarizeRequest.class);
        verify(summarizer).summarize(captor.capture(), any(LlmJsonInvoker.class));
        List<Turn> turns = captor.getValue().coldTurns();
        assertThat(turns).extracting(Turn::roleLabel).containsExactly("user", "assistant", "tool");
        assertThat(turns).extracting(Turn::body).containsExactly("u", "a", "t");
    }

    // -------------------------------------------------------------------------
    // turnsSinceLastSummary derivation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("turnsSinceLastSummary equals COLD size when no prior envelope")
    void noPriorEnvelopeYieldsFullColdSize() {
        when(messageRepo.findByConversationIdOrderByCreatedAtAsc(CONV))
                .thenReturn(messages(10)); // cold = first 5 (window=5)
        when(conversationRepo.findById(CONV)).thenReturn(Optional.of(emptyConversation()));

        orchestrator.afterTurn(CONV, "anthropic", "claude", TENANT, null);

        ArgumentCaptor<SummarizeRequest> captor = ArgumentCaptor.forClass(SummarizeRequest.class);
        verify(summarizer).summarize(captor.capture(), any(LlmJsonInvoker.class));
        assertThat(captor.getValue().turnsSinceLastSummary()).isEqualTo(5);
    }

    @Test
    @DisplayName("turnsSinceLastSummary subtracts max prior turns_covered from current cold last index")
    void priorEnvelopeShrinksTurnsSince() {
        // 10 messages, cold = [0..4], envelope previously covered turns [0,1,2]
        // → last covered = 2, current cold last index = 4, delta = 2
        when(messageRepo.findByConversationIdOrderByCreatedAtAsc(CONV))
                .thenReturn(messages(10));
        Conversation conv = new Conversation();
        Map<String, Object> env = new HashMap<>();
        env.put("turns_covered", List.of(0, 1, 2));
        conv.setSummaryCold(env);
        when(conversationRepo.findById(CONV)).thenReturn(Optional.of(conv));

        orchestrator.afterTurn(CONV, "anthropic", "claude", TENANT, null);

        ArgumentCaptor<SummarizeRequest> captor = ArgumentCaptor.forClass(SummarizeRequest.class);
        verify(summarizer).summarize(captor.capture(), any(LlmJsonInvoker.class));
        assertThat(captor.getValue().turnsSinceLastSummary()).isEqualTo(2);
    }

    @Test
    @DisplayName("Prior envelope with empty turns_covered list falls back to full cold size")
    void priorEnvelopeEmptyTurnsCoveredFallsBack() {
        when(messageRepo.findByConversationIdOrderByCreatedAtAsc(CONV))
                .thenReturn(messages(10));
        Conversation conv = new Conversation();
        Map<String, Object> env = new HashMap<>();
        env.put("turns_covered", new ArrayList<>()); // key present but empty
        conv.setSummaryCold(env);
        when(conversationRepo.findById(CONV)).thenReturn(Optional.of(conv));

        orchestrator.afterTurn(CONV, "anthropic", "claude", TENANT, null);

        ArgumentCaptor<SummarizeRequest> captor = ArgumentCaptor.forClass(SummarizeRequest.class);
        verify(summarizer).summarize(captor.capture(), any(LlmJsonInvoker.class));
        assertThat(captor.getValue().turnsSinceLastSummary()).isEqualTo(5);
    }

    @Test
    @DisplayName("Prior envelope without turns_covered field falls back to full cold size")
    void priorEnvelopeWithoutTurnsCoveredFallsBack() {
        when(messageRepo.findByConversationIdOrderByCreatedAtAsc(CONV))
                .thenReturn(messages(10));
        Conversation conv = new Conversation();
        conv.setSummaryCold(new HashMap<>()); // present but empty
        when(conversationRepo.findById(CONV)).thenReturn(Optional.of(conv));

        orchestrator.afterTurn(CONV, "anthropic", "claude", TENANT, null);

        ArgumentCaptor<SummarizeRequest> captor = ArgumentCaptor.forClass(SummarizeRequest.class);
        verify(summarizer).summarize(captor.capture(), any(LlmJsonInvoker.class));
        assertThat(captor.getValue().turnsSinceLastSummary()).isEqualTo(5);
    }

    @Test
    @DisplayName("turns_covered with mixed non-integer entries: strings/nulls ignored, max Number still wins")
    void priorEnvelopeMixedTypesTolerated() {
        // Defensive pin: the JSONB field may round-trip as a mix (legacy writes,
        // hand-edits). The orchestrator must walk the list, pick Number entries
        // only, and not NPE on nulls or explode on strings.
        when(messageRepo.findByConversationIdOrderByCreatedAtAsc(CONV))
                .thenReturn(messages(10)); // cold = [0..4]
        Conversation conv = new Conversation();
        Map<String, Object> env = new HashMap<>();
        List<Object> mixed = new ArrayList<>();
        mixed.add("not-a-number");
        mixed.add(null);
        mixed.add(2);                   // Integer - should be picked up
        mixed.add(1L);                  // Long - Number subclass, counts
        mixed.add("also-not-a-number");
        env.put("turns_covered", mixed);
        conv.setSummaryCold(env);
        when(conversationRepo.findById(CONV)).thenReturn(Optional.of(conv));

        orchestrator.afterTurn(CONV, "anthropic", "claude", TENANT, null);

        // prevMax = 2 (Long 1 < Integer 2); cold last index = 4 → delta = 2
        ArgumentCaptor<SummarizeRequest> captor = ArgumentCaptor.forClass(SummarizeRequest.class);
        verify(summarizer).summarize(captor.capture(), any(LlmJsonInvoker.class));
        assertThat(captor.getValue().turnsSinceLastSummary()).isEqualTo(2);
    }

    @Test
    @DisplayName("All-null COLD content (TOOL rows with no body) tallies 0 tokens without NPE")
    void allNullColdContentIsSafe() {
        // Regression: Message.getContent() can return null (e.g. tool-call rows
        // where the structured payload lives elsewhere). The orchestrator must
        // substitute "" so length()/4 stays defined and the request still ships.
        List<Message> msgs = new ArrayList<>();
        for (int i = 0; i < 3; i++) msgs.add(messageWithRole(Message.MessageRole.TOOL, null));
        for (int i = 0; i < 5; i++) msgs.add(messageWithRole(Message.MessageRole.USER, "hot"));
        when(messageRepo.findByConversationIdOrderByCreatedAtAsc(CONV)).thenReturn(msgs);
        when(conversationRepo.findById(CONV)).thenReturn(Optional.of(emptyConversation()));

        orchestrator.afterTurn(CONV, "anthropic", "claude", TENANT, null);

        ArgumentCaptor<SummarizeRequest> captor = ArgumentCaptor.forClass(SummarizeRequest.class);
        verify(summarizer).summarize(captor.capture(), any(LlmJsonInvoker.class));
        SummarizeRequest req = captor.getValue();
        assertThat(req.currentColdTokens()).isEqualTo(0);
        assertThat(req.coldTurns()).hasSize(3);
        assertThat(req.coldTurns()).extracting(Turn::body).containsExactly("", "", "");
    }

    // -------------------------------------------------------------------------
    // Shrink detection + status-aware stale marking (regression:
    // non-monotone / status-blind COLD recall)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("regression - COLD zone shrank under the stored summary → markStale('cold-shrink') + full cadence pressure")
    void coldShrinkMarksStaleAndAppliesFullCadencePressure() {
        // 10 messages with window=5 → cold = [0..4], but the stored envelope
        // claims coverage up to turn 7: the history shrank under it (window
        // reconfiguration or deleted turns). Pre-fix the delta clamped to 0,
        // silently starving the cadence gate while a now-wrong summary kept
        // being recalled as authoritative. Post-fix: the envelope is flagged
        // stale (caveated recall + monotone-guard escape hatch) and the
        // cadence sees the full COLD size so a regeneration fires promptly.
        when(messageRepo.findByConversationIdOrderByCreatedAtAsc(CONV))
                .thenReturn(messages(10));
        Conversation conv = new Conversation();
        Map<String, Object> env = new HashMap<>();
        env.put("turns_covered", List.of(0, 1, 2, 3, 4, 5, 6, 7));
        conv.setSummaryCold(env);
        when(conversationRepo.findById(CONV)).thenReturn(Optional.of(conv));

        orchestrator.afterTurn(CONV, "anthropic", "claude", TENANT, null);

        verify(summarizer).markStale(CONV, "cold-shrink");
        ArgumentCaptor<SummarizeRequest> captor = ArgumentCaptor.forClass(SummarizeRequest.class);
        verify(summarizer).summarize(captor.capture(), any(LlmJsonInvoker.class));
        assertThat(captor.getValue().turnsSinceLastSummary()).isEqualTo(5);
    }

    @Test
    @DisplayName("prior coverage exactly at the COLD boundary is NOT a shrink - no markStale, delta 0")
    void priorCoverageAtBoundaryIsNotShrink() {
        // prevMax = 4 == coldSize-1: the envelope covers exactly the current
        // COLD zone. That is steady state, not corruption - flagging it stale
        // would caveat every fully-covered conversation.
        when(messageRepo.findByConversationIdOrderByCreatedAtAsc(CONV))
                .thenReturn(messages(10));
        Conversation conv = new Conversation();
        Map<String, Object> env = new HashMap<>();
        env.put("turns_covered", List.of(0, 1, 2, 3, 4));
        conv.setSummaryCold(env);
        when(conversationRepo.findById(CONV)).thenReturn(Optional.of(conv));

        orchestrator.afterTurn(CONV, "anthropic", "claude", TENANT, null);

        verify(summarizer, never()).markStale(anyString(), anyString());
        ArgumentCaptor<SummarizeRequest> captor = ArgumentCaptor.forClass(SummarizeRequest.class);
        verify(summarizer).summarize(captor.capture(), any(LlmJsonInvoker.class));
        assertThat(captor.getValue().turnsSinceLastSummary()).isEqualTo(0);
    }

    @Test
    @DisplayName("regression - keyword hit without a persisted regeneration → markStale('invalidation-regen-missed')")
    void keywordWithoutPersistedRegenMarksStale() {
        // The user corrected course but no fresh envelope landed (gate skip /
        // lock contention / LLM failure). Pre-fix the stored summary kept its
        // authoritative "trust it / do NOT redo work" framing although the
        // user had just walked part of it back.
        when(messageRepo.findByConversationIdOrderByCreatedAtAsc(CONV))
                .thenReturn(messagesEndingWithKeyword());
        Conversation conv = new Conversation();
        Map<String, Object> env = new HashMap<>();
        env.put("turns_covered", List.of(0, 1));
        conv.setSummaryCold(env);
        when(conversationRepo.findById(CONV)).thenReturn(Optional.of(conv));
        // Default stub already returns SkippedGate (non-Persisted).

        orchestrator.afterTurn(CONV, "anthropic", "claude", TENANT, null);

        verify(summarizer).markStale(CONV, "invalidation-regen-missed");
    }

    @Test
    @DisplayName("keyword hit with a persisted regeneration does NOT mark stale - the fresh envelope is active")
    void keywordWithPersistedRegenDoesNotMarkStale() {
        when(messageRepo.findByConversationIdOrderByCreatedAtAsc(CONV))
                .thenReturn(messagesEndingWithKeyword());
        Conversation conv = new Conversation();
        Map<String, Object> env = new HashMap<>();
        env.put("turns_covered", List.of(0, 1));
        conv.setSummaryCold(env);
        when(conversationRepo.findById(CONV)).thenReturn(Optional.of(conv));
        ColdSummaryEnvelope fresh = new ColdSummaryEnvelope(
                List.of(), Map.of(), List.of(), List.of(), List.of(),
                "2026-04-21T10:00:00Z", "claude-haiku-4-5", 42, List.of(0, 1, 2));
        when(summarizer.summarize(any(SummarizeRequest.class), any(LlmJsonInvoker.class)))
                .thenReturn(new SummarizeOutcome.Persisted(fresh));

        orchestrator.afterTurn(CONV, "anthropic", "claude", TENANT, null);

        verify(summarizer, never()).markStale(anyString(), anyString());
    }

    @Test
    @DisplayName("keyword hit with NO stored envelope does not mark stale - nothing to caveat")
    void keywordWithoutPriorEnvelopeDoesNotMarkStale() {
        when(messageRepo.findByConversationIdOrderByCreatedAtAsc(CONV))
                .thenReturn(messagesEndingWithKeyword());
        when(conversationRepo.findById(CONV)).thenReturn(Optional.of(emptyConversation()));

        orchestrator.afterTurn(CONV, "anthropic", "claude", TENANT, null);

        verify(summarizer, never()).markStale(anyString(), anyString());
    }

    @Test
    @DisplayName("keyword hit + SkippedStaleWrite (racer won) also marks stale - racer's envelope may predate the correction")
    void keywordWithSkippedStaleWriteMarksStale() {
        // SkippedStaleWrite means another writer's envelope just landed - but
        // it may have been generated from messages loaded BEFORE the user's
        // correction turn. Conservatively caveat it; the next persisted
        // envelope re-activates. Also pin that the rejected write never
        // publishes a CompactionDone divider (nothing of ours persisted).
        when(messageRepo.findByConversationIdOrderByCreatedAtAsc(CONV))
                .thenReturn(messagesEndingWithKeyword());
        Conversation conv = new Conversation();
        Map<String, Object> env = new HashMap<>();
        env.put("turns_covered", List.of(0, 1));
        conv.setSummaryCold(env);
        when(conversationRepo.findById(CONV)).thenReturn(Optional.of(conv));
        when(summarizer.summarize(any(SummarizeRequest.class), any(LlmJsonInvoker.class)))
                .thenReturn(new SummarizeOutcome.SkippedStaleWrite());

        orchestrator.afterTurn(CONV, "anthropic", "claude", TENANT, "stream-abc");

        verify(summarizer).markStale(CONV, "invalidation-regen-missed");
        verifyNoInteractions(streamPubSub);
    }

    @Test
    @DisplayName("non-Persisted outcome without a keyword hit does not mark stale - staleness needs a signal")
    void nonPersistedWithoutKeywordDoesNotMarkStale() {
        // A routine gate skip (cadence not reached) on a healthy envelope
        // must not caveat recall - only an explicit correction or a shrink
        // justifies downgrading the summary's trust level.
        when(messageRepo.findByConversationIdOrderByCreatedAtAsc(CONV))
                .thenReturn(messages(10));
        Conversation conv = new Conversation();
        Map<String, Object> env = new HashMap<>();
        env.put("turns_covered", List.of(0, 1, 2));
        conv.setSummaryCold(env);
        when(conversationRepo.findById(CONV)).thenReturn(Optional.of(conv));

        orchestrator.afterTurn(CONV, "anthropic", "claude", TENANT, null);

        verify(summarizer, never()).markStale(anyString(), anyString());
    }

    // -------------------------------------------------------------------------
    // Keyword invalidation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Keyword hit reads from the LATEST user message - pivot in HOT zone fires invalidation")
    void keywordHitOnLatestUserMessage() {
        List<Message> msgs = new ArrayList<>();
        // 3 cold (no keyword)
        for (int i = 0; i < 3; i++) msgs.add(messageWithRole(Message.MessageRole.USER, "filler"));
        // 4 hot+warm - last user message contains a sentence-initial pivot keyword
        msgs.add(messageWithRole(Message.MessageRole.ASSISTANT, "ack"));
        msgs.add(messageWithRole(Message.MessageRole.ASSISTANT, "ack"));
        msgs.add(messageWithRole(Message.MessageRole.ASSISTANT, "ack"));
        msgs.add(messageWithRole(Message.MessageRole.USER, "Actually, scratch that"));
        when(messageRepo.findByConversationIdOrderByCreatedAtAsc(CONV)).thenReturn(msgs);
        when(conversationRepo.findById(CONV)).thenReturn(Optional.of(emptyConversation()));

        orchestrator.afterTurn(CONV, "anthropic", "claude", TENANT, null);

        ArgumentCaptor<SummarizeRequest> captor = ArgumentCaptor.forClass(SummarizeRequest.class);
        verify(summarizer).summarize(captor.capture(), any(LlmJsonInvoker.class));
        assertThat(captor.getValue().keywordHit()).isTrue();
    }

    @Test
    @DisplayName("No pivot keyword anywhere → keywordHit is false")
    void noKeywordHit() {
        when(messageRepo.findByConversationIdOrderByCreatedAtAsc(CONV))
                .thenReturn(messages(8));
        when(conversationRepo.findById(CONV)).thenReturn(Optional.of(emptyConversation()));

        orchestrator.afterTurn(CONV, "anthropic", "claude", TENANT, null);

        ArgumentCaptor<SummarizeRequest> captor = ArgumentCaptor.forClass(SummarizeRequest.class);
        verify(summarizer).summarize(captor.capture(), any(LlmJsonInvoker.class));
        assertThat(captor.getValue().keywordHit()).isFalse();
    }

    // -------------------------------------------------------------------------
    // Tenant-bound invoker
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("LlmJsonInvoker handed to summarizer forwards tenantId to HttpLlmJsonInvoker.invoke(5-arg)")
    void invokerForwardsTenantId() {
        when(messageRepo.findByConversationIdOrderByCreatedAtAsc(CONV))
                .thenReturn(messages(8));
        when(conversationRepo.findById(CONV)).thenReturn(Optional.of(emptyConversation()));
        // Invoke the lambda the orchestrator hands to summarize() so the call lands on httpInvoker.
        when(summarizer.summarize(any(SummarizeRequest.class), any(LlmJsonInvoker.class)))
                .thenAnswer(inv -> {
                    LlmJsonInvoker boundInvoker = inv.getArgument(1);
                    boundInvoker.invoke("anthropic", "claude-haiku-4-5", "SYS", "USER");
                    return new SummarizeOutcome.SkippedGate();
                });
        when(httpInvoker.invoke(eq("anthropic"), eq("claude-haiku-4-5"), eq("SYS"), eq("USER"), eq(TENANT)))
                .thenReturn("{}");

        orchestrator.afterTurn(CONV, "anthropic", "claude", TENANT, null);

        verify(httpInvoker, times(1)).invoke("anthropic", "claude-haiku-4-5", "SYS", "USER", TENANT);
    }

    // -------------------------------------------------------------------------
    // Per-provider COLD cap
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Cold cap: anthropic=4000, google=2500, unknown=4000, null=4000, case-insensitive")
    void coldCapResolution() {
        assertThat(ChatCompactionOrchestrator.resolveColdCap("anthropic")).isEqualTo(4000);
        assertThat(ChatCompactionOrchestrator.resolveColdCap("ANTHROPIC")).isEqualTo(4000);
        assertThat(ChatCompactionOrchestrator.resolveColdCap("google")).isEqualTo(2500);
        assertThat(ChatCompactionOrchestrator.resolveColdCap("openai")).isEqualTo(3500);
        assertThat(ChatCompactionOrchestrator.resolveColdCap("unknown-provider")).isEqualTo(4000);
        assertThat(ChatCompactionOrchestrator.resolveColdCap(null)).isEqualTo(4000);
        assertThat(ChatCompactionOrchestrator.resolveColdCap("   ")).isEqualTo(4000);
    }

    @Test
    @DisplayName("Gemini provider routes through 2500-token cap in the request")
    void geminiCapInRequest() {
        when(messageRepo.findByConversationIdOrderByCreatedAtAsc(CONV))
                .thenReturn(messages(8));
        when(conversationRepo.findById(CONV)).thenReturn(Optional.of(emptyConversation()));

        orchestrator.afterTurn(CONV, "google", "gemini-3-flash", TENANT, null);

        ArgumentCaptor<SummarizeRequest> captor = ArgumentCaptor.forClass(SummarizeRequest.class);
        verify(summarizer).summarize(captor.capture(), any(LlmJsonInvoker.class));
        assertThat(captor.getValue().modelColdCapTokens()).isEqualTo(2500);
    }

    @Test
    @DisplayName("CLI-bridge providers normalise to API-family cap: claude-code=4000, gemini-cli=2500, codex=3500, mistral-vibe=3500")
    void cliBridgeProvidersNormalise() {
        // Regression pin: without the CLI_BRIDGE_PROVIDER_FAMILY lookup, a
        // gemini-cli request would silently inherit the 4k default cap,
        // over-budgeting COLD against a model whose real window is 2.5k.
        assertThat(ChatCompactionOrchestrator.resolveColdCap("claude-code")).isEqualTo(4000);
        assertThat(ChatCompactionOrchestrator.resolveColdCap("CLAUDE-CODE")).isEqualTo(4000);
        assertThat(ChatCompactionOrchestrator.resolveColdCap("gemini-cli")).isEqualTo(2500);
        assertThat(ChatCompactionOrchestrator.resolveColdCap("codex")).isEqualTo(3500);
        assertThat(ChatCompactionOrchestrator.resolveColdCap("mistral-vibe")).isEqualTo(3500);
    }

    // -------------------------------------------------------------------------
    // Async wrapper exception handling
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("afterTurnAsync swallows runtime exceptions - chat caller never sees them")
    void asyncSwallowsExceptions() {
        when(messageRepo.findByConversationIdOrderByCreatedAtAsc(CONV))
                .thenThrow(new RuntimeException("DB down"));

        // Must not throw.
        orchestrator.afterTurnAsync(CONV, "anthropic", "claude", TENANT, null);

        verify(summarizer, never()).summarize(any(), any());
    }

    @Test
    @DisplayName("afterTurnAsync increments cold_summary_dispatch_failed_total on exception")
    void asyncIncrementsFailureCounter() {
        // Operators monitor compaction outages via this Prometheus counter.
        // Confirm it advances when the DB read trips - WARN-log-only was the
        // pre-audit behaviour and is insufficient for alerting.
        when(messageRepo.findByConversationIdOrderByCreatedAtAsc(CONV))
                .thenThrow(new RuntimeException("DB down"));

        double before = meterRegistry.counter("cold_summary_dispatch_failed_total").count();
        orchestrator.afterTurnAsync(CONV, "anthropic", "claude", TENANT, null);
        double after = meterRegistry.counter("cold_summary_dispatch_failed_total").count();

        assertThat(after - before).isEqualTo(1.0);
    }

    // -------------------------------------------------------------------------
    // Compaction-done event emission (user-facing notification)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Persisted outcome with streamId publishes CompactionDone with envelope fields")
    void persistedOutcomeEmitsCompactionDoneEvent() {
        // Ensures the "prior context summarised" divider lands in real-time when
        // a stream is still open - the UI cue is the whole point of Option A.
        when(messageRepo.findByConversationIdOrderByCreatedAtAsc(CONV))
                .thenReturn(messages(8));
        when(conversationRepo.findById(CONV)).thenReturn(Optional.of(emptyConversation()));
        ColdSummaryEnvelope env = new ColdSummaryEnvelope(
                List.of(), Map.of(), List.of(), List.of(), List.of(),
                "2026-04-21T10:00:00Z", "claude-haiku-4-5", 42, List.of(0, 1, 2));
        when(summarizer.summarize(any(SummarizeRequest.class), any(LlmJsonInvoker.class)))
                .thenReturn(new SummarizeOutcome.Persisted(env));

        orchestrator.afterTurn(CONV, "anthropic", "claude", TENANT, "stream-abc");

        verify(streamPubSub, times(1)).publishCompactionDone(
                eq("stream-abc"), eq(CONV), eq(3),
                eq("claude-haiku-4-5"), eq(Instant.parse("2026-04-21T10:00:00Z")));
    }

    @Test
    @DisplayName("Persisted outcome with null streamId does NOT publish - DTO fallback covers it")
    void persistedOutcomeNullStreamIdSkipsEmission() {
        // Sync callers (webhook/schedule triggers) have no SSE subscriber; the
        // DTO's compactionMarker field is the persistent source of truth, so
        // emission is correctly a no-op - avoid publishing to a dead channel.
        when(messageRepo.findByConversationIdOrderByCreatedAtAsc(CONV))
                .thenReturn(messages(8));
        when(conversationRepo.findById(CONV)).thenReturn(Optional.of(emptyConversation()));
        ColdSummaryEnvelope env = new ColdSummaryEnvelope(
                List.of(), Map.of(), List.of(), List.of(), List.of(),
                "2026-04-21T10:00:00Z", "claude-haiku-4-5", 42, List.of(0, 1, 2));
        when(summarizer.summarize(any(SummarizeRequest.class), any(LlmJsonInvoker.class)))
                .thenReturn(new SummarizeOutcome.Persisted(env));

        orchestrator.afterTurn(CONV, "anthropic", "claude", TENANT, null);

        verifyNoInteractions(streamPubSub);
    }

    @Test
    @DisplayName("Persisted outcome with blank streamId is also skipped (defensive: null-or-blank)")
    void persistedOutcomeBlankStreamIdSkipsEmission() {
        when(messageRepo.findByConversationIdOrderByCreatedAtAsc(CONV))
                .thenReturn(messages(8));
        when(conversationRepo.findById(CONV)).thenReturn(Optional.of(emptyConversation()));
        ColdSummaryEnvelope env = new ColdSummaryEnvelope(
                List.of(), Map.of(), List.of(), List.of(), List.of(),
                "2026-04-21T10:00:00Z", "claude-haiku-4-5", 42, List.of(0));
        when(summarizer.summarize(any(SummarizeRequest.class), any(LlmJsonInvoker.class)))
                .thenReturn(new SummarizeOutcome.Persisted(env));

        orchestrator.afterTurn(CONV, "anthropic", "claude", TENANT, "   ");

        verifyNoInteractions(streamPubSub);
    }

    @Test
    @DisplayName("SkippedGate outcome never publishes CompactionDone - no phantom divider on rejection")
    void skippedGateOutcomeDoesNotEmit() {
        // SkippedGate is already the default stub from setUp(); pin that the
        // gate path is quiet so the UI never gets a "divider" event without a
        // real envelope behind it.
        when(messageRepo.findByConversationIdOrderByCreatedAtAsc(CONV))
                .thenReturn(messages(8));
        when(conversationRepo.findById(CONV)).thenReturn(Optional.of(emptyConversation()));

        orchestrator.afterTurn(CONV, "anthropic", "claude", TENANT, "stream-xyz");

        verifyNoInteractions(streamPubSub);
    }

    @Test
    @DisplayName("Failed outcome never publishes CompactionDone - no divider on LLM/parse/persistence failures")
    void failedOutcomeDoesNotEmit() {
        // When the summariser returns Failed (empty LLM response, JSON parse
        // error, DB write failure), no envelope was persisted - the UI must
        // not flash a "prior context summarised" banner, since there is no
        // corresponding summary_cold row to fall back to.
        when(messageRepo.findByConversationIdOrderByCreatedAtAsc(CONV))
                .thenReturn(messages(8));
        when(conversationRepo.findById(CONV)).thenReturn(Optional.of(emptyConversation()));
        when(summarizer.summarize(any(SummarizeRequest.class), any(LlmJsonInvoker.class)))
                .thenReturn(new SummarizeOutcome.Failed("llm-invoke: upstream 500"));

        orchestrator.afterTurn(CONV, "anthropic", "claude", TENANT, "stream-xyz");

        verifyNoInteractions(streamPubSub);
    }

    @Test
    @DisplayName("Malformed envelope.generatedAt falls back to Instant.now() - never NPE/parse-throw")
    void malformedGeneratedAtFallsBackToNow() {
        // Summary model may emit a bad timestamp; persistence still succeeded,
        // so emission must proceed with a best-effort generatedAt instead of
        // propagating a DateTimeParseException up through the @Async executor.
        when(messageRepo.findByConversationIdOrderByCreatedAtAsc(CONV))
                .thenReturn(messages(8));
        when(conversationRepo.findById(CONV)).thenReturn(Optional.of(emptyConversation()));
        ColdSummaryEnvelope env = new ColdSummaryEnvelope(
                List.of(), Map.of(), List.of(), List.of(), List.of(),
                "not-a-timestamp", "claude-haiku-4-5", 1, List.of(0));
        when(summarizer.summarize(any(SummarizeRequest.class), any(LlmJsonInvoker.class)))
                .thenReturn(new SummarizeOutcome.Persisted(env));

        Instant before = Instant.now();
        orchestrator.afterTurn(CONV, "anthropic", "claude", TENANT, "stream-abc");
        Instant after = Instant.now();

        ArgumentCaptor<Instant> genCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(streamPubSub).publishCompactionDone(
                eq("stream-abc"), eq(CONV), eq(1), eq("claude-haiku-4-5"), genCaptor.capture());
        Instant captured = genCaptor.getValue();
        // Fallback value should be a wall-clock "now" captured during afterTurn.
        assertThat(captured).isBetween(before.minusSeconds(1), after.plusSeconds(1));
    }

    @Test
    @DisplayName("Publishing synchronous exception is swallowed - compaction must not fail on Redis hiccup, dispatch counter untouched")
    void publishingExceptionIsSwallowed() {
        // Emission is best-effort: a Redis outage must not break the chat
        // turn. Emission failures are NOT the same as a summariser failure -
        // cold_summary_dispatch_failed_total tracks the latter, so asserting
        // it stays at 0 here keeps the alerting signal clean.
        when(messageRepo.findByConversationIdOrderByCreatedAtAsc(CONV))
                .thenReturn(messages(8));
        when(conversationRepo.findById(CONV)).thenReturn(Optional.of(emptyConversation()));
        ColdSummaryEnvelope env = new ColdSummaryEnvelope(
                List.of(), Map.of(), List.of(), List.of(), List.of(),
                "2026-04-21T10:00:00Z", "claude-haiku-4-5", 1, List.of(0));
        when(summarizer.summarize(any(SummarizeRequest.class), any(LlmJsonInvoker.class)))
                .thenReturn(new SummarizeOutcome.Persisted(env));
        when(streamPubSub.publishCompactionDone(anyString(), anyString(), anyInt(), anyString(), any(Instant.class)))
                .thenThrow(new RuntimeException("Redis down"));

        double before = meterRegistry.counter("cold_summary_dispatch_failed_total").count();

        // Must not throw - and must still return the persisted outcome verbatim.
        SummarizeOutcome outcome = orchestrator.afterTurn(
                CONV, "anthropic", "claude", TENANT, "stream-abc");

        double after = meterRegistry.counter("cold_summary_dispatch_failed_total").count();

        assertThat(outcome).isInstanceOf(SummarizeOutcome.Persisted.class);
        // Publishing failure ≠ summariser failure: the dispatch-failure
        // counter must NOT tick. Otherwise a transient Redis blip would page
        // operators as if the summariser itself was down.
        assertThat(after - before).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Publishing Mono.error (async failure) is logged and swallowed - not a sync throw path")
    void publishingAsyncMonoErrorIsSwallowed() {
        // Real Redis hiccups surface as Mono.error(...) from reactive Lettuce,
        // NOT as a synchronous exception from publishCompactionDone(). The
        // .subscribe(value, error) handler must catch these - without it,
        // reactor would log an onErrorDropped WARN and still not bubble up,
        // but the noisy log would confuse operators debugging compaction.
        when(messageRepo.findByConversationIdOrderByCreatedAtAsc(CONV))
                .thenReturn(messages(8));
        when(conversationRepo.findById(CONV)).thenReturn(Optional.of(emptyConversation()));
        ColdSummaryEnvelope env = new ColdSummaryEnvelope(
                List.of(), Map.of(), List.of(), List.of(), List.of(),
                "2026-04-21T10:00:00Z", "claude-haiku-4-5", 1, List.of(0));
        when(summarizer.summarize(any(SummarizeRequest.class), any(LlmJsonInvoker.class)))
                .thenReturn(new SummarizeOutcome.Persisted(env));
        when(streamPubSub.publishCompactionDone(anyString(), anyString(), anyInt(), anyString(), any(Instant.class)))
                .thenReturn(Mono.error(new RuntimeException("Lettuce connection reset")));

        double before = meterRegistry.counter("cold_summary_dispatch_failed_total").count();

        // Must not throw - the error is routed to the subscribe error handler.
        SummarizeOutcome outcome = orchestrator.afterTurn(
                CONV, "anthropic", "claude", TENANT, "stream-abc");

        double after = meterRegistry.counter("cold_summary_dispatch_failed_total").count();

        assertThat(outcome).isInstanceOf(SummarizeOutcome.Persisted.class);
        assertThat(after - before).isEqualTo(0.0);
        // Verify the publish was actually attempted (and errored), not silently skipped.
        verify(streamPubSub, times(1)).publishCompactionDone(
                eq("stream-abc"), eq(CONV), eq(1), eq("claude-haiku-4-5"), any(Instant.class));
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private static List<Message> messages(int n) {
        List<Message> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(messageWithRole(Message.MessageRole.USER, "msg-" + i));
        }
        return out;
    }

    /**
     * 10 messages (window=5 → cold=[0..4]) whose LATEST user turn carries a
     * sentence-initial pivot keyword, so {@code keywordHit} is true.
     */
    private static List<Message> messagesEndingWithKeyword() {
        List<Message> msgs = new ArrayList<>(messages(9));
        msgs.add(messageWithRole(Message.MessageRole.USER, "Actually, scratch that"));
        return msgs;
    }

    private static Message messageWithRole(Message.MessageRole role, String body) {
        Message m = new Message();
        m.setRole(role);
        m.setContent(body);
        // createdAt isn't used by the orchestrator (repo returns pre-sorted); leave default.
        m.setCreatedAt(LocalDateTime.now());
        return m;
    }

    private static Conversation emptyConversation() {
        Conversation c = new Conversation();
        c.setSummaryCold(null);
        return c;
    }

    /**
     * Conversation carrying a per-conversation compaction override under
     * {@code chat_config.compaction.{enabled,afterTurns}} and an optional linked
     * {@code agentId}. Null override fields are omitted so the resolver inherits.
     */
    private static Conversation conversationWithCompaction(Boolean enabled, Integer afterTurns, String agentId) {
        Conversation c = new Conversation();
        c.setSummaryCold(null);
        if (agentId != null) {
            c.setAgentId(agentId);
        }
        if (enabled != null || afterTurns != null) {
            Map<String, Object> comp = new HashMap<>();
            if (enabled != null) comp.put("enabled", enabled);
            if (afterTurns != null) comp.put("afterTurns", afterTurns);
            Map<String, Object> cfg = new HashMap<>();
            cfg.put("compaction", comp);
            c.setChatConfig(cfg);
        }
        return c;
    }
}
