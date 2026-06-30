package com.apimarketplace.publication.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "publication_snapshot_versions")
public class PublicationSnapshotVersionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "publication_id", nullable = false)
    private UUID publicationId;

    @Column(name = "version", nullable = false)
    private Integer version;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "plan_snapshot", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> planSnapshot;

    @Column(name = "label")
    private String label;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public PublicationSnapshotVersionEntity() {
        this.createdAt = Instant.now();
    }

    public PublicationSnapshotVersionEntity(UUID publicationId, Integer version, Map<String, Object> planSnapshot) {
        this();
        this.publicationId = publicationId;
        this.version = version;
        this.planSnapshot = planSnapshot;
    }

    @PrePersist
    private void ensureDefaults() {
        if (this.id == null) this.id = UUID.randomUUID();
        if (this.createdAt == null) this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getPublicationId() {
        return publicationId;
    }

    public void setPublicationId(UUID publicationId) {
        this.publicationId = publicationId;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public Map<String, Object> getPlanSnapshot() {
        return planSnapshot;
    }

    public void setPlanSnapshot(Map<String, Object> planSnapshot) {
        this.planSnapshot = planSnapshot;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
