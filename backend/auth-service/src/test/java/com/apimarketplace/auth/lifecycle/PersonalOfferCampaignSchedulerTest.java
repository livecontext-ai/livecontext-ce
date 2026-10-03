package com.apimarketplace.auth.lifecycle;

import com.apimarketplace.auth.domain.PersonalOfferMatrix;
import com.apimarketplace.auth.domain.PersonalOfferPolicy;
import com.apimarketplace.auth.service.PersonalOfferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PersonalOfferCampaignSchedulerTest {
    private static final long USER = 7L;
    private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");
    @Mock private PersonalOfferLifecycleRepository lifecycle;
    @Mock private PersonalOfferService offers;
    @Mock private LifecycleEmailService email;
    private PersonalOfferCampaignScheduler scheduler;
    private PersonalOfferPolicy policy;

    @BeforeEach
    void setUp() {
        scheduler = new PersonalOfferCampaignScheduler(lifecycle, offers, email,
                Clock.fixed(NOW, ZoneOffset.UTC), 120);
        policy = new PersonalOfferPolicy();
        policy.setId(3L);
        policy.setWaitHours(4);
        policy.setValidityHours(72);
        policy.setReminderEnabled(true);
        policy.setReminderHours(12);
        policy.setPaygCreditsPerUsd(800);
    }

    private void issuedAt(Instant expiresAt) {
        issuedAt(expiresAt, "LUCAS-BONUS-72H");
    }

    private void issuedAt(Instant expiresAt, String code) {
        when(offers.activePolicy(PersonalOfferLifecycleRepository.CAMPAIGN_KEY)).thenReturn(Optional.of(policy));
        when(lifecycle.issuedWithPendingEmails(0L))
                .thenReturn(List.of(new PersonalOfferLifecycleRepository.Issued(USER, 14L)));
        when(offers.readCurrentForUser(USER)).thenReturn(Optional.of(new PersonalOfferService.IssuedOffer(
                14L, code, expiresAt.minusSeconds(72 * 3600), expiresAt, 3L, 1)));
        when(offers.policyById(3L)).thenReturn(Optional.of(policy));
        when(lifecycle.isUserMarketingEligible(USER)).thenReturn(true);
        when(lifecycle.isStillFreeAndExhausted(USER)).thenReturn(true);
        when(offers.current(USER)).thenReturn(new PersonalOfferService.CurrentOffer(
                "AVAILABLE", 14L, 1, expiresAt, code, null, null, null, null));
        PersonalOfferMatrix row = new PersonalOfferMatrix();
        row.setPlanCode("STARTER");
        row.setMonthlyCredits(50000);
        row.setBonusCredits(8000);
        when(offers.matrixForPolicy(3L)).thenReturn(List.of(row));
    }

    @ParameterizedTest
    @ValueSource(strings = {"LUCAS-BONUS-72H", "LUCAS_75-BONUS-24H", "LUCAS-BONUS-72H-2",
            "USER012345678901234567890123456789-BONUS-72H", "ABCDEFGHJKMN23456789"})
    @DisplayName("Personalized and previously issued codes reach both email event payloads unchanged")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void personalizedCodesReachInitialAndReminderEmailPayloads(String code) {
        issuedAt(NOW.plusSeconds(11 * 3600), code);
        when(lifecycle.initialAccepted(USER)).thenReturn(true);

        scheduler.scan();

        ArgumentCaptor<Function<String, Map<String, Object>>> payload = ArgumentCaptor.forClass(Function.class);
        verify(email).submitPersonalOffer(eq(USER), eq(LifecycleEvents.PERSONAL_OFFER_INITIAL_DUE),
                payload.capture(), any(), any(), any());
        verify(email).submitPersonalOffer(eq(USER), eq(LifecycleEvents.PERSONAL_OFFER_REMINDER_DUE),
                payload.capture(), any(), any(), any());
        assertThat(payload.getAllValues()).allSatisfy(factory -> {
            assertThat(factory.apply("fr")).containsEntry("code", code).containsKeys("expires_at", "terms", "headline");
            assertThat(factory.apply("en")).containsEntry("code", code);
        });
        verify(lifecycle, never()).suppressPending(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("The email's subject line is read from the policy's own matrix: its top bonus step, in the contact's language")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void headlineComesFromThePolicyMatrix() {
        issuedAt(NOW.plusSeconds(11 * 3600));
        // The production matrix (V552): Starter up to 100,000 a month, every pack from 500,000 gives 80,000.
        List<PersonalOfferMatrix> matrix = new java.util.ArrayList<>();
        for (String plan : List.of("STARTER", "PRO", "TEAM")) {
            for (int pack : new int[] {5_000, 10_000, 25_000, 50_000, 100_000, 250_000, 500_000, 1_000_000, 5_000_000, 10_000_000}) {
                if (plan.equals("STARTER") && pack > 100_000) continue;
                PersonalOfferMatrix cell = new PersonalOfferMatrix();
                cell.setPlanCode(plan);
                cell.setMonthlyCredits(pack);
                cell.setBonusCredits(pack >= 500_000 ? 80_000 : pack >= 250_000 ? 40_000 : pack >= 50_000 ? 8_000 : 0);
                matrix.add(cell);
            }
        }
        when(offers.matrixForPolicy(3L)).thenReturn(matrix);

        scheduler.scan();

        ArgumentCaptor<Function<String, Map<String, Object>>> payload = ArgumentCaptor.forClass(Function.class);
        verify(email).submitPersonalOffer(eq(USER), eq(LifecycleEvents.PERSONAL_OFFER_INITIAL_DUE),
                payload.capture(), any(), any(), any());
        assertThat(payload.getValue().apply("en")).containsEntry("headline", "Up to 80,000 bonus credits with your first plan");
        assertThat(payload.getValue().apply("fr")).containsEntry("headline", "Jusqu'à "
                + java.text.NumberFormat.getIntegerInstance(java.util.Locale.FRENCH).format(80_000)
                + " crédits offerts avec votre premier abonnement");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"<script>BAD</script>", "LUCAS?other=value", "ABC\nDEF", "_LUCAS-BONUS-72H",
            "USER012345678901234567890123456789012345678901234567890123456789012"})
    @DisplayName("Malformed stored codes are suppressed before any email event is submitted")
    void malformedCodesNeverReachEmailPayloads(String code) {
        issuedAt(NOW.plusSeconds(11 * 3600), code);

        scheduler.scan();

        verify(lifecycle).suppressPending(USER, "invalid_code", NOW);
        verifyNoInteractions(email);
    }

    @Test
    void reminderDispatchesAfterItsDueInstantInsideDeliveryWindow() {
        issuedAt(NOW.plusSeconds(11 * 3600)); // due was an hour ago, expiry remains 11h away
        when(lifecycle.initialAccepted(USER)).thenReturn(true);

        scheduler.scan();

        verify(email).submitPersonalOffer(eq(USER), eq(LifecycleEvents.PERSONAL_OFFER_REMINDER_DUE),
                any(), any(), any(), any());
    }

    @Test
    void expiryMarginSuppressesBothPendingSteps() {
        issuedAt(NOW.plusSeconds(3600));

        scheduler.scan();

        verify(lifecycle).suppressPending(eq(USER), eq("delivery_window"), eq(NOW));
        verifyNoInteractions(email);
    }

    @Test
    void rechargeAfterIssueStopsEmailsWithoutChangingCode() {
        issuedAt(NOW.plusSeconds(24 * 3600));
        when(lifecycle.hasPositiveGrantSince(eq(USER), any())).thenReturn(true);

        scheduler.scan();

        verify(lifecycle).suppressPending(eq(USER), eq("ineligible"), eq(NOW));
        verifyNoInteractions(email);
        verify(offers, never()).issueForEligible(anyLong(), anyString(), any());
    }

    @Test
    void draftPolicyDoesNotObserveIssueOrDispatch() {
        when(offers.activePolicy(PersonalOfferLifecycleRepository.CAMPAIGN_KEY)).thenReturn(Optional.empty());

        scheduler.scan();

        verify(lifecycle).markStaleClaimsUnknown(any(), eq(NOW));
        verify(lifecycle, never()).exhaustedFreeUsers(anyLong());
        verify(lifecycle, never()).issuedWithPendingEmails(anyLong());
        verifyNoInteractions(email);
    }

    @Test
    void dueExistingAccountReachesIssuerForHistoryVerification() {
        when(offers.activePolicy(PersonalOfferLifecycleRepository.CAMPAIGN_KEY)).thenReturn(Optional.of(policy));
        when(lifecycle.observations(0L)).thenReturn(List.of(
                new PersonalOfferLifecycleRepository.Observation(USER, NOW.minusSeconds(5 * 3600))));
        when(lifecycle.isStillFreeAndExhausted(USER)).thenReturn(true);
        when(lifecycle.isUserMarketingEligible(USER)).thenReturn(true);
        // Migrated accounts begin with UNKNOWN history; the issuer is responsible for
        // verifying it against Stripe, so the scheduler must not prefilter them.
        when(offers.hasFirstPaidPurchase(USER)).thenReturn(true);

        scheduler.scan();

        verify(offers).issueForEligible(USER, PersonalOfferLifecycleRepository.CAMPAIGN_KEY, NOW);
    }

    @Test
    void checkoutInProgressDefersPendingEmailWithoutSuppressingIt() {
        issuedAt(NOW.plusSeconds(24 * 3600));
        when(offers.current(USER)).thenReturn(new PersonalOfferService.CurrentOffer(
                "CHECKOUT_OPEN", 14L, 1, NOW.plusSeconds(24 * 3600), null, null, null, null, null));

        scheduler.scan();

        verify(lifecycle, never()).suppressPending(anyLong(), anyString(), any());
        verifyNoInteractions(email);

        // The checkout expires without payment; the original offer can still send.
        when(offers.current(USER)).thenReturn(new PersonalOfferService.CurrentOffer(
                "AVAILABLE", 14L, 1, NOW.plusSeconds(24 * 3600), "ABCDEFGHJKMN23456789", null, null, null, null));
        scheduler.scan();
        verify(email).submitPersonalOffer(eq(USER), eq(LifecycleEvents.PERSONAL_OFFER_INITIAL_DUE),
                any(), any(), any(), any());
    }

    @Test
    void pausingCampaignWhileEventIsQueuedPreventsWorkerClaim() {
        issuedAt(NOW.plusSeconds(24 * 3600));
        scheduler.scan();
        ArgumentCaptor<BooleanSupplier> claim = ArgumentCaptor.forClass(BooleanSupplier.class);
        verify(email).submitPersonalOffer(eq(USER), eq(LifecycleEvents.PERSONAL_OFFER_INITIAL_DUE),
                any(), claim.capture(), any(), any());
        when(offers.activePolicy(PersonalOfferLifecycleRepository.CAMPAIGN_KEY)).thenReturn(Optional.empty());

        assertThat(claim.getValue().getAsBoolean()).isFalse();
        verify(lifecycle, never()).claim(anyLong(), any(), any());
    }
}
