package com.apimarketplace.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * DTO for onboarding submission request.
 */
public class OnboardingRequest {

    @NotBlank(message = "Display name is required")
    @Size(min = 3, max = 30, message = "Display name must be between 3 and 30 characters")
    private String displayName;

    @Size(max = 100)
    private String profession;

    private String companySize; // solo, startup, small, medium, enterprise

    private List<String> interests;

    private List<String> useCases;

    private String experienceLevel; // beginner, intermediate, advanced

    private int currentStep; // For partial save (resume capability)

    private boolean skip; // If user wants to skip onboarding

    // Constructors

    public OnboardingRequest() {}

    public OnboardingRequest(String displayName) {
        setDisplayName(displayName);
    }

    // Getters and Setters

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName == null ? null : displayName.trim();
    }

    public String getProfession() {
        return profession;
    }

    public void setProfession(String profession) {
        this.profession = profession;
    }

    public String getCompanySize() {
        return companySize;
    }

    public void setCompanySize(String companySize) {
        this.companySize = companySize;
    }

    public List<String> getInterests() {
        return interests;
    }

    public void setInterests(List<String> interests) {
        this.interests = interests;
    }

    public List<String> getUseCases() {
        return useCases;
    }

    public void setUseCases(List<String> useCases) {
        this.useCases = useCases;
    }

    public String getExperienceLevel() {
        return experienceLevel;
    }

    public void setExperienceLevel(String experienceLevel) {
        this.experienceLevel = experienceLevel;
    }

    public int getCurrentStep() {
        return currentStep;
    }

    public void setCurrentStep(int currentStep) {
        this.currentStep = currentStep;
    }

    public boolean isSkip() {
        return skip;
    }

    public void setSkip(boolean skip) {
        this.skip = skip;
    }

    @Override
    public String toString() {
        return "OnboardingRequest{" +
                "displayName='" + displayName + '\'' +
                ", profession='" + profession + '\'' +
                ", companySize='" + companySize + '\'' +
                ", interests=" + interests +
                ", useCases=" + useCases +
                ", experienceLevel='" + experienceLevel + '\'' +
                ", currentStep=" + currentStep +
                ", skip=" + skip +
                '}';
    }
}
