package com.apimarketplace.auth.dto;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * DTO returned to the gateway by /api/users/resolve.
 * Carries identity + plan + onboarding flags + credit-wallet balance.
 *
 * <p>Legacy cycle-counter quota fields (remainingTokens / remainingRequests / usedStorage /
 * maxStorage) were removed - the billing system is now wallet-based via {@code CreditService}.
 */
public class UserResolutionResponse {

    private Long userId;
    private String providerId;
    private String email;
    /**
     * Legacy field - keeps the user's OWN subscription plan today.
     * PR7 cutover will repoint this to the active-workspace plan
     * ({@link #activeOrgPlan}). See {@link #billingPlan} for the
     * always-per-user companion that PR6 ships in parallel.
     */
    private String plan;
    /**
     * PR6 dual-write - the user's OWN subscription plan (per-user,
     * where Stripe customer points). Always populated regardless of
     * which org is active. After PR7 cutover, FE billing UI should
     * read this (not {@link #plan}) for "your subscription" displays.
     */
    private String billingPlan;
    /**
     * PR6 dual-write - the plan of the user's current default
     * workspace's OWNER. Stays "FREE" if there's no default org
     * or the owner has no active subscription. After PR7 cutover,
     * the gateway's X-User-Plan header reads this (not {@link #plan})
     * so workspace capabilities follow the active org context.
     */
    private String activeOrgPlan;
    private Set<String> roles;
    private Long userVersion;
    private boolean isActive;
    // Frontend signals
    private boolean firstLogin;
    private boolean profileIncomplete;
    private boolean needsOnboarding;

    // Credit-wallet balance (CreditService is the source of truth)
    private BigDecimal remainingCredits;

    // Organization context
    private String defaultOrganizationId;
    private String defaultOrganizationRole;

    /**
     * MCP tool names the authenticating API key may call. Populated ONLY when the
     * resolution came from a SCOPED multi API key (auth.api_keys row with non-null
     * scopes); {@code null} for JWT auth and for legacy/full-access keys. The
     * gateway forwards it downstream as the {@code X-Api-Key-Scopes} header
     * (comma-joined) so services can enforce least-privilege tool access.
     */
    private List<String> apiKeyScopes;

    /**
     * All non-deleted orgs the user is a member of, with their role per-org.
     * Used by the gateway to validate active-org claims sent by the frontend
     * (PR0.5 of the org/membership redesign - see {@link OrgMembershipDto}).
     * Initialised to empty list so JSON deserialisation of legacy payloads
     * without this field doesn't NPE downstream lookups.
     */
    private List<OrgMembershipDto> memberships = Collections.emptyList();

    public UserResolutionResponse() {}

    public UserResolutionResponse(Long userId, String providerId, String email, String plan,
                                  Set<String> roles, Long userVersion,
                                  boolean isActive, boolean firstLogin, boolean profileIncomplete,
                                  boolean needsOnboarding) {
        this.userId = userId;
        this.providerId = providerId;
        this.email = email;
        this.plan = plan;
        this.roles = roles;
        this.userVersion = userVersion;
        this.isActive = isActive;
        this.firstLogin = firstLogin;
        this.profileIncomplete = profileIncomplete;
        this.needsOnboarding = needsOnboarding;
    }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getProviderId() { return providerId; }
    public void setProviderId(String providerId) { this.providerId = providerId; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPlan() { return plan; }
    public void setPlan(String plan) { this.plan = plan; }

    public String getBillingPlan() { return billingPlan; }
    public void setBillingPlan(String billingPlan) { this.billingPlan = billingPlan; }

    public String getActiveOrgPlan() { return activeOrgPlan; }
    public void setActiveOrgPlan(String activeOrgPlan) { this.activeOrgPlan = activeOrgPlan; }

    public Set<String> getRoles() { return roles; }
    public void setRoles(Set<String> roles) { this.roles = roles; }

    public Long getUserVersion() { return userVersion; }
    public void setUserVersion(Long userVersion) { this.userVersion = userVersion; }

    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { isActive = active; }

    public boolean isFirstLogin() { return firstLogin; }
    public void setFirstLogin(boolean firstLogin) { this.firstLogin = firstLogin; }

    public boolean isProfileIncomplete() { return profileIncomplete; }
    public void setProfileIncomplete(boolean profileIncomplete) { this.profileIncomplete = profileIncomplete; }

    public boolean isNeedsOnboarding() { return needsOnboarding; }
    public void setNeedsOnboarding(boolean needsOnboarding) { this.needsOnboarding = needsOnboarding; }

    public String getDefaultOrganizationId() { return defaultOrganizationId; }
    public void setDefaultOrganizationId(String defaultOrganizationId) { this.defaultOrganizationId = defaultOrganizationId; }

    public String getDefaultOrganizationRole() { return defaultOrganizationRole; }
    public void setDefaultOrganizationRole(String defaultOrganizationRole) { this.defaultOrganizationRole = defaultOrganizationRole; }

    public List<OrgMembershipDto> getMemberships() { return memberships; }
    public void setMemberships(List<OrgMembershipDto> memberships) {
        this.memberships = memberships != null ? memberships : Collections.emptyList();
    }

    /**
     * Lookup helper used by the gateway: returns the role for {@code orgId}
     * if the user is a member of that org, or {@code null} otherwise.
     */
    public String findRoleForOrg(String orgId) {
        if (orgId == null || memberships == null) return null;
        for (OrgMembershipDto m : memberships) {
            if (orgId.equals(m.getOrgId())) return m.getRole();
        }
        return null;
    }

    public BigDecimal getRemainingCredits() { return remainingCredits; }
    public void setRemainingCredits(BigDecimal remainingCredits) { this.remainingCredits = remainingCredits; }

    public List<String> getApiKeyScopes() { return apiKeyScopes; }
    public void setApiKeyScopes(List<String> apiKeyScopes) { this.apiKeyScopes = apiKeyScopes; }

    public boolean hasCredits() {
        return remainingCredits != null && remainingCredits.compareTo(BigDecimal.ZERO) > 0;
    }

    public boolean canMakeRequest() {
        // Per-call enforcement is delegated to CreditService at LLM-call time;
        // the gateway only blocks inactive accounts.
        return isActive;
    }

    @Override
    public String toString() {
        return "UserResolutionResponse{" +
                "userId=" + userId +
                ", providerId='" + providerId + '\'' +
                ", email='" + email + '\'' +
                ", plan='" + plan + '\'' +
                ", roles=" + roles +
                ", userVersion=" + userVersion +
                ", isActive=" + isActive +
                ", firstLogin=" + firstLogin +
                ", profileIncomplete=" + profileIncomplete +
                ", needsOnboarding=" + needsOnboarding +
                ", remainingCredits=" + remainingCredits +
                ", defaultOrganizationId='" + defaultOrganizationId + '\'' +
                ", defaultOrganizationRole='" + defaultOrganizationRole + '\'' +
                '}';
    }
}
