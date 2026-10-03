package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.PartnerOffer;
import com.apimarketplace.auth.domain.RewardCode;
import com.apimarketplace.auth.domain.RewardProgram;
import com.apimarketplace.auth.domain.RewardRedemption;
import com.apimarketplace.auth.repository.PartnerOfferRepository;
import com.apimarketplace.auth.repository.RewardCodeRepository;
import com.apimarketplace.auth.repository.RewardRedemptionRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.domain.Subscription;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class PartnerOfferCheckoutServiceTest {

    private static final long CLIENT = 7L;
    private static final long PARTNER = 42L;

    private PartnerOfferRepository offers;
    private RewardCodeRepository codes;
    private RewardRedemptionRepository redemptions;
    private RewardService rewards;
    private PartnerOfferDeliveryService deliveries;
    private SubscriptionRepository subscriptions;
    private PartnerOfferService partnerOffers;
    private PartnerOfferCheckoutService service;
    private PartnerOffer offer;
    private RewardCode code;

    @BeforeEach
    void setUp() {
        offers = mock(PartnerOfferRepository.class);
        codes = mock(RewardCodeRepository.class);
        redemptions = mock(RewardRedemptionRepository.class);
        rewards = mock(RewardService.class);
        deliveries = mock(PartnerOfferDeliveryService.class);
        subscriptions = mock(SubscriptionRepository.class);
        partnerOffers = mock(PartnerOfferService.class);
        service = new PartnerOfferCheckoutService(offers, codes, redemptions, rewards, deliveries, subscriptions, partnerOffers);
        when(subscriptions.findActiveByUserId(CLIENT)).thenReturn(Optional.empty());

        offer = new PartnerOffer();
        offer.setId(1L);
        offer.setToken("Abc23XyZ9k");
        offer.setPartnerUserId(PARTNER);
        offer.setRewardCodeId(400L);
        code = new RewardCode();
        code.setId(400L);
        code.setCode("NORTHWIND");
        code.setProgram(RewardProgram.PARTNER);
        code.setOwnerUserId(PARTNER);
        code.setActive(true);
        code.setValidFrom(Instant.now().minus(1, ChronoUnit.DAYS));
        when(offers.findByToken("Abc23XyZ9k")).thenReturn(Optional.of(offer));
        when(codes.findById(400L)).thenReturn(Optional.of(code));
        when(redemptions.findByRedeemerUserIdAndProgram(CLIENT, RewardProgram.PARTNER)).thenReturn(Optional.empty());
        when(rewards.redeem(CLIENT, "NORTHWIND")).thenReturn(new RewardService.RedeemResult(RewardService.RedeemStatus.SUCCESS, null));
        when(partnerOffers.appsRunOn(any(PartnerOffer.class), anyString())).thenReturn(true);
    }

    @Test
    @DisplayName("regression: an account that already pays opens no offer checkout (Stripe would change its plan with no payment carrying the offer or its apps), and is attributed to no one")
    void alreadySubscribed() {
        Subscription live = new Subscription();
        live.setProviderSubscriptionId("sub_live");
        when(subscriptions.findActiveByUserId(CLIENT)).thenReturn(Optional.of(live));

        assertThat(service.prepare(CLIENT, "Abc23XyZ9k", "TEAM")).isEqualTo(PartnerOfferCheckoutService.Verdict.ALREADY_SUBSCRIBED);
        verify(rewards, never()).redeem(anyLong(), anyString());

        // A free account (no Stripe subscription) pays as usual.
        when(subscriptions.findActiveByUserId(CLIENT)).thenReturn(Optional.of(new Subscription()));
        assertThat(service.prepare(CLIENT, "Abc23XyZ9k", "TEAM")).isEqualTo(PartnerOfferCheckoutService.Verdict.PROCEED);
    }

    @Test
    @DisplayName("regression: a plan the offer's apps cannot be installed on opens no checkout, before any attribution, even for this partner's client")
    void planTooLowForTheApps() {
        when(partnerOffers.appsRunOn(offer, "STARTER")).thenReturn(false);
        RewardRedemption ours = new RewardRedemption();
        ours.setOwnerUserId(PARTNER);
        when(redemptions.findByRedeemerUserIdAndProgram(CLIENT, RewardProgram.PARTNER)).thenReturn(Optional.of(ours));

        assertThat(service.prepare(CLIENT, "Abc23XyZ9k", "STARTER")).isEqualTo(PartnerOfferCheckoutService.Verdict.PLAN_TOO_LOW);
        verify(rewards, never()).redeem(anyLong(), anyString());
        assertThat(service.prepare(CLIENT, "Abc23XyZ9k", "PRO")).isEqualTo(PartnerOfferCheckoutService.Verdict.PROCEED);
    }

    @Test
    @DisplayName("regression: the client is attributed to the offer's partner on the server, before Stripe, whatever the browser did")
    void attributesOnTheServer() {
        assertThat(service.prepare(CLIENT, "Abc23XyZ9k", "PRO")).isEqualTo(PartnerOfferCheckoutService.Verdict.PROCEED);
        verify(rewards).redeem(CLIENT, "NORTHWIND");
    }

    @Test
    @DisplayName("a client already attributed (by the browser, or to another partner first) goes on without a second redeem")
    void alreadyAttributed() {
        when(redemptions.findByRedeemerUserIdAndProgram(CLIENT, RewardProgram.PARTNER)).thenReturn(Optional.of(new RewardRedemption()));

        assertThat(service.prepare(CLIENT, "Abc23XyZ9k", "PRO")).isEqualTo(PartnerOfferCheckoutService.Verdict.PROCEED);
        verify(rewards, never()).redeem(anyLong(), anyString());
    }

    @Test
    @DisplayName("regression: a code whose cap this very client just reached does not stop them paying, while another partner's client is refused the dead offer")
    void cappedCodeStillLetsItsClientPay() {
        code.setCapScope(com.apimarketplace.auth.domain.CapScope.GLOBAL);
        code.setCapLimit(5);
        code.setCurrentRedemptions(5);
        RewardRedemption ours = new RewardRedemption();
        ours.setOwnerUserId(PARTNER);
        when(redemptions.findByRedeemerUserIdAndProgram(CLIENT, RewardProgram.PARTNER)).thenReturn(Optional.of(ours));

        assertThat(service.prepare(CLIENT, "Abc23XyZ9k", "PRO")).isEqualTo(PartnerOfferCheckoutService.Verdict.PROCEED);

        RewardRedemption theirs = new RewardRedemption();
        theirs.setOwnerUserId(PARTNER + 1);
        when(redemptions.findByRedeemerUserIdAndProgram(CLIENT, RewardProgram.PARTNER)).thenReturn(Optional.of(theirs));

        assertThat(service.prepare(CLIENT, "Abc23XyZ9k", "PRO")).isEqualTo(PartnerOfferCheckoutService.Verdict.OFFER_UNAVAILABLE);
        verify(rewards, never()).redeem(anyLong(), anyString());
    }

    @Test
    @DisplayName("an unverified email stops the checkout (onboarding settles it, then the code applies)")
    void unverifiedEmail() {
        when(rewards.redeem(CLIENT, "NORTHWIND")).thenReturn(new RewardService.RedeemResult(RewardService.RedeemStatus.EMAIL_NOT_VERIFIED, null));

        assertThat(service.prepare(CLIENT, "Abc23XyZ9k", "PRO")).isEqualTo(PartnerOfferCheckoutService.Verdict.EMAIL_NOT_VERIFIED);
    }

    @Test
    @DisplayName("a client the code cannot attribute (partner account, old account, existing subscriber) still pays")
    void unattributableStillPays() {
        for (RewardService.RedeemStatus refusal : new RewardService.RedeemStatus[] {
                RewardService.RedeemStatus.PARTNER_ACCOUNT, RewardService.RedeemStatus.NOT_NEW_ACCOUNT,
                RewardService.RedeemStatus.ALREADY_PAID, RewardService.RedeemStatus.SELF_REFERRAL}) {
            when(rewards.redeem(CLIENT, "NORTHWIND")).thenReturn(new RewardService.RedeemResult(refusal, null));
            assertThat(service.prepare(CLIENT, "Abc23XyZ9k", "PRO")).as(refusal.name()).isEqualTo(PartnerOfferCheckoutService.Verdict.PROCEED);
        }
    }

    @Test
    @DisplayName("a redeem raced by the browser's (unique index) reads as attributed; any other failure stops the checkout")
    void racesAndFailures() {
        when(rewards.redeem(CLIENT, "NORTHWIND")).thenThrow(new DataIntegrityViolationException("uq_redemption"));
        assertThat(service.prepare(CLIENT, "Abc23XyZ9k", "PRO")).isEqualTo(PartnerOfferCheckoutService.Verdict.PROCEED);

        reset(rewards);
        when(rewards.redeem(CLIENT, "NORTHWIND")).thenThrow(new IllegalStateException("db down"));
        assertThatThrownBy(() -> service.prepare(CLIENT, "Abc23XyZ9k", "PRO")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("an offer that is unknown, deactivated, or whose code can no longer bring a sign-up opens no checkout")
    void unavailable() {
        assertThat(service.prepare(CLIENT, "Unknown0001", "PRO")).isEqualTo(PartnerOfferCheckoutService.Verdict.OFFER_UNAVAILABLE);
        assertThat(service.prepare(CLIENT, " ", "PRO")).isEqualTo(PartnerOfferCheckoutService.Verdict.OFFER_UNAVAILABLE);
        assertThat(service.prepare(null, "Abc23XyZ9k", "PRO")).isEqualTo(PartnerOfferCheckoutService.Verdict.OFFER_UNAVAILABLE);

        offer.setActive(false);
        assertThat(service.prepare(CLIENT, "Abc23XyZ9k", "PRO")).isEqualTo(PartnerOfferCheckoutService.Verdict.OFFER_UNAVAILABLE);

        offer.setActive(true);
        code.setValidUntil(Instant.now().minus(1, ChronoUnit.HOURS));
        assertThat(service.prepare(CLIENT, "Abc23XyZ9k", "PRO")).isEqualTo(PartnerOfferCheckoutService.Verdict.OFFER_UNAVAILABLE);
        verify(rewards, never()).redeem(anyLong(), anyString());
    }

    @Test
    @DisplayName("the opened checkout leaves the offer's apps waiting for its payment")
    void openedRecordsTheWaitingApps() {
        service.opened(CLIENT, "Abc23XyZ9k");

        verify(deliveries).expect("Abc23XyZ9k", CLIENT);
    }
}
