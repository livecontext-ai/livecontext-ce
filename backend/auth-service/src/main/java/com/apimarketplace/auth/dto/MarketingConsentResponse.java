package com.apimarketplace.auth.dto;

import java.time.Instant;

/** Response of {@code GET /api/users/profile/marketing-consent}. */
public record MarketingConsentResponse(boolean consent, Instant updatedAt) {
}
