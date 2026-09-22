package com.apimarketplace.auth.web;

import com.apimarketplace.auth.domain.BillingCustomer;
import com.apimarketplace.auth.domain.BillingEvent;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.BillingCustomerRepository;
import com.apimarketplace.auth.repository.BillingEventRepository;
import com.apimarketplace.auth.repository.PendingCreditUpgradeRepository;
import com.apimarketplace.auth.repository.PlanRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.service.CreditAttributionService;
import com.apimarketplace.auth.service.PriceCacheService;
import com.apimarketplace.auth.service.StripeBillingService;
import com.apimarketplace.auth.service.SubscriptionService;
import com.apimarketplace.auth.util.NonceUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.StripeClient;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.StripeObject;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PAYG top-up attribution when the nonce cannot be decoded.
 *
 * <p>Regression: with no shared {@code NONCE_ENCRYPTION_KEY}, every auth replica used its own
 * ephemeral AES key, so a webhook landing on the replica that had not created the checkout
 * logged "has invalid nonce - skipping grant" and the paid top-up was dropped. The Stripe
 * customer id on the session is the durable link to the local billing customer.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Webhook PAYG top-up: Stripe-customer fallback when the nonce is unreadable")
class WebhookControllerPaygCustomerFallbackTest {

    @Mock private BillingEventRepository billingEventRepository;
    @Mock private SubscriptionService subscriptionService;
    @Mock private BillingCustomerRepository billingCustomerRepository;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private PlanRepository planRepository;
    @Mock private PriceCacheService priceCacheService;
    @Mock private CreditAttributionService creditAttributionService;
    @Mock private PendingCreditUpgradeRepository pendingCreditUpgradeRepository;
    @Mock private StripeBillingService stripeBillingService;
    @Mock private NonceUtil nonceUtil;
    @Mock private StripeClient stripeClient;

    private static final String WEBHOOK_SECRET = "whsec_test_secret";
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        WebhookController controller = new WebhookController(billingEventRepository, subscriptionService,
                billingCustomerRepository, subscriptionRepository, new ObjectMapper(), planRepository,
                priceCacheService, creditAttributionService, pendingCreditUpgradeRepository,
                stripeBillingService, nonceUtil, stripeClient, WEBHOOK_SECRET);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        lenient().when(billingEventRepository.existsByEventId(anyString())).thenReturn(false);
        lenient().when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static BillingCustomer customerOfUser(long userId) {
        User user = new User();
        user.setId(userId);
        BillingCustomer bc = new BillingCustomer(user, "internal");
        bc.setProviderCustomerId("cus_" + userId);
        return bc;
    }

    private static Event typedEvent(String eventId, Session session) {
        Event event = mock(Event.class);
        lenient().when(event.getId()).thenReturn(eventId);
        lenient().when(event.getType()).thenReturn("checkout.session.completed");
        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        lenient().when(event.getDataObjectDeserializer()).thenReturn(deserializer);
        lenient().when(deserializer.getObject()).thenReturn(Optional.ofNullable(session));
        Event.Data data = mock(Event.Data.class);
        lenient().when(event.getData()).thenReturn(data);
        return event;
    }

    private static Event rawEvent(String eventId, String rawJson) {
        Event event = typedEvent(eventId, null);
        StripeObject raw = mock(StripeObject.class);
        lenient().when(raw.toJson()).thenReturn(rawJson);
        lenient().when(event.getData().getObject()).thenReturn(raw);
        return event;
    }

    private static Session paygSession(String sessionId, String customer, String nonce) {
        Session session = mock(Session.class);
        lenient().when(session.getId()).thenReturn(sessionId);
        lenient().when(session.getCustomer()).thenReturn(customer);
        lenient().when(session.getSubscription()).thenReturn(null);
        lenient().when(session.getClientReferenceId()).thenReturn(nonce);
        lenient().when(session.getMetadata()).thenReturn(Map.of(
                "kind", "payg_topup", "tier", "small", "credit_amount", "8000"));
        return session;
    }

    private void deliver(Event event) throws Exception {
        String payload = "{\"type\":\"checkout.session.completed\"}";
        try (MockedStatic<Webhook> webhook = mockStatic(Webhook.class)) {
            webhook.when(() -> Webhook.constructEvent(eq(payload), anyString(), eq(WEBHOOK_SECRET))).thenReturn(event);
            mockMvc.perform(post("/webhooks/stripe")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload)
                            .header("Stripe-Signature", "t=123,v1=abc"))
                    .andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("typed path: undecodable nonce + known Stripe customer grants the top-up to that customer's user")
    void typedUndecodableNonceFallsBackToCustomer() throws Exception {
        when(nonceUtil.decodeNonce("n_other_pod")).thenReturn(null);
        when(billingCustomerRepository.findByProviderCustomerId("cus_121")).thenReturn(Optional.of(customerOfUser(121L)));

        deliver(typedEvent("evt_t1", paygSession("cs_t1", "cus_121", "n_other_pod")));

        verify(creditAttributionService).grantPaygTopup(eq(121L), eq(new BigDecimal("8000")), eq("cs_t1"), eq("small"));
        verify(subscriptionService, never()).onSubscriptionUpsert(any(), any(), any(), any(), any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    @Test
    @DisplayName("typed path: no client_reference_id at all still resolves through the customer")
    void typedMissingNonceFallsBackToCustomer() throws Exception {
        when(billingCustomerRepository.findByProviderCustomerId("cus_121")).thenReturn(Optional.of(customerOfUser(121L)));

        deliver(typedEvent("evt_t2", paygSession("cs_t2", "cus_121", null)));

        verify(nonceUtil, never()).decodeNonce(any());
        verify(creditAttributionService).grantPaygTopup(eq(121L), eq(new BigDecimal("8000")), eq("cs_t2"), eq("small"));
    }

    @Test
    @DisplayName("typed path: unknown customer and unreadable nonce grants nothing and still acknowledges the event (reconciler retries from the stored event)")
    void typedUnknownCustomerGrantsNothing() throws Exception {
        when(nonceUtil.decodeNonce("n_other_pod")).thenReturn(null);
        when(billingCustomerRepository.findByProviderCustomerId("cus_ghost")).thenReturn(Optional.empty());

        deliver(typedEvent("evt_t3", paygSession("cs_t3", "cus_ghost", "n_other_pod")));

        verify(creditAttributionService, never()).grantPaygTopup(any(), any(), any(), any());
    }

    @Test
    @DisplayName("typed path: a decodable nonce is used as before, without any customer lookup")
    void typedDecodableNonceNeedsNoFallback() throws Exception {
        when(nonceUtil.decodeNonce("n_mine")).thenReturn(121L);

        deliver(typedEvent("evt_t4", paygSession("cs_t4", "cus_121", "n_mine")));

        verify(billingCustomerRepository, never()).findByProviderCustomerId(any());
        verify(creditAttributionService).grantPaygTopup(eq(121L), eq(new BigDecimal("8000")), eq("cs_t4"), eq("small"));
    }

    @Test
    @DisplayName("typed path: a subscription checkout (no kind) with an unreadable nonce never consults the customer and grants nothing (provisioned later by customer.subscription.*)")
    void typedSubscriptionCheckoutIsUnchanged() throws Exception {
        Session session = mock(Session.class);
        lenient().when(session.getId()).thenReturn("cs_sub");
        lenient().when(session.getCustomer()).thenReturn("cus_121");
        lenient().when(session.getSubscription()).thenReturn("sub_x");
        lenient().when(session.getClientReferenceId()).thenReturn("n_other_pod");
        lenient().when(session.getMetadata()).thenReturn(Map.of("planCode", "TEAM"));
        when(nonceUtil.decodeNonce("n_other_pod")).thenReturn(null);

        deliver(typedEvent("evt_t5", session));

        verify(billingCustomerRepository, never()).findByProviderCustomerId(any());
        verify(creditAttributionService, never()).grantPaygTopup(any(), any(), any(), any());
    }

    @Test
    @DisplayName("RAW path: undecodable nonce + known customer grants the top-up (deserializer-failure parity)")
    void rawUndecodableNonceFallsBackToCustomer() throws Exception {
        String raw = "{\"id\":\"cs_r1\",\"object\":\"checkout.session\",\"customer\":\"cus_121\","
                + "\"client_reference_id\":\"n_other_pod\","
                + "\"metadata\":{\"kind\":\"payg_topup\",\"tier\":\"small\",\"credit_amount\":\"8000\"}}";
        when(nonceUtil.decodeNonce("n_other_pod")).thenReturn(null);
        when(billingCustomerRepository.findByProviderCustomerId("cus_121")).thenReturn(Optional.of(customerOfUser(121L)));

        deliver(rawEvent("evt_r1", raw));

        verify(creditAttributionService).grantPaygTopup(eq(121L), eq(new BigDecimal("8000")), eq("cs_r1"), eq("small"));
    }

    @Test
    @DisplayName("RAW path: a session without client_reference_id is no longer skipped, the customer attributes it")
    void rawMissingNonceFallsBackToCustomer() throws Exception {
        String raw = "{\"id\":\"cs_r2\",\"object\":\"checkout.session\",\"customer\":\"cus_121\","
                + "\"metadata\":{\"kind\":\"payg_topup\",\"tier\":\"small\",\"credit_amount\":\"8000\"}}";
        when(billingCustomerRepository.findByProviderCustomerId("cus_121")).thenReturn(Optional.of(customerOfUser(121L)));

        deliver(rawEvent("evt_r2", raw));

        verify(nonceUtil, never()).decodeNonce(any());
        verify(creditAttributionService).grantPaygTopup(eq(121L), eq(new BigDecimal("8000")), eq("cs_r2"), eq("small"));
    }
}
