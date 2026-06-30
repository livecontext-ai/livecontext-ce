package com.apimarketplace.credential.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * V166: response shape from {@code GET /api/internal/credentials/scopes}, used by
 * {@code HttpExecutionService.preflightScopeCheck} in catalog-service.
 *
 * <p>{@code type} is the credential's auth type as stored on {@code auth.credentials.type}
 * (e.g. {@code "oauth2"}, {@code "api_key"}). {@code scopes} is the granted-scope list
 * captured at OAuth callback time - non-null only when {@code type} is {@code "oauth2"}.
 * For other types, callers treat null scopes as "scope concept does not apply, skip
 * preflight."
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CredentialScopesDto {

    private String type;
    private List<String> scopes;

    public CredentialScopesDto() {}

    public CredentialScopesDto(String type, List<String> scopes) {
        this.type = type;
        this.scopes = scopes;
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public List<String> getScopes() { return scopes; }
    public void setScopes(List<String> scopes) { this.scopes = scopes; }
}
