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

    /**
     * Profile visibility, three states mirroring {@code PublicationVisibility}
     * so users learn one vocabulary and the two models cannot drift apart.
     *
     * <ul>
     *   <li>{@link #VISIBILITY_PRIVATE} - no page at all; listings show the
     *       author's name but never link to them.</li>
     *   <li>{@link #VISIBILITY_UNLISTED} - the page is reachable by direct link
     *       (and from a listing), but carries noindex and stays out of the
     *       sitemap. This is the DEFAULT.</li>
     *   <li>{@link #VISIBILITY_PUBLIC} - listed and indexable by search
     *       engines.</li>
     * </ul>
     *
     * <p>The split exists because "PUBLIC" had quietly meant three different
     * things over time: visible to logged-in users, then readable by anyone
     * with the link, and now potentially indexed by Google. Users only ever
     * consented to the first. Making search indexing its own explicit state is
     * what stops that drift from silently widening again.
     */
    public static final String VISIBILITY_PUBLIC = "PUBLIC";
    public static final String VISIBILITY_UNLISTED = "UNLISTED";
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

    // Defaults to UNLISTED: a new user gets a working, linkable profile page
    // without being opted into search indexing they never asked for.
    @Column(name = "profile_visibility", length = 20, nullable = false)
    private String profileVisibility = VISIBILITY_UNLISTED;

    /**
     * Manually granted verified badge (the blue check next to the public name).
     * The platform ADMIN role grants the same badge implicitly and is NOT
     * mirrored here, so this column only ever carries the manual grants an
     * admin makes for everyone else. Resolution of the two sources lives in
     * {@code VerifiedAccountService} - never read this field directly to decide
     * whether to show a badge.
     */
    @Column(name = "verified", nullable = false)
    private boolean verified = false;

    /** When the manual grant was last flipped ON. Null while never granted. */
    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    /** Admin user id that performed the last manual grant. Null while never granted. */
    @Column(name = "verified_by")
    private Long verifiedBy;

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

    /**
     * Whether the profile page exists at all for someone other than its owner.
     * True for UNLISTED and PUBLIC.
     *
     * <p>Replaces the old {@code isPublic()}, which returned true for anything
     * that was not literally "PRIVATE". With a third state that shape would have
     * made UNLISTED behave exactly like PUBLIC everywhere, including search
     * indexing: the one outcome this split exists to prevent. Callers must now
     * say which question they are asking.
     */
    public boolean isPageVisible() {
        return !VISIBILITY_PRIVATE.equalsIgnoreCase(profileVisibility);
    }

    /**
     * Whether search engines may index the profile page. True ONLY for the
     * explicit PUBLIC state, never by default and never by falling through from
     * an unrecognised value.
     */
    public boolean isSearchIndexable() {
        return VISIBILITY_PUBLIC.equalsIgnoreCase(profileVisibility);
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

    /**
     * The stored manual grant ONLY. Callers deciding whether to render a badge
     * must go through {@code VerifiedAccountService}, which also honours the
     * ADMIN role and the managed-cloud edition gate.
     */
    public boolean isVerified() {
        return verified;
    }

    public void setVerified(boolean verified) {
        this.verified = verified;
    }

    public LocalDateTime getVerifiedAt() {
        return verifiedAt;
    }

    public void setVerifiedAt(LocalDateTime verifiedAt) {
        this.verifiedAt = verifiedAt;
    }

    public Long getVerifiedBy() {
        return verifiedBy;
    }

    public void setVerifiedBy(Long verifiedBy) {
        this.verifiedBy = verifiedBy;
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
