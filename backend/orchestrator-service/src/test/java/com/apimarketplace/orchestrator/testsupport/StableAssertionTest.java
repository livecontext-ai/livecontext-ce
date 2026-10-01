package com.apimarketplace.orchestrator.testsupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("StableAssertion (awaitStatusCounts polling)")
class StableAssertionTest {

    private static final Duration INTERVAL = Duration.ofMillis(5);

    /** Plays the observed counts poll after poll, repeating the last one. */
    private static Runnable observed(AtomicInteger polls, int expected, List<Integer> counts) {
        return () -> {
            int poll = polls.getAndIncrement();
            int actual = counts.get(Math.min(poll, counts.size() - 1));
            assertEquals(expected, actual, "rows at poll " + poll);
        };
    }

    @Test
    @DisplayName("a late extra row after an early one-row match fails instead of passing")
    void lateExtraRowAfterAnEarlyMatchFails() {
        // Poll 0: one row, the inferred count matches. Poll 1 on: the second row has landed.
        AtomicInteger polls = new AtomicInteger();

        AssertionError error = assertThrows(AssertionError.class, () -> StableAssertion.await(
            observed(polls, 1, List.of(1, 2)), Duration.ofMillis(100), INTERVAL, "node"));

        assertTrue(error.getMessage().contains("expected: <1> but was: <2>"), error.getMessage());
    }

    @Test
    @DisplayName("a match that holds on two consecutive polls returns after exactly two polls")
    void stableMatchReturnsAfterTwoPolls() {
        AtomicInteger polls = new AtomicInteger();

        StableAssertion.await(observed(polls, 1, List.of(1)), Duration.ofSeconds(5), INTERVAL, "node");

        assertEquals(2, polls.get());
    }

    @Test
    @DisplayName("a match interrupted by a mismatch starts counting again")
    void aMismatchResetsTheStreak() {
        AtomicInteger polls = new AtomicInteger();

        StableAssertion.await(observed(polls, 2, List.of(0, 2, 1, 2)), Duration.ofSeconds(5), INTERVAL, "node");

        assertEquals(5, polls.get(), "polls 1 and 3..4: only the last two are consecutive matches");
    }

    @Test
    @DisplayName("on timeout the assertion runs once more and fails with the real values")
    void timeoutRethrowsTheRealFailure() {
        AtomicInteger polls = new AtomicInteger();

        AssertionError error = assertThrows(AssertionError.class, () -> StableAssertion.await(
            observed(polls, 3, List.of(1)), Duration.ofMillis(30), INTERVAL, "node"));

        assertTrue(error.getMessage().contains("expected: <3> but was: <1>"), error.getMessage());
    }

    @Test
    @DisplayName("a zero timeout still judges once")
    void zeroTimeoutJudgesOnce() {
        AtomicInteger polls = new AtomicInteger();

        StableAssertion.await(observed(polls, 1, List.of(1)), Duration.ZERO, INTERVAL, "node");

        assertEquals(1, polls.get());
    }
}
