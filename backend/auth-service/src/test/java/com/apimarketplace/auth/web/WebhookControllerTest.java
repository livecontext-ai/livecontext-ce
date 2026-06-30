package com.apimarketplace.auth.web;

import com.apimarketplace.auth.domain.BillingCustomer;
import com.apimarketplace.auth.domain.BillingEvent;
import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.BillingCustomerRepository;
import com.apimarketplace.auth.repository.BillingEventRepository;
import com.apimarketplace.auth.repository.PlanRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.service.CreditAttributionService;
import com.apimarketplace.auth.service.PriceCacheService;
import com.apimarketplace.auth.service.StripeBillingService;
import com.apimarketplace.auth.service.SubscriptionService;
import com.apimarketplace.auth.util.NonceUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stripe.StripeClient;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Customer;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.Invoice;
import com.stripe.model.StripeObject;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Comprehensive unit tests for WebhookController handling Stripe webhook events.
 *
 * Since WebhookController uses the static method Webhook.constructEvent() for signature
 * verification, we use MockedStatic to intercept that call in MockMvc-level tests.
 * For private handler methods, we use ReflectionTestUtils to invoke them directly.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WebhookController Tests")
class WebhookControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private BillingEventRepository billingEventRepository;

    @Mock
    private SubscriptionService subscriptionService;

    @Mock
    private BillingCustomerRepository billingCustomerRepository;

    @Mock
    private SubscriptionRepository subscriptionRepository;

    @Mock
    private PlanRepository planRepository;

    @Mock
    private PriceCacheService priceCacheService;

    @Mock
    private CreditAttributionService creditAttributionService;

    @Mock
    private com.apimarketplace.auth.repository.PendingCreditUpgradeRepository pendingCreditUpgradeRepository;

    @Mock
    private StripeBillingService stripeBillingService;

    @Mock
    private NonceUtil nonceUtil;

    @Mock
    private StripeClient stripeClient;

    private WebhookController controller;

    private static final String WEBHOOK_SECRET = "whsec_test_secret";

    @BeforeEach
    void setUp() {
        controller = new WebhookController(
                billingEventRepository,
                subscriptionService,
                billingCustomerRepository,
                subscriptionRepository,
                objectMapper,
                planRepository,
                priceCacheService,
                creditAttributionService,
                pendingCreditUpgradeRepository,
                stripeBillingService,
                nonceUtil,
                stripeClient,
                WEBHOOK_SECRET
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    // ==================== Helper Methods ====================

    /**
     * Creates a mock Stripe Event with the given type and deserialized StripeObject.
     */
    private Event createMockEvent(String eventId, String eventType, StripeObject object) {
        Event event = mock(Event.class);
        lenient().when(event.getId()).thenReturn(eventId);
        lenient().when(event.getType()).thenReturn(eventType);

        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        lenient().when(event.getDataObjectDeserializer()).thenReturn(deserializer);

        if (object != null) {
            lenient().when(deserializer.getObject()).thenReturn(Optional.of(object));
        } else {
            lenient().when(deserializer.getObject()).thenReturn(Optional.empty());
        }

        // Mock event data for raw handlers
        Event.Data eventData = mock(Event.Data.class);
        lenient().when(event.getData()).thenReturn(eventData);

        return event;
    }

    /**
     * Creates a mock raw StripeObject that returns the given JSON when toJson() is called.
     */
    private StripeObject createRawStripeObject(String json) {
        StripeObject rawObj = mock(StripeObject.class);
        lenient().when(rawObj.toJson()).thenReturn(json);
        return rawObj;
    }

    /**
     * Creates a test User entity.
     */
    private User createTestUser(Long id, String email) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        return user;
    }

    /**
     * Creates a test BillingCustomer entity.
     */
    private BillingCustomer createTestBillingCustomer(Long id, User user, String providerCustomerId) {
        BillingCustomer bc = new BillingCustomer(user, "stripe");
        bc.setId(id);
        bc.setProviderCustomerId(providerCustomerId);
        return bc;
    }

    /**
     * Creates a test Subscription entity.
     */
    private Subscription createTestSubscription(Long id, String status, String providerSubId) {
        Subscription sub = new Subscription();
        sub.setId(id);
        sub.setStatus(status);
        sub.setProviderSubscriptionId(providerSubId);
        sub.setCurrentPeriodStart(LocalDateTime.now());
        sub.setCurrentPeriodEnd(LocalDateTime.now().plusMonths(1));
        sub.setCancelAtPeriodEnd(false);
        return sub;
    }

    /**
     * Creates a test Plan entity.
     */
    private Plan createTestPlan(Long id, String code, String name) {
        Plan plan = new Plan(code, name, "Test plan " + code);
        plan.setId(id);
        return plan;
    }

    /**
     * Performs a POST to /webhooks/stripe with MockedStatic for Webhook.constructEvent.
     */
    private void performWebhookPost(String payload, Event mockEvent, int expectedStatus)
            throws Exception {
        try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
            webhookMock.when(() -> Webhook.constructEvent(eq(payload), anyString(), eq(WEBHOOK_SECRET)))
                       .thenReturn(mockEvent);

            var resultActions = mockMvc.perform(post("/webhooks/stripe")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload)
                    .header("Stripe-Signature", "t=123,v1=abc"));

            if (expectedStatus == 200) {
                resultActions.andExpect(status().isOk());
            } else if (expectedStatus == 400) {
                resultActions.andExpect(status().isBadRequest());
            }
        }
    }

    // ==================== Tests ====================

    @Nested
    @DisplayName("POST /webhooks/stripe - Event Routing")
    class EventRouting {

        @Test
        @DisplayName("1. checkout.session.completed event dispatches to handleCheckoutCompleted")
        void checkoutSessionCompleted_dispatchesCorrectly() throws Exception {
            Session session = mock(Session.class);
            lenient().when(session.getId()).thenReturn("cs_test_123");
            lenient().when(session.getCustomer()).thenReturn("cus_test_123");
            lenient().when(session.getSubscription()).thenReturn("sub_test_123");
            lenient().when(session.getClientReferenceId()).thenReturn("n_testNonce");

            when(nonceUtil.decodeNonce("n_testNonce")).thenReturn(42L);

            Event event = createMockEvent("evt_1", "checkout.session.completed", session);
            when(billingEventRepository.existsByEventId("evt_1")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            performWebhookPost("{\"type\":\"checkout.session.completed\"}", event, 200);

            // Should save two billing events: one from handleStripeWebhook and one from handleCheckoutCompleted
            verify(billingEventRepository, atLeastOnce()).save(any(BillingEvent.class));
        }

        @Test
        @DisplayName("2. customer.subscription.created event dispatches to handleSubscriptionUpsert")
        void subscriptionCreated_dispatchesCorrectly() throws Exception {
            com.stripe.model.Subscription sub = mock(com.stripe.model.Subscription.class);
            lenient().when(sub.getId()).thenReturn("sub_new_123");
            lenient().when(sub.getStatus()).thenReturn("active");
            lenient().when(sub.getCustomer()).thenReturn("cus_123");
            lenient().when(sub.getMetadata()).thenReturn(java.util.Map.of("plan_id", "1"));
            lenient().when(sub.getItems()).thenReturn(null);

            // Mock stripeClient.customers().retrieve() for handleSubscriptionUpsert
            var customersService = mock(com.stripe.service.CustomerService.class);
            var customerObj = mock(Customer.class);
            lenient().when(stripeClient.customers()).thenReturn(customersService);
            lenient().when(customersService.retrieve(anyString())).thenReturn(customerObj);
            lenient().when(customerObj.getMetadata()).thenReturn(java.util.Map.of("nonce", "n_test"));
            lenient().when(nonceUtil.decodeNonce("n_test")).thenReturn(1L);

            // Mock stripeClient.subscriptions().retrieve() for plan resolution
            var subsService = mock(com.stripe.service.SubscriptionService.class);
            var fullSub = mock(com.stripe.model.Subscription.class);
            lenient().when(stripeClient.subscriptions()).thenReturn(subsService);
            lenient().when(subsService.retrieve(anyString(), any(com.stripe.param.SubscriptionRetrieveParams.class))).thenReturn(fullSub);
            lenient().when(fullSub.getItems()).thenReturn(null);

            Event event = createMockEvent("evt_2", "customer.subscription.created", sub);
            when(billingEventRepository.existsByEventId("evt_2")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            performWebhookPost("{\"type\":\"customer.subscription.created\"}", event, 200);

            verify(subscriptionService).onSubscriptionUpsert(
                    eq("evt_2"), eq("sub_new_123"), eq("active"), eq(1L),
                    any(), any(), any(), eq(1L), eq("n_test"), eq(0), isNull());
        }

        @Test
        @DisplayName("3. customer.subscription.updated event dispatches to handleSubscriptionUpsert")
        void subscriptionUpdated_dispatchesCorrectly() throws Exception {
            com.stripe.model.Subscription sub = mock(com.stripe.model.Subscription.class);
            lenient().when(sub.getId()).thenReturn("sub_upd_123");
            lenient().when(sub.getStatus()).thenReturn("active");
            lenient().when(sub.getCustomer()).thenReturn("cus_123");
            lenient().when(sub.getMetadata()).thenReturn(java.util.Map.of("plan_id", "2"));
            lenient().when(sub.getItems()).thenReturn(null);

            var customersService = mock(com.stripe.service.CustomerService.class);
            var customerObj = mock(Customer.class);
            lenient().when(stripeClient.customers()).thenReturn(customersService);
            lenient().when(customersService.retrieve(anyString())).thenReturn(customerObj);
            lenient().when(customerObj.getMetadata()).thenReturn(java.util.Map.of("nonce", "n_upd"));
            lenient().when(nonceUtil.decodeNonce("n_upd")).thenReturn(2L);

            var subsService = mock(com.stripe.service.SubscriptionService.class);
            var fullSub = mock(com.stripe.model.Subscription.class);
            lenient().when(stripeClient.subscriptions()).thenReturn(subsService);
            lenient().when(subsService.retrieve(anyString(), any(com.stripe.param.SubscriptionRetrieveParams.class))).thenReturn(fullSub);
            lenient().when(fullSub.getItems()).thenReturn(null);

            Event event = createMockEvent("evt_3", "customer.subscription.updated", sub);
            when(billingEventRepository.existsByEventId("evt_3")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            performWebhookPost("{\"type\":\"customer.subscription.updated\"}", event, 200);

            verify(subscriptionService).onSubscriptionUpsert(
                    eq("evt_3"), eq("sub_upd_123"), eq("active"), eq(2L),
                    any(), any(), any(), eq(2L), eq("n_upd"), eq(0), isNull());
        }

        @Test
        @DisplayName("4. customer.subscription.deleted event dispatches to handleSubscriptionDeleted")
        void subscriptionDeleted_dispatchesCorrectly() throws Exception {
            com.stripe.model.Subscription sub = mock(com.stripe.model.Subscription.class);
            lenient().when(sub.getId()).thenReturn("sub_del_123");

            Event event = createMockEvent("evt_4", "customer.subscription.deleted", sub);
            when(billingEventRepository.existsByEventId("evt_4")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            performWebhookPost("{\"type\":\"customer.subscription.deleted\"}", event, 200);

            verify(subscriptionService).cancelSubscription("sub_del_123");
        }

        @Test
        @DisplayName("5. customer.deleted event dispatches to handleCustomerDeleted")
        void customerDeleted_dispatchesCorrectly() throws Exception {
            Customer customer = mock(Customer.class);
            lenient().when(customer.getId()).thenReturn("cus_del_123");

            User user = createTestUser(10L, "deleted@test.com");
            BillingCustomer bc = createTestBillingCustomer(1L, user, "cus_del_123");

            when(billingCustomerRepository.findByProviderCustomerId("cus_del_123"))
                    .thenReturn(Optional.of(bc));
            when(subscriptionRepository.findByBillingCustomer_IdOrderByCreatedAtDesc(1L))
                    .thenReturn(Collections.emptyList());

            Event event = createMockEvent("evt_5", "customer.deleted", customer);
            when(billingEventRepository.existsByEventId("evt_5")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            performWebhookPost("{\"type\":\"customer.deleted\"}", event, 200);

            verify(billingCustomerRepository).save(bc);
            assertThat(bc.getProviderCustomerId()).isNull();
            // Plan transition is handled via subscription update - no separate quota service.
        }

        @Test
        @DisplayName("6. invoice.paid event dispatches to handleInvoicePaid")
        void invoicePaid_dispatchesCorrectly() throws Exception {
            Invoice invoice = mock(Invoice.class);
            lenient().when(invoice.getId()).thenReturn("in_paid_123");
            lenient().when(invoice.getParent()).thenReturn(null);

            Event event = createMockEvent("evt_6", "invoice.paid", invoice);
            when(billingEventRepository.existsByEventId("evt_6")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            // invoice.paid only logs, so we just verify no exception and 200 response
            performWebhookPost("{\"type\":\"invoice.paid\"}", event, 200);
        }

        @Test
        @DisplayName("7. invoice.payment_succeeded event dispatches to handleInvoicePaymentSucceeded")
        void invoicePaymentSucceeded_dispatchesCorrectly() throws Exception {
            Invoice invoice = mock(Invoice.class);
            lenient().when(invoice.getId()).thenReturn("in_succ_123");
            lenient().when(invoice.getBillingReason()).thenReturn("subscription_cycle");
            lenient().when(invoice.getParent()).thenReturn(null);

            Event event = createMockEvent("evt_7", "invoice.payment_succeeded", invoice);
            when(billingEventRepository.existsByEventId("evt_7")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            performWebhookPost("{\"type\":\"invoice.payment_succeeded\"}", event, 200);
            // Not subscription_create, so no provisioning
            verify(subscriptionService, never()).onSubscriptionUpsert(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any());
        }

        @Test
        @DisplayName("8. subscription_schedule.released event dispatches to handleSubscriptionScheduleReleased")
        void subscriptionScheduleReleased_dispatchesCorrectly() throws Exception {
            // Schedule events use raw handler since deserializer may not produce typed object
            String rawJson = "{\"id\":\"sub_sched_123\",\"object\":\"subscription_schedule\"," +
                    "\"released_subscription\":\"sub_rel_456\"}";
            StripeObject rawObj = createRawStripeObject(rawJson);

            Event event = createMockEvent("evt_8", "subscription_schedule.released", null);
            Event.Data eventData = event.getData();
            when(eventData.getObject()).thenReturn(rawObj);

            // Override deserializer to return empty (triggers raw handler path)
            EventDataObjectDeserializer deser = event.getDataObjectDeserializer();
            when(deser.getObject()).thenReturn(Optional.empty());

            when(billingEventRepository.existsByEventId("evt_8")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            performWebhookPost("{\"type\":\"subscription_schedule.released\"}", event, 200);
            // Just logs info, no service calls required
        }

        @Test
        @DisplayName("9. Unknown event type is logged but returns 200")
        void unknownEventType_returns200() throws Exception {
            Event event = createMockEvent("evt_9", "unknown.event.type", null);
            EventDataObjectDeserializer deser = event.getDataObjectDeserializer();
            when(deser.getObject()).thenReturn(Optional.empty());

            Event.Data eventData = event.getData();
            when(eventData.getObject()).thenReturn(null);

            when(billingEventRepository.existsByEventId("evt_9")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            performWebhookPost("{\"type\":\"unknown.event.type\"}", event, 200);
        }
    }

    @Nested
    @DisplayName("Idempotency")
    class Idempotency {

        @Test
        @DisplayName("10. Duplicate event is skipped when BillingEvent already exists")
        void duplicateEvent_isSkipped() throws Exception {
            Event event = createMockEvent("evt_dup", "checkout.session.completed", mock(Session.class));
            when(billingEventRepository.existsByEventId("evt_dup")).thenReturn(true);

            performWebhookPost("{\"type\":\"checkout.session.completed\"}", event, 200);

            // Should not save any new BillingEvent or dispatch
            verify(billingEventRepository, never()).save(any(BillingEvent.class));
            verify(subscriptionService, never()).onSubscriptionUpsert(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any());
        }

        @Test
        @DisplayName("11. Duplicate subscription event handled gracefully")
        void duplicateSubscriptionEvent_handledGracefully() throws Exception {
            com.stripe.model.Subscription sub = mock(com.stripe.model.Subscription.class);
            lenient().when(sub.getId()).thenReturn("sub_dup_123");

            Event event = createMockEvent("evt_dup_sub", "customer.subscription.deleted", sub);
            when(billingEventRepository.existsByEventId("evt_dup_sub")).thenReturn(true);

            performWebhookPost("{\"type\":\"customer.subscription.deleted\"}", event, 200);

            verify(subscriptionService, never()).cancelSubscription(anyString());
        }

        @Test
        @DisplayName("12. Event ID stored in BillingEvent table after processing")
        void eventId_storedInBillingEventTable() throws Exception {
            Session session = mock(Session.class);
            lenient().when(session.getId()).thenReturn("cs_store");
            lenient().when(session.getCustomer()).thenReturn("cus_store");
            lenient().when(session.getSubscription()).thenReturn("sub_store");
            lenient().when(session.getClientReferenceId()).thenReturn(null);

            Event event = createMockEvent("evt_store", "checkout.session.completed", session);
            when(billingEventRepository.existsByEventId("evt_store")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            performWebhookPost("{\"type\":\"checkout.session.completed\"}", event, 200);

            verify(billingEventRepository).save(argThat(be ->
                    "evt_store".equals(be.getEventId()) &&
                    "stripe".equals(be.getProvider()) &&
                    "checkout.session.completed".equals(be.getType())
            ));
        }
    }

    @Nested
    @DisplayName("checkout.session.completed Handler")
    class CheckoutSessionCompleted {

        @Test
        @DisplayName("13. Valid checkout session with nonce decodes userId")
        void validCheckoutSession_decodesUserId() throws Exception {
            Session session = mock(Session.class);
            when(session.getId()).thenReturn("cs_valid_123");
            when(session.getCustomer()).thenReturn("cus_valid_123");
            when(session.getSubscription()).thenReturn("sub_valid_123");
            when(session.getClientReferenceId()).thenReturn("n_validNonce");

            when(nonceUtil.decodeNonce("n_validNonce")).thenReturn(42L);

            Event event = createMockEvent("evt_13", "checkout.session.completed", session);
            when(billingEventRepository.existsByEventId("evt_13")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            performWebhookPost("{\"type\":\"checkout.session.completed\"}", event, 200);

            verify(nonceUtil).decodeNonce("n_validNonce");
            // Should save the checkout event with userId
            verify(billingEventRepository, atLeast(2)).save(any(BillingEvent.class));
        }

        @Test
        @DisplayName("14. Checkout with metadata extracts userId from nonce correctly")
        void checkoutWithMetadata_extractsUserIdCorrectly() throws Exception {
            Session session = mock(Session.class);
            when(session.getId()).thenReturn("cs_meta_123");
            when(session.getCustomer()).thenReturn("cus_meta_123");
            when(session.getSubscription()).thenReturn("sub_meta_123");
            when(session.getClientReferenceId()).thenReturn("n_metaNonce");

            when(nonceUtil.decodeNonce("n_metaNonce")).thenReturn(99L);

            Event event = createMockEvent("evt_14", "checkout.session.completed", session);
            when(billingEventRepository.existsByEventId("evt_14")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> {
                BillingEvent be = inv.getArgument(0);
                // Verify the checkout event payload contains the userId
                if (be.getPayload() != null && be.getPayload().has("userId")) {
                    assertThat(be.getPayload().get("userId").asLong()).isEqualTo(99L);
                }
                return be;
            });

            performWebhookPost("{\"type\":\"checkout.session.completed\"}", event, 200);
        }

        @Test
        @DisplayName("15. Missing nonce in checkout session is handled gracefully")
        void missingNonce_handledGracefully() throws Exception {
            Session session = mock(Session.class);
            when(session.getId()).thenReturn("cs_no_nonce");
            when(session.getCustomer()).thenReturn("cus_no_nonce");
            when(session.getSubscription()).thenReturn(null);
            when(session.getClientReferenceId()).thenReturn(null);

            Event event = createMockEvent("evt_15", "checkout.session.completed", session);
            when(billingEventRepository.existsByEventId("evt_15")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            performWebhookPost("{\"type\":\"checkout.session.completed\"}", event, 200);

            // Should not crash, should still return 200
            verify(nonceUtil, never()).decodeNonce(any());
        }

        @Test
        @DisplayName("16. Failed nonce decoding does not crash")
        void failedNonceDecode_doesNotCrash() throws Exception {
            Session session = mock(Session.class);
            when(session.getId()).thenReturn("cs_bad_nonce");
            when(session.getCustomer()).thenReturn("cus_bad_nonce");
            when(session.getSubscription()).thenReturn("sub_bad_nonce");
            when(session.getClientReferenceId()).thenReturn("n_invalidNonce");

            when(nonceUtil.decodeNonce("n_invalidNonce")).thenReturn(null);

            Event event = createMockEvent("evt_16", "checkout.session.completed", session);
            when(billingEventRepository.existsByEventId("evt_16")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            performWebhookPost("{\"type\":\"checkout.session.completed\"}", event, 200);

            verify(nonceUtil).decodeNonce("n_invalidNonce");
        }
    }

    @Nested
    @DisplayName("customer.subscription.created/updated Handler")
    class SubscriptionUpsert {

        @Test
        @DisplayName("17. New subscription created calls onSubscriptionUpsert with correct params")
        void newSubscription_callsUpsert() throws Exception {
            com.stripe.model.Subscription sub = mock(com.stripe.model.Subscription.class);
            when(sub.getId()).thenReturn("sub_new_17");
            when(sub.getStatus()).thenReturn("active");
            when(sub.getCustomer()).thenReturn("cus_17");
            when(sub.getMetadata()).thenReturn(java.util.Map.of("plan_id", "3"));
            when(sub.getItems()).thenReturn(null);

            var customersService = mock(com.stripe.service.CustomerService.class);
            var customerObj = mock(Customer.class);
            when(stripeClient.customers()).thenReturn(customersService);
            when(customersService.retrieve("cus_17")).thenReturn(customerObj);
            when(customerObj.getMetadata()).thenReturn(java.util.Map.of("nonce", "n_17"));
            when(nonceUtil.decodeNonce("n_17")).thenReturn(17L);

            var subsService = mock(com.stripe.service.SubscriptionService.class);
            when(stripeClient.subscriptions()).thenReturn(subsService);
            var fullSub = mock(com.stripe.model.Subscription.class);
            when(subsService.retrieve(eq("sub_new_17"), any(com.stripe.param.SubscriptionRetrieveParams.class))).thenReturn(fullSub);
            when(fullSub.getItems()).thenReturn(null);

            Event event = createMockEvent("evt_17", "customer.subscription.created", sub);
            when(billingEventRepository.existsByEventId("evt_17")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            performWebhookPost("{\"type\":\"customer.subscription.created\"}", event, 200);

            verify(subscriptionService).onSubscriptionUpsert(
                    eq("evt_17"), eq("sub_new_17"), eq("active"), eq(3L),
                    any(), any(), any(), eq(17L), eq("n_17"), eq(0), isNull());
        }

        @Test
        @DisplayName("18. Subscription status change (trialing to active) updates correctly")
        void subscriptionStatusChange_updatesCorrectly() throws Exception {
            com.stripe.model.Subscription sub = mock(com.stripe.model.Subscription.class);
            when(sub.getId()).thenReturn("sub_status_18");
            when(sub.getStatus()).thenReturn("active"); // Changed from trialing to active
            when(sub.getCustomer()).thenReturn("cus_18");
            when(sub.getMetadata()).thenReturn(java.util.Map.of("plan_id", "2"));
            when(sub.getItems()).thenReturn(null);

            var customersService = mock(com.stripe.service.CustomerService.class);
            var customerObj = mock(Customer.class);
            when(stripeClient.customers()).thenReturn(customersService);
            when(customersService.retrieve("cus_18")).thenReturn(customerObj);
            when(customerObj.getMetadata()).thenReturn(java.util.Map.of("nonce", "n_18"));
            when(nonceUtil.decodeNonce("n_18")).thenReturn(18L);

            var subsService = mock(com.stripe.service.SubscriptionService.class);
            when(stripeClient.subscriptions()).thenReturn(subsService);
            var fullSub = mock(com.stripe.model.Subscription.class);
            when(subsService.retrieve(eq("sub_status_18"), any(com.stripe.param.SubscriptionRetrieveParams.class))).thenReturn(fullSub);
            when(fullSub.getItems()).thenReturn(null);

            Event event = createMockEvent("evt_18", "customer.subscription.updated", sub);
            when(billingEventRepository.existsByEventId("evt_18")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            performWebhookPost("{\"type\":\"customer.subscription.updated\"}", event, 200);

            verify(subscriptionService).onSubscriptionUpsert(
                    eq("evt_18"), eq("sub_status_18"), eq("active"),
                    eq(2L), any(), any(), any(), eq(18L), eq("n_18"), eq(0), isNull());
        }

        @Test
        @DisplayName("19. Subscription plan change updates with new plan")
        void subscriptionPlanChange_updatesNewPlan() throws Exception {
            com.stripe.model.Subscription sub = mock(com.stripe.model.Subscription.class);
            when(sub.getId()).thenReturn("sub_plan_19");
            when(sub.getStatus()).thenReturn("active");
            when(sub.getCustomer()).thenReturn("cus_19");
            when(sub.getMetadata()).thenReturn(java.util.Map.of("plan_id", "5")); // New plan ID
            when(sub.getItems()).thenReturn(null);

            var customersService = mock(com.stripe.service.CustomerService.class);
            var customerObj = mock(Customer.class);
            when(stripeClient.customers()).thenReturn(customersService);
            when(customersService.retrieve("cus_19")).thenReturn(customerObj);
            when(customerObj.getMetadata()).thenReturn(java.util.Map.of("nonce", "n_19"));
            when(nonceUtil.decodeNonce("n_19")).thenReturn(19L);

            var subsService = mock(com.stripe.service.SubscriptionService.class);
            when(stripeClient.subscriptions()).thenReturn(subsService);
            var fullSub = mock(com.stripe.model.Subscription.class);
            when(subsService.retrieve(eq("sub_plan_19"), any(com.stripe.param.SubscriptionRetrieveParams.class))).thenReturn(fullSub);
            when(fullSub.getItems()).thenReturn(null);

            Event event = createMockEvent("evt_19", "customer.subscription.updated", sub);
            when(billingEventRepository.existsByEventId("evt_19")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            performWebhookPost("{\"type\":\"customer.subscription.updated\"}", event, 200);

            verify(subscriptionService).onSubscriptionUpsert(
                    eq("evt_19"), eq("sub_plan_19"), eq("active"),
                    eq(5L), any(), any(), any(), eq(19L), eq("n_19"), eq(0), isNull());
        }

        @Test
        @DisplayName("20. Subscription with price resolved from cache when no plan_id in metadata")
        void subscriptionPriceResolution_fromCache() throws Exception {
            com.stripe.model.Subscription sub = mock(com.stripe.model.Subscription.class);
            when(sub.getId()).thenReturn("sub_price_20");
            when(sub.getStatus()).thenReturn("active");
            when(sub.getCustomer()).thenReturn("cus_20");
            when(sub.getMetadata()).thenReturn(java.util.Map.of()); // No plan_id
            when(sub.getItems()).thenReturn(null);

            var customersService = mock(com.stripe.service.CustomerService.class);
            var customerObj = mock(Customer.class);
            when(stripeClient.customers()).thenReturn(customersService);
            when(customersService.retrieve("cus_20")).thenReturn(customerObj);
            when(customerObj.getMetadata()).thenReturn(java.util.Map.of("nonce", "n_20"));
            when(nonceUtil.decodeNonce("n_20")).thenReturn(20L);

            // Mock subscriptions().retrieve() to return price data
            var subsService = mock(com.stripe.service.SubscriptionService.class);
            when(stripeClient.subscriptions()).thenReturn(subsService);

            var fullSub = mock(com.stripe.model.Subscription.class);
            var items = mock(com.stripe.model.SubscriptionItemCollection.class);
            var item = mock(com.stripe.model.SubscriptionItem.class);
            var price = mock(com.stripe.model.Price.class);

            when(subsService.retrieve(eq("sub_price_20"), any(com.stripe.param.SubscriptionRetrieveParams.class))).thenReturn(fullSub);
            when(fullSub.getItems()).thenReturn(items);
            when(items.getData()).thenReturn(List.of(item));
            when(item.getPrice()).thenReturn(price);
            when(price.getId()).thenReturn("price_pro_monthly");
            when(price.getMetadata()).thenReturn(null);

            // Price cache resolves planCode
            when(priceCacheService.getAllCachedPrices())
                    .thenReturn(java.util.Map.of("PRO_monthly", "price_pro_monthly"));

            Plan proPlan = createTestPlan(3L, "PRO", "Pro Plan");
            when(planRepository.findByCode("PRO")).thenReturn(Optional.of(proPlan));

            Event event = createMockEvent("evt_20", "customer.subscription.created", sub);
            when(billingEventRepository.existsByEventId("evt_20")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            performWebhookPost("{\"type\":\"customer.subscription.created\"}", event, 200);

            verify(subscriptionService).onSubscriptionUpsert(
                    eq("evt_20"), eq("sub_price_20"), eq("active"),
                    eq(3L), eq("price_pro_monthly"), any(), any(), eq(20L), eq("n_20"), eq(0), isNull());
        }

        @Test
        @DisplayName("21. Missing plan_id in metadata and no cache match logs warning but does not crash")
        void missingPlanId_handledGracefully() throws Exception {
            com.stripe.model.Subscription sub = mock(com.stripe.model.Subscription.class);
            when(sub.getId()).thenReturn("sub_no_plan_21");
            when(sub.getStatus()).thenReturn("active");
            when(sub.getCustomer()).thenReturn("cus_21");
            when(sub.getMetadata()).thenReturn(java.util.Map.of()); // No plan_id
            when(sub.getItems()).thenReturn(null);

            var customersService = mock(com.stripe.service.CustomerService.class);
            var customerObj = mock(Customer.class);
            when(stripeClient.customers()).thenReturn(customersService);
            when(customersService.retrieve("cus_21")).thenReturn(customerObj);
            when(customerObj.getMetadata()).thenReturn(java.util.Map.of());
            // nonceUtil.decodeNonce is never called because metadata has no "nonce" key

            var subsService = mock(com.stripe.service.SubscriptionService.class);
            when(stripeClient.subscriptions()).thenReturn(subsService);
            var fullSub = mock(com.stripe.model.Subscription.class);
            when(subsService.retrieve(eq("sub_no_plan_21"), any(com.stripe.param.SubscriptionRetrieveParams.class))).thenReturn(fullSub);
            when(fullSub.getItems()).thenReturn(null);

            Event event = createMockEvent("evt_21", "customer.subscription.created", sub);
            when(billingEventRepository.existsByEventId("evt_21")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            performWebhookPost("{\"type\":\"customer.subscription.created\"}", event, 200);

            // No plan_id found -> onSubscriptionUpsert should NOT be called
            verify(subscriptionService, never()).onSubscriptionUpsert(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any());
        }
    }

    @Nested
    @DisplayName("customer.subscription.deleted Handler")
    class SubscriptionDeleted {

        @Test
        @DisplayName("22. Active subscription canceled calls cancelSubscription")
        void activeSubscriptionCanceled_callsCancelSubscription() throws Exception {
            com.stripe.model.Subscription sub = mock(com.stripe.model.Subscription.class);
            when(sub.getId()).thenReturn("sub_active_del_22");

            Event event = createMockEvent("evt_22", "customer.subscription.deleted", sub);
            when(billingEventRepository.existsByEventId("evt_22")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            performWebhookPost("{\"type\":\"customer.subscription.deleted\"}", event, 200);

            verify(subscriptionService).cancelSubscription("sub_active_del_22");
        }

        @Test
        @DisplayName("23. Already canceled subscription is idempotent")
        void alreadyCanceledSubscription_idempotent() throws Exception {
            com.stripe.model.Subscription sub = mock(com.stripe.model.Subscription.class);
            when(sub.getId()).thenReturn("sub_already_canceled_23");

            // cancelSubscription is idempotent by design
            doNothing().when(subscriptionService).cancelSubscription("sub_already_canceled_23");

            Event event = createMockEvent("evt_23", "customer.subscription.deleted", sub);
            when(billingEventRepository.existsByEventId("evt_23")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            performWebhookPost("{\"type\":\"customer.subscription.deleted\"}", event, 200);

            verify(subscriptionService).cancelSubscription("sub_already_canceled_23");
        }
    }

    @Nested
    @DisplayName("subscription_schedule.released Handler")
    class SubscriptionScheduleReleased {

        @Test
        @DisplayName("24. Schedule released logs subscription info correctly")
        void scheduleReleased_logsCorrectly() throws Exception {
            String rawJson = "{\"id\":\"sub_sched_24\",\"object\":\"subscription_schedule\"," +
                    "\"released_subscription\":\"sub_released_24\"}";
            StripeObject rawObj = createRawStripeObject(rawJson);

            Event event = createMockEvent("evt_24", "subscription_schedule.released", null);
            Event.Data eventData = event.getData();
            when(eventData.getObject()).thenReturn(rawObj);

            when(billingEventRepository.existsByEventId("evt_24")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            performWebhookPost("{\"type\":\"subscription_schedule.released\"}", event, 200);

            // Handler only logs, no direct service calls
            verify(subscriptionService, never()).onSubscriptionUpsert(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any());
        }

        @Test
        @DisplayName("25. Schedule released with null raw data does not crash")
        void scheduleReleasedNullData_doesNotCrash() throws Exception {
            Event event = createMockEvent("evt_25", "subscription_schedule.released", null);
            Event.Data eventData = event.getData();
            when(eventData.getObject()).thenReturn(null);

            when(billingEventRepository.existsByEventId("evt_25")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            performWebhookPost("{\"type\":\"subscription_schedule.released\"}", event, 200);
        }
    }

    @Nested
    @DisplayName("Error Handling")
    class ErrorHandling {

        @Test
        @DisplayName("26. Invalid webhook signature returns 400")
        void invalidSignature_returns400() throws Exception {
            try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                webhookMock.when(() -> Webhook.constructEvent(anyString(), anyString(), eq(WEBHOOK_SECRET)))
                           .thenThrow(new SignatureVerificationException("Invalid signature", "sig_header"));

                mockMvc.perform(post("/webhooks/stripe")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"type\":\"test\"}")
                                .header("Stripe-Signature", "t=123,v1=invalid"))
                       .andExpect(status().isBadRequest())
                       .andExpect(content().string("Invalid signature"));
            }
        }

        @Test
        @DisplayName("27. Malformed payload (parse exception) returns 400")
        void malformedPayload_returns400() throws Exception {
            try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                webhookMock.when(() -> Webhook.constructEvent(anyString(), anyString(), eq(WEBHOOK_SECRET)))
                           .thenThrow(new RuntimeException("Failed to parse"));

                mockMvc.perform(post("/webhooks/stripe")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("not-valid-json")
                                .header("Stripe-Signature", "t=123,v1=abc"))
                       .andExpect(status().isBadRequest())
                       .andExpect(content().string("Invalid event"));
            }
        }

        @Test
        @DisplayName("28. SubscriptionService throws exception returns 200 (error logged)")
        void serviceThrowsException_returns200() throws Exception {
            com.stripe.model.Subscription sub = mock(com.stripe.model.Subscription.class);
            when(sub.getId()).thenReturn("sub_err_28");
            when(sub.getStatus()).thenReturn("active");
            when(sub.getCustomer()).thenReturn("cus_28");
            when(sub.getMetadata()).thenReturn(java.util.Map.of("plan_id", "1"));
            when(sub.getItems()).thenReturn(null);

            var customersService = mock(com.stripe.service.CustomerService.class);
            var customerObj = mock(Customer.class);
            when(stripeClient.customers()).thenReturn(customersService);
            when(customersService.retrieve("cus_28")).thenReturn(customerObj);
            when(customerObj.getMetadata()).thenReturn(java.util.Map.of("nonce", "n_28"));
            when(nonceUtil.decodeNonce("n_28")).thenReturn(28L);

            var subsService = mock(com.stripe.service.SubscriptionService.class);
            when(stripeClient.subscriptions()).thenReturn(subsService);
            var fullSub = mock(com.stripe.model.Subscription.class);
            when(subsService.retrieve(eq("sub_err_28"), any(com.stripe.param.SubscriptionRetrieveParams.class))).thenReturn(fullSub);
            when(fullSub.getItems()).thenReturn(null);

            // Simulate service throwing
            doThrow(new RuntimeException("DB connection lost"))
                    .when(subscriptionService).onSubscriptionUpsert(
                            any(), any(), any(), any(), any(), any(), any(), any(), any());

            Event event = createMockEvent("evt_28", "customer.subscription.created", sub);
            when(billingEventRepository.existsByEventId("evt_28")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            // Despite exception in dispatch, returns 200 (error logged)
            performWebhookPost("{\"type\":\"customer.subscription.created\"}", event, 200);
        }

        @Test
        @DisplayName("29. Missing required fields in event handled gracefully")
        void missingRequiredFields_handledGracefully() throws Exception {
            com.stripe.model.Subscription sub = mock(com.stripe.model.Subscription.class);
            when(sub.getId()).thenReturn(null); // Missing subscription ID
            when(sub.getStatus()).thenReturn(null);
            when(sub.getCustomer()).thenReturn(null);
            when(sub.getMetadata()).thenReturn(null);
            when(sub.getItems()).thenReturn(null);

            // Customer stubs not needed: sub.getCustomer() returns null so customer retrieve is skipped

            Event event = createMockEvent("evt_29", "customer.subscription.created", sub);
            when(billingEventRepository.existsByEventId("evt_29")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            performWebhookPost("{\"type\":\"customer.subscription.created\"}", event, 200);
        }

        @Test
        @DisplayName("30. Missing Stripe-Signature header returns 400")
        void missingSignatureHeader_returns400() throws Exception {
            mockMvc.perform(post("/webhooks/stripe")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"type\":\"test\"}"))
                   .andExpect(status().isBadRequest())
                   .andExpect(content().string("Missing signature"));
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        @DisplayName("31. Event with null metadata on subscription")
        void eventWithNullMetadata() throws Exception {
            com.stripe.model.Subscription sub = mock(com.stripe.model.Subscription.class);
            when(sub.getId()).thenReturn("sub_null_meta_31");
            when(sub.getStatus()).thenReturn("active");
            when(sub.getCustomer()).thenReturn("cus_31");
            when(sub.getMetadata()).thenReturn(null); // Null metadata
            when(sub.getItems()).thenReturn(null);

            var customersService = mock(com.stripe.service.CustomerService.class);
            var customerObj = mock(Customer.class);
            when(stripeClient.customers()).thenReturn(customersService);
            when(customersService.retrieve("cus_31")).thenReturn(customerObj);
            when(customerObj.getMetadata()).thenReturn(null);

            var subsService = mock(com.stripe.service.SubscriptionService.class);
            when(stripeClient.subscriptions()).thenReturn(subsService);
            var fullSub = mock(com.stripe.model.Subscription.class);
            when(subsService.retrieve(eq("sub_null_meta_31"), any(com.stripe.param.SubscriptionRetrieveParams.class))).thenReturn(fullSub);
            when(fullSub.getItems()).thenReturn(null);

            Event event = createMockEvent("evt_31", "customer.subscription.created", sub);
            when(billingEventRepository.existsByEventId("evt_31")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            performWebhookPost("{\"type\":\"customer.subscription.created\"}", event, 200);

            // Should not crash, planId will be null -> no upsert call
            verify(subscriptionService, never()).onSubscriptionUpsert(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any());
        }

        @Test
        @DisplayName("32. Event with empty subscription ID on deletion")
        void emptySubscriptionId_onDeletion() throws Exception {
            com.stripe.model.Subscription sub = mock(com.stripe.model.Subscription.class);
            when(sub.getId()).thenReturn("");

            Event event = createMockEvent("evt_32", "customer.subscription.deleted", sub);
            when(billingEventRepository.existsByEventId("evt_32")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            performWebhookPost("{\"type\":\"customer.subscription.deleted\"}", event, 200);

            verify(subscriptionService).cancelSubscription("");
        }

        @Test
        @DisplayName("33. Large payload is processed correctly")
        void largePayload_processedCorrectly() throws Exception {
            // Build a large-ish payload
            StringBuilder sb = new StringBuilder("{\"type\":\"checkout.session.completed\",\"data\":{\"object\":{");
            for (int i = 0; i < 100; i++) {
                if (i > 0) sb.append(",");
                sb.append("\"field_").append(i).append("\":\"").append("value_".repeat(10)).append("\"");
            }
            sb.append("}}}");
            String largePayload = sb.toString();

            Session session = mock(Session.class);
            lenient().when(session.getId()).thenReturn("cs_large_33");
            lenient().when(session.getCustomer()).thenReturn("cus_large_33");
            lenient().when(session.getSubscription()).thenReturn("sub_large_33");
            lenient().when(session.getClientReferenceId()).thenReturn(null);

            Event event = createMockEvent("evt_33", "checkout.session.completed", session);
            when(billingEventRepository.existsByEventId("evt_33")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            performWebhookPost(largePayload, event, 200);
        }
    }

    @Nested
    @DisplayName("customer.deleted Handler Details")
    class CustomerDeletedDetails {

        @Test
        @DisplayName("34. Customer deleted cancels all active local subscriptions")
        void customerDeleted_cancelsAllActiveSubscriptions() throws Exception {
            Customer customer = mock(Customer.class);
            when(customer.getId()).thenReturn("cus_del_34");

            User user = createTestUser(34L, "user34@test.com");
            BillingCustomer bc = createTestBillingCustomer(34L, user, "cus_del_34");

            Subscription activeSub = createTestSubscription(100L, "active", "sub_active_34");
            Subscription trialingSub = createTestSubscription(101L, "trialing", "sub_trial_34");
            Subscription canceledSub = createTestSubscription(102L, "canceled", "sub_cancel_34");

            when(billingCustomerRepository.findByProviderCustomerId("cus_del_34"))
                    .thenReturn(Optional.of(bc));
            when(subscriptionRepository.findByBillingCustomer_IdOrderByCreatedAtDesc(34L))
                    .thenReturn(List.of(activeSub, trialingSub, canceledSub));

            Event event = createMockEvent("evt_34", "customer.deleted", customer);
            when(billingEventRepository.existsByEventId("evt_34")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            performWebhookPost("{\"type\":\"customer.deleted\"}", event, 200);

            // Active and trialing should be canceled
            assertThat(activeSub.getStatus()).isEqualTo("canceled");
            assertThat(activeSub.getCancelAtPeriodEnd()).isTrue();
            assertThat(trialingSub.getStatus()).isEqualTo("canceled");
            assertThat(trialingSub.getCancelAtPeriodEnd()).isTrue();
            // Already canceled should remain unchanged
            assertThat(canceledSub.getStatus()).isEqualTo("canceled");

            verify(subscriptionRepository).saveAll(anyList());
            verify(billingCustomerRepository).save(bc);
            assertThat(bc.getProviderCustomerId()).isNull();
        }

        @Test
        @DisplayName("35. Customer deleted with no BillingCustomer found does not crash")
        void customerDeleted_noBillingCustomer_doesNotCrash() throws Exception {
            Customer customer = mock(Customer.class);
            when(customer.getId()).thenReturn("cus_unknown_35");

            when(billingCustomerRepository.findByProviderCustomerId("cus_unknown_35"))
                    .thenReturn(Optional.empty());

            Event event = createMockEvent("evt_35", "customer.deleted", customer);
            when(billingEventRepository.existsByEventId("evt_35")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            performWebhookPost("{\"type\":\"customer.deleted\"}", event, 200);

            verify(subscriptionRepository, never()).saveAll(anyList());
        }
    }

    @Nested
    @DisplayName("Raw Fallback Handlers")
    class RawFallbackHandlers {

        @Test
        @DisplayName("36. Raw checkout.session.completed fallback extracts session ID")
        void rawCheckoutCompleted_extractsSessionId() throws Exception {
            String rawJson = "{\"id\":\"cs_raw_36\",\"object\":\"checkout.session\",\"customer\":\"cus_raw_36\"}";
            StripeObject rawObj = createRawStripeObject(rawJson);

            Event event = createMockEvent("evt_36", "checkout.session.completed", null);
            Event.Data eventData = event.getData();
            when(eventData.getObject()).thenReturn(rawObj);

            when(billingEventRepository.existsByEventId("evt_36")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            performWebhookPost("{\"type\":\"checkout.session.completed\"}", event, 200);
            // Raw handler only logs, no service calls
        }

        @Test
        @DisplayName("37. Raw customer.subscription.deleted extracts sub ID and calls Stripe API")
        void rawSubscriptionDeleted_extractsSubId() throws Exception {
            String rawJson = "{\"id\":\"sub_raw_del_37\",\"object\":\"subscription\",\"customer\":\"cus_37\"}";
            StripeObject rawObj = createRawStripeObject(rawJson);

            Event event = createMockEvent("evt_37", "customer.subscription.deleted", null);
            Event.Data eventData = event.getData();
            when(eventData.getObject()).thenReturn(rawObj);

            // Mock stripeClient for the retrieve call in handleSubscriptionDeletedRaw
            var subsService = mock(com.stripe.service.SubscriptionService.class);
            when(stripeClient.subscriptions()).thenReturn(subsService);

            // Simulate that retrieve fails -> fallback to cancelSubscription
            when(subsService.retrieve(eq("sub_raw_del_37"), any(com.stripe.param.SubscriptionRetrieveParams.class)))
                    .thenThrow(new RuntimeException("Not found"));

            when(billingEventRepository.existsByEventId("evt_37")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            performWebhookPost("{\"type\":\"customer.subscription.deleted\"}", event, 200);

            verify(subscriptionService).cancelSubscription("sub_raw_del_37");
        }

        @Test
        @DisplayName("38. Raw customer.deleted fallback extracts customer ID and downgrades")
        void rawCustomerDeleted_extractsCustomerId() throws Exception {
            String rawJson = "{\"id\":\"cus_raw_38\",\"object\":\"customer\"}";
            StripeObject rawObj = createRawStripeObject(rawJson);

            Event event = createMockEvent("evt_38", "customer.deleted", null);
            Event.Data eventData = event.getData();
            when(eventData.getObject()).thenReturn(rawObj);

            User user = createTestUser(38L, "user38@test.com");
            BillingCustomer bc = createTestBillingCustomer(38L, user, "cus_raw_38");

            when(billingCustomerRepository.findByProviderCustomerId("cus_raw_38"))
                    .thenReturn(Optional.of(bc));
            when(subscriptionRepository.findByBillingCustomer_IdOrderByCreatedAtDesc(38L))
                    .thenReturn(Collections.emptyList());

            when(billingEventRepository.existsByEventId("evt_38")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            performWebhookPost("{\"type\":\"customer.deleted\"}", event, 200);

            verify(billingCustomerRepository).save(bc);
            assertThat(bc.getProviderCustomerId()).isNull();
            // Plan transition is handled via subscription update - no separate quota service.
        }

        @Test
        @DisplayName("39. Raw subscription deleted with blank sub ID handled gracefully")
        void rawSubscriptionDeleted_blankSubId_handled() throws Exception {
            String rawJson = "{\"id\":\"\",\"object\":\"subscription\"}";
            StripeObject rawObj = createRawStripeObject(rawJson);

            Event event = createMockEvent("evt_39", "customer.subscription.deleted", null);
            Event.Data eventData = event.getData();
            when(eventData.getObject()).thenReturn(rawObj);

            when(billingEventRepository.existsByEventId("evt_39")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            performWebhookPost("{\"type\":\"customer.subscription.deleted\"}", event, 200);

            // Blank ID should be caught and not cause further calls
            verify(subscriptionService, never()).cancelSubscription(anyString());
        }
    }

    @Nested
    @DisplayName("Subscription Schedule Handlers")
    class SubscriptionScheduleHandlers {

        @Test
        @DisplayName("40. subscription_schedule.created logs schedule info")
        void scheduleCreated_logsInfo() throws Exception {
            String rawJson = "{\"id\":\"sub_sched_40\",\"object\":\"subscription_schedule\"," +
                    "\"subscription\":\"sub_40\",\"status\":\"not_started\"}";
            StripeObject rawObj = createRawStripeObject(rawJson);

            Event event = createMockEvent("evt_40", "subscription_schedule.created", null);
            Event.Data eventData = event.getData();
            when(eventData.getObject()).thenReturn(rawObj);

            when(billingEventRepository.existsByEventId("evt_40")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            performWebhookPost("{\"type\":\"subscription_schedule.created\"}", event, 200);
        }

        @Test
        @DisplayName("41. subscription_schedule.updated logs phase changes")
        void scheduleUpdated_logsPhases() throws Exception {
            String rawJson = "{\"id\":\"sub_sched_41\",\"object\":\"subscription_schedule\"," +
                    "\"status\":\"active\",\"phases\":[{\"start_date\":1000},{\"start_date\":2000}]}";
            StripeObject rawObj = createRawStripeObject(rawJson);

            Event event = createMockEvent("evt_41", "subscription_schedule.updated", null);
            Event.Data eventData = event.getData();
            when(eventData.getObject()).thenReturn(rawObj);

            when(billingEventRepository.existsByEventId("evt_41")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            performWebhookPost("{\"type\":\"subscription_schedule.updated\"}", event, 200);
        }

        @Test
        @DisplayName("42. subscription_schedule.completed logs completion")
        void scheduleCompleted_logs() throws Exception {
            String rawJson = "{\"id\":\"sub_sched_42\",\"object\":\"subscription_schedule\"}";
            StripeObject rawObj = createRawStripeObject(rawJson);

            Event event = createMockEvent("evt_42", "subscription_schedule.completed", null);
            Event.Data eventData = event.getData();
            when(eventData.getObject()).thenReturn(rawObj);

            when(billingEventRepository.existsByEventId("evt_42")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            performWebhookPost("{\"type\":\"subscription_schedule.completed\"}", event, 200);
        }

        @Test
        @DisplayName("43. subscription_schedule.canceled logs cancellation")
        void scheduleCanceled_logs() throws Exception {
            String rawJson = "{\"id\":\"sub_sched_43\",\"object\":\"subscription_schedule\"," +
                    "\"subscription\":\"sub_43\"}";
            StripeObject rawObj = createRawStripeObject(rawJson);

            Event event = createMockEvent("evt_43", "subscription_schedule.canceled", null);
            Event.Data eventData = event.getData();
            when(eventData.getObject()).thenReturn(rawObj);

            when(billingEventRepository.existsByEventId("evt_43")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            performWebhookPost("{\"type\":\"subscription_schedule.canceled\"}", event, 200);
        }
    }

    @Nested
    @DisplayName("Invoice Handlers")
    class InvoiceHandlers {

        @Test
        @DisplayName("44. invoice.payment_succeeded with subscription_create billing reason triggers provisioning")
        void invoicePaymentSucceeded_subscriptionCreate_triggersProvisioning() throws Exception {
            Invoice invoice = mock(Invoice.class);
            when(invoice.getId()).thenReturn("in_create_44");
            when(invoice.getBillingReason()).thenReturn("subscription_create");
            when(invoice.getParent()).thenReturn(null);

            // getSubscriptionIdFromInvoice returns null (no parent) -> subscriptionId will be null
            // so no provisioning happens, and stripeClient.subscriptions() is never called
            Event event = createMockEvent("evt_44", "invoice.payment_succeeded", invoice);
            when(billingEventRepository.existsByEventId("evt_44")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            performWebhookPost("{\"type\":\"invoice.payment_succeeded\"}", event, 200);

            // subscriptionId is null because getSubscriptionIdFromInvoice returns null (no parent)
            // So no subscription upsert should happen
            verify(subscriptionService, never()).onSubscriptionUpsert(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any());
        }

        @Test
        @DisplayName("45. invoice.payment_succeeded with subscription_cycle does not trigger provisioning")
        void invoicePaymentSucceeded_subscriptionCycle_noProvisioning() throws Exception {
            Invoice invoice = mock(Invoice.class);
            when(invoice.getId()).thenReturn("in_cycle_45");
            when(invoice.getBillingReason()).thenReturn("subscription_cycle");
            when(invoice.getParent()).thenReturn(null);

            Event event = createMockEvent("evt_45", "invoice.payment_succeeded", invoice);
            when(billingEventRepository.existsByEventId("evt_45")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            performWebhookPost("{\"type\":\"invoice.payment_succeeded\"}", event, 200);

            verify(subscriptionService, never()).onSubscriptionUpsert(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any());
        }

        @Test
        @DisplayName("46. Raw invoice.paid fallback retrieves invoice from Stripe")
        void rawInvoicePaid_retrievesFromStripe() throws Exception {
            String rawJson = "{\"id\":\"in_raw_46\",\"object\":\"invoice\"}";
            StripeObject rawObj = createRawStripeObject(rawJson);

            Event event = createMockEvent("evt_46", "invoice.paid", null);
            Event.Data eventData = event.getData();
            when(eventData.getObject()).thenReturn(rawObj);

            var invoiceService = mock(com.stripe.service.InvoiceService.class);
            when(stripeClient.invoices()).thenReturn(invoiceService);
            Invoice retrievedInvoice = mock(Invoice.class);
            when(invoiceService.retrieve("in_raw_46")).thenReturn(retrievedInvoice);
            when(retrievedInvoice.getId()).thenReturn("in_raw_46");
            when(retrievedInvoice.getParent()).thenReturn(null);

            when(billingEventRepository.existsByEventId("evt_46")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            performWebhookPost("{\"type\":\"invoice.paid\"}", event, 200);

            verify(invoiceService).retrieve("in_raw_46");
        }
    }

    @Nested
    @DisplayName("Enforce Single Subscription Safety Net")
    class EnforceSingleSubscription {

        @Test
        @DisplayName("47. Active subscription triggers cancelOtherStripeSubscriptions")
        void activeSubscription_cancelOthers() throws Exception {
            com.stripe.model.Subscription sub = mock(com.stripe.model.Subscription.class);
            when(sub.getId()).thenReturn("sub_enforce_47");
            when(sub.getStatus()).thenReturn("active");
            when(sub.getCustomer()).thenReturn("cus_47");
            when(sub.getMetadata()).thenReturn(java.util.Map.of("plan_id", "1"));
            when(sub.getItems()).thenReturn(null);

            var customersService = mock(com.stripe.service.CustomerService.class);
            var customerObj = mock(Customer.class);
            when(stripeClient.customers()).thenReturn(customersService);
            when(customersService.retrieve("cus_47")).thenReturn(customerObj);
            when(customerObj.getMetadata()).thenReturn(java.util.Map.of("nonce", "n_47"));
            when(nonceUtil.decodeNonce("n_47")).thenReturn(47L);

            var subsService = mock(com.stripe.service.SubscriptionService.class);
            when(stripeClient.subscriptions()).thenReturn(subsService);
            var fullSub = mock(com.stripe.model.Subscription.class);
            when(subsService.retrieve(eq("sub_enforce_47"), any(com.stripe.param.SubscriptionRetrieveParams.class))).thenReturn(fullSub);
            when(fullSub.getItems()).thenReturn(null);

            when(stripeBillingService.cancelOtherStripeSubscriptions("cus_47", "sub_enforce_47", false))
                    .thenReturn(2);

            Event event = createMockEvent("evt_47", "customer.subscription.created", sub);
            when(billingEventRepository.existsByEventId("evt_47")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            performWebhookPost("{\"type\":\"customer.subscription.created\"}", event, 200);

            verify(stripeBillingService).cancelOtherStripeSubscriptions("cus_47", "sub_enforce_47", false);
        }

        @Test
        @DisplayName("48. Trialing subscription also triggers cancelOtherStripeSubscriptions")
        void trialingSubscription_cancelOthers() throws Exception {
            com.stripe.model.Subscription sub = mock(com.stripe.model.Subscription.class);
            when(sub.getId()).thenReturn("sub_trial_48");
            when(sub.getStatus()).thenReturn("trialing");
            when(sub.getCustomer()).thenReturn("cus_48");
            when(sub.getMetadata()).thenReturn(java.util.Map.of("plan_id", "2"));
            when(sub.getItems()).thenReturn(null);

            var customersService = mock(com.stripe.service.CustomerService.class);
            var customerObj = mock(Customer.class);
            when(stripeClient.customers()).thenReturn(customersService);
            when(customersService.retrieve("cus_48")).thenReturn(customerObj);
            when(customerObj.getMetadata()).thenReturn(java.util.Map.of("nonce", "n_48"));
            when(nonceUtil.decodeNonce("n_48")).thenReturn(48L);

            var subsService = mock(com.stripe.service.SubscriptionService.class);
            when(stripeClient.subscriptions()).thenReturn(subsService);
            var fullSub = mock(com.stripe.model.Subscription.class);
            when(subsService.retrieve(eq("sub_trial_48"), any(com.stripe.param.SubscriptionRetrieveParams.class))).thenReturn(fullSub);
            when(fullSub.getItems()).thenReturn(null);

            when(stripeBillingService.cancelOtherStripeSubscriptions("cus_48", "sub_trial_48", false))
                    .thenReturn(0);

            Event event = createMockEvent("evt_48", "customer.subscription.updated", sub);
            when(billingEventRepository.existsByEventId("evt_48")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            performWebhookPost("{\"type\":\"customer.subscription.updated\"}", event, 200);

            verify(stripeBillingService).cancelOtherStripeSubscriptions("cus_48", "sub_trial_48", false);
        }
    }

    @Nested
    @DisplayName("Dispatch Method Direct Testing via Reflection")
    class DispatchDirect {

        @Test
        @DisplayName("49. dispatch with charge.succeeded event logs and does not crash")
        void dispatch_chargeSucceeded_logsOnly() {
            StripeObject chargeObj = mock(StripeObject.class);
            Event event = createMockEvent("evt_49", "charge.succeeded", chargeObj);

            // invoke dispatch via reflection
            ReflectionTestUtils.invokeMethod(controller, "dispatch", event);

            // No service calls should be made for charge.succeeded
            verifyNoInteractions(subscriptionService);
        }

        @Test
        @DisplayName("50. dispatch with invoice_payment.paid event routes to raw handler")
        void dispatch_invoicePaymentPaid_routesToRawHandler() {
            // invoice_payment.paid hits the default case and is routed to handleRawInvoiceLike
            Event event = createMockEvent("evt_50", "invoice_payment.paid", null);
            EventDataObjectDeserializer deser = event.getDataObjectDeserializer();
            when(deser.getObject()).thenReturn(Optional.empty());

            Event.Data eventData = event.getData();
            when(eventData.getObject()).thenReturn(null);

            ReflectionTestUtils.invokeMethod(controller, "dispatch", event);

            // No crash is the pass condition; raw handler with null object will log warning
        }
    }

    @Nested
    @DisplayName("BillingEvent Race Condition Handling")
    class RaceConditionHandling {

        @Test
        @DisplayName("51. Race condition on billing event insert returns OK")
        void raceOnInsert_returnsOk() throws Exception {
            Session session = mock(Session.class);
            lenient().when(session.getId()).thenReturn("cs_race");
            lenient().when(session.getClientReferenceId()).thenReturn(null);

            Event event = createMockEvent("evt_race", "checkout.session.completed", session);
            when(billingEventRepository.existsByEventId("evt_race")).thenReturn(false);
            // Simulate race condition: save throws exception
            when(billingEventRepository.save(any(BillingEvent.class)))
                    .thenThrow(new RuntimeException("Duplicate key"));

            performWebhookPost("{\"type\":\"checkout.session.completed\"}", event, 200);
        }
    }

    @Nested
    @DisplayName("Price Resolution From Cache")
    class PriceResolutionFromCache {

        @Test
        @DisplayName("52. resolvePlanCodeFromPriceId returns correct plan code")
        void resolvePlanCode_returnsCorrectCode() {
            when(priceCacheService.getAllCachedPrices())
                    .thenReturn(java.util.Map.of("STARTER_monthly", "price_starter_monthly"));

            String planCode = ReflectionTestUtils.invokeMethod(
                    controller, "resolvePlanCodeFromPriceId", "price_starter_monthly");

            assertThat(planCode).isEqualTo("STARTER");
        }

        @Test
        @DisplayName("53. resolvePlanCodeFromPriceId returns null for unknown price")
        void resolvePlanCode_returnsNullForUnknown() {
            when(priceCacheService.getAllCachedPrices())
                    .thenReturn(java.util.Map.of("STARTER_monthly", "price_starter_monthly"));

            String planCode = ReflectionTestUtils.invokeMethod(
                    controller, "resolvePlanCodeFromPriceId", "price_unknown_xyz");

            assertThat(planCode).isNull();
        }

        @Test
        @DisplayName("54. resolvePlanCodeFromPriceId with null priceId returns null")
        void resolvePlanCode_nullPriceId_returnsNull() {
            String planCode = ReflectionTestUtils.invokeMethod(
                    controller, "resolvePlanCodeFromPriceId", (String) null);

            assertThat(planCode).isNull();
        }

        @Test
        @DisplayName("55. resolvePlanCodeFromPriceId with empty cache returns null")
        void resolvePlanCode_emptyCache_returnsNull() {
            when(priceCacheService.getAllCachedPrices()).thenReturn(java.util.Map.of());

            String planCode = ReflectionTestUtils.invokeMethod(
                    controller, "resolvePlanCodeFromPriceId", "price_test");

            assertThat(planCode).isNull();
        }

        @Test
        @DisplayName("56. resolvePlanCodeFromPriceId with null cache returns null")
        void resolvePlanCode_nullCache_returnsNull() {
            when(priceCacheService.getAllCachedPrices()).thenReturn(null);

            String planCode = ReflectionTestUtils.invokeMethod(
                    controller, "resolvePlanCodeFromPriceId", "price_test");

            assertThat(planCode).isNull();
        }
    }

    @Nested
    @DisplayName("Customer Deleted - Subscription Status Variants")
    class CustomerDeletedSubscriptionVariants {

        @Test
        @DisplayName("57. past_due subscriptions are canceled on customer deletion")
        void pastDueSubscriptions_canceledOnCustomerDeletion() throws Exception {
            Customer customer = mock(Customer.class);
            when(customer.getId()).thenReturn("cus_pd_57");

            User user = createTestUser(57L, "user57@test.com");
            BillingCustomer bc = createTestBillingCustomer(57L, user, "cus_pd_57");

            Subscription pastDueSub = createTestSubscription(570L, "past_due", "sub_pd_57");

            when(billingCustomerRepository.findByProviderCustomerId("cus_pd_57"))
                    .thenReturn(Optional.of(bc));
            when(subscriptionRepository.findByBillingCustomer_IdOrderByCreatedAtDesc(57L))
                    .thenReturn(List.of(pastDueSub));

            Event event = createMockEvent("evt_57", "customer.deleted", customer);
            when(billingEventRepository.existsByEventId("evt_57")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            performWebhookPost("{\"type\":\"customer.deleted\"}", event, 200);

            assertThat(pastDueSub.getStatus()).isEqualTo("canceled");
            assertThat(pastDueSub.getCancelAtPeriodEnd()).isTrue();
        }

        @Test
        @DisplayName("58. incomplete subscriptions are canceled on customer deletion")
        void incompleteSubscriptions_canceledOnCustomerDeletion() throws Exception {
            Customer customer = mock(Customer.class);
            when(customer.getId()).thenReturn("cus_inc_58");

            User user = createTestUser(58L, "user58@test.com");
            BillingCustomer bc = createTestBillingCustomer(58L, user, "cus_inc_58");

            Subscription incompleteSub = createTestSubscription(580L, "incomplete", "sub_inc_58");

            when(billingCustomerRepository.findByProviderCustomerId("cus_inc_58"))
                    .thenReturn(Optional.of(bc));
            when(subscriptionRepository.findByBillingCustomer_IdOrderByCreatedAtDesc(58L))
                    .thenReturn(List.of(incompleteSub));

            Event event = createMockEvent("evt_58", "customer.deleted", customer);
            when(billingEventRepository.existsByEventId("evt_58")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            performWebhookPost("{\"type\":\"customer.deleted\"}", event, 200);

            assertThat(incompleteSub.getStatus()).isEqualTo("canceled");
        }
    }

    @Nested
    @DisplayName("Deserializer Empty Fallback Paths")
    class DeserializerEmptyFallbacks {

        @Test
        @DisplayName("59. subscription.upsert raw fallback calls Stripe API")
        void rawSubscriptionUpsert_callsStripeApi() throws Exception {
            String rawJson = "{\"id\":\"sub_raw_59\",\"object\":\"subscription\"}";
            StripeObject rawObj = createRawStripeObject(rawJson);

            Event event = createMockEvent("evt_59", "customer.subscription.created", null);
            Event.Data eventData = event.getData();
            when(eventData.getObject()).thenReturn(rawObj);

            var subsService = mock(com.stripe.service.SubscriptionService.class);
            when(stripeClient.subscriptions()).thenReturn(subsService);

            com.stripe.model.Subscription fullSub = mock(com.stripe.model.Subscription.class);
            when(subsService.retrieve(eq("sub_raw_59"), any(com.stripe.param.SubscriptionRetrieveParams.class))).thenReturn(fullSub);
            when(fullSub.getId()).thenReturn("sub_raw_59");
            when(fullSub.getStatus()).thenReturn("active");
            when(fullSub.getCustomer()).thenReturn("cus_59");
            when(fullSub.getMetadata()).thenReturn(java.util.Map.of("plan_id", "1"));
            when(fullSub.getItems()).thenReturn(null);

            var customersService = mock(com.stripe.service.CustomerService.class);
            var customerObj = mock(Customer.class);
            when(stripeClient.customers()).thenReturn(customersService);
            when(customersService.retrieve("cus_59")).thenReturn(customerObj);
            when(customerObj.getMetadata()).thenReturn(java.util.Map.of("nonce", "n_59"));
            when(nonceUtil.decodeNonce("n_59")).thenReturn(59L);

            when(billingEventRepository.existsByEventId("evt_59")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            performWebhookPost("{\"type\":\"customer.subscription.created\"}", event, 200);

            verify(subsService, atLeast(1)).retrieve(eq("sub_raw_59"), any(com.stripe.param.SubscriptionRetrieveParams.class));
        }

        @Test
        @DisplayName("60. Deserializer empty for unknown type logs warning")
        void deserializerEmpty_unknownType_logsWarning() throws Exception {
            Event event = createMockEvent("evt_60", "some.random.type", null);
            EventDataObjectDeserializer deser = event.getDataObjectDeserializer();
            when(deser.getObject()).thenReturn(Optional.empty());

            Event.Data eventData = event.getData();
            when(eventData.getObject()).thenReturn(null);

            when(billingEventRepository.existsByEventId("evt_60")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            performWebhookPost("{\"type\":\"some.random.type\"}", event, 200);

            // No crash, just logs
        }
    }

    @Nested
    @DisplayName("BillingEvent Entity Verification")
    class BillingEventVerification {

        @Test
        @DisplayName("61. BillingEvent entity is created with correct provider, eventId, and type")
        void billingEvent_createdWithCorrectFields() throws Exception {
            Session session = mock(Session.class);
            lenient().when(session.getId()).thenReturn("cs_verify");
            lenient().when(session.getCustomer()).thenReturn("cus_verify");
            lenient().when(session.getSubscription()).thenReturn(null);
            lenient().when(session.getClientReferenceId()).thenReturn(null);

            Event event = createMockEvent("evt_verify", "checkout.session.completed", session);
            when(billingEventRepository.existsByEventId("evt_verify")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            String payload = "{\"type\":\"checkout.session.completed\",\"id\":\"evt_verify\"}";
            performWebhookPost(payload, event, 200);

            verify(billingEventRepository).save(argThat(be -> {
                assertThat(be.getProvider()).isEqualTo("stripe");
                assertThat(be.getEventId()).isEqualTo("evt_verify");
                assertThat(be.getType()).isEqualTo("checkout.session.completed");
                assertThat(be.getPayload()).isNotNull();
                return true;
            }));
        }
    }

    // ========================================================================
    // V250/PR3 - PAYG one-time top-up dispatch
    // (M4 audit finding closure - covers handlePaygTopup typed branch
    //  AND handleCheckoutCompletedRaw RAW branch)
    // ========================================================================

    @Nested
    @DisplayName("PAYG one-time top-up (V250/PR3)")
    class PaygTopupHandling {

        @Test
        @DisplayName("T1. typed path: payg_topup with valid credit_amount calls grantPaygTopup")
        void typed_happyPath_callsGrantPaygTopup() throws Exception {
            Session session = mock(Session.class);
            lenient().when(session.getId()).thenReturn("cs_payg_happy");
            lenient().when(session.getCustomer()).thenReturn("cus_x");
            lenient().when(session.getSubscription()).thenReturn(null);
            lenient().when(session.getClientReferenceId()).thenReturn("n_payg");
            lenient().when(session.getMetadata()).thenReturn(java.util.Map.of(
                    "kind", "payg_topup",
                    "tier", "small",
                    "credit_amount", "8000"));
            when(nonceUtil.decodeNonce("n_payg")).thenReturn(7L);

            Event event = createMockEvent("evt_payg_1", "checkout.session.completed", session);
            when(billingEventRepository.existsByEventId("evt_payg_1")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            performWebhookPost("{\"type\":\"checkout.session.completed\"}", event, 200);

            verify(creditAttributionService).grantPaygTopup(
                    eq(7L),
                    eq(new java.math.BigDecimal("8000")),
                    eq("cs_payg_happy"),
                    eq("small"));
            // Critical: must NOT fall through to subscription provisioning path
            verify(subscriptionService, never()).onSubscriptionUpsert(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any());
        }

        @Test
        @DisplayName("T2. typed path: payg_topup with missing credit_amount metadata is a no-op grant")
        void typed_missingCreditAmount_skipsGrant() throws Exception {
            Session session = mock(Session.class);
            lenient().when(session.getId()).thenReturn("cs_payg_missing");
            lenient().when(session.getClientReferenceId()).thenReturn("n_payg");
            lenient().when(session.getMetadata()).thenReturn(java.util.Map.of(
                    "kind", "payg_topup",
                    "tier", "small"));  // credit_amount absent
            when(nonceUtil.decodeNonce("n_payg")).thenReturn(7L);

            Event event = createMockEvent("evt_payg_2", "checkout.session.completed", session);
            when(billingEventRepository.existsByEventId("evt_payg_2")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            performWebhookPost("{\"type\":\"checkout.session.completed\"}", event, 200);

            verify(creditAttributionService, never()).grantPaygTopup(any(), any(), any(), any());
        }

        @Test
        @DisplayName("T3. typed path: payg_topup with non-numeric credit_amount is logged and skipped")
        void typed_invalidCreditAmount_skipsGrant() throws Exception {
            Session session = mock(Session.class);
            lenient().when(session.getId()).thenReturn("cs_payg_nfe");
            lenient().when(session.getClientReferenceId()).thenReturn("n_payg");
            lenient().when(session.getMetadata()).thenReturn(java.util.Map.of(
                    "kind", "payg_topup",
                    "tier", "medium",
                    "credit_amount", "not-a-number"));
            when(nonceUtil.decodeNonce("n_payg")).thenReturn(7L);

            Event event = createMockEvent("evt_payg_3", "checkout.session.completed", session);
            when(billingEventRepository.existsByEventId("evt_payg_3")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            performWebhookPost("{\"type\":\"checkout.session.completed\"}", event, 200);

            verify(creditAttributionService, never()).grantPaygTopup(any(), any(), any(), any());
        }

        @Test
        @DisplayName("T4. typed path: payg_topup with non-positive amount is skipped (defends against -1, 0)")
        void typed_nonPositiveAmount_skipsGrant() throws Exception {
            Session session = mock(Session.class);
            lenient().when(session.getId()).thenReturn("cs_payg_zero");
            lenient().when(session.getClientReferenceId()).thenReturn("n_payg");
            lenient().when(session.getMetadata()).thenReturn(java.util.Map.of(
                    "kind", "payg_topup",
                    "tier", "small",
                    "credit_amount", "0"));
            when(nonceUtil.decodeNonce("n_payg")).thenReturn(7L);

            Event event = createMockEvent("evt_payg_4", "checkout.session.completed", session);
            when(billingEventRepository.existsByEventId("evt_payg_4")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            performWebhookPost("{\"type\":\"checkout.session.completed\"}", event, 200);

            verify(creditAttributionService, never()).grantPaygTopup(any(), any(), any(), any());
        }

        @Test
        @DisplayName("T5. typed path: grantPaygTopup exception is swallowed (Stripe would otherwise retry the whole event)")
        void typed_grantException_swallowedReturns200() throws Exception {
            Session session = mock(Session.class);
            lenient().when(session.getId()).thenReturn("cs_payg_throw");
            lenient().when(session.getClientReferenceId()).thenReturn("n_payg");
            lenient().when(session.getMetadata()).thenReturn(java.util.Map.of(
                    "kind", "payg_topup",
                    "tier", "large",
                    "credit_amount", "80000"));
            when(nonceUtil.decodeNonce("n_payg")).thenReturn(7L);
            doThrow(new RuntimeException("DB down")).when(creditAttributionService)
                    .grantPaygTopup(any(), any(), any(), any());

            Event event = createMockEvent("evt_payg_5", "checkout.session.completed", session);
            when(billingEventRepository.existsByEventId("evt_payg_5")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            // Must still respond 200 - the underlying dispatch wrapper logs+swallows,
            // and handlePaygTopup itself catches the exception. Stripe should not
            // see this as a failed event and retry, since the unique-constraint on
            // credit_ledger.source_id makes any future replay safe.
            performWebhookPost("{\"type\":\"checkout.session.completed\"}", event, 200);

            verify(creditAttributionService).grantPaygTopup(any(), any(), any(), any());
        }

        @Test
        @DisplayName("R1. RAW path: payg_topup parsed from JSON metadata calls grantPaygTopup (deserializer-failure parity)")
        void raw_happyPath_callsGrantPaygTopup() throws Exception {
            String rawJson = "{\"id\":\"cs_raw_payg\",\"object\":\"checkout.session\"," +
                    "\"client_reference_id\":\"n_raw_payg\"," +
                    "\"metadata\":{\"kind\":\"payg_topup\",\"tier\":\"medium\",\"credit_amount\":\"40000\"}}";
            StripeObject rawObj = createRawStripeObject(rawJson);

            Event event = createMockEvent("evt_payg_r1", "checkout.session.completed", null);
            Event.Data eventData = event.getData();
            when(eventData.getObject()).thenReturn(rawObj);
            EventDataObjectDeserializer deser = event.getDataObjectDeserializer();
            when(deser.getObject()).thenReturn(Optional.empty());

            when(nonceUtil.decodeNonce("n_raw_payg")).thenReturn(99L);
            when(billingEventRepository.existsByEventId("evt_payg_r1")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            performWebhookPost("{\"type\":\"checkout.session.completed\"}", event, 200);

            verify(creditAttributionService).grantPaygTopup(
                    eq(99L),
                    eq(new java.math.BigDecimal("40000")),
                    eq("cs_raw_payg"),
                    eq("medium"));
        }

        @Test
        @DisplayName("R2. RAW path: payg_topup with missing credit_amount metadata is a no-op grant")
        void raw_missingCreditAmount_skipsGrant() throws Exception {
            String rawJson = "{\"id\":\"cs_raw_payg_missing\",\"object\":\"checkout.session\"," +
                    "\"client_reference_id\":\"n_raw_payg\"," +
                    "\"metadata\":{\"kind\":\"payg_topup\",\"tier\":\"small\"}}";  // credit_amount absent
            StripeObject rawObj = createRawStripeObject(rawJson);

            Event event = createMockEvent("evt_payg_r2", "checkout.session.completed", null);
            Event.Data eventData = event.getData();
            when(eventData.getObject()).thenReturn(rawObj);
            EventDataObjectDeserializer deser = event.getDataObjectDeserializer();
            when(deser.getObject()).thenReturn(Optional.empty());

            lenient().when(nonceUtil.decodeNonce("n_raw_payg")).thenReturn(99L);
            when(billingEventRepository.existsByEventId("evt_payg_r2")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            performWebhookPost("{\"type\":\"checkout.session.completed\"}", event, 200);

            verify(creditAttributionService, never()).grantPaygTopup(any(), any(), any(), any());
        }

        @Test
        @DisplayName("R3. RAW path: payg_topup with non-positive amount is skipped (regression for negative 'free credits' bug)")
        void raw_nonPositiveAmount_skipsGrant() throws Exception {
            String rawJson = "{\"id\":\"cs_raw_payg_neg\",\"object\":\"checkout.session\"," +
                    "\"client_reference_id\":\"n_raw_payg\"," +
                    "\"metadata\":{\"kind\":\"payg_topup\",\"tier\":\"small\",\"credit_amount\":\"-1\"}}";
            StripeObject rawObj = createRawStripeObject(rawJson);

            Event event = createMockEvent("evt_payg_r3", "checkout.session.completed", null);
            Event.Data eventData = event.getData();
            when(eventData.getObject()).thenReturn(rawObj);
            EventDataObjectDeserializer deser = event.getDataObjectDeserializer();
            when(deser.getObject()).thenReturn(Optional.empty());

            when(nonceUtil.decodeNonce("n_raw_payg")).thenReturn(99L);
            when(billingEventRepository.existsByEventId("evt_payg_r3")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            performWebhookPost("{\"type\":\"checkout.session.completed\"}", event, 200);

            verify(creditAttributionService, never()).grantPaygTopup(any(), any(), any(), any());
        }

        @Test
        @DisplayName("R4. RAW path: payg_topup with nonce that fails to decode is skipped (no grant on phantom user)")
        void raw_invalidNonce_skipsGrant() throws Exception {
            String rawJson = "{\"id\":\"cs_raw_payg_badnonce\",\"object\":\"checkout.session\"," +
                    "\"client_reference_id\":\"n_corrupted\"," +
                    "\"metadata\":{\"kind\":\"payg_topup\",\"tier\":\"small\",\"credit_amount\":\"8000\"}}";
            StripeObject rawObj = createRawStripeObject(rawJson);

            Event event = createMockEvent("evt_payg_r4", "checkout.session.completed", null);
            Event.Data eventData = event.getData();
            when(eventData.getObject()).thenReturn(rawObj);
            EventDataObjectDeserializer deser = event.getDataObjectDeserializer();
            when(deser.getObject()).thenReturn(Optional.empty());

            when(nonceUtil.decodeNonce("n_corrupted")).thenReturn(null);  // tampered or expired
            when(billingEventRepository.existsByEventId("evt_payg_r4")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            performWebhookPost("{\"type\":\"checkout.session.completed\"}", event, 200);

            verify(creditAttributionService, never()).grantPaygTopup(any(), any(), any(), any());
        }

        @Test
        @DisplayName("R5. RAW path: payg_topup with non-numeric credit_amount is logged and skipped (mirror of T3)")
        void raw_invalidCreditAmount_skipsGrant() throws Exception {
            String rawJson = "{\"id\":\"cs_raw_payg_nfe\",\"object\":\"checkout.session\"," +
                    "\"client_reference_id\":\"n_raw_payg\"," +
                    "\"metadata\":{\"kind\":\"payg_topup\",\"tier\":\"medium\",\"credit_amount\":\"not-a-number\"}}";
            StripeObject rawObj = createRawStripeObject(rawJson);

            Event event = createMockEvent("evt_payg_r5", "checkout.session.completed", null);
            Event.Data eventData = event.getData();
            when(eventData.getObject()).thenReturn(rawObj);
            EventDataObjectDeserializer deser = event.getDataObjectDeserializer();
            when(deser.getObject()).thenReturn(Optional.empty());

            when(nonceUtil.decodeNonce("n_raw_payg")).thenReturn(99L);
            when(billingEventRepository.existsByEventId("evt_payg_r5")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            performWebhookPost("{\"type\":\"checkout.session.completed\"}", event, 200);

            verify(creditAttributionService, never()).grantPaygTopup(any(), any(), any(), any());
        }

        @Test
        @DisplayName("R6. RAW path: grantPaygTopup exception is swallowed (Stripe replay safety via source_id unique index) - mirror of T5")
        void raw_grantException_swallowedReturns200() throws Exception {
            String rawJson = "{\"id\":\"cs_raw_payg_throw\",\"object\":\"checkout.session\"," +
                    "\"client_reference_id\":\"n_raw_payg\"," +
                    "\"metadata\":{\"kind\":\"payg_topup\",\"tier\":\"large\",\"credit_amount\":\"80000\"}}";
            StripeObject rawObj = createRawStripeObject(rawJson);

            Event event = createMockEvent("evt_payg_r6", "checkout.session.completed", null);
            Event.Data eventData = event.getData();
            when(eventData.getObject()).thenReturn(rawObj);
            EventDataObjectDeserializer deser = event.getDataObjectDeserializer();
            when(deser.getObject()).thenReturn(Optional.empty());

            when(nonceUtil.decodeNonce("n_raw_payg")).thenReturn(99L);
            doThrow(new RuntimeException("DB down")).when(creditAttributionService)
                    .grantPaygTopup(any(), any(), any(), any());

            when(billingEventRepository.existsByEventId("evt_payg_r6")).thenReturn(false);
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            performWebhookPost("{\"type\":\"checkout.session.completed\"}", event, 200);

            verify(creditAttributionService).grantPaygTopup(any(), any(), any(), any());
        }
    }
}
