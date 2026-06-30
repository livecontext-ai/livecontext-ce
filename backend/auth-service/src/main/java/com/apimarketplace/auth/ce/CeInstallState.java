package com.apimarketplace.auth.ce;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Singleton install-state row (id = 1) tracking whether a CE (Community Edition)
 * deployment has completed its first-run wizard. Written by
 * {@link CeInstallController#complete} when the admin clicks Finish on
 * {@code /ce-setup}; read by the frontend {@code CeFirstLoginGuard} to decide
 * whether to redirect to the wizard.
 *
 * <p>The table has a {@code CHECK (id = 1)} constraint (V121 migration) so the
 * "there can be only one" invariant is enforced at the DB level - the entity does
 * not attempt to guard it at the ORM level.
 *
 * <p>Cloud deploys ignore this row entirely (the frontend guard routes to
 * {@code /onboarding} via a different code path when {@code auth.mode != embedded}).
 */
@Entity
@Table(name = "ce_install_state", schema = "auth")
public class CeInstallState {

    /** Singleton id - always 1. */
    public static final short SINGLETON_ID = 1;

    /** Current schema version - bumped when the singleton's shape changes incompatibly. */
    public static final String CURRENT_VERSION = "v1";

    @Id
    @Column(name = "id")
    private Short id;

    @Column(name = "bootstrapped", nullable = false)
    private boolean bootstrapped;

    @Column(name = "bootstrapped_at")
    private Instant bootstrappedAt;

    @Column(name = "bootstrap_admin_id")
    private Long bootstrapAdminId;

    @Column(name = "version", nullable = false, length = 32)
    private String version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "registration_open", nullable = false)
    private boolean registrationOpen = true;

    public CeInstallState() {}

    public Short getId() { return id; }
    public void setId(Short id) { this.id = id; }

    public boolean isBootstrapped() { return bootstrapped; }
    public void setBootstrapped(boolean bootstrapped) { this.bootstrapped = bootstrapped; }

    public Instant getBootstrappedAt() { return bootstrappedAt; }
    public void setBootstrappedAt(Instant bootstrappedAt) { this.bootstrappedAt = bootstrappedAt; }

    public Long getBootstrapAdminId() { return bootstrapAdminId; }
    public void setBootstrapAdminId(Long bootstrapAdminId) { this.bootstrapAdminId = bootstrapAdminId; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public boolean isRegistrationOpen() { return registrationOpen; }
    public void setRegistrationOpen(boolean registrationOpen) { this.registrationOpen = registrationOpen; }
}
