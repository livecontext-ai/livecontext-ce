package com.apimarketplace.auth.lifecycle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The throttle's guard rails, without a database. Its real rule (one window per user and kind,
 * held across pods) is proven on Postgres by {@link CheckoutStartedThrottlePostgresTest}.
 */
@DisplayName("CheckoutStartedThrottle - input guards and fail-closed")
class CheckoutStartedThrottleTest {

    private static final Instant NOW = Instant.parse("2026-09-24T10:00:00Z");

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final CheckoutStartedThrottle throttle =
            new CheckoutStartedThrottle(jdbc, TransactionOperations.withoutTransaction(),
                    Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("a kind claims ITS column, with a cutoff exactly 24 hours back")
    void claimsTheKindsColumn() {
        when(jdbc.update(anyString(), any(), any(), any())).thenReturn(1);

        assertThat(throttle.tryAcquire(7L, LifecycleEvents.KIND_CREDITS)).isTrue();

        verify(jdbc).update(
                eq("UPDATE auth.users SET last_checkout_credits_at = ? WHERE id = ? AND "
                        + "(last_checkout_credits_at IS NULL OR last_checkout_credits_at <= ?)"),
                eq(Timestamp.from(NOW)), eq(7L), eq(Timestamp.from(NOW.minusSeconds(24 * 3600))));
    }

    @Test
    @DisplayName("Regression (recovery email lost): a released claim clears only the stamp it wrote, truncated to what TIMESTAMPTZ keeps")
    void releaseClearsOnlyItsOwnStamp() {
        Instant nanos = NOW.plusNanos(123_456_789);
        Timestamp micros = Timestamp.from(NOW.plusNanos(123_456_000));
        CheckoutStartedThrottle precise = new CheckoutStartedThrottle(jdbc, TransactionOperations.withoutTransaction(),
                Clock.fixed(nanos, ZoneOffset.UTC));
        when(jdbc.update(anyString(), any(), any(), any())).thenReturn(1);
        LifecycleEmailService.Claim claim = precise.claim(7L, LifecycleEvents.KIND_SUBSCRIPTION);

        assertThat(claim.getAsBoolean()).isTrue();
        claim.release();

        verify(jdbc).update(anyString(), eq(micros), eq(7L), any());
        verify(jdbc).update(
                eq("UPDATE auth.users SET last_checkout_subscription_at = NULL WHERE id = ? AND "
                        + "last_checkout_subscription_at = ?"),
                eq(7L), eq(micros));
    }

    @Test
    @DisplayName("a refused claim releases nothing, and a failing release never throws")
    void refusedClaimReleasesNothing() {
        when(jdbc.update(anyString(), any(), any(), any())).thenReturn(0);
        LifecycleEmailService.Claim refused = throttle.claim(7L, LifecycleEvents.KIND_CREDITS);
        assertThat(refused.getAsBoolean()).isFalse();
        refused.release();
        verify(jdbc, org.mockito.Mockito.never()).update(anyString(), any(), any());

        when(jdbc.update(anyString(), any(), any(), any())).thenReturn(1);
        when(jdbc.update(anyString(), any(), any())).thenThrow(new DataAccessResourceFailureException("down"));
        LifecycleEmailService.Claim granted = throttle.claim(7L, LifecycleEvents.KIND_CREDITS);
        assertThat(granted.getAsBoolean()).isTrue();
        org.assertj.core.api.Assertions.assertThatCode(granted::release).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("no row updated = inside the window = throttled")
    void noRowIsThrottled() {
        when(jdbc.update(anyString(), any(), any(), any())).thenReturn(0);

        assertThat(throttle.tryAcquire(7L, LifecycleEvents.KIND_SUBSCRIPTION)).isFalse();
    }

    @Test
    @DisplayName("an unknown kind or a null user never reaches SQL (the column name is never built from input)")
    void unknownKindNeverQueries() {
        assertThat(throttle.tryAcquire(7L, "x; DROP TABLE auth.users")).isFalse();
        assertThat(throttle.tryAcquire(7L, null)).isFalse();
        assertThat(throttle.tryAcquire(null, LifecycleEvents.KIND_CREDITS)).isFalse();

        verifyNoInteractions(jdbc);
    }

    @Test
    @DisplayName("a database failure fails CLOSED (no event) and never throws into the checkout")
    void failsClosed() {
        when(jdbc.update(anyString(), any(), any(), any())).thenThrow(new DataAccessResourceFailureException("down"));

        assertThat(throttle.tryAcquire(7L, LifecycleEvents.KIND_SUBSCRIPTION)).isFalse();
    }
    @Test
    @DisplayName("Regression (silent fail-closed): a failing throttle WARNs at most once per 10 minutes per instance")
    void failClosedWarnsRateLimited() {
        java.util.concurrent.atomic.AtomicReference<Instant> now = new java.util.concurrent.atomic.AtomicReference<>(NOW);
        Clock moving = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        CheckoutStartedThrottle failing =
                new CheckoutStartedThrottle(jdbc, TransactionOperations.withoutTransaction(), moving);
        when(jdbc.update(anyString(), any(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("column \"last_checkout_credits_at\" does not exist"));
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(CheckoutStartedThrottle.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            failing.tryAcquire(7L, LifecycleEvents.KIND_CREDITS);               // first failure: WARN
            now.set(NOW.plus(java.time.Duration.ofMinutes(9)));
            failing.tryAcquire(8L, LifecycleEvents.KIND_SUBSCRIPTION);          // inside the interval: silent
            now.set(NOW.plus(CheckoutStartedThrottle.WARN_INTERVAL));
            failing.tryAcquire(9L, LifecycleEvents.KIND_CREDITS);               // interval elapsed: WARN again
        } finally {
            logger.detachAppender(appender);
        }

        java.util.List<ch.qos.logback.classic.spi.ILoggingEvent> warns = appender.list.stream()
                .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                .toList();
        assertThat(warns).hasSize(2);
        assertThat(warns.get(0).getFormattedMessage()).contains("failing closed").contains("does not exist");
        assertThat(CheckoutStartedThrottle.WARN_INTERVAL).isEqualTo(java.time.Duration.ofMinutes(10));
    }
}
