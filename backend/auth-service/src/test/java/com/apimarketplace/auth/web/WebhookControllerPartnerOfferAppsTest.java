package com.apimarketplace.auth.web;

import com.apimarketplace.auth.domain.BillingCustomer;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.BillingCustomerRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.service.PartnerOfferDeliveryService;
import com.apimarketplace.auth.service.StripeBillingService;
import com.stripe.model.Invoice;
import com.stripe.model.Subscription;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * V560 glue inside WebhookController: the first paid invoice of a subscription opened from a
 * partner's offer hands the offer's apps to the client. Handlers are private, so they are invoked
 * via ReflectionTestUtils like WebhookControllerPartnerCommissionTest.
 */
@DisplayName("WebhookController - partner offer apps delivered on the first paid invoice")
class WebhookControllerPartnerOfferAppsTest {

    private BillingCustomerRepository billingCustomerRepository;
    private PartnerOfferDeliveryService deliveries;
    private com.stripe.StripeClient stripeClient;
    private WebhookController controller;

    @BeforeEach
    void setUp() {
        billingCustomerRepository = mock(BillingCustomerRepository.class);
        deliveries = mock(PartnerOfferDeliveryService.class);
        stripeClient = mock(com.stripe.StripeClient.class, RETURNS_DEEP_STUBS);
        controller = new WebhookController(
                mock(com.apimarketplace.auth.repository.BillingEventRepository.class),
                mock(com.apimarketplace.auth.service.SubscriptionService.class),
                billingCustomerRepository,
                mock(SubscriptionRepository.class),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                mock(com.apimarketplace.auth.repository.PlanRepository.class),
                mock(com.apimarketplace.auth.service.PriceCacheService.class),
                mock(com.apimarketplace.auth.service.CreditAttributionService.class),
                mock(com.apimarketplace.auth.repository.PendingCreditUpgradeRepository.class),
                mock(StripeBillingService.class),
                mock(com.apimarketplace.auth.util.NonceUtil.class),
                stripeClient,
                "whsec_test");
        ReflectionTestUtils.setField(controller, "partnerOfferDeliveries", deliveries);

        User user = mock(User.class);
        lenient().when(user.getId()).thenReturn(7L);
        BillingCustomer bc = mock(BillingCustomer.class);
        lenient().when(bc.getUser()).thenReturn(user);
        lenient().when(billingCustomerRepository.findByProviderCustomerId("cus_1")).thenReturn(Optional.of(bc));
    }

    /** An invoice whose subscription metadata (as the invoice carries it) holds {@code metadata}. */
    private Invoice invoice(String status, String billingReason, Map<String, String> metadata) {
        Invoice inv = mock(Invoice.class, RETURNS_DEEP_STUBS);
        lenient().when(inv.getId()).thenReturn("in_1");
        lenient().when(inv.getStatus()).thenReturn(status);
        lenient().when(inv.getBillingReason()).thenReturn(billingReason);
        lenient().when(inv.getCustomer()).thenReturn("cus_1");
        lenient().when(inv.getParent().getSubscriptionDetails().getMetadata()).thenReturn(metadata);
        return inv;
    }

    private void handle(Invoice inv) {
        ReflectionTestUtils.invokeMethod(controller, "tryDeliverPartnerOfferApps", inv, "sub_1");
    }

    @Test
    @DisplayName("the first paid invoice of an offer's subscription delivers its apps to the paying client")
    void firstPaidInvoiceDelivers() {
        handle(invoice("paid", "subscription_create", Map.of(StripeBillingService.PARTNER_OFFER_METADATA, "Abc23XyZ9k")));

        verify(deliveries).enqueue("Abc23XyZ9k", 7L, "in_1");
        // The token came with the invoice: no call to Stripe.
        verifyNoInteractions(stripeClient);
    }

    @Test
    @DisplayName("an older payload without the subscription's metadata reads it from the subscription")
    void readsTheSubscriptionWhenTheInvoiceLacksIt() throws Exception {
        Invoice inv = invoice("paid", "subscription_create", null);
        when(inv.getParent()).thenReturn(null);
        Subscription sub = mock(Subscription.class);
        when(sub.getMetadata()).thenReturn(Map.of(StripeBillingService.PARTNER_OFFER_METADATA, "Abc23XyZ9k"));
        when(stripeClient.subscriptions().retrieve("sub_1")).thenReturn(sub);

        handle(inv);

        verify(deliveries).enqueue("Abc23XyZ9k", 7L, "in_1");
    }

    @Test
    @DisplayName("nothing for a renewal, an unpaid invoice, or a subscription not opened from an offer")
    void notAnOfferPurchase() {
        handle(invoice("paid", "subscription_cycle", Map.of(StripeBillingService.PARTNER_OFFER_METADATA, "Abc23XyZ9k")));
        handle(invoice("open", "subscription_create", Map.of(StripeBillingService.PARTNER_OFFER_METADATA, "Abc23XyZ9k")));
        handle(invoice("paid", "subscription_create", Map.of("plan_id", "3")));

        verifyNoInteractions(deliveries);
    }

    @Test
    @DisplayName("a customer unknown here delivers nothing, and nothing escapes into the webhook")
    void unknownCustomer() {
        Invoice inv = invoice("paid", "subscription_create", Map.of(StripeBillingService.PARTNER_OFFER_METADATA, "Abc23XyZ9k"));
        when(inv.getCustomer()).thenReturn("cus_unknown");

        handle(inv);

        verifyNoInteractions(deliveries);
    }

    @Test
    @DisplayName("a delivery that throws never fails the webhook (credits and commission must still go through); the reconciliation finds that payment again")
    void failureIsContained() {
        doThrow(new IllegalStateException("db down")).when(deliveries).enqueue(anyString(), anyLong(), anyString());

        handle(invoice("paid", "subscription_create", Map.of(StripeBillingService.PARTNER_OFFER_METADATA, "Abc23XyZ9k")));

        verify(deliveries).enqueue("Abc23XyZ9k", 7L, "in_1");
    }

    @Test
    @DisplayName("regression: an invoice.paid handled by the controller reaches the offer delivery (not only the helper)")
    void invoicePaidReachesTheDelivery() {
        Invoice inv = invoice("paid", "subscription_create", Map.of(StripeBillingService.PARTNER_OFFER_METADATA, "Abc23XyZ9k"));
        when(inv.getParent().getSubscriptionDetails().getSubscription()).thenReturn("sub_1");
        when(inv.getParent().getType()).thenReturn("subscription_details");

        ReflectionTestUtils.invokeMethod(controller, "handleInvoicePaid", inv);

        verify(deliveries).enqueue("Abc23XyZ9k", 7L, "in_1");
    }
}
