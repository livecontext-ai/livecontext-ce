package com.apimarketplace.credential.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Status info for a platform credential.
 * Maps server fields: integrationName -> name, isEnabled -> enabled.
 *
 * <p>The {@code hasClientSecret} / {@code hasApiKey} / {@code hasBasicAuth} /
 * {@code hasCustomFields} flags are surfaced on {@code PlatformCredentialResponse}
 * server-side and aggregated here through {@link #isConfigured()}. A disabled
 * row with {@code isConfigured()==false} is a placeholder synthesized by
 * {@code PlatformCredentialRepository.setEnabledForVariant} when the admin
 * toggled a variant off before ever saving secrets - it must NOT be treated as
 * an admin opt-out of the integration, because the catalog's default state for
 * "no row" is already "enabled". See {@code CredentialTemplateController
 * .fetchDisabledVariantKeys} for the gate that consumes this flag.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PlatformCredentialStatusDto {

    private Long id;
    private String name;
    private Boolean enabled;
    private String variant;
    private String createdAt;
    private String updatedAt;
    private Boolean hasClientSecret;
    private Boolean hasApiKey;
    private Boolean hasBasicAuth;
    private Boolean hasCustomFields;

    public PlatformCredentialStatusDto() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    @JsonProperty("integrationName")
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    @JsonProperty("isEnabled")
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public String getVariant() { return variant; }
    public void setVariant(String variant) { this.variant = variant; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }

    /**
     * Server-side semantics: this flag is "OAuth2 pair complete" (both {@code client_id}
     * AND {@code client_secret} non-blank), NOT merely "secret present". Mirror of
     * {@code PlatformCredential.hasOAuth2Credentials()}. A half-saved OAuth2 row with
     * only {@code client_id} reports {@code false} here - intentional, because such a
     * row cannot drive an OAuth2 flow and should not count as "configured".
     */
    public Boolean getHasClientSecret() { return hasClientSecret; }
    public void setHasClientSecret(Boolean hasClientSecret) { this.hasClientSecret = hasClientSecret; }

    public Boolean getHasApiKey() { return hasApiKey; }
    public void setHasApiKey(Boolean hasApiKey) { this.hasApiKey = hasApiKey; }

    public Boolean getHasBasicAuth() { return hasBasicAuth; }
    public void setHasBasicAuth(Boolean hasBasicAuth) { this.hasBasicAuth = hasBasicAuth; }

    public Boolean getHasCustomFields() { return hasCustomFields; }
    public void setHasCustomFields(Boolean hasCustomFields) { this.hasCustomFields = hasCustomFields; }

    /**
     * True when the admin has actually saved a secret on this row (any auth
     * method). False when the row is a placeholder from a per-variant toggle
     * click on an integration that was never configured.
     *
     * <p>Null safety: a server that pre-dates this DTO change won't send the
     * {@code hasX} fields → all four are null → returns {@code false} (treats
     * the row as unconfigured). Old behavior is fail-open at the caller via the
     * filter contract; defaulting to {@code false} here matches the post-fix
     * intent.
     */
    public boolean isConfigured() {
        return Boolean.TRUE.equals(hasClientSecret)
                || Boolean.TRUE.equals(hasApiKey)
                || Boolean.TRUE.equals(hasBasicAuth)
                || Boolean.TRUE.equals(hasCustomFields);
    }
}
