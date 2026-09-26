package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.repository.CreditLedgerRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.web.CreditController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("CreditAlertScheduler: the EXHAUSTED level")
class CreditAlertSchedulerLevelTest {

    private static final BigDecimal RATIO = new BigDecimal("0.2");
    /** 5,000 credits: LOW is under 1,000, so every balance used here is already LOW territory. */
    private static final long GRANT = 5000L;
    private static final long USER_ID = 42L;

    private static CreditAlertScheduler.Candidate candidate(BigDecimal balance, boolean lowSent, boolean exhaustedSent) {
        return new CreditAlertScheduler.Candidate(1L, USER_ID, balance, "20260914235309:0",
                "org", GRANT, lowSent, exhaustedSent);
    }

    private static CreditAlertScheduler.Candidate candidate(String balance, boolean lowSent, boolean exhaustedSent) {
        return candidate(new BigDecimal(balance), lowSent, exhaustedSent);
    }

    @Nested
    @DisplayName("levelToAlert")
    class LevelToAlert {

        @Test
        @DisplayName("Regression (EXHAUSTED never sent): 0.44 left after LOW is exhausted, although it is above zero")
        void fractionBelowOneCreditIsExhausted() {
            assertThat(candidate("0.4398", true, false).levelToAlert(RATIO)).isEqualTo("EXHAUSTED");
        }

        @Test
        @DisplayName("Regression (EXHAUSTED never sent): 0.44 with LOW never sent says EXHAUSTED, not LOW")
        void fractionBelowOneCreditBeforeLowIsExhausted() {
            assertThat(candidate("0.4398", false, false).levelToAlert(RATIO)).isEqualTo("EXHAUSTED");
        }

        @Test
        @DisplayName("Exactly one credit is not exhausted: LOW when LOW was not sent yet (grant 5,000), nothing after it")
        void exactlyOneCreditIsLowNotExhausted() {
            assertThat(candidate("1", false, false).levelToAlert(RATIO)).isEqualTo("LOW");
            assertThat(candidate("1", true, false).levelToAlert(RATIO)).isNull();
        }

        @Test
        @DisplayName("Zero and a negative balance are still exhausted")
        void zeroAndNegativeAreExhausted() {
            assertThat(candidate("0", false, false).levelToAlert(RATIO)).isEqualTo("EXHAUSTED");
            assertThat(candidate("-100.83", false, false).levelToAlert(RATIO)).isEqualTo("EXHAUSTED");
        }

        @Test
        @DisplayName("EXHAUSTED wins over LOW: on a grant of 3 (LOW threshold 0.6), 0.5 sends EXHAUSTED, never LOW")
        void exhaustedWinsOverLowOnTinyGrant() {
            CreditAlertScheduler.Candidate tinyGrant = new CreditAlertScheduler.Candidate(1L, USER_ID,
                    new BigDecimal("0.5"), "20260914235309:0", "org", 3L, false, false);
            assertThat(tinyGrant.levelToAlert(RATIO)).isEqualTo("EXHAUSTED");
        }

        @Test
        @DisplayName("EXHAUSTED already sent this cycle: nothing more, however long the balance stays under one credit")
        void exhaustedOncePerCycle() {
            assertThat(candidate("0.4398", true, true).levelToAlert(RATIO)).isNull();
        }
    }

    /**
     * The alert is only right if it fires exactly where the gates start refusing. These run the
     * REAL {@link CreditService} gates (and the {@code /check} endpoint the orchestrator's node
     * gate calls) on the same balance as the scan, so a gate comparing against a different value,
     * or the constant changing without the gates, fails here instead of silently reopening the gap.
     */
    @Nested
    @DisplayName("Alignment with the credit gates")
    class AlignmentWithGates {

        private SubscriptionRepository subscriptions;
        private CreditService creditService;

        @BeforeEach
        void setUp() {
            subscriptions = mock(SubscriptionRepository.class);
            creditService = new CreditService(subscriptions, mock(CreditLedgerRepository.class),
                    mock(ModelPricingService.class), false);
        }

        /** The source type a workflow node's {@code /check} carries. */
        private static final String WORKFLOW_NODE = "WORKFLOW_NODE";

        /**
         * Serves {@code sub} to the gates and returns the candidate the scan would build from it:
         * its balance is the scan's own SQL total ({@code remaining_credits + payg_remaining_credits}),
         * computed from the SAME row the gates read, never typed in separately.
         */
        private CreditAlertScheduler.Candidate account(String planCode, BigDecimal monthly, BigDecimal payg) {
            Subscription sub = new Subscription();
            sub.setId(1L);
            sub.setPlan(new Plan(planCode, planCode, ""));
            sub.setRemainingCredits(monthly);
            sub.setPaygRemainingCredits(payg);
            when(subscriptions.findActiveByUserId(USER_ID)).thenReturn(Optional.of(sub));
            return candidate(sub.getRemainingCredits().add(sub.getPaygRemainingCredits()), true, false);
        }

        /** Split across both buckets (0.5 in PAYG), so a gate reading one bucket alone fails. */
        private CreditAlertScheduler.Candidate account(BigDecimal total) {
            BigDecimal payg = new BigDecimal("0.5");
            return account("TEAM", total.subtract(payg), payg);
        }

        /**
         * Every cost-blind gate, in every overload with no projected cost: {@code /check} and
         * {@code hasSufficientCredits} are what a workflow node hits; the {@code canAfford}
         * overloads are the chat pre-flight's cost-blind form, held to the same floor.
         */
        private void assertWorkflowGates(boolean admitted) {
            // The endpoint the orchestrator's NodeCreditGate calls, with no source type.
            CreditController controller = new CreditController(creditService, mock(ModelPricingService.class),
                    mock(LlmCostEstimateService.class));
            assertThat(controller.checkCredits(USER_ID, null, null, null).getStatusCode().value())
                    .isEqualTo(admitted ? 200 : 402);
            assertThat(creditService.hasSufficientCredits(USER_ID)).isEqualTo(admitted);
            assertThat(creditService.hasSufficientCredits(USER_ID, null)).isEqualTo(admitted);
            assertThat(creditService.hasSufficientCredits(USER_ID, WORKFLOW_NODE, null, null)).isEqualTo(admitted);
            assertThat(creditService.canAfford(USER_ID, null)).isEqualTo(admitted);
            assertThat(creditService.canAfford(USER_ID, BigDecimal.ZERO)).isEqualTo(admitted);
            assertThat(creditService.canAfford(USER_ID, null, null)).isEqualTo(admitted);
            assertThat(creditService.canAfford(USER_ID, null, WORKFLOW_NODE, null, null)).isEqualTo(admitted);
        }

        @Test
        @DisplayName("Just under the floor: every workflow gate refuses, and the scan says EXHAUSTED")
        void justUnderFloorBlockedAndExhausted() {
            var scanned = account(CreditService.MIN_USABLE_BALANCE.subtract(new BigDecimal("0.0001")));

            assertWorkflowGates(false);
            assertThat(scanned.levelToAlert(RATIO)).isEqualTo("EXHAUSTED");
        }

        @Test
        @DisplayName("At the floor: every workflow gate admits, and the scan does not say EXHAUSTED")
        void atFloorAdmittedAndNotExhausted() {
            var scanned = account(CreditService.MIN_USABLE_BALANCE);

            assertWorkflowGates(true);
            assertThat(scanned.levelToAlert(RATIO)).isNull();
        }

        @Test
        @DisplayName("On FREE the workflow gate counts BOTH buckets like the scan: 0.9 + 0.3 is admitted and not EXHAUSTED")
        void freePlanWorkflowGateCountsBothBucketsLikeScan() {
            // Each bucket alone is under the floor, only their sum is above it: a gate reading
            // PAYG alone (or the monthly bucket alone) would refuse here while the scan stays quiet.
            var scanned = account("FREE", new BigDecimal("0.9"), new BigDecimal("0.3"));

            assertWorkflowGates(true);
            assertThat(scanned.levelToAlert(RATIO)).isNull();
        }

        @Test
        @DisplayName("On FREE, both buckets together under the floor (0.3 + 0.1398): the workflow gate refuses and the scan says EXHAUSTED")
        void freePlanBothBucketsUnderFloorBlockedAndExhausted() {
            var scanned = account("FREE", new BigDecimal("0.3"), new BigDecimal("0.1398"));

            assertWorkflowGates(false);
            assertThat(scanned.levelToAlert(RATIO)).isEqualTo("EXHAUSTED");
        }

        @Test
        @DisplayName("Known divergence, pinned: on FREE with no PAYG, chat is refused while the scan sees 5 credits and stays quiet")
        void freePlanChatRefusedBeforeExhausted() {
            var scanned = account("FREE", new BigDecimal("5"), BigDecimal.ZERO);

            assertThat(creditService.hasSufficientCredits(USER_ID, "CHAT_CONVERSATION", "openai", "gpt-4o")).isFalse();
            assertThat(creditService.hasSufficientCredits(USER_ID, WORKFLOW_NODE, null, null)).isTrue();
            assertThat(scanned.levelToAlert(RATIO)).isNull();
        }

        @Test
        @DisplayName("Known divergence, pinned: at 0.44 a chat turn estimated under one credit is still admitted")
        void cheapChatTurnStillAdmittedUnderFloor() {
            var scanned = account(new BigDecimal("0.4398"));

            assertThat(creditService.canAfford(USER_ID, new BigDecimal("0.3"))).isTrue();
            assertThat(scanned.levelToAlert(RATIO)).isEqualTo("EXHAUSTED");
        }
    }
}
