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

    /**
     * True when this row holds an OAuth <b>client</b> (a client_id/secret pair) rather
     * than a bare secret, which is what makes it substitutable by the user.
     *
     * <p>Callers that hide admin-disabled variants from end users use this to leave the
     * OAuth2 entry visible: disabling the row withdraws the platform's own app, and the
     * user can still register theirs and connect BYOK. Removing the entry instead would
     * take away a path that works, which the credentials wizard already implements and
     * documents for exactly this case.
     *
     * <p>Two clauses, because the variant literal alone does not cover the table. It is
     * what the catalog seeds use ({@code oauth2} is their sole OAuth2 key), but
     * {@code auth.platform_credentials} also carries rows saved without a variant, stored
     * under the {@code primary} default, and those would slip through. The second clause
     * catches exactly those, and only those.
     *
     * <p>It is deliberately NOT "any row with a client secret". The admin dialog routes a
     * field literally named {@code client_id}/{@code client_secret} into those columns
     * whatever the variant's auth type, and three {@code custom} variants (tidio, personio,
     * box) declare precisely those field names, so a bare {@code hasClientSecret} test
     * would quietly widen the carve-out to them. They are not OAuth clients: the wizard's
     * BYOK branch is gated on {@code authType === "oauth2"}, so they land on the
     * custom-fields form instead, and they must keep the ordinary hide-when-disabled
     * behaviour like every other user-supplied-secret variant.
     */
    public boolean holdsOAuthClient() {
        return "oauth2".equals(variant)
                || ("primary".equals(variant) && Boolean.TRUE.equals(hasClientSecret));
    }
}
