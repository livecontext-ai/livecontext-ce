package com.apimarketplace.auth.dto;

/**
 * Lightweight per-org membership entry packed inside
 * {@link UserResolutionResponse#getMemberships()}.
 *
 * <p>The gateway uses this list to validate `X-Active-Organization-ID` headers
 * coming from the frontend without doing an extra HTTP call: when a request
 * carries an active-org claim, the gateway looks it up here and injects
 * `X-Organization-ID` + `X-Organization-Role` downstream from the matched
 * entry (PR0.5 of the org/membership redesign). If the active-org header is
 * missing or unmatched, the gateway falls back to
 * {@link UserResolutionResponse#getDefaultOrganizationId()}.
 *
 * <p>The set is bounded by the user's actual memberships - soft-deleted orgs
 * are excluded by {@code OrganizationMemberRepository.findByUserIdWithOrganization}.
 */
public class OrgMembershipDto {

    private String orgId;
    private String role;
    private boolean personal;
    private boolean paused;

    public OrgMembershipDto() {}

    public OrgMembershipDto(String orgId, String role) {
        this.orgId = orgId;
        this.role = role;
    }

    public OrgMembershipDto(String orgId, String role, boolean personal) {
        this.orgId = orgId;
        this.role = role;
        this.personal = personal;
    }

    public OrgMembershipDto(String orgId, String role, boolean personal, boolean paused) {
        this.orgId = orgId;
        this.role = role;
        this.personal = personal;
        this.paused = paused;
    }

    public String getOrgId() { return orgId; }
    public void setOrgId(String orgId) { this.orgId = orgId; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    /**
     * V224-hotfix: true for personal orgs (Organization.is_personal). Gateway
     * AuthenticationFilter uses this to suppress X-Organization-ID for
     * personal-org claims so org-strict downstream readers fall back to
     * user-scope. Without this, every personal-org user sees empty results
     * across credits/interfaces/conversations/publications (the post-V224
     * org-strict routing returns empty for resources that have
     * organization_id IS NULL = legacy / not assigned to any specific org).
     */
    public boolean isPersonal() { return personal; }
    public void setPersonal(boolean personal) { this.personal = personal; }

    /**
     * "Dormant" team org: true when this is a non-owner, non-personal membership
     * whose org OWNER is no longer on a team plan. The member keeps the membership
     * (visible + leave-able) but must be BLOCKED from entering/switching into it -
     * the gateway reads this flag to reject the active-org claim. Always false in
     * CE-free embedded mode (team is unlimited there).
     */
    public boolean isPaused() { return paused; }
    public void setPaused(boolean paused) { this.paused = paused; }

    @Override
    public String toString() {
        return "OrgMembershipDto{orgId='" + orgId + "', role='" + role
                + "', personal=" + personal + ", paused=" + paused + "}";
    }
}
