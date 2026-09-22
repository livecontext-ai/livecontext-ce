package com.apimarketplace.common.credit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shared agent-budget decision, which three services depend on answering identically.
 *
 * <p>Each group below pins a property that something OUTSIDE this class relies on, and the
 * comment says which - a test that only restates the implementation would not survive the
 * next person deciding the implementation looked odd.
 */
@DisplayName("AgentBudgetRule")
class AgentBudgetRuleTest {

    private static final Instant NOW = Instant.parse("2026-09-18T12:00:00Z");
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private static BigDecimal credits(String amount) {
        return new BigDecimal(amount);
    }

    @Nested
    @DisplayName("blocked")
    class Blocked {

        @Test
        @DisplayName("refuses once consumption REACHES the cap, not once it passes it")
        void refusesAtTheCapNotPastIt() {
            // The boundary is >=, and it has to be, because AgentBudgetGuard denies on
            // >= too. An exclusive test here would let a run start that the guard refuses
            // on its first iteration, which is the worst of both: the fire is counted, the
            // conversation is opened, and nothing comes out of it.
            assertThat(AgentBudgetRule.blocked(credits("10"), credits("10"), ZERO,
                    "cumulative", null, NOW)).isTrue();
            assertThat(AgentBudgetRule.blocked(credits("10"), credits("9.9999"), ZERO,
                    "cumulative", null, NOW)).isFalse();
        }

        @Test
        @DisplayName("counts credits a sub-agent is holding, not only credits already spent")
        void countsReservedCredits() {
            // A cascade reservation is money committed to a descendant that is still
            // running. Leaving it out would let a parent start a run against credits its
            // own child is about to consume.
            assertThat(AgentBudgetRule.blocked(credits("10"), credits("6"), credits("4"),
                    "cumulative", null, NOW)).isTrue();
            assertThat(AgentBudgetRule.blocked(credits("10"), credits("6"), credits("3"),
                    "cumulative", null, NOW)).isFalse();
        }

        @Test
        @DisplayName("an uncapped agent is never blocked")
        void anUncappedAgentIsNeverBlocked() {
            assertThat(AgentBudgetRule.blocked(null, credits("9999"), credits("9999"),
                    "cumulative", null, NOW)).isFalse();
        }

        @Test
        @DisplayName("a zero or negative cap disables the rule rather than blocking everything")
        void aNonPositiveCapDisablesTheRule() {
            // Zero reads as "no cap" everywhere else in this feature (BudgetState.isEnabled,
            // AgentBudgetGuard's own disabled branch). Reading it as "cap of nothing" here
            // would stop every agent whose budget field was cleared to 0 instead of null.
            assertThat(AgentBudgetRule.blocked(ZERO, credits("5"), ZERO, "cumulative", null, NOW)).isFalse();
            assertThat(AgentBudgetRule.blocked(credits("-1"), credits("5"), ZERO, "cumulative", null, NOW)).isFalse();
        }

        @Test
        @DisplayName("a null instant answers not-blocked, so a gate that cannot read the clock stays open")
        void failsOpenOnANullInstant() {
            assertThat(AgentBudgetRule.blocked(credits("1"), credits("5"), ZERO,
                    "cumulative", null, null)).isFalse();
        }

        @Test
        @DisplayName("null counters are read as zero, not as a reason to refuse")
        void nullCountersAreZero() {
            assertThat(AgentBudgetRule.blocked(credits("10"), null, null,
                    "cumulative", null, NOW)).isFalse();
        }
    }

    @Nested
    @DisplayName("the pending reset, applied without writing")
    class PendingReset {

        @Test
        @DisplayName("a monthly agent that hit its cap LAST month is not blocked today")
        void aRolledOverMonthlyAgentIsNotBlocked() {
            // This is the case the whole read-only reset exists for. The counter is only
            // zeroed when the agent next EXECUTES, so the stored figure still reads at the
            // cap. A rule that answered from the figure alone would grey out a month of
            // fires that are going to run, and refuse a manual run that would have worked.
            Instant lastMonth = Instant.parse("2026-08-20T00:00:00Z");
            assertThat(AgentBudgetRule.blocked(credits("10"), credits("10"), ZERO,
                    "monthly", lastMonth, NOW)).isFalse();
        }

        @Test
        @DisplayName("a monthly agent that hit its cap THIS month is blocked")
        void aMonthlyAgentInsideItsPeriodIsBlocked() {
            Instant earlierThisMonth = Instant.parse("2026-09-02T00:00:00Z");
            assertThat(AgentBudgetRule.blocked(credits("10"), credits("10"), ZERO,
                    "monthly", earlierThisMonth, NOW)).isTrue();
        }

        @Test
        @DisplayName("weekly rolls on the 7th day, measured from the last reset")
        void weeklyRollsOnTheSeventhDay() {
            assertThat(AgentBudgetRule.resetDue("weekly", NOW.minus(7, ChronoUnit.DAYS), NOW)).isTrue();
            assertThat(AgentBudgetRule.resetDue("weekly", NOW.minus(6, ChronoUnit.DAYS), NOW)).isFalse();
        }

        @Test
        @DisplayName("a reset a held reservation would refuse is NOT predicted")
        void aResetBlockedByAReservationIsNotPredicted() {
            // The persisted reset is a CAS that requires credits_reserved = 0
            // (AgentRepository.resetConsumedIfUnreservedAndUnchanged). Predicting a reset
            // the enforcement path will refuse is worse than predicting nothing: it answers
            // "not blocked" for an agent whose run is about to be denied on arrival.
            Instant lastMonth = Instant.parse("2026-08-20T00:00:00Z");
            assertThat(AgentBudgetRule.blocked(credits("10"), credits("10"), credits("2"),
                    "monthly", lastMonth, NOW)).isTrue();
        }

        @Test
        @DisplayName("cumulative never rolls over, whatever the dates say")
        void cumulativeNeverRollsOver() {
            assertThat(AgentBudgetRule.resetDue("cumulative", Instant.EPOCH, NOW)).isFalse();
            assertThat(AgentBudgetRule.resetDue(null, Instant.EPOCH, NOW)).isFalse();
        }

        @Test
        @DisplayName("an unknown mode is cumulative, which is the AGENT default and not the workflow one")
        void anUnknownModeIsCumulative() {
            // The two owners disagree on purpose: a workflow with no mode resets monthly, an
            // agent with no mode never resets. Reading an unknown value as "monthly" here
            // would silently re-arm capped agents once a month.
            assertThat(AgentBudgetRule.resetDue("quarterly", Instant.EPOCH, NOW)).isFalse();
        }

        @Test
        @DisplayName("a differently-CASED mode never resets, exactly as it did before")
        void modeMatchingIsExact() {
            // budget_reset_mode is persisted with no validation whatsoever: AgentService and
            // AgentCrudModule write whatever arrives, so "Monthly" from an LLM is a row that
            // exists and has NEVER reset. Accepting it here would be a silent data migration:
            // every such agent would start zeroing its accumulator on a cadence its owner
            // never chose, and the first symptom would be spend where a cap used to hold.
            assertThat(AgentBudgetRule.resetDue("Monthly", Instant.EPOCH, NOW)).isFalse();
            assertThat(AgentBudgetRule.resetDue("WEEKLY", Instant.EPOCH, NOW)).isFalse();
            assertThat(AgentBudgetRule.resetDue(" monthly ", Instant.EPOCH, NOW)).isFalse();
            assertThat(AgentBudgetRule.resetDue("monthly", Instant.EPOCH, NOW)).isTrue();
        }
    }

    @Nested
    @DisplayName("blockedUntil")
    class BlockedUntil {

        @Test
        @DisplayName("is null for a cumulative cap, which is how a caller knows it never lifts")
        void nullMeansNeverForACumulativeCap() {
            // The agenda reads exactly this: a null "until" on a blocked schedule greys the
            // WHOLE projected future instead of a window of it.
            assertThat(AgentBudgetRule.blockedUntil(credits("1"), credits("5"), ZERO,
                    "cumulative", null, NOW)).isNull();
        }

        @Test
        @DisplayName("names the first instant of the next calendar month for a monthly cap")
        void monthlyLiftsAtTheStartOfNextMonth() {
            Instant earlierThisMonth = Instant.parse("2026-09-02T00:00:00Z");
            assertThat(AgentBudgetRule.blockedUntil(credits("1"), credits("5"), ZERO,
                    "monthly", earlierThisMonth, NOW))
                    .isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        }

        @Test
        @DisplayName("is anchored on the last reset for a weekly cap, not on the calendar week")
        void weeklyLiftsSevenDaysAfterTheLastReset() {
            // resetDue measures from lastReset, so a date derived any other way would be one
            // the enforcement path does not honour.
            Instant lastReset = Instant.parse("2026-09-15T08:30:00Z");
            assertThat(AgentBudgetRule.blockedUntil(credits("1"), credits("5"), ZERO,
                    "weekly", lastReset, NOW))
                    .isEqualTo(Instant.parse("2026-09-22T08:30:00Z"));
        }

        @Test
        @DisplayName("is null when the agent is not blocked at all")
        void nullWhenNotBlocked() {
            assertThat(AgentBudgetRule.blockedUntil(credits("10"), credits("1"), ZERO,
                    "monthly", NOW, NOW)).isNull();
        }

        @Test
        @DisplayName("never points at the past, or every future fire reads as un-blocked")
        void neverPointsAtThePast() {
            // The shape that produces it: a WEEKLY agent whose reset is due (lastReset is 13
            // days old) but held off by a sub-agent reservation, which the CAS refuses. It is
            // blocked now, and lastReset + 7d is six days behind us.
            //
            // Every reader compares this instant against a FUTURE moment, so a past one means
            // "not blocked any more" for the whole projected future: the agenda would draw
            // armed fires and leave Run now enabled on occurrences the gate refuses, which is
            // the exact failure this feature exists to remove.
            Instant thirteenDaysAgo = NOW.minus(13, ChronoUnit.DAYS);
            Instant until = AgentBudgetRule.blockedUntil(credits("10"), credits("10"), credits("2"),
                    "weekly", thirteenDaysAgo, NOW);

            assertThat(until).isNotNull();
            assertThat(until).isAfterOrEqualTo(NOW);
        }

        @Test
        @DisplayName("a MONTHLY reset held by a reservation lifts now, not on the 1st of next month")
        void anOverdueMonthlyResetDoesNotNameTheNextPeriod() {
            // The other spelling of the same defect, and the one the clamp exists for: the
            // monthly branch returns a FUTURE instant that is never in the past, so nothing
            // catches it. An agent whose September cap rolled over but whose reset the CAS
            // refuses (a sub-agent holds a reservation) would name 1 October, and the agenda
            // would grey a fortnight of fires over a descendant that settles in seconds.
            Instant lastMonth = Instant.parse("2026-08-20T00:00:00Z");
            Instant until = AgentBudgetRule.blockedUntil(credits("10"), credits("10"), credits("2"),
                    "monthly", lastMonth, NOW);

            assertThat(until).isEqualTo(NOW);
        }

        @Test
        @DisplayName("a monthly cap that has NOT rolled over still names the next period start")
        void aMonthlyCapInsideItsPeriodNamesTheBoundary() {
            // The clamp must not swallow the normal case, which is the whole point of naming a
            // date: the reader can decide to wait.
            Instant earlierThisMonth = Instant.parse("2026-09-02T00:00:00Z");
            assertThat(AgentBudgetRule.blockedUntil(credits("10"), credits("12"), ZERO,
                    "monthly", earlierThisMonth, NOW))
                    .isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        }

        @Test
        @DisplayName("a weekly agent that has NEVER reset does not name a past date either")
        void aNeverResetWeeklyAgentDoesNotNameThePast() {
            Instant until = AgentBudgetRule.blockedUntil(credits("10"), credits("10"), credits("2"),
                    "weekly", null, NOW);

            assertThat(until).isAfterOrEqualTo(NOW);
        }
    }
}
