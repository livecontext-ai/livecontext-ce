package com.apimarketplace.agent.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The verdict the rest of the product reads off an agent: is its own cap refusing runs?
 *
 * <p>Lives on the entity because that is the only place every consumer can reach it from.
 * The public agent endpoints serialize this entity directly, the internal DTO copies it, and
 * the MCP tool's budget block reads it, so one getter keeps the agents list, the agenda, the
 * bell, the schedule gate and an LLM asking {@code agent(action='get')} on one answer.
 *
 * <p>The arithmetic itself belongs to {@code AgentBudgetRule} and is tested there. What is
 * pinned here is the WIRING - that the entity passes its own reserved counter and its own
 * reset state, the two inputs a caller outside this service cannot supply.
 */
@DisplayName("AgentEntity - the budget verdict read by every other surface")
class AgentEntityBudgetVerdictTest {

    private static AgentEntity agent(String cap, String consumed, String reserved,
                                     String mode, Instant lastReset) {
        AgentEntity entity = new AgentEntity();
        entity.setCreditBudget(cap == null ? null : new BigDecimal(cap));
        entity.setCreditsConsumed(consumed == null ? null : new BigDecimal(consumed));
        entity.setCreditsReserved(reserved == null ? null : new BigDecimal(reserved));
        entity.setBudgetResetMode(mode);
        entity.setBudgetLastReset(lastReset);
        return entity;
    }

    @Test
    @DisplayName("the reported shape: capped at 1, spent 3, never resets")
    void reportsTheCappedOutAgentAsBlocked() {
        assertThat(agent("1", "3", "0", "cumulative", null).isBudgetBlocked()).isTrue();
    }

    @Test
    @DisplayName("is NOT the same question as creditsFree == 0")
    void differsFromTheFreeFigure() {
        // creditsFree is arithmetic on the STORED counter; the verdict applies the reset the
        // next run would perform. A monthly agent that hit its cap last month reads zero
        // free and is not blocked, and confusing the two was worth a whole month of
        // wrongly-greyed calendar per agent.
        AgentEntity rolledOver = agent("10", "10", "0", "monthly",
                Instant.now().minus(45, ChronoUnit.DAYS));

        assertThat(rolledOver.getCreditsFree()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(rolledOver.isBudgetBlocked()).isFalse();
        assertThat(rolledOver.getBudgetBlockedUntil()).isNull();
    }

    @Test
    @DisplayName("counts the credits a running sub-agent is holding")
    void countsReservedCredits() {
        // credits_reserved is deliberately absent from AgentDto, so nothing outside this
        // service could apply it. If the entity stopped passing it, a parent would start
        // runs against credits its own child is committed to spend, and no test elsewhere
        // could see it.
        assertThat(agent("10", "6", "4", "cumulative", null).isBudgetBlocked()).isTrue();
        assertThat(agent("10", "6", "3", "cumulative", null).isBudgetBlocked()).isFalse();
    }

    @Test
    @DisplayName("an uncapped agent is never blocked and names no date")
    void anUncappedAgentIsNeverBlocked() {
        AgentEntity uncapped = agent(null, "9999", "0", "cumulative", null);

        assertThat(uncapped.isBudgetBlocked()).isFalse();
        assertThat(uncapped.getBudgetBlockedUntil()).isNull();
    }

    @Test
    @DisplayName("the committed figure is what every refusal prints, and it counts the reservation")
    void committedCountsTheReservation() {
        // This is the producer of the number in "10 of 10 credits". The schedule, the webhook
        // and the workflow node all print it, and only this side can compute it: credits
        // reserved never leaves agent-service. Printing the spend alone read "6 of 10" beside
        // a refusal, which looks like a bug to the person who set the cap.
        assertThat(agent("10", "6", "4", "cumulative", null).getBudgetCommitted())
                .isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("the committed figure is POST-reset, so a rolled-over agent reads what its next run will")
    void committedAppliesThePendingReset() {
        AgentEntity rolledOver = agent("10", "10", "0", "monthly",
                Instant.now().minus(45, ChronoUnit.DAYS));

        assertThat(rolledOver.getBudgetCommitted()).isEqualByComparingTo("0");
        assertThat(rolledOver.isBudgetBlocked()).isFalse();
    }

    @Test
    @DisplayName("an uncapped agent has no committed figure, because there is nothing to commit against")
    void anUncappedAgentHasNoCommittedFigure() {
        // Null rather than the raw spend: a caller that printed it would state a ratio against
        // a cap that does not exist.
        assertThat(agent(null, "9999", "0", "cumulative", null).getBudgetCommitted()).isNull();
    }

    @Test
    @DisplayName("the three derived fields agree when they are read at ONE instant")
    void theDerivedFieldsAgreeAtOneInstant() {
        // Read at three clocks they can contradict each other: a monthly cap rolling over
        // between two getters ships blocked=true beside a null "until", which every consumer
        // reads as "this never lifts". The At(Instant) overloads exist for that, and the two
        // mappers use them.
        Instant at = Instant.parse("2026-09-18T12:00:00Z");
        AgentEntity blockedNow = agent("10", "12", "0", "monthly", Instant.parse("2026-09-02T00:00:00Z"));

        assertThat(blockedNow.isBudgetBlockedAt(at)).isTrue();
        assertThat(blockedNow.getBudgetBlockedUntilAt(at)).isNotNull();
        assertThat(blockedNow.getBudgetCommittedAt(at)).isEqualByComparingTo("12");
    }

    @Test
    @DisplayName("a periodic cap names the instant it lifts; a cumulative one names none")
    void namesTheInstantTheBlockLifts() {
        // Null beside a true verdict is load-bearing: the agenda greys the whole future on
        // it, instead of a window.
        AgentEntity monthly = agent("1", "3", "0", "monthly", Instant.now());
        AgentEntity forever = agent("1", "3", "0", "cumulative", null);

        assertThat(monthly.getBudgetBlockedUntil()).isNotNull();
        assertThat(forever.isBudgetBlocked()).isTrue();
        assertThat(forever.getBudgetBlockedUntil()).isNull();
    }
}
