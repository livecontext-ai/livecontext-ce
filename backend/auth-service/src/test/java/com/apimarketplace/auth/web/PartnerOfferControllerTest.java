package com.apimarketplace.auth.web;

import com.apimarketplace.auth.domain.PartnerOffer;
import com.apimarketplace.auth.dto.PublicProfileDto;
import com.apimarketplace.auth.service.PartnerOfferDeliveryService;
import com.apimarketplace.auth.service.PartnerOfferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PartnerOfferControllerTest {

    private PartnerOfferService service;
    private PartnerOfferDeliveryService deliveries;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(PartnerOfferService.class);
        deliveries = mock(PartnerOfferDeliveryService.class);
        mvc = MockMvcBuilders.standaloneSetup(new PartnerOfferController(service, deliveries, false)).build();
    }

    private static PartnerOffer offer() {
        PartnerOffer o = new PartnerOffer();
        o.setToken("ABCDEFGHJK");
        o.setPartnerUserId(42L);
        o.setRewardCodeId(400L);
        o.setPlanCode("PRO");
        o.setCreditTierIndex(5);
        o.setBillingCycle("monthly");
        o.setLabel("For Acme");
        return o;
    }

    @Test
    @DisplayName("POST creates an offer for the signed-in partner and answers 201 with its token")
    void create() throws Exception {
        when(service.create(42L, "PRO", 5, "monthly", "For Acme", null)).thenReturn(new PartnerOfferService.Outcome(offer(), null));

        mvc.perform(post("/api/billing/partner/offers").header("X-User-ID", "42").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plan_code\":\"PRO\",\"credit_tier_index\":5,\"billing_cycle\":\"monthly\",\"label\":\"For Acme\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.offer.token").value("ABCDEFGHJK"))
                .andExpect(jsonPath("$.offer.plan_code").value("PRO"))
                .andExpect(jsonPath("$.offer.credit_tier_index").value(5))
                .andExpect(jsonPath("$.offer.billing_cycle").value("monthly"))
                .andExpect(jsonPath("$.offer.label").value("For Acme"));
    }

    @Test
    @DisplayName("POST: a refusal keeps its reason, with a status that says what kind of refusal it is")
    void createRefusals() throws Exception {
        String body = "{\"plan_code\":\"PRO\",\"credit_tier_index\":5,\"billing_cycle\":\"monthly\"}";
        when(service.create(anyLong(), any(), any(), any(), any(), any())).thenReturn(new PartnerOfferService.Outcome(null, "not_partner"));
        mvc.perform(post("/api/billing/partner/offers").header("X-User-ID", "42").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("not_partner"));

        when(service.create(anyLong(), any(), any(), any(), any(), any())).thenReturn(new PartnerOfferService.Outcome(null, "code_inactive"));
        mvc.perform(post("/api/billing/partner/offers").header("X-User-ID", "42").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("code_inactive"));

        when(service.create(anyLong(), any(), any(), any(), any(), any())).thenReturn(new PartnerOfferService.Outcome(null, "too_many_offers"));
        mvc.perform(post("/api/billing/partner/offers").header("X-User-ID", "42").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("too_many_offers"));

        when(service.create(anyLong(), any(), any(), any(), any(), any())).thenReturn(new PartnerOfferService.Outcome(null, "invalid_credits"));
        mvc.perform(post("/api/billing/partner/offers").header("X-User-ID", "42").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_credits"));

        mvc.perform(post("/api/billing/partner/offers").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/billing/partner/offers").header("X-User-ID", "not-a-number").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/billing/partner/offers").header("X-User-ID", "42").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("missing_body"));
    }

    @Test
    @DisplayName("GET lists the partner's offers; DELETE deactivates one of theirs, 404 otherwise")
    void listAndDeactivate() throws Exception {
        when(service.list(42L)).thenReturn(List.of(offer()));
        mvc.perform(get("/api/billing/partner/offers").header("X-User-ID", "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.offers[0].token").value("ABCDEFGHJK"));

        when(service.deactivate(42L, "ABCDEFGHJK")).thenReturn(true);
        mvc.perform(delete("/api/billing/partner/offers/ABCDEFGHJK").header("X-User-ID", "42")).andExpect(status().isOk());
        mvc.perform(delete("/api/billing/partner/offers/OTHER00000").header("X-User-ID", "42"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("unknown_offer"));

        // No caller, nothing listed or deactivated.
        mvc.perform(get("/api/billing/partner/offers")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/billing/partner/offers/ABCDEFGHJK")).andExpect(status().isUnauthorized());
        verify(service, never()).deactivate(isNull(), any());
    }

    @Test
    @DisplayName("GET public offer: the plan, the code's credits and the partner's public identity, nothing private")
    void publicOffer() throws Exception {
        PublicProfileDto profile = new PublicProfileDto(42L, "Northwind Automation", "northwind", "/a.png", "bio",
                LocalDateTime.now(), false, true, true, "gold");
        when(service.publicOffer("ABCDEFGHJK")).thenReturn(Optional.of(
                new PartnerOfferService.PublicOffer("ABCDEFGHJK", "NORTHWIND", 8000, "PRO", 5, "monthly", profile, List.of(), "PRO")));

        mvc.perform(get("/api/public/partner-program/offers/ABCDEFGHJK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("NORTHWIND"))
                .andExpect(jsonPath("$.credits").value(8000))
                .andExpect(jsonPath("$.plan_code").value("PRO"))
                .andExpect(jsonPath("$.credit_tier_index").value(5))
                .andExpect(jsonPath("$.billing_cycle").value("monthly"))
                .andExpect(jsonPath("$.partner.name").value("Northwind Automation"))
                .andExpect(jsonPath("$.partner.handle").value("northwind"))
                .andExpect(jsonPath("$.partner.tier").value("gold"))
                .andExpect(jsonPath("$.partner.avatar_url").value("/a.png"))
                // The blue check the marketplace draws next to the partner's name on their app cards.
                .andExpect(jsonPath("$.partner.verified").value(true))
                // The smallest plan the offered apps install on: a checkout on a smaller one is refused.
                .andExpect(jsonPath("$.apps_plan").value("PRO"))
                // Never the partner's note, a partner_user_id field or the bio (the avatar URL carries
                // the user id, as every public avatar in the marketplace does).
                .andExpect(jsonPath("$.label").doesNotExist())
                .andExpect(jsonPath("$.partner_user_id").doesNotExist())
                .andExpect(jsonPath("$.partner.bio").doesNotExist());
    }

    @Test
    @DisplayName("GET public offer: 404 when the offer is not live; a private partner profile reads as no partner")
    void publicOfferMissingOrAnonymousPartner() throws Exception {
        when(service.publicOffer("NOPE000000")).thenReturn(Optional.empty());
        mvc.perform(get("/api/public/partner-program/offers/NOPE000000"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("unknown_offer"));

        when(service.publicOffer("ABCDEFGHJK")).thenReturn(Optional.of(
                new PartnerOfferService.PublicOffer("ABCDEFGHJK", "NORTHWIND", 8000, "TEAM", 7, "yearly", null, List.of(), null)));
        mvc.perform(get("/api/public/partner-program/offers/ABCDEFGHJK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partner").isEmpty());
    }

    @Test
    @DisplayName("POST with apps: the chosen ids reach the service and come back on the offer; unchecked apps are a 503")
    void createWithApps() throws Exception {
        PartnerOffer withApps = offer();
        withApps.setAppPublicationIds(List.of("6f1c0d2e-0000-4000-8000-00000000000a"));
        when(service.create(42L, "PRO", 5, "monthly", null, List.of("6f1c0d2e-0000-4000-8000-00000000000a")))
                .thenReturn(new PartnerOfferService.Outcome(withApps, null));

        mvc.perform(post("/api/billing/partner/offers").header("X-User-ID", "42").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plan_code\":\"PRO\",\"credit_tier_index\":5,\"billing_cycle\":\"monthly\","
                                + "\"app_ids\":[\"6f1c0d2e-0000-4000-8000-00000000000a\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.offer.app_ids[0]").value("6f1c0d2e-0000-4000-8000-00000000000a"));

        when(service.create(anyLong(), any(), any(), any(), any(), any())).thenReturn(new PartnerOfferService.Outcome(null, "apps_unavailable"));
        mvc.perform(post("/api/billing/partner/offers").header("X-User-ID", "42").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plan_code\":\"PRO\",\"credit_tier_index\":5,\"billing_cycle\":\"monthly\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error").value("apps_unavailable"));

        when(service.create(anyLong(), any(), any(), any(), any(), any())).thenReturn(new PartnerOfferService.Outcome(null, "invalid_apps"));
        mvc.perform(post("/api/billing/partner/offers").header("X-User-ID", "42").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plan_code\":\"PRO\",\"credit_tier_index\":5,\"billing_cycle\":\"monthly\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_apps"));
    }

    @Test
    @DisplayName("GET public offer: the apps it gives come as marketplace cards")
    void publicOfferApps() throws Exception {
        when(service.publicOffer("ABCDEFGHJK")).thenReturn(Optional.of(new PartnerOfferService.PublicOffer(
                "ABCDEFGHJK", "NORTHWIND", 8000, "PRO", 5, "monthly", null,
                List.of(java.util.Map.of("id", "6f1c0d2e-0000-4000-8000-00000000000a", "title", "Invoice chaser", "publisherId", "42")), null)));

        mvc.perform(get("/api/public/partner-program/offers/ABCDEFGHJK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apps[0].id").value("6f1c0d2e-0000-4000-8000-00000000000a"))
                .andExpect(jsonPath("$.apps[0].title").value("Invoice chaser"))
                .andExpect(jsonPath("$.apps[0].publisherId").value("42"));
    }

    @Test
    @DisplayName("GET view: the offer as the signed-in caller sees it (their id passed on); 404 when there is none for them; 401 without a caller")
    void viewOffer() throws Exception {
        when(service.publicOffer("ABCDEFGHJK", 7L)).thenReturn(Optional.of(
                new PartnerOfferService.PublicOffer("ABCDEFGHJK", "NORTHWIND", 0, "PRO", 5, "monthly", null, List.of(), null)));

        mvc.perform(get("/api/billing/partner/offers/ABCDEFGHJK/view").header("X-User-ID", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan_code").value("PRO"))
                .andExpect(jsonPath("$.credits").value(0));
        mvc.perform(get("/api/billing/partner/offers/ZZZZZZZZZZ/view").header("X-User-ID", "7"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/billing/partner/offers/ABCDEFGHJK/view"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET welcome: the partner the client may write to and each app's delivery; 404 for an unknown offer; 401 without a caller")
    void welcome() throws Exception {
        PublicProfileDto profile = new PublicProfileDto(42L, "Northwind Automation", "northwind", "/a.png", "bio",
                LocalDateTime.now(), false, false, true, "gold");
        when(deliveries.welcome(7L, "ABCDEFGHJK")).thenReturn(Optional.of(new PartnerOfferDeliveryService.Welcome(profile, 42L,
                List.of(java.util.Map.of("id", "6f1c0d2e-0000-4000-8000-00000000000a", "title", "Invoice chaser", "status", "INSTALLED")))));

        mvc.perform(get("/api/billing/partner/offers/ABCDEFGHJK/welcome").header("X-User-ID", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partner.user_id").value("42"))
                .andExpect(jsonPath("$.partner.name").value("Northwind Automation"))
                .andExpect(jsonPath("$.partner.bio").doesNotExist())
                .andExpect(jsonPath("$.apps[0].status").value("INSTALLED"));

        mvc.perform(get("/api/billing/partner/offers/NOPE000000/welcome").header("X-User-ID", "7"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("unknown_offer"));
        mvc.perform(get("/api/billing/partner/offers/ABCDEFGHJK/welcome")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET welcome: a partner with a private profile is still reachable by id when the client may write; otherwise no id")
    void welcomePartnerVisibility() throws Exception {
        when(deliveries.welcome(7L, "ABCDEFGHJK")).thenReturn(Optional.of(new PartnerOfferDeliveryService.Welcome(null, 42L, List.of())));
        mvc.perform(get("/api/billing/partner/offers/ABCDEFGHJK/welcome").header("X-User-ID", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partner.user_id").value("42"))
                .andExpect(jsonPath("$.partner.name").isEmpty());

        PublicProfileDto profile = new PublicProfileDto(42L, "Northwind Automation", "northwind", "/a.png", null,
                LocalDateTime.now(), false, false, true, "gold");
        when(deliveries.welcome(8L, "ABCDEFGHJK")).thenReturn(Optional.of(new PartnerOfferDeliveryService.Welcome(profile, null, List.of())));
        mvc.perform(get("/api/billing/partner/offers/ABCDEFGHJK/welcome").header("X-User-ID", "8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partner.name").value("Northwind Automation"))
                .andExpect(jsonPath("$.partner.user_id").doesNotExist());

        when(deliveries.welcome(9L, "ABCDEFGHJK")).thenReturn(Optional.of(new PartnerOfferDeliveryService.Welcome(null, null, List.of())));
        mvc.perform(get("/api/billing/partner/offers/ABCDEFGHJK/welcome").header("X-User-ID", "9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partner").isEmpty());
    }

    @Test
    @DisplayName("self-hosted (credits unlimited): every offer endpoint answers 503 and touches nothing")
    void ceRefuses() throws Exception {
        MockMvc ce = MockMvcBuilders.standaloneSetup(new PartnerOfferController(service, deliveries, true)).build();

        ce.perform(get("/api/public/partner-program/offers/ABCDEFGHJK")).andExpect(status().isServiceUnavailable());
        ce.perform(get("/api/billing/partner/offers").header("X-User-ID", "42")).andExpect(status().isServiceUnavailable());
        ce.perform(post("/api/billing/partner/offers").header("X-User-ID", "42").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isServiceUnavailable());
        ce.perform(delete("/api/billing/partner/offers/ABCDEFGHJK").header("X-User-ID", "42")).andExpect(status().isServiceUnavailable());
        ce.perform(get("/api/billing/partner/offers/ABCDEFGHJK/welcome").header("X-User-ID", "7")).andExpect(status().isServiceUnavailable());
        verifyNoInteractions(service, deliveries);
    }
}
