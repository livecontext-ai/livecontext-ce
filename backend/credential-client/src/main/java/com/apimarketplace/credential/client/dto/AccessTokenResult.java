package com.apimarketplace.credential.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Result of an access token lookup.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AccessTokenResult {

    private String accessToken;
    private boolean found;
    // V103: auth type of the resolved credential (oauth2, api_key, bearer_token, ...).
    // Used as the variant filter when selecting tool_credentials injection metadata
    // for APIs that expose multiple auth variants. Null for platform-sourced tokens
    // where variant selection is driven by admin configuration, not the credential.
    private String type;

    public AccessTokenResult() {}

    public AccessTokenResult(String accessToken, boolean found) {
        this.accessToken = accessToken;
        this.found = found;
    }

    public AccessTokenResult(String accessToken, boolean found, String type) {
        this.accessToken = accessToken;
        this.found = found;
        this.type = type;
    }

    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }

    public boolean isFound() { return found; }
    public void setFound(boolean found) { this.found = found; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
}
