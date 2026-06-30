package com.apimarketplace.credential.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Summary DTO for credential data returned from auth-service.
 * Used by orchestrator consumers (SendEmailNode, CatalogSearchModule, etc.)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CredentialSummaryDto {
    private Long id;
    private String name;
    private String integration;
    private String type;
    private String status;
    @JsonProperty("is_default")
    private boolean isDefault;
    @JsonProperty("credential_data")
    private Map<String, Object> credentialData;
    private List<String> scopes;
    @JsonProperty("icon_url")
    private String iconUrl;

    // Default constructor for Jackson
    public CredentialSummaryDto() {}

    // Getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getIntegration() { return integration; }
    public void setIntegration(String integration) { this.integration = integration; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public boolean isDefault() { return isDefault; }
    public void setDefault(boolean isDefault) { this.isDefault = isDefault; }

    public Map<String, Object> getCredentialData() { return credentialData; }
    public void setCredentialData(Map<String, Object> credentialData) { this.credentialData = credentialData; }

    public List<String> getScopes() { return scopes; }
    public void setScopes(List<String> scopes) { this.scopes = scopes; }

    public String getIconUrl() { return iconUrl; }
    public void setIconUrl(String iconUrl) { this.iconUrl = iconUrl; }
}
