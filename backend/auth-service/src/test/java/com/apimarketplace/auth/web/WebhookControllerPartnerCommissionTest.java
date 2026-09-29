package com.apimarketplace.auth.web;

import com.apimarketplace.auth.domain.BillingCustomer;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.BillingCustomerRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.service.PartnerCommissionService;
import com.stripe.model.Charge;
import com.stripe.model.Invoice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * V549 partner revenue-share glue inside WebhookController: which paid invoices reach the
 * commission service, on which base amount, and that a refund voids the line. Handlers are
 * private, so they are invoked via ReflectionTestUtils like WebhookControllerReferralTest.
 */
@DisplayName("WebhookController - partner commission recording + voiding")
class WebhookControllerPartnerCommissionTest {

    private BillingCustomerRepository billingCustomerRepository;
    private PartnerCommissionService commissionService;
    private com.stripe.StripeClient stripeClient;
    private WebhookController controller;

    @BeforeEach
    void setUp() {
        billingCustomerRepository = mock(BillingCustomerRepository.class);
        commissionService = mock(PartnerCommissionService.class);
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
                mock(com.apimarketplace.auth.service.StripeBillingService.class),
                mock(com.apimarketplace.auth.util.NonceUtil.class),
                stripeClient,
                "whsec_test");
        ReflectionTestUtils.setField(controller, "partnerCommissionService", commissionService);

        User user = mock(User.class);
        lenient().when(user.getId()).thenReturn(7L);
        BillingCustomer bc = mock(BillingCustomer.class);
        lenient().when(bc.getUser()).thenReturn(user);
        lenient().when(billingCustomerRepository.findByProviderCustomerId("cus_1")).thenReturn(Optional.of(bc));
    }

    private Invoice invoice(String status, Long amountPaid, Long totalExclTax) {
        Invoice inv = mock(Invoice.class);
        lenient().when(inv.getId()).thenReturn("in_1");
        lenient().when(inv.getStatus()).thenReturn(status);
        lenient().when(inv.getAmountPaid()).thenReturn(amountPaid);
        lenient().when(inv.getTotalExcludingTax()).thenReturn(totalExclTax);
        lenient().when(inv.getTotal()).thenReturn(amountPaid);
        lenient().when(inv.getCurrency()).thenReturn("eur");
        lenient().when(inv.getCustomer()).thenReturn("cus_1");
        return inv;
    }

    private void record(Invoice inv) {
        ReflectionTestUtils.invokeMethod(controller, "tryRecordPartnerCommission", inv);
    }

    @Test
    @DisplayName("a paid invoice reaches the commission service with the amount EXCLUDING tax")
    void paidInvoiceRecordsOnAmountExcludingTax() {
        record(invoice("paid", 2880L, 2400L));

        verify(commissionService).recordPaidInvoice(eq(7L), eq("in_1"), eq(2400L), eq("eur"), any(Instant.class));
    }

    @Test
    @DisplayName("a customer balance paying half the invoice halves the base (never the full pre-tax total)")
    void balancePaidInvoiceScalesTheBase() {
        Invoice inv = invoice("paid", 1440L, 2400L);
        when(inv.getTotal()).thenReturn(2880L); // 2400 + 20% tax, 1440 paid by card

        record(inv);

        verify(commissionService).recordPaidInvoice(eq(7L), eq("in_1"), eq(1200L), eq("eur"), any(Instant.class));
    }

    @Test
    @DisplayName("without a tax breakdown the amount paid is the base")
    void noTaxBreakdownFallsBackToAmountPaid() {
        record(invoice("paid", 2400L, null));

        verify(commissionService).recordPaidInvoice(eq(7L), eq("in_1"), eq(2400L), eq("eur"), any(Instant.class));
    }

    @Test
    @DisplayName("an unpaid or zero-amount invoice records nothing")
    void unpaidOrZeroRecordsNothing() {
        record(invoice("open", 2400L, 2400L));
        record(invoice("paid", 0L, 0L));

        verifyNoInteractions(commissionService);
    }

    @Test
    @DisplayName("an invoice whose Stripe customer is unknown locally records nothing")
    void unknownCustomerRecordsNothing() {
        Invoice inv = invoice("paid", 2400L, 2400L);
        when(inv.getCustomer()).thenReturn("cus_unknown");

        record(inv);

        verifyNoInteractions(commissionService);
    }

    @Test
    @DisplayName("a commission failure never breaks the webhook (credits must still be processed)")
    void commissionFailureIsSwallowed() {
        when(commissionService.recordPaidInvoice(anyLong(), anyString(), anyLong(), anyString(), any()))
                .thenThrow(new RuntimeException("db down"));

        record(invoice("paid", 2400L, 2400L)); // must not throw
    }

    @Test
    @DisplayName("a full refund voids the customer's commission line for that charged amount")
    void fullRefundVoidsCommission() {
        Charge charge = mock(Charge.class);
        when(charge.getId()).thenReturn("ch_1");
        when(charge.getAmount()).thenReturn(2880L);
        when(charge.getAmountRefunded()).thenReturn(2880L);
        when(charge.getCustomer()).thenReturn("cus_1");
        when(charge.getCurrency()).thenReturn("eur");

        ReflectionTestUtils.invokeMethod(controller, "handleChargeRefunded", charge);

        verify(commissionService).voidForRefund(7L, null, true, 2880L, "eur", "REFUNDED");
    }

    @Test
    @DisplayName("a partial refund voids nothing")
    void partialRefundVoidsNothing() {
        Charge charge = mock(Charge.class);
        when(charge.getId()).thenReturn("ch_1");
        when(charge.getAmount()).thenReturn(2880L);
        when(charge.getAmountRefunded()).thenReturn(1000L);

        ReflectionTestUtils.invokeMethod(controller, "handleChargeRefunded", charge);

        verifyNoInteractions(commissionService);
    }

    @Test
    @DisplayName("dispatch: invoice.paid reaches the commission service through the real handler")
    void invoicePaidHandlerRecordsCommission() {
        Invoice inv = invoice("paid", 2400L, 2400L);

        ReflectionTestUtils.invokeMethod(controller, "handleInvoicePaid", inv);

        verify(commissionService).recordPaidInvoice(eq(7L), eq("in_1"), eq(2400L), eq("eur"), any(Instant.class));
    }

    @Test
    @DisplayName("dispatch: a credit-pack upgrade invoice (early return for credits) still pays the partner")
    void creditUpgradeInvoiceStillRecordsCommission() {
        Invoice inv = invoice("paid", 1000L, 1000L);
        when(inv.getMetadata()).thenReturn(java.util.Map.of("kind", "credit_upgrade"));

        try {
            ReflectionTestUtils.invokeMethod(controller, "handleInvoicePaid", inv);
        } catch (RuntimeException ignored) {
            // The upgrade grant itself is not wired in this test; only the order matters.
        }

        verify(commissionService).recordPaidInvoice(eq(7L), eq("in_1"), eq(1000L), eq("eur"), any(Instant.class));
    }

    @Test
    @DisplayName("dispatch RAW: an invoice.paid delivered untyped is re-read from Stripe and recorded")
    void rawInvoicePaidRecordsCommission() throws Exception {
        Invoice inv = invoice("paid", 2400L, 2400L);
        when(stripeClient.invoices().retrieve("in_1")).thenReturn(inv);
        com.stripe.model.Event event = mock(com.stripe.model.Event.class, RETURNS_DEEP_STUBS);
        when(event.getData().getObject().toJson()).thenReturn("{\"object\":\"invoice\",\"id\":\"in_1\"}");

        ReflectionTestUtils.invokeMethod(controller, "handleInvoicePaidRaw", event);

        verify(commissionService).recordPaidInvoice(eq(7L), eq("in_1"), eq(2400L), eq("eur"), any(Instant.class));
    }

    @Test
    @DisplayName("a dispute voids the commission of the disputed charge (resolved through Stripe)")
    void disputeVoidsCommission() throws Exception {
        Charge charge = mock(Charge.class);
        when(charge.getCustomer()).thenReturn("cus_1");
        when(charge.getAmount()).thenReturn(2880L);
        when(stripeClient.charges().retrieve("ch_1")).thenReturn(charge);
        com.stripe.model.Dispute dispute = mock(com.stripe.model.Dispute.class);
        when(dispute.getCharge()).thenReturn("ch_1");

        ReflectionTestUtils.invokeMethod(controller, "handleDisputeCreated", dispute);

        verify(commissionService).voidForRefund(7L, null, true, 2880L, null, "DISPUTED");
    }

    @Test
    @DisplayName("RAW refund: a charge.refunded delivered untyped is re-read and voids the commission")
    void rawRefundVoidsCommission() throws Exception {
        Charge charge = mock(Charge.class);
        when(charge.getId()).thenReturn("ch_1");
        when(charge.getAmount()).thenReturn(2880L);
        when(charge.getAmountRefunded()).thenReturn(2880L);
        when(charge.getCustomer()).thenReturn("cus_1");
        when(stripeClient.charges().retrieve("ch_1")).thenReturn(charge);
        com.stripe.model.Event event = mock(com.stripe.model.Event.class, RETURNS_DEEP_STUBS);
        when(event.getData().getObject().toJson()).thenReturn("{\"object\":\"charge\",\"id\":\"ch_1\"}");

        ReflectionTestUtils.invokeMethod(controller, "handleChargeRefundedRaw", event);

        verify(commissionService).voidForRefund(7L, null, true, 2880L, null, "REFUNDED");
    }

    @Test
    @DisplayName("a concurrent duplicate delivery (unique invoice constraint) is swallowed, never an error")
    void concurrentDuplicateIsNotAnError() {
        when(commissionService.recordPaidInvoice(anyLong(), anyString(), anyLong(), anyString(), any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uq_partner_commission_invoice"));

        record(invoice("paid", 2400L, 2400L)); // must not throw
    }

    @Test
    @DisplayName("refund: the invoice is resolved from the charge's payment intent, so the exact line is voided")
    void refundResolvesInvoiceFromPaymentIntent() throws Exception {
        Charge charge = mock(Charge.class);
        when(charge.getId()).thenReturn("ch_1");
        when(charge.getAmount()).thenReturn(2880L);
        when(charge.getAmountRefunded()).thenReturn(2880L);
        when(charge.getCustomer()).thenReturn("cus_1");
        when(charge.getCurrency()).thenReturn("eur");
        when(charge.getPaymentIntent()).thenReturn("pi_1");
        com.stripe.model.InvoicePayment payment = mock(com.stripe.model.InvoicePayment.class);
        when(payment.getInvoice()).thenReturn("in_42");
        @SuppressWarnings("unchecked")
        com.stripe.model.StripeCollection<com.stripe.model.InvoicePayment> page = mock(com.stripe.model.StripeCollection.class);
        when(page.getData()).thenReturn(java.util.List.of(payment));
        when(stripeClient.invoicePayments().list(any(com.stripe.param.InvoicePaymentListParams.class))).thenReturn(page);

        ReflectionTestUtils.invokeMethod(controller, "handleChargeRefunded", charge);

        verify(commissionService).voidForRefund(7L, "in_42", false, 2880L, "eur", "REFUNDED");
    }

    @Test
    @DisplayName("refund: when Stripe cannot resolve the invoice, the service falls back to its unambiguous amount match")
    void refundFallsBackWhenInvoiceUnresolvable() throws Exception {
        Charge charge = mock(Charge.class);
        when(charge.getId()).thenReturn("ch_1");
        when(charge.getAmount()).thenReturn(2880L);
        when(charge.getAmountRefunded()).thenReturn(2880L);
        when(charge.getCustomer()).thenReturn("cus_1");
        when(charge.getPaymentIntent()).thenReturn("pi_1");
        when(stripeClient.invoicePayments().list(any(com.stripe.param.InvoicePaymentListParams.class)))
                .thenThrow(new com.stripe.exception.ApiConnectionException("down"));

        ReflectionTestUtils.invokeMethod(controller, "handleChargeRefunded", charge);

        verify(commissionService).voidForRefund(7L, null, true, 2880L, null, "REFUNDED");
    }

    @Test
    @DisplayName("refund: Stripe answers 'no invoice for this payment' -> confirmed none, the service voids nothing")
    void refundOfPaymentWithoutInvoiceIsConfirmedNone() throws Exception {
        Charge charge = mock(Charge.class);
        when(charge.getId()).thenReturn("ch_1");
        when(charge.getAmount()).thenReturn(2880L);
        when(charge.getAmountRefunded()).thenReturn(2880L);
        when(charge.getCustomer()).thenReturn("cus_1");
        when(charge.getPaymentIntent()).thenReturn("pi_topup");
        @SuppressWarnings("unchecked")
        com.stripe.model.StripeCollection<com.stripe.model.InvoicePayment> empty = mock(com.stripe.model.StripeCollection.class);
        when(empty.getData()).thenReturn(java.util.List.of());
        when(stripeClient.invoicePayments().list(any(com.stripe.param.InvoicePaymentListParams.class))).thenReturn(empty);

        ReflectionTestUtils.invokeMethod(controller, "handleChargeRefunded", charge);

        verify(commissionService).voidForRefund(7L, null, false, 2880L, null, "REFUNDED");
    }

    @Test
    @DisplayName("a failing referral clawback never prevents the partner commission void")
    void clawbackFailureDoesNotSkipPartnerVoid() {
        com.apimarketplace.auth.service.RewardService rewards = mock(com.apimarketplace.auth.service.RewardService.class);
        doThrow(new RuntimeException("clawback down")).when(rewards).clawbackByRedeemerUserId(anyLong(), anyString());
        ReflectionTestUtils.setField(controller, "rewardService", rewards);
        Charge charge = mock(Charge.class);
        when(charge.getId()).thenReturn("ch_1");
        when(charge.getAmount()).thenReturn(2880L);
        when(charge.getAmountRefunded()).thenReturn(2880L);
        when(charge.getCustomer()).thenReturn("cus_1");

        ReflectionTestUtils.invokeMethod(controller, "handleChargeRefunded", charge);

        verify(commissionService).voidForRefund(eq(7L), any(), anyBoolean(), eq(2880L), any(), eq("REFUNDED"));
    }

    @Test
    @DisplayName("dispute: a failing referral clawback never prevents the partner commission void either")
    void disputeClawbackFailureDoesNotSkipPartnerVoid() throws Exception {
        com.apimarketplace.auth.service.RewardService rewards = mock(com.apimarketplace.auth.service.RewardService.class);
        doThrow(new RuntimeException("clawback down")).when(rewards).clawbackByRedeemerUserId(anyLong(), anyString());
        ReflectionTestUtils.setField(controller, "rewardService", rewards);
        Charge charge = mock(Charge.class);
        when(charge.getCustomer()).thenReturn("cus_1");
        when(charge.getAmount()).thenReturn(2880L);
        when(stripeClient.charges().retrieve("ch_1")).thenReturn(charge);
        com.stripe.model.Dispute dispute = mock(com.stripe.model.Dispute.class);
        when(dispute.getCharge()).thenReturn("ch_1");

        ReflectionTestUtils.invokeMethod(controller, "handleDisputeCreated", dispute);

        verify(commissionService).voidForRefund(eq(7L), any(), anyBoolean(), eq(2880L), any(), eq("DISPUTED"));
    }
}
