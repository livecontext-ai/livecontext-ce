package com.apimarketplace.publication.screening;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only audit row for one publisher decision on a flagged image.
 * Backs V274 {@code publication.image_screening_decisions}.
 *
 * <p>Production code MUST go through {@link ImageScreeningDecisionRepository}
 * - the entity is intentionally narrow (no equals/hashCode, no setters
 * for the immutable identity fields) because audit rows must never mutate
 * after insert.
 */
@Entity
@Table(name = "image_screening_decisions", schema = "publication")
public class ImageScreeningDecisionEntity {

    public enum Decision {
        REPLACED_STOCK,
        REPLACED_AI,
        /** Publisher replaced the flagged media by uploading their own file. */
        REPLACED_UPLOAD,
        KEPT_ATTESTED,
        KEPT_OWN_UPLOAD,
        SKIPPED
    }

    public enum ImageSource {
        HTML, CSS, JS, DATA
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "publication_id", nullable = false, updatable = false)
    private UUID publicationId;

    @Column(name = "snapshot_version", nullable = false, updatable = false)
    private Integer snapshotVersion = 0;

    @Column(name = "decided_at", nullable = false, updatable = false)
    private Instant decidedAt = Instant.now();

    @Column(name = "decided_by", nullable = false, length = 255, updatable = false)
    private String decidedBy;

    @Column(name = "organization_id", length = 255, updatable = false)
    private String organizationId;

    @Column(name = "image_url_hash", nullable = false, length = 64, updatable = false)
    private String imageUrlHash;

    @Column(name = "image_url_host", nullable = false, length = 255, updatable = false)
    private String imageUrlHost;

    @Enumerated(EnumType.STRING)
    @Column(name = "image_source", nullable = false, length = 10, updatable = false)
    private ImageSource imageSource;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, length = 20, updatable = false)
    private Decision decision;

    @Column(name = "replacement_ref", length = 500, updatable = false)
    private String replacementRef;

    @Column(name = "attestation_text", columnDefinition = "TEXT", updatable = false)
    private String attestationText;

    @Column(name = "attestation_text_version", length = 40, updatable = false)
    private String attestationTextVersion;

    @Column(name = "user_agent", columnDefinition = "TEXT", updatable = false)
    private String userAgent;

    @Column(name = "ip_hash", length = 64, updatable = false)
    private String ipHash;

    public Long getId() { return id; }
    public UUID getPublicationId() { return publicationId; }
    public void setPublicationId(UUID publicationId) { this.publicationId = publicationId; }
    public Integer getSnapshotVersion() { return snapshotVersion; }
    public void setSnapshotVersion(Integer snapshotVersion) { this.snapshotVersion = snapshotVersion; }
    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant decidedAt) { this.decidedAt = decidedAt; }
    public String getDecidedBy() { return decidedBy; }
    public void setDecidedBy(String decidedBy) { this.decidedBy = decidedBy; }
    public String getOrganizationId() { return organizationId; }
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }
    public String getImageUrlHash() { return imageUrlHash; }
    public void setImageUrlHash(String imageUrlHash) { this.imageUrlHash = imageUrlHash; }
    public String getImageUrlHost() { return imageUrlHost; }
    public void setImageUrlHost(String imageUrlHost) { this.imageUrlHost = imageUrlHost; }
    public ImageSource getImageSource() { return imageSource; }
    public void setImageSource(ImageSource imageSource) { this.imageSource = imageSource; }
    public Decision getDecision() { return decision; }
    public void setDecision(Decision decision) { this.decision = decision; }
    public String getReplacementRef() { return replacementRef; }
    public void setReplacementRef(String replacementRef) { this.replacementRef = replacementRef; }
    public String getAttestationText() { return attestationText; }
    public void setAttestationText(String attestationText) { this.attestationText = attestationText; }
    public String getAttestationTextVersion() { return attestationTextVersion; }
    public void setAttestationTextVersion(String attestationTextVersion) { this.attestationTextVersion = attestationTextVersion; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    public String getIpHash() { return ipHash; }
    public void setIpHash(String ipHash) { this.ipHash = ipHash; }
}
