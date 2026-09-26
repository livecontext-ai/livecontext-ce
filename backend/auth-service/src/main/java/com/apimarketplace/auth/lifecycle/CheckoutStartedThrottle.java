package com.apimarketplace.auth.lifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lets at most one {@code checkout.started} through per user and checkout kind per
 * {@link #WINDOW}. Someone who opens the pricing checkout five times in an evening would
 * otherwise start five recovery sequences in Resend and get five copies of every email.
 *
 * <p>The window is kept on the user row (V529: {@code last_checkout_subscription_at},
 * {@code last_checkout_credits_at}) and claimed with ONE conditional UPDATE: Postgres locks
 * the row and re-checks the condition before writing, so of two pods racing on the same
 * user exactly one sees a row updated. That is what makes the throttle hold across every
 * auth-service instance and across restarts, which an in-memory map could not.
 *
 * <p>One column per kind, so a PAYG credits checkout never swallows the subscription checkout
 * that follows it (only the subscription one starts the recovery sequence). The columns are
 * not mapped on {@code User}, so a whole-row save can never rewind them.
 *
 * <p>The claim runs in its OWN transaction ({@code REQUIRES_NEW}): whoever calls it, a failing
 * UPDATE (a missing column, a lock or statement timeout) aborts only that transaction, never the
 * caller's. Its only caller claims on the lifecycle worker, after the checkout committed and the
 * event was queued ({@link LifecycleEmailService#emitIfClaimed}).
 *
 * <p>Fails CLOSED and never throws: a database error answers "throttled", because a missed
 * recovery email costs less than a checkout that failed or a duplicate sequence. Failing closed is
 * silent to the user, so it is logged at WARN (at most once per {@link #WARN_INTERVAL} per
 * instance, the rest at DEBUG): a missing V529 or a persistent SQL error must show in the logs.
 */
@Component
public class CheckoutStartedThrottle {

    private static final Logger log = LoggerFactory.getLogger(CheckoutStartedThrottle.class);

    static final Duration WINDOW = Duration.ofHours(24);

    /** At most one WARN per instance per interval while the throttle keeps failing. */
    static final Duration WARN_INTERVAL = Duration.ofMinutes(10);

    /** Kind -> its column. A closed map: the column name is never built from input. */
    static final Map<String, String> COLUMN_BY_KIND = Map.of(
            LifecycleEvents.KIND_SUBSCRIPTION, "last_checkout_subscription_at",
            LifecycleEvents.KIND_CREDITS, "last_checkout_credits_at");

    private final JdbcTemplate jdbc;
    private final TransactionOperations ownTx;
    private final Clock clock;
    private final AtomicReference<Instant> lastWarnAt = new AtomicReference<>();

    @Autowired
    public CheckoutStartedThrottle(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this(jdbc, requiresNew(transactionManager), Clock.systemUTC());
    }

    CheckoutStartedThrottle(JdbcTemplate jdbc, TransactionOperations ownTx, Clock clock) {
        this.jdbc = jdbc;
        this.ownTx = ownTx;
        this.clock = clock;
    }

    static TransactionOperations requiresNew(PlatformTransactionManager tm) {
        TransactionTemplate t = new TransactionTemplate(tm);
        t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return t;
    }

    /** True when this checkout.started may be sent, and records it; false inside the window. */
    public boolean tryAcquire(Long userId, String kind) {
        return acquire(userId, kind) != null;
    }

    /**
     * The window as a {@link LifecycleEmailService.Claim}: granted like {@link #tryAcquire}, and
     * given back when the event it gated never left Resend, clearing only the stamp THIS grant
     * wrote. A racing pod that re-claimed in between is never undone.
     */
    public LifecycleEmailService.Claim claim(Long userId, String kind) {
        return new LifecycleEmailService.Claim() {
            private Instant stampedAt;

            @Override
            public boolean getAsBoolean() {
                stampedAt = acquire(userId, kind);
                return stampedAt != null;
            }

            @Override
            public void release() {
                if (stampedAt != null) CheckoutStartedThrottle.this.release(userId, kind, stampedAt);
            }
        };
    }

    /** The stamp written when the window was granted, null otherwise. */
    private Instant acquire(Long userId, String kind) {
        String column = kind != null ? COLUMN_BY_KIND.get(kind) : null;
        if (userId == null || column == null) return null;
        // Microseconds: what a TIMESTAMPTZ stores, so the release can match the stamp exactly.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        try {
            Integer rows = ownTx.execute(status -> jdbc.update(
                    "UPDATE auth.users SET " + column + " = ? WHERE id = ? AND (" + column + " IS NULL OR "
                            + column + " <= ?)",
                    Timestamp.from(now), userId, Timestamp.from(now.minus(WINDOW))));
            return rows != null && rows > 0 ? now : null;
        } catch (Exception e) {
            if (claimWarnSlot(now)) {
                log.warn("[lifecycle] checkout throttle unavailable, failing closed (no checkout.started sent) "
                        + "for user {} ({}); repeats are logged at DEBUG for {} min: {}",
                        userId, kind, WARN_INTERVAL.toMinutes(), e.toString());
            } else {
                log.debug("[lifecycle] checkout throttle unavailable for user {} ({}): {}", userId, kind, e.toString());
            }
            return null;
        }
    }

    /**
     * Clears the window only while it still holds {@code stampedAt}. The value it replaced was
     * NULL or already outside the window, so NULL is equivalent to it. Never throws.
     */
    void release(Long userId, String kind, Instant stampedAt) {
        String column = COLUMN_BY_KIND.get(kind);
        try {
            ownTx.execute(status -> jdbc.update(
                    "UPDATE auth.users SET " + column + " = NULL WHERE id = ? AND " + column + " = ?",
                    userId, Timestamp.from(stampedAt)));
        } catch (Exception e) {
            log.warn("[lifecycle] checkout throttle window of user {} ({}) not released: {}", userId, kind, e.toString());
        }
    }

    /** True for the first failure of each {@link #WARN_INTERVAL}; one winner under concurrency. */
    private boolean claimWarnSlot(Instant now) {
        Instant last = lastWarnAt.get();
        if (last != null && now.isBefore(last.plus(WARN_INTERVAL))) return false;
        return lastWarnAt.compareAndSet(last, now);
    }
}
