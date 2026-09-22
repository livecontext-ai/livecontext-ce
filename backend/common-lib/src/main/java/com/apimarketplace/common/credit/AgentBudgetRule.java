package com.apimarketplace.common.credit;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * The single answer to "has this agent spent its own credit budget".
 *
 * <p><b>Why it is here and not in agent-service.</b> The budget is owned by
 * {@code agent.agents.credit_budget}, but three different services need the verdict and
 * only one of them can read that table: agent-service enforces it inside a run
 * ({@code AgentBudgetGuard}), orchestrator-service refuses a scheduled fire with it, and
 * the agenda greys the fires it is going to refuse. A rule copied into each of those grows
 * three answers, which is how the workflow cap and the agent cap already ended up with
 * opposite defaults for a blank reset mode. So the decision is written once, as a pure
 * function of values any caller can hold, and every consumer calls it.
 *
 * <p><b>It mirrors {@code BudgetResolver} deliberately, including the parts that look
 * redundant.</b> The reset is LAZY: nothing resets an agent on a timer, the accumulator is
 * zeroed at the start of the next execution. A caller that only wants to DISPLAY or REFUSE
 * must therefore apply the pending reset itself, without writing, or a monthly agent would
 * read as blocked for the whole of the month after its cap was reached. {@link #resetDue}
 * is that read-only half of {@code BudgetResolver.resolveAndPersist}.
 *
 * <p><b>The "no reservation held" condition on a reset is not decoration.</b> The persisted
 * reset is a CAS that refuses to fire while a sub-agent holds a reservation
 * ({@code AgentRepository.resetConsumedIfUnreservedAndUnchanged}), so a rule that reset
 * regardless would answer "not blocked" for an agent the enforcement path is about to
 * block. Predicting the opposite of what the guard does is worse than not predicting.
 *
 * <p><b>The blocked test matches the guard's first iteration exactly.</b>
 * {@code AgentBudgetGuard} denies when
 * {@code consumed + reserved + runCost + projection >= budget}, and on iteration 1 both
 * {@code runCost} and {@code projection} are zero by design. So {@code consumed + reserved
 * >= budget} is precisely "the next run would be denied before it spends anything", which
 * is the only honest thing a pre-flight gate can promise. A run that is NOT blocked here
 * may still be stopped mid-way by the guard, and that asymmetry is intended: this rule
 * never lets a run start that the guard would refuse on arrival, and never claims a run
 * will finish.
 */
public final class AgentBudgetRule {

    private AgentBudgetRule() {
    }

    /** Never resets: a lifetime accumulator. The default for an agent, and for any unknown value. */
    public static final String MODE_CUMULATIVE = "cumulative";
    /** Resets once 7 whole days have elapsed since the last reset. */
    public static final String MODE_WEEKLY = "weekly";
    /** Resets when the calendar month (UTC) differs from the last reset's month. */
    public static final String MODE_MONTHLY = "monthly";

    /**
     * True when the agent's accumulator is due to be zeroed at the next execution.
     *
     * <p>A null or unknown mode is {@link #MODE_CUMULATIVE}, which never resets. That
     * default belongs to the agent, and it is the OPPOSITE of the workflow cap's (monthly):
     * the two are different subsystems with different owners, and a shared "sensible"
     * default would silently re-arm one of them. A null {@code lastReset} on a periodic mode
     * means the period has never rolled, so the first resolution resets.
     */
    public static boolean resetDue(String mode, Instant lastReset, Instant now) {
        if (now == null) {
            return false;
        }
        // EXACT match, case-sensitive, deliberately. This is what BudgetResolver has always
        // done, and budget_reset_mode is stored with no validation at all: AgentService and
        // AgentCrudModule both persist whatever arrives, so an LLM writing "Monthly" produces
        // a row that has never reset in production. Teaching this switch to accept it would
        // silently start zeroing those accumulators on a schedule nobody asked for. Widening
        // the accepted spellings is a data decision, and it belongs at the WRITE side.
        return switch (mode == null ? MODE_CUMULATIVE : mode) {
            case MODE_WEEKLY -> lastReset == null || ChronoUnit.DAYS.between(lastReset, now) >= 7;
            case MODE_MONTHLY -> lastReset == null
                    || YearMonth.from(LocalDate.ofInstant(now, ZoneOffset.UTC))
                            .isAfter(YearMonth.from(LocalDate.ofInstant(lastReset, ZoneOffset.UTC)));
            default -> false;
        };
    }

    /**
     * When a periodic accumulator next rolls over, or {@code null} when it never does.
     *
     * <p>Null has ONE meaning and the agenda depends on it: the cap does not reset, so the
     * whole projected future is refused rather than a window of it.
     *
     * <p>Weekly is anchored on {@code lastReset}, not on the calendar, because that is what
     * {@link #resetDue} measures. Anchoring it on "next Monday" would draw a date the
     * enforcement path does not honour.
     */
    public static Instant nextResetAt(String mode, Instant lastReset, Instant now) {
        if (now == null) {
            return null;
        }
        Instant next = switch (mode == null ? MODE_CUMULATIVE : mode) {
            case MODE_WEEKLY -> lastReset == null ? now : lastReset.plus(7, ChronoUnit.DAYS);
            case MODE_MONTHLY -> YearMonth.from(LocalDate.ofInstant(now, ZoneOffset.UTC))
                    .plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
            default -> null;
        };
        // A reset that is already DUE lifts as soon as it can land, not at the next period
        // boundary. That case is real and is not an edge: the persisted reset is a CAS that
        // refuses while a sub-agent holds a reservation, so an agent can be blocked NOW with
        // its rollover overdue, waiting only for that descendant to settle.
        //
        // Without this, monthly would name the 1st of next month and grey up to a month of
        // fires over a reservation that settles in seconds, and weekly would name an instant
        // in the PAST. "now" is the closest thing to the truth that exists here: the reset is
        // due, and it lands on the next attempt.
        //
        // WHAT THIS DOES NOT FIX, stated because the obvious reading is wrong. Every reader
        // compares this against a FUTURE moment - the agenda asks "is this occurrence before
        // it", the bell asks "is the next fire after it" - and "now" is before all of them,
        // so both draw the future as un-blocked. That is the right answer for a fire
        // tomorrow and the wrong one for a fire in the next few seconds, which the gate will
        // still refuse. Closing that would mean knowing when the descendant settles, which
        // is bounded only by its own executionTimeout and is not carried here. The residue
        // is one reservation long and clears itself; the alternative, null, claims the cap
        // never lifts and greys the whole future for the same seconds.
        if (resetDue(mode, lastReset, now)) {
            return now;
        }
        return next;
    }

    /**
     * The accumulator as the next execution would see it: zero when a reset is due AND can
     * actually land, otherwise the stored value.
     *
     * <p>Read-only. The real reset is persisted by {@code BudgetResolver} at execution
     * start; this only predicts it, so a display or a gate never has to write.
     */
    public static BigDecimal effectiveConsumed(BigDecimal consumed, BigDecimal reserved,
                                               String mode, Instant lastReset, Instant now) {
        BigDecimal held = orZero(reserved);
        if (held.signum() == 0 && resetDue(mode, lastReset, now)) {
            return BigDecimal.ZERO;
        }
        return orZero(consumed);
    }

    /**
     * True when this agent's own cap refuses the next run outright.
     *
     * <p>Fails OPEN on every unknown: no cap, a zero or negative cap, and a null instant all
     * answer {@code false}. A cap that cannot be read must never be the reason a customer's
     * agent stops, which is the posture every other gate in this codebase takes.
     *
     * @param budget    the agent's {@code creditBudget}; null or non-positive disables the cap
     * @param consumed  the stored {@code creditsConsumed} accumulator, BEFORE any pending reset
     * @param reserved  credits held by in-flight sub-agent executions
     * @param mode      the agent's {@code budgetResetMode}
     * @param lastReset the agent's {@code budgetLastReset}
     * @param now       reference instant
     */
    public static boolean blocked(BigDecimal budget, BigDecimal consumed, BigDecimal reserved,
                                  String mode, Instant lastReset, Instant now) {
        if (budget == null || budget.signum() <= 0 || now == null) {
            return false;
        }
        BigDecimal held = orZero(reserved);
        return effectiveConsumed(consumed, held, mode, lastReset, now).add(held).compareTo(budget) >= 0;
    }

    /**
     * When the block described by {@link #blocked} lifts, or {@code null} if it never does.
     *
     * <p>Answers {@code null} for an agent that is NOT blocked as well, so a caller can pass
     * the pair straight through without branching: "until" is only meaningful beside a true
     * "blocked", exactly as it is on the workflow side.
     */
    public static Instant blockedUntil(BigDecimal budget, BigDecimal consumed, BigDecimal reserved,
                                       String mode, Instant lastReset, Instant now) {
        if (!blocked(budget, consumed, reserved, mode, lastReset, now)) {
            return null;
        }
        return nextResetAt(mode, lastReset, now);
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
