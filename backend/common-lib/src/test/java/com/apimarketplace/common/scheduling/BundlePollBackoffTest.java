package com.apimarketplace.common.scheduling;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BundlePollBackoff - when a CE bundle poller may talk to the cloud again")
class BundlePollBackoffTest {

    private static final Instant NOW = Instant.parse("2026-09-26T12:00:00Z");

    @ParameterizedTest(name = "level {0} -> {1} min")
    @CsvSource({
            "-3, 0",
            "0, 0",
            "1, 10",
            "2, 30",
            "3, 60",
            "4, 120",
            "5, 240",
            "6, 360",
            "7, 360",
            "40, 360",
            "2147483647, 360",
    })
    @DisplayName("the ladder: no wait after a success, a first failure retried at the next 15-min tick, then doubling up to 6 h")
    void ladder(int level, long minutes) {
        assertThat(BundlePollBackoff.delayForLevel(level)).isEqualTo(Duration.ofMinutes(minutes));
    }

    @Test
    @DisplayName("level 1 is shorter than the 15-minute poll, so a single blip changes nothing for the next tick")
    void firstFailureDoesNotSkipTheNextTick() {
        Instant next = BundlePollBackoff.nextAttemptAt(1, null, NOW);
        assertThat(BundlePollBackoff.isDeferred(next, NOW.plus(Duration.ofMinutes(15)))).isFalse();
    }

    @Test
    @DisplayName("level 2 skips the next 15-minute tick: only a streak slows down")
    void streakSkipsTheNextTick() {
        Instant next = BundlePollBackoff.nextAttemptAt(2, null, NOW);
        assertThat(BundlePollBackoff.isDeferred(next, NOW.plus(Duration.ofMinutes(15)))).isTrue();
        assertThat(BundlePollBackoff.isDeferred(next, NOW.plus(Duration.ofMinutes(30)))).isFalse();
    }

    @Test
    @DisplayName("a Retry-After longer than the ladder wins")
    void retryAfterLengthens() {
        assertThat(BundlePollBackoff.nextAttemptAt(1, Duration.ofHours(3), NOW))
                .isEqualTo(NOW.plus(Duration.ofHours(3)));
    }

    @Test
    @DisplayName("a Retry-After shorter than the ladder never shortens the wait")
    void retryAfterNeverShortens() {
        assertThat(BundlePollBackoff.nextAttemptAt(5, Duration.ofSeconds(30), NOW))
                .isEqualTo(NOW.plus(Duration.ofHours(4)));
    }

    @Test
    @DisplayName("a Retry-After is capped at 24 h, so a wrong header cannot silence an install for weeks")
    void retryAfterCapped() {
        assertThat(BundlePollBackoff.nextAttemptAt(1, Duration.ofDays(30), NOW))
                .isEqualTo(NOW.plus(BundlePollBackoff.MAX_RETRY_AFTER));
    }

    @Test
    @DisplayName("a negative Retry-After is ignored")
    void negativeRetryAfterIgnored() {
        assertThat(BundlePollBackoff.nextAttemptAt(2, Duration.ofMinutes(-5), NOW))
                .isEqualTo(NOW.plus(Duration.ofMinutes(30)));
    }

    @Test
    @DisplayName("isDeferred: null never defers, a past instant does not, a future one does")
    void isDeferred() {
        assertThat(BundlePollBackoff.isDeferred(null, NOW)).isFalse();
        assertThat(BundlePollBackoff.isDeferred(NOW.minusSeconds(1), NOW)).isFalse();
        assertThat(BundlePollBackoff.isDeferred(NOW, NOW)).as("the boundary instant is due").isFalse();
        assertThat(BundlePollBackoff.isDeferred(NOW.plusSeconds(1), NOW)).isTrue();
    }

    @Test
    @DisplayName("Retry-After as delta-seconds")
    void parseSeconds() {
        assertThat(BundlePollBackoff.parseRetryAfter("3600", NOW)).isEqualTo(Duration.ofHours(1));
        assertThat(BundlePollBackoff.parseRetryAfter("  120 ", NOW)).isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    @DisplayName("Retry-After as an HTTP-date, including one already past (zero, not negative)")
    void parseHttpDate() {
        String inTwoHours = DateTimeFormatter.RFC_1123_DATE_TIME
                .format(ZonedDateTime.ofInstant(NOW.plus(Duration.ofHours(2)), ZoneOffset.UTC));
        assertThat(BundlePollBackoff.parseRetryAfter(inTwoHours, NOW)).isEqualTo(Duration.ofHours(2));

        String past = DateTimeFormatter.RFC_1123_DATE_TIME
                .format(ZonedDateTime.ofInstant(NOW.minus(Duration.ofHours(2)), ZoneOffset.UTC));
        assertThat(BundlePollBackoff.parseRetryAfter(past, NOW)).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("a missing or unparseable Retry-After falls back to the ladder (null); an absurd number is the cap")
    void parseGarbage() {
        assertThat(BundlePollBackoff.parseRetryAfter(null, NOW)).isNull();
        assertThat(BundlePollBackoff.parseRetryAfter("", NOW)).isNull();
        assertThat(BundlePollBackoff.parseRetryAfter("soon", NOW)).isNull();
        assertThat(BundlePollBackoff.parseRetryAfter("-5", NOW)).isNull();
        assertThat(BundlePollBackoff.parseRetryAfter("99999999999999999999999", NOW))
                .isEqualTo(BundlePollBackoff.MAX_RETRY_AFTER);
    }
}
