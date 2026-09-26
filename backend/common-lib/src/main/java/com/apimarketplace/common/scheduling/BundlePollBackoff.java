package com.apimarketplace.common.scheduling;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * When a CE bundle poller (model catalog, skills, API catalog) may next talk to the cloud.
 *
 * <p><b>The problem.</b> A poller that fails keeps its 15-minute cadence forever. For the API
 * catalog that means a 32 MB download every quarter hour per broken install: measured on
 * 2026-09-26, six installs that could not apply the bundle pulled ~780 MB an hour from the cloud,
 * none of them ever getting closer to success.
 *
 * <p><b>Why the attempt is counted BEFORE the download.</b> The expensive failure is the one that
 * never reports itself: an {@code OutOfMemoryError} while applying is an {@code Error}, not an
 * {@code Exception}, so it escapes the scheduler's catch and no failure is ever recorded; a JVM
 * killed mid-apply records nothing either. So a scheduler raises the level and writes the next
 * allowed attempt as it STARTS ({@link #nextAttemptAt(int, Duration, Instant)} with no
 * Retry-After), and only a completed success lowers it back to zero. An attempt that never
 * finished is therefore a failure by default, and it survives a restart because the state lives
 * on the poller's sync-status row, not in memory.
 *
 * <p><b>The ladder</b> ({@link #delayForLevel(int)}): 10 min, 30 min, 1 h, 2 h, 4 h, then 6 h.
 * Level 1 (the first failure after a success) is shorter than the 15-minute tick on purpose, so a
 * single blip is retried at the very next tick, exactly as before. Only a streak slows down. The
 * 6 h ceiling keeps a broken install at four attempts a day, and still picks up a fixed cloud (or
 * an operator's memory bump) the same day without anyone pressing "sync now".
 *
 * <p><b>Retry-After</b> from the cloud (429 or 503) extends the wait, never shortens it, and is
 * capped at {@link #MAX_RETRY_AFTER} so a wrong header cannot silence an install for weeks.
 *
 * <p>A manual "sync now" is never deferred: it is the operator's explicit request, and it goes
 * through the same accounting so its outcome still moves the level.
 */
public final class BundlePollBackoff {

    /** Upper bound of the exponential ladder. */
    public static final Duration MAX_DELAY = Duration.ofHours(6);

    /** Upper bound applied to a cloud-supplied Retry-After. */
    public static final Duration MAX_RETRY_AFTER = Duration.ofHours(24);

    private static final Duration FIRST_DELAY = Duration.ofMinutes(10);
    private static final Duration SECOND_DELAY = Duration.ofMinutes(30);

    private BundlePollBackoff() {
    }

    /**
     * Wait imposed after {@code level} consecutive unsuccessful attempts. Level 0 (or less) means
     * the last attempt succeeded: no wait.
     */
    public static Duration delayForLevel(int level) {
        if (level <= 0) return Duration.ZERO;
        if (level == 1) return FIRST_DELAY;
        if (level == 2) return SECOND_DELAY;
        // 3 -> 1 h, 4 -> 2 h, 5 -> 4 h, then capped. The shift is bounded so a corrupted,
        // absurdly high level cannot overflow.
        long hours = 1L << Math.min(level - 3, 10);
        Duration d = Duration.ofHours(hours);
        return d.compareTo(MAX_DELAY) > 0 ? MAX_DELAY : d;
    }

    /**
     * The earliest moment the scheduled poll may run again after an attempt at {@code level}.
     *
     * @param retryAfter the cloud's Retry-After, or null. Only ever lengthens the wait.
     */
    public static Instant nextAttemptAt(int level, Duration retryAfter, Instant now) {
        Duration wait = delayForLevel(level);
        if (retryAfter != null && !retryAfter.isNegative()) {
            Duration capped = retryAfter.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : retryAfter;
            if (capped.compareTo(wait) > 0) wait = capped;
        }
        return now.plus(wait);
    }

    /** True while the scheduled poll must stay quiet. A null {@code nextAttemptAt} never defers. */
    public static boolean isDeferred(Instant nextAttemptAt, Instant now) {
        return nextAttemptAt != null && now.isBefore(nextAttemptAt);
    }

    /**
     * Parse an HTTP {@code Retry-After} value: delta-seconds or an HTTP-date (RFC 9110 10.2.3).
     * Returns null for a missing or unparseable value, and zero for a date already past, so a
     * malformed header simply falls back to the ladder.
     */
    public static Duration parseRetryAfter(String value, Instant now) {
        if (value == null) return null;
        String v = value.trim();
        if (v.isEmpty()) return null;
        if (v.chars().allMatch(Character::isDigit)) {
            try {
                return Duration.ofSeconds(Long.parseLong(v));
            } catch (NumberFormatException e) {
                // More digits than a long holds: far beyond any cap, so treat as the cap.
                return MAX_RETRY_AFTER;
            }
        }
        try {
            Instant at = ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            Duration d = Duration.between(now, at);
            return d.isNegative() ? Duration.ZERO : d;
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
