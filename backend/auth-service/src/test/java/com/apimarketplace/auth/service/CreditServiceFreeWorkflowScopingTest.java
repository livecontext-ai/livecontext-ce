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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Free-plan workflow-credit scoping (Cloud only). The monthly Free grant lands on
 * the sub bucket ({@code remaining_credits}); on the FREE plan that bucket may fund
 * ONLY workflow-node orchestration. Chat, agent and every other LLM/tool source
 * must draw the PAYG bucket. Paid plans are unaffected (sub funds everything); CE
 * (unlimited mode) is unaffected so a Free CE user keeps full BYOK access.
 *
 * <p>Companion to {@link CreditServiceTwoBucketTest}, which pins the plan-agnostic
 * sub/payg arithmetic. Here every fixture carries an explicit plan code so the
 * (plan x source_type x bucket) routing matrix is exercised end to end.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CreditService - Free monthly credits are workflow-scoped (Cloud only)")
class CreditServiceFreeWorkflowScopingTest {

    @Mock
    private SubscriptionRepository subscriptionRepository;
    @Mock
    private CreditLedgerRepository ledgerRepository;
    @Mock
    private ModelPricingService pricingService;

    private static final Long USER_ID = 42L;

    /** Cloud-metering CreditService (unlimited=false, markup enabled via 4-arg ctor). */
    private CreditService cloud;

    @BeforeEach
    void setUp() {
        cloud = new CreditService(subscriptionRepository, ledgerRepository, pricingService, false);
    }

    private Subscription sub(String planCode, BigDecimal subBucket, BigDecimal paygBucket) {
        Subscription s = new Subscription();
        s.setId(1L);
        s.setPlan(new Plan(planCode, planCode, ""));
        s.setRemainingCredits(subBucket);
        s.setPaygRemainingCredits(paygBucket);
        s.setDelinquent(false);
        return s;
    }

    // ==========================================================================
    // Post-flight debit routing (deductCredits)
    // ==========================================================================

    @Nested
    @DisplayName("debit routing")
    class DebitRouting {

        @Test
        @DisplayName("FREE + workflow node draws the monthly sub bucket - workflows are funded by Free credits")
        void freeWorkflowNodeDrawsSubBucket() {
            Subscription s = sub("FREE", new BigDecimal("5.00"), BigDecimal.ZERO);
            when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(s));

            CreditConsumeResult result = cloud.consumeForWorkflowNode(USER_ID, "run-1:step-1");

            assertThat(result.success()).isTrue();
            // 1 credit/node taken from the sub (monthly) bucket.
            assertThat(s.getRemainingCredits()).isEqualByComparingTo("4.00");
            assertThat(s.getPaygRemainingCredits()).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("FREE + workflow node with a PAYG balance still drains the sub bucket first - eligible type keeps the V250 sub-first policy")
        void freeWorkflowNodeDrainsSubFirstEvenWithPayg() {
            Subscription s = sub("FREE", new BigDecimal("5.00"), new BigDecimal("3.00"));
            when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(s));

            CreditConsumeResult result = cloud.consumeForWorkflowNode(USER_ID, "run-2:step-1");

            assertThat(result.success()).isTrue();
            // Workflow node is sub-eligible -> sub drained first, PAYG untouched.
            // A regression routing eligible debits onto PAYG-first would fail here.
            assertThat(s.getRemainingCredits()).isEqualByComparingTo("4.00");
            assertThat(s.getPaygRemainingCredits()).isEqualByComparingTo("3.00");
        }

        @Test
        @DisplayName("FREE + agent with no PAYG is REJECTED and the monthly sub bucket is untouched")
        void freeAgentWithoutPaygIsRejected() {
            Subscription s = sub("FREE", new BigDecimal("1000.00"), BigDecimal.ZERO);
            when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(s));
            when(pricingService.calculateCost("anthropic", "claude-3-5-sonnet", LlmTokenBreakdown.of(1000, 500)))
                    .thenReturn(new BigDecimal("8.00"));

            CreditConsumeResult result = cloud.consumeForAgent(
                    USER_ID, "exec-1", "anthropic", "claude-3-5-sonnet", 1000, 500);

            assertThat(result.success()).isFalse();
            // The 1000 monthly workflow credits cannot fund an agent turn.
            assertThat(s.getRemainingCredits()).isEqualByComparingTo("1000.00");
            assertThat(s.getPaygRemainingCredits()).isEqualByComparingTo("0.00");

            ArgumentCaptor<CreditLedgerEntry> captor = ArgumentCaptor.forClass(CreditLedgerEntry.class);
            verify(ledgerRepository).save(captor.capture());
            assertThat(captor.getValue().getSourceType()).isEqualTo("AGENT_EXECUTION_REJECTED");
            // balance_after on the rejection row reflects the ELIGIBLE (PAYG) balance,
            // not the masked total - i.e. the 0 the agent could actually spend.
            assertThat(captor.getValue().getBalanceAfter()).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("FREE + agent with a PAYG top-up draws PAYG only, never the monthly grant")
        void freeAgentWithPaygDrawsPaygOnly() {
            Subscription s = sub("FREE", new BigDecimal("1000.00"), new BigDecimal("20.00"));
            when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(s));
            when(pricingService.calculateCost("anthropic", "claude-3-5-sonnet", LlmTokenBreakdown.of(1000, 500)))
                    .thenReturn(new BigDecimal("8.00"));

            CreditConsumeResult result = cloud.consumeForAgent(
                    USER_ID, "exec-2", "anthropic", "claude-3-5-sonnet", 1000, 500);

            assertThat(result.success()).isTrue();
            assertThat(s.getRemainingCredits()).isEqualByComparingTo("1000.00"); // monthly grant untouched
            assertThat(s.getPaygRemainingCredits()).isEqualByComparingTo("12.00"); // 20 - 8 from PAYG
        }

        @Test
        @DisplayName("FREE + chat post-flight overshoot pushes PAYG negative but never drains the monthly sub bucket")
        void freeChatOvershootProtectsSubBucket() {
            Subscription s = sub("FREE", new BigDecimal("1000.00"), BigDecimal.ZERO);
            when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(s));
            // consumeForChat is post-flight (allowNegative=true): the LLM already streamed.
            when(pricingService.calculateCost("anthropic", "claude-3-5-sonnet", LlmTokenBreakdown.of(1000, 500)))
                    .thenReturn(new BigDecimal("10.00"));

            CreditConsumeResult result = cloud.consumeForChat(
                    USER_ID, "conv-1", "anthropic", "claude-3-5-sonnet", 1000, 500);

            assertThat(result.success()).isTrue();
            // Overshoot lands on PAYG, NOT on the monthly workflow grant.
            assertThat(s.getRemainingCredits()).isEqualByComparingTo("1000.00");
            assertThat(s.getPaygRemainingCredits()).isEqualByComparingTo("-10.00");
            // Total stays positive (sub masks it), so the account is not flagged
            // delinquent - workflows keep running while chat needs a PAYG top-up.
            assertThat(s.getTotalBalance()).isEqualByComparingTo("990.00");
            assertThat(s.getDelinquent()).isFalse();
        }

        @Test
        @DisplayName("PAID plan + agent draws the sub bucket as before - the scoping is FREE-only")
        void paidPlanAgentDrawsSubBucketUnchanged() {
            Subscription s = sub("STARTER", new BigDecimal("1000.00"), BigDecimal.ZERO);
            when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(s));
            when(pricingService.calculateCost("anthropic", "claude-3-5-sonnet", LlmTokenBreakdown.of(1000, 500)))
                    .thenReturn(new BigDecimal("8.00"));

            CreditConsumeResult result = cloud.consumeForAgent(
                    USER_ID, "exec-3", "anthropic", "claude-3-5-sonnet", 1000, 500);

            assertThat(result.success()).isTrue();
            assertThat(s.getRemainingCredits()).isEqualByComparingTo("992.00"); // sub funds agent on paid plans
            assertThat(s.getPaygRemainingCredits()).isEqualByComparingTo("0.00");
        }
    }

    // ==========================================================================
    // Pre-flight gate (canAfford)
    // ==========================================================================

    @Nested
    @DisplayName("pre-flight canAfford")
    class PreFlight {

        @Test
        @DisplayName("FREE chat pre-flight is refused when only monthly (sub) credits exist")
        void freeChatPreflightRefusedWithSubOnly() {
            Subscription s = sub("FREE", new BigDecimal("1000.00"), BigDecimal.ZERO);
            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(s));

            assertThat(cloud.canAfford(USER_ID, new BigDecimal("5.00"), "CHAT_CONVERSATION")).isFalse();
        }

        @Test
        @DisplayName("FREE chat pre-flight is allowed when PAYG covers the projected cost")
        void freeChatPreflightAllowedWithPayg() {
            Subscription s = sub("FREE", new BigDecimal("1000.00"), new BigDecimal("10.00"));
            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(s));

            assertThat(cloud.canAfford(USER_ID, new BigDecimal("5.00"), "CHAT_CONVERSATION")).isTrue();
        }

        @Test
        @DisplayName("FREE workflow-node pre-flight is allowed against the monthly sub bucket")
        void freeWorkflowPreflightAllowedAgainstSub() {
            Subscription s = sub("FREE", new BigDecimal("1000.00"), BigDecimal.ZERO);
            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(s));

            assertThat(cloud.canAfford(USER_ID, new BigDecimal("5.00"), "WORKFLOW_NODE")).isTrue();
        }

        @Test
        @DisplayName("FREE legacy canAfford (null sourceType) keeps the total-balance check - back-compat")
        void freeLegacyCanAffordUsesTotal() {
            Subscription s = sub("FREE", new BigDecimal("1000.00"), BigDecimal.ZERO);
            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(s));

            // 2-arg overload delegates with null sourceType -> total balance, not PAYG-scoped.
            assertThat(cloud.canAfford(USER_ID, new BigDecimal("5.00"))).isTrue();
        }

        @Test
        @DisplayName("PAID chat pre-flight is allowed against the sub bucket - scoping is FREE-only")
        void paidChatPreflightAllowedAgainstSub() {
            Subscription s = sub("PRO", new BigDecimal("1000.00"), BigDecimal.ZERO);
            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(s));

            assertThat(cloud.canAfford(USER_ID, new BigDecimal("5.00"), "CHAT_CONVERSATION")).isTrue();
        }
    }

    // ==========================================================================
    // Platform-markup reservation (catalog tools using platform credentials)
    // ==========================================================================

    @Nested
    @DisplayName("platform markup reserve")
    class MarkupReserve {

        @Test
        @DisplayName("FREE markup reserve is refused when only monthly (sub) credits exist - tools are not free on Free")
        void freeMarkupReserveRefusedWithSubOnly() {
            Subscription s = sub("FREE", new BigDecimal("1000.00"), BigDecimal.ZERO);
            when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(s));

            CreditConsumeResult result = cloud.tryReserveMarkup(
                    USER_ID, "platform-markup:STREAM:abc", "openai", "gpt-4o",
                    new BigDecimal("7.00"), null, 15, "STREAM", "scope-1", false);

            assertThat(result.success()).isFalse();
            assertThat(s.getRemainingCredits()).isEqualByComparingTo("1000.00"); // monthly grant untouched
            assertThat(s.getPaygRemainingCredits()).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("FREE markup reserve draws PAYG only and stamps the full PAYG portion (symmetric refund)")
        void freeMarkupReserveDrawsPaygOnly() {
            Subscription s = sub("FREE", new BigDecimal("1000.00"), new BigDecimal("10.00"));
            when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(s));

            CreditConsumeResult result = cloud.tryReserveMarkup(
                    USER_ID, "platform-markup:STREAM:def", "openai", "gpt-4o",
                    new BigDecimal("7.00"), null, 15, "STREAM", "scope-1", false);

            assertThat(result.success()).isTrue();
            assertThat(s.getRemainingCredits()).isEqualByComparingTo("1000.00"); // monthly grant untouched
            assertThat(s.getPaygRemainingCredits()).isEqualByComparingTo("3.00");

            ArgumentCaptor<CreditLedgerEntry> captor = ArgumentCaptor.forClass(CreditLedgerEntry.class);
            verify(ledgerRepository).save(captor.capture());
            // Whole reserve came out of PAYG, so paygPortion == reserved -> release
            // refunds entirely to PAYG, never converting paid money into sub credits.
            assertThat(captor.getValue().getPaygPortion()).isEqualByComparingTo("7.00");
        }
    }

    // ==========================================================================
    // Platform-markup reservation lifecycle (commit) under FREE scoping
    // ==========================================================================

    @Nested
    @DisplayName("platform markup commit lifecycle")
    class MarkupCommit {

        /** A PAYG-only reserve row, as tryReserveMarkup stamps it on a FREE account. */
        private CreditLedgerEntry paygReserveRow(BigDecimal reserved) {
            CreditLedgerEntry row = new CreditLedgerEntry();
            row.setUserId(USER_ID);
            row.setSourceId("platform-markup:STREAM:commit");
            row.setSourceType("PLATFORM_MARKUP_RESERVE");
            row.setAmount(reserved.negate());     // reserve stored as negative
            row.setPaygPortion(reserved);          // FREE reserve was entirely PAYG
            return row;
        }

        @Test
        @DisplayName("FREE markup commit over-shoot (actual > reserved) debits the extra from PAYG only - sub bucket untouched")
        void freeCommitOvershootExtraDebitFromPaygOnly() {
            // Post-reserve state: a 7-credit PAYG-only reserve already drained payg 10 -> 3.
            Subscription s = sub("FREE", new BigDecimal("1000.00"), new BigDecimal("3.00"));
            when(ledgerRepository.findFirstBySourceIdForUpdate("platform-markup:STREAM:commit"))
                    .thenReturn(Optional.of(paygReserveRow(new BigDecimal("7.00"))));
            when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(s));

            CreditService.CommitOutcome outcome = cloud.commitReservation(
                    "platform-markup:STREAM:commit", new BigDecimal("9.00"), "openai", "gpt-4o");

            assertThat(outcome).isEqualTo(CreditService.CommitOutcome.COMMITTED);
            // Extra 2 (actual 9 - reserved 7) comes out of PAYG (3 -> 1); the monthly
            // sub grant is NEVER touched by the markup over-shoot on a FREE account.
            assertThat(s.getRemainingCredits()).isEqualByComparingTo("1000.00");
            assertThat(s.getPaygRemainingCredits()).isEqualByComparingTo("1.00");
        }

        @Test
        @DisplayName("FREE markup commit under-shoot (actual < reserved) refunds the delta to PAYG - symmetric with the PAYG-only reserve")
        void freeCommitUndershootRefundsToPayg() {
            // Post-reserve state: same 7-credit PAYG-only reserve (payg 10 -> 3).
            Subscription s = sub("FREE", new BigDecimal("1000.00"), new BigDecimal("3.00"));
            when(ledgerRepository.findFirstBySourceIdForUpdate("platform-markup:STREAM:commit"))
                    .thenReturn(Optional.of(paygReserveRow(new BigDecimal("7.00"))));
            when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(s));

            CreditService.CommitOutcome outcome = cloud.commitReservation(
                    "platform-markup:STREAM:commit", new BigDecimal("4.00"), "openai", "gpt-4o");

            assertThat(outcome).isEqualTo(CreditService.CommitOutcome.COMMITTED);
            // Refund delta 3 (reserved 7 - actual 4) returns entirely to PAYG (paygPortion
            // was the full 7), so payg 3 -> 6; sub stays at the monthly grant.
            assertThat(s.getRemainingCredits()).isEqualByComparingTo("1000.00");
            assertThat(s.getPaygRemainingCredits()).isEqualByComparingTo("6.00");
        }
    }

    // ==========================================================================
    // CE / self-host (unlimited mode) is NOT impacted - a Free user keeps BYOK
    // ==========================================================================

    @Nested
    @DisplayName("CE unlimited mode is never restricted")
    class CeUnlimited {

        private CreditService ce() {
            return new CreditService(subscriptionRepository, ledgerRepository, pricingService, true);
        }

        @Test
        @DisplayName("CE Free user can run an agent - unlimited short-circuits before any bucket scoping")
        void ceFreeAgentSucceeds() {
            when(pricingService.calculateCost("anthropic", "claude-3-5-sonnet", LlmTokenBreakdown.of(1000, 500)))
                    .thenReturn(new BigDecimal("8.00"));

            CreditConsumeResult result = ce().consumeForAgent(
                    USER_ID, "exec-ce", "anthropic", "claude-3-5-sonnet", 1000, 500);

            assertThat(result.success()).isTrue();
            // Unlimited sentinel balance - consumption tracked, never debited, never refused.
            assertThat(result.remainingCredits()).isEqualByComparingTo("999999999");
            // No subscription lookup happens in unlimited mode (gate is before the bucket logic).
            verify(subscriptionRepository, org.mockito.Mockito.never()).findActiveByUserIdForUpdate(any());
        }

        @Test
        @DisplayName("CE Free user passes the chat pre-flight unconditionally")
        void ceFreeChatPreflightAllowed() {
            // No subscription stub needed: canAfford returns true immediately in unlimited mode.
            assertThat(ce().canAfford(USER_ID, new BigDecimal("5.00"), "CHAT_CONVERSATION")).isTrue();
        }
    }
}
