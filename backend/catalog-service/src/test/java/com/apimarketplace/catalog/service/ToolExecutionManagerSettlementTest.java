package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.service.billing.CatalogToolBillingService;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one question a settled reservation is asked afterwards: may this amount be REPORTED as
 * charged?
 *
 * <p>What hangs on the answer is a number printed under a generated asset. Every outcome that is
 * not a whole commit means the figure in hand is not the figure taken - less was charged, nothing
 * was charged, or no ledger was reached at all - and each of them arrives as an ordinary,
 * unexceptional string from a service that is working correctly. The wiring suite proves the two
 * happy paths and the partial one end to end; this pins the cases that are cheap to get wrong
 * because they never fail loudly.
 */
@DisplayName("what a settled reservation may say it cost")
class ToolExecutionManagerSettlementTest {

    private static final BigDecimal RESERVED = new BigDecimal("78");

    @Nested
    @DisplayName("a commit that took the whole amount")
    class WholeCommit {

        @Test
        @DisplayName("reports the amount on a FLOORED commit too, because that one charges the "
                + "reserved figure exactly")
        void reportsTheAmountOnAFlooredCommit() {
            // The loud outcome that is nevertheless a full charge: the balance had already gone
            // negative through a concurrent debit and the account is marked delinquent, but the
            // ledger row records `reserved`. Refusing it would drop a price the reader really paid,
            // and the reason to refuse PARTIAL - the amount is smaller and unknown - does not apply.
            assertThat(ToolExecutionManager.Settlement.of("COMMITTED_FLOORED", RESERVED).billedCredits())
                    .isEqualByComparingTo(RESERVED);
        }

        @Test
        @DisplayName("reports the amount, and is settled")
        void reportsTheAmount() {
            ToolExecutionManager.Settlement settlement =
                    ToolExecutionManager.Settlement.of("COMMITTED", RESERVED);

            assertThat(settlement.settled()).isTrue();
            assertThat(settlement.billedCredits()).isEqualByComparingTo(RESERVED);
        }

    }

    @Nested
    @DisplayName("everything else")
    class NothingToReport {

        @Test
        @DisplayName("says nothing when the charge could not be taken whole")
        void saysNothingOnAPartialCharge() {
            // The balance could not cover the whole reservation, so the ledger charged less than
            // was reserved and does not say how much. The reserved figure would overstate it.
            assertThat(ToolExecutionManager.Settlement.of("COMMITTED_PARTIAL", RESERVED).billedCredits())
                    .isNull();
        }

        @Test
        @DisplayName("says nothing on an idempotent retry, because it cannot tell WHICH commit it "
                + "is repeating")
        void saysNothingOnARepeatedCommit() {
            // The trap this closes: a partial or floored commit leaves the row as an ordinary
            // committed one, so a retry over a charge that took LESS than was reserved comes back
            // as ALREADY_COMMITTED. Reading the reserved figure then would re-open the very
            // overstatement the partial outcome is refused for, through the retry door.
            assertThat(ToolExecutionManager.Settlement.of("ALREADY_COMMITTED", RESERVED).billedCredits())
                    .isNull();
            // Still settled: the reservation is closed, and releasing it again would refund a
            // charge that stands.
            assertThat(ToolExecutionManager.Settlement.of("ALREADY_COMMITTED", RESERVED).settled()).isTrue();
        }

        @Test
        @DisplayName("says nothing when the reservation had already been swept away")
        void saysNothingOnAnExpiredReservation() {
            assertThat(ToolExecutionManager.Settlement.of("RESERVATION_EXPIRED", RESERVED).billedCredits())
                    .isNull();
        }

        @Test
        @DisplayName("says nothing when this deployment does not meter, which is NOT a charge of zero")
        void saysNothingWhenBillingIsDisabled() {
            // A self-hosted install: no ledger was reached. Both the credit client and the auth
            // service answer with this word rather than a success one precisely so that the
            // difference survives the wire - it used to be spelled COMMITTED, which was harmless
            // only while nobody read it.
            assertThat(ToolExecutionManager.Settlement
                    .of(CreditConsumptionClient.BILLING_DISABLED, RESERVED).billedCredits())
                    .isNull();
        }

        @Test
        @DisplayName("says nothing for an amount of zero, because zero is not a price")
        void saysNothingForZero() {
            // An endpoint published at nothing is not a purchase to report; drawn on a card, "0"
            // would be a claim that this asset was free.
            assertThat(ToolExecutionManager.Settlement.of("COMMITTED", BigDecimal.ZERO).billedCredits())
                    .isNull();
            assertThat(ToolExecutionManager.Settlement.of("COMMITTED", null).billedCredits())
                    .isNull();
        }

        @Test
        @DisplayName("says nothing for a call that reserved nothing, which is its own answer")
        void saysNothingWhenNothingWasReserved() {
            // The word a commit gets when there was no reservation to commit. It is deliberately
            // neither a success one nor the one for "this deployment does not meter": every state
            // that did not debit a ledger row has to be distinguishable from the one that did.
            assertThat(ToolExecutionManager.Settlement
                    .of(CatalogToolBillingService.NOTHING_RESERVED, RESERVED).billedCredits())
                    .isNull();
        }

        @Test
        @DisplayName("says nothing for an outcome this build has never heard of")
        void saysNothingForAnUnknownOutcome() {
            // A new outcome added on the auth side reaches an older catalog as a word it cannot
            // read. Silence is the only safe reading of it: the alternative is to report a price on
            // the strength of not recognising the answer.
            assertThat(ToolExecutionManager.Settlement.of("SOMETHING_NEW", RESERVED).billedCredits())
                    .isNull();
            assertThat(ToolExecutionManager.Settlement.of(null, RESERVED).billedCredits()).isNull();
        }

        @Test
        @DisplayName("still counts as settled, so the safety net does not refund on top of it")
        void staysSettled() {
            // The finally block releases anything not settled. A partial commit HAS closed the
            // reservation; releasing it again would be a refund of a charge that stands.
            assertThat(ToolExecutionManager.Settlement.of("COMMITTED_PARTIAL", RESERVED).settled()).isTrue();
            assertThat(ToolExecutionManager.Settlement.UNBILLED.settled()).isTrue();
            assertThat(ToolExecutionManager.Settlement.NOT_SETTLED.settled()).isFalse();
        }
    }
}
