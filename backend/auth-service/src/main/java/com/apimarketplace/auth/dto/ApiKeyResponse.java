package com.apimarketplace.auth.dto;

import java.time.LocalDateTime;

/**
 * Response DTO for API key management endpoints.
 * Quota fields were removed - credit balance is exposed via /api/credits/balance.
 */
public class ApiKeyResponse {

    private String apiKey;          // null on GET, plaintext on POST (shown once)
    private String maskedApiKey;    // hint: "lc_live_...a1b2"
    private LocalDateTime createdAt;
    private boolean active;

    public ApiKeyResponse() {}

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getMaskedApiKey() { return maskedApiKey; }
    public void setMaskedApiKey(String maskedApiKey) { this.maskedApiKey = maskedApiKey; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
