package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.*;
import com.apimarketplace.auth.repository.*;
import com.apimarketplace.auth.validation.UsernameValidator;
import com.stripe.StripeClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PersonalOfferServiceTest {
    private final PersonalOfferPolicyRepository policies = mock(PersonalOfferPolicyRepository.class);
    private final PersonalOfferMatrixRepository matrix = mock(PersonalOfferMatrixRepository.class);
    private final PersonalOfferCheckoutAttemptRepository attempts = mock(PersonalOfferCheckoutAttemptRepository.class);
    private final PersonalOfferFirstPaidPurchaseRepository firstPaid = mock(PersonalOfferFirstPaidPurchaseRepository.class);
    private final RewardCodeRepository codes = mock(RewardCodeRepository.class);
    private final RewardRedemptionRepository redemptions = mock(RewardRedemptionRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final SubscriptionRepository subscriptions = mock(SubscriptionRepository.class);
    private final BillingCustomerRepository customers = mock(BillingCustomerRepository.class);
    private final StripeClient stripe = mock(StripeClient.class);
    private PersonalOfferService service;
    private RewardCode offer;
    private PersonalOfferPolicy policy;

    @BeforeEach
    void setUp() {
        service = new PersonalOfferService(policies, matrix, attempts, firstPaid, codes,
                redemptions, users, subscriptions, customers, stripe, new UsernameValidator(users));
        offer = new RewardCode();
        offer.setId(50L);
        offer.setCode("OWNEDCODE123");
        offer.setProgram(RewardProgram.PERSONAL_UPGRADE);
        offer.setRecipientUserId(7L);
        offer.setPolicyVersionId(3L);
        offer.setActive(true);
        offer.setValidFrom(Instant.now().minusSeconds(7200));
        offer.setValidUntil(Instant.now().minusSeconds(60));
        policy = new PersonalOfferPolicy();
        policy.setId(3L);
        policy.setVersion(2);
        policy.setPaygCreditsPerUsd(800);
        when(policies.findById(3L)).thenReturn(Optional.of(policy));
        when(codes.findById(50L)).thenReturn(Optional.of(offer));
        when(codes.findByCodeIgnoreCase("OWNEDCODE123")).thenReturn(Optional.of(offer));
    }

    @Test
    void previewRejectsCodeBoundToAnotherAccountWithoutRevealingItsTerms() {
        assertThatThrownBy(() -> service.preview(8L, null, "OWNEDCODE123", 0, "monthly"))
                .isInstanceOf(PersonalOfferService.OfferException.class)
                .hasMessage("OFFER_UNAVAILABLE");
        verifyNoInteractions(matrix);
    }

    @Test
    @DisplayName("the preview carries the offer's bonus tiers, the same steps the email states, for the page's ladder")
    void previewCarriesTheTiers() {
        offer.setValidUntil(Instant.now().plusSeconds(3600));
        PersonalOfferFirstPaidPurchase history = new PersonalOfferFirstPaidPurchase();
        history.setStatus("VERIFIED_NEW");
        when(firstPaid.findById(7L)).thenReturn(Optional.of(history));
        List<PersonalOfferMatrix> cells = new java.util.ArrayList<>();
        for (String plan : List.of("STARTER", "PRO", "TEAM")) {
            for (int pack : new int[] {5_000, 10_000, 25_000, 50_000, 100_000, 250_000, 500_000, 1_000_000, 5_000_000, 10_000_000}) {
                if (plan.equals("STARTER") && pack > 100_000) continue;
                PersonalOfferMatrix cell = new PersonalOfferMatrix();
                cell.setPlanCode(plan);
                cell.setMonthlyCredits(pack);
                cell.setBonusCredits(pack >= 500_000 ? 80_000 : pack >= 250_000 ? 40_000 : pack >= 50_000 ? 8_000 : 0);
                cells.add(cell);
            }
        }
        when(matrix.findByPolicyId(3L)).thenReturn(cells);
        when(matrix.findByPolicyIdAndPlanCodeAndMonthlyCredits(eq(3L), anyString(), eq(5_000)))
                .thenReturn(Optional.of(cells.get(0)));

        var preview = service.preview(7L, null, "OWNEDCODE123", 0, "monthly");

        assertThat(preview.steps()).containsExactly(
                new PersonalOfferSteps.Step(50_000, 8_000),
                new PersonalOfferSteps.Step(250_000, 40_000),
                new PersonalOfferSteps.Step(500_000, 80_000));
        assertThat(preview.nextEligibleMonthlyCredits()).isEqualTo(50_000);
    }

    @Test
    void expiredMarketingCodeStillPreviewsSameLiveReservation() {
        PersonalOfferCheckoutAttempt reserved = reservation();
        when(attempts.findByRecipientUserIdOrderByCreatedAtDesc(7L)).thenReturn(List.of(reserved));
        PersonalOfferFirstPaidPurchase history = new PersonalOfferFirstPaidPurchase();
        history.setStatus("VERIFIED_NEW");
        when(firstPaid.findById(7L)).thenReturn(Optional.of(history));
        PersonalOfferMatrix row = new PersonalOfferMatrix();
        row.setBonusCredits(8_000);
        when(matrix.findByPolicyIdAndPlanCodeAndMonthlyCredits(3L, "PRO", 5_000))
                .thenReturn(Optional.of(row));
        when(matrix.findByPolicyId(3L)).thenReturn(List.of(row));

        var preview = service.preview(7L, null, "OWNEDCODE123", 0, "monthly");

        assertThat(preview.status()).isEqualTo("CHECKOUT_OPEN");
        assertThat(preview.plans()).filteredOn(p -> p.planCode().equals("PRO"))
                .singleElement().satisfies(p -> assertThat(p.bonusCredits()).isEqualTo(8_000));
        assertThat(preview.plans()).filteredOn(p -> !p.planCode().equals("PRO"))
                .allSatisfy(p -> assertThat(p.status()).isEqualTo("UNAVAILABLE"));
    }

    @Test
    void identicalReservationCanBeReusedAfterMarketingDeadlineWithoutNewSession() {
        PersonalOfferCheckoutAttempt reserved = reservation();
        User user = new User();
        user.setId(7L);
        when(users.lockForPersonalOffer(7L)).thenReturn(Optional.of(user));
        when(attempts.findPayableForUpdate(7L)).thenReturn(List.of(reserved));
        PersonalOfferFirstPaidPurchase history = new PersonalOfferFirstPaidPurchase();
        history.setStatus("VERIFIED_NEW");
        when(firstPaid.findById(7L)).thenReturn(Optional.of(history));
        Plan free = new Plan();
        free.setCode("FREE");
        Subscription subscription = new Subscription();
        subscription.setPlan(free);
        when(subscriptions.findActiveByUserId(7L)).thenReturn(Optional.of(subscription));
        PersonalOfferMatrix row = new PersonalOfferMatrix();
        row.setBonusCredits(8_000);
        when(matrix.findByPolicyIdAndPlanCodeAndMonthlyCredits(3L, "PRO", 5_000))
                .thenReturn(Optional.of(row));

        var prepared = service.prepareCheckout(7L, 50L, 2, "PRO", 0, "monthly",
                "price_plan", null);

        assertThat(prepared.reused()).isTrue();
        assertThat(prepared.attempt().getId()).isEqualTo(reserved.getId());
        verify(attempts, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("regression: past the deadline, another choice is refused before the reservation's Stripe session is expired, so the reservation stays payable")
    void otherChoicePastDeadlineKeepsTheReservation() {
        PersonalOfferCheckoutAttempt reserved = reservation();
        reserved.setStripeSessionId("cs_reserved");
        User user = new User();
        user.setId(7L);
        when(users.lockForPersonalOffer(7L)).thenReturn(Optional.of(user));
        when(attempts.findPayableForUpdate(7L)).thenReturn(List.of(reserved));
        PersonalOfferFirstPaidPurchase history = new PersonalOfferFirstPaidPurchase();
        history.setStatus("VERIFIED_NEW");
        when(firstPaid.findById(7L)).thenReturn(Optional.of(history));
        Plan free = new Plan();
        free.setCode("FREE");
        Subscription subscription = new Subscription();
        subscription.setPlan(free);
        when(subscriptions.findActiveByUserId(7L)).thenReturn(Optional.of(subscription));
        PersonalOfferMatrix row = new PersonalOfferMatrix();
        row.setBonusCredits(8_000);
        when(matrix.findByPolicyIdAndPlanCodeAndMonthlyCredits(3L, "TEAM", 5_000)).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.prepareCheckout(7L, 50L, 2, "TEAM", 0, "monthly", "price_team", null))
                .isInstanceOf(PersonalOfferService.OfferException.class)
                .hasMessage("OFFER_EXPIRED");
        verifyNoInteractions(stripe);
        assertThat(reserved.getStatus()).isEqualTo("OPEN");
        verify(attempts, never()).save(any());
    }

    @Test
    @DisplayName("before the deadline, another choice replaces the open checkout: its Stripe session is expired and a new reservation is prepared")
    void otherChoiceBeforeDeadlineReplacesTheReservation() throws Exception {
        StripeClient deepStripe = mock(StripeClient.class, RETURNS_DEEP_STUBS);
        service = new PersonalOfferService(policies, matrix, attempts, firstPaid, codes,
                redemptions, users, subscriptions, customers, deepStripe, new UsernameValidator(users));
        offer.setValidUntil(Instant.now().plusSeconds(3600));
        PersonalOfferCheckoutAttempt reserved = reservation();
        reserved.setStripeSessionId("cs_old");
        User user = new User();
        user.setId(7L);
        when(users.lockForPersonalOffer(7L)).thenReturn(Optional.of(user));
        when(attempts.findPayableForUpdate(7L)).thenReturn(List.of(reserved));
        PersonalOfferFirstPaidPurchase history = new PersonalOfferFirstPaidPurchase();
        history.setStatus("VERIFIED_NEW");
        when(firstPaid.findById(7L)).thenReturn(Optional.of(history));
        Plan free = new Plan();
        free.setCode("FREE");
        Subscription subscription = new Subscription();
        subscription.setPlan(free);
        when(subscriptions.findActiveByUserId(7L)).thenReturn(Optional.of(subscription));
        PersonalOfferMatrix row = new PersonalOfferMatrix();
        row.setBonusCredits(8_000);
        when(matrix.findByPolicyIdAndPlanCodeAndMonthlyCredits(3L, "TEAM", 5_000)).thenReturn(Optional.of(row));
        when(attempts.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        var prepared = service.prepareCheckout(7L, 50L, 2, "TEAM", 0, "monthly", "price_team", null);

        verify(deepStripe.checkout().sessions()).expire("cs_old");
        assertThat(reserved.getStatus()).isEqualTo("EXPIRED");
        assertThat(prepared.reused()).isFalse();
        assertThat(prepared.attempt().getPlanCode()).isEqualTo("TEAM");
        assertThat(prepared.attempt().getId()).isNotEqualTo(reserved.getId());
    }

    @Test
    void attachingSessionAfterCompletionNeverRegressesPaymentState() {
        PersonalOfferCheckoutAttempt attempt = reservation();
        attempt.setStatus("COMPLETED");
        when(attempts.lockById(attempt.getId())).thenReturn(Optional.of(attempt));

        service.attachSession(attempt.getId(), "cs_1", "https://checkout.test", Instant.now().plusSeconds(1800));

        assertThat(attempt.getStatus()).isEqualTo("COMPLETED");
        assertThat(attempt.getStripeSessionId()).isEqualTo("cs_1");
        verify(attempts).save(attempt);
    }

    @Test
    void definiteStripeRefusalReleasesCreatingAttemptEvenAfterPositivePreview() {
        PersonalOfferCheckoutAttempt attempt = reservation();
        attempt.setStatus("CREATING");
        attempt.setFirstInvoicePreviewAmount(1000L);
        when(attempts.lockById(attempt.getId())).thenReturn(Optional.of(attempt));

        service.failDefinitelyRejectedCheckout(attempt.getId());

        assertThat(attempt.getStatus()).isEqualTo("FAILED");
        verify(attempts).saveAndFlush(attempt);
    }

    @Test
    void preSessionFailureCannotReleaseAttemptWithEarlierPositivePreview() {
        PersonalOfferCheckoutAttempt attempt = reservation();
        attempt.setStatus("CREATING");
        attempt.setFirstInvoicePreviewAmount(1000L);
        when(attempts.lockById(attempt.getId())).thenReturn(Optional.of(attempt));

        service.failUnsubmittedPreview(attempt.getId());

        assertThat(attempt.getStatus()).isEqualTo("CREATING");
        verify(attempts, never()).saveAndFlush(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"OPEN", "COMPLETED", "PAID", "GRANTED"})
    void lateStripeRefusalCannotRegressAttachedOrPaidAttempt(String status) {
        PersonalOfferCheckoutAttempt attempt = reservation();
        attempt.setStatus(status);
        attempt.setStripeSessionId("cs_attached");
        when(attempts.lockById(attempt.getId())).thenReturn(Optional.of(attempt));

        service.failDefinitelyRejectedCheckout(attempt.getId());

        assertThat(attempt.getStatus()).isEqualTo(status);
        verify(attempts, never()).saveAndFlush(any());
    }

    @Test
    void lateStripeRefusalCannotReleaseCreatingAttemptWithRecoveredSession() {
        PersonalOfferCheckoutAttempt attempt = reservation();
        attempt.setStatus("CREATING");
        attempt.setStripeSessionId("cs_recovered");
        when(attempts.lockById(attempt.getId())).thenReturn(Optional.of(attempt));

        service.failDefinitelyRejectedCheckout(attempt.getId());

        assertThat(attempt.getStatus()).isEqualTo("CREATING");
        verify(attempts, never()).saveAndFlush(any());
    }

    @Test
    void ordinaryPaidCheckoutConsumesOfferInCurrentWithoutShowingCode() {
        when(codes.findByRecipientUserIdAndCampaignKey(7L, "free-credit-upgrade"))
                .thenReturn(Optional.of(offer));
        when(attempts.findByRecipientUserIdOrderByCreatedAtDesc(7L)).thenReturn(List.of());
        PersonalOfferFirstPaidPurchase paid = new PersonalOfferFirstPaidPurchase();
        paid.setStatus("PAID");
        paid.setInvoiceId("in_ordinary");
        paid.setProviderSubscriptionId("sub_ordinary");
        when(firstPaid.findById(7L)).thenReturn(Optional.of(paid));

        var current = service.current(7L);

        assertThat(current.status()).isEqualTo("ALREADY_USED");
        assertThat(current.code()).isNull();
        assertThat(current.offerAttemptId()).isNull();
    }

    @Test
    @DisplayName("regression: an open checkout names the plan, pack and cycle it holds, so the offer page can open on it once the offer's own deadline has passed")
    void openCheckoutNamesItsReservation() {
        when(codes.findByRecipientUserIdAndCampaignKey(7L, "free-credit-upgrade"))
                .thenReturn(Optional.of(offer));
        PersonalOfferCheckoutAttempt reserved = reservation();
        reserved.setPlanCode("TEAM");
        reserved.setCreditTierIndex(4);
        reserved.setCadence("yearly");
        when(attempts.findByRecipientUserIdOrderByCreatedAtDesc(7L)).thenReturn(List.of(reserved));
        when(firstPaid.findById(7L)).thenReturn(Optional.empty());

        var current = service.current(7L);

        assertThat(current.status()).isEqualTo("CHECKOUT_OPEN");
        assertThat(current.reservedPlanCode()).isEqualTo("TEAM");
        assertThat(current.reservedCreditTierIndex()).isEqualTo(4);
        assertThat(current.reservedBillingCycle()).isEqualTo("yearly");
        assertThat(current.sessionExpiresAt()).isEqualTo(reserved.getSessionExpiresAt());
    }

    @Test
    @DisplayName("regression: a checkout still being created reads as such, not as another benefit in the way")
    void creatingCheckoutIsNotAConflict() {
        offer.setValidUntil(Instant.now().plusSeconds(3600));
        when(codes.findByRecipientUserIdAndCampaignKey(7L, "free-credit-upgrade")).thenReturn(Optional.of(offer));
        PersonalOfferCheckoutAttempt creating = reservation();
        creating.setStatus("CREATING");
        when(attempts.findByRecipientUserIdOrderByCreatedAtDesc(7L)).thenReturn(List.of(creating));
        when(firstPaid.findById(7L)).thenReturn(Optional.empty());

        assertThat(service.current(7L).status()).isEqualTo("CHECKOUT_CREATING");
    }

    @Test
    @DisplayName("regression: the preview refuses a bonus another conversion benefit stands in the way of, as the checkout would")
    void previewSaysTheConflict() {
        offer.setValidUntil(Instant.now().plusSeconds(3600));
        PersonalOfferFirstPaidPurchase history = new PersonalOfferFirstPaidPurchase();
        history.setStatus("VERIFIED_NEW");
        when(firstPaid.findById(7L)).thenReturn(Optional.of(history));
        for (RewardProgram program : List.of(RewardProgram.REFERRAL, RewardProgram.PARTNER)) {
            when(redemptions.findByRedeemerUserIdAndProgram(eq(7L), any())).thenReturn(Optional.empty());
            when(redemptions.findByRedeemerUserIdAndProgram(7L, program)).thenReturn(Optional.of(new RewardRedemption()));
            assertThatThrownBy(() -> service.preview(7L, null, "OWNEDCODE123", 0, "monthly"))
                    .as(program.name())
                    .isInstanceOf(PersonalOfferService.OfferException.class)
                    .hasMessage("OFFER_CONFLICT");
        }

        // A policy that allows stacking shows the bonus.
        policy.setAllowConversionStack(true);
        PersonalOfferMatrix row = new PersonalOfferMatrix();
        row.setBonusCredits(8_000);
        when(matrix.findByPolicyIdAndPlanCodeAndMonthlyCredits(eq(3L), anyString(), eq(5_000))).thenReturn(Optional.of(row));
        assertThat(service.preview(7L, null, "OWNEDCODE123", 0, "monthly").plans())
                .filteredOn(p -> p.planCode().equals("PRO"))
                .singleElement().satisfies(p -> assertThat(p.bonusCredits()).isEqualTo(8_000));
    }

    @Test
    @DisplayName("no reservation is named outside an open checkout")
    void noReservationOutsideAnOpenCheckout() {
        offer.setValidUntil(Instant.now().plusSeconds(3600));
        when(codes.findByRecipientUserIdAndCampaignKey(7L, "free-credit-upgrade"))
                .thenReturn(Optional.of(offer));
        when(attempts.findByRecipientUserIdOrderByCreatedAtDesc(7L)).thenReturn(List.of());
        when(firstPaid.findById(7L)).thenReturn(Optional.empty());

        var current = service.current(7L);

        assertThat(current.status()).isEqualTo("AVAILABLE");
        assertThat(current.reservedPlanCode()).isNull();
        assertThat(current.reservedCreditTierIndex()).isNull();
        assertThat(current.reservedBillingCycle()).isNull();
    }

    @ParameterizedTest
    @CsvSource({"lucas,LUCAS-BONUS-72H", "lucas_75,LUCAS_75-BONUS-72H", "Élodie,ELODIE-BONUS-72H"})
    @DisplayName("Issued offers use the username and policy duration instead of the recipient's first name")
    void issuesUsernameAndDurationCode(String username, String expectedCode) {
        User user = eligibleRecipient();
        user.setFirstName("Different first name");
        user.setLastName("Different family name");
        user.setUsername(username);

        var issued = service.issueForEligible(7L, "free-credit-upgrade", Instant.now()).orElseThrow();

        assertThat(issued.code()).isEqualTo(expectedCode);
    }

    @Test
    @DisplayName("A code collision after username normalization receives an available numeric suffix")
    void normalizedUsernameSkipsOccupiedNumericSuffix() {
        eligibleRecipient().setUsername("Lucas");
        when(codes.findByCodeIgnoreCase("LUCAS-BONUS-72H")).thenReturn(Optional.of(offer));
        when(codes.countByCodeStartingWithIgnoreCase("LUCAS-BONUS-72H")).thenReturn(2L);
        when(codes.findByCodeIgnoreCase("LUCAS-BONUS-72H-3")).thenReturn(Optional.of(offer));

        var issued = service.issueForEligible(7L, "free-credit-upgrade", Instant.now()).orElseThrow();

        assertThat(issued.code()).isEqualTo("LUCAS-BONUS-72H-4");
    }

    @ParameterizedTest
    @ValueSource(ints = {24, 72, 168})
    @DisplayName("The duration in the code matches the actual validity fixed at issuance")
    void codeDurationMatchesAbsoluteExpiry(int validityHours) {
        eligibleRecipient().setUsername("lucas");
        policy.setValidityHours(validityHours);
        Instant now = Instant.parse("2026-09-29T18:30:00Z");

        var issued = service.issueForEligible(7L, "free-credit-upgrade", now).orElseThrow();

        assertThat(issued.code()).isEqualTo("LUCAS-BONUS-" + validityHours + "H");
        assertThat(issued.expiresAt()).isEqualTo(now.plusSeconds(validityHours * 3600L));
    }

    @Test
    @DisplayName("A missing username uses a neutral label instead of exposing the recipient's real name")
    void missingUsernameDoesNotFallBackToRealName() {
        User user = eligibleRecipient();
        user.setFirstName("Lucas");
        user.setLastName("Dupont");

        var issued = service.issueForEligible(7L, "free-credit-upgrade", Instant.now()).orElseThrow();

        assertThat(issued.code()).isEqualTo("MEMBER-BONUS-72H");
    }

    @Test
    @DisplayName("An email username is never exposed in a personal code")
    void missingUsableNameDoesNotExposeEmail() {
        User user = eligibleRecipient();
        user.setFirstName("张伟");
        user.setUsername("private.person@example.test");

        var issued = service.issueForEligible(7L, "free-credit-upgrade", Instant.now()).orElseThrow();

        assertThat(issued.code()).isEqualTo("MEMBER-BONUS-72H");
    }

    @Test
    @DisplayName("Long usernames produce a code accepted by the existing 64-character link format")
    void longUsernameKeepsCodeWithinExistingFormat() {
        eligibleRecipient().setUsername("A".repeat(50));

        var issued = service.issueForEligible(7L, "free-credit-upgrade", Instant.now()).orElseThrow();

        assertThat(issued.code()).isEqualTo("A".repeat(32) + "-BONUS-72H")
                .matches("[A-Z0-9][A-Z0-9_-]{2,63}");
    }

    @Test
    @DisplayName("Changing username or policy duration never changes a code or expiry that was already issued")
    void repeatedIssuanceAfterUsernameOrDurationChangeKeepsExistingCode() {
        eligibleRecipient().setUsername("new_username");
        policy.setValidityHours(24);
        when(codes.findByRecipientUserIdAndCampaignKey(7L, "free-credit-upgrade"))
                .thenReturn(Optional.of(offer));

        var issued = service.issueForEligible(7L, "free-credit-upgrade", Instant.now()).orElseThrow();

        assertThat(issued.code()).isEqualTo(offer.getCode());
        assertThat(issued.expiresAt()).isEqualTo(offer.getValidUntil());
        verify(codes, never()).saveAndFlush(any());
        verify(codes, never()).lockPersonalOfferCodeName(anyString());
    }

    private User eligibleRecipient() {
        User user = new User();
        user.setId(7L);
        user.setEmailVerified(true);
        user.setMarketingConsent(true);
        when(users.lockForPersonalOffer(7L)).thenReturn(Optional.of(user));
        PersonalOfferFirstPaidPurchase history = new PersonalOfferFirstPaidPurchase();
        history.setStatus("VERIFIED_NEW");
        when(firstPaid.findById(7L)).thenReturn(Optional.of(history));
        when(policies.findByCampaignKeyAndState("free-credit-upgrade", "ACTIVE"))
                .thenReturn(Optional.of(policy));
        Plan free = new Plan();
        free.setCode("FREE");
        Subscription subscription = new Subscription();
        subscription.setPlan(free);
        when(subscriptions.findActiveByUserId(7L)).thenReturn(Optional.of(subscription));
        when(codes.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        return user;
    }

    private PersonalOfferCheckoutAttempt reservation() {
        PersonalOfferCheckoutAttempt attempt = new PersonalOfferCheckoutAttempt();
        attempt.setId(UUID.randomUUID());
        attempt.setRewardCodeId(50L);
        attempt.setRecipientUserId(7L);
        attempt.setPolicyVersionId(3L);
        attempt.setPlanCode("PRO");
        attempt.setCreditTierIndex(0);
        attempt.setMonthlyCredits(5_000);
        attempt.setCadence("monthly");
        attempt.setBonusCredits(8_000);
        attempt.setStatus("OPEN");
        attempt.setSessionExpiresAt(Instant.now().plusSeconds(1200));
        return attempt;
    }
}
