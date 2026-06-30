package com.apimarketplace.credential.client.dto;

/**
 * V148+ name → id lookup for platform credentials. Catalog-service stores
 * credentials by integration name (e.g. {@code "llm_openai"}); the markup
 * subsystem keys on the numeric id, so callers must translate via
 * {@code GET /api/internal/credentials/platform/by-name} before billing.
 *
 * <p>{@link #providerKind} surfaces the bridge skip hint; bridge credentials
 * use a different accounting path (see {@code project_v120_bridge_pricing_fix})
 * and the catalog billing service short-circuits on {@code "bridge"}.
 */
public class PlatformCredentialLookupDto {

    private boolean found;
    private Long id;
    private String integrationName;
    private String providerKind;

    public PlatformCredentialLookupDto() {}

    public boolean isFound() { return found; }
    public void setFound(boolean found) { this.found = found; }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getIntegrationName() { return integrationName; }
    public void setIntegrationName(String integrationName) { this.integrationName = integrationName; }

    public String getProviderKind() { return providerKind; }
    public void setProviderKind(String providerKind) { this.providerKind = providerKind; }
}
