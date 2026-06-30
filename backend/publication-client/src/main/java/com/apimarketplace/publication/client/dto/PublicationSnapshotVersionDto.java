package com.apimarketplace.publication.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public class PublicationSnapshotVersionDto {
    private UUID id;
    private UUID publicationId;
    private Integer version;
    private Map<String, Object> planSnapshot;
    private String label;
    private Instant createdAt;

    public PublicationSnapshotVersionDto() {
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
