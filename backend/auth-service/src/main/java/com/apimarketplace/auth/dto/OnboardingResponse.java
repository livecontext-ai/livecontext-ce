package com.apimarketplace.auth.dto;

import com.apimarketplace.auth.domain.UserOnboarding;

import java.util.List;

/**
 * DTO for onboarding response.
 */
public class OnboardingResponse {

    private boolean needsOnboarding;
    private boolean completed;
    private boolean skipped;
    private boolean emailVerified;
    private int currentStep;

    // Onboarding data (if exists)
    private String displayName;
    private String profession;
    private String companySize;
    private List<String> interests;
    private List<String> useCases;
    private String experienceLevel;

    // Constructors

    public OnboardingResponse() {}

    /**
     * Create response for user that needs onboarding.
     */
    public static OnboardingResponse needsOnboarding() {
        OnboardingResponse response = new OnboardingResponse();
        response.setNeedsOnboarding(true);
        response.setCompleted(false);
        response.setSkipped(false);
        response.setCurrentStep(0);
        return response;
    }

    /**
     * Create response from existing onboarding data.
     */
    public static OnboardingResponse fromEntity(UserOnboarding onboarding) {
        OnboardingResponse response = new OnboardingResponse();
        response.setNeedsOnboarding(onboarding.needsOnboarding());
        response.setCompleted(onboarding.isOnboardingCompleted());
        response.setSkipped(onboarding.isOnboardingSkipped());
        response.setCurrentStep(onboarding.getOnboardingStep());
        response.setDisplayName(onboarding.getDisplayName());
        response.setProfession(onboarding.getProfession());
        response.setCompanySize(onboarding.getCompanySize());
        response.setInterests(onboarding.getInterests());
        response.setUseCases(onboarding.getUseCases());
        response.setExperienceLevel(onboarding.getExperienceLevel());
        return response;
    }

    /**
     * Create response for completed onboarding.
     */
    public static OnboardingResponse completed(UserOnboarding onboarding) {
        OnboardingResponse response = fromEntity(onboarding);
        response.setNeedsOnboarding(false);
        response.setCompleted(true);
        return response;
    }

    // Getters and Setters

    public boolean isNeedsOnboarding() {
        return needsOnboarding;
    }

    public void setNeedsOnboarding(boolean needsOnboarding) {
        this.needsOnboarding = needsOnboarding;
    }

    public boolean isCompleted() {
        return completed;
    }

    public void setCompleted(boolean completed) {
        this.completed = completed;
    }

    public boolean isSkipped() {
        return skipped;
    }

    public void setSkipped(boolean skipped) {
        this.skipped = skipped;
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }

    public void setEmailVerified(boolean emailVerified) {
        this.emailVerified = emailVerified;
    }

    public int getCurrentStep() {
        return currentStep;
    }

    public void setCurrentStep(int currentStep) {
        this.currentStep = currentStep;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
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
}
