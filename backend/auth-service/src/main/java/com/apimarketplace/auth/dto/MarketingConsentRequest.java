package com.apimarketplace.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Body of {@code PUT /api/users/profile/marketing-consent}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MarketingConsentRequest(Boolean consent) {
}
