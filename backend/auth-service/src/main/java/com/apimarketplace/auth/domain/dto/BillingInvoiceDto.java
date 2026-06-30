package com.apimarketplace.auth.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Customer-facing invoice record returned by {@code GET /api/billing/invoices}.
 *
 * <p>Mirrors the Stripe Invoice fields we surface in {@code /settings/billing}.
 * {@code Instant} (not {@code LocalDateTime}) is used so Jackson serializes
 * with a trailing {@code Z} (UTC marker) - Stripe timestamps are Unix epoch
 * seconds and have no timezone ambiguity.
 *
 * <p>Both {@code amountPaid} and {@code amountDue} are carried so the
 * frontend can render the right number per status: {@code paid} →
 * {@code amountPaid}; {@code open}, {@code uncollectible}, {@code draft} →
 * {@code amountDue}; {@code void} → render as a dash.
 *
 * <p>{@code hostedInvoiceUrl} and {@code invoicePdf} are signed Stripe URLs
 * that can be {@code null} for non-finalized invoices (drafts) - the
 * frontend hides the corresponding action when null.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BillingInvoiceDto {

    private String id;
    private String number;
    private Long amountPaid;
    private Long amountDue;
    private String currency;
    private String status;
    private Instant created;
    private Instant periodStart;
    private Instant periodEnd;
    private String hostedInvoiceUrl;
    private String invoicePdf;
}
