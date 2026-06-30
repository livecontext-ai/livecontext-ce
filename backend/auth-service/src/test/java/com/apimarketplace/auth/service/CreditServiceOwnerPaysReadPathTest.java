package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.repository.CreditLedgerRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression - read-path owner-pays routing.
 *
 * <p>Audit 1 (2026-05-17, Bug B): the WRITE path was correctly redirecting
 * a guest's consumes to the owner's wallet, but the READ path
 * ({@link CreditService#getBalance}, {@link CreditService#isDelinquent},
 * {@link CreditService#hasSufficientCredits}, {@link CreditService#canAfford})
 * still queried the executor's wallet. Result: a guest user calling
 * {@code GET /api/credits/balance} saw {@code 0} while their consumes
 * succeeded (and debited the owner). Pre-flight {@code canAfford} fired
 * on the guest's empty wallet, blocking legitimate chats.
 *
 * <p>Fix: every read-path entry point now calls {@code resolvePayer(userId)}
 * before looking up the subscription. This test pins that contract by
 * mocking {@link PlanResolutionService#resolvePayerUserId} so a guest's
 * read returns the owner's wallet state.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CreditService - owner-pays read-path routing")
class CreditServiceOwnerPaysReadPathTest {

    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private CreditLedgerRepository ledgerRepository;
    @Mock private ModelPricingService pricingService;
    @Mock private PlanResolutionService planResolutionService;

    private static final Long GUEST_ID = 5L;
    private static final Long OWNER_ID = 1L;
    private static final BigDecimal OWNER_BALANCE = new BigDecimal("250.0000");

    private CreditService service;

    @BeforeEach
    void setUp() {
        service = new CreditService(
                subscriptionRepository, ledgerRepository, pricingService,
                /* unlimited */ false, /* markupEnabled */ true, /* markupShadow */ false);
        ReflectionTestUtils.setField(service, "planResolutionService", planResolutionService);

        // Guest is a member of an org owned by OWNER_ID. Every read routed
        // through resolvePayer(GUEST_ID) must come back as OWNER_ID. Lenient
        // because findSubscriptionForUpdateUsesArgumentVerbatim doesn't
        // exercise the GUEST→OWNER redirect path.
        lenient().when(planResolutionService.resolvePayerUserId(GUEST_ID)).thenReturn(OWNER_ID);
        // resolveActiveSubscription re-routes through resolvePayer (idempotent
        // on the payer), so the helper sees OWNER_ID a second time. Stub the
        // owner-fix-point identity-resolve so strict mocking stays happy.
        lenient().when(planResolutionService.resolvePayerUserId(OWNER_ID)).thenReturn(OWNER_ID);
    }

    @Test
    @DisplayName("getBalance(guest) returns the OWNER's balance, not 0")
    void getBalanceReturnsOwnerBalance() {
        Subscription ownerSub = new Subscription();
        ownerSub.setRemainingCredits(OWNER_BALANCE);
        ownerSub.setDelinquent(false);
        when(subscriptionRepository.findActiveByUserId(OWNER_ID)).thenReturn(Optional.of(ownerSub));

        BigDecimal balance = service.getBalance(GUEST_ID);

        assertThat(balance)
                .as("Owner-pays: read-path routes through resolvePayer; guest sees OWNER's balance, not their own empty wallet")
                .isEqualByComparingTo(OWNER_BALANCE);
        // Verifies that the lookup ALWAYS went to OWNER_ID - never to GUEST_ID.
        verify(subscriptionRepository, never()).findActiveByUserId(eq(GUEST_ID));
    }

    @Test
    @DisplayName("hasSufficientCredits(guest) reflects the OWNER's balance")
    void hasSufficientCreditsReflectsOwnerBalance() {
        Subscription ownerSub = new Subscription();
        ownerSub.setRemainingCredits(OWNER_BALANCE);
        when(subscriptionRepository.findActiveByUserId(OWNER_ID)).thenReturn(Optional.of(ownerSub));

        assertThat(service.hasSufficientCredits(GUEST_ID))
                .as("hasSufficientCredits routes through getBalance → resolvePayer")
                .isTrue();
        verify(subscriptionRepository, never()).findActiveByUserId(eq(GUEST_ID));
    }

    @Test
    @DisplayName("hasSufficientCredits(guest) returns false when OWNER has zero balance")
    void hasSufficientCreditsRefusesWhenOwnerEmpty() {
        Subscription ownerSub = new Subscription();
        ownerSub.setRemainingCredits(BigDecimal.ZERO);
        when(subscriptionRepository.findActiveByUserId(OWNER_ID)).thenReturn(Optional.of(ownerSub));

        assertThat(service.hasSufficientCredits(GUEST_ID))
                .as("Owner-pays: an empty OWNER wallet must gate the guest's consumes - not the guest's own wallet")
                .isFalse();
    }

    @Test
    @DisplayName("isDelinquent(guest) mirrors the OWNER's delinquency flag")
    void isDelinquentMirrorsOwner() {
        Subscription ownerSub = new Subscription();
        ownerSub.setRemainingCredits(BigDecimal.ZERO);
        ownerSub.setDelinquent(true);
        when(subscriptionRepository.findActiveByUserId(OWNER_ID)).thenReturn(Optional.of(ownerSub));

        assertThat(service.isDelinquent(GUEST_ID))
                .as("Owner-pays: guest's delinquency state IS owner's delinquency state - same wallet")
                .isTrue();
        verify(subscriptionRepository, never()).findActiveByUserId(eq(GUEST_ID));
    }

    @Test
    @DisplayName("canAfford(guest, cost) gates on the OWNER's balance, not the guest's empty wallet")
    void canAffordGatesOnOwnerBalance() {
        Subscription ownerSub = new Subscription();
        ownerSub.setRemainingCredits(OWNER_BALANCE);
        when(subscriptionRepository.findActiveByUserId(OWNER_ID)).thenReturn(Optional.of(ownerSub));

        // Projected cost = 100 → owner has 250 → afford OK
        assertThat(service.canAfford(GUEST_ID, new BigDecimal("100.0000")))
                .as("Owner-pays: pre-flight check passes when OWNER's balance covers the projected cost")
                .isTrue();

        // Projected cost = 1000 → owner has 250 → afford NOT OK (gates on owner, not member)
        assertThat(service.canAfford(GUEST_ID, new BigDecimal("1000.0000")))
                .as("Owner-pays: pre-flight check refuses when OWNER's balance falls short, regardless of member balance")
                .isFalse();
    }

    @Test
    @DisplayName("resolveActiveSubscription(guest) returns the OWNER's subscription row (pinned for downstream callers)")
    void resolveActiveSubscriptionReturnsOwnersRow() {
        Subscription ownerSub = new Subscription();
        ownerSub.setRemainingCredits(OWNER_BALANCE);
        when(subscriptionRepository.findActiveByUserId(OWNER_ID)).thenReturn(Optional.of(ownerSub));

        Subscription resolved = service.resolveActiveSubscription(GUEST_ID);

        assertThat(resolved)
                .as("Guest's active sub IS owner's active sub under owner-pays")
                .isSameAs(ownerSub);
        verify(subscriptionRepository, never()).findActiveByUserId(eq(GUEST_ID));
    }

    @Test
    @DisplayName("findSubscriptionForUpdate(payerId) takes PESSIMISTIC_WRITE on the payer's row verbatim - callers pre-resolve")
    void findSubscriptionForUpdateUsesArgumentVerbatim() {
        // The lock-taking helper does NOT re-resolve through resolvePayer:
        // callers (consume / grant / refund paths) have already determined the
        // payer userId and pass it directly. Re-resolving here would silently
        // redirect the marketplace bypass to the workspace owner's wallet.
        Subscription ownerSub = new Subscription();
        ownerSub.setRemainingCredits(OWNER_BALANCE);
        when(subscriptionRepository.findActiveByUserIdForUpdate(OWNER_ID)).thenReturn(Optional.of(ownerSub));

        ReflectionTestUtils.invokeMethod(service, "findSubscriptionForUpdate", OWNER_ID);

        verify(subscriptionRepository).findActiveByUserIdForUpdate(OWNER_ID);
        verify(subscriptionRepository, never()).findActiveByUserIdForUpdate(eq(GUEST_ID));
    }
}
