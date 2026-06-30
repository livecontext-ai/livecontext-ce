package com.apimarketplace.auth.service;

/**
 * Raised when an organization invitation is rejected because the inviter (or
 * the inviter's organization) has exceeded the per-window invitation cap.
 *
 * <p>Mapped to HTTP 429 by {@code OrganizationController}. S-2 in the
 * invitation security pass - prevents enumeration / spam / SPF reputation
 * damage by a single rogue OWNER/ADMIN.
 */
public class InvitationRateLimitException extends RuntimeException {
    public InvitationRateLimitException(String message) {
        super(message);
    }
}
