package com.apimarketplace.auth.dto;

import java.util.List;

/**
 * Request body for POST /api/auth/api-keys (multi-key system, V398).
 *
 * <p>{@code name} is required (trimmed, max 100 chars). {@code scopes} is the
 * list of MCP tool names the key may call: {@code null} = full access; an
 * empty list is rejected (a key must grant at least one tool or full access).</p>
 */
public class CreateApiKeyRequest {

    private String name;
    private List<String> scopes;

    public CreateApiKeyRequest() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public List<String> getScopes() { return scopes; }
    public void setScopes(List<String> scopes) { this.scopes = scopes; }
}
