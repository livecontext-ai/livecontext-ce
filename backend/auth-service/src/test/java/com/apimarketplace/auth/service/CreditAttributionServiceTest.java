package com.apimarketplace.auth.service;

import com.apimarketplace.auth.billing.CreditTierConstants;
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
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for CreditAttributionService.
 *
 * Credit model:
 * - Plans unlock features only, they do NOT grant credits (except FREE = 1000 via includedLlmTokens).
 * - Credits come from credit packs (tiers) via Stripe slider.
 * - attributeOnSubscription: grants pack credits OR plan-included credits (FREE only)
 * - attributeOnRenewal: resets balance + re-grants pack credits or plan credits (FREE only)
 * - handleCreditPackChange: grants full new pack credits (no reset, user keeps balance)
 *
 * SourceId is derived from subscription state (subscriptionId + currentPeriodStart):
 * - Initial: plan_sub_{subId}_init / pack_sub_{subId}_init
 * - Renewal: reset_sub_{subId}_{epochSec} / plan_sub_{subId}_{epochSec} / pack_sub_{subId}_{epochSec}
 * - Pack upgrade: pack_sub_{subId}_upgrade_{epochSec}
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CreditAttributionService Tests")
class CreditAttributionServiceTest {

    @Mock
    private CreditService creditService;

    @Mock
    private CreditLedgerRepository ledgerRepository;

    @Mock
    private SubscriptionRepository subscriptionRepository;

    @Mock
    private com.apimarketplace.auth.repository.PendingCreditUpgradeRepository pendingCreditUpgradeRepository;

    @InjectMocks
    private CreditAttributionService attributionService;

    @Captor
    private ArgumentCaptor<CreditLedgerEntry> ledgerEntryCaptor;

    // ===== Constants =====
    private static final Long USER_ID = 42L;
    private static final Long SUB_ID = 1L;
    private static final LocalDateTime PERIOD_START = LocalDateTime.of(2025, 1, 18, 0, 0, 0);
    // 2025-01-18T00:00:00 UTC = 1737158400 epoch seconds
    private static final String PERIOD_KEY = "1737158400";

    // ===== Helpers =====

    private Plan createPlan(String code, Long includedToolCredits) {
        return createPlan(code, includedToolCredits, null);
    }

    private Plan createPlan(String code, Long includedToolCredits, Long includedLlmTokens) {
        Plan plan = new Plan();
        plan.setId(1L);
        plan.setCode(code);
        plan.setName(code);
        plan.setIncludedToolCredits(includedToolCredits);
        plan.setIncludedLlmTokens(includedLlmTokens);
        return plan;
    }

    private Subscription createSubscription(Plan plan, int creditQuantity, BigDecimal remainingCredits) {
        return createSubscription(plan, creditQuantity, remainingCredits, "stripe");
    }

    private Subscription createSubscription(Plan plan, int creditQuantity, BigDecimal remainingCredits, String provider) {
        Subscription sub = new Subscription();
        sub.setId(SUB_ID);
        sub.setPlan(plan);
        sub.setCreditQuantity(creditQuantity);
        sub.setRemainingCredits(remainingCredits);
        sub.setCurrentPeriodStart(PERIOD_START);
        sub.setProvider(provider);
        return sub;
    }

    private void mockNoExistingLedger() {
        lenient().when(ledgerRepository.existsBySourceId(anyString())).thenReturn(false);
    }

    private void mockGrantSuccess() {
        lenient().when(creditService.grantCredits(anyLong(), any(BigDecimal.class), anyString(), anyString(), anyString()))
                .thenReturn(CreditConsumeResult.success(BigDecimal.ZERO, BigDecimal.ZERO));
    }

    // ===== attributeOnSubscription =====

    @Nested
    @DisplayName("attributeOnSubscription")
    class AttributeOnSubscription {

        @BeforeEach
        void setUp() {
            mockNoExistingLedger();
            mockGrantSuccess();
        }

        @Test
        @DisplayName("should grant plan-included credits for FREE plan (only plan with includedLlmTokens)")
        void shouldGrantPlanCreditsForFreePlan() {
            Plan free = createPlan("FREE", 1000L, 1000L);
            Subscription sub = createSubscription(free, 0, BigDecimal.ZERO, "internal");

            attributionService.attributeOnSubscription(USER_ID, sub, 0);

            verify(creditService).grantCredits(eq(USER_ID), eq(new BigDecimal("1000")),
                    eq("PURCHASE"), eq("plan_sub_1_init"), anyString());
        }

        @Test
        @DisplayName("STARTER with no pack - grants tier 0 credits (5K at $0)")
        void starterNoPack_grantsTier0Credits() {
            Plan starter = createPlan("STARTER", 25000L);
            Subscription sub = createSubscription(starter, 0, BigDecimal.ZERO);

            attributionService.attributeOnSubscription(USER_ID, sub, 0);

            verify(creditService).grantCredits(eq(USER_ID), eq(new BigDecimal("5000")),
                    eq("PURCHASE"), eq("pack_sub_1_init"), anyString());
        }

        @Test
        @DisplayName("should not grant any credits when internal plan has creditQuantity 0 and no includedLlmTokens")
        void shouldNotGrantWhenZeroCreditQuantityAndNoLlmTokens() {
            Plan creditPack = createPlan("CREDIT_PACK", 0L, null);
            Subscription sub = createSubscription(creditPack, 0, BigDecimal.ZERO, "internal");

            attributionService.attributeOnSubscription(USER_ID, sub, 0);

            verify(creditService, never()).grantCredits(anyLong(), any(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("should grant pack credits for tier 1 (creditQuantity=5 = 10K credits)")
        void shouldGrantPackCreditsTier1() {
            Plan starter = createPlan("STARTER", 25000L);
            Subscription sub = createSubscription(starter, 10, BigDecimal.ZERO);

            attributionService.attributeOnSubscription(USER_ID, sub, 10);

            verify(creditService).grantCredits(eq(USER_ID), eq(new BigDecimal("10000")),
                    eq("PURCHASE"), eq("pack_sub_1_init"), anyString());
        }

        @Test
        @DisplayName("should grant pack credits for tier 2 (creditQuantity=22 = 25K credits)")
        void shouldGrantPackCreditsTier2() {
            Plan pro = createPlan("PRO", 100000L);
            Subscription sub = createSubscription(pro, 22, BigDecimal.ZERO);

            attributionService.attributeOnSubscription(USER_ID, sub, 22);

            verify(creditService).grantCredits(eq(USER_ID), eq(new BigDecimal("25000")),
                    eq("PURCHASE"), eq("pack_sub_1_init"), anyString());
        }

        @Test
        @DisplayName("should grant pack credits for tier 4 (creditQuantity=80 = 100K credits)")
        void shouldGrantPackCreditsTier4() {
            Plan pro = createPlan("PRO", 100000L);
            Subscription sub = createSubscription(pro, 80, BigDecimal.ZERO);

            attributionService.attributeOnSubscription(USER_ID, sub, 80);

            verify(creditService).grantCredits(eq(USER_ID), eq(new BigDecimal("100000")),
                    eq("PURCHASE"), eq("pack_sub_1_init"), anyString());
        }

        @Test
        @DisplayName("should grant pack credits (not plan credits) when creditQuantity > 0 even if plan has includedLlmTokens")
        void shouldGrantPackCreditsWhenCreditQuantityPositive() {
            Plan free = createPlan("FREE", 1000L, 1000L);
            Subscription sub = createSubscription(free, 10, BigDecimal.ZERO);

            attributionService.attributeOnSubscription(USER_ID, sub, 10);

            // Exactly one grant: pack credits only (plan credits skipped because pack takes priority)
            verify(creditService, times(1)).grantCredits(anyLong(), any(), anyString(), anyString(), anyString());
            verify(creditService).grantCredits(eq(USER_ID), any(),
                    eq("PURCHASE"), eq("pack_sub_1_init"), anyString());
        }

        @Test
        @DisplayName("should not grant pack credits when creditQuantity is negative")
        void shouldNotGrantPackWhenNegative() {
            Plan pro = createPlan("PRO", 100000L);
            Subscription sub = createSubscription(pro, 0, BigDecimal.ZERO);

            attributionService.attributeOnSubscription(USER_ID, sub, -1);

            verify(creditService, never()).grantCredits(anyLong(), any(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("FREE plan with no pack - grants plan-included credits (1000)")
        void freePlanNoPack() {
            Plan free = createPlan("FREE", 5000L, 1000L);
            Subscription sub = createSubscription(free, 0, BigDecimal.ZERO, "internal");

            attributionService.attributeOnSubscription(USER_ID, sub, 0);

            verify(creditService).grantCredits(eq(USER_ID), eq(new BigDecimal("1000")),
                    eq("PURCHASE"), eq("plan_sub_1_init"), anyString());
        }

        @Test
        @DisplayName("ENTERPRISE plan with no pack - grants tier 0 credits (5K at $0)")
        void enterprisePlanNoPack_grantsTier0Credits() {
            Plan enterprise = createPlan("ENTERPRISE", null);
            Subscription sub = createSubscription(enterprise, 0, BigDecimal.ZERO);

            attributionService.attributeOnSubscription(USER_ID, sub, 0);

            verify(creditService).grantCredits(eq(USER_ID), eq(new BigDecimal("5000")),
                    eq("PURCHASE"), eq("pack_sub_1_init"), anyString());
        }

        @Test
        @DisplayName("admin comp PRO (internal, no pack) - grants the tier-0 5K base, not nothing")
        void compInternalPro_grantsTier0Base() {
            // A comp Pro is provider='internal' with no Stripe pack. Pre-fix the gate keyed on
            // !isPaidSubscription and this internal row would have fallen to grantPlanCredits,
            // and since PRO has no includedLlmTokens it would have granted NOTHING.
            Plan pro = createPlan("PRO", 100000L, null);
            Subscription sub = createSubscription(pro, 0, BigDecimal.ZERO, "internal");

            attributionService.attributeOnSubscription(USER_ID, sub, 0);

            verify(creditService).grantCredits(eq(USER_ID), eq(new BigDecimal("5000")),
                    eq("PURCHASE"), eq("pack_sub_1_init"), anyString());
        }

        @Test
        @DisplayName("admin comp STARTER (internal, no pack) - grants the tier-0 5K base")
        void compInternalStarter_grantsTier0Base() {
            Plan starter = createPlan("STARTER", 25000L, null);
            Subscription sub = createSubscription(starter, 0, BigDecimal.ZERO, "internal");

            attributionService.attributeOnSubscription(USER_ID, sub, 0);

            verify(creditService).grantCredits(eq(USER_ID), eq(new BigDecimal("5000")),
                    eq("PURCHASE"), eq("pack_sub_1_init"), anyString());
        }
    }

    // ===== attributeOnRenewal =====

    @Nested
    @DisplayName("attributeOnRenewal")
    class AttributeOnRenewal {

        @BeforeEach
        void setUp() {
            mockNoExistingLedger();
            mockGrantSuccess();
        }

        @Test
        @DisplayName("should reset balance and re-grant pack credits on renewal")
        void shouldResetAndRegrantPackOnRenewal() {
            Plan pro = createPlan("PRO", 100000L);
            Subscription sub = createSubscription(pro, 42, new BigDecimal("45000"));

            attributionService.attributeOnRenewal(USER_ID, sub);

            // Reset
            verify(subscriptionRepository).save(sub);
            assertThat(sub.getRemainingCredits()).isEqualByComparingTo(BigDecimal.ZERO);

            verify(ledgerRepository).save(ledgerEntryCaptor.capture());
            CreditLedgerEntry resetEntry = ledgerEntryCaptor.getValue();
            assertThat(resetEntry.getSourceType()).isEqualTo("PLAN_RESET");
            assertThat(resetEntry.getSourceId()).isEqualTo("reset_sub_1_" + PERIOD_KEY);
            assertThat(resetEntry.getAmount()).isEqualByComparingTo(new BigDecimal("-45000"));
            assertThat(resetEntry.getBalanceAfter()).isEqualByComparingTo(BigDecimal.ZERO);

            // Re-grant pack credits (tier 3 = 50K)
            verify(creditService).grantCredits(eq(USER_ID), eq(new BigDecimal("50000")),
                    eq("PURCHASE"), eq("pack_sub_1_" + PERIOD_KEY), anyString());
        }

        @Test
        @DisplayName("should reset negative balance on renewal")
        void shouldResetNegativeBalanceOnRenewal() {
            Plan starter = createPlan("STARTER", 25000L);
            Subscription sub = createSubscription(starter, 10, new BigDecimal("-5000"));

            attributionService.attributeOnRenewal(USER_ID, sub);

            verify(subscriptionRepository).save(sub);
            assertThat(sub.getRemainingCredits()).isEqualByComparingTo(BigDecimal.ZERO);

            verify(ledgerRepository).save(ledgerEntryCaptor.capture());
            assertThat(ledgerEntryCaptor.getValue().getAmount()).isEqualByComparingTo(new BigDecimal("5000"));
        }

        @Test
        @DisplayName("should skip reset when balance is already zero but still re-grant pack")
        void shouldSkipResetWhenBalanceAlreadyZero() {
            Plan pro = createPlan("PRO", 100000L);
            Subscription sub = createSubscription(pro, 80, BigDecimal.ZERO);

            attributionService.attributeOnRenewal(USER_ID, sub);

            verify(subscriptionRepository, never()).save(any(Subscription.class));
            verify(ledgerRepository, never()).save(any(CreditLedgerEntry.class));

            // Pack credits still granted (tier 4 = 100K)
            verify(creditService).grantCredits(eq(USER_ID), eq(new BigDecimal("100000")),
                    eq("PURCHASE"), eq("pack_sub_1_" + PERIOD_KEY), anyString());
        }

        @Test
        @DisplayName("should reset and grant plan credits on FREE plan renewal (creditQuantity=0)")
        void shouldResetAndGrantPlanCreditsForFreePlan() {
            Plan free = createPlan("FREE", 1000L, 1000L);
            Subscription sub = createSubscription(free, 0, new BigDecimal("500"), "internal");

            attributionService.attributeOnRenewal(USER_ID, sub);

            // Reset still happens
            verify(subscriptionRepository).save(sub);
            assertThat(sub.getRemainingCredits()).isEqualByComparingTo(BigDecimal.ZERO);

            // Plan credits granted (FREE = 1000)
            verify(creditService).grantCredits(eq(USER_ID), eq(new BigDecimal("1000")),
                    eq("PURCHASE"), eq("plan_sub_1_" + PERIOD_KEY), anyString());
        }

        @Test
        @DisplayName("admin comp PRO (internal) renewal - resets balance and re-grants the 5K base")
        void compInternalPro_renewal_resetsAndGrantsTier0Base() {
            Plan pro = createPlan("PRO", 100000L, null);
            Subscription sub = createSubscription(pro, 0, new BigDecimal("1200"), "internal");

            attributionService.attributeOnRenewal(USER_ID, sub);

            // Reset to zero
            verify(subscriptionRepository).save(sub);
            assertThat(sub.getRemainingCredits()).isEqualByComparingTo(BigDecimal.ZERO);

            // Re-grant the tier-0 5K base (comp cap), via the pack source-id
            verify(creditService).grantCredits(eq(USER_ID), eq(new BigDecimal("5000")),
                    eq("PURCHASE"), eq("pack_sub_1_" + PERIOD_KEY), anyString());
        }

        @Test
        @DisplayName("should reset but not grant when internal plan has creditQuantity 0 and no includedLlmTokens")
        void shouldResetButNotGrantWhenZeroAndNoLlmTokens() {
            Plan creditPack = createPlan("CREDIT_PACK", 0L, null);
            Subscription sub = createSubscription(creditPack, 0, new BigDecimal("10000"), "internal");

            attributionService.attributeOnRenewal(USER_ID, sub);

            // Reset still happens
            verify(subscriptionRepository).save(sub);
            assertThat(sub.getRemainingCredits()).isEqualByComparingTo(BigDecimal.ZERO);

            // No credits granted
            verify(creditService, never()).grantCredits(anyLong(), any(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("should return early when subscription has null plan")
        void shouldReturnEarlyWhenNullPlan() {
            Subscription sub = createSubscription(null, 0, new BigDecimal("10000"));
            sub.setPlan(null);

            attributionService.attributeOnRenewal(USER_ID, sub);

            verify(subscriptionRepository, never()).save(any(Subscription.class));
            verify(ledgerRepository, never()).save(any(CreditLedgerEntry.class));
            verify(creditService, never()).grantCredits(anyLong(), any(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("should handle null creditQuantity on subscription as 0 - grants plan credits if available (FREE)")
        void shouldHandleNullCreditQuantityAsZero() {
            Plan free = createPlan("FREE", 1000L, 1000L);
            Subscription sub = createSubscription(free, 0, BigDecimal.ZERO, "internal");
            sub.setCreditQuantity(null);

            attributionService.attributeOnRenewal(USER_ID, sub);

            // Plan credits granted (creditQuantity treated as 0, falls through to plan credits)
            verify(creditService).grantCredits(eq(USER_ID), eq(new BigDecimal("1000")),
                    eq("PURCHASE"), eq("plan_sub_1_" + PERIOD_KEY), anyString());
        }
    }

    // ===== handleCreditPackChange =====

    @Nested
    @DisplayName("handleCreditPackChange")
    class HandleCreditPackChange {

        @BeforeEach
        void setUp() {
            mockNoExistingLedger();
            mockGrantSuccess();
        }

        @Test
        @DisplayName("should grant full new pack credits on upgrade (tier 1 -> tier 2)")
        void shouldGrantFullNewPackOnUpgrade() {
            Plan starter = createPlan("STARTER", 25000L);
            Subscription sub = createSubscription(starter, 10, BigDecimal.ZERO);

            attributionService.handleCreditPackChange(USER_ID, sub, 10, 22);

            // Full new pack credits (tier 2 = 25K), no reset
            verify(creditService).grantCredits(eq(USER_ID), eq(new BigDecimal("25000")),
                    eq("PURCHASE"), eq("pack_sub_1_upgrade_" + PERIOD_KEY), anyString());
            // No subscription lookup, no reset
            verify(subscriptionRepository, never()).findActiveByUserIdForUpdate(anyLong());
            verify(subscriptionRepository, never()).save(any(Subscription.class));
            verify(ledgerRepository, never()).save(any(CreditLedgerEntry.class));
        }

        @Test
        @DisplayName("should grant full new pack credits for tier 4 (100K credits)")
        void shouldGrantFullNewPackTier4() {
            Plan pro = createPlan("PRO", 100000L);
            Subscription sub = createSubscription(pro, 10, BigDecimal.ZERO);

            attributionService.handleCreditPackChange(USER_ID, sub, 10, 80);

            verify(creditService).grantCredits(eq(USER_ID), eq(new BigDecimal("100000")),
                    eq("PURCHASE"), eq("pack_sub_1_upgrade_" + PERIOD_KEY), anyString());
        }

        @Test
        @DisplayName("should not grant when new creditQuantity is 0")
        void shouldNotGrantWhenNewIsZero() {
            Plan starter = createPlan("STARTER", 25000L);
            Subscription sub = createSubscription(starter, 10, BigDecimal.ZERO);

            attributionService.handleCreditPackChange(USER_ID, sub, 10, 0);

            verify(creditService, never()).grantCredits(anyLong(), any(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("should not grant when new creditQuantity is negative")
        void shouldNotGrantWhenNewIsNegative() {
            Plan pro = createPlan("PRO", 100000L);
            Subscription sub = createSubscription(pro, 10, BigDecimal.ZERO);

            attributionService.handleCreditPackChange(USER_ID, sub, 10, -1);

            verify(creditService, never()).grantCredits(anyLong(), any(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("should grant pack credits for first pack addition (0 -> tier 3)")
        void shouldGrantOnFirstPack() {
            Plan pro = createPlan("PRO", 100000L);
            Subscription sub = createSubscription(pro, 0, BigDecimal.ZERO);

            attributionService.handleCreditPackChange(USER_ID, sub, 0, 42);

            verify(creditService).grantCredits(eq(USER_ID), eq(new BigDecimal("50000")),
                    eq("PURCHASE"), eq("pack_sub_1_upgrade_" + PERIOD_KEY), anyString());
        }

        @Test
        @DisplayName("should grant for tier 1 pack (10K credits)")
        void shouldGrantTier1Pack() {
            Plan starter = createPlan("STARTER", 25000L);
            Subscription sub = createSubscription(starter, 0, BigDecimal.ZERO);

            attributionService.handleCreditPackChange(USER_ID, sub, 0, 10);

            verify(creditService).grantCredits(eq(USER_ID), eq(new BigDecimal("10000")),
                    eq("PURCHASE"), eq("pack_sub_1_upgrade_" + PERIOD_KEY), anyString());
        }
    }

    // ===== Idempotence =====

    @Nested
    @DisplayName("Idempotence")
    class Idempotence {

        @Test
        @DisplayName("should not double-grant pack credits on subscription (idempotent)")
        void shouldNotDoubleGrantPackCredits() {
            when(ledgerRepository.existsBySourceId("pack_sub_1_init")).thenReturn(true);
            Plan pro = createPlan("PRO", 100000L);
            Subscription sub = createSubscription(pro, 35, BigDecimal.ZERO);

            attributionService.attributeOnSubscription(USER_ID, sub, 35);

            verify(creditService, never()).grantCredits(anyLong(), any(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("should not double-grant plan credits on subscription (idempotent)")
        void shouldNotDoubleGrantPlanCredits() {
            when(ledgerRepository.existsBySourceId("plan_sub_1_init")).thenReturn(true);
            Plan free = createPlan("FREE", 5000L, 1000L);
            Subscription sub = createSubscription(free, 0, BigDecimal.ZERO, "internal");

            attributionService.attributeOnSubscription(USER_ID, sub, 0);

            verify(creditService, never()).grantCredits(anyLong(), any(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("should not double-reset balance on renewal (idempotent)")
        void shouldNotDoubleResetBalance() {
            when(ledgerRepository.existsBySourceId("reset_sub_1_" + PERIOD_KEY)).thenReturn(true);
            when(ledgerRepository.existsBySourceId("pack_sub_1_" + PERIOD_KEY)).thenReturn(true);
            Plan pro = createPlan("PRO", 100000L);
            Subscription sub = createSubscription(pro, 35, new BigDecimal("50000"));

            attributionService.attributeOnRenewal(USER_ID, sub);

            verify(subscriptionRepository, never()).save(any(Subscription.class));
            verify(ledgerRepository, never()).save(any(CreditLedgerEntry.class));
            verify(creditService, never()).grantCredits(anyLong(), any(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("should not double-grant pack credits on pack change (idempotent)")
        void shouldNotDoubleGrantOnPackChange() {
            when(ledgerRepository.existsBySourceId("pack_sub_1_upgrade_" + PERIOD_KEY)).thenReturn(true);
            Plan pro = createPlan("PRO", 100000L);
            Subscription sub = createSubscription(pro, 10, BigDecimal.ZERO);

            attributionService.handleCreditPackChange(USER_ID, sub, 10, 35);

            verify(creditService, never()).grantCredits(anyLong(), any(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("different subscription IDs produce different sourceIds (no collision)")
        void differentSubscriptionsNoCrossCollision() {
            // Sub 1 already granted
            lenient().when(ledgerRepository.existsBySourceId("pack_sub_1_init")).thenReturn(true);
            // Sub 2 not yet granted
            when(ledgerRepository.existsBySourceId("pack_sub_2_init")).thenReturn(false);
            mockGrantSuccess();

            Plan pro = createPlan("PRO", 100000L);
            Subscription sub2 = createSubscription(pro, 35, BigDecimal.ZERO);
            sub2.setId(2L);

            attributionService.attributeOnSubscription(USER_ID, sub2, 35);

            verify(creditService).grantCredits(eq(USER_ID), any(), eq("PURCHASE"),
                    eq("pack_sub_2_init"), anyString());
        }

        @Test
        @DisplayName("reset_ and pack_ prefixes are independent on renewal")
        void resetAndPackPrefixesIndependent() {
            // Reset already done, but pack not yet
            when(ledgerRepository.existsBySourceId("reset_sub_1_" + PERIOD_KEY)).thenReturn(true);
            when(ledgerRepository.existsBySourceId("pack_sub_1_" + PERIOD_KEY)).thenReturn(false);
            mockGrantSuccess();

            Plan pro = createPlan("PRO", 100000L);
            Subscription sub = createSubscription(pro, 35, new BigDecimal("50000"));

            attributionService.attributeOnRenewal(USER_ID, sub);

            // Reset skipped (already done)
            verify(subscriptionRepository, never()).save(any(Subscription.class));
            verify(ledgerRepository, never()).save(any(CreditLedgerEntry.class));
            // But pack still granted
            verify(creditService).grantCredits(eq(USER_ID), any(), eq("PURCHASE"),
                    eq("pack_sub_1_" + PERIOD_KEY), anyString());
        }
    }

    // ===== Edge Cases =====

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @BeforeEach
        void setUp() {
            mockNoExistingLedger();
            mockGrantSuccess();
        }

        @Test
        @DisplayName("PRO with no pack - grants tier 0 credits (5K at $0)")
        void proNoPack_grantsTier0Credits() {
            Plan pro = createPlan("PRO", 100000L);
            Subscription sub = createSubscription(pro, 0, BigDecimal.ZERO);

            attributionService.attributeOnSubscription(USER_ID, sub, 0);

            verify(creditService).grantCredits(eq(USER_ID), eq(new BigDecimal("5000")),
                    eq("PURCHASE"), eq("pack_sub_1_init"), anyString());
        }

        @Test
        @DisplayName("should handle unknown creditQuantity (defaults to tier 0 = 5K credits)")
        void shouldHandleUnknownCreditQuantity() {
            Plan pro = createPlan("PRO", 100000L);
            Subscription sub = createSubscription(pro, 0, BigDecimal.ZERO);

            attributionService.attributeOnSubscription(USER_ID, sub, 999);

            verify(creditService).grantCredits(eq(USER_ID), eq(new BigDecimal("5000")),
                    eq("PURCHASE"), eq("pack_sub_1_init"), anyString());
        }

        @Test
        @DisplayName("should handle all credit tier indices correctly")
        void shouldHandleAllCreditTiers() {
            Plan pro = createPlan("PRO", 100000L);

            for (int i = 0; i < CreditTierConstants.CREDIT_COSTS.length; i++) {
                int cost = CreditTierConstants.CREDIT_COSTS[i];
                if (cost == 0) continue;

                reset(creditService, ledgerRepository);
                mockNoExistingLedger();
                mockGrantSuccess();

                // Use different subscription IDs to get unique sourceIds
                Subscription sub = createSubscription(pro, cost, BigDecimal.ZERO);
                sub.setId((long) (i + 1));

                attributionService.attributeOnSubscription(USER_ID, sub, cost);

                int expectedCredits = CreditTierConstants.CREDIT_TIERS[i];
                verify(creditService).grantCredits(eq(USER_ID), eq(new BigDecimal(String.valueOf(expectedCredits))),
                        eq("PURCHASE"), eq("pack_sub_" + (i + 1) + "_init"), anyString());
            }
        }

        @Test
        @DisplayName("should handle negative creditQuantity gracefully (treated as no pack)")
        void shouldHandleNegativeCreditQuantity() {
            Plan pro = createPlan("PRO", 100000L);
            Subscription sub = createSubscription(pro, 0, BigDecimal.ZERO);

            attributionService.attributeOnSubscription(USER_ID, sub, -1);

            verify(creditService, never()).grantCredits(anyLong(), any(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("renewal with null remainingCredits treated as zero (no reset needed)")
        void renewalNullRemainingCredits() {
            Plan pro = createPlan("PRO", 100000L);
            Subscription sub = createSubscription(pro, 35, null);

            attributionService.attributeOnRenewal(USER_ID, sub);

            // Null balance → treated as zero, no reset
            verify(subscriptionRepository, never()).save(any(Subscription.class));
            verify(ledgerRepository, never()).save(any(CreditLedgerEntry.class));

            // Pack still granted
            verify(creditService).grantCredits(eq(USER_ID), any(), eq("PURCHASE"), anyString(), anyString());
        }
    }

    // ===== Grant Failure Handling =====

    @Nested
    @DisplayName("Grant Failure Handling")
    class GrantFailureHandling {

        @BeforeEach
        void setUp() {
            mockNoExistingLedger();
        }

        @Test
        @DisplayName("should throw IllegalStateException when pack grant fails on subscription")
        void shouldThrowOnPackGrantFailureSubscription() {
            when(creditService.grantCredits(anyLong(), any(BigDecimal.class), anyString(), anyString(), anyString()))
                    .thenReturn(CreditConsumeResult.noSubscription());

            Plan pro = createPlan("PRO", 100000L);
            Subscription sub = createSubscription(pro, 10, BigDecimal.ZERO);

            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () ->
                    attributionService.attributeOnSubscription(USER_ID, sub, 10));
        }

        @Test
        @DisplayName("should throw IllegalStateException when pack grant fails on renewal (after reset)")
        void shouldThrowOnPackGrantFailureRenewal() {
            when(creditService.grantCredits(anyLong(), any(BigDecimal.class), anyString(), anyString(), anyString()))
                    .thenReturn(CreditConsumeResult.noSubscription());

            Plan pro = createPlan("PRO", 100000L);
            Subscription sub = createSubscription(pro, 35, new BigDecimal("50000"));

            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () ->
                    attributionService.attributeOnRenewal(USER_ID, sub));

            // Reset should have happened before the failure
            assertThat(sub.getRemainingCredits()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("should throw IllegalStateException when pack grant fails on pack change")
        void shouldThrowOnPackGrantFailurePackChange() {
            when(creditService.grantCredits(anyLong(), any(BigDecimal.class), anyString(), anyString(), anyString()))
                    .thenReturn(CreditConsumeResult.noSubscription());

            Plan pro = createPlan("PRO", 100000L);
            Subscription sub = createSubscription(pro, 10, BigDecimal.ZERO);

            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () ->
                    attributionService.handleCreditPackChange(USER_ID, sub, 10, 35));
        }

        @Test
        @DisplayName("should not attempt grant when internal plan has creditQuantity 0 and no includedLlmTokens")
        void shouldNotAttemptGrantWhenZeroPackAndNoLlmTokens() {
            Plan pack = createPlan("CREDIT_PACK", 0L, null);
            Subscription sub = createSubscription(pack, 0, BigDecimal.ZERO, "internal");

            // No exception because no grant is attempted
            attributionService.attributeOnSubscription(USER_ID, sub, 0);

            verify(creditService, never()).grantCredits(anyLong(), any(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("should throw when grantCredits returns insufficientCredits")
        void insufficientCreditsResultThrows() {
            when(creditService.grantCredits(anyLong(), any(BigDecimal.class), anyString(), anyString(), anyString()))
                    .thenReturn(CreditConsumeResult.insufficientCredits(BigDecimal.ZERO, BigDecimal.TEN));

            Plan pro = createPlan("PRO", 100000L);
            Subscription sub = createSubscription(pro, 10, BigDecimal.ZERO);

            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () ->
                    attributionService.attributeOnSubscription(USER_ID, sub, 10));
        }
    }

    // ===== Tier Validation Warning =====

    @Nested
    @DisplayName("Tier Validation")
    class TierValidation {

        @BeforeEach
        void setUp() {
            mockNoExistingLedger();
            mockGrantSuccess();
        }

        @Test
        @DisplayName("should still grant credits even when tier validation warns (Stripe already charged)")
        void shouldGrantDespiteTierValidationWarning() {
            Plan starter = createPlan("STARTER", 25000L);
            Subscription sub = createSubscription(starter, 500, BigDecimal.ZERO);

            // High tier possibly not valid for STARTER
            attributionService.attributeOnSubscription(USER_ID, sub, 500);

            // Should still grant (Stripe already charged)
            verify(creditService).grantCredits(eq(USER_ID), any(), eq("PURCHASE"), eq("pack_sub_1_init"), anyString());
        }
    }

    // ===== DataIntegrityViolation Handling =====

    @Nested
    @DisplayName("DataIntegrityViolation Handling")
    class DataIntegrityViolationHandling {

        @BeforeEach
        void setUp() {
            mockNoExistingLedger();
        }

        @Test
        @DisplayName("should swallow DataIntegrityViolationException on subscription (idempotent)")
        void shouldSwallowDuplicateOnSubscription() {
            when(creditService.grantCredits(anyLong(), any(BigDecimal.class), anyString(), anyString(), anyString()))
                    .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate"));

            Plan pro = createPlan("PRO", 100000L);
            Subscription sub = createSubscription(pro, 10, BigDecimal.ZERO);

            // Should not throw
            attributionService.attributeOnSubscription(USER_ID, sub, 10);
        }

        @Test
        @DisplayName("should swallow DataIntegrityViolationException on renewal (idempotent)")
        void shouldSwallowDuplicateOnRenewal() {
            when(creditService.grantCredits(anyLong(), any(BigDecimal.class), anyString(), anyString(), anyString()))
                    .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate"));

            Plan pro = createPlan("PRO", 100000L);
            Subscription sub = createSubscription(pro, 35, BigDecimal.ZERO);

            attributionService.attributeOnRenewal(USER_ID, sub);
        }

        @Test
        @DisplayName("should swallow DataIntegrityViolationException on pack change (idempotent)")
        void shouldSwallowDuplicateOnPackChange() {
            when(creditService.grantCredits(anyLong(), any(BigDecimal.class), anyString(), anyString(), anyString()))
                    .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate"));

            Plan pro = createPlan("PRO", 100000L);
            Subscription sub = createSubscription(pro, 10, BigDecimal.ZERO);

            attributionService.handleCreditPackChange(USER_ID, sub, 10, 35);
        }
    }

    // ===== handleCreditUpgradeInvoicePaid (Option A flow) =====

    @Nested
    @DisplayName("handleCreditUpgradeInvoicePaid - Option A grant routing")
    class HandleCreditUpgradeInvoicePaid {

        @BeforeEach
        void setUp() {
            mockNoExistingLedger();
            mockGrantSuccess();
        }

        private com.apimarketplace.auth.domain.PendingCreditUpgrade newPending(String status, int targetQty) {
            com.apimarketplace.auth.domain.PendingCreditUpgrade p = new com.apimarketplace.auth.domain.PendingCreditUpgrade();
            p.setUserId(USER_ID);
            p.setSubscriptionId(SUB_ID);
            p.setProviderSubscriptionId("sub_test_123");
            p.setStripeInvoiceId("in_test_upgrade_xyz");
            p.setStripeInvoiceItemId("ii_test_xyz");
            p.setTargetTierIndex(3);
            p.setTargetCreditQuantity(targetQty);
            p.setTargetCreditPriceId("price_pack_test");
            p.setStatus(status);
            return p;
        }

        @Test
        @DisplayName("grants full new pack credits with stripe_invoice source_id")
        void grantsFullPackWithInvoiceSourceId() {
            Plan pro = createPlan("PRO", 100000L);
            Subscription sub = createSubscription(pro, 10, BigDecimal.valueOf(8000)); // 8K leftovers
            when(subscriptionRepository.findById(SUB_ID)).thenReturn(java.util.Optional.of(sub));

            attributionService.handleCreditUpgradeInvoicePaid(
                    newPending(com.apimarketplace.auth.domain.PendingCreditUpgrade.STATUS_PAID_SUB_PENDING, 35));

            ArgumentCaptor<String> sourceIdCaptor = ArgumentCaptor.forClass(String.class);
            verify(creditService).grantCredits(eq(USER_ID), any(BigDecimal.class), eq("PURCHASE"),
                    sourceIdCaptor.capture(), anyString());
            assertThat(sourceIdCaptor.getValue()).isEqualTo("stripe_invoice:in_test_upgrade_xyz");
        }

        @Test
        @DisplayName("refuses to grant when pending status is FAILED")
        void refusesGrantWhenFailed() {
            attributionService.handleCreditUpgradeInvoicePaid(
                    newPending(com.apimarketplace.auth.domain.PendingCreditUpgrade.STATUS_FAILED, 35));

            verifyNoInteractions(creditService);
        }

        @Test
        @DisplayName("idempotent on duplicate invoice.paid (V6 UNIQUE swallows)")
        void idempotentOnDuplicateInvoicePaid() {
            Plan pro = createPlan("PRO", 100000L);
            Subscription sub = createSubscription(pro, 35, BigDecimal.ZERO);
            when(subscriptionRepository.findById(SUB_ID)).thenReturn(java.util.Optional.of(sub));
            when(creditService.grantCredits(anyLong(), any(BigDecimal.class), anyString(), anyString(), anyString()))
                    .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate source_id"));

            // Must not propagate - DataIntegrityViolationException is the V6 UNIQUE absorbing a retry.
            attributionService.handleCreditUpgradeInvoicePaid(
                    newPending(com.apimarketplace.auth.domain.PendingCreditUpgrade.STATUS_PAID_SUB_PENDING, 35));
        }

        @Test
        @DisplayName("no-op when subscription has been deleted between webhook delivery and lookup")
        void noopWhenSubscriptionMissing() {
            when(subscriptionRepository.findById(SUB_ID)).thenReturn(java.util.Optional.empty());

            attributionService.handleCreditUpgradeInvoicePaid(
                    newPending(com.apimarketplace.auth.domain.PendingCreditUpgrade.STATUS_PAID_SUB_PENDING, 35));

            verifyNoInteractions(creditService);
        }

        @Test
        @DisplayName("null pending is silently ignored - defensive")
        void nullPendingIgnored() {
            attributionService.handleCreditUpgradeInvoicePaid(null);
            verifyNoInteractions(creditService);
        }
    }
}
