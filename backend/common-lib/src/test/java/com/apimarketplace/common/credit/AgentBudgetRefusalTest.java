package com.apimarketplace.common.credit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The agent-budget refusal sentence, which is both user-facing copy and a matcher key.
 */
@DisplayName("AgentBudgetRefusal")
class AgentBudgetRefusalTest {

    @Test
    @DisplayName("shares no wording with the empty-wallet refusal, which needs opposite advice")
    void doesNotCollideWithTheWalletRefusal() {
        // A workspace with no credits is told to top up. An agent that reached the cap its
        // OWNER set is told to raise that cap, on an account that is already paid. The two
        // sentences must stay distinguishable, because the log classifier the wallet one is
        // registered with is a substring test: if this sentence ever contained that one, an
        // agent cap would start reading as an empty wallet everywhere that classifier runs.
        assertThat(AgentBudgetRefusal.MESSAGE).doesNotContain(ChatCreditRefusal.MESSAGE);
        assertThat(ChatCreditRefusal.isChatCreditRefusal(AgentBudgetRefusal.MESSAGE)).isFalse();
    }

    @Test
    @DisplayName("states the figures without trailing zeros from the NUMERIC column")
    void statesReadableFigures() {
        // credit_budget is NUMERIC(19,4), so a cap of 1 arrives as 1.0000 and a raw
        // toString would print "spent 3.0000 of 1.0000 credits" to a human.
        String message = AgentBudgetRefusal.message(
                new BigDecimal("1.0000"), new BigDecimal("3.0000"), null);
        assertThat(message).contains("3 of 1 credits");
    }

    @Test
    @DisplayName("offers waiting when the cap lifts, and an action when it never does")
    void tellsTheReaderWhatHappensNext() {
        Instant lifts = Instant.parse("2026-10-01T09:30:00Z");
        String periodic = AgentBudgetRefusal.message(new BigDecimal("1"), new BigDecimal("3"), lifts);
        // The DAY, because a person reads this in a toast. A raw Instant would put
        // "2026-10-01T09:30:00Z" in front of someone who only wants to know when they can
        // use the agent again.
        assertThat(periodic).contains("2026-10-01");
        assertThat(periodic).doesNotContain("T09:30");
        assertThat(periodic).doesNotContain("Raise its credit budget");

        String forever = AgentBudgetRefusal.message(new BigDecimal("1"), new BigDecimal("3"), null);
        assertThat(forever).contains("Raise its credit budget");
    }

    @Test
    @DisplayName("stays a complete sentence when the figures are unknown")
    void survivesMissingFigures() {
        assertThat(AgentBudgetRefusal.message(null, null, null))
                .startsWith(AgentBudgetRefusal.MESSAGE + ".");
    }
}
