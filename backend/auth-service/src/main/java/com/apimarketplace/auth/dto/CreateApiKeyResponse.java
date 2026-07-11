package com.apimarketplace.auth.dto;

/**
 * Response of POST /api/auth/api-keys: the key entry plus the one-time
 * plaintext. The plaintext is shown ONCE at creation and never again
 * (only the HMAC hash is stored).
 */
public class CreateApiKeyResponse extends ApiKeyEntryResponse {

    private String apiKey;          // plaintext, shown once

    public CreateApiKeyResponse() {}

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
}
