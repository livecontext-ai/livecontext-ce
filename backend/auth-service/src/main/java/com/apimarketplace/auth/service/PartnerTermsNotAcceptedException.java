package com.apimarketplace.auth.service;

/**
 * A payout was attempted for a partner who never accepted the Partner Program Terms (V557).
 * Thrown before any line is marked paid, so nothing is settled; the admin endpoint answers it
 * 409 {@code terms_not_accepted}, and the partner accepts the terms from their dashboard.
 */
public class PartnerTermsNotAcceptedException extends RuntimeException {
    public PartnerTermsNotAcceptedException() {
        super("terms_not_accepted");
    }
}
