package com.apimarketplace.auth.web;

import com.apimarketplace.auth.repository.*;
import com.apimarketplace.auth.service.*;
import com.apimarketplace.auth.util.NonceUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.StripeClient;
import com.stripe.model.Event;
import com.stripe.net.Webhook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("Personal reversal intent is durable before Stripe webhook acknowledgement")
class PersonalOfferWebhookTest {
    private final BillingEventRepository events = mock(BillingEventRepository.class);
    private final PersonalOfferReversalService reversals = mock(PersonalOfferReversalService.class);
    private WebhookController controller;
    private MockHttpServletRequest request;
    private static final String PAYLOAD = "{\"data\":{\"object\":{\"id\":\"ch_refund\",\"amount\":100,\"amount_refunded\":100}}}";

    @BeforeEach
    void setUp() {
        controller = new WebhookController(events, mock(SubscriptionService.class), mock(BillingCustomerRepository.class),
                mock(SubscriptionRepository.class), new ObjectMapper(), mock(PlanRepository.class), mock(PriceCacheService.class),
                mock(CreditAttributionService.class), mock(PendingCreditUpgradeRepository.class), mock(StripeBillingService.class),
                mock(NonceUtil.class), mock(StripeClient.class), "test-webhook-secret");
        ReflectionTestUtils.setField(controller, "personalOfferReversals", reversals);
        request = new MockHttpServletRequest();
        request.setContent(PAYLOAD.getBytes(StandardCharsets.UTF_8));
        request.addHeader("Stripe-Signature", "test-signature");
    }

    @Test
    @DisplayName("A replay repairs reversal capture even if the Stripe event was already recorded")
    void alreadyRecordedWebhookStillCapturesReversal() {
        Event event = mock(Event.class);
        when(event.getType()).thenReturn("charge.refunded");
        when(event.getId()).thenReturn("evt_refund");
        when(events.existsByEventId("evt_refund")).thenReturn(true);
        try (var verifier = mockStatic(Webhook.class)) {
            verifier.when(() -> Webhook.constructEvent(PAYLOAD, "test-signature", "test-webhook-secret")).thenReturn(event);
            assertThat(controller.handleStripeWebhook(request).getStatusCode().value()).isEqualTo(200);
        }
        var order = inOrder(reversals, events);
        order.verify(reversals).capture(eq("charge.refunded"), argThat(node -> "ch_refund".equals(node.path("id").asText())));
        order.verify(events).existsByEventId("evt_refund");
    }

    @Test
    @DisplayName("A failed reversal write returns 503 before event deduplication so Stripe can retry")
    void captureFailureRemainsRetryable() {
        Event event = mock(Event.class);
        when(event.getType()).thenReturn("charge.refunded");
        doThrow(new IllegalStateException("Database unavailable")).when(reversals).capture(anyString(), any());
        try (var verifier = mockStatic(Webhook.class)) {
            verifier.when(() -> Webhook.constructEvent(PAYLOAD, "test-signature", "test-webhook-secret")).thenReturn(event);
            assertThat(controller.handleStripeWebhook(request).getStatusCode().value()).isEqualTo(503);
        }
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("Unverified webhook input cannot create a reversal task")
    void missingSignatureCannotCaptureReversal() {
        request.removeHeader("Stripe-Signature");
        assertThat(controller.handleStripeWebhook(request).getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(events, reversals);
    }
}
