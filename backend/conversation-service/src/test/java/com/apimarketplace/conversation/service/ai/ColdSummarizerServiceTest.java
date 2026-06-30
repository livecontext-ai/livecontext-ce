package com.apimarketplace.conversation.service.ai;

import com.apimarketplace.agent.summary.ColdSummarizerPromptBuilder.Turn;
import com.apimarketplace.agent.summary.ColdSummaryEnvelope;
import com.apimarketplace.conversation.repository.ConversationRepository;
import com.apimarketplace.conversation.service.ai.ColdSummarizerService.LlmJsonInvoker;
import com.apimarketplace.conversation.service.ai.ColdSummarizerService.SummarizeOutcome;
import com.apimarketplace.conversation.service.ai.ColdSummarizerService.SummarizeRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Stage 5.4 - behaviour pins for the summariser orchestrator. Tests fence
 * off: gate refusal path, happy path end-to-end, LLM-empty / parse-failure
 * / DB-miss failure branches, invalidate idempotency, and the required-arg
 * record invariants.
 *
 * <p>The {@link LlmJsonInvoker} is stubbed explicitly - we never want these
 * unit tests to talk to a real provider. The summariser is also the cheapest
 * place to catch JSON-contract drift; the happy-path test encodes what a
 * well-behaved summariser response looks like.
 */
@DisplayName("ColdSummarizerService - gate/parse/persist (Stage 5.4)")
class ColdSummarizerServiceTest {

    private ConversationRepository repo;
    private ObjectMapper mapper;
    private LockProvider lockProvider;
    private MeterRegistry meterRegistry;
    private ColdSummarizerService service;

    @BeforeEach
    void setUp() {
        repo = mock(ConversationRepository.class);
        mapper = new ObjectMapper();
        // Default: always grant the lock - individual tests override when
        // they need to assert dedup behaviour.
        lockProvider = mock(LockProvider.class);
        when(lockProvider.lock(any(LockConfiguration.class)))
                .thenReturn(Optional.of(mock(SimpleLock.class)));
        meterRegistry = new SimpleMeterRegistry();
        service = new ColdSummarizerService(repo, mapper, lockProvider, meterRegistry);
    }

    // ---- DTO invariants ------------------------------------------------------

    @Test
    @DisplayName("SummarizeRequest rejects null conversationId / turns / provider / model")
    void requestInvariants() {
        // Each field is a load-bearing contract: conversationId pins the DB
        // write target, coldTurns feeds the prompt, provider/model tag the
        // envelope so replay debugging can identify drift. A null anywhere
        // would silently break one of those.
        assertThatThrownBy(() -> new SummarizeRequest(null, 4000, 3000, 5, 5, false,
                List.of(new Turn(1, "USER", "x")), List.of(1), "anthropic", "claude-haiku-4-5"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("conversationId");
        assertThatThrownBy(() -> new SummarizeRequest("c1", 4000, 3000, 5, 5, false,
                null, List.of(1), "anthropic", "claude-haiku-4-5"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("coldTurns");
        assertThatThrownBy(() -> new SummarizeRequest("c1", 4000, 3000, 5, 5, false,
                List.of(new Turn(1, "USER", "x")), List.of(1), null, "m"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("providerName");
    }

    // ---- gate refusal --------------------------------------------------------

    @Test
    @DisplayName("gate below size threshold → SkippedGate, zero DB writes, invoker untouched")
    void gateRefusesSmallColdZone() {
        // 500 tokens is well below the 2k floor; the gate must refuse and
        // the summariser must not even call the LLM (would burn credits).
        LlmJsonInvoker invoker = (p, m, s, u) -> {
            throw new AssertionError("invoker must not be called when gate refuses");
        };
        SummarizeRequest req = new SummarizeRequest(
                "conv-1", 4000, 500, 10, 5, false,
                List.of(new Turn(1, "USER", "tiny")), List.of(1),
                "anthropic", "claude-haiku-4-5");
        SummarizeOutcome out = service.summarize(req, invoker);
        assertThat(out).isInstanceOf(SummarizeOutcome.SkippedGate.class);
        verify(repo, never()).updateSummaryCold(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("gate: cadence not reached + no keyword → SkippedGate")
    void gateRefusesOnCadence() {
        // Size gate passes (3k > 2k threshold) but cadence is only 2 turns
        // against a default of 5 and keyword didn't trip.
        LlmJsonInvoker invoker = (p, m, s, u) -> { throw new AssertionError(); };
        SummarizeRequest req = new SummarizeRequest(
                "conv-1", 4000, 3000, 2, 5, false,
                List.of(new Turn(1, "USER", "x")), List.of(1),
                "anthropic", "claude-haiku-4-5");
        assertThat(service.summarize(req, invoker))
                .isInstanceOf(SummarizeOutcome.SkippedGate.class);
    }

    // ---- happy path ----------------------------------------------------------

    @Test
    @DisplayName("happy path: invokes LLM, parses envelope, stamps metadata, writes jsonb")
    void happyPathEndToEnd() throws Exception {
        // Minimal well-formed summariser output (the five content keys, per
        // the system prompt). Metadata fields are stamped by the service.
        String llmResponse = """
                {
                  "decisions": [{"turn": 3, "decision": "chose Fork over Split"}],
                  "ids_resolved": {"jean_wf": "wf_abc"},
                  "errors_resolved": [{"error": "bad token", "resolution": "rotated"}],
                  "user_intents": ["build CRM"],
                  "helped_actions": ["agent.create"]
                }
                """;

        LlmJsonInvoker invoker = (p, m, s, u) -> llmResponse;
        when(repo.updateSummaryCold(eq("conv-1"), anyString(), anyInt())).thenReturn(1);

        SummarizeRequest req = new SummarizeRequest(
                "conv-1", 4000, 3000, 5, 5, false,
                List.of(new Turn(1, "USER", "hi"), new Turn(2, "ASSISTANT", "hello")),
                List.of(1, 2), "anthropic", "claude-haiku-4-5");

        SummarizeOutcome out = service.summarize(req, invoker);

        assertThat(out).isInstanceOf(SummarizeOutcome.Persisted.class);
        ColdSummaryEnvelope env = ((SummarizeOutcome.Persisted) out).envelope();
        // Metadata stamping
        assertThat(env.model()).isEqualTo("claude-haiku-4-5");
        assertThat(env.coldTokensAtGeneration()).isEqualTo(3000);
        assertThat(env.turnsCovered()).containsExactly(1, 2);
        assertThat(env.generatedAt()).isNotBlank();
        // Freshly generated envelopes are always stamped active - staleness
        // is a post-persistence transition, never a generation-time state.
        assertThat(env.status()).isEqualTo(ColdSummaryEnvelope.STATUS_ACTIVE);
        assertThat(env.isStale()).isFalse();
        // Content preservation
        assertThat(env.decisions()).hasSize(1);
        assertThat(env.idsResolved()).containsEntry("jean_wf", "wf_abc");
        assertThat(env.errorsResolved()).hasSize(1);
        assertThat(env.userIntents()).containsExactly("build CRM");
        assertThat(env.helpedActions()).containsExactly("agent.create");

        // DB write happened with the full serialised envelope, and the
        // monotone guard received the covered-turn count for this envelope.
        ArgumentCaptor<String> jsonCap = ArgumentCaptor.forClass(String.class);
        verify(repo).updateSummaryCold(eq("conv-1"), jsonCap.capture(), eq(2));
        assertThat(jsonCap.getValue()).contains("\"generated_at\"")
                .contains("\"cold_tokens_at_generation\":3000")
                .contains("\"turns_covered\":[1,2]")
                .contains("\"status\":\"active\"");
    }

    @Test
    @DisplayName("keyword hit overrides cadence floor - gate passes at 1 turn")
    void keywordHitForcesRegen() throws Exception {
        // An invalidation-keyword hit (e.g. "actually, scratch the CRM idea")
        // should let the gate fire even if only 1 turn has elapsed.
        String llmResponse = """
                {"decisions":[{"turn":1,"decision":"flipped course"}],
                 "user_intents":["new plan"]}
                """;
        LlmJsonInvoker invoker = (p, m, s, u) -> llmResponse;
        when(repo.updateSummaryCold(anyString(), anyString(), anyInt())).thenReturn(1);

        SummarizeRequest req = new SummarizeRequest(
                "conv-k", 4000, 3000, /*turnsSince*/ 1, 5, /*kwHit*/ true,
                List.of(new Turn(1, "USER", "scratch that")), List.of(1),
                "anthropic", "claude-haiku-4-5");
        assertThat(service.summarize(req, invoker))
                .isInstanceOf(SummarizeOutcome.Persisted.class);
        verify(repo, times(1)).updateSummaryCold(anyString(), anyString(), anyInt());
    }

    // ---- failure paths -------------------------------------------------------

    @Test
    @DisplayName("LLM returns null → Failed('empty-response'); DB untouched")
    void llmNullResponseFails() {
        LlmJsonInvoker invoker = (p, m, s, u) -> null;
        SummarizeRequest req = new SummarizeRequest(
                "conv-1", 4000, 3000, 5, 5, false,
                List.of(new Turn(1, "USER", "x")), List.of(1),
                "anthropic", "claude-haiku-4-5");
        SummarizeOutcome out = service.summarize(req, invoker);
        assertThat(out).isInstanceOf(SummarizeOutcome.Failed.class);
        assertThat(((SummarizeOutcome.Failed) out).reason()).contains("empty-response");
        verify(repo, never()).updateSummaryCold(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("LLM throws → Failed('llm-invoke: …'); DB untouched")
    void llmThrowsFails() {
        LlmJsonInvoker invoker = (p, m, s, u) -> { throw new RuntimeException("rate limited"); };
        SummarizeRequest req = new SummarizeRequest(
                "conv-1", 4000, 3000, 5, 5, false,
                List.of(new Turn(1, "USER", "x")), List.of(1),
                "anthropic", "claude-haiku-4-5");
        SummarizeOutcome out = service.summarize(req, invoker);
        assertThat(out).isInstanceOf(SummarizeOutcome.Failed.class);
        assertThat(((SummarizeOutcome.Failed) out).reason()).contains("rate limited");
        verify(repo, never()).updateSummaryCold(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("LLM returns non-JSON → Failed('parse: …'); DB untouched")
    void llmNonJsonFails() {
        LlmJsonInvoker invoker = (p, m, s, u) -> "I'm sorry, I can't do that";
        SummarizeRequest req = new SummarizeRequest(
                "conv-1", 4000, 3000, 5, 5, false,
                List.of(new Turn(1, "USER", "x")), List.of(1),
                "anthropic", "claude-haiku-4-5");
        SummarizeOutcome out = service.summarize(req, invoker);
        assertThat(out).isInstanceOf(SummarizeOutcome.Failed.class);
        assertThat(((SummarizeOutcome.Failed) out).reason()).startsWith("parse:");
        verify(repo, never()).updateSummaryCold(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("DB update returns 0 rows on a missing conversation → Failed('conversation-not-found')")
    void dbMissFails() {
        LlmJsonInvoker invoker = (p, m, s, u) ->
                "{\"decisions\":[{\"turn\":1,\"decision\":\"x\"}]}";
        when(repo.updateSummaryCold(anyString(), anyString(), anyInt())).thenReturn(0);
        when(repo.existsById("conv-dead")).thenReturn(false);

        SummarizeRequest req = new SummarizeRequest(
                "conv-dead", 4000, 3000, 5, 5, false,
                List.of(new Turn(1, "USER", "x")), List.of(1),
                "anthropic", "claude-haiku-4-5");
        SummarizeOutcome out = service.summarize(req, invoker);
        assertThat(out).isInstanceOf(SummarizeOutcome.Failed.class);
        assertThat(((SummarizeOutcome.Failed) out).reason()).contains("conversation-not-found");
    }

    // ---- monotone-recall guard (regression: non-monotone COLD recall) --------

    @Test
    @DisplayName("regression - monotone guard rejection (row exists, 0 rows updated) → SkippedStaleWrite, counter bumped, NOT a failure")
    void monotoneGuardRejectionIsSkippedStaleWriteNotFailure() {
        // The exact race the guard exists for: this pod's ShedLock TTL
        // expired under a slow LLM call, another pod persisted a broader
        // envelope, and our late write comes back 0 rows although the
        // conversation row exists. Pre-fix this was last-write-wins (the
        // stale envelope silently SHRANK the agent's recall); the guard
        // turns it into a clean skip.
        LlmJsonInvoker invoker = (p, m, s, u) ->
                "{\"decisions\":[{\"turn\":1,\"decision\":\"x\"}]}";
        when(repo.updateSummaryCold(anyString(), anyString(), anyInt())).thenReturn(0);
        when(repo.existsById("conv-raced")).thenReturn(true);

        SummarizeRequest req = new SummarizeRequest(
                "conv-raced", 4000, 3000, 5, 5, false,
                List.of(new Turn(1, "USER", "x")), List.of(1),
                "anthropic", "claude-haiku-4-5");
        SummarizeOutcome out = service.summarize(req, invoker);

        assertThat(out).isInstanceOf(SummarizeOutcome.SkippedStaleWrite.class);
        assertThat(meterRegistry.counter("cold_summary_stale_write_skipped_total").count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("persist hands the monotone guard the size of turnsCovered, not a recomputed value")
    void persistPassesCoveredCountToGuard() {
        LlmJsonInvoker invoker = (p, m, s, u) ->
                "{\"decisions\":[{\"turn\":1,\"decision\":\"x\"}]}";
        when(repo.updateSummaryCold(anyString(), anyString(), anyInt())).thenReturn(1);

        SummarizeRequest req = new SummarizeRequest(
                "conv-n", 4000, 3000, 5, 5, false,
                List.of(new Turn(1, "USER", "a"), new Turn(2, "ASSISTANT", "b"),
                        new Turn(3, "USER", "c")),
                List.of(0, 1, 2), "anthropic", "claude-haiku-4-5");
        service.summarize(req, invoker);

        verify(repo).updateSummaryCold(eq("conv-n"), anyString(), eq(3));
    }

    // ---- invalidate ----------------------------------------------------------

    @Test
    @DisplayName("invalidate returns true when a row was cleared; false when no-op")
    void invalidateReturnsRowCount() {
        when(repo.clearSummaryCold("conv-A")).thenReturn(1);
        when(repo.clearSummaryCold("conv-B")).thenReturn(0);
        assertThat(service.invalidate("conv-A")).isTrue();
        assertThat(service.invalidate("conv-B")).isFalse();
    }

    @Test
    @DisplayName("invalidate rejects null conversationId")
    void invalidateRejectsNull() {
        assertThatThrownBy(() -> service.invalidate(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ---- markStale (status-aware recall) --------------------------------------

    @Test
    @DisplayName("markStale: actual transition → true + reason-tagged counter; repeat/no-op → false, counter untouched")
    void markStaleCountsOnlyTransitions() {
        // The repository UPDATE is conditional (status not already stale),
        // so repeat calls return 0 rows - the counter must reflect real
        // transitions only, or the Grafana panel would count every turn of
        // a shrunken conversation as a new staleness event.
        when(repo.markSummaryColdStale("conv-A")).thenReturn(1).thenReturn(0);

        assertThat(service.markStale("conv-A", "cold-shrink")).isTrue();
        assertThat(service.markStale("conv-A", "cold-shrink")).isFalse();

        assertThat(meterRegistry.counter(
                "cold_summary_marked_stale_total", "reason", "cold-shrink").count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("markStale reasons produce distinct counter time series - Grafana can tell shrink from invalidation")
    void markStaleReasonsAreDistinctTimeSeries() {
        when(repo.markSummaryColdStale(anyString())).thenReturn(1);

        service.markStale("conv-A", "cold-shrink");
        service.markStale("conv-B", "invalidation-regen-missed");
        service.markStale("conv-C", "cold-shrink");

        assertThat(meterRegistry.counter(
                "cold_summary_marked_stale_total", "reason", "cold-shrink").count())
                .isEqualTo(2.0);
        assertThat(meterRegistry.counter(
                "cold_summary_marked_stale_total", "reason", "invalidation-regen-missed").count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("markStale rejects null conversationId and null reason")
    void markStaleRejectsNulls() {
        assertThatThrownBy(() -> service.markStale(null, "cold-shrink"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.markStale("conv-A", null))
                .isInstanceOf(NullPointerException.class);
    }

    // ---- ShedLock dedup ------------------------------------------------------

    @Test
    @DisplayName("lock not acquired → SkippedGate, LLM not called, contended counter incremented")
    void lockContendedSkipsWithoutCallingLlm() {
        // Simulate another pod already holding the lock. The summariser must
        // treat this as SkippedGate (no envelope was written by this call)
        // and must not burn credits by invoking the LLM. The distinction
        // between "gate refused" and "lock contended" lives in the metric,
        // not in the outcome type - both are no-ops from the caller's POV.
        when(lockProvider.lock(any(LockConfiguration.class))).thenReturn(Optional.empty());
        LlmJsonInvoker invoker = (p, m, s, u) -> {
            throw new AssertionError("invoker must not be called when lock is contended");
        };

        SummarizeRequest req = new SummarizeRequest(
                "conv-hot", 4000, 3000, 5, 5, false,
                List.of(new Turn(1, "USER", "x")), List.of(1),
                "anthropic", "claude-haiku-4-5");

        SummarizeOutcome out = service.summarize(req, invoker);

        assertThat(out).isInstanceOf(SummarizeOutcome.SkippedGate.class);
        verify(repo, never()).updateSummaryCold(anyString(), anyString(), anyInt());
        assertThat(meterRegistry.counter(
                "cold_summary_lock_contended_total", "result", "skipped").count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.counter(
                "cold_summary_lock_contended_total", "result", "acquired").count())
                .isEqualTo(0.0);
    }

    @Test
    @DisplayName("lock released in finally even when LLM throws - next call re-acquires")
    void lockReleasedOnLlmFailure() {
        // Regression guard: if the LLM throws, the lock must still be
        // released or the next retry would block until the TTL expires.
        SimpleLock firstLock = mock(SimpleLock.class);
        SimpleLock secondLock = mock(SimpleLock.class);
        when(lockProvider.lock(any(LockConfiguration.class)))
                .thenReturn(Optional.of(firstLock))
                .thenReturn(Optional.of(secondLock));

        LlmJsonInvoker failing = (p, m, s, u) -> { throw new RuntimeException("boom"); };
        LlmJsonInvoker ok = (p, m, s, u) ->
                "{\"decisions\":[{\"turn\":1,\"decision\":\"x\"}]}";
        when(repo.updateSummaryCold(anyString(), anyString(), anyInt())).thenReturn(1);

        SummarizeRequest req = new SummarizeRequest(
                "conv-retry", 4000, 3000, 5, 5, false,
                List.of(new Turn(1, "USER", "x")), List.of(1),
                "anthropic", "claude-haiku-4-5");

        SummarizeOutcome first = service.summarize(req, failing);
        SummarizeOutcome second = service.summarize(req, ok);

        assertThat(first).isInstanceOf(SummarizeOutcome.Failed.class);
        assertThat(second).isInstanceOf(SummarizeOutcome.Persisted.class);
        verify(firstLock).unlock();
        verify(secondLock).unlock();
    }

    @Test
    @DisplayName("concurrent calls for same conversation: only one LLM invocation, one persist, others SkippedGate")
    void concurrentSameConversationRunsOnce() throws Exception {
        // End-to-end contention: 8 threads race on the same conversationId
        // with a real in-memory lock emulator. The LLM + DB must fire
        // exactly once; every loser should observe SkippedGate.
        //
        // The emulator simulates ShedLock's single-winner contract without
        // a real JDBC table. It keys on the LockConfiguration name, so any
        // bug that forgets to scope the lock per-conversation would leak
        // across keys and be caught by the second half of the test.
        LockProvider realLock = new InMemoryLockProvider();
        ColdSummarizerService real = new ColdSummarizerService(
                repo, mapper, realLock, meterRegistry);

        AtomicInteger invokerCalls = new AtomicInteger();
        CountDownLatch allThreadsEntered = new CountDownLatch(8);
        LlmJsonInvoker slow = (p, m, s, u) -> {
            invokerCalls.incrementAndGet();
            try {
                // Winner holds the lock until every loser has entered the
                // pool and attempted acquisition. 5s cap is generous against
                // CI scheduling jitter (per-future get() below is also 5s).
                allThreadsEntered.await(5, TimeUnit.SECONDS);
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "{\"decisions\":[{\"turn\":1,\"decision\":\"x\"}]}";
        };
        when(repo.updateSummaryCold(anyString(), anyString(), anyInt())).thenReturn(1);

        SummarizeRequest req = new SummarizeRequest(
                "conv-race", 4000, 3000, 5, 5, false,
                List.of(new Turn(1, "USER", "x")), List.of(1),
                "anthropic", "claude-haiku-4-5");

        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<SummarizeOutcome>> futures = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Callable<SummarizeOutcome> task = () -> {
                allThreadsEntered.countDown();
                return real.summarize(req, slow);
            };
            futures.add(pool.submit(task));
        }

        int persisted = 0;
        int skipped = 0;
        for (Future<SummarizeOutcome> f : futures) {
            SummarizeOutcome o = f.get(5, TimeUnit.SECONDS);
            if (o instanceof SummarizeOutcome.Persisted) persisted++;
            else if (o instanceof SummarizeOutcome.SkippedGate) skipped++;
        }
        pool.shutdownNow();

        assertThat(persisted).as("exactly one thread persists").isEqualTo(1);
        assertThat(skipped).as("other 7 threads skip on contention").isEqualTo(7);
        assertThat(invokerCalls.get()).as("LLM invoked once").isEqualTo(1);
        verify(repo, times(1)).updateSummaryCold(eq("conv-race"), anyString(), anyInt());
        assertThat(meterRegistry.counter(
                "cold_summary_lock_contended_total", "result", "skipped").count())
                .isEqualTo(7.0);
        assertThat(meterRegistry.counter(
                "cold_summary_lock_contended_total", "result", "acquired").count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("different conversations run in parallel - per-conversation lock scoping")
    void differentConversationsDoNotBlockEachOther() throws Exception {
        // If the lock key were accidentally shared (e.g. a single "cold-summary"
        // literal) every conversation would serialise globally under load.
        // Two threads hitting two different conversationIds must both persist.
        LockProvider realLock = new InMemoryLockProvider();
        ColdSummarizerService real = new ColdSummarizerService(
                repo, mapper, realLock, meterRegistry);

        LlmJsonInvoker invoker = (p, m, s, u) ->
                "{\"decisions\":[{\"turn\":1,\"decision\":\"x\"}]}";
        when(repo.updateSummaryCold(anyString(), anyString(), anyInt())).thenReturn(1);

        SummarizeRequest a = new SummarizeRequest(
                "conv-A", 4000, 3000, 5, 5, false,
                List.of(new Turn(1, "USER", "x")), List.of(1),
                "anthropic", "claude-haiku-4-5");
        SummarizeRequest b = new SummarizeRequest(
                "conv-B", 4000, 3000, 5, 5, false,
                List.of(new Turn(1, "USER", "x")), List.of(1),
                "anthropic", "claude-haiku-4-5");

        assertThat(real.summarize(a, invoker)).isInstanceOf(SummarizeOutcome.Persisted.class);
        assertThat(real.summarize(b, invoker)).isInstanceOf(SummarizeOutcome.Persisted.class);
        verify(repo).updateSummaryCold(eq("conv-A"), anyString(), anyInt());
        verify(repo).updateSummaryCold(eq("conv-B"), anyString(), anyInt());
    }

    @Test
    @DisplayName("gate-refused request does not touch the lock provider")
    void gateRefusedSkipsLockAcquisition() {
        // Optimisation invariant: small conversations must not hit the
        // shedlock table (one DB round-trip per turn would dwarf the
        // savings). The gate check runs first; the lock is only consulted
        // when we're about to call the LLM.
        LlmJsonInvoker invoker = (p, m, s, u) -> { throw new AssertionError(); };
        SummarizeRequest req = new SummarizeRequest(
                "conv-tiny", 4000, 500, 10, 5, false,
                List.of(new Turn(1, "USER", "x")), List.of(1),
                "anthropic", "claude-haiku-4-5");

        service.summarize(req, invoker);

        verify(lockProvider, never()).lock(any(LockConfiguration.class));
    }

    /**
     * Minimal in-memory stand-in for ShedLock's JDBC provider. Models the
     * "one winner per lock name" contract without a DB: good enough for
     * contention tests, not a production piece. Tracks held names in a
     * synchronised set so two threads can't both enter the same name.
     *
     * <p><b>Not modelled:</b> TTL expiry (the {@code LOCK_AT_MOST_FOR}
     * cap on {@link LockConfiguration}). Crash-recovery behaviour - a
     * dead pod's lock being auto-released after the TTL - can only be
     * verified against the real JDBC provider under Testcontainers and
     * is intentionally out of scope for these unit tests.
     */
    private static final class InMemoryLockProvider implements LockProvider {
        private final Set<String> held = java.util.Collections.synchronizedSet(new HashSet<>());

        @Override
        public Optional<SimpleLock> lock(LockConfiguration cfg) {
            String name = cfg.getName();
            synchronized (held) {
                if (held.contains(name)) {
                    return Optional.empty();
                }
                held.add(name);
            }
            return Optional.of(new SimpleLock() {
                @Override public void unlock() { held.remove(name); }
                @Override public Optional<SimpleLock> extend(java.time.Duration a, java.time.Duration b) {
                    return Optional.of(this);
                }
            });
        }
    }
}
