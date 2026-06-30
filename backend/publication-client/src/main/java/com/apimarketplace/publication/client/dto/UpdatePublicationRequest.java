package com.apimarketplace.publication.client.dto;

public class UpdatePublicationRequest {
    private String title;
    private String description;
    private String categoryId;
    private Integer creditsPerUse;
    private String showcaseInterfaceId;
    private String showcaseRunId;
    private String visibility;
    private String displayMode;

    public UpdatePublicationRequest() {
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

    public String getDisplayMode() {
        return displayMode;
    }

    public void setDisplayMode(String displayMode) {
        this.displayMode = displayMode;
    }
}
