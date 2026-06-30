package com.apimarketplace.credential.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * Request to create/update a platform credential.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SavePlatformCredentialRequest {

    private String credentialName;
    private String credentialType;
    private Map<String, Object> credentialData;

    public SavePlatformCredentialRequest() {}

    public SavePlatformCredentialRequest(String credentialName, String credentialType, Map<String, Object> credentialData) {
        this.credentialName = credentialName;
        this.credentialType = credentialType;
        this.credentialData = credentialData;
    }

    public String getCredentialName() { return credentialName; }
    public void setCredentialName(String credentialName) { this.credentialName = credentialName; }

    public String getCredentialType() { return credentialType; }
    public void setCredentialType(String credentialType) { this.credentialType = credentialType; }

    public Map<String, Object> getCredentialData() { return credentialData; }
    public void setCredentialData(Map<String, Object> credentialData) { this.credentialData = credentialData; }
}
