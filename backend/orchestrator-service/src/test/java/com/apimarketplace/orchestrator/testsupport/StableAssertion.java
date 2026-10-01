package com.apimarketplace.orchestrator.testsupport;

import java.time.Duration;
import java.time.Instant;

/**
 * Polls an assertion until it holds on TWO consecutive polls, then returns; on timeout the
 * assertion runs one last time so the failure carries the real values.
 *
 * <p>Why two. A node's step rows land one by one, and while a node has a single row the
 * aggregated-steps view carries no {@code statusCounts}: the count is inferred from that one
 * row's status. An expectation of one completed row therefore matches the instant the first
 * row lands, even if a second row (a SKIPPED item of the same split, say) is about to follow, and
 * a poller that returns on the first match lets a node that ends up with two rows pass as one.
 * Requiring the match to survive one more poll interval closes that window without a sleep on
 * the happy path beyond that one interval.
 */
public final class StableAssertion {

    private StableAssertion() {
    }

    public static void await(Runnable assertion, Duration timeout, Duration interval, String what) {
        Instant deadline = Instant.now().plus(timeout);
        boolean matchedLastPoll = false;
        while (Instant.now().isBefore(deadline)) {
            try {
                assertion.run();
                if (matchedLastPoll) {
                    return;
                }
                matchedLastPoll = true;
            } catch (AssertionError notYet) {
                matchedLastPoll = false;
            }
            try {
                Thread.sleep(interval.toMillis());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for " + what, ie);
            }
        }
        assertion.run();
    }
}
