package com.apimarketplace.agent.gemini.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 2.2 - pin the min-token / min-traffic gate (R17).
 *
 * <p>The gate's purpose is to keep Gemini {@code cachedContent} storage
 * fees from outrunning the token savings. Each test below maps to a
 * concrete waste scenario:
 * <ul>
 *   <li>Undersized prefix → 400 from the API on create, wasted call.</li>
 *   <li>Low-traffic tenant → TTL expires before re-use, 100% of storage
 *       fee with 0% of discount.</li>
 *   <li>Unknown model string → we can't know the floor, so refuse.</li>
 * </ul>
 */
@DisplayName("GeminiCacheGate - min-token + min-traffic eligibility (Stage 2.2)")
class GeminiCacheGateTest {

    @ParameterizedTest(name = "{0}: Flash floor = 1024, Pro floor = 4096")
    @CsvSource({
            "gemini-1.5-flash, 1024",
            "gemini-1.5-flash-8b, 1024",
            "gemini-2.0-flash, 1024",
            "gemini-1.5-pro, 4096",
            "gemini-1.0-pro, 4096",
            "gemini-2.5-pro, 4096"
    })
    @DisplayName("minCachedTokensFor picks the right floor per family")
    void minCachedTokensPerModelFamily(String model, int expectedFloor) {
        assertThat(GeminiCacheGate.minCachedTokensFor(model)).isEqualTo(expectedFloor);
    }

    @Test
    @DisplayName("unknown model defaults to the stricter Pro floor - never undershoot")
    void unknownModelFallsBackToStricterFloor() {
        // A new tier or a typo must NOT silently get the lower Flash
        // floor - undercutting the API minimum causes 400s at runtime.
        assertThat(GeminiCacheGate.minCachedTokensFor("gemini-9000-turbo"))
                .isEqualTo(GeminiCacheGate.PRO_MIN_CACHED_TOKENS);
    }

    @Test
    @DisplayName("null model → skip with UNKNOWN_MODEL; don't guess a floor")
    void nullModelSkipsWithUnknownReason() {
        GeminiCacheGate.Decision d = GeminiCacheGate.decide(null, 100_000, 100, 3);
        assertThat(d.eligible()).isFalse();
        assertThat(d.reason()).isEqualTo(GeminiCacheGate.SkipReason.UNKNOWN_MODEL);
    }

    @Test
    @DisplayName("blank model → skip with UNKNOWN_MODEL")
    void blankModelSkipsWithUnknownReason() {
        GeminiCacheGate.Decision d = GeminiCacheGate.decide("   ", 100_000, 100, 3);
        assertThat(d.eligible()).isFalse();
        assertThat(d.reason()).isEqualTo(GeminiCacheGate.SkipReason.UNKNOWN_MODEL);
    }

    @Test
    @DisplayName("Flash prefix just below 1024 → skip BELOW_MIN_TOKENS, threshold pinned at 1024")
    void flashPrefixUnderFloorSkips() {
        GeminiCacheGate.Decision d = GeminiCacheGate.decide("gemini-1.5-flash", 1023, 100, 3);
        assertThat(d.eligible()).isFalse();
        assertThat(d.reason()).isEqualTo(GeminiCacheGate.SkipReason.BELOW_MIN_TOKENS);
        assertThat(d.threshold()).isEqualTo(1024);
    }

    @Test
    @DisplayName("Pro prefix at exactly 4096 → eligible (boundary is inclusive)")
    void proPrefixAtExactFloorIsEligible() {
        // Inclusive boundary: 4096 is the minimum accepted by the API;
        // skipping at exactly 4096 would reject the smallest valid
        // cache possible. The test pins the `>=` semantics.
        GeminiCacheGate.Decision d = GeminiCacheGate.decide("gemini-1.5-pro", 4096, 100, 3);
        assertThat(d.eligible()).isTrue();
    }

    @Test
    @DisplayName("Pro prefix at 4095 → skip (just below floor, API would reject)")
    void proPrefixOneBelowFloorSkips() {
        GeminiCacheGate.Decision d = GeminiCacheGate.decide("gemini-1.5-pro", 4095, 100, 3);
        assertThat(d.eligible()).isFalse();
        assertThat(d.reason()).isEqualTo(GeminiCacheGate.SkipReason.BELOW_MIN_TOKENS);
    }

    @Test
    @DisplayName("large prefix but traffic below min → skip BELOW_MIN_TRAFFIC")
    void largePrefixWithLowTrafficSkips() {
        // Token floor OK (50k), but only 1 req/hour observed → skip so
        // we don't pay TTL storage for a cache that never gets re-hit.
        GeminiCacheGate.Decision d = GeminiCacheGate.decide("gemini-1.5-pro", 50_000, 1, 3);
        assertThat(d.eligible()).isFalse();
        assertThat(d.reason()).isEqualTo(GeminiCacheGate.SkipReason.BELOW_MIN_TRAFFIC);
        assertThat(d.threshold()).isEqualTo(3);
    }

    @Test
    @DisplayName("traffic at exactly min-req-per-hour → eligible (>= semantics)")
    void trafficAtExactMinIsEligible() {
        GeminiCacheGate.Decision d = GeminiCacheGate.decide("gemini-1.5-pro", 50_000, 3, 3);
        assertThat(d.eligible()).isTrue();
    }

    @Test
    @DisplayName("both floors satisfied → eligible, reason null, model echoed")
    void bothFloorsSatisfiedIsEligible() {
        GeminiCacheGate.Decision d = GeminiCacheGate.decide("gemini-1.5-flash", 2_000, 10, 3);
        assertThat(d.eligible()).isTrue();
        assertThat(d.reason()).isNull();
        assertThat(d.modelName()).isEqualTo("gemini-1.5-flash");
    }

    @Test
    @DisplayName("token check precedes traffic check - fires the FIRST reason, not both")
    void tokenFloorTakesPrecedenceOverTraffic() {
        // A single structured reason on the log line is more actionable
        // than two flags - pin the priority so it doesn't silently flip.
        GeminiCacheGate.Decision d = GeminiCacheGate.decide("gemini-1.5-pro", 100, 0, 3);
        assertThat(d.reason())
                .as("tokens-first, traffic-second - matches the Grafana panel order")
                .isEqualTo(GeminiCacheGate.SkipReason.BELOW_MIN_TOKENS);
    }

    @Test
    @DisplayName("negative min-req-per-hour is clamped to 0 - defensive for bad config")
    void negativeMinReqIsClampedToZero() {
        // A misconfigured deployment passing -1 shouldn't accidentally
        // make every tenant skip; clamp to 0 so the gate degrades to
        // "token floor only", which is the safer default.
        GeminiCacheGate.Decision d = GeminiCacheGate.decide("gemini-1.5-pro", 50_000, 0, -1);
        assertThat(d.eligible()).isTrue();
    }
}
