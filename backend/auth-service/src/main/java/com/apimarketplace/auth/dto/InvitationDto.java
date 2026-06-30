package com.apimarketplace.auth.dto;

import com.apimarketplace.auth.domain.InvitationStatus;
import com.apimarketplace.auth.domain.OrganizationInvitation;
import com.apimarketplace.auth.domain.OrganizationRole;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * DTO for organization invitation responses.
 */
public class InvitationDto {

    private UUID id;
    private String email;
    private OrganizationRole role;
    private InvitationStatus status;
    private String invitedByName;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
    /**
     * Milestone-1 audit fix: org display name + id, so the /app/invitations
     * inbox can render "X invited you to {orgName}" instead of just
     * "X invited you" (audit B flagged: invitee couldn't see WHICH org
     * they were joining before clicking accept).
     */
    private String organizationName;
    private UUID organizationId;

    /**
     * CE invite-by-link only: the raw invitation token, so a CE admin can build a
     * copyable accept link for a brand-new invitee (no email delivery in embedded
     * auth). Populated ONLY when {@code includeToken} is true - the controller
     * passes the same embedded/auth.mode signal {@link com.apimarketplace.auth.service.OrganizationMemberService}
     * uses. In cloud the token is delivered by email, NEVER in the API response,
     * so this stays {@code null} (and is omitted from JSON via the getter).
     */
    private String token;

    public InvitationDto() {}

    public InvitationDto(OrganizationInvitation invitation, String invitedByName) {
        this(invitation, invitedByName, false);
    }

    public InvitationDto(OrganizationInvitation invitation, String invitedByName, boolean includeToken) {
        this.id = invitation.getId();
        this.email = invitation.getEmail();
        this.role = invitation.getRole();
        this.status = invitation.getStatus();
        this.invitedByName = invitedByName;
        this.createdAt = invitation.getCreatedAt();
        this.expiresAt = invitation.getExpiresAt();
        if (invitation.getOrganization() != null) {
            this.organizationName = invitation.getOrganization().getName();
            this.organizationId = invitation.getOrganization().getId();
        }
        if (includeToken) {
            this.token = invitation.getToken();
        }
    }

    /**
     * S-4: response DTO for {@code POST /members/invite}.
     *
     * <p>The invite endpoint takes two server-side paths - auto-accept when
     * the invitee already has an account (status flips to ACCEPTED in DB),
     * and email-send when they don't (status stays PENDING). The DB row
     * faithfully reflects the path; the HTTP response must not, otherwise
     * an attacker can enumerate existing accounts by inviting an address
     * and reading the response status. Always echoes PENDING here. The
     * org's own member list / invitation list endpoints carry the real
     * status - those are visible only to admins of the org, which is
     * acceptable (they already know who's a member).
     */
    public static InvitationDto forInviteResponse(OrganizationInvitation invitation, String invitedByName) {
        return forInviteResponse(invitation, invitedByName, false);
    }

    /**
     * CE-aware variant: {@code includeToken=true} (embedded auth only) exposes the
     * raw token so the admin can copy the accept link. Cloud passes {@code false}
     * so the token is never echoed. Status is still normalized to PENDING (S-4).
     */
    public static InvitationDto forInviteResponse(OrganizationInvitation invitation, String invitedByName, boolean includeToken) {
        InvitationDto dto = new InvitationDto(invitation, invitedByName, includeToken);
        dto.status = InvitationStatus.PENDING;
        return dto;
    }

    // Getters and Setters

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public OrganizationRole getRole() { return role; }
    public void setRole(OrganizationRole role) { this.role = role; }

    public InvitationStatus getStatus() { return status; }
    public void setStatus(InvitationStatus status) { this.status = status; }

    public String getInvitedByName() { return invitedByName; }
    public void setInvitedByName(String invitedByName) { this.invitedByName = invitedByName; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }

    public String getOrganizationName() { return organizationName; }
    public void setOrganizationName(String organizationName) { this.organizationName = organizationName; }

    public UUID getOrganizationId() { return organizationId; }
    public void setOrganizationId(UUID organizationId) { this.organizationId = organizationId; }

    /** Null (and omitted from JSON) outside CE embedded invite-by-link. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
}
