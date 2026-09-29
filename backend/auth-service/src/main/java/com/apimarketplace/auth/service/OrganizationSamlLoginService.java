package com.apimarketplace.auth.service;

import com.apimarketplace.auth.analytics.AuthAnalyticsEmitter;
import com.apimarketplace.auth.domain.AuthProvider;
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
        SAVE_FAILED,
        /** The token reached an account this IdP did not create (a linked password/Google/GitHub account). */
        NOT_PROVISIONED_BY_IDP
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

        // FIRST, before anything else and in particular before the existing-member return
        // below: a workspace IdP may only sign in the account it created itself. Keycloak can
        // link a workspace IdP to an account that already exists (same email, a password,
        // Google or GitHub account); that IdP is configured by a workspace ADMIN and asserts
        // whatever identity it likes, so accepting the link would let an admin sign in as any
        // member of the workspace. Linking SAML to a pre-existing account is therefore NOT a
        // supported flow, deliberately: such a person keeps signing in with their original
        // method, and a SAML login landing on their account is refused here (and the link is
        // removed from Keycloak by the caller).
        if (!isProvisionedBy(user, identityProviderAlias)) {
            UUID orgId = organizationIdOf(identityProviderAlias);
            throw reject(reportRejection, user, orgId, RejectionReason.NOT_PROVISIONED_BY_IDP,
                    "This account was not created through this workspace's SSO. Sign in with the method "
                            + "the account was created with.", null, true);
        }

        Organization organization = activeConnectionOrganization(identityProviderAlias, reportRejection, user);
        UUID orgId = organization.getId();

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

    /**
     * The admission rule for a SAML login that has NO app account yet, run BEFORE the account,
     * its FREE subscription or its credits are created: the connection is active, the asserted
     * email sits on a domain the workspace verified, and the workspace can take one more member.
     * The same rules {@link #ensureMembershipForIdentityProvider} applies at join time; running
     * them first means a refused login leaves nothing behind in the app.
     *
     * @throws SamlMembershipException when the login must be refused
     */
    @Transactional(readOnly = true)
    public void checkNewAccountAdmission(String email, String identityProviderAlias, boolean reportRejection) {
        if (!OrganizationSamlService.isOrganizationSamlAlias(identityProviderAlias)) {
            return;
        }
        Organization organization = activeConnectionOrganization(identityProviderAlias, reportRejection, null);
        UUID orgId = organization.getId();
        if (!domainService.isEmailOnVerifiedDomain(orgId, email)) {
            throw reject(reportRejection, null, orgId, RejectionReason.DOMAIN_NOT_VERIFIED,
                    "This email address is not on a domain verified for this workspace's SSO", null);
        }
        enforceTeamAdmission(reportRejection, null, orgId);
    }

    /**
     * Whether {@code user} is the account the workspace IdP {@code alias} created: a SAML account
     * whose recorded creating IdP is exactly this one. A password, Google or GitHub account, or a
     * SAML account created by another workspace's IdP, never is.
     */
    static boolean isProvisionedBy(User user, String alias) {
        return user != null
                && user.getAuthProvider() == AuthProvider.SAML
                && alias != null
                && alias.equals(user.getSamlIdpAlias());
    }

    private UUID organizationIdOf(String alias) {
        return samlRepository.findByIdpAlias(alias)
                .map(OrganizationSamlConnection::getOrganization)
                .map(Organization::getId)
                .orElse(null);
    }

    private Organization activeConnectionOrganization(String identityProviderAlias, boolean reportRejection, User user) {
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
        if (organization.getId() == null) {
            throw reject(reportRejection, user, null, RejectionReason.MISSING_ORGANIZATION,
                    "SAML SSO connection is missing an organization", null);
        }
        return organization;
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
        return reject(reportRejection, user, orgId, reason, message, cause, false);
    }

    private SamlMembershipException reject(boolean reportRejection, User user, UUID orgId, RejectionReason reason,
                                           String message, Throwable cause, boolean notProvisionedByIdp) {
        if (reportRejection) emit(user, orgId, AuthAnalyticsEmitter.SSO_OUTCOME_REJECTED,
                reason.name().toLowerCase(java.util.Locale.ROOT), null);
        if (notProvisionedByIdp) {
            return new SamlAccountNotProvisionedException(message);
        }
        return cause != null ? new SamlMembershipException(message, cause) : new SamlMembershipException(message);
    }

    /** Best-effort: analytics never changes the admission outcome. */
    private void emit(User user, UUID orgId, String outcome, String reason, OrganizationRole role) {
        if (analytics == null) return;
        try {
            analytics.ssoMemberJoined(user != null ? user.getId() : null,
                    orgId != null ? orgId.toString() : null, outcome, reason, role);
        } catch (Exception e) {
            log.debug("[saml] analytics dropped: {}", e.toString());
        }
    }
}
