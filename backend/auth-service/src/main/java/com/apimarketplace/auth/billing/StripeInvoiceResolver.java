package com.apimarketplace.auth.billing;

import com.stripe.StripeClient;
import com.stripe.model.Charge;
import com.stripe.param.InvoicePaymentListParams;

/** Shared by partner commissions and personal offers for this Stripe API version. */
public final class StripeInvoiceResolver {
    private StripeInvoiceResolver() { }
    public record Result(String invoiceId, boolean failed) { }

    public static Result resolve(StripeClient stripe, Charge charge) {
        String intent = charge == null ? null : charge.getPaymentIntent();
        if (intent == null || intent.isBlank()) return new Result(null, true);
        try {
            var params = InvoicePaymentListParams.builder()
                    .setPayment(InvoicePaymentListParams.Payment.builder()
                            .setType(InvoicePaymentListParams.Payment.Type.PAYMENT_INTENT)
                            .setPaymentIntent(intent).build()).setLimit(1L).build();
            var page = stripe.invoicePayments().list(params);
            if (page == null || page.getData() == null) return new Result(null, true);
            if (page.getData().isEmpty()) return new Result(null, false);
            String invoice = page.getData().getFirst().getInvoice();
            return new Result(invoice, invoice == null || invoice.isBlank());
        } catch (Exception unavailable) {
            return new Result(null, true);
        }
    }
}
