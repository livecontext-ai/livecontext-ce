package com.apimarketplace.agent.loop;

import com.apimarketplace.agent.domain.AgentStopReason;
import com.apimarketplace.agent.domain.CompletionResponse;
import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.streaming.InactivityWatchdogCallback;
import com.apimarketplace.agent.streaming.StreamingCallback;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the inactivity-watchdog wiring in {@link AgentLoopService}: how the watchdog is built
 * for a run, and how a watchdog-triggered stop is reclassified to INACTIVITY_TIMEOUT.
 */
@DisplayName("AgentLoopService - inactivity watchdog wiring")
class AgentLoopServiceInactivityWiringTest {

    /** No-op streaming callback. */
    static class NoopCallback implements StreamingCallback {
        @Override public void onChunk(String content) { }
        @Override public void onToolCall(ToolCall toolCall) { }
        @Override public void onComplete(CompletionResponse response) { }
        @Override public void onError(String error) { }
    }

    /** A watchdog that has already tripped (clock advanced past the window). */
    private static InactivityWatchdogCallback trippedWatchdog() {
        long[] now = {0};
        LongSupplier clock = () -> now[0];
        InactivityWatchdogCallback wd = new InactivityWatchdogCallback(new NoopCallback(), 1000, clock);
        now[0] = 2000;          // 2s of silence, window is 1s
        assertThat(wd.shouldStop()).isTrue();
        assertThat(wd.isIdleTripped()).isTrue();
        return wd;
    }

    // ── buildInactivityWatchdog ───────────────────────────────────────────────

    @Test
    @DisplayName("null callback -> no watchdog (a single-shot non-streaming call emits no events)")
    void nullCallbackYieldsNoWatchdog() {
        AgentLoopContext ctx = AgentLoopContext.builder().build();
        assertThat(AgentLoopService.buildInactivityWatchdog(ctx, null)).isNull();
    }

    @Test
    @DisplayName("default context wraps the callback with a 5-minute watchdog (every agent gets one)")
    void defaultContextWrapsWithFiveMinuteWatchdog() {
        AgentLoopContext ctx = AgentLoopContext.builder().build(); // inactivityTimeout null -> default
        InactivityWatchdogCallback wd = AgentLoopService.buildInactivityWatchdog(ctx, new NoopCallback());
        assertThat(wd).isNotNull();
        assertThat(wd.isEnabled()).isTrue();
        assertThat(wd.getInactivityTimeoutMs()).isEqualTo(5 * 60 * 1000L);
    }

    @Test
    @DisplayName("inactivityTimeout=0 disables the watchdog for this run")
    void zeroWindowDisablesWatchdog() {
        AgentLoopContext ctx = AgentLoopContext.builder().inactivityTimeout(0).build();
        assertThat(AgentLoopService.buildInactivityWatchdog(ctx, new NoopCallback())).isNull();
    }

    @Test
    @DisplayName("a per-agent inactivityTimeout (seconds) is honored")
    void perAgentWindowHonored() {
        AgentLoopContext ctx = AgentLoopContext.builder().inactivityTimeout(120).build(); // 2 min
        InactivityWatchdogCallback wd = AgentLoopService.buildInactivityWatchdog(ctx, new NoopCallback());
        assertThat(wd).isNotNull();
        assertThat(wd.getInactivityTimeoutMs()).isEqualTo(120_000L);
    }

    @Test
    @DisplayName("an already-wrapped callback is not double-wrapped")
    void alreadyWrappedIsReturnedAsIs() {
        AgentLoopContext ctx = AgentLoopContext.builder().build();
        InactivityWatchdogCallback existing = new InactivityWatchdogCallback(new NoopCallback(), 1000);
        assertThat(AgentLoopService.buildInactivityWatchdog(ctx, existing)).isSameAs(existing);
    }

    // ── reclassifyInactivity ──────────────────────────────────────────────────

    @Test
    @DisplayName("a STOPPED_BY_USER result is promoted to INACTIVITY_TIMEOUT when the watchdog tripped")
    void promotesStoppedByUserToInactivity() {
        AgentLoopResult stopped = AgentLoopResult.builder()
            .success(true)
            .stopReason(AgentStopReason.STOPPED_BY_USER)
            .build();

        AgentLoopResult out = AgentLoopService.reclassifyInactivity(stopped, trippedWatchdog());

        assertThat(out.stopReason()).isEqualTo(AgentStopReason.INACTIVITY_TIMEOUT);
        assertThat(out.success()).isFalse();
    }

    @Test
    @DisplayName("a non-STOPPED_BY_USER result (e.g. COMPLETED) is left untouched even if tripped")
    void leavesNonUserStopUntouched() {
        AgentLoopResult completed = AgentLoopResult.builder()
            .success(true)
            .stopReason(AgentStopReason.COMPLETED)
            .build();

        AgentLoopResult out = AgentLoopService.reclassifyInactivity(completed, trippedWatchdog());

        assertThat(out.stopReason()).isEqualTo(AgentStopReason.COMPLETED);
        assertThat(out).isSameAs(completed);
    }

    @Test
    @DisplayName("a STOPPED_BY_USER result with a watchdog that did NOT trip stays STOPPED_BY_USER (real user cancel)")
    void realUserCancelStaysStoppedByUser() {
        InactivityWatchdogCallback notTripped = new InactivityWatchdogCallback(new NoopCallback(), 60_000);
        AgentLoopResult stopped = AgentLoopResult.builder()
            .success(false)
            .stopReason(AgentStopReason.STOPPED_BY_USER)
            .build();

        AgentLoopResult out = AgentLoopService.reclassifyInactivity(stopped, notTripped);

        assertThat(out.stopReason()).isEqualTo(AgentStopReason.STOPPED_BY_USER);
        assertThat(out).isSameAs(stopped);
    }

    @Test
    @DisplayName("a null watchdog leaves the result untouched")
    void nullWatchdogIsNoOp() {
        AgentLoopResult stopped = AgentLoopResult.builder()
            .success(false)
            .stopReason(AgentStopReason.STOPPED_BY_USER)
            .build();

        assertThat(AgentLoopService.reclassifyInactivity(stopped, null)).isSameAs(stopped);
    }

    // ── resolveInactivityWindowMs (the per-agent override carried on credentials) ──

    @Test
    @DisplayName("resolveInactivityWindowMs: a per-agent credential override (seconds) wins over the default")
    void credentialOverrideWins() {
        AgentLoopContext ctx = AgentLoopContext.builder()
            .credentials(java.util.Map.of("__inactivityTimeoutSeconds__", 120))
            .build();
        assertThat(AgentLoopService.resolveInactivityWindowMs(ctx)).isEqualTo(120_000L);
    }

    @Test
    @DisplayName("resolveInactivityWindowMs: a 0 credential override disables the watchdog")
    void credentialOverrideZeroDisables() {
        AgentLoopContext ctx = AgentLoopContext.builder()
            .credentials(java.util.Map.of("__inactivityTimeoutSeconds__", 0))
            .build();
        assertThat(AgentLoopService.resolveInactivityWindowMs(ctx)).isZero();
    }

    @Test
    @DisplayName("resolveInactivityWindowMs: a string credential override (from the bridge/JSON) is parsed")
    void credentialOverrideStringParsed() {
        AgentLoopContext ctx = AgentLoopContext.builder()
            .credentials(java.util.Map.of("__inactivityTimeoutSeconds__", "90"))
            .build();
        assertThat(AgentLoopService.resolveInactivityWindowMs(ctx)).isEqualTo(90_000L);
    }

    @Test
    @DisplayName("resolveInactivityWindowMs: no credential -> the context default (5 min)")
    void noCredentialUsesDefault() {
        AgentLoopContext ctx = AgentLoopContext.builder().build();
        assertThat(AgentLoopService.resolveInactivityWindowMs(ctx)).isEqualTo(5 * 60 * 1000L);
    }

    @Test
    @DisplayName("resolveInactivityWindowMs: a malformed credential falls back to the default")
    void malformedCredentialFallsBack() {
        AgentLoopContext ctx = AgentLoopContext.builder()
            .credentials(java.util.Map.of("__inactivityTimeoutSeconds__", "not-a-number"))
            .build();
        assertThat(AgentLoopService.resolveInactivityWindowMs(ctx)).isEqualTo(5 * 60 * 1000L);
    }
}
