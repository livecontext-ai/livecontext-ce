package com.apimarketplace.auth.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One named API key in the user's key list (multi-key system, V398).
 * Never carries plaintext - see {@link CreateApiKeyResponse} for the
 * one-time plaintext returned on creation.
 */
public class ApiKeyEntryResponse {

    private UUID id;
    private String name;
    private String maskedApiKey;    // hint: "lc_live_...a1b2"
    /** MCP tool names this key may call; null = full access. */
    private List<String> scopes;
    private LocalDateTime createdAt;
    private LocalDateTime lastUsedAt;

    public ApiKeyEntryResponse() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getMaskedApiKey() { return maskedApiKey; }
    public void setMaskedApiKey(String maskedApiKey) { this.maskedApiKey = maskedApiKey; }

    public List<String> getScopes() { return scopes; }
    public void setScopes(List<String> scopes) { this.scopes = scopes; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(LocalDateTime lastUsedAt) { this.lastUsedAt = lastUsedAt; }
}
