package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.repository.CreditLedgerRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.service.CreditService.CreditConsumeResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The Free plan's single monthly pool (V512).
 *
 * <p>Before V512 a Free account held 1000 monthly credits that paid for workflow runs
 * only, plus a separate 100-credit AI allowance for chat and agent turns. Users read the
 * two balances as a bug, so they were merged: the 1000 monthly credits now pay for
 * workflow runs AND for chat/agent turns, the latter only on a model a cloud admin opened
 * to the free tier. Every "draws the monthly pool" case below failed before the merge:
 * the turn fell through to an empty PAYG bucket and was refused.
 *
 * <p>The other half is pinned just as hard: a model outside the free tier, a flat-cost
 * add-on, or a CE relay must still never touch the pool, otherwise the free plan is an
 * unmetered frontier-model plan.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CreditService - the Free plan's single monthly pool (V512)")
class CreditServiceFreePlanSinglePoolTest {

    private static final Long USER_ID = 42L;
    private static final String PROVIDER = "anthropic";
    private static final String FREE_TIER_MODEL = "claude-haiku-4-5";
    private static final String OTHER_MODEL = "claude-opus-4-6";

    @Mock
    private SubscriptionRepository subscriptionRepository;
    @Mock
    private CreditLedgerRepository ledgerRepository;
    @Mock
    private ModelPricingService pricingService;

    private CreditService cloud;

    @BeforeEach
    void setUp() {
        cloud = new CreditService(subscriptionRepository, ledgerRepository, pricingService, false);
        when(pricingService.isFreeTierModel(anyString(), anyString())).thenReturn(false);
        when(pricingService.isFreeTierModel(PROVIDER, FREE_TIER_MODEL)).thenReturn(true);
        when(pricingService.calculateCost(anyString(), anyString(), any(LlmTokenBreakdown.class)))
                .thenReturn(new BigDecimal("2.00"));
    }

    /** A post-V512 subscription: no plan grants an AI allowance, so the pot is empty. */
    private Subscription sub(String planCode, String monthly, String payg) {
        Subscription s = new Subscription();
        s.setId(1L);
        s.setPlan(new Plan(planCode, planCode, ""));
        s.setRemainingCredits(new BigDecimal(monthly));
        s.setPaygRemainingCredits(new BigDecimal(payg));
        s.setAiRemainingCredits(BigDecimal.ZERO);
        s.setDelinquent(false);
        return s;
    }

    private Subscription active(Subscription s) {
        when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(s));
        when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(s));
        return s;
    }

    private CreditConsumeResult llmTurn(String model, String sourceType) {
        return cloud.consumeForAgent(USER_ID, "exec-" + sourceType + "-" + model, PROVIDER, model,
                LlmTokenBreakdown.of(1000, 500), sourceType);
    }

    @Nested
    @DisplayName("what the monthly pool now pays for")
    class WhatThePoolPaysFor {

        @Test
        @DisplayName("a Free agent turn on a free-tier model is paid from the 1000 monthly credits")
        void agentTurnOnFreeTierModelDrawsThePool() {
            Subscription s = active(sub("FREE", "1000.00", "0.00"));

            CreditConsumeResult result = llmTurn(FREE_TIER_MODEL, "AGENT_EXECUTION");

            assertThat(result.success())
                    .as("pre-V512 this was refused: the monthly credits were workflow-only and PAYG is empty")
                    .isTrue();
            assertThat(s.getRemainingCredits()).isEqualByComparingTo("998.00");
            assertThat(s.getPaygRemainingCredits()).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("a Free chat turn on a free-tier model is paid from the same pool")
        void chatTurnOnFreeTierModelDrawsThePool() {
            Subscription s = active(sub("FREE", "1000.00", "0.00"));

            CreditConsumeResult result = cloud.consumeForChat(USER_ID, "conv-1", PROVIDER,
                    FREE_TIER_MODEL, LlmTokenBreakdown.of(1000, 500));

            assertThat(result.success()).isTrue();
            assertThat(s.getRemainingCredits()).isEqualByComparingTo("998.00");
        }

        /**
         * The set is seven raw strings; a typo in one is silent (the turn falls through
         * to PAYG and is refused), so each is exercised rather than trusted.
         */
        @ParameterizedTest(name = "{0} draws the monthly pool")
        @ValueSource(strings = {
                "AGENT_EXECUTION", "CHAT_CONVERSATION", "CLASSIFY_EXECUTION",
                "GUARDRAIL_EXECUTION", "COMPACTION_SUMMARY", "BROWSER_AGENT_EXECUTION",
                "CLI_SESSION"})
        @DisplayName("every free-tier LLM source type is funded by the pool")
        void everyFreeTierLlmSourceTypeDrawsThePool(String sourceType) {
            Subscription s = active(sub("FREE", "1000.00", "0.00"));

            assertThat(llmTurn(FREE_TIER_MODEL, sourceType).success()).isTrue();
            assertThat(s.getRemainingCredits()).isEqualByComparingTo("998.00");
        }

        @Test
        @DisplayName("workflow nodes keep drawing the same pool, so both uses share one balance")
        void workflowNodesAndChatShareOneBalance() {
            Subscription s = active(sub("FREE", "1000.00", "0.00"));

            cloud.consumeForWorkflowNode(USER_ID, "run-1:step-1");
            llmTurn(FREE_TIER_MODEL, "AGENT_EXECUTION");

            assertThat(s.getRemainingCredits())
                    .as("1 credit for the node + 2 for the turn, from the same 1000")
                    .isEqualByComparingTo("997.00");
        }

        @Test
        @DisplayName("once the pool is short, a free-tier turn reaches the top-up for the rest")
        void poolShortfallFallsThroughToPayg() {
            Subscription s = active(sub("FREE", "0.50", "10.00"));

            assertThat(llmTurn(FREE_TIER_MODEL, "AGENT_EXECUTION").success()).isTrue();
            assertThat(s.getRemainingCredits()).isEqualByComparingTo("0.00");
            assertThat(s.getPaygRemainingCredits()).isEqualByComparingTo("8.50");
        }
    }

    @Nested
    @DisplayName("what the monthly pool still refuses")
    class WhatThePoolRefuses {

        @Test
        @DisplayName("a model NOT opened to the free tier cannot touch the pool")
        void otherModelIsRefusedWithoutTopUp() {
            Subscription s = active(sub("FREE", "1000.00", "0.00"));

            CreditConsumeResult result = llmTurn(OTHER_MODEL, "AGENT_EXECUTION");

            assertThat(result.success())
                    .as("only the models an admin opened are free; anything else needs a top-up")
                    .isFalse();
            assertThat(s.getRemainingCredits()).isEqualByComparingTo("1000.00");
        }

        @Test
        @DisplayName("a top-up still pays for any model, leaving the monthly pool untouched")
        void otherModelIsPaidFromTopUpOnly() {
            Subscription s = active(sub("FREE", "1000.00", "50.00"));

            assertThat(llmTurn(OTHER_MODEL, "AGENT_EXECUTION").success()).isTrue();
            assertThat(s.getPaygRemainingCredits()).isEqualByComparingTo("48.00");
            assertThat(s.getRemainingCredits()).isEqualByComparingTo("1000.00");
        }

        @Test
        @DisplayName("an unknown LLM source type is refused even on a free-tier model")
        void undeclaredSourceTypeIsRefused() {
            Subscription s = active(sub("FREE", "1000.00", "0.00"));

            assertThat(llmTurn(FREE_TIER_MODEL, "SOME_FUTURE_SOURCE").success()).isFalse();
            assertThat(s.getRemainingCredits()).isEqualByComparingTo("1000.00");
        }

        @Test
        @DisplayName("a CE relay is not a free-tier turn, even on a free-tier model")
        void ceRelayCannotDrawThePool() {
            // Tokens forwarded for a self-hosted install: letting them draw the pool would
            // hand every linked install a monthly grant of platform inference.
            // The relay settles post-flight (allowNegative), so the movement is recorded
            // either way: what matters is WHICH bucket carries it.
            Subscription s = active(sub("FREE", "1000.00", "0.00"));

            cloud.consumeForCeRelay(USER_ID, "relay-1", PROVIDER,
                    FREE_TIER_MODEL, LlmTokenBreakdown.of(1000, 500));

            assertThat(s.getRemainingCredits())
                    .as("the monthly pool is not the relay's to spend")
                    .isEqualByComparingTo("1000.00");
            assertThat(s.getPaygRemainingCredits()).isEqualByComparingTo("-2.00");
        }

        @Test
        @DisplayName("a flat-cost add-on (web search) still needs a top-up")
        void webSearchStillNeedsTopUp() {
            Subscription s = active(sub("FREE", "1000.00", "0.00"));

            assertThat(cloud.consumeForWebSearch(USER_ID, "search-1").success()).isFalse();
            assertThat(s.getRemainingCredits()).isEqualByComparingTo("1000.00");
        }

        @Test
        @DisplayName("a chat overshoot on a free-tier model is booked on the pool and gates the account")
        void chatOvershootIsBookedOnThePool() {
            // Post-flight (allowNegative): the LLM already streamed. The tail is an
            // ordinary debt on the monthly pool, which the next monthly reset clears, and
            // the delinquency gate stops it growing until then. PAYG stays untouched, so
            // no unclearable PAYG debt is created.
            Subscription s = active(sub("FREE", "0.50", "0.00"));

            cloud.consumeForChat(USER_ID, "conv-overshoot", PROVIDER, FREE_TIER_MODEL,
                    LlmTokenBreakdown.of(1000, 500));

            assertThat(s.getRemainingCredits()).isEqualByComparingTo("-1.50");
            assertThat(s.getPaygRemainingCredits()).isEqualByComparingTo("0.00");
            assertThat(s.getDelinquent()).isTrue();
        }
    }

    @Nested
    @DisplayName("drain order and recovery of a free-tier turn")
    class DrainOrderAndRecovery {

        @Test
        @DisplayName("monthly bucket at zero, top-up positive: the top-up pays the whole turn")
        void spentMonthDrawsTopUp() {
            Subscription s = active(sub("FREE", "0.00", "10.00"));

            assertThat(llmTurn(FREE_TIER_MODEL, "AGENT_EXECUTION").success()).isTrue();
            assertThat(s.getRemainingCredits()).isEqualByComparingTo("0.00");
            assertThat(s.getPaygRemainingCredits()).isEqualByComparingTo("8.00");
        }

        @Test
        @DisplayName("both buckets positive, chat overshoot: monthly first, then the top-up, and the rest is monthly debt")
        void overshootWithBothBucketsDrainsInOrder() {
            // Cost 2.00 against 0.50 monthly + 1.00 top-up. The top-up is the user's cash
            // and is spent before any debt; the uncovered 0.50 lands on the monthly bucket,
            // which the next monthly reset clears, never on the top-up.
            Subscription s = active(sub("FREE", "0.50", "1.00"));

            cloud.consumeForChat(USER_ID, "conv-both", PROVIDER, FREE_TIER_MODEL,
                    LlmTokenBreakdown.of(1000, 500));

            assertThat(s.getRemainingCredits()).isEqualByComparingTo("-0.50");
            assertThat(s.getPaygRemainingCredits()).isEqualByComparingTo("0.00");
            assertThat(s.getDelinquent()).isTrue();
        }

        @Test
        @DisplayName("the monthly reset clears the gate a free-tier overshoot latched")
        void monthlyResetClearsTheOvershootGate() {
            Subscription s = active(sub("FREE", "0.50", "0.00"));
            cloud.consumeForChat(USER_ID, "conv-latch", PROVIDER, FREE_TIER_MODEL,
                    LlmTokenBreakdown.of(1000, 500));
            assertThat(s.getDelinquent()).isTrue();

            // A stand-in for the renewal's credit step: the real renewal
            // (CreditAttributionService) resets the monthly bucket first and then grants
            // the plan credits through grantCredits, which is where the delinquency clear
            // lives. So the 998.50 below is this grant's arithmetic, not the renewal's
            // (which would land on 1000); what is under test is the clear.
            cloud.grantCredits(USER_ID, new BigDecimal("1000"), "SUBSCRIPTION_RENEWAL",
                    "renewal-1", "monthly reset");

            assertThat(s.getDelinquent())
                    .as("the debt sits on the monthly bucket, so the renewal repays it")
                    .isFalse();
            assertThat(s.getRemainingCredits()).isEqualByComparingTo("998.50");
        }

        @Test
        @DisplayName("a top-up clears it too")
        void topUpClearsTheOvershootGate() {
            Subscription s = active(sub("FREE", "0.50", "0.00"));
            cloud.consumeForChat(USER_ID, "conv-latch-2", PROVIDER, FREE_TIER_MODEL,
                    LlmTokenBreakdown.of(1000, 500));

            cloud.grantCredits(USER_ID, new BigDecimal("50"), "PAYG_TOPUP", "topup-1", "top-up");

            assertThat(s.getDelinquent()).isFalse();
            assertThat(s.getPaygRemainingCredits()).isEqualByComparingTo("50.00");
        }

        @Test
        @DisplayName("a non-free-tier overshoot still leaves PAYG debt that only a top-up clears, as before")
        void nonFreeTierOvershootKeepsThePaygRule() {
            Subscription s = active(sub("FREE", "1000.00", "0.50"));
            cloud.consumeForChat(USER_ID, "conv-closed", PROVIDER, OTHER_MODEL,
                    LlmTokenBreakdown.of(1000, 500));
            assertThat(s.getPaygRemainingCredits()).isEqualByComparingTo("-1.50");
            assertThat(s.getDelinquent()).isTrue();

            cloud.grantCredits(USER_ID, new BigDecimal("1000"), "SUBSCRIPTION_RENEWAL",
                    "renewal-2", "monthly reset");

            assertThat(s.getDelinquent())
                    .as("the monthly credits cannot repay a debt they were never allowed to fund")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("platform markup reservations keep their PAYG-only funding")
    class MarkupReservation {

        @Test
        @DisplayName("a Free account with only monthly credits is refused a markup reservation")
        void markupReservationIgnoresThePool() {
            Subscription s = active(sub("FREE", "1000.00", "0.00"));

            CreditConsumeResult result = cloud.tryReserveMarkup(USER_ID, "markup:gen-1", PROVIDER,
                    FREE_TIER_MODEL, new BigDecimal("5"), null, 15, "RUN", "run-1", false);

            assertThat(result.success())
                    .as("a reservation is a generation, not a chat turn, even on a free-tier model")
                    .isFalse();
            assertThat(result.error()).contains("PLAN_EXCLUDES_THIS");
            assertThat(s.getRemainingCredits()).isEqualByComparingTo("1000.00");
        }

        @Test
        @DisplayName("with a top-up it is funded from the top-up alone")
        void markupReservationDrawsTopUp() {
            Subscription s = active(sub("FREE", "1000.00", "20.00"));

            assertThat(cloud.tryReserveMarkup(USER_ID, "markup:gen-2", PROVIDER, FREE_TIER_MODEL,
                    new BigDecimal("5"), null, 15, "RUN", "run-1", false).success()).isTrue();
            assertThat(s.getPaygRemainingCredits()).isEqualByComparingTo("15.00");
            assertThat(s.getRemainingCredits()).isEqualByComparingTo("1000.00");
        }
    }

    @Nested
    @DisplayName("the pre-flight gates agree with the debit")
    class GateParity {

        @Test
        @DisplayName("canAfford clears a free-tier turn the pool pays for, and refuses another model")
        void canAffordFollowsTheModel() {
            active(sub("FREE", "1000.00", "0.00"));

            assertThat(cloud.canAfford(USER_ID, new BigDecimal("2.00"), "CHAT_CONVERSATION",
                    PROVIDER, FREE_TIER_MODEL)).isTrue();
            assertThat(cloud.canAfford(USER_ID, new BigDecimal("2.00"), "CHAT_CONVERSATION",
                    PROVIDER, OTHER_MODEL)).isFalse();
        }

        @Test
        @DisplayName("the /check gate answers the same way")
        void checkGateFollowsTheModel() {
            active(sub("FREE", "1000.00", "0.00"));

            assertThat(cloud.hasSufficientCredits(USER_ID, "CHAT_CONVERSATION", PROVIDER, FREE_TIER_MODEL))
                    .isTrue();
            assertThat(cloud.hasSufficientCredits(USER_ID, "CHAT_CONVERSATION", PROVIDER, OTHER_MODEL))
                    .isFalse();
        }

        @Test
        @DisplayName("a model-blind gate fails closed: without a model nothing is free-tier")
        void modelBlindGateFailsClosed() {
            active(sub("FREE", "1000.00", "0.00"));

            assertThat(cloud.hasSufficientCredits(USER_ID, "CHAT_CONVERSATION")).isFalse();
            assertThat(cloud.canAfford(USER_ID, new BigDecimal("2.00"), "CHAT_CONVERSATION")).isFalse();
        }
    }

    @Nested
    @DisplayName("the LLM-spendable balance a budget guard reads")
    class LlmSpendableBalance {

        @Test
        @DisplayName("Free on a free-tier model: the monthly pool plus the top-up")
        void freeTierModelCountsThePool() {
            active(sub("FREE", "1000.00", "25.00"));

            assertThat(cloud.getLlmSpendableBalance(USER_ID, PROVIDER, FREE_TIER_MODEL))
                    .isEqualByComparingTo("1025.00");
        }

        @Test
        @DisplayName("Free on another model: the top-up alone, since no debit for it can reach the pool")
        void otherModelCountsTopUpOnly() {
            active(sub("FREE", "1000.00", "25.00"));

            assertThat(cloud.getLlmSpendableBalance(USER_ID, PROVIDER, OTHER_MODEL))
                    .isEqualByComparingTo("25.00");
        }

        @ParameterizedTest(name = "{0} gets its whole wallet")
        @ValueSource(strings = {"STARTER", "PRO", "TEAM"})
        @DisplayName("a paid plan is unaffected: the whole wallet, whatever the model")
        void paidPlanGetsTheWholeWallet(String planCode) {
            active(sub(planCode, "1000.00", "25.00"));

            assertThat(cloud.getLlmSpendableBalance(USER_ID, PROVIDER, OTHER_MODEL))
                    .isEqualByComparingTo("1025.00");
        }

        @Test
        @DisplayName("no active subscription: zero")
        void noSubscriptionIsZero() {
            assertThat(cloud.getLlmSpendableBalance(USER_ID, PROVIDER, FREE_TIER_MODEL))
                    .isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("CE (unlimited) reports the unlimited sentinel")
        void ceIsUnlimited() {
            CreditService ce = new CreditService(subscriptionRepository, ledgerRepository, pricingService, true);

            assertThat(ce.getLlmSpendableBalance(USER_ID, PROVIDER, OTHER_MODEL))
                    .isEqualByComparingTo("999999999");
        }
    }

    @Nested
    @DisplayName("paid plans and CE are unaffected")
    class Unaffected {

        @ParameterizedTest(name = "{0} pays any model from its monthly credits")
        @ValueSource(strings = {"STARTER", "PRO", "TEAM"})
        @DisplayName("a paid plan's turn on any model draws the monthly credits, as before")
        void paidPlanDrawsItsWallet(String planCode) {
            Subscription s = active(sub(planCode, "1000.00", "0.00"));

            assertThat(llmTurn(OTHER_MODEL, "AGENT_EXECUTION").success()).isTrue();
            assertThat(s.getRemainingCredits()).isEqualByComparingTo("998.00");
        }

        @Test
        @DisplayName("CE never meters the pool")
        void ceNeverMeters() {
            CreditService ce = new CreditService(subscriptionRepository, ledgerRepository, pricingService, true);
            Subscription s = active(sub("FREE", "0.00", "0.00"));

            CreditConsumeResult result = ce.consumeForAgent(USER_ID, "exec-ce", PROVIDER, OTHER_MODEL,
                    LlmTokenBreakdown.of(1000, 500), "AGENT_EXECUTION");

            assertThat(result.success()).isTrue();
            assertThat(s.getRemainingCredits()).isEqualByComparingTo("0.00");
        }
    }
}
