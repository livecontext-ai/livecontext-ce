package com.apimarketplace.auth.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * OrganizationMember represents the membership of a user in an organization.
 * This is a junction table with additional attributes (role, isDefault, joinedAt).
 */
@Entity
@Table(name = "organization_member",
        uniqueConstraints = @UniqueConstraint(columnNames = {"organization_id", "user_id"}))
public class OrganizationMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Convert(converter = OrganizationRoleConverter.class)
    @Column(nullable = false, length = 20)
    private OrganizationRole role = OrganizationRole.MEMBER;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault = false;

    @Column(name = "joined_at", nullable = false)
    private LocalDateTime joinedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invited_by")
    private User invitedBy;

    public OrganizationMember() {
        this.joinedAt = LocalDateTime.now();
    }

    public OrganizationMember(Organization organization, User user, OrganizationRole role, boolean isDefault) {
        this();
        this.organization = organization;
        this.user = user;
        this.role = role;
        this.isDefault = isDefault;
    }

    // Getters and Setters

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Organization getOrganization() {
        return organization;
    }

    public void setOrganization(Organization organization) {
        this.organization = organization;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public OrganizationRole getRole() {
        return role;
    }

    public void setRole(OrganizationRole role) {
        this.role = role;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public void setDefault(boolean aDefault) {
        isDefault = aDefault;
    }

    public LocalDateTime getJoinedAt() {
        return joinedAt;
    }

    public void setJoinedAt(LocalDateTime joinedAt) {
        this.joinedAt = joinedAt;
    }

    public User getInvitedBy() {
        return invitedBy;
    }

    public void setInvitedBy(User invitedBy) {
        this.invitedBy = invitedBy;
    }

    /**
     * Convenience method to get the organization ID without loading the full entity.
     */
    public UUID getOrganizationId() {
        return organization != null ? organization.getId() : null;
    }
}
