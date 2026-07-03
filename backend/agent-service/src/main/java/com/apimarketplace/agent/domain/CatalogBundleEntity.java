package com.apimarketplace.agent.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Signed catalog bundle (cloud → CE distribution).
 *
 * <p>A bundle is an immutable snapshot of {@code model_config_overrides} at a
 * point in time, signed with Ed25519 so CE instances can verify authenticity
 * offline. Exactly one row may have {@code isActive = true} at any time
 * (enforced by partial unique index {@code idx_catalog_bundles_one_active}).
 *
 * <p>The canonical signed payload IS stored on the row ({@code payload},
 * V381): serving reads the exact bytes that were signed, so a bundle stays
 * servable forever - historical versions included - regardless of later edits
 * to the live {@code model_config_overrides} table. Rows built before V381
 * have a NULL payload (legacy); the serve path returns a clean "republishing"
 * error for them and the auto-rebuild scheduler replaces them on its next
 * tick. The {@code checksum} still pins the payload as a tamper check.
 *
 * <p>Use {@code CatalogBundleService.buildBundle()} to create a new bundle
 * (inserts with {@code isActive=false}); {@code activateBundle(id)} flips the
 * active flag atomically.
 */
@Entity
@Table(name = "catalog_bundles")
public class CatalogBundleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "version", nullable = false, unique = true)
    private Long version;

    @Column(name = "schema_version", nullable = false)
    private Integer schemaVersion = 1;

    /** SHA-256 hex of canonical payload (64 chars). */
    @Column(name = "checksum", nullable = false, length = 64)
    private String checksum;

    /** Base64-encoded Ed25519 signature of the canonical payload bytes. */
    @Column(name = "signature", nullable = false, columnDefinition = "text")
    private String signature;

    @Column(name = "signing_key_id", nullable = false, length = 50)
    private String signingKeyId;

    @Column(name = "issuer", nullable = false, length = 100)
    private String issuer;

    @Column(name = "model_count", nullable = false)
    private Integer modelCount;

    @Column(name = "raw_bytes_size", nullable = false)
    private Integer rawBytesSize;

    @Column(name = "source_url", columnDefinition = "text")
    private String sourceUrl;

    @Column(name = "imported_at", nullable = false)
    private Instant importedAt;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "is_active", nullable = false)
    private boolean active = false;

    /**
     * Canonical signed payload (UTF-8 JSON), persisted at build time (V381).
     * NULL only on legacy rows built before payload persistence.
     */
    @Column(name = "payload", columnDefinition = "text")
    private String payload;

    @PrePersist
    protected void onCreate() {
        if (importedAt == null) importedAt = Instant.now();
        if (schemaVersion == null) schemaVersion = 1;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public Integer getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(Integer schemaVersion) { this.schemaVersion = schemaVersion; }

    public String getChecksum() { return checksum; }
    public void setChecksum(String checksum) { this.checksum = checksum; }

    public String getSignature() { return signature; }
    public void setSignature(String signature) { this.signature = signature; }

    public String getSigningKeyId() { return signingKeyId; }
    public void setSigningKeyId(String signingKeyId) { this.signingKeyId = signingKeyId; }

    public String getIssuer() { return issuer; }
    public void setIssuer(String issuer) { this.issuer = issuer; }

    public Integer getModelCount() { return modelCount; }
    public void setModelCount(Integer modelCount) { this.modelCount = modelCount; }

    public Integer getRawBytesSize() { return rawBytesSize; }
    public void setRawBytesSize(Integer rawBytesSize) { this.rawBytesSize = rawBytesSize; }

    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }

    public Instant getImportedAt() { return importedAt; }
    public void setImportedAt(Instant importedAt) { this.importedAt = importedAt; }

    public Instant getActivatedAt() { return activatedAt; }
    public void setActivatedAt(Instant activatedAt) { this.activatedAt = activatedAt; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
}
