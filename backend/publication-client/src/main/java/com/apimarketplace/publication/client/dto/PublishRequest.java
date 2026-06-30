package com.apimarketplace.publication.client.dto;

public class PublishRequest {
    private String workflowId;
    private String title;
    private String description;
    private String categoryId;
    private Integer creditsPerUse;
    private String publisherName;
    private String publisherEmail;
    private String publisherAvatarUrl;
    private String showcaseInterfaceId;
    private String showcaseRunId;
    private String visibility;
    private Integer planVersion;
    private String displayMode;

    public PublishRequest() {
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(String workflowId) {
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

    public String getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(String categoryId) {
        this.categoryId = categoryId;
    }

    public Integer getCreditsPerUse() {
        return creditsPerUse;
    }

    public void setCreditsPerUse(Integer creditsPerUse) {
        this.creditsPerUse = creditsPerUse;
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

    public String getShowcaseInterfaceId() {
        return showcaseInterfaceId;
    }

    public void setShowcaseInterfaceId(String showcaseInterfaceId) {
        this.showcaseInterfaceId = showcaseInterfaceId;
    }

    public String getShowcaseRunId() {
        return showcaseRunId;
    }

    public void setShowcaseRunId(String showcaseRunId) {
        this.showcaseRunId = showcaseRunId;
    }

    public String getVisibility() {
        return visibility;
    }

    public void setVisibility(String visibility) {
        this.visibility = visibility;
    }

    public Integer getPlanVersion() {
        return planVersion;
    }

    public void setPlanVersion(Integer planVersion) {
        this.planVersion = planVersion;
    }

    public String getDisplayMode() {
        return displayMode;
    }

    public void setDisplayMode(String displayMode) {
        this.displayMode = displayMode;
    }
}
