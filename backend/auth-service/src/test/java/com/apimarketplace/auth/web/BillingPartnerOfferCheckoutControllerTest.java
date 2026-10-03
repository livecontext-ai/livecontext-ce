package com.apimarketplace.auth.web;

import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.repository.BillingEventRepository;
import com.apimarketplace.auth.service.PartnerOfferCheckoutService;
import com.apimarketplace.auth.service.PlanCacheService;
import com.apimarketplace.auth.service.StripeBillingService;
import com.apimarketplace.auth.util.NonceUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Checkout opened from a partner's offer: checked live, client attributed on the server, offer carried to Stripe")
class BillingPartnerOfferCheckoutControllerTest {

    private StripeBillingService stripe;
    private PartnerOfferCheckoutService partnerOffers;
    private MockMvc http;
    private BillingController controller;

    @BeforeEach
    void setUp() {
        controller = new BillingController();
        stripe = mock(StripeBillingService.class);
        partnerOffers = mock(PartnerOfferCheckoutService.class);
        PlanCacheService plans = mock(PlanCacheService.class);
        when(plans.getAllPlans()).thenReturn(Map.of("TEAM", new Plan()));
        ReflectionTestUtils.setField(controller, "stripeBillingService", stripe);
        ReflectionTestUtils.setField(controller, "partnerOfferCheckout", partnerOffers);
        ReflectionTestUtils.setField(controller, "planCacheService", plans);
        ReflectionTestUtils.setField(controller, "nonceUtil", mock(NonceUtil.class));
        ReflectionTestUtils.setField(controller, "billingEventRepository", mock(BillingEventRepository.class));
        ReflectionTestUtils.setField(controller, "objectMapper", new ObjectMapper());
        http = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private String body(String extra) {
        return "{\"planCode\":\"TEAM\",\"billingCycle\":\"yearly\",\"creditTierIndex\":\"3\"" + extra + "}";
    }

    @Test
    @DisplayName("a live offer: prepared (attribution) BEFORE the Stripe session, which carries the offer")
    void liveOffer() throws Exception {
        when(partnerOffers.prepare(7L, "Abc23XyZ9k", "TEAM")).thenReturn(PartnerOfferCheckoutService.Verdict.PROCEED);
        when(stripe.createCheckoutSession(7L, "TEAM", "yearly", 3, null, null, "Abc23XyZ9k")).thenReturn("https://checkout.stripe.com/p");

        http.perform(post("/api/billing/checkout").header("X-User-ID", "7").header("X-Organization-Role", "OWNER")
                        .contentType(MediaType.APPLICATION_JSON).content(body(",\"partnerOfferToken\":\"Abc23XyZ9k\"")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.url").value("https://checkout.stripe.com/p"));
        var order = inOrder(partnerOffers, stripe);
        order.verify(partnerOffers).prepare(7L, "Abc23XyZ9k", "TEAM");
        order.verify(stripe).createCheckoutSession(7L, "TEAM", "yearly", 3, null, null, "Abc23XyZ9k");
        // Once the session exists, the offer's apps wait for its payment.
        order.verify(partnerOffers).opened(7L, "Abc23XyZ9k");
    }

    @Test
    @DisplayName("an offer gone since the page loaded opens no payment, and says so")
    void offerGone() throws Exception {
        when(partnerOffers.prepare(7L, "Abc23XyZ9k", "TEAM")).thenReturn(PartnerOfferCheckoutService.Verdict.OFFER_UNAVAILABLE);

        http.perform(post("/api/billing/checkout").header("X-User-ID", "7").header("X-Organization-Role", "OWNER")
                        .contentType(MediaType.APPLICATION_JSON).content(body(",\"partnerOfferToken\":\"Abc23XyZ9k\"")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("offer_unavailable"))
                .andExpect(jsonPath("$.url").doesNotExist());
        verify(stripe, never()).createCheckoutSession(anyLong(), anyString(), anyString(), anyInt(), any(), any(), any());
        verify(partnerOffers, never()).opened(anyLong(), anyString());
    }

    @Test
    @DisplayName("an unverified email opens no payment: the page sends the client through onboarding")
    void unverifiedEmail() throws Exception {
        when(partnerOffers.prepare(7L, "Abc23XyZ9k", "TEAM")).thenReturn(PartnerOfferCheckoutService.Verdict.EMAIL_NOT_VERIFIED);

        http.perform(post("/api/billing/checkout").header("X-User-ID", "7").header("X-Organization-Role", "OWNER")
                        .contentType(MediaType.APPLICATION_JSON).content(body(",\"partnerOfferToken\":\"Abc23XyZ9k\"")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("email_not_verified"));
        verifyNoInteractions(stripe);
    }

    @Test
    @DisplayName("regression: an account that already pays, or a plan the offer's apps cannot run on, opens no payment and says which")
    void subscriberAndPlanTooLow() throws Exception {
        when(partnerOffers.prepare(7L, "Abc23XyZ9k", "TEAM")).thenReturn(PartnerOfferCheckoutService.Verdict.ALREADY_SUBSCRIBED);
        http.perform(post("/api/billing/checkout").header("X-User-ID", "7").header("X-Organization-Role", "OWNER")
                        .contentType(MediaType.APPLICATION_JSON).content(body(",\"partnerOfferToken\":\"Abc23XyZ9k\"")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("offer_already_subscribed"));

        when(partnerOffers.prepare(7L, "Abc23XyZ9k", "TEAM")).thenReturn(PartnerOfferCheckoutService.Verdict.PLAN_TOO_LOW);
        http.perform(post("/api/billing/checkout").header("X-User-ID", "7").header("X-Organization-Role", "OWNER")
                        .contentType(MediaType.APPLICATION_JSON).content(body(",\"partnerOfferToken\":\"Abc23XyZ9k\"")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("offer_plan_too_low"));

        verifyNoInteractions(stripe);
        verify(partnerOffers, never()).opened(anyLong(), anyString());
    }

    @Test
    @DisplayName("without an offer (or a blank token) the checkout is the ordinary one, nothing prepared")
    void noOffer() throws Exception {
        when(stripe.createCheckoutSession(7L, "TEAM", "yearly", 3)).thenReturn("https://checkout.stripe.com/plain");

        http.perform(post("/api/billing/checkout").header("X-User-ID", "7").header("X-Organization-Role", "OWNER")
                        .contentType(MediaType.APPLICATION_JSON).content(body(",\"partnerOfferToken\":\"  \"")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.url").value("https://checkout.stripe.com/plain"));
        verifyNoInteractions(partnerOffers);
    }

    @Test
    @DisplayName("the workspace owner gate comes first: a member opens nothing and attributes no one")
    void ownerGateFirst() throws Exception {
        http.perform(post("/api/billing/checkout").header("X-User-ID", "7").header("X-Organization-Role", "MEMBER")
                        .contentType(MediaType.APPLICATION_JSON).content(body(",\"partnerOfferToken\":\"Abc23XyZ9k\"")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(partnerOffers, stripe);
    }

    @Test
    @DisplayName("a personal offer wins: a partner token sent alongside is ignored, nothing prepared or recorded for it")
    void personalOfferWins() throws Exception {
        var personal = mock(com.apimarketplace.auth.service.PersonalOfferService.class);
        ReflectionTestUtils.setField(controller, "personalOffers", personal);
        when(stripe.createCheckoutSession(7L, "TEAM", "yearly", 3, 11L, 2)).thenReturn("https://checkout.stripe.com/personal");
        when(personal.current(7L)).thenReturn(new com.apimarketplace.auth.service.PersonalOfferService.CurrentOffer(
                "CHECKOUT_OPEN", 11L, 2, null, null, 8000, null, null, null));

        http.perform(post("/api/billing/checkout").header("X-User-ID", "7").header("X-Organization-Role", "OWNER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(",\"personalOfferId\":\"11\",\"offerVersion\":\"2\",\"partnerOfferToken\":\"Abc23XyZ9k\"")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.offerStatus").value("ATTACHED"));
        verifyNoInteractions(partnerOffers);
    }

    @Test
    @DisplayName("without the partner offer service (not wired), an offer checkout opens nothing rather than an unchecked one")
    void noServiceNoCheckout() throws Exception {
        ReflectionTestUtils.setField(controller, "partnerOfferCheckout", null);

        http.perform(post("/api/billing/checkout").header("X-User-ID", "7").header("X-Organization-Role", "OWNER")
                        .contentType(MediaType.APPLICATION_JSON).content(body(",\"partnerOfferToken\":\"Abc23XyZ9k\"")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("offer_unavailable"));
        verifyNoInteractions(stripe);
    }

    @Test
    @DisplayName("no Stripe session (null URL) or a session that failed: nothing waits for a payment that cannot come")
    void noSessionNoWaitingApps() throws Exception {
        when(partnerOffers.prepare(7L, "Abc23XyZ9k", "TEAM")).thenReturn(PartnerOfferCheckoutService.Verdict.PROCEED);
        when(stripe.createCheckoutSession(7L, "TEAM", "yearly", 3, null, null, "Abc23XyZ9k")).thenReturn(null);
        http.perform(post("/api/billing/checkout").header("X-User-ID", "7").header("X-Organization-Role", "OWNER")
                .contentType(MediaType.APPLICATION_JSON).content(body(",\"partnerOfferToken\":\"Abc23XyZ9k\"")));

        when(stripe.createCheckoutSession(7L, "TEAM", "yearly", 3, null, null, "Abc23XyZ9k")).thenThrow(new IllegalStateException("stripe down"));
        http.perform(post("/api/billing/checkout").header("X-User-ID", "7").header("X-Organization-Role", "OWNER")
                .contentType(MediaType.APPLICATION_JSON).content(body(",\"partnerOfferToken\":\"Abc23XyZ9k\"")));

        verify(partnerOffers, never()).opened(anyLong(), anyString());
    }
}
