package com.apimarketplace.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * In-app profile presentation data (bio / visibility), 1:1 with {@link User}
 * through a shared primary key ({@code user_id}).
 *
 * <p>Deliberately kept separate from {@link UserOnboarding}: editing a profile
 * must never touch the onboarding lifecycle (completed/skipped/step or the
 * display-name uniqueness guard). The row is created lazily the first time a
 * user edits their profile, so most fields default to empty/PUBLIC.
 */
@Entity
@Table(name = "user_profiles")
public class UserProfileEntity {

    public static final String VISIBILITY_PUBLIC = "PUBLIC";
    public static final String VISIBILITY_PRIVATE = "PRIVATE";

    @Id
    @Column(name = "user_id")
    private Long userId;

    /**
     * Public, URL-safe @handle used to address this profile (/app/u/{handle}) and shown as
     * @handle - instead of the numeric user/tenant id (sensitive). Derived from the display
     * name, editable, unique. Generated lazily, so it may be {@code null} until first resolved.
     */
    @Column(name = "handle", length = 32, unique = true)
    private String handle;

    /**
     * When the user last <i>explicitly</i> changed their @handle - drives the same
     * 1-change-per-week cooldown as the display name. Stays {@code null} when the
     * handle was only lazily auto-generated (generation must not start the cooldown).
     */
    @Column(name = "handle_changed_at")
    private LocalDateTime handleChangedAt;

    @Column(name = "bio", length = 500)
    private String bio;

    @Column(name = "profile_visibility", length = 20, nullable = false)
    private String profileVisibility = VISIBILITY_PUBLIC;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public UserProfileEntity() {
    }

    public UserProfileEntity(Long userId) {
        this.userId = userId;
    }

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /** A profile is publicly visible unless explicitly set to PRIVATE. */
    public boolean isPublic() {
        return !VISIBILITY_PRIVATE.equalsIgnoreCase(profileVisibility);
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getHandle() {
        return handle;
    }

    public void setHandle(String handle) {
        this.handle = handle;
    }

    public LocalDateTime getHandleChangedAt() {
        return handleChangedAt;
    }

    public void setHandleChangedAt(LocalDateTime handleChangedAt) {
        this.handleChangedAt = handleChangedAt;
    }

    public String getBio() {
        return bio;
    }

    public void setBio(String bio) {
        this.bio = bio;
    }

    public String getProfileVisibility() {
        return profileVisibility;
    }

    public void setProfileVisibility(String profileVisibility) {
        this.profileVisibility = profileVisibility;
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
}
