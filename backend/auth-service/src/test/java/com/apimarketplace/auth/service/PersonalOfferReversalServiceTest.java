package com.apimarketplace.auth.service;

import com.apimarketplace.auth.repository.PersonalOfferReversalRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.StripeClient;
import com.stripe.model.Charge;
import com.stripe.model.InvoicePayment;
import com.stripe.model.InvoicePaymentCollection;
import com.stripe.param.InvoicePaymentListParams;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("Personal reversal capture and retry boundaries")
class PersonalOfferReversalServiceTest {
    private final PersonalOfferReversalRepository tasks = mock(PersonalOfferReversalRepository.class);
    private final PersonalOfferPaymentService payments = mock(PersonalOfferPaymentService.class);
    private final StripeClient stripe = mock(StripeClient.class, RETURNS_DEEP_STUBS);
    private final PersonalOfferReversalService service = new PersonalOfferReversalService(tasks, payments, stripe);
    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("A full refund is persisted without any provider lookup")
    void fullRefundCapturedBeforeAnyStripeCall() throws Exception {
        service.capture("charge.refunded", json.readTree("{\"id\":\"ch_1\",\"amount\":100,\"amount_refunded\":100}"));
        verify(tasks).enqueue("ch_1", "REFUNDED");
        verifyNoInteractions(stripe, payments);
    }

    @Test
    @DisplayName("Partial refunds preserve the existing full-refund-only policy")
    void partialRefundDoesNotClawBackBonus() throws Exception {
        service.capture("charge.refunded", json.readTree("{\"id\":\"ch_1\",\"amount\":100,\"amount_refunded\":10}"));
        verifyNoInteractions(tasks, stripe, payments);
    }

    @Test
    @DisplayName("Disputes accept a charge ID or an expanded charge object before provider lookup")
    void disputeCapturesBothStripePayloadShapes() throws Exception {
        service.capture("charge.dispute.created", json.readTree("{\"charge\":\"ch_string\"}"));
        service.capture("charge.dispute.created", json.readTree("{\"charge\":{\"id\":\"ch_object\"}}"));
        verify(tasks).enqueue("ch_string", "DISPUTED");
        verify(tasks).enqueue("ch_object", "DISPUTED");
        verifyNoInteractions(stripe, payments);
    }

    @Test
    @DisplayName("A database failure during clawback leaves the task pending for another attempt")
    void failedClawbackCannotMarkTaskResolved() throws Exception {
        var task = new PersonalOfferReversalRepository.Task("ch_1", "DISPUTED");
        when(tasks.due(any(Instant.class))).thenReturn(List.of(task));
        Charge charge = new Charge();
        charge.setPaymentIntent("pi_1");
        when(stripe.charges().retrieve("ch_1")).thenReturn(charge);
        InvoicePayment payment = new InvoicePayment();
        payment.setInvoice("in_1");
        InvoicePaymentCollection page = new InvoicePaymentCollection();
        page.setData(List.of(payment));
        when(stripe.invoicePayments().list(any(InvoicePaymentListParams.class))).thenReturn(page);
        doThrow(new IllegalStateException("Wallet unavailable")).when(payments).onInvoiceReversed("in_1", "DISPUTED");

        service.reconcile();

        verify(tasks).defer(eq(task), any(Instant.class));
        verify(tasks, never()).complete(any(), any());
    }

    @Test
    @DisplayName("A charge lookup outage cannot drop a queued dispute")
    void failedChargeLookupIsRetried() throws Exception {
        var task = new PersonalOfferReversalRepository.Task("ch_1", "DISPUTED");
        when(tasks.due(any(Instant.class))).thenReturn(List.of(task));
        when(stripe.charges().retrieve("ch_1")).thenThrow(new IllegalStateException("Stripe unavailable"));
        service.reconcile();
        verify(tasks).defer(eq(task), any(Instant.class));
        verify(tasks, never()).complete(any(), any());
        verifyNoInteractions(payments);
    }
}
