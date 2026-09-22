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
import java.time.ZoneOffset;
import java.util.Optional;

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
        sub.setCurrentPeriodEnd(PERIOD_START.plusMonths(1));
        sub.setProvider(provider);
        // The internal renewal re-validates status under the lock (a Stripe upgrade cancels the
        // internal sibling), so the fixture must carry a realistic one.
        sub.setStatus("active");
        lastSubscription = sub;
        return sub;
    }

    /** Set by {@link #createSubscription} so {@link #mockManagedRow()} can hand the same
     *  instance back as the "live row" the service re-reads under lock. */
    private Subscription lastSubscription;

    /**
     * Stub the lock-and-reload the service performs before touching a subscription.
     * In a unit test the live row IS the instance the test built, so the observable
     * behaviour matches what these tests asserted before that reload existed - while now
     * failing loudly if the reload is ever dropped (an unresolvable row aborts the renewal).
     */
    private void mockManagedRow() {
        // anyLong(), not SUB_ID: a future test using another id would otherwise get an unstubbed
        // null and NPE inside the service instead of failing on its own assertion.
        lenient().when(subscriptionRepository.findByIdForUpdate(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(lastSubscription));
    }

    /**
     * Class-level, NOT per nested class: leaving it out anywhere silently routes those tests
     * down the unresolvable-row branch, where the service aborts. They would still be green
     * (nothing is asserted about the grant in some of them) while covering none of the code the
     * renewal actually runs. A test that stops exercising its subject without failing is worse
     * than no test.
     */
    @BeforeEach
    void stubRowResolution() {
        mockManagedRow();
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

        @Test
        @DisplayName("resolves the row under lock instead of trusting the caller's entity")
        void resolvesTheRowUnderLock() {
            Plan free = createPlan("FREE", 1000L, 1000L);
            Subscription sub = createSubscription(free, 0, BigDecimal.ZERO, "internal");

            attributionService.attributeOnRenewal(USER_ID, sub);

            verify(subscriptionRepository).findByIdForUpdate(SUB_ID);
        }

        @Test
        @DisplayName("an unresolvable row aborts the Stripe path too, instead of writing through a detached copy")
        void unresolvableRowAbortsEvenWithoutAdvancingThePeriod() {
            Plan free = createPlan("FREE", 1000L, 1000L);
            Subscription sub = createSubscription(free, 0, new BigDecimal("500"), "internal");
            lastSubscription = null; // row not addressable

            attributionService.attributeOnRenewal(USER_ID, sub);

            // This path still WRITES: resetBalance zeroes the balance and saves. Continuing on
            // the caller's copy would merge every stale column back over the row - and if the
            // row is really gone, re-insert it as a brand-new subscription.
            verify(subscriptionRepository, never()).save(any(Subscription.class));
            verify(ledgerRepository, never()).save(any(CreditLedgerEntry.class));
            verify(creditService, never()).grantCredits(anyLong(), any(), anyString(), anyString(), anyString());
            assertThat(sub.getRemainingCredits()).isEqualByComparingTo(new BigDecimal("500"));
        }

        @Test
        @DisplayName("a null subscription is refused loudly instead of throwing deep in the call chain")
        void nullSubscriptionIsRefused() {
            attributionService.attributeOnRenewal(USER_ID, null);

            verify(creditService, never()).grantCredits(anyLong(), any(), anyString(), anyString(), anyString());
            verify(ledgerRepository, never()).save(any(CreditLedgerEntry.class));
        }

        @Test
        @DisplayName("aborts without granting when the row cannot be resolved for update")
        void abortsWhenRowUnresolvable() {
            Plan free = createPlan("FREE", 1000L, 1000L);
            Subscription sub = createSubscription(free, 0, new BigDecimal("500"), "internal");
            // The row vanished (or was never persisted) between selection and lock. Continuing
            // on the caller's detached copy would write nothing and re-grant on every pass.
            lastSubscription = null;

            attributionService.attributeOnRenewal(USER_ID, sub, LocalDateTime.now());

            verify(creditService, never()).grantCredits(anyLong(), any(), anyString(), anyString(), anyString());
            verify(ledgerRepository, never()).save(any(CreditLedgerEntry.class));
            assertThat(sub.getCurrentPeriodStart()).isEqualTo(PERIOD_START);
        }
    }

    // ===== attributeOnRenewal(.., newPeriodStart) - the internal scheduler contract =====

    @Nested
    @DisplayName("attributeOnRenewal with a new period start")
    class AttributeOnRenewalAdvancingThePeriod {

        private static final LocalDateTime NEW_PERIOD_START = LocalDateTime.of(2025, 3, 1, 13, 0, 0);
        private static final String NEW_PERIOD_KEY =
                String.valueOf(NEW_PERIOD_START.toEpochSecond(ZoneOffset.UTC));

        @BeforeEach
        void setUp() {
            mockNoExistingLedger();
            mockGrantSuccess();
        }

        @Test
        @DisplayName("advances the period by one month on the resolved row")
        void advancesThePeriod() {
            Plan free = createPlan("FREE", 1000L, 1000L);
            Subscription sub = createSubscription(free, 0, BigDecimal.ZERO, "internal");

            attributionService.attributeOnRenewal(USER_ID, sub, NEW_PERIOD_START);

            assertThat(sub.getCurrentPeriodStart()).isEqualTo(NEW_PERIOD_START);
            assertThat(sub.getCurrentPeriodEnd()).isEqualTo(NEW_PERIOD_START.plusMonths(1));
        }

        @Test
        @DisplayName("keys the grant on the NEW period, never on the consumed previous one")
        void keysTheGrantOnTheNewPeriod() {
            Plan free = createPlan("FREE", 1000L, 1000L);
            Subscription sub = createSubscription(free, 0, new BigDecimal("500"), "internal");

            attributionService.attributeOnRenewal(USER_ID, sub, NEW_PERIOD_START);

            // The defect: deriving the key from the OLD period re-mints an id an admin plan
            // grant already consumed, so existsBySourceId skips and the user gets nothing.
            verify(creditService).grantCredits(eq(USER_ID), eq(new BigDecimal("1000")),
                    eq("PURCHASE"), eq("plan_sub_1_" + NEW_PERIOD_KEY), anyString());
            verify(creditService, never()).grantCredits(anyLong(), any(), anyString(),
                    eq("plan_sub_1_" + PERIOD_KEY), anyString());

            verify(ledgerRepository).save(ledgerEntryCaptor.capture());
            assertThat(ledgerEntryCaptor.getValue().getSourceId())
                    .isEqualTo("reset_sub_1_" + NEW_PERIOD_KEY);
        }

        @Test
        @DisplayName("skips a row a concurrent actor already renewed, instead of granting twice")
        void skipsARowAlreadyRenewedUnderTheLock() {
            Plan free = createPlan("FREE", 1000L, 1000L);
            Subscription sub = createSubscription(free, 0, new BigDecimal("1000"), "internal");
            // Selected as expired by an unlocked read, but by the time we hold the lock an
            // admin grant (or an overlapping pass) has already moved the period forward.
            sub.setCurrentPeriodStart(NEW_PERIOD_START.plusDays(1));
            sub.setCurrentPeriodEnd(NEW_PERIOD_START.plusMonths(1).plusDays(1));

            attributionService.attributeOnRenewal(USER_ID, sub, NEW_PERIOD_START);

            verify(creditService, never()).grantCredits(anyLong(), any(), anyString(), anyString(), anyString());
            verify(ledgerRepository, never()).save(any(CreditLedgerEntry.class));
            // The winner's period is left exactly as it found it.
            assertThat(sub.getCurrentPeriodStart()).isEqualTo(NEW_PERIOD_START.plusDays(1));
            assertThat(sub.getRemainingCredits()).isEqualByComparingTo(new BigDecimal("1000"));
        }

        @Test
        @DisplayName("leaves the period untouched when null - the Stripe and admin callers own it")
        void nullPeriodStartLeavesTheCycleAlone() {
            Plan free = createPlan("FREE", 1000L, 1000L);
            Subscription sub = createSubscription(free, 0, BigDecimal.ZERO, "internal");

            attributionService.attributeOnRenewal(USER_ID, sub, null);

            // Stripe owns the cycle of a paid subscription; advancing it here would desync
            // the local row from Stripe's own billing period.
            assertThat(sub.getCurrentPeriodStart()).isEqualTo(PERIOD_START);
            assertThat(sub.getCurrentPeriodEnd()).isEqualTo(PERIOD_START.plusMonths(1));
            verify(creditService).grantCredits(eq(USER_ID), any(), anyString(),
                    eq("plan_sub_1_" + PERIOD_KEY), anyString());
        }

        @Test
        @DisplayName("writes the LIVE row, not the caller's stale copy")
        void writesTheResolvedRowNotTheCallersCopy() {
            Plan free = createPlan("FREE", 1000L, 1000L);
            // What the caller loaded a while ago: 200 credits, old cycle.
            Subscription stale = createSubscription(free, 0, new BigDecimal("200"), "internal");
            // What the row actually holds now. A DISTINCT instance, so an implementation that
            // keeps using the caller's object is caught instead of passing by aliasing.
            Subscription live = new Subscription();
            live.setId(SUB_ID);
            live.setPlan(free);
            live.setCreditQuantity(0);
            live.setProvider("internal");
            live.setStatus("active");
            live.setRemainingCredits(new BigDecimal("640"));
            live.setCurrentPeriodStart(PERIOD_START);
            live.setCurrentPeriodEnd(PERIOD_START.plusMonths(1));
            lastSubscription = live;

            attributionService.attributeOnRenewal(USER_ID, stale, NEW_PERIOD_START);

            assertThat(live.getCurrentPeriodStart()).isEqualTo(NEW_PERIOD_START);
            // Zeroed by resetBalance. creditService is a mock, so this says nothing about the
            // grant; the load-bearing assertion is the -640 ledger amount below.
            assertThat(live.getRemainingCredits()).isEqualByComparingTo(BigDecimal.ZERO);
            // The caller's object must be left completely alone - mutating it is what let the
            // scheduler merge a pre-grant copy back over the row.
            assertThat(stale.getCurrentPeriodStart()).isEqualTo(PERIOD_START);
            assertThat(stale.getRemainingCredits()).isEqualByComparingTo(new BigDecimal("200"));
            // And the audit amount is the live balance, not the caller's snapshot.
            verify(ledgerRepository).save(ledgerEntryCaptor.capture());
            assertThat(ledgerEntryCaptor.getValue().getAmount()).isEqualByComparingTo(new BigDecimal("-640"));
        }

        @Test
        @DisplayName("skips a row a Stripe upgrade has meanwhile cancelled, instead of granting onto the paid wallet")
        void skipsARowNoLongerEligible() {
            Plan starter = createPlan("STARTER", 25000L);
            Subscription sub = createSubscription(starter, 0, BigDecimal.ZERO, "internal");
            // A Stripe upgrade cancels the internal sibling between the unlocked selection and
            // the lock. grantCredits resolves the wallet by user, which is now the PAID row.
            sub.setStatus("canceled");

            attributionService.attributeOnRenewal(USER_ID, sub, NEW_PERIOD_START);

            verify(creditService, never()).grantCredits(anyLong(), any(), anyString(), anyString(), anyString());
            assertThat(sub.getCurrentPeriodStart()).isEqualTo(PERIOD_START);
        }

        @Test
        @DisplayName("still advances the period when the plan is missing, so the row leaves the expired window")
        void advancesEvenWhenPlanIsMissing() {
            Subscription sub = createSubscription(null, 0, BigDecimal.ZERO, "internal");
            sub.setPlan(null);

            attributionService.attributeOnRenewal(USER_ID, sub, NEW_PERIOD_START);

            // Otherwise every hourly pass re-picks the same broken row forever.
            assertThat(sub.getCurrentPeriodStart()).isEqualTo(NEW_PERIOD_START);
            verify(creditService, never()).grantCredits(anyLong(), any(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("reports RENEWED when the cycle was actually attributed")
        void reportsRenewed() {
            Plan free = createPlan("FREE", 1000L, 1000L);
            Subscription sub = createSubscription(free, 0, BigDecimal.ZERO, "internal");

            assertThat(attributionService.attributeOnRenewal(USER_ID, sub, NEW_PERIOD_START))
                    .isEqualTo(CreditAttributionService.RenewalOutcome.RENEWED);
        }

        @Test
        @DisplayName("reports ALREADY_RENEWED when a concurrent actor got there first")
        void reportsAlreadyRenewed() {
            Plan free = createPlan("FREE", 1000L, 1000L);
            Subscription sub = createSubscription(free, 0, BigDecimal.ZERO, "internal");
            sub.setCurrentPeriodEnd(NEW_PERIOD_START.plusMonths(1));

            assertThat(attributionService.attributeOnRenewal(USER_ID, sub, NEW_PERIOD_START))
                    .isEqualTo(CreditAttributionService.RenewalOutcome.ALREADY_RENEWED);
        }

        @Test
        @DisplayName("reports SKIPPED when the row is no longer eligible")
        void reportsSkipped() {
            Plan free = createPlan("FREE", 1000L, 1000L);
            Subscription sub = createSubscription(free, 0, BigDecimal.ZERO, "internal");
            sub.setStatus("canceled");

            assertThat(attributionService.attributeOnRenewal(USER_ID, sub, NEW_PERIOD_START))
                    .isEqualTo(CreditAttributionService.RenewalOutcome.SKIPPED);
        }

        @Test
        @DisplayName("skips a row that is no longer provider=internal - a paid row is Stripe's to renew")
        void skipsARowThatIsNoLongerInternal() {
            Plan starter = createPlan("STARTER", 25000L);
            Subscription sub = createSubscription(starter, 0, BigDecimal.ZERO, "stripe");

            attributionService.attributeOnRenewal(USER_ID, sub, NEW_PERIOD_START);

            verify(creditService, never()).grantCredits(anyLong(), any(), anyString(), anyString(), anyString());
            assertThat(sub.getCurrentPeriodStart()).isEqualTo(PERIOD_START);
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
        @DisplayName("an unresolvable row still grants - this path never writes through the entity")
        void unresolvableRowStillGrants() {
            Plan starter = createPlan("STARTER", 25000L);
            Subscription sub = createSubscription(starter, 10, BigDecimal.ZERO);
            lastSubscription = null;

            attributionService.handleCreditPackChange(USER_ID, sub, 10, 22);

            // Aborting here would drop a grant Stripe has already charged for. That is only
            // safe because nothing is written through the entity - assert that too, so a
            // future refactor adding a write breaks here instead of in production.
            verify(creditService).grantCredits(eq(USER_ID), eq(new BigDecimal("25000")),
                    eq("PURCHASE"), eq("pack_sub_1_upgrade_" + PERIOD_KEY), anyString());
            verify(subscriptionRepository, never()).save(any(Subscription.class));
            assertThat(sub.getRemainingCredits()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("refuses to grant when the subscription has no plan, instead of throwing")
        void refusesWhenPlanIsMissing() {
            Subscription sub = createSubscription(null, 10, BigDecimal.ZERO);
            sub.setPlan(null);

            attributionService.handleCreditPackChange(USER_ID, sub, 10, 22);

            verify(creditService, never()).grantCredits(anyLong(), any(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("keys the upgrade on the LIVE period, not the webhook entity's stale copy")
        void keysTheUpgradeOnTheLivePeriod() {
            Plan starter = createPlan("STARTER", 25000L);
            Subscription stale = createSubscription(starter, 10, BigDecimal.ZERO);

            // The Stripe webhook loads its entity with @Lock(NONE) outside any transaction, so
            // by the time we grant, customer.subscription.updated may already have re-anchored
            // the cycle. Keying on the stale period re-mints an id that cycle already consumed.
            LocalDateTime liveStart = PERIOD_START.plusMonths(1);
            Subscription live = createSubscription(starter, 10, BigDecimal.ZERO);
            live.setCurrentPeriodStart(liveStart);
            lastSubscription = live;

            attributionService.handleCreditPackChange(USER_ID, stale, 10, 22);

            verify(subscriptionRepository).findByIdForUpdate(SUB_ID);
            verify(creditService).grantCredits(eq(USER_ID), eq(new BigDecimal("25000")), eq("PURCHASE"),
                    eq("pack_sub_1_upgrade_" + liveStart.toEpochSecond(ZoneOffset.UTC)), anyString());
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

    // ===== attributeMonthlyCreditCycle - yearly Stripe subscriptions (V498) =====

    /**
     * A yearly Stripe subscription pays twelve months of credit pack up front (the pack is
     * priced per unit per month on every cadence, $12/unit/year) and is sold "credits per
     * month", but its {@code invoice.paid} fires once a year, so before V498 it was granted
     * ONE month of credits for a year of payment. These tests pin the monthly cycle that
     * closes that gap, and that it never touches what monthly and internal rows already do.
     */
    @Nested
    @DisplayName("attributeMonthlyCreditCycle - a yearly Stripe subscription is granted every month")
    class AttributeMonthlyCreditCycle {

        /** A yearly billing period anchored on PERIOD_START (2025-01-18). */
        private static final LocalDateTime PERIOD_END = PERIOD_START.plusMonths(12);

        private Subscription yearlyStripe(Plan plan, int creditQuantity, BigDecimal balance) {
            Subscription sub = createSubscription(plan, creditQuantity, balance, "stripe");
            sub.setCadence("yearly");
            sub.setCurrentPeriodEnd(PERIOD_END);
            return sub;
        }

        /** Epoch-second key of monthly cycle {@code n}, i.e. of {@code PERIOD_START + n months}. */
        private static String cycleKey(int n) {
            return String.valueOf(PERIOD_START.plusMonths(n).toEpochSecond(ZoneOffset.UTC));
        }

        @BeforeEach
        void setUp() {
            mockNoExistingLedger();
            mockGrantSuccess();
        }

        @Test
        @DisplayName("REGRESSION: one month into a yearly period the pack is granted again, not in twelve months")
        void yearlySubscriptionOneMonthInReceivesItsPackAgain() {
            // Prod user 121: TEAM yearly, 100 units = tier 4 = 100,000 credits, granted once at
            // creation and then nothing until the next invoice.paid a year later.
            Plan team = createPlan("TEAM", 5000L);
            Subscription sub = yearlyStripe(team, 100, new BigDecimal("-100.8285"));
            LocalDateTime now = PERIOD_START.plusMonths(1).plusDays(1);

            CreditAttributionService.MonthlyCycleOutcome outcome =
                    attributionService.attributeMonthlyCreditCycle(USER_ID, sub, now);

            assertThat(outcome).isEqualTo(CreditAttributionService.MonthlyCycleOutcome.GRANTED);
            verify(creditService).grantCredits(eq(USER_ID), eq(new BigDecimal("100000")),
                    eq("PURCHASE"), eq("pack_sub_1_" + cycleKey(1)), anyString());
            assertThat(sub.getCreditCycleIndex()).isEqualTo(1);
        }

        @Test
        @DisplayName("the previous balance is absorbed by a PLAN_RESET keyed on the CYCLE start, never on the period")
        void resetIsKeyedOnTheCycleStart() {
            Plan team = createPlan("TEAM", 5000L);
            Subscription sub = yearlyStripe(team, 100, new BigDecimal("-100.8285"));

            attributionService.attributeMonthlyCreditCycle(USER_ID, sub, PERIOD_START.plusMonths(1).plusDays(1));

            verify(ledgerRepository).save(ledgerEntryCaptor.capture());
            CreditLedgerEntry reset = ledgerEntryCaptor.getValue();
            assertThat(reset.getSourceType()).isEqualTo("PLAN_RESET");
            // Keyed on the cycle, so the eleven intra-year resets and grants of one subscription
            // never collide with each other nor with the period-start grant.
            assertThat(reset.getSourceId()).isEqualTo("reset_sub_1_" + cycleKey(1));
            assertThat(reset.getSourceId()).doesNotContain(PERIOD_KEY);
            // A debt is forgiven by the reset exactly as on a monthly renewal.
            assertThat(reset.getAmount()).isEqualByComparingTo(new BigDecimal("100.8285"));
            assertThat(sub.getRemainingCredits()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("nothing is due before the first month boundary")
        void nothingIsDueBeforeTheMonthBoundary() {
            Plan team = createPlan("TEAM", 5000L);
            Subscription sub = yearlyStripe(team, 100, new BigDecimal("40000"));

            CreditAttributionService.MonthlyCycleOutcome outcome =
                    attributionService.attributeMonthlyCreditCycle(USER_ID, sub, PERIOD_START.plusDays(20));

            assertThat(outcome).isEqualTo(CreditAttributionService.MonthlyCycleOutcome.NOT_DUE);
            verify(creditService, never()).grantCredits(anyLong(), any(), anyString(), anyString(), anyString());
            verify(ledgerRepository, never()).save(any(CreditLedgerEntry.class));
            assertThat(sub.getRemainingCredits()).isEqualByComparingTo(new BigDecimal("40000"));
            assertThat(sub.getCreditCycleIndex()).isZero();
        }

        @Test
        @DisplayName("exactly at the month boundary the cycle is due (inclusive)")
        void theBoundaryInstantIsDue() {
            Plan team = createPlan("TEAM", 5000L);
            Subscription sub = yearlyStripe(team, 100, BigDecimal.ZERO);

            CreditAttributionService.MonthlyCycleOutcome outcome =
                    attributionService.attributeMonthlyCreditCycle(USER_ID, sub, PERIOD_START.plusMonths(1));

            assertThat(outcome).isEqualTo(CreditAttributionService.MonthlyCycleOutcome.GRANTED);
        }

        @Test
        @DisplayName("a cycle already granted is not granted twice")
        void aGrantedCycleIsNotGrantedTwice() {
            Plan team = createPlan("TEAM", 5000L);
            Subscription sub = yearlyStripe(team, 100, new BigDecimal("90000"));
            sub.setCreditCycleIndex(1);

            CreditAttributionService.MonthlyCycleOutcome outcome =
                    attributionService.attributeMonthlyCreditCycle(USER_ID, sub, PERIOD_START.plusMonths(1).plusDays(5));

            assertThat(outcome).isEqualTo(CreditAttributionService.MonthlyCycleOutcome.NOT_DUE);
            verify(creditService, never()).grantCredits(anyLong(), any(), anyString(), anyString(), anyString());
            assertThat(sub.getRemainingCredits()).isEqualByComparingTo(new BigDecimal("90000"));
        }

        @Test
        @DisplayName("the twelfth boundary belongs to the Stripe renewal, never to the monthly cycle")
        void theTwelfthBoundaryIsLeftToStripe() {
            // Cycle 11 granted; the yearly invoice.paid is late (period not moved yet). A
            // twelfth grant here would double up with the renewal's own reset + grant.
            Plan team = createPlan("TEAM", 5000L);
            Subscription sub = yearlyStripe(team, 100, new BigDecimal("1200"));
            sub.setCreditCycleIndex(11);

            CreditAttributionService.MonthlyCycleOutcome outcome =
                    attributionService.attributeMonthlyCreditCycle(USER_ID, sub, PERIOD_END.plusDays(3));

            assertThat(outcome).isEqualTo(CreditAttributionService.MonthlyCycleOutcome.NOT_DUE);
            verify(creditService, never()).grantCredits(anyLong(), any(), anyString(), anyString(), anyString());
            assertThat(sub.getCreditCycleIndex()).isEqualTo(11);
        }

        @Test
        @DisplayName("catch-up jumps to the latest due cycle and grants once, not once per missed month")
        void catchUpGrantsTheLatestCycleOnce() {
            // A yearly subscription that predates the scheduler: three boundaries have passed.
            Plan team = createPlan("TEAM", 5000L);
            Subscription sub = yearlyStripe(team, 100, new BigDecimal("12"));

            CreditAttributionService.MonthlyCycleOutcome outcome =
                    attributionService.attributeMonthlyCreditCycle(USER_ID, sub, PERIOD_START.plusMonths(3).plusDays(2));

            assertThat(outcome).isEqualTo(CreditAttributionService.MonthlyCycleOutcome.GRANTED);
            // Cycles 1 and 2 would each have been reset by the next; replaying them only
            // writes pairs that cancel out. One grant, keyed on cycle 3.
            verify(creditService, times(1)).grantCredits(anyLong(), any(), anyString(), anyString(), anyString());
            verify(creditService).grantCredits(eq(USER_ID), eq(new BigDecimal("100000")),
                    eq("PURCHASE"), eq("pack_sub_1_" + cycleKey(3)), anyString());
            verify(ledgerRepository, times(1)).save(any(CreditLedgerEntry.class));
            assertThat(sub.getCreditCycleIndex()).isEqualTo(3);
        }

        @Test
        @DisplayName("a cycle whose ledger keys already exist is ABSORBED, never announced as granted")
        void alreadyLedgeredCycleIsAbsorbed() {
            Plan team = createPlan("TEAM", 5000L);
            Subscription sub = yearlyStripe(team, 100, BigDecimal.ZERO);
            // A rewound index (bad manual fix, the deferred upsert race): the cycle is due again
            // by the index, but both its sourceIds are already in the ledger.
            when(ledgerRepository.existsBySourceId("pack_sub_1_" + cycleKey(1))).thenReturn(true);
            when(ledgerRepository.existsBySourceId("reset_sub_1_" + cycleKey(1))).thenReturn(true);

            CreditAttributionService.MonthlyCycleOutcome outcome =
                    attributionService.attributeMonthlyCreditCycle(USER_ID, sub, PERIOD_START.plusMonths(1).plusDays(1));

            // GRANTED here would make the scheduler log a grant and bust caches for a pass that
            // wrote nothing: the green-when-wrong class.
            assertThat(outcome).isEqualTo(CreditAttributionService.MonthlyCycleOutcome.ABSORBED);
            verify(creditService, never()).grantCredits(anyLong(), any(), anyString(), anyString(), anyString());
            verify(ledgerRepository, never()).save(any(CreditLedgerEntry.class));
            // The index still re-aligns with the ledger, so the row is consistent again.
            assertThat(sub.getCreditCycleIndex()).isEqualTo(1);
        }

        @Test
        @DisplayName("the index is advanced BEFORE the grant runs, inside the same transaction")
        void indexAdvancesBeforeTheGrant() {
            Plan team = createPlan("TEAM", 5000L);
            Subscription sub = yearlyStripe(team, 100, BigDecimal.ZERO);
            java.util.concurrent.atomic.AtomicInteger indexSeenByGrant = new java.util.concurrent.atomic.AtomicInteger(-1);
            when(creditService.grantCredits(anyLong(), any(BigDecimal.class), anyString(), anyString(), anyString()))
                    .thenAnswer(inv -> {
                        indexSeenByGrant.set(sub.getCreditCycleIndex());
                        return CreditConsumeResult.success(BigDecimal.ZERO, BigDecimal.ZERO);
                    });

            attributionService.attributeMonthlyCreditCycle(USER_ID, sub, PERIOD_START.plusMonths(1).plusDays(1));

            // Advance-then-grant is what makes a crash retry cleanly (the rollback takes the
            // index back with the grant) and a concurrent pass see the cycle already taken.
            assertThat(indexSeenByGrant.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("the PAYG bucket survives the monthly reset, as it survives every renewal")
        void paygBucketSurvivesTheReset() {
            Plan team = createPlan("TEAM", 5000L);
            Subscription sub = yearlyStripe(team, 100, new BigDecimal("50"));
            sub.setPaygRemainingCredits(new BigDecimal("300"));

            attributionService.attributeMonthlyCreditCycle(USER_ID, sub, PERIOD_START.plusMonths(1).plusDays(1));

            assertThat(sub.getRemainingCredits()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(sub.getPaygRemainingCredits()).isEqualByComparingTo(new BigDecimal("300"));
        }

        @Test
        @DisplayName("a yearly subscription without a pack gets the tier-0 base every month, like a monthly one")
        void yearlyWithoutPackGetsTheBasePackMonthly() {
            Plan pro = createPlan("PRO", 5000L);
            Subscription sub = yearlyStripe(pro, 0, BigDecimal.ZERO);

            attributionService.attributeMonthlyCreditCycle(USER_ID, sub, PERIOD_START.plusMonths(1).plusDays(1));

            verify(creditService).grantCredits(eq(USER_ID), eq(new BigDecimal("5000")),
                    eq("PURCHASE"), eq("pack_sub_1_" + cycleKey(1)), anyString());
        }

        @Test
        @DisplayName("a moved billing period restarts the cycle count, so the renewal re-arms the drips")
        void aMovedPeriodRestartsTheCycles() {
            // Year one fully granted. The yearly invoice.paid moves the period (Stripe sync).
            Plan team = createPlan("TEAM", 5000L);
            Subscription sub = yearlyStripe(team, 100, BigDecimal.ZERO);
            sub.setCreditCycleIndex(11);
            sub.setCurrentPeriodStart(PERIOD_END);
            sub.setCurrentPeriodEnd(PERIOD_END.plusMonths(12));
            String yearTwoCycleOneKey = String.valueOf(PERIOD_END.plusMonths(1).toEpochSecond(ZoneOffset.UTC));

            CreditAttributionService.MonthlyCycleOutcome outcome =
                    attributionService.attributeMonthlyCreditCycle(USER_ID, sub, PERIOD_END.plusMonths(1).plusDays(1));

            // A stale index of 11 would have made every cycle of year two "already granted".
            assertThat(outcome).isEqualTo(CreditAttributionService.MonthlyCycleOutcome.GRANTED);
            verify(creditService).grantCredits(eq(USER_ID), any(), eq("PURCHASE"),
                    eq("pack_sub_1_" + yearTwoCycleOneKey), anyString());
            assertThat(sub.getCreditCycleIndex()).isEqualTo(1);
        }

        @Test
        @DisplayName("a monthly Stripe subscription is never touched here - Stripe owns its cycle")
        void monthlyStripeIsSkipped() {
            Plan team = createPlan("TEAM", 5000L);
            Subscription sub = createSubscription(team, 100, new BigDecimal("500"), "stripe");
            sub.setCadence("monthly");

            CreditAttributionService.MonthlyCycleOutcome outcome =
                    attributionService.attributeMonthlyCreditCycle(USER_ID, sub, PERIOD_START.plusMonths(2));

            assertThat(outcome).isEqualTo(CreditAttributionService.MonthlyCycleOutcome.SKIPPED);
            verifyNoInteractions(creditService);
            verify(ledgerRepository, never()).save(any(CreditLedgerEntry.class));
            assertThat(sub.getRemainingCredits()).isEqualByComparingTo(new BigDecimal("500"));
        }

        @Test
        @DisplayName("an internal (FREE / comp) row is left to the internal scheduler, whatever its cadence")
        void internalRowIsSkipped() {
            Plan free = createPlan("FREE", 1000L, 1000L);
            Subscription sub = createSubscription(free, 0, new BigDecimal("700"), "internal");
            sub.setCadence("yearly");
            sub.setCurrentPeriodEnd(PERIOD_END);

            CreditAttributionService.MonthlyCycleOutcome outcome =
                    attributionService.attributeMonthlyCreditCycle(USER_ID, sub, PERIOD_START.plusMonths(2));

            assertThat(outcome).isEqualTo(CreditAttributionService.MonthlyCycleOutcome.SKIPPED);
            verifyNoInteractions(creditService);
            assertThat(sub.getRemainingCredits()).isEqualByComparingTo(new BigDecimal("700"));
        }

        @org.junit.jupiter.params.ParameterizedTest(name = "status {0} is skipped")
        @org.junit.jupiter.params.provider.ValueSource(strings = {"canceled", "past_due", "trialing"})
        @DisplayName("a yearly subscription that is not active is not in good standing and is skipped")
        void notActiveIsSkipped(String status) {
            Plan team = createPlan("TEAM", 5000L);
            Subscription sub = yearlyStripe(team, 100, BigDecimal.ZERO);
            sub.setStatus(status);

            CreditAttributionService.MonthlyCycleOutcome outcome =
                    attributionService.attributeMonthlyCreditCycle(USER_ID, sub, PERIOD_START.plusMonths(2));

            assertThat(outcome).isEqualTo(CreditAttributionService.MonthlyCycleOutcome.SKIPPED);
            verifyNoInteractions(creditService);
        }

        @Test
        @DisplayName("an unresolvable row is skipped without writing through a detached copy")
        void unresolvableRowIsSkipped() {
            Plan team = createPlan("TEAM", 5000L);
            Subscription sub = yearlyStripe(team, 100, new BigDecimal("500"));
            when(subscriptionRepository.findByIdForUpdate(anyLong())).thenReturn(Optional.empty());

            CreditAttributionService.MonthlyCycleOutcome outcome =
                    attributionService.attributeMonthlyCreditCycle(USER_ID, sub, PERIOD_START.plusMonths(2));

            assertThat(outcome).isEqualTo(CreditAttributionService.MonthlyCycleOutcome.SKIPPED);
            verifyNoInteractions(creditService);
            verify(subscriptionRepository, never()).save(any());
            assertThat(sub.getCreditCycleIndex()).isZero();
        }

        @Test
        @DisplayName("a row without a plan is skipped")
        void rowWithoutPlanIsSkipped() {
            Subscription sub = yearlyStripe(createPlan("TEAM", 5000L), 100, BigDecimal.ZERO);
            sub.setPlan(null);

            CreditAttributionService.MonthlyCycleOutcome outcome =
                    attributionService.attributeMonthlyCreditCycle(USER_ID, sub, PERIOD_START.plusMonths(2));

            assertThat(outcome).isEqualTo(CreditAttributionService.MonthlyCycleOutcome.SKIPPED);
            verifyNoInteractions(creditService);
        }

        @Test
        @DisplayName("a null credit quantity is treated as 0: the tier-0 base pack, like everywhere else")
        void nullCreditQuantityGetsTheBasePack() {
            Plan team = createPlan("TEAM", 5000L);
            Subscription sub = yearlyStripe(team, 0, BigDecimal.ZERO);
            sub.setCreditQuantity(null);

            attributionService.attributeMonthlyCreditCycle(USER_ID, sub, PERIOD_START.plusMonths(1).plusDays(1));

            verify(creditService).grantCredits(eq(USER_ID), eq(new BigDecimal("5000")),
                    eq("PURCHASE"), eq("pack_sub_1_" + cycleKey(1)), anyString());
        }

        @Test
        @DisplayName("a row set to cancel at period end is still active and still granted: the year is paid for")
        void cancelAtPeriodEndRowIsStillGranted() {
            Plan team = createPlan("TEAM", 5000L);
            Subscription sub = yearlyStripe(team, 100, BigDecimal.ZERO);
            sub.setCancelAtPeriodEnd(true);

            CreditAttributionService.MonthlyCycleOutcome outcome =
                    attributionService.attributeMonthlyCreditCycle(USER_ID, sub, PERIOD_START.plusMonths(1).plusDays(1));

            assertThat(outcome).isEqualTo(CreditAttributionService.MonthlyCycleOutcome.GRANTED);
        }

        @Nested
        @DisplayName("dueCreditCycle - anchored on the period start, drift-free")
        class DueCreditCycle {

            @Test
            @DisplayName("a day-31 anchor yields exactly eleven cycles over the year, none in its final days")
            void dayThirtyOneAnchorYieldsElevenCycles() {
                LocalDateTime start = LocalDateTime.of(2025, 1, 31, 0, 0);
                LocalDateTime end = start.plusMonths(12); // 2026-01-31

                int maxDue = 0;
                for (LocalDateTime day = start; day.isBefore(end); day = day.plusDays(1)) {
                    maxDue = Math.max(maxDue, CreditAttributionService.dueCreditCycle(start, end, day));
                }

                assertThat(maxDue).isEqualTo(11);
                assertThat(CreditAttributionService.dueCreditCycle(start, end, end.minusDays(1))).isEqualTo(11);
                // Chaining plusMonths(1) from the previous cycle would slide Jan 31 to Feb 28 to
                // Mar 28 and so on, and land a thirteenth pack on Jan 28 2026, three days before the
                // renewal. Anchored, cycle 2 starts on Mar 31, so on Mar 30 only cycle 1 is due.
                assertThat(CreditAttributionService.dueCreditCycle(start, end, LocalDateTime.of(2025, 3, 30, 0, 0)))
                        .isEqualTo(1);
                assertThat(start.plusMonths(2)).isEqualTo(LocalDateTime.of(2025, 3, 31, 0, 0));
            }

            @Test
            @DisplayName("a mid-month anchor yields the same eleven cycles")
            void midMonthAnchorYieldsElevenCycles() {
                LocalDateTime start = LocalDateTime.of(2026, 9, 14, 23, 53, 9);
                LocalDateTime end = start.plusMonths(12);

                assertThat(CreditAttributionService.dueCreditCycle(start, end, end.minusSeconds(1))).isEqualTo(11);
                assertThat(CreditAttributionService.dueCreditCycle(start, end, end)).isEqualTo(11);
                assertThat(CreditAttributionService.dueCreditCycle(start, end, start.plusMonths(1))).isEqualTo(1);
            }

            @Test
            @DisplayName("a period end an hour past twelve months (DST between Stripe's UTC and a local anchor) still yields eleven, never a thirteenth pack")
            void dstShiftedPeriodEndDoesNotYieldATwelfthCycle() {
                LocalDateTime start = LocalDateTime.of(2026, 3, 20, 1, 0);
                LocalDateTime end = start.plusMonths(12).plusHours(1);

                // Cycle 12 would start an hour before the renewal; with a strict "< periodEnd"
                // bound it is due, and the renewal resets it minutes later: a pack paid twice
                // for one month. A cycle that lives less than a day is the renewal's.
                assertThat(CreditAttributionService.dueCreditCycle(start, end, end.plusDays(2))).isEqualTo(11);
            }

            @Test
            @DisplayName("the one-day margin is inclusive: a cycle starting exactly one day before the period end is due")
            void oneDayBeforeTheEndIsStillDue() {
                LocalDateTime start = LocalDateTime.of(2026, 1, 18, 0, 0);
                LocalDateTime end = start.plusMonths(3).plusDays(1);

                // Cycle 3 starts at start + 3 months = end - 1 day: exactly on the margin.
                assertThat(CreditAttributionService.dueCreditCycle(start, end, end)).isEqualTo(3);
                assertThat(CreditAttributionService.dueCreditCycle(start, end.minusSeconds(1), end)).isEqualTo(2);
            }

            @Test
            @DisplayName("a billing period shorter than twelve months yields fewer cycles, never one past its end")
            void shorterPeriodYieldsFewerCycles() {
                LocalDateTime start = LocalDateTime.of(2025, 1, 18, 0, 0);
                LocalDateTime end = start.plusMonths(3);

                assertThat(CreditAttributionService.dueCreditCycle(start, end, end.plusDays(30))).isEqualTo(2);
            }

            @Test
            @DisplayName("nothing is due at the period start, and nothing is ever due on nulls")
            void nothingDueAtStartOrOnNulls() {
                LocalDateTime start = LocalDateTime.of(2025, 1, 18, 0, 0);

                assertThat(CreditAttributionService.dueCreditCycle(start, start.plusMonths(12), start)).isZero();
                assertThat(CreditAttributionService.dueCreditCycle(null, start.plusMonths(12), start)).isZero();
                assertThat(CreditAttributionService.dueCreditCycle(start, null, start)).isZero();
                assertThat(CreditAttributionService.dueCreditCycle(start, start.plusMonths(12), null)).isZero();
            }
        }
    }

    @Nested
    @DisplayName("nextCreditGrantAt - when the credits themselves come back")
    class NextCreditGrantAt {

        private static final LocalDateTime PERIOD_END_YEARLY = PERIOD_START.plusMonths(12);

        private Subscription yearly(int creditQuantity) {
            Subscription sub = createSubscription(createPlan("TEAM", 5000L), creditQuantity,
                    BigDecimal.ZERO, "stripe");
            sub.setCadence("yearly");
            sub.setCurrentPeriodEnd(PERIOD_END_YEARLY);
            return sub;
        }

        private Subscription monthly() {
            Subscription sub = createSubscription(createPlan("PRO", 5000L), 5, BigDecimal.ZERO, "stripe");
            sub.setCadence("monthly");
            return sub;
        }

        @Test
        @DisplayName("REGRESSION: a yearly subscriber is pointed at next month's drip, not at next year's invoice")
        void yearlySubscriptionPointsAtTheNextMonthlyCycle() {
            // The defect this method exists for: the invoice is annual and the credit pack is
            // monthly, so answering currentPeriodEnd would tell a customer three weeks into his
            // year to wait eleven more months for credits arriving in ten days.
            Subscription sub = yearly(100);
            LocalDateTime now = PERIOD_START.plusDays(20);

            LocalDateTime next = CreditAttributionService.nextCreditGrantAt(sub, now);

            assertThat(next).isEqualTo(PERIOD_START.plusMonths(1));
            assertThat(next).isNotEqualTo(sub.getCurrentPeriodEnd());
        }

        @Test
        @DisplayName("the instant it names is exactly the cycle the scheduler calls due there, for every month of the year")
        void theNamedInstantIsTheCycleTheSchedulerWillGrant() {
            // The whole reason this lives beside dueCreditCycle: a date the UI promises and a
            // grant the scheduler makes must be the same event.
            assertAgreesWithTheSchedulerAllYear(PERIOD_START);
        }

        @Test
        @DisplayName("a day-31 anchor agrees with the scheduler too, where plusMonths clamps and chaining would drift")
        void aDayThirtyOneAnchorAgreesWithTheSchedulerAllYear() {
            // dueCreditCycle has its own day-31 test because Jan 31 is where a chained
            // plusMonths(1) slides to Feb 28 and never comes back. The mirror has to be held to
            // the same bar, or "exact mirror" is a claim tested on the one anchor shape
            // (mid-month) that cannot expose the difference.
            assertAgreesWithTheSchedulerAllYear(LocalDateTime.of(2025, 1, 31, 0, 0));
        }

        /**
         * Walk a whole billing year day by day and assert, at every step, that the instant
         * {@code nextCreditGrantAt} names is the cycle {@code dueCreditCycle} will call due when
         * that instant arrives, and no other.
         *
         * <p>The row's {@code creditCycleIndex} is advanced along with the walk, which models the
         * healthy system this is about: the hourly scheduler has granted every cycle that came
         * due. A row left at index 0 all year is a row whose grants are OWED, and that is a
         * different question, asked by {@code aDueButUngrantedCycleNamesNoDate}.
         */
        private void assertAgreesWithTheSchedulerAllYear(LocalDateTime periodStart) {
            LocalDateTime periodEnd = periodStart.plusMonths(12);
            Subscription sub = yearly(100);
            sub.setCurrentPeriodStart(periodStart);
            sub.setCurrentPeriodEnd(periodEnd);

            for (LocalDateTime day = periodStart; day.isBefore(periodEnd); day = day.plusDays(1)) {
                sub.setCreditCycleIndex(
                        CreditAttributionService.dueCreditCycle(periodStart, periodEnd, day));
                LocalDateTime next = CreditAttributionService.nextCreditGrantAt(sub, day);
                assertThat(next).as("a grant date is always named at %s", day).isNotNull();
                assertThat(next).as("the next grant is in the future at %s", day).isAfter(day);
                if (next.equals(periodEnd)) {
                    // Past the eleventh cycle: what comes next is the yearly renewal itself.
                    assertThat(CreditAttributionService.dueCreditCycle(periodStart, periodEnd, day))
                            .isEqualTo(11);
                    continue;
                }
                // At the promised instant the scheduler owes exactly that cycle: one more than it
                // owes today. A margin or anchor drift between the two would break this.
                int dueToday = CreditAttributionService.dueCreditCycle(periodStart, periodEnd, day);
                int dueThen = CreditAttributionService.dueCreditCycle(periodStart, periodEnd, next);
                assertThat(dueThen).as("the promised date grants the next cycle, at %s", day)
                        .isEqualTo(dueToday + 1);
                assertThat(periodStart.plusMonths(dueThen)).isEqualTo(next);
            }
        }

        @Test
        @DisplayName("past the last intra-period cycle it falls back to the yearly renewal, never a thirteenth pack")
        void afterTheLastCycleItFallsBackToThePeriodEnd() {
            Subscription sub = yearly(100);
            sub.setCreditCycleIndex(11);

            // Cycle 11 is the last one that fits; from its day onward the next grant is the renewal.
            LocalDateTime afterTheLastCycle = PERIOD_START.plusMonths(11).plusDays(1);

            assertThat(CreditAttributionService.nextCreditGrantAt(sub, afterTheLastCycle))
                    .isEqualTo(PERIOD_END_YEARLY);
        }

        @Test
        @DisplayName("a period end an hour past twelve months does not buy a twelfth cycle, exactly as the scheduler refuses one")
        void dstShiftedPeriodEndDoesNotYieldATwelfthCycle() {
            // The one-day margin, held on this side too. Without it, a period end that lands an
            // hour past periodStart + 12 months (a DST shift between the UTC instant Stripe
            // computes and a local-time anchor) makes cycle 12 fit: this method would promise a
            // pack an hour before the renewal that dueCreditCycle never calls due, so the date
            // would simply pass with nothing happening. dueCreditCycle has the same test; the
            // mirror without it let the margin be deleted here with the suite still green.
            LocalDateTime start = LocalDateTime.of(2026, 3, 20, 1, 0);
            LocalDateTime end = start.plusMonths(12).plusHours(1);
            Subscription sub = yearly(100);
            sub.setCurrentPeriodStart(start);
            sub.setCurrentPeriodEnd(end);
            sub.setCreditCycleIndex(11);

            LocalDateTime justAfterTheEleventhCycle = start.plusMonths(11).plusDays(1);

            assertThat(CreditAttributionService.dueCreditCycle(start, end, end.minusHours(2))).isEqualTo(11);
            assertThat(CreditAttributionService.nextCreditGrantAt(sub, justAfterTheEleventhCycle))
                    .isEqualTo(end);
        }

        @Test
        @DisplayName("standing exactly on a cycle start, once it is granted, the answer is the NEXT one")
        void onACycleStartTheAnswerIsTheFollowingCycle() {
            Subscription sub = yearly(100);
            LocalDateTime onCycleThree = PERIOD_START.plusMonths(3);
            sub.setCreditCycleIndex(3);

            // dueCreditCycle already calls cycle 3 due at that instant and the row says it has
            // been granted, so the grant a reader is still waiting for is cycle 4.
            assertThat(CreditAttributionService.dueCreditCycle(PERIOD_START, PERIOD_END_YEARLY, onCycleThree))
                    .isEqualTo(3);
            assertThat(CreditAttributionService.nextCreditGrantAt(sub, onCycleThree))
                    .isEqualTo(PERIOD_START.plusMonths(4));
        }

        @Test
        @DisplayName("a cycle that is due and not yet granted names no date - the wait is an hour, not a month")
        void aDueButUngrantedCycleNamesNoDate() {
            // The yearly scheduler runs hourly, so a row sits in this state after every cycle
            // start and for the whole of any outage. Naming the FOLLOWING cycle there would tell
            // somebody whose credits land within the hour to wait a month, which is the same
            // "imminent is not an instant" case the expired-period branch already answers with
            // silence. Read from the row's index, which is what the scheduler compares against.
            Subscription sub = yearly(100);
            sub.setCreditCycleIndex(2);
            LocalDateTime justAfterCycleThree = PERIOD_START.plusMonths(3).plusMinutes(1);

            assertThat(CreditAttributionService.dueCreditCycle(
                    PERIOD_START, PERIOD_END_YEARLY, justAfterCycleThree)).isEqualTo(3);
            assertThat(CreditAttributionService.nextCreditGrantAt(sub, justAfterCycleThree)).isNull();

            // Once the scheduler catches up, the next cycle is named again.
            sub.setCreditCycleIndex(3);
            assertThat(CreditAttributionService.nextCreditGrantAt(sub, justAfterCycleThree))
                    .isEqualTo(PERIOD_START.plusMonths(4));
        }

        @Test
        @DisplayName("a long scheduler outage names no date either, rather than the cycle after the missed ones")
        void aBackloggedRowNamesNoDate() {
            Subscription sub = yearly(100);
            sub.setCreditCycleIndex(1);

            // Five cycles owed. The catch-up grants one of them within the hour, so any date this
            // named would be wrong in the same direction and by five times as much.
            assertThat(CreditAttributionService.nextCreditGrantAt(sub, PERIOD_START.plusMonths(6)))
                    .isNull();
        }

        @Test
        @DisplayName("a monthly Stripe subscription renews at its period end")
        void monthlyStripeRenewsAtThePeriodEnd() {
            assertThat(CreditAttributionService.nextCreditGrantAt(monthly(), PERIOD_START.plusDays(3)))
                    .isEqualTo(PERIOD_START.plusMonths(1));
        }

        @Test
        @DisplayName("a FREE internal row renews at its period end - the monthly reset is a real grant")
        void freeInternalRowRenewsAtThePeriodEnd() {
            Subscription sub = createSubscription(createPlan("FREE", 1000L, 1000L), 0,
                    BigDecimal.ZERO, "internal");

            assertThat(CreditAttributionService.nextCreditGrantAt(sub, PERIOD_START.plusDays(3)))
                    .isEqualTo(PERIOD_START.plusMonths(1));
        }

        @Test
        @DisplayName("an internal row with a yearly cadence still renews at its period end - the monthly cycle is Stripe's alone")
        void internalYearlyRowIsNotPutOnTheMonthlyCycle() {
            // isMonthlyCreditCycleEligible requires provider=stripe, and an internal row renews
            // through its own scheduler. Naming a cycle date here would promise a grant that
            // nothing makes.
            Subscription sub = createSubscription(createPlan("PRO", 5000L), 5, BigDecimal.ZERO, "internal");
            sub.setCadence("yearly");
            sub.setCurrentPeriodEnd(PERIOD_END_YEARLY);

            assertThat(CreditAttributionService.nextCreditGrantAt(sub, PERIOD_START.plusDays(20)))
                    .isEqualTo(PERIOD_END_YEARLY);
        }

        @Test
        @DisplayName("REGRESSION: a cancelling YEARLY row is still told about the packs it has already paid for")
        void cancellingYearlyRowStillNamesItsMonthlyCycle() {
            // attributeMonthlyCreditCycle does not look at the cancel flag and neither does the
            // query that feeds it: the year is paid for, so the packs keep coming. Refusing to
            // name them silenced this feature for up to eleven months on the exact plan shape it
            // was written for, and at the exact moment its owner is asking whether cancelling
            // costs them this month's credits.
            Subscription sub = yearly(100);
            sub.setCancelAtPeriodEnd(true);

            assertThat(CreditAttributionService.nextCreditGrantAt(sub, PERIOD_START.plusDays(20)))
                    .isEqualTo(PERIOD_START.plusMonths(1));
        }

        @Test
        @DisplayName("a cancelling yearly row past its last cycle names nothing - the renewal is the one grant it will not get")
        void cancellingYearlyRowPastItsLastCycleNamesNoDate() {
            Subscription sub = yearly(100);
            sub.setCancelAtPeriodEnd(true);
            sub.setCreditCycleIndex(11);

            assertThat(CreditAttributionService.nextCreditGrantAt(sub, PERIOD_START.plusMonths(11).plusDays(1)))
                    .isNull();
        }

        @Test
        @DisplayName("a cancelling MONTHLY row names nothing - there is no further invoice to grant on")
        void cancellingMonthlyRowNamesNoDate() {
            Subscription sub = monthly();
            sub.setCancelAtPeriodEnd(true);

            assertThat(CreditAttributionService.nextCreditGrantAt(sub, PERIOD_START.plusDays(3))).isNull();
        }

        @Test
        @DisplayName("a period end already behind us names nothing - the honest answer there is not an instant")
        void anExpiredPeriodNamesNoDate() {
            // Every internal row spends up to an hour in this state each month: the renewal
            // scheduler is hourly and selects rows whose period has ALREADY expired. Returning
            // the stale end put "+1,000 credits on {yesterday}" on the wallet card.
            Subscription sub = createSubscription(createPlan("FREE", 1000L, 1000L), 0,
                    BigDecimal.ZERO, "internal");
            LocalDateTime oneMinuteAfterExpiry = sub.getCurrentPeriodEnd().plusMinutes(1);

            assertThat(CreditAttributionService.nextCreditGrantAt(sub, oneMinuteAfterExpiry)).isNull();
            // On the boundary itself the grant is already owed, so it is not a future instant either.
            assertThat(CreditAttributionService.nextCreditGrantAt(sub, sub.getCurrentPeriodEnd())).isNull();
        }

        @Test
        @DisplayName("a row whose renewal grants nothing names no date, so no amount is promised that never arrives")
        void aRowThatGrantsNothingNamesNoDate() {
            // grantsBasePack excludes an INTERNAL row at quantity zero unless its plan is one of
            // the comp plans, and a PAYG/CREDIT_PACK plan carries no included credits either. The
            // row is active and has a period end, so before this guard both surfaces offered it
            // "+5,000 credits" on a date nothing would honour: resolveMonthlyAllowance reads
            // tier 0 for any non-FREE plan code.
            for (String planCode : new String[] {"PAYG", "CREDIT_PACK"}) {
                Subscription sub = createSubscription(createPlan(planCode, 0L), 0,
                        BigDecimal.ZERO, "internal");
                assertThat(CreditAttributionService.nextCreditGrantAt(sub, PERIOD_START.plusDays(3)))
                        .as("plan %s", planCode)
                        .isNull();
            }
        }

        @Test
        @DisplayName("a comp Starter/Pro/Team row DOES name a date - the guard must not refuse what the renewal grants")
        void aCompPlanRowStillNamesItsDate() {
            // The other side of the guard above. grantsBasePack admits these three by name, so
            // they receive the tier-0 base pack every cycle and must be told about it.
            for (String planCode : new String[] {"STARTER", "PRO", "TEAM"}) {
                Subscription sub = createSubscription(createPlan(planCode, 5000L), 0,
                        BigDecimal.ZERO, "internal");
                assertThat(CreditAttributionService.nextCreditGrantAt(sub, PERIOD_START.plusDays(3)))
                        .as("plan %s", planCode)
                        .isEqualTo(PERIOD_START.plusMonths(1));
            }
        }

        @Test
        @DisplayName("a negative credit quantity names no date - that renewal is explicitly nothing to grant")
        void aNegativeQuantityNamesNoDate() {
            Subscription sub = createSubscription(createPlan("PRO", 5000L), -1, BigDecimal.ZERO, "stripe");

            assertThat(CreditAttributionService.nextCreditGrantAt(sub, PERIOD_START.plusDays(3))).isNull();
        }

        @Test
        @DisplayName("a row with no plan names no date instead of throwing")
        void aRowWithNoPlanNamesNoDate() {
            Subscription sub = monthly();
            sub.setPlan(null);

            assertThat(CreditAttributionService.nextCreditGrantAt(sub, PERIOD_START.plusDays(3))).isNull();
        }

        @Test
        @DisplayName("a row out of good standing names no date - its grant waits on an invoice nobody can date")
        void rowsOutOfGoodStandingNameNoDate() {
            // past_due and incomplete are selectable as "your current subscription" by
            // /billing/me, which is a different question from "are you owed a further grant".
            for (String status : new String[] {"past_due", "incomplete", "canceled", "unpaid"}) {
                Subscription sub = yearly(100);
                sub.setStatus(status);
                assertThat(CreditAttributionService.nextCreditGrantAt(sub, PERIOD_START.plusDays(20)))
                        .as("status %s", status)
                        .isNull();
            }
        }

        @Test
        @DisplayName("a trialing row names its trial end - that invoice is what grants")
        void trialingRowNamesItsTrialEnd() {
            Subscription sub = monthly();
            sub.setStatus("trialing");

            assertThat(CreditAttributionService.nextCreditGrantAt(sub, PERIOD_START.plusDays(3)))
                    .isEqualTo(PERIOD_START.plusMonths(1));
        }

        @Test
        @DisplayName("nothing is named on nulls, rather than a date computed from a missing period")
        void nullsNameNoDate() {
            Subscription noEnd = yearly(100);
            noEnd.setCurrentPeriodEnd(null);

            assertThat(CreditAttributionService.nextCreditGrantAt(noEnd, PERIOD_START)).isNull();
            assertThat(CreditAttributionService.nextCreditGrantAt(null, PERIOD_START)).isNull();
            assertThat(CreditAttributionService.nextCreditGrantAt(yearly(100), null)).isNull();

            Subscription noStatus = yearly(100);
            noStatus.setStatus(null);
            assertThat(CreditAttributionService.nextCreditGrantAt(noStatus, PERIOD_START)).isNull();
        }

        @Test
        @DisplayName("a yearly row with no period start falls back to the renewal instead of throwing")
        void yearlyRowWithNoPeriodStartFallsBackToTheRenewal() {
            Subscription sub = yearly(100);
            sub.setCurrentPeriodStart(null);

            assertThat(CreditAttributionService.nextCreditGrantAt(sub, PERIOD_START.plusDays(20)))
                    .isEqualTo(PERIOD_END_YEARLY);
        }
    }
}
