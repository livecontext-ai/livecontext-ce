package com.apimarketplace.auth.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Organization represents a workspace/tenant that contains resources (workflows, datasources, etc.).
 * Each user automatically gets a personal organization during onboarding.
 * Users can also join or create additional organizations for team collaboration.
 */
@Entity
@Table(name = "organization")
public class Organization {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Size(max = 100)
    @Column(nullable = false)
    private String name;

    @Size(max = 100)
    @Column(nullable = false, unique = true)
    private String slug;

    @Column(name = "is_personal", nullable = false)
    private boolean isPersonal = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Size(max = 255)
    @Column(name = "avatar_url")
    private String avatarUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * PR-cascade soft-delete timestamp. NULL = active org. Set by the
     * DELETE /organizations/{id} flow ; a future cron hard-purges rows with
     * deleted_at older than 30 days.
     */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /**
     * Set when the workspace hard-purge cron ran: operational data is gone, the
     * financial ledger is retained, and this org row is kept as a tombstone (so
     * owner-pays credit_ledger references stay valid). NULL = not yet purged.
     */
    @Column(name = "purged_at")
    private LocalDateTime purgedAt;

    /** User id that initiated the soft-delete (audit). NULL for active orgs. */
    @Column(name = "deleted_by")
    private Long deletedBy;

    /**
     * Set when this workspace was PAUSED because it exceeds the owner's plan
     * workspace cap after a downgrade (V311). Non-null = paused (owner + members
     * cannot enter; data retained); reconciliation un-pauses on re-upgrade.
     * Personal workspaces are never paused. NULL = active.
     */
    @Column(name = "paused_at")
    private LocalDateTime pausedAt;

    @OneToMany(mappedBy = "organization", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private Set<OrganizationMember> members = new HashSet<>();

    public Organization() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public Organization(String name, String slug, boolean isPersonal, User owner) {
        this();
        this.name = name;
        this.slug = slug;
        this.isPersonal = isPersonal;
        this.owner = owner;
    }

    // Getters and Setters

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public boolean isPersonal() {
        return isPersonal;
    }

    public void setPersonal(boolean personal) {
        isPersonal = personal;
    }

    public User getOwner() {
        return owner;
    }

    public void setOwner(User owner) {
        this.owner = owner;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
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

    public Set<OrganizationMember> getMembers() {
        return members;
    }

    public void setMembers(Set<OrganizationMember> members) {
        this.members = members;
    }

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public LocalDateTime getPausedAt() {
        return pausedAt;
    }

    public void setPausedAt(LocalDateTime pausedAt) {
        this.pausedAt = pausedAt;
    }

    /** True when the workspace is paused (over the owner's plan workspace cap). */
    public boolean isPaused() {
        return pausedAt != null;
    }

    public LocalDateTime getPurgedAt() {
        return purgedAt;
    }

    public void setPurgedAt(LocalDateTime purgedAt) {
        this.purgedAt = purgedAt;
    }

    public boolean isPurged() {
        return purgedAt != null;
    }

    public Long getDeletedBy() {
        return deletedBy;
    }

    public void setDeletedBy(Long deletedBy) {
        this.deletedBy = deletedBy;
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
