package com.apimarketplace.agent.provider;

import com.apimarketplace.agent.domain.CompletionRequest;
import com.apimarketplace.agent.tokenizer.TokenEstimator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 1b.2 - pin the Claude native {@code clear_thinking_20251015}
 * edit behaviour. Fires only when the previous assistant turn was more
 * than {@link ClaudeProvider#THINKING_IDLE_THRESHOLD} ago; at that point
 * the prompt cache is cold anyway, so dropping prior thinking turns
 * costs nothing and shortens the replay.
 *
 * <p><b>Why a dedicated test.</b> The idle branch is time-dependent, so
 * we inject a fixed {@link Clock} via {@link ClaudeProvider#setClock}
 * to make the boundary condition deterministic. Without a clock seam
 * the test either sleeps (flaky) or calls {@code Instant.now()} inside
 * production code (non-deterministic). The seam is small enough to
 * introduce solely for test control and is package-private so it
 * doesn't leak into the public surface.
 *
 * <p><b>Boundary precision.</b> The Anthropic edit fires on strictly
 * <em>greater than</em> one hour. We pin the {@code >} semantics in
 * {@link #exactOneHourIsNotIdle} - a refactor to {@code >=} would
 * fire an edit on the first turn within a sub-hour multi-turn that
 * happens to hit exactly 1h after the prior turn, churning the cache
 * right at the boundary.
 */
@DisplayName("ClaudeProvider - clear_thinking_20251015 after idle (Stage 1b.2)")
class ClaudeClearsThinkingAfterIdleTest {

    // Pin the now-instant so every test is deterministic. The exact
    // value doesn't matter, only that it doesn't drift between runs.
    private static final Instant NOW = Instant.parse("2026-04-20T12:00:00Z");

    private ClaudeProvider provider;

    @BeforeEach
    void setUp() {
        provider = new ClaudeProvider();
        provider.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
        // Keep Strategy 1 (clear_tool_uses) below the 180k trigger by
        // default; tests that want both paths firing raise the stub.
        provider.setTokenEstimator(stubEstimator(1_000));
    }

    private static TokenEstimator stubEstimator(int totalTokens) {
        return new TokenEstimator() {
            @Override public String name() { return "stub"; }
            @Override public int estimate(CompletionRequest request) { return totalTokens; }
        };
    }

    private static CompletionRequest requestWithLastTurnAt(Instant lastTurnAt) {
        return CompletionRequest.builder()
                .model("claude-sonnet-4-5")
                .userPrompt("follow-up")
                .lastTurnAt(lastTurnAt)
                .build();
    }

    @Test
    @DisplayName("lastTurnAt = null → non-conversation caller, no clear_thinking edit emitted")
    void nullLastTurnAtEmitsNoEdit() {
        // Non-conversation entry points (batch jobs, one-shots) leave
        // lastTurnAt null; the provider must treat that as "not idle"
        // and skip the edit entirely. Otherwise every cron-style caller
        // would unnecessarily include the edit on every request.
        CompletionRequest req = CompletionRequest.builder()
                .model("claude-sonnet-4-5")
                .userPrompt("probe")
                .build();

        Map<String, Object> body = provider.buildRequestBody(req);

        assertThat(body)
                .as("null lastTurnAt is the default; must not trigger any edits")
                .doesNotContainKey("context_management");
    }

    @Test
    @DisplayName("lastTurnAt = now - 59m → still within idle window, no edit")
    void justInsideIdleWindowEmitsNoEdit() {
        Instant lastTurn = NOW.minus(Duration.ofMinutes(59));
        Map<String, Object> body = provider.buildRequestBody(requestWithLastTurnAt(lastTurn));
        assertThat(body)
                .as("59 minutes idle is below the 1-hour threshold; no edit")
                .doesNotContainKey("context_management");
    }

    @Test
    @DisplayName("lastTurnAt = now - 61m → idle threshold crossed, emit clear_thinking edit")
    void past61MinIsIdleAndEmitsEdit() {
        Instant lastTurn = NOW.minus(Duration.ofMinutes(61));
        Map<String, Object> body = provider.buildRequestBody(requestWithLastTurnAt(lastTurn));

        assertThat(body).containsKey("context_management");

        @SuppressWarnings("unchecked")
        Map<String, Object> cm = (Map<String, Object>) body.get("context_management");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edits = (List<Map<String, Object>>) cm.get("edits");

        assertThat(edits).hasSize(1);
        Map<String, Object> edit = edits.get(0);
        assertThat(edit.get("type")).isEqualTo("clear_thinking_20251015");
        assertThat(edit.get("keep"))
                .as("keep must be {type:thinking_turns, value:1} - preserve only the last reasoning turn")
                .isEqualTo(Map.of("type", "thinking_turns", "value", 1));
    }

    @Test
    @DisplayName("exactly 1h idle → NOT idle (boundary is strictly greater-than)")
    void exactOneHourIsNotIdle() {
        // Duration.compareTo > 0, not >= 0. Exactly 1h must stay below
        // the boundary; otherwise a conversation with steady hourly
        // cadence would fire the edit on every single turn.
        Instant lastTurn = NOW.minus(Duration.ofHours(1));
        Map<String, Object> body = provider.buildRequestBody(requestWithLastTurnAt(lastTurn));
        assertThat(body)
                .as("exactly 1h is the boundary; must NOT be considered idle")
                .doesNotContainKey("context_management");
    }

    @Test
    @DisplayName("both strategies fire → edits[] contains clear_tool_uses first, then clear_thinking")
    void bothStrategiesFireInDeterministicOrder() {
        // Push Strategy 1 above its trigger AND put lastTurnAt past the
        // idle threshold → edits[] must hold both, with clear_tool_uses
        // first (that's the code order - deterministic wire layout is
        // needed for byte-identical retries under Anthropic's cache).
        provider.setTokenEstimator(stubEstimator(200_000));
        Instant lastTurn = NOW.minus(Duration.ofHours(2));

        Map<String, Object> body = provider.buildRequestBody(requestWithLastTurnAt(lastTurn));

        @SuppressWarnings("unchecked")
        Map<String, Object> cm = (Map<String, Object>) body.get("context_management");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edits = (List<Map<String, Object>>) cm.get("edits");

        assertThat(edits).hasSize(2);
        assertThat(edits.get(0).get("type")).isEqualTo("clear_tool_uses_20250919");
        assertThat(edits.get(1).get("type")).isEqualTo("clear_thinking_20251015");
    }

    @Test
    @DisplayName("clear_thinking edit key order is insertion-stable - LinkedHashMap guarantee")
    void clearThinkingEditKeyOrderIsStable() {
        Instant lastTurn = NOW.minus(Duration.ofHours(2));
        Map<String, Object> body = provider.buildRequestBody(requestWithLastTurnAt(lastTurn));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edits = (List<Map<String, Object>>)
                ((Map<String, Object>) body.get("context_management")).get("edits");
        Map<String, Object> edit = edits.get(0);

        // A refactor that built the edit with HashMap would break retry
        // byte-identity; pin the insertion order here.
        assertThat(edit.keySet()).containsExactly("type", "keep");
    }

    @Test
    @DisplayName("lastTurnAt in the future (clock skew) → treated as not idle, no edit")
    void futureLastTurnAtDoesNotFire() {
        // If the caller's clock drifted forward (or a stale record is
        // replayed), lastTurnAt could be in the future relative to our
        // fixed clock. Duration.between returns a NEGATIVE duration,
        // whose compareTo vs 1h is also negative → not idle. Guard the
        // expectation so a future refactor that flipped signs would
        // fire this test.
        Instant future = NOW.plus(Duration.ofMinutes(5));
        Map<String, Object> body = provider.buildRequestBody(requestWithLastTurnAt(future));
        assertThat(body)
                .as("future lastTurnAt → negative idle duration → must not fire the edit")
                .doesNotContainKey("context_management");
    }

    @Test
    @DisplayName("setClock(null) resets to system clock - defensive behaviour for container refresh")
    void setClockNullResetsToSystemUtc() {
        // The setter is package-private so production code doesn't
        // touch it, but any caller that passes null must NOT NPE on the
        // next request - that would mean a test-only override could
        // crash production if it leaked. Exercise the contract.
        provider.setClock(null);
        // Still use a null lastTurnAt so we don't need to reason about
        // real wall-clock idle; we just want the request to build
        // cleanly without throwing.
        Map<String, Object> body = provider.buildRequestBody(
                CompletionRequest.builder()
                        .model("claude-sonnet-4-5")
                        .userPrompt("probe")
                        .build());
        assertThat(body).containsKey("model");
    }
}
