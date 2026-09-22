package com.apimarketplace.agent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Whether a whole AI provider is offered at all.
 *
 * <p>Only EXCEPTIONS are stored: a provider with no row here is enabled. Disabling one hides
 * every model it serves from every picker at once, which is the only workable answer for a
 * provider the feed fills with hundreds of rows.
 *
 * <p>It does NOT touch each model's own {@code enabled} flag, so switching the provider back on
 * restores exactly the selection an admin had curated rather than turning everything on. The
 * admin catalogue keeps listing a disabled provider, or there would be no way back. See V508.
 */
@Entity
@Table(name = "model_provider_settings")
public class ModelProviderSettingsEntity {

    @Id
    @Column(name = "provider", nullable = false, length = 64)
    private String provider;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = Boolean.TRUE;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void stamp() {
        this.updatedAt = Instant.now();
        if (this.enabled == null) {
            this.enabled = Boolean.TRUE;
        }
    }

    public ModelProviderSettingsEntity() {
    }

    public ModelProviderSettingsEntity(String provider, boolean enabled) {
        this.provider = provider;
        this.enabled = enabled;
    }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
