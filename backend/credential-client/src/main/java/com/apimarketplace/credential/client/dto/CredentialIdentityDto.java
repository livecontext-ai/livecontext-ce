package com.apimarketplace.credential.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * A credential's IDENTITY: enough to decide which one a caller meant, and
 * nothing else.
 *
 * <p>This exists because the only other way to list an account's credentials
 * internally is {@code /api/internal/credentials/all}, which answers with whole
 * {@code Credential} records, decrypted secrets included. Pulling those in order
 * to pick an id would be the shape the credential code explicitly refuses
 * elsewhere: "asking for a secret in order to decide not to use it is the one
 * shape this check must not have"
 * ({@code HttpExecutionService.resolvePinnedCredentialOwnership}).
 *
 * <p>So: id, name, integration, status, type, granted {@code scopes} and whether
 * it is the integration's default. No {@code credentialData}, ever. If a future
 * caller needs the secret it must ask for the credential by id, through the paths
 * that already exist for that.
 *
 * <p>The scopes are here because "which of these accounts can run THIS endpoint"
 * is exactly the deciding question, and answering it from {@code /all} would mean
 * pulling the decrypted secret of every account in order to reject most of them.
 * A granted scope is a permission label the user consented to on the provider's
 * own screen and already sees in their credentials list; it unlocks nothing by
 * itself.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CredentialIdentityDto {

    private Long id;
    private String name;
    private String integration;
    private String status;
    private String type;
    private List<String> scopes;
    @JsonProperty("is_default")
    private boolean isDefault;

    public CredentialIdentityDto() {
    }

    /**
     * Identity without the scope axis. Kept so the call sites that only match a
     * credential by name or id (the run-time selection resolver) keep compiling
     * and keep meaning the same thing.
     */
    public CredentialIdentityDto(Long id, String name, String integration, String status) {
        this(id, name, integration, status, null, null, false);
    }

    public CredentialIdentityDto(Long id, String name, String integration, String status,
                                 String type, List<String> scopes, boolean isDefault) {
        this.id = id;
        this.name = name;
        this.integration = integration;
        this.status = status;
        this.type = type;
        this.scopes = scopes;
        this.isDefault = isDefault;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getIntegration() {
        return integration;
    }

    public void setIntegration(String integration) {
        this.integration = integration;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public List<String> getScopes() {
        return scopes;
    }

    public void setScopes(List<String> scopes) {
        this.scopes = scopes;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public void setDefault(boolean isDefault) {
        this.isDefault = isDefault;
    }
}
