package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.CreditLedgerEntry;
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
import org.mockito.ArgumentCaptor;
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
 * The per-plan monthly AI allowance (V494), a third bucket drained BEFORE the wallet.
 *
 * <p>No plan grants one since V512: the Free plan's 100-credit pot was merged into its
 * 1000 monthly credits, which now fund free-tier chat/agent turns directly (pinned in
 * {@link CreditServiceFreePlanSinglePoolTest}). The mechanism stays, tunable through
 * {@code plan.included_ai_credits}, so its contract is still pinned here: when a plan
 * grants a pot it pays for the curated agent turns first, and it refuses everything
 * else - another model, a workflow node, a flat-cost add-on, a plan that configures no
 * allowance.
 *
 * <p>The fixtures still use the FREE plan code, because that is the plan whose monthly
 * credits are scoped; what falls through after the pot is therefore the V512 routing
 * (monthly pool on a free-tier model, PAYG alone otherwise).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CreditService - the monthly AI allowance")
class CreditServiceAiAllowanceTest {

    private static final Long USER_ID = 42L;
    private static final String PROVIDER = "anthropic";
    private static final String OPEN_MODEL = "claude-haiku-4-5";
    private static final String CLOSED_MODEL = "claude-opus-4-6";

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
        when(pricingService.isFreeTierModel(PROVIDER, OPEN_MODEL)).thenReturn(true);
        when(pricingService.calculateCost(anyString(), anyString(), any(LlmTokenBreakdown.class)))
                .thenReturn(new BigDecimal("2.00"));
    }

    /** sub = the workflow grant, payg = top-ups, ai = the new allowance. */
    private Subscription sub(String planCode, String subBucket, String payg, String ai) {
        // The plan CONFIGURES an allowance, which is a second condition on spending the
        // pot: a balance alone is not enough (see planWithNoConfiguredAllowanceCannotSpendItsPot).
        return sub(planCode, subBucket, payg, ai, 100);
    }

    private Subscription sub(String planCode, String subBucket, String payg, String ai,
                             Integer planAiAllowance) {
        Subscription s = new Subscription();
        s.setId(1L);
        Plan plan = new Plan(planCode, planCode, "");
        plan.setIncludedAiCredits(planAiAllowance);
        s.setPlan(plan);
        s.setRemainingCredits(new BigDecimal(subBucket));
        s.setPaygRemainingCredits(new BigDecimal(payg));
        s.setAiRemainingCredits(new BigDecimal(ai));
        s.setDelinquent(false);
        return s;
    }

    private void active(Subscription s) {
        when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(s));
        when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(s));
    }

    private CreditConsumeResult agentTurn(Subscription s, String model, String sourceId) {
        active(s);
        return cloud.consumeForAgent(USER_ID, sourceId, PROVIDER, model,
                LlmTokenBreakdown.of(1000, 500), "AGENT_EXECUTION");
    }

    @Nested
    @DisplayName("what it pays for")
    class WhatItPaysFor {

        @Test
        @DisplayName("a Free agent turn on an open model is paid by the AI pot, leaving the workflow grant intact")
        void openModelDrawsTheAiPot() {
            Subscription s = sub("FREE", "1000.00", "0.00", "100.00");

            CreditConsumeResult result = agentTurn(s, OPEN_MODEL, "exec-1");

            assertThat(result.success())
                    .as("pre-V494 this was refused: PAYG is empty on a fresh signup")
                    .isTrue();
            assertThat(s.getAiRemainingCredits()).isEqualByComparingTo("98.00");
            assertThat(s.getRemainingCredits())
                    .as("the workflow grant must not fund an agent turn")
                    .isEqualByComparingTo("1000.00");
            assertThat(s.getPaygRemainingCredits()).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("a Free chat turn on an open model is paid the same way")
        void chatAlsoDrawsTheAiPot() {
            Subscription s = sub("FREE", "1000.00", "0.00", "100.00");
            active(s);

            cloud.consumeForChat(USER_ID, "conv-1", PROVIDER, OPEN_MODEL,
                    LlmTokenBreakdown.of(1000, 500));

            assertThat(s.getAiRemainingCredits()).isEqualByComparingTo("98.00");
            assertThat(s.getRemainingCredits()).isEqualByComparingTo("1000.00");
        }

        @Test
        @DisplayName("a turn costlier than what is left drains the pot to zero and charges only the rest elsewhere")
        void partialCoverageDrainsThenFallsThrough() {
            Subscription s = sub("FREE", "1000.00", "50.00", "1.50");

            agentTurn(s, OPEN_MODEL, "exec-partial");

            // cost 2.00: 1.50 from the pot, 0.50 left to the normal routing, which on
            // FREE (V512) is the monthly pool for a free-tier model.
            assertThat(s.getAiRemainingCredits())
                    .as("the allowance is spent to the last credit rather than wasted")
                    .isEqualByComparingTo("0.00");
            assertThat(s.getRemainingCredits()).isEqualByComparingTo("999.50");
            assertThat(s.getPaygRemainingCredits())
                    .as("the top-up is only reached once the monthly pool is empty")
                    .isEqualByComparingTo("50.00");
        }

        @Test
        @DisplayName("a chat overshoot floors the pot at zero and books the tail on the monthly pool, never on PAYG")
        void chatOvershootFloorsThePot() {
            // Chat debits post-flight (allowNegative): the LLM has already streamed, so
            // the movement is recorded even though the cost exceeds what the account holds.
            // Since V512 a free-tier turn may draw the monthly pool, so the uncovered tail
            // is an ordinary debt on it (cleared by the next monthly reset or a top-up),
            // exactly as on a paid plan, and the PAYG bucket is never driven negative.
            Subscription s = sub("FREE", "0.00", "0.00", "0.50");
            active(s);

            cloud.consumeForChat(USER_ID, "conv-overshoot", PROVIDER, OPEN_MODEL,
                    LlmTokenBreakdown.of(1000, 500));

            assertThat(s.getAiRemainingCredits())
                    .as("the allowance can be emptied but never driven negative")
                    .isEqualByComparingTo("0.00");
            assertThat(s.getRemainingCredits()).isEqualByComparingTo("-1.50");
            assertThat(s.getPaygRemainingCredits()).isEqualByComparingTo("0.00");
            assertThat(s.getDelinquent())
                    .as("a negative total gates new spend until the reset or a top-up")
                    .isTrue();
        }

        @Test
        @DisplayName("an agent turn the pot cannot cover is refused outright, leaving the allowance untouched")
        void underfundedAgentTurnIsRefusedWithoutSpending() {
            // Agent executions gate pre-flight (allowNegative=false), so an unaffordable
            // turn must not nibble the pot on its way to being refused. The monthly pool is
            // empty: since V512 it would otherwise fund the free-tier rest.
            Subscription s = sub("FREE", "0.00", "0.00", "0.50");

            CreditConsumeResult result = agentTurn(s, OPEN_MODEL, "exec-underfunded");

            assertThat(result.success()).isFalse();
            assertThat(s.getAiRemainingCredits()).isEqualByComparingTo("0.50");
            assertThat(s.getPaygRemainingCredits()).isEqualByComparingTo("0.00");
        }
    }

    @Nested
    @DisplayName("what it refuses - otherwise it is just a bigger grant")
    class WhatItRefuses {

        @Test
        @DisplayName("a model NOT opened to the free tier cannot touch the pot")
        void closedModelCannotTouchThePot() {
            Subscription s = sub("FREE", "1000.00", "0.00", "100.00");

            CreditConsumeResult result = agentTurn(s, CLOSED_MODEL, "exec-closed");

            assertThat(result.success())
                    .as("only the curated models are free; anything else needs a top-up")
                    .isFalse();
            assertThat(s.getAiRemainingCredits()).isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("a plan that configures NO allowance cannot spend a pot it still holds")
        void planWithNoConfiguredAllowanceCannotSpendItsPot() {
            // Defence in depth for the money path. Clearing the pot on a plan change is
            // what normally stops this, and it lives in a different class on a different
            // schedule; if any route ever misses it, the balance alone would fund a
            // paying account's inference from a pot its plan does not grant. Two
            // conditions means one missed clear is inert instead of expensive.
            Subscription s = sub("PRO", "1000.00", "0.00", "100.00", null);

            CreditConsumeResult result = agentTurn(s, OPEN_MODEL, "exec-no-configured-allowance");

            assertThat(result.success()).isTrue();
            assertThat(s.getAiRemainingCredits())
                    .as("the stranded pot is left untouched, not spent")
                    .isEqualByComparingTo("100.00");
            assertThat(s.getRemainingCredits())
                    .as("and the turn is paid for by the wallet the account actually has")
                    .isEqualByComparingTo("998.00");
        }

        @Test
        @DisplayName("a plan configured with ZERO allowance is treated the same way")
        void planWithZeroConfiguredAllowanceCannotSpendItsPot() {
            // How an admin closes the free tier: included_ai_credits = 0. It must stop
            // the spending immediately, not at the next renewal.
            Subscription s = sub("FREE", "1000.00", "50.00", "100.00", 0);

            CreditConsumeResult result = agentTurn(s, OPEN_MODEL, "exec-zero-configured-allowance");

            assertThat(result.success()).isTrue();
            assertThat(s.getAiRemainingCredits()).isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("a model the mirror answers FALSE for cannot draw the pot")
        void modelTheMirrorRefusesCannotDrawThePot() {
            // Where that false comes from for an UNKNOWN model - the synthetic default
            // row - belongs to ModelPricingService and is pinned in
            // ModelPricingServiceFreeTierMirrorTest.unknownModelFailsClosed. What this
            // asserts is the half that lives here: a false is honoured, whatever its origin.
            Subscription s = sub("FREE", "1000.00", "0.00", "100.00");

            CreditConsumeResult result = agentTurn(s, "model-not-in-mirror", "exec-unknown");

            assertThat(result.success()).isFalse();
            assertThat(s.getAiRemainingCredits()).isEqualByComparingTo("100.00");
        }


        @Test
        @DisplayName("a FREE overshoot with NO allowance involved still marks the account delinquent")
        void nonAllowanceOvershootStillLatches() {
            // The pre-V494 behaviour, kept exactly. This account has a PAYG wallet and a
            // model the allowance does not cover, so the debt is an ordinary one and the
            // flag is what stops it growing through new runs. Without this case, the fix
            // above could silently disable the delinquency gate for every FREE account.
            Subscription s = sub("FREE", "1000.00", "0.50", "100.00");
            active(s);

            cloud.consumeForChat(USER_ID, "conv-no-allowance", PROVIDER, CLOSED_MODEL,
                    LlmTokenBreakdown.of(1000, 500));

            assertThat(s.getAiRemainingCredits())
                    .as("a closed model cannot touch the pot")
                    .isEqualByComparingTo("100.00");
            assertThat(s.getPaygRemainingCredits()).isEqualByComparingTo("-1.50");
            assertThat(s.getDelinquent()).isTrue();
        }

        @Test
        @DisplayName("a workflow node keeps drawing the workflow grant, never the AI pot")
        void workflowNodeIgnoresThePot() {
            Subscription s = sub("FREE", "1000.00", "0.00", "100.00");
            active(s);

            cloud.consumeForWorkflowNode(USER_ID, "run-1:step-1");

            assertThat(s.getRemainingCredits()).isEqualByComparingTo("999.00");
            assertThat(s.getAiRemainingCredits())
                    .as("the AI pot is for agents; workflow orchestration has its own grant")
                    .isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("a flat-cost add-on (web search) cannot draw the AI pot")
        void flatCostAddOnsIgnoreThePot() {
            Subscription s = sub("FREE", "1000.00", "0.00", "100.00");
            active(s);

            CreditConsumeResult result = cloud.consumeForWebSearch(USER_ID, "search-1");

            assertThat(result.success()).isFalse();
            assertThat(s.getAiRemainingCredits()).isEqualByComparingTo("100.00");
        }

        @ParameterizedTest(name = "{0} is unaffected")
        @ValueSource(strings = {"STARTER", "PRO", "TEAM"})
        @DisplayName("a paid plan's agent turn draws the normal wallet, untouched by this change")
        void paidPlansAreUnaffected(String planCode) {
            Subscription s = sub(planCode, "1000.00", "0.00", "0.00");

            CreditConsumeResult result = agentTurn(s, OPEN_MODEL, "exec-" + planCode);

            assertThat(result.success()).isTrue();
            assertThat(s.getRemainingCredits()).isEqualByComparingTo("998.00");
            assertThat(s.getAiRemainingCredits()).isEqualByComparingTo("0.00");
        }

        @ParameterizedTest(name = "{0} with a pot still spends it")
        @ValueSource(strings = {"STARTER", "PRO", "TEAM"})
        @DisplayName("the pot is drawn by BALANCE, not by plan code - stated, because it reads the other way")
        void aPaidPlanHoldingAPotStillSpendsIt(String planCode) {
            // The fixture above starts the pot at zero, which proves only that an empty
            // pot is not drawn. This one gives a paid plan 100 and shows the debit takes
            // it: aiAllowanceEligible never looks at the plan code. That is deliberate -
            // it means an admin can grant a pot to any plan and it works - but it also
            // means the protection against a paid account spending one is NOT here. It
            // is refillAiAllowance CLEARING the pot on a plan that grants none, which
            // CreditAttributionAiAllowanceRefillTest pins. Two halves, stated where each
            // one lives, rather than one test implying it covers both.
            // Configured allowance AND balance: this is the "admin granted a pot to a
            // paid plan" case, which is allowed on purpose. The case that must NOT spend
            // is a pot with no configured allowance behind it, pinned in WhatItRefuses.
            Subscription s = sub(planCode, "1000.00", "0.00", "100.00", 100);

            CreditConsumeResult result = agentTurn(s, OPEN_MODEL, "exec-pot-" + planCode);

            assertThat(result.success()).isTrue();
            assertThat(s.getAiRemainingCredits())
                    .as("drained first, exactly as on FREE")
                    .isEqualByComparingTo("98.00");
            assertThat(s.getRemainingCredits())
                    .as("and the wallet is untouched while the pot covers it")
                    .isEqualByComparingTo("1000.00");
        }
    }

    @Nested
    @DisplayName("the gate agrees with the debit")
    class GateParity {

        @Test
        @DisplayName("the pre-flight clears a turn the AI pot alone would pay for")
        void gateCountsTheAllowance() {
            active(sub("FREE", "1000.00", "0.00", "100.00"));

            assertThat(cloud.canAfford(USER_ID, new BigDecimal("2.00"), "CHAT_CONVERSATION",
                    PROVIDER, OPEN_MODEL))
                    .as("gate and debit must read the same buckets, or a funded turn is refused up-front")
                    .isTrue();
        }

        @Test
        @DisplayName("the pre-flight still refuses the same turn on a closed model")
        void gateRefusesClosedModel() {
            active(sub("FREE", "1000.00", "0.00", "100.00"));

            assertThat(cloud.canAfford(USER_ID, new BigDecimal("2.00"), "CHAT_CONVERSATION",
                    PROVIDER, CLOSED_MODEL))
                    .isFalse();
        }

        @Test
        @DisplayName("the model-less overload keeps the pre-V494 behaviour")
        void modelLessOverloadUnchanged() {
            active(sub("FREE", "1000.00", "0.00", "100.00"));

            assertThat(cloud.canAfford(USER_ID, new BigDecimal("2.00"), "CHAT_CONVERSATION"))
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("every source type the allowance is declared to fund")
    class SourceTypeCoverage {

        /**
         * The set is seven raw strings. A typo in any of them is silent - the turn
         * simply falls through to PAYG and is refused - so each one is exercised
         * rather than trusted. Only two of the seven were covered before.
         */
        @ParameterizedTest(name = "{0} draws the allowance")
        @ValueSource(strings = {
                "AGENT_EXECUTION", "CHAT_CONVERSATION", "CLASSIFY_EXECUTION",
                "GUARDRAIL_EXECUTION", "COMPACTION_SUMMARY", "BROWSER_AGENT_EXECUTION",
                "CLI_SESSION"})
        @DisplayName("is funded by the pot on an open model")
        void declaredSourceTypesDrawTheAllowance(String sourceType) {
            Subscription s = sub("FREE", "1000.00", "0.00", "100.00");
            active(s);

            CreditConsumeResult result = cloud.consumeForAgent(USER_ID, "exec-" + sourceType,
                    PROVIDER, OPEN_MODEL, LlmTokenBreakdown.of(1000, 500), sourceType);

            assertThat(result.success()).isTrue();
            assertThat(s.getAiRemainingCredits()).isEqualByComparingTo("98.00");
            assertThat(s.getRemainingCredits()).isEqualByComparingTo("1000.00");
        }

        @Test
        @DisplayName("a source type NOT in the set is refused, so the list stays the whole contract")
        void undeclaredSourceTypeIsRefused() {
            Subscription s = sub("FREE", "1000.00", "0.00", "100.00");
            active(s);

            CreditConsumeResult result = cloud.consumeForAgent(USER_ID, "exec-undeclared",
                    PROVIDER, OPEN_MODEL, LlmTokenBreakdown.of(1000, 500), "SOME_FUTURE_SOURCE");

            assertThat(result.success()).isFalse();
            assertThat(s.getAiRemainingCredits()).isEqualByComparingTo("100.00");
        }
    }

    @Nested
    @DisplayName("self-hosted (CE)")
    class CommunityEdition {

        /** unlimited=true: the whole bucket-routing layer is short-circuited. */
        private CreditService ce() {
            return new CreditService(subscriptionRepository, ledgerRepository, pricingService, true);
        }

        @Test
        @DisplayName("the allowance is never consulted - CE meters nothing and has no plans")
        void ceNeverTouchesTheAllowance() {
            Subscription s = sub("FREE", "0.00", "0.00", "100.00");
            active(s);

            CreditConsumeResult result = ce().consumeForAgent(USER_ID, "exec-ce", PROVIDER,
                    OPEN_MODEL, LlmTokenBreakdown.of(1000, 500), "AGENT_EXECUTION");

            assertThat(result.success()).isTrue();
            assertThat(s.getAiRemainingCredits())
                    .as("a self-hosted install must not spend a cloud entitlement")
                    .isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("the balance breakdown reports no allowance on CE")
        void ceReportsNoAllowance() {
            assertThat(ce().getBalanceBreakdown(USER_ID).aiBalance()).isEqualByComparingTo("0.00");
        }
    }

    @Nested
    @DisplayName("the generic /check gate agrees too")
    class CheckGate {

        /**
         * Regression: this gate was left model-blind when the model-aware form was
         * added, so a fresh Free account with a full allowance was refused 402 by the
         * scheduled/streaming chat entry points while the cost-aware gate let the same
         * turn through. The two must answer the same question.
         */
        @Test
        @DisplayName("clears a free-tier turn the allowance would pay for")
        void checkGateCountsTheAllowance() {
            active(sub("FREE", "1000.00", "0.00", "100.00"));

            assertThat(cloud.hasSufficientCredits(USER_ID, "CHAT_CONVERSATION", PROVIDER, OPEN_MODEL))
                    .isTrue();
        }

        @Test
        @DisplayName("still refuses the same turn on a model outside the free tier")
        void checkGateRefusesClosedModel() {
            active(sub("FREE", "1000.00", "0.00", "100.00"));

            assertThat(cloud.hasSufficientCredits(USER_ID, "CHAT_CONVERSATION", PROVIDER, CLOSED_MODEL))
                    .isFalse();
        }

        @Test
        @DisplayName("refuses once the allowance AND the monthly pool are spent")
        void checkGateRefusesSpentAllowance() {
            active(sub("FREE", "0.00", "0.00", "0.00"));

            assertThat(cloud.hasSufficientCredits(USER_ID, "CHAT_CONVERSATION", PROVIDER, OPEN_MODEL))
                    .isFalse();
        }

        @Test
        @DisplayName("a workflow launch gate (null sourceType) still sees the full two-bucket total")
        void nullSourceTypeUnchanged() {
            active(sub("FREE", "1000.00", "0.00", "0.00"));

            assertThat(cloud.hasSufficientCredits(USER_ID, null, null, null)).isTrue();
        }
    }

    @Nested
    @DisplayName("the ledger row records what the allowance paid")
    class LedgerPortion {

        /**
         * The wire the whole reconciliation story rests on, and the one thing a mock
         * on either side cannot prove. The row carries the FULL cost in `amount`
         * (reporting needs it) while the allowance sits outside the balance the daily
         * reconciliation compares against, so `ai_portion` is what lets it add the
         * movement back. Delete the single {@code entry.setAiPortion(...)} line and,
         * without this test, the whole repo still passes while every free-tier turn
         * grows an unexplained drift of exactly its cost.
         */
        private CreditLedgerEntry savedRow() {
            ArgumentCaptor<CreditLedgerEntry> captor = ArgumentCaptor.forClass(CreditLedgerEntry.class);
            org.mockito.Mockito.verify(ledgerRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
            return captor.getValue();
        }

        @Test
        @DisplayName("an allowance-funded turn stamps the funded amount on the row")
        void aiFundedRowCarriesThePortion() {
            Subscription s = sub("FREE", "1000.00", "0.00", "100.00");

            agentTurn(s, OPEN_MODEL, "exec-portion");

            CreditLedgerEntry row = savedRow();
            assertThat(row.getAmount())
                    .as("the row still states what the turn was worth")
                    .isEqualByComparingTo("-2.00");
            assertThat(row.getAiPortion())
                    .as("and how much of it never touched the wallet")
                    .isEqualByComparingTo("2.00");
        }

        @Test
        @DisplayName("a partially-funded turn stamps only the part the allowance covered")
        void partiallyFundedRowCarriesOnlyTheCoveredPart() {
            Subscription s = sub("FREE", "1000.00", "50.00", "1.50");

            agentTurn(s, OPEN_MODEL, "exec-portion-partial");

            CreditLedgerEntry row = savedRow();
            assertThat(row.getAmount()).isEqualByComparingTo("-2.00");
            assertThat(row.getAiPortion())
                    .as("0.50 of this debit DID leave the wallet and must not be added back")
                    .isEqualByComparingTo("1.50");
        }

        @Test
        @DisplayName("a wallet-funded turn stamps zero, so reconciliation is untouched for paid accounts")
        void walletFundedRowCarriesZero() {
            Subscription s = sub("PRO", "1000.00", "0.00", "0.00");

            agentTurn(s, CLOSED_MODEL, "exec-portion-paid");

            assertThat(savedRow().getAiPortion()).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("a REFUSED turn's audit row stamps zero - nothing was funded, so nothing is added back")
        void rejectedRowCarriesZero() {
            // Reconciliation sums ai_portion over ALL rows of the period, audit rows
            // included. A nonzero here would credit the account for a turn that never
            // ran, and the drift would look exactly like a real one.
            Subscription s = sub("FREE", "0.00", "0.00", "0.00");

            CreditConsumeResult result = agentTurn(s, OPEN_MODEL, "exec-portion-refused");

            assertThat(result.success()).isFalse();
            assertThat(savedRow().getAiPortion())
                    .as("an audit row records a refusal, not a movement")
                    .isEqualByComparingTo("0.00");
        }
    }

    @Nested
    @DisplayName("the balance surface")
    class BalanceSurface {

        @Test
        @DisplayName("the allowance is reported on its own and is NOT folded into the headline balance")
        void allowanceIsReportedSeparately() {
            active(sub("FREE", "1000.00", "25.00", "100.00"));

            CreditService.BalanceBreakdown breakdown = cloud.getBalanceBreakdown(USER_ID);

            assertThat(breakdown.aiBalance()).isEqualByComparingTo("100.00");
            assertThat(breakdown.balance())
                    .as("folding a restricted pot into the total would promise spending power the wallet lacks")
                    .isEqualByComparingTo("1025.00");
        }
    }
}
