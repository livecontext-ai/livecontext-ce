package com.apimarketplace.auth.web;

import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.repository.BillingEventRepository;
import com.apimarketplace.auth.service.*;
import com.apimarketplace.auth.util.NonceUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("Personal offer HTTP entry paths preserve identity, selection and payment-only activation")
class BillingPersonalOfferControllerTest {
    private PersonalOfferService offers;
    private StripeBillingService stripe;
    private RewardService rewards;
    private PlanCacheService plans;
    private MockMvc http;

    @BeforeEach
    void setUp() {
        BillingController controller = new BillingController();
        offers = mock(PersonalOfferService.class);
        stripe = mock(StripeBillingService.class);
        rewards = mock(RewardService.class);
        plans = mock(PlanCacheService.class);
        ReflectionTestUtils.setField(controller, "personalOffers", offers);
        ReflectionTestUtils.setField(controller, "stripeBillingService", stripe);
        ReflectionTestUtils.setField(controller, "rewardService", rewards);
        ReflectionTestUtils.setField(controller, "planCacheService", plans);
        ReflectionTestUtils.setField(controller, "nonceUtil", mock(NonceUtil.class));
        ReflectionTestUtils.setField(controller, "billingEventRepository", mock(BillingEventRepository.class));
        ReflectionTestUtils.setField(controller, "objectMapper", new ObjectMapper());
        http = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("Current and preview reject unauthenticated requests before reading an offer")
    void offerReadsRequireAuthenticatedAccount() throws Exception {
        http.perform(get("/api/billing/offers/current")).andExpect(status().isUnauthorized());
        http.perform(post("/api/billing/offers/preview").contentType(MediaType.APPLICATION_JSON)
                .content("{\"offerId\":42,\"creditTierIndex\":3,\"billingCycle\":\"monthly\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(offers);
    }

    @Test
    @DisplayName("The preview uses the authenticated account and returns plan-specific bonus terms")
    void previewReturnsVerifiedPlanMatrix() throws Exception {
        when(offers.preview(7L, 42L, null, 3, "monthly")).thenReturn(new PersonalOfferService.OfferPreview(
                "AVAILABLE", 42L, 5, Instant.now().plusSeconds(3600), 50000, "monthly",
                List.of(new PersonalOfferService.PlanBonus("PRO", 8000, 10, "ELIGIBLE")), null));
        http.perform(post("/api/billing/offers/preview").header("X-User-ID", "7")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"offerId\":42,\"creditTierIndex\":3,\"billingCycle\":\"monthly\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.offerVersion").value(5))
                .andExpect(jsonPath("$.plans[0].bonusCredits").value(8000));
    }

    @Test
    @DisplayName("A foreign code returns the opaque unavailable error without bonus information")
    void foreignCodeIsOpaque() throws Exception {
        when(offers.preview(7L, null, "FOREIGN", 3, "monthly"))
                .thenThrow(new PersonalOfferService.OfferException("OFFER_UNAVAILABLE"));
        http.perform(post("/api/billing/offers/preview").header("X-User-ID", "7")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"FOREIGN\",\"creditTierIndex\":3,\"billingCycle\":\"monthly\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OFFER_UNAVAILABLE"))
                .andExpect(jsonPath("$.plans").doesNotExist());
    }

    @Test
    @DisplayName("A missing pack produces a stable validation error without previewing")
    void missingPackIsRejected() throws Exception {
        http.perform(post("/api/billing/offers/preview").header("X-User-ID", "7")
                .contentType(MediaType.APPLICATION_JSON).content("{\"offerId\":42}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PLAN_PACK_UNSUPPORTED"));
        verifyNoInteractions(offers);
    }

    @Test
    @DisplayName("Typing a personal code recognizes the offer and does not grant any credits")
    void manualCodeRecognizesWithoutGranting() throws Exception {
        when(offers.recognizePersonalCode(7L, "PERSONAL"))
                .thenReturn(Optional.of(new PersonalOfferService.IssuedOffer(42L, "PERSONAL",
                        Instant.now(), Instant.now().plusSeconds(3600), 2L, 5)));
        http.perform(post("/api/billing/redeem").header("X-User-ID", "7")
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"PERSONAL\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("OFFER_READY"))
                .andExpect(jsonPath("$.offerId").value(42)).andExpect(jsonPath("$.grantedCredits").doesNotExist());
        verifyNoInteractions(rewards);
    }

    @Test
    @DisplayName("Ordinary codes still pass to the existing reward redemption service")
    void ordinaryCodeKeepsExistingRoute() throws Exception {
        when(offers.recognizePersonalCode(7L, "PROMO")).thenReturn(Optional.empty());
        when(rewards.redeem(7L, "PROMO")).thenReturn(new RewardService.RedeemResult(
                RewardService.RedeemStatus.UNKNOWN_CODE, null));
        http.perform(post("/api/billing/redeem").header("X-User-ID", "7")
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"PROMO\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("INVALID_CODE"));
        verify(rewards).redeem(7L, "PROMO");
    }

    @Test
    @DisplayName("Checkout forwards the verified offer version with the selected plan, pack and cadence")
    void checkoutAcknowledgesAttachedOffer() throws Exception {
        when(plans.getAllPlans()).thenReturn(Map.of("PRO", new Plan()));
        when(stripe.createCheckoutSession(7L, "PRO", "monthly", 3, 42L, 5))
                .thenReturn("https://checkout.stripe.com/test-personal");
        when(offers.current(7L)).thenReturn(new PersonalOfferService.CurrentOffer("CHECKOUT_OPEN",
                42L, 5, Instant.now().plusSeconds(3600), null, 8000, null,
                UUID.randomUUID(), Instant.now().plusSeconds(2100)));

        http.perform(post("/api/billing/checkout").header("X-User-ID", "7")
                .header("X-Organization-Role", "OWNER").contentType(MediaType.APPLICATION_JSON).content(checkoutBody()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.offerStatus").value("ATTACHED"))
                .andExpect(jsonPath("$.bonusCredits").value(8000))
                .andExpect(jsonPath("$.url").value("https://checkout.stripe.com/test-personal"));
    }

    @Test
    @DisplayName("A stale offer preview stops checkout and never returns a payment URL")
    void stalePreviewStopsCheckout() throws Exception {
        when(plans.getAllPlans()).thenReturn(Map.of("PRO", new Plan()));
        when(stripe.createCheckoutSession(7L, "PRO", "monthly", 3, 42L, 5))
                .thenThrow(new PersonalOfferService.OfferException("OFFER_PREVIEW_STALE"));
        http.perform(post("/api/billing/checkout").header("X-User-ID", "7")
                .header("X-Organization-Role", "OWNER").contentType(MediaType.APPLICATION_JSON).content(checkoutBody()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OFFER_PREVIEW_STALE"))
                .andExpect(jsonPath("$.url").doesNotExist());
    }

    @Test
    @DisplayName("The personal offer checkout preserves the existing workspace owner gate")
    void nonOwnerCannotCreatePaidSession() throws Exception {
        http.perform(post("/api/billing/checkout").header("X-User-ID", "7")
                .header("X-Organization-Role", "MEMBER").contentType(MediaType.APPLICATION_JSON).content(checkoutBody()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NOT_WORKSPACE_OWNER"));
        verifyNoInteractions(stripe, offers);
    }

    private String checkoutBody() {
        return "{\"planCode\":\"PRO\",\"billingCycle\":\"monthly\",\"creditTierIndex\":\"3\","
                + "\"personalOfferId\":\"42\",\"offerVersion\":\"5\"}";
    }
}
