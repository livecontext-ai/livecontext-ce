package com.apimarketplace.auth.dto;

import com.apimarketplace.auth.domain.Organization;
import com.apimarketplace.auth.domain.OrganizationMember;
import com.apimarketplace.auth.domain.OrganizationRole;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * DTO for Organization responses.
 */
public class OrganizationDto {

    private UUID id;
    private String name;
    private String slug;
    private boolean isPersonal;
    private String avatarUrl;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private OrganizationRole currentUserRole;
    private boolean isDefault;
    private int memberCount;
    private List<MemberDto> members;

    // Plan and team fields
    private String planCode;
    private int maxMembers;
    private boolean canInvite;
    private int pendingInvitationCount;
    /**
     * "Dormant" team org for the CURRENT user: the org owner is no longer on a
     * team plan and this user is not the owner. The FE renders it as "paused" -
     * still visible + leave-able, but the switch-into action is disabled (the
     * gateway also rejects entering it). Always false for the owner / personal orgs.
     */
    private boolean paused;
    /**
     * Workspace is soft-deleted and still in its grace window (not yet hard-purged). Only
     * surfaced to the OWNER in /me so they can restore it; the gateway still rejects entering
     * it. False for active orgs.
     */
    private boolean pendingDeletion;
    /** Scheduled hard-purge time (deleted_at + grace window). Null unless pendingDeletion. */
    private LocalDateTime purgeAt;

    public OrganizationDto() {}

    public OrganizationDto(Organization org, OrganizationMember membership, int memberCount) {
        this.id = org.getId();
        this.name = org.getName();
        this.slug = org.getSlug();
        this.isPersonal = org.isPersonal();
        this.avatarUrl = buildAvatarUrl(org);
        this.createdAt = org.getCreatedAt();
        this.updatedAt = org.getUpdatedAt();
        this.memberCount = memberCount;
        if (membership != null) {
            this.currentUserRole = membership.getRole();
            this.isDefault = membership.isDefault();
        }
    }

    public static OrganizationDto fromEntity(Organization org, OrganizationMember membership, int memberCount) {
        return new OrganizationDto(org, membership, memberCount);
    }

    /**
     * Maps the org's stored avatar reference to a servable URL. When
     * {@code avatar_url} is a storage UUID (an uploaded image), returns the
     * public avatar endpoint with a {@code ?v=} cache-buster; null otherwise so
     * the UI renders the initials fallback. Defensive: a non-UUID value (legacy
     * external URL) is passed through untouched.
     */
    private static String buildAvatarUrl(Organization org) {
        String raw = org.getAvatarUrl();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            UUID.fromString(raw);
            return "/api/organizations/" + org.getId() + "/avatar?v=" + raw;
        } catch (IllegalArgumentException e) {
            return raw;
        }
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

    @JsonProperty("isPersonal")
    public boolean isPersonal() {
        return isPersonal;
    }

    public void setPersonal(boolean personal) {
        isPersonal = personal;
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

    public OrganizationRole getCurrentUserRole() {
        return currentUserRole;
    }

    public void setCurrentUserRole(OrganizationRole currentUserRole) {
        this.currentUserRole = currentUserRole;
    }

    @JsonProperty("isDefault")
    public boolean isDefault() {
        return isDefault;
    }

    public void setDefault(boolean aDefault) {
        isDefault = aDefault;
    }

    public int getMemberCount() {
        return memberCount;
    }

    public void setMemberCount(int memberCount) {
        this.memberCount = memberCount;
    }

    public List<MemberDto> getMembers() {
        return members;
    }

    public void setMembers(List<MemberDto> members) {
        this.members = members;
    }

    public String getPlanCode() { return planCode; }
    public void setPlanCode(String planCode) { this.planCode = planCode; }

    public int getMaxMembers() { return maxMembers; }
    public void setMaxMembers(int maxMembers) { this.maxMembers = maxMembers; }

    public boolean isCanInvite() { return canInvite; }
    public void setCanInvite(boolean canInvite) { this.canInvite = canInvite; }

    public int getPendingInvitationCount() { return pendingInvitationCount; }
    public void setPendingInvitationCount(int pendingInvitationCount) { this.pendingInvitationCount = pendingInvitationCount; }

    public boolean isPaused() { return paused; }
    public void setPaused(boolean paused) { this.paused = paused; }

    public boolean isPendingDeletion() { return pendingDeletion; }
    public void setPendingDeletion(boolean pendingDeletion) { this.pendingDeletion = pendingDeletion; }

    public LocalDateTime getPurgeAt() { return purgeAt; }
    public void setPurgeAt(LocalDateTime purgeAt) { this.purgeAt = purgeAt; }

    /**
     * DTO for organization member.
     */
    public static class MemberDto {
        private Long userId;
        private String email;
        private String displayName;
        private String avatarUrl;
        private OrganizationRole role;
        private LocalDateTime joinedAt;
        private boolean isOwner;

        public MemberDto() {}

        public MemberDto(OrganizationMember member, String displayName) {
            this.userId = member.getUser().getId();
            this.email = member.getUser().getEmail();
            this.displayName = displayName;
            this.avatarUrl = member.getUser().getAvatarUrl();
            this.role = member.getRole();
            this.joinedAt = member.getJoinedAt();
            this.isOwner = member.getRole() == OrganizationRole.OWNER;
        }

        // Getters and Setters
        public Long getUserId() { return userId; }
        public void setUserId(Long userId) { this.userId = userId; }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public String getAvatarUrl() { return avatarUrl; }
        public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }
        public OrganizationRole getRole() { return role; }
        public void setRole(OrganizationRole role) { this.role = role; }
        public LocalDateTime getJoinedAt() { return joinedAt; }
        public void setJoinedAt(LocalDateTime joinedAt) { this.joinedAt = joinedAt; }
        @JsonProperty("isOwner")
        public boolean isOwner() { return isOwner; }
        public void setOwner(boolean owner) { isOwner = owner; }
    }
}
