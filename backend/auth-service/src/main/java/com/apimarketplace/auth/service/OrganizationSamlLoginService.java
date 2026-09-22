package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.Organization;
import com.apimarketplace.auth.domain.OrganizationAuditEvent;
import com.apimarketplace.auth.domain.OrganizationMember;
import com.apimarketplace.auth.domain.OrganizationRole;
import com.apimarketplace.auth.domain.OrganizationSamlConnection;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.OrganizationMemberRepository;
import com.apimarketplace.auth.repository.OrganizationRepository;
import com.apimarketplace.auth.repository.OrganizationSamlConnectionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class OrganizationSamlLoginService {

    private final OrganizationSamlConnectionRepository samlRepository;
    private final OrganizationMemberRepository memberRepository;
    private final OrganizationRepository organizationRepository;
    private final OrganizationMemberService memberService;
    private final OrganizationAuditService auditService;

    public OrganizationSamlLoginService(
            OrganizationSamlConnectionRepository samlRepository,
            OrganizationMemberRepository memberRepository,
            OrganizationRepository organizationRepository,
            OrganizationMemberService memberService,
            OrganizationAuditService auditService
    ) {
        this.samlRepository = samlRepository;
        this.memberRepository = memberRepository;
        this.organizationRepository = organizationRepository;
        this.memberService = memberService;
        this.auditService = auditService;
    }

    @Transactional
    public Optional<UUID> ensureMembershipForIdentityProvider(User user, String identityProviderAlias) {
        if (user == null || user.getId() == null || identityProviderAlias == null || identityProviderAlias.isBlank()) {
            return Optional.empty();
        }
        if (!isOrganizationSamlAlias(identityProviderAlias)) {
            return Optional.empty();
        }

        OrganizationSamlConnection connection = samlRepository.findByIdpAlias(identityProviderAlias)
                .orElseThrow(() -> new SamlMembershipException("SAML SSO connection is not active"));
        if (connection.getStatus() != OrganizationSamlConnection.Status.ACTIVE
                || connection.getOrganization() == null
                || connection.getOrganization().isDeleted()) {
            throw new SamlMembershipException("SAML SSO connection is not active");
        }

        Organization organization = connection.getOrganization();
        UUID orgId = organization.getId();
        if (orgId == null) {
            throw new SamlMembershipException("SAML SSO connection is missing an organization");
        }

        Optional<OrganizationMember> existing = memberRepository.findActiveByOrganizationIdAndUserId(orgId, user.getId());
        if (existing.isPresent()) {
            return Optional.of(orgId);
        }

        organization = lockOrganizationForAdmission(orgId);
        existing = memberRepository.findActiveByOrganizationIdAndUserId(orgId, user.getId());
        if (existing.isPresent()) {
            return Optional.of(orgId);
        }

        enforceTeamAdmission(orgId);
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
            throw new SamlMembershipException("Could not join SAML workspace", e);
        }

        auditService.record(orgId, user.getId(), OrganizationAuditEvent.Type.SAML_SSO_MEMBER_JOINED,
                Map.of("idpAlias", identityProviderAlias, "role", OrganizationRole.MEMBER.name()));
        return Optional.of(orgId);
    }

    private Organization lockOrganizationForAdmission(UUID orgId) {
        Organization organization = organizationRepository.findByIdForUpdate(orgId)
                .orElseThrow(() -> new SamlMembershipException("SAML SSO connection is not active"));
        if (organization.isDeleted()) {
            throw new SamlMembershipException("SAML SSO connection is not active");
        }
        return organization;
    }

    private void enforceTeamAdmission(UUID orgId) {
        OrganizationMemberService.TeamStatus status = memberService.getTeamStatus(orgId);
        if (!status.supportsTeam()) {
            throw new SamlMembershipException("SAML SSO requires a Team or Enterprise plan");
        }
        if (!status.canInvite()) {
            throw new SamlMembershipException("Member limit reached (" + status.maxMembers()
                    + "). Upgrade your plan for more members.");
        }
    }

    private boolean isOrganizationSamlAlias(String alias) {
        return alias.matches("^org-[0-9a-fA-F]{32}-saml$");
    }
}
