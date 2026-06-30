package com.apimarketplace.publication.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public class PublicationDto {
    private UUID id;
    private UUID workflowId;
    private String title;
    private String description;
    private UUID showcaseInterfaceId;
    private String showcaseRunId;
    private Map<String, Object> planSnapshot;
    private Integer planVersion;
    private Integer snapshotVersion;
    private List<Map<String, Object>> nodeIcons;
    private Integer creditsPerUse;
    private String publisherId;
    private String publisherName;
    private String publisherEmail;
    private String publisherAvatarUrl;
    private UUID projectId;
    private String status;
    private String visibility;
    private String displayMode;
    private Integer interfaceCount;
    private Integer datasourceCount;
    private Integer useCount;
    private Integer totalCreditsEarned;
    private Double averageRating;
    private Integer reviewCount;
    private Instant publishedAt;
    private Instant updatedAt;
    private Map<String, Object> category;

    public PublicationDto() {
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(UUID workflowId) {
        this.workflowId = workflowId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public UUID getShowcaseInterfaceId() {
        return showcaseInterfaceId;
    }

    public void setShowcaseInterfaceId(UUID showcaseInterfaceId) {
        this.showcaseInterfaceId = showcaseInterfaceId;
    }

    public String getShowcaseRunId() {
        return showcaseRunId;
    }

    public void setShowcaseRunId(String showcaseRunId) {
        this.showcaseRunId = showcaseRunId;
    }

    public Map<String, Object> getPlanSnapshot() {
        return planSnapshot;
    }

    public void setPlanSnapshot(Map<String, Object> planSnapshot) {
        this.planSnapshot = planSnapshot;
    }

    public Integer getPlanVersion() {
        return planVersion;
    }

    public void setPlanVersion(Integer planVersion) {
        this.planVersion = planVersion;
    }

    public Integer getSnapshotVersion() {
        return snapshotVersion;
    }

    public void setSnapshotVersion(Integer snapshotVersion) {
        this.snapshotVersion = snapshotVersion;
    }

    public List<Map<String, Object>> getNodeIcons() {
        return nodeIcons;
    }

    public void setNodeIcons(List<Map<String, Object>> nodeIcons) {
        this.nodeIcons = nodeIcons;
    }

    public Integer getCreditsPerUse() {
        return creditsPerUse;
    }

    public void setCreditsPerUse(Integer creditsPerUse) {
        this.creditsPerUse = creditsPerUse;
    }

    public String getPublisherId() {
        return publisherId;
    }

    public void setPublisherId(String publisherId) {
        this.publisherId = publisherId;
    }

    public String getPublisherName() {
        return publisherName;
    }

    public void setPublisherName(String publisherName) {
        this.publisherName = publisherName;
    }

    public String getPublisherEmail() {
        return publisherEmail;
    }

    public void setPublisherEmail(String publisherEmail) {
        this.publisherEmail = publisherEmail;
    }

    public String getPublisherAvatarUrl() {
        return publisherAvatarUrl;
    }

    public void setPublisherAvatarUrl(String publisherAvatarUrl) {
        this.publisherAvatarUrl = publisherAvatarUrl;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public void setProjectId(UUID projectId) {
        this.projectId = projectId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getVisibility() {
        return visibility;
    }

    public void setVisibility(String visibility) {
        this.visibility = visibility;
    }

    public String getDisplayMode() {
        return displayMode;
    }

    public void setDisplayMode(String displayMode) {
        this.displayMode = displayMode;
    }

    public Integer getInterfaceCount() {
        return interfaceCount;
    }

    public void setInterfaceCount(Integer interfaceCount) {
        this.interfaceCount = interfaceCount;
    }

    public Integer getDatasourceCount() {
        return datasourceCount;
    }

    public void setDatasourceCount(Integer datasourceCount) {
        this.datasourceCount = datasourceCount;
    }

    public Integer getUseCount() {
        return useCount;
    }

    public void setUseCount(Integer useCount) {
        this.useCount = useCount;
    }

    public Integer getTotalCreditsEarned() {
        return totalCreditsEarned;
    }

    public void setTotalCreditsEarned(Integer totalCreditsEarned) {
        this.totalCreditsEarned = totalCreditsEarned;
    }

    public Double getAverageRating() {
        return averageRating;
    }

    public void setAverageRating(Double averageRating) {
        this.averageRating = averageRating;
    }

    public Integer getReviewCount() {
        return reviewCount;
    }

    public void setReviewCount(Integer reviewCount) {
        this.reviewCount = reviewCount;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Map<String, Object> getCategory() {
        return category;
    }

    public void setCategory(Map<String, Object> category) {
        this.category = category;
    }
}
