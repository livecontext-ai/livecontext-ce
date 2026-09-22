package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.repository.CreditLedgerRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Monthly refill of the AI allowance (V494).
 *
 * <p>The allowance is what lets a Free account talk to an agent, so it has to come
 * back every cycle - and it has to come back as a SET, not an add: it is an allowance,
 * not a credit grant that accumulates.
 *
 * <p>It is refilled from {@code attributeOnRenewal} rather than from
 * {@code resetBalance}, because that helper early-returns when the workflow bucket is
 * already zero. Hooking the refill there would have skipped exactly the accounts that
 * had spent everything, which are the ones the refill exists for. That is what the
 * "spent to zero" case below pins.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CreditAttributionService - monthly AI allowance refill")
class CreditAttributionAiAllowanceRefillTest {

    private static final Long USER_ID = 42L;
    private static final Long SUB_ID = 7L;
    private static final LocalDateTime NEW_PERIOD_START = LocalDateTime.of(2026, 9, 12, 0, 0);

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

    @org.junit.jupiter.api.BeforeEach
    void stubGrantPath() {
        // The cycle's normal credit grant runs before the refill; without a result the
        // renewal NPEs on .success() and never reaches the code under test.
        when(creditService.grantCredits(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(BigDecimal.class),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(CreditService.CreditConsumeResult.success(BigDecimal.ZERO, BigDecimal.ZERO));
    }

    private Subscription internalSubscription(String planCode, Integer planAiAllowance, String currentAi) {
        return internalSubscription(planCode, planAiAllowance, currentAi, true);
    }

    /**
     * @param emailVerified the owner's state. The allowance is only ever granted to a
     *                      verified account, on EVERY leg - the renewal included, which
     *                      is the leg the creation-path gate does not cover.
     */
    private Subscription internalSubscription(String planCode, Integer planAiAllowance, String currentAi,
                                              boolean emailVerified) {
        Plan plan = new Plan(planCode, planCode, "");
        plan.setIncludedLlmTokens(1000L);
        plan.setIncludedAiCredits(planAiAllowance);

        Subscription sub = new Subscription();
        sub.setId(SUB_ID);
        sub.setPlan(plan);
        sub.setProvider("internal");
        sub.setStatus("active");
        sub.setCreditQuantity(0);
        sub.setCurrentPeriodStart(NEW_PERIOD_START.minusMonths(1));
        sub.setCurrentPeriodEnd(NEW_PERIOD_START.minusDays(1));
        sub.setRemainingCredits(BigDecimal.ZERO);
        sub.setPaygRemainingCredits(BigDecimal.ZERO);
        sub.setAiRemainingCredits(new BigDecimal(currentAi));

        com.apimarketplace.auth.domain.User owner = new com.apimarketplace.auth.domain.User();
        owner.setId(USER_ID);
        owner.setEmailVerified(emailVerified);
        com.apimarketplace.auth.domain.BillingCustomer customer =
                new com.apimarketplace.auth.domain.BillingCustomer(owner, "internal");
        sub.setBillingCustomer(customer);

        when(subscriptionRepository.findByIdForUpdate(SUB_ID)).thenReturn(Optional.of(sub));
        return sub;
    }

    @Test
    @DisplayName("a Free account spent to zero gets its full allowance back")
    void spentAllowanceIsRestored() {
        Subscription sub = internalSubscription("FREE", 100, "0.00");

        attributionService.attributeOnRenewal(USER_ID, sub, NEW_PERIOD_START);

        assertThat(sub.getAiRemainingCredits()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("an unused remainder does NOT roll over - the allowance is set, not added")
    void unusedAllowanceDoesNotAccumulate() {
        Subscription sub = internalSubscription("FREE", 100, "80.00");

        attributionService.attributeOnRenewal(USER_ID, sub, NEW_PERIOD_START);

        assertThat(sub.getAiRemainingCredits())
                .as("80 left + a 100 allowance must be 100, never 180")
                .isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("an admin raising the plan's allowance takes effect at the next renewal")
    void raisedAllowanceTakesEffectOnRenewal() {
        Subscription sub = internalSubscription("FREE", 250, "0.00");

        attributionService.attributeOnRenewal(USER_ID, sub, NEW_PERIOD_START);

        assertThat(sub.getAiRemainingCredits()).isEqualByComparingTo("250.00");
    }

    @Test
    @DisplayName("a plan with no allowance keeps an empty pot empty")
    void planWithoutAllowanceStaysEmpty() {
        Subscription sub = internalSubscription("PRO", null, "0.00");

        attributionService.attributeOnRenewal(USER_ID, sub, NEW_PERIOD_START);

        assertThat(sub.getAiRemainingCredits())
                .as("giving a paid plan a pot would route its agent spend away from the wallet it pays for")
                .isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("a plan with no allowance CLEARS a pot inherited from the plan the account left")
    void planWithoutAllowanceClearsAnInheritedPot() {
        // The case a zero-valued fixture cannot see, and the one that matters:
        // AdminPlanService swaps the plan on the EXISTING subscription row, so a Free
        // account with credits left that is moved to a paid or comp plan arrives here
        // still holding a pot its new plan must not have. An implementation that
        // returns early on a null allowance leaves those credits spendable forever on
        // free-tier models - and would pass every other test in this class.
        Subscription sub = internalSubscription("PRO", null, "80.00");

        attributionService.attributeOnRenewal(USER_ID, sub, NEW_PERIOD_START);

        assertThat(sub.getAiRemainingCredits())
                .as("an entitlement of the plan the account just left must not survive the move")
                .isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("a negative configured allowance is clamped to zero rather than creating a debt")
    void negativeAllowanceIsClamped() {
        Subscription sub = internalSubscription("FREE", -50, "10.00");

        attributionService.attributeOnRenewal(USER_ID, sub, NEW_PERIOD_START);

        assertThat(sub.getAiRemainingCredits()).isEqualByComparingTo("0.00");
    }
    /**
     * The FIRST grant, which is also the only thing standing between the allowance and
     * every unverified throwaway signup.
     *
     * <p>{@code attributeOnSubscription} is reached for a FREE row only through
     * {@code UserResolutionService.attributeCreditsIfEligible}, which returns early
     * unless the email is verified. Seeding the pot where the subscription row is
     * CREATED bypasses that gate entirely, which is what the two creation-path tests
     * ({@code FreeSubscriptionProvisionerTest}, {@code BillingControllerFreeAllowanceTest})
     * now forbid. Here is where it must happen instead.
     */
    @org.junit.jupiter.api.Nested
    @DisplayName("the first grant, behind the email-verification gate")
    class FirstGrant {

        @Test
        @DisplayName("seeds the allowance alongside the plan's monthly credits")
        void seedsTheAllowanceWithTheCredits() {
            Subscription sub = internalSubscription("FREE", 100, "0.00");
            when(ledgerRepository.existsBySourceId("plan_sub_" + SUB_ID + "_init")).thenReturn(false);

            attributionService.attributeOnSubscription(USER_ID, sub, 0);

            assertThat(sub.getAiRemainingCredits())
                    .as("a verified Free account must be able to run an agent right away")
                    .isEqualByComparingTo("100");
        }

        @Test
        @DisplayName("does NOT top the pot back up on a later login")
        void laterLoginsDoNotRefill() {
            // This runs on EVERY resolveUser. A refill that did not key off the ledger
            // row would restore the pot to full on each one: an unlimited allowance,
            // refilled by reloading the page. The spent pot below must stay spent.
            Subscription sub = internalSubscription("FREE", 100, "3.00");
            when(ledgerRepository.existsBySourceId("plan_sub_" + SUB_ID + "_init")).thenReturn(true);

            attributionService.attributeOnSubscription(USER_ID, sub, 0);

            assertThat(sub.getAiRemainingCredits())
                    .as("the monthly renewal refills it, not a page reload")
                    .isEqualByComparingTo("3.00");
        }

        @Test
        @DisplayName("a paid subscription gets no pot from this path")
        void paidSubscriptionGetsNoPot() {
            // Fixtured with a plan that DOES configure an allowance, so the assertion
            // discriminates: a zero-allowance plan starting at zero would read the same
            // whether the branch ran or not. A Stripe row takes the base-pack branch,
            // which never reaches the refill - handing it a pot would route its agent
            // spend away from the wallet it pays for.
            Subscription sub = internalSubscription("PRO", 100, "0.00");
            sub.setProvider("stripe");

            attributionService.attributeOnSubscription(USER_ID, sub, 0);

            assertThat(sub.getAiRemainingCredits()).isEqualByComparingTo("0.00");
        }
    }
    @org.junit.jupiter.api.Nested
    @DisplayName("the renewal leg of the e-mail-verification gate")
    class VerificationGate {

        @Test
        @DisplayName("an UNVERIFIED account gets no pot at renewal, thirty days later or ever")
        void unverifiedAccountIsNotRefilled() {
            // The hole this closes: the scheduler selects on provider='internal' and an
            // expired period alone, and every signup's row expires after a month. Without
            // the check, the creation-path gate is not a gate at all - it is a 30-day
            // delay, after which every unverified throwaway account is handed a full pot
            // of platform-key inference, hourly and unattended.
            Subscription sub = internalSubscription("FREE", 100, "0.00", false);

            attributionService.attributeOnRenewal(USER_ID, sub, NEW_PERIOD_START);

            assertThat(sub.getAiRemainingCredits()).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("an unverified account that somehow holds a pot has it CLEARED, not left")
        void unverifiedAccountIsCleared() {
            // Same direction as a plan with no allowance: whatever it was granted by
            // (an older build, a restored snapshot), it must not keep it.
            Subscription sub = internalSubscription("FREE", 100, "80.00", false);

            attributionService.attributeOnRenewal(USER_ID, sub, NEW_PERIOD_START);

            assertThat(sub.getAiRemainingCredits()).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("an owner that cannot be resolved is treated as unverified")
        void unresolvableOwnerWithholdsThePot() {
            // Fail closed. A verified account wrongly skipped gets its pot next cycle;
            // a wrongly granted one is inference nobody can take back.
            Subscription sub = internalSubscription("FREE", 100, "0.00", true);
            sub.setBillingCustomer(null);

            attributionService.attributeOnRenewal(USER_ID, sub, NEW_PERIOD_START);

            assertThat(sub.getAiRemainingCredits()).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("the first grant is gated too, so neither leg can be the weak one")
        void firstGrantIsGatedAsWell() {
            Subscription sub = internalSubscription("FREE", 100, "0.00", false);
            when(ledgerRepository.existsBySourceId("plan_sub_" + SUB_ID + "_init")).thenReturn(false);

            attributionService.attributeOnSubscription(USER_ID, sub, 0);

            assertThat(sub.getAiRemainingCredits()).isEqualByComparingTo("0.00");
        }
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("the refill is idempotent within a period")
    class Idempotency {

        /**
         * A ledger that REMEMBERS, instead of a hand-stubbed precondition.
         *
         * <p>The previous version of these two tests stubbed
         * {@code existsBySourceId("reset_…")} to the answer it wanted. That established
         * by hand the very state production failed to establish: {@code resetBalance}
         * returns before writing its row whenever the sub bucket is already zero, which
         * is the ordinary state of an account that spent its month - so the marker never
         * existed, the guard re-armed on every call, and a redelivered webhook refilled
         * the pot. The test asserted the {@code if}, not the system.
         */
        private void recordWrites() {
            java.util.Set<String> written = new java.util.HashSet<>();
            when(ledgerRepository.existsBySourceId(org.mockito.ArgumentMatchers.anyString()))
                    .thenAnswer(inv -> written.contains(inv.getArgument(0, String.class)));
            when(ledgerRepository.save(org.mockito.ArgumentMatchers.any()))
                    .thenAnswer(inv -> {
                        com.apimarketplace.auth.domain.CreditLedgerEntry e = inv.getArgument(0);
                        if (e.getSourceId() != null) written.add(e.getSourceId());
                        return e;
                    });
            // grantPlanCredits goes through CreditService, which writes its own row; the
            // fake records it so the second call sees the same world the first left.
            when(creditService.grantCredits(org.mockito.ArgumentMatchers.anyLong(),
                    org.mockito.ArgumentMatchers.any(BigDecimal.class),
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyString()))
                    .thenAnswer(inv -> {
                        written.add(inv.getArgument(3, String.class));
                        return CreditService.CreditConsumeResult.success(BigDecimal.ZERO, BigDecimal.ZERO);
                    });
        }

        @Test
        @DisplayName("a SECOND renewal for the same period does not hand out another pot")
        void secondAttributionInTheSamePeriodIsSkipped() {
            // A redelivered Stripe invoice.paid: WebhookController calls
            // attributeOnRenewal with newPeriodStart = null, so both deliveries land on
            // the same period and the same keys. The account is renewed at ZERO, which
            // is what made the old marker unwritable.
            recordWrites();
            Subscription sub = internalSubscription("FREE", 100, "0.00");

            attributionService.attributeOnRenewal(USER_ID, sub);
            assertThat(sub.getAiRemainingCredits())
                    .as("the first delivery grants the pot")
                    .isEqualByComparingTo("100.00");

            sub.setAiRemainingCredits(new BigDecimal("3.00"));
            attributionService.attributeOnRenewal(USER_ID, sub);

            assertThat(sub.getAiRemainingCredits())
                    .as("the second must change nothing - the period was already attributed")
                    .isEqualByComparingTo("3.00");
        }

        @Test
        @DisplayName("a plan that grants NO credits still has its pot cleared, on every call")
        void aPlanThatGrantsNothingStillClears() {
            // The third branch, and the one with no ledger row to key on: neither grant
            // runs, so nothing marks the period. Refilling unguarded is safe here ONLY
            // because the refill degenerates to a clear - the plan has no allowance to
            // hand out - and clearing an already-clear pot is idempotent. This pins that
            // premise: if the branch ever started granting, the repetition would matter.
            recordWrites();
            Subscription sub = internalSubscription("COMP", null, "40.00");
            sub.getPlan().setIncludedLlmTokens(0L);

            attributionService.attributeOnRenewal(USER_ID, sub);
            assertThat(sub.getAiRemainingCredits())
                    .as("a pot inherited from the plan the account left must not survive")
                    .isEqualByComparingTo("0.00");

            sub.setAiRemainingCredits(new BigDecimal("40.00"));
            attributionService.attributeOnRenewal(USER_ID, sub);
            assertThat(sub.getAiRemainingCredits())
                    .as("and a repeat is a clear again, never a grant")
                    .isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("a negative credit quantity is not read as 'already attributed'")
        void negativeQuantityStillClearsThePot() {
            // grantPackCredits returns false both for "already granted" and for "nothing
            // to grant". Collapsing the two would skip the clear on exactly the plan the
            // clear exists for.
            recordWrites();
            Subscription sub = internalSubscription("PRO", null, "40.00");
            sub.setCreditQuantity(-1);

            attributionService.attributeOnRenewal(USER_ID, sub);

            assertThat(sub.getAiRemainingCredits()).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("a renewal into a NEW period refills again, so the guard is not a block")
        void aNewPeriodRefills() {
            recordWrites();
            Subscription sub = internalSubscription("FREE", 100, "0.00");
            attributionService.attributeOnRenewal(USER_ID, sub, NEW_PERIOD_START);
            sub.setAiRemainingCredits(new BigDecimal("3.00"));

            // A month later: a different period, therefore different sourceIds.
            attributionService.attributeOnRenewal(USER_ID, sub, NEW_PERIOD_START.plusMonths(1));

            assertThat(sub.getAiRemainingCredits()).isEqualByComparingTo("100.00");
        }
    }
}
