package com.apimarketplace.auth.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Entity for storing user onboarding preferences and personalization data.
 * Collected during the first login flow to customize user experience.
 */
@Entity
@Table(name = "user_onboarding")
public class UserOnboarding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    /**
     * Mandatory display name shown in marketplace and public profiles.
     */
    /**
     * Entity-level @Size stays permissive (2..100) to preserve legacy rows created
     * before the tighter (3..30) bound. New / updated values are guarded at the DTO
     * boundary (OnboardingRequest, UserProfileUpdateRequest) - see those classes.
     */
    @NotBlank(message = "Display name is required")
    @Size(min = 2, max = 100, message = "Display name must be between 2 and 100 characters")
    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    /**
     * User profession or job title.
     */
    @Size(max = 100)
    @Column(name = "profession", length = 100)
    private String profession;

    /**
     * Company size: solo, startup, small, medium, enterprise.
     */
    @Column(name = "company_size", length = 50)
    private String companySize;

    /**
     * User interests for personalization (e.g., automation, ai, data, integration).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "interests", columnDefinition = "jsonb")
    private List<String> interests = new ArrayList<>();

    /**
     * Intended use cases (e.g., api-testing, workflow-automation, chatbots).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "use_cases", columnDefinition = "jsonb")
    private List<String> useCases = new ArrayList<>();

    /**
     * User experience level: beginner, intermediate, advanced.
     */
    @Column(name = "experience_level", length = 50)
    private String experienceLevel;

    /**
     * Whether onboarding is completed.
     */
    @Column(name = "onboarding_completed", nullable = false)
    private boolean onboardingCompleted = false;

    /**
     * Whether onboarding was skipped.
     */
    @Column(name = "onboarding_skipped", nullable = false)
    private boolean onboardingSkipped = false;

    /**
     * Last completed onboarding step (allows resume).
     */
    @Column(name = "onboarding_step", nullable = false)
    private int onboardingStep = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "display_name_changed_at")
    private LocalDateTime displayNameChangedAt;

    // Constructors

    public UserOnboarding() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public UserOnboarding(User user, String displayName) {
        this();
        this.user = user;
        this.displayName = displayName;
    }

    // Pre-persist and pre-update hooks

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // Business methods

    /**
     * Marks the onboarding as completed.
     */
    public void markCompleted() {
        this.onboardingCompleted = true;
        this.completedAt = LocalDateTime.now();
    }

    /**
     * Marks the onboarding as skipped.
     */
    public void markSkipped() {
        this.onboardingSkipped = true;
        this.completedAt = LocalDateTime.now();
    }

    /**
     * Checks if onboarding needs to be shown (not completed and not skipped).
     */
    public boolean needsOnboarding() {
        return !onboardingCompleted && !onboardingSkipped;
    }

    // Getters and Setters

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
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
        this.interests = interests != null ? interests : new ArrayList<>();
    }

    public List<String> getUseCases() {
        return useCases;
    }

    public void setUseCases(List<String> useCases) {
        this.useCases = useCases != null ? useCases : new ArrayList<>();
    }

    public String getExperienceLevel() {
        return experienceLevel;
    }

    public void setExperienceLevel(String experienceLevel) {
        this.experienceLevel = experienceLevel;
    }

    public boolean isOnboardingCompleted() {
        return onboardingCompleted;
    }

    public void setOnboardingCompleted(boolean onboardingCompleted) {
        this.onboardingCompleted = onboardingCompleted;
    }

    public boolean isOnboardingSkipped() {
        return onboardingSkipped;
    }

    public void setOnboardingSkipped(boolean onboardingSkipped) {
        this.onboardingSkipped = onboardingSkipped;
    }

    public int getOnboardingStep() {
        return onboardingStep;
    }

    public void setOnboardingStep(int onboardingStep) {
        this.onboardingStep = onboardingStep;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }

    public LocalDateTime getDisplayNameChangedAt() {
        return displayNameChangedAt;
    }

    public void setDisplayNameChangedAt(LocalDateTime displayNameChangedAt) {
        this.displayNameChangedAt = displayNameChangedAt;
    }

    @Override
    public String toString() {
        return "UserOnboarding{" +
                "id=" + id +
                ", userId=" + (user != null ? user.getId() : null) +
                ", displayName='" + displayName + '\'' +
                ", profession='" + profession + '\'' +
                ", companySize='" + companySize + '\'' +
                ", experienceLevel='" + experienceLevel + '\'' +
                ", onboardingCompleted=" + onboardingCompleted +
                ", onboardingSkipped=" + onboardingSkipped +
                ", onboardingStep=" + onboardingStep +
                '}';
    }
}
