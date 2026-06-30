package com.apimarketplace.agent.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Signed skill bundle (cloud -> CE distribution).
 *
 * <p>An immutable snapshot of every admin-managed GLOBAL skill ({@code is_global=true})
 * on the cloud at a point in time, signed with Ed25519 so CE installs can verify
 * authenticity offline. Exactly one row may have {@code isActive=true} at any time
 * (partial unique index {@code idx_skill_bundles_one_active}).
 *
 * <p>The canonical signed payload is NOT stored here - it is re-derived from the live
 * {@code skills} table at read time (mirrors {@link CatalogBundleEntity}); the
 * {@code checksum} pins the original payload, so any edit to the live global-skill set
 * after a build makes the serve path refuse to hand out a stale bundle until the
 * operator rebuilds.
 */
@Entity
@Table(name = "skill_bundles")
public class SkillBundleEntity {

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

    @Column(name = "skill_count", nullable = false)
    private Integer skillCount;

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

    public Integer getSkillCount() { return skillCount; }
    public void setSkillCount(Integer skillCount) { this.skillCount = skillCount; }

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
}
