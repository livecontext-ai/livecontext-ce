package com.apimarketplace.auth.service;

import com.apimarketplace.auth.analytics.AuthAnalyticsEmitter;
import com.apimarketplace.auth.domain.Organization;
import com.apimarketplace.auth.domain.OrganizationAuditEvent;
import com.apimarketplace.auth.domain.OrganizationMember;
import com.apimarketplace.auth.domain.OrganizationRole;
import com.apimarketplace.auth.domain.OrganizationSamlConnection;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.OrganizationMemberRepository;
import com.apimarketplace.auth.repository.OrganizationRepository;
import com.apimarketplace.auth.repository.OrganizationSamlConnectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class OrganizationSamlLoginService {

    private static final Logger log = LoggerFactory.getLogger(OrganizationSamlLoginService.class);

    /** Why a SAML sign-in was refused a workspace membership (analytics reason, lowercased). */
    enum RejectionReason {
        CONNECTION_INACTIVE,
        MISSING_ORGANIZATION,
        DOMAIN_NOT_VERIFIED,
        PLAN_NOT_TEAM,
        MEMBER_LIMIT,
        SAVE_FAILED
    }

    private final OrganizationSamlConnectionRepository samlRepository;
    private final OrganizationMemberRepository memberRepository;
    private final OrganizationRepository organizationRepository;
    private final OrganizationMemberService memberService;
    private final OrganizationAuditService auditService;
    private final OrganizationSsoDomainService domainService;

    public OrganizationSamlLoginService(
            OrganizationSamlConnectionRepository samlRepository,
            OrganizationMemberRepository memberRepository,
            OrganizationRepository organizationRepository,
            OrganizationMemberService memberService,
            OrganizationAuditService auditService,
            OrganizationSsoDomainService domainService
    ) {
        this.samlRepository = samlRepository;
        this.memberRepository = memberRepository;
        this.organizationRepository = organizationRepository;
        this.memberService = memberService;
        this.auditService = auditService;
        this.domainService = domainService;
    }

    /** Product analytics (PostHog). Optional: a null field emits nothing. */
    @Autowired(required = false)
    private AuthAnalyticsEmitter analytics;

    /** Same as the three-argument form, treating the call as a real sign-in (rejection reported). */
    @Transactional
    public Optional<UUID> ensureMembershipForIdentityProvider(User user, String identityProviderAlias) {
        return ensureMembershipForIdentityProvider(user, identityProviderAlias, true);
    }

    /**
     * @param reportRejection whether a refusal is reported to analytics. This check runs on every
     *                        uncached user resolution, not once per sign-in, so the caller passes
     *                        {@code true} only for the resolution that carries a real new
     *                        authentication; a repeat resolution still throws, silently. A join is
     *                        reported regardless: a membership is created once.
     */
    @Transactional
    public Optional<UUID> ensureMembershipForIdentityProvider(User user, String identityProviderAlias,
                                                             boolean reportRejection) {
        if (user == null || user.getId() == null || identityProviderAlias == null || identityProviderAlias.isBlank()) {
            return Optional.empty();
        }
        if (!OrganizationSamlService.isOrganizationSamlAlias(identityProviderAlias)) {
            return Optional.empty();
        }

        OrganizationSamlConnection connection = samlRepository.findByIdpAlias(identityProviderAlias)
                .orElseThrow(() -> reject(reportRejection, user, null, RejectionReason.CONNECTION_INACTIVE,
                        "SAML SSO connection is not active", null));
        if (connection.getStatus() != OrganizationSamlConnection.Status.ACTIVE
                || connection.getOrganization() == null
                || connection.getOrganization().isDeleted()) {
            throw reject(reportRejection, user, connection.getOrganization() != null ? connection.getOrganization().getId() : null,
                    RejectionReason.CONNECTION_INACTIVE, "SAML SSO connection is not active", null);
        }

        Organization organization = connection.getOrganization();
        UUID orgId = organization.getId();
        if (orgId == null) {
            throw reject(reportRejection, user, null, RejectionReason.MISSING_ORGANIZATION,
                    "SAML SSO connection is missing an organization", null);
        }

        Optional<OrganizationMember> existing = memberRepository.findActiveByOrganizationIdAndUserId(orgId, user.getId());
        if (existing.isPresent()) {
            // Deliberately not counted: this check runs on every uncached user resolution,
            // not once per sign-in, so an "already a member" event would be request noise.
            return Optional.of(orgId);
        }

        // ADMISSION rule, deliberately placed after the existing-member return above: people who
        // already belong to the workspace keep signing in exactly as before (this rule shipped
        // after SAML did, and must not lock a live workspace out on deploy). What it closes is
        // JOINING: the IdP is configured by the workspace admin, asserts whatever email it likes
        // and Keycloak trusts it, so only an address on a domain the workspace PROVED it owns
        // may enter. Otherwise any Team workspace could mint a member in someone else's name.
        if (!domainService.isEmailOnVerifiedDomain(orgId, user.getEmail())) {
            throw reject(reportRejection, user, orgId, RejectionReason.DOMAIN_NOT_VERIFIED,
                    "This email address is not on a domain verified for this workspace's SSO", null);
        }
        organization = lockOrganizationForAdmission(reportRejection, user, orgId);
        existing = memberRepository.findActiveByOrganizationIdAndUserId(orgId, user.getId());
        if (existing.isPresent()) {
            return Optional.of(orgId);
        }

        enforceTeamAdmission(reportRejection, user, orgId);
        boolean makeDefault = memberRepository.findActiveDefaultByUserId(user.getId()).isEmpty();
        OrganizationMember membership = new OrganizationMember(organization, user, OrganizationRole.MEMBER, makeDefault);

        try {
            memberRepository.save(membership);
        } catch (RuntimeException e) {
            // A duplicate membership used to be recovered here by re-reading the winner's row.
            // That read can never run: the violation has already put this transaction in
            // PostgreSQL's ERROR state, so the SELECT comes back as 25P02 and the caller sees
            // that instead of the answer the catch promised. Nor is the recovery needed: the
            // admission is serialised on the organization row by lockOrganizationForAdmission
            // above, then re-checked under that lock, so a concurrent joiner is already
            // accounted for by the time we get here. Reaching this line means something outside that reasoning
            // went wrong, and refusing the join is the safe reading of a SAML admission.
            throw reject(reportRejection, user, orgId, RejectionReason.SAVE_FAILED, "Could not join SAML workspace", e);
        }

        auditService.record(orgId, user.getId(), OrganizationAuditEvent.Type.SAML_SSO_MEMBER_JOINED,
                Map.of("idpAlias", identityProviderAlias, "role", OrganizationRole.MEMBER.name()));
        emit(user, orgId, AuthAnalyticsEmitter.SSO_OUTCOME_JOINED, null, OrganizationRole.MEMBER);
        return Optional.of(orgId);
    }

    private Organization lockOrganizationForAdmission(boolean reportRejection, User user, UUID orgId) {
        Organization organization = organizationRepository.findByIdForUpdate(orgId)
                .orElseThrow(() -> reject(reportRejection, user, orgId, RejectionReason.CONNECTION_INACTIVE,
                        "SAML SSO connection is not active", null));
        if (organization.isDeleted()) {
            throw reject(reportRejection, user, orgId, RejectionReason.CONNECTION_INACTIVE, "SAML SSO connection is not active", null);
        }
        return organization;
    }

    private void enforceTeamAdmission(boolean reportRejection, User user, UUID orgId) {
        OrganizationMemberService.TeamStatus status = memberService.getTeamStatus(orgId);
        if (!status.supportsTeam()) {
            throw reject(reportRejection, user, orgId, RejectionReason.PLAN_NOT_TEAM, "SAML SSO requires a Team or Enterprise plan", null);
        }
        if (!status.canInvite()) {
            throw reject(reportRejection, user, orgId, RejectionReason.MEMBER_LIMIT, "Member limit reached (" + status.maxMembers()
                    + "). Upgrade your plan for more members.", null);
        }
    }

    /** Records the refusal for analytics (when asked to), then builds the exception the caller throws. */
    private SamlMembershipException reject(boolean reportRejection, User user, UUID orgId, RejectionReason reason,
                                           String message, Throwable cause) {
        if (reportRejection) emit(user, orgId, AuthAnalyticsEmitter.SSO_OUTCOME_REJECTED,
                reason.name().toLowerCase(java.util.Locale.ROOT), null);
        return cause != null ? new SamlMembershipException(message, cause) : new SamlMembershipException(message);
    }

    /** Best-effort: analytics never changes the admission outcome. */
    private void emit(User user, UUID orgId, String outcome, String reason, OrganizationRole role) {
        if (analytics == null) return;
        try {
            analytics.ssoMemberJoined(user.getId(), orgId != null ? orgId.toString() : null, outcome, reason, role);
        } catch (Exception e) {
            log.debug("[saml] analytics dropped: {}", e.toString());
        }
    }
}
