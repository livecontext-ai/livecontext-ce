package com.apimarketplace.auth.service;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrganizationSamlLoginService")
class OrganizationSamlLoginServiceTest {

    private static final UUID ORG_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final String ALIAS = "org-aaaaaaaabbbbccccddddeeeeeeeeeeee-saml";

    @Mock private OrganizationSamlConnectionRepository samlRepository;
    @Mock private OrganizationMemberRepository memberRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private OrganizationMemberService memberService;
    @Mock private OrganizationAuditService auditService;
    @Mock private OrganizationSsoDomainService domainService;

    private Organization organization;
    private User user;
    private OrganizationSamlLoginService service;

    @BeforeEach
    void setUp() {
        User owner = new User("owner", "owner@example.com", AuthProvider.KEYCLOAK, "kc-owner");
        owner.setId(1L);
        organization = new Organization("Acme", "acme", false, owner);
        organization.setId(ORG_ID);

        user = new User("member", "member@example.com", AuthProvider.KEYCLOAK, "kc-member");
        user.setId(42L);

        service = new OrganizationSamlLoginService(
                samlRepository,
                memberRepository,
                organizationRepository,
                memberService,
                auditService,
                domainService);
        // The existing scenarios are about membership, so their email is on a verified domain.
        org.mockito.Mockito.lenient()
                .when(domainService.isEmailOnVerifiedDomain(ORG_ID, "member@example.com")).thenReturn(true);
    }

    @Test
    @DisplayName("a NEW SAML member whose email is not on a verified domain is refused before the workspace is locked")
    void offDomainEmailCannotJoin() {
        // The IdP is the workspace admin's and asserts any email; Keycloak trusts it. Without this
        // rule a Team workspace could mint a member in the name of user@example.com.
        when(samlRepository.findByIdpAlias(ALIAS)).thenReturn(Optional.of(activeConnection()));
        User outsider = new User("outsider", "user@example.com", AuthProvider.SAML, "kc-outsider");
        outsider.setId(77L);
        when(memberRepository.findActiveByOrganizationIdAndUserId(ORG_ID, 77L)).thenReturn(Optional.empty());
        when(domainService.isEmailOnVerifiedDomain(ORG_ID, "user@example.com")).thenReturn(false);

        assertThatThrownBy(() -> service.ensureMembershipForIdentityProvider(outsider, ALIAS))
                .isInstanceOf(SamlMembershipException.class)
                .hasMessageContaining("not on a domain verified");

        verify(organizationRepository, never()).findByIdForUpdate(any());
        verify(memberRepository, never()).save(any());
        verify(auditService, never()).record(any(), any(), any(), any());
    }

    @Test
    @DisplayName("an EXISTING member keeps signing in through SAML even though the workspace has verified no domain")
    void existingMemberIsNotLockedOutByTheDomainRule() {
        // The domain rule shipped after SAML did. Applied to members too, it would lock every
        // workspace configured before it out of its own SSO on deploy.
        OrganizationMember membership = new OrganizationMember(organization, user, OrganizationRole.MEMBER, true);
        when(samlRepository.findByIdpAlias(ALIAS)).thenReturn(Optional.of(activeConnection()));
        when(memberRepository.findActiveByOrganizationIdAndUserId(ORG_ID, 42L)).thenReturn(Optional.of(membership));
        org.mockito.Mockito.lenient()
                .when(domainService.isEmailOnVerifiedDomain(ORG_ID, "member@example.com")).thenReturn(false);

        assertThat(service.ensureMembershipForIdentityProvider(user, ALIAS)).contains(ORG_ID);
        verify(domainService, never()).isEmailOnVerifiedDomain(any(), any());
        verify(memberRepository, never()).save(any());
    }

    @Test
    @DisplayName("samlLoginAddsMissingWorkspaceMembership")
    void samlLoginAddsMissingWorkspaceMembership() {
        OrganizationSamlConnection connection = activeConnection();
        when(samlRepository.findByIdpAlias(ALIAS)).thenReturn(Optional.of(connection));
        when(memberRepository.findActiveByOrganizationIdAndUserId(ORG_ID, 42L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty());
        when(organizationRepository.findByIdForUpdate(ORG_ID)).thenReturn(Optional.of(organization));
        stubTeamStatus(true, 10, 1, 0);
        when(memberRepository.findActiveDefaultByUserId(42L)).thenReturn(Optional.empty());
        when(memberRepository.save(any(OrganizationMember.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<UUID> result = service.ensureMembershipForIdentityProvider(user, ALIAS);

        assertThat(result).contains(ORG_ID);
        ArgumentCaptor<OrganizationMember> memberCaptor = ArgumentCaptor.forClass(OrganizationMember.class);
        verify(memberRepository).save(memberCaptor.capture());
        assertThat(memberCaptor.getValue().getOrganization()).isEqualTo(organization);
        assertThat(memberCaptor.getValue().getUser()).isEqualTo(user);
        assertThat(memberCaptor.getValue().getRole()).isEqualTo(OrganizationRole.MEMBER);
        assertThat(memberCaptor.getValue().isDefault()).isTrue();
        verify(auditService).record(eq(ORG_ID), eq(42L), eq(OrganizationAuditEvent.Type.SAML_SSO_MEMBER_JOINED), any());
        var ordered = inOrder(organizationRepository, memberService);
        ordered.verify(organizationRepository).findByIdForUpdate(ORG_ID);
        ordered.verify(memberService).getTeamStatus(ORG_ID);
    }

    @Test
    @DisplayName("a rejected membership insert is refused outright, it does not re-read inside the dead transaction")
    void aRejectedMembershipInsertIsRefusedWithoutReReading() {
        OrganizationSamlConnection connection = activeConnection();
        when(samlRepository.findByIdpAlias(ALIAS)).thenReturn(Optional.of(connection));
        when(memberRepository.findActiveByOrganizationIdAndUserId(ORG_ID, 42L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty());
        when(organizationRepository.findByIdForUpdate(ORG_ID)).thenReturn(Optional.of(organization));
        stubTeamStatus(true, 10, 1, 0);
        when(memberRepository.findActiveDefaultByUserId(42L)).thenReturn(Optional.empty());
        when(memberRepository.save(any(OrganizationMember.class)))
                .thenThrow(new DataIntegrityViolationException("uq_org_member"));

        assertThatThrownBy(() -> service.ensureMembershipForIdentityProvider(user, ALIAS))
                .isInstanceOf(SamlMembershipException.class)
                .hasMessageContaining("Could not join SAML workspace");

        // The insert is reached only after the organization row is locked and membership
        // re-checked under that lock, so a duplicate here is not a lost race, it is a surprise.
        // The recovery this used to attempt (a THIRD read, to return the winner's row) could
        // never have run: the violation has already put the transaction in PostgreSQL's ERROR
        // state, so that read comes back 25P02 and the caller sees that instead of the answer
        // the catch promised. Exactly two reads, and a refusal.
        verify(memberRepository, times(2)).findActiveByOrganizationIdAndUserId(ORG_ID, 42L);
        verify(auditService, never()).record(any(), any(), any(), any());
    }

    @Test
    @DisplayName("inactiveSamlConnectionDoesNotAddWorkspaceMembership")
    void inactiveSamlConnectionDoesNotAddWorkspaceMembership() {
        OrganizationSamlConnection connection = activeConnection();
        connection.setStatus(OrganizationSamlConnection.Status.ERROR);
        when(samlRepository.findByIdpAlias(ALIAS)).thenReturn(Optional.of(connection));

        assertThatThrownBy(() -> service.ensureMembershipForIdentityProvider(user, ALIAS))
                .isInstanceOf(SamlMembershipException.class)
                .hasMessageContaining("not active");

        verify(memberRepository, never()).save(any());
        verify(auditService, never()).record(any(), any(), any(), any());
    }

    @Test
    @DisplayName("samlAdmissionRechecksMembershipAfterOrganizationLock")
    void samlAdmissionRechecksMembershipAfterOrganizationLock() {
        OrganizationMember membership = new OrganizationMember(organization, user, OrganizationRole.MEMBER, false);
        when(samlRepository.findByIdpAlias(ALIAS)).thenReturn(Optional.of(activeConnection()));
        when(memberRepository.findActiveByOrganizationIdAndUserId(ORG_ID, 42L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(membership));
        when(organizationRepository.findByIdForUpdate(ORG_ID)).thenReturn(Optional.of(organization));

        Optional<UUID> result = service.ensureMembershipForIdentityProvider(user, ALIAS);

        assertThat(result).contains(ORG_ID);
        verify(memberService, never()).getTeamStatus(any());
        verify(memberRepository, never()).save(any());
    }

    @Test
    @DisplayName("fullWorkspaceCannotAddSamlMember")
    void fullWorkspaceCannotAddSamlMember() {
        when(samlRepository.findByIdpAlias(ALIAS)).thenReturn(Optional.of(activeConnection()));
        when(memberRepository.findActiveByOrganizationIdAndUserId(ORG_ID, 42L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty());
        when(organizationRepository.findByIdForUpdate(ORG_ID)).thenReturn(Optional.of(organization));
        stubTeamStatus(true, 2, 2, 0);

        assertThatThrownBy(() -> service.ensureMembershipForIdentityProvider(user, ALIAS))
                .isInstanceOf(SamlMembershipException.class)
                .hasMessageContaining("Member limit reached");

        verify(memberRepository, never()).save(any());
        verify(auditService, never()).record(any(), any(), any(), any());
    }

    @Test
    @DisplayName("freeWorkspaceCannotAddSamlMember")
    void freeWorkspaceCannotAddSamlMember() {
        when(samlRepository.findByIdpAlias(ALIAS)).thenReturn(Optional.of(activeConnection()));
        when(memberRepository.findActiveByOrganizationIdAndUserId(ORG_ID, 42L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty());
        when(organizationRepository.findByIdForUpdate(ORG_ID)).thenReturn(Optional.of(organization));
        stubTeamStatus(false, 1, 1, 0);

        assertThatThrownBy(() -> service.ensureMembershipForIdentityProvider(user, ALIAS))
                .isInstanceOf(SamlMembershipException.class)
                .hasMessageContaining("Team or Enterprise");

        verify(memberRepository, never()).save(any());
        verify(auditService, never()).record(any(), any(), any(), any());
    }

    private OrganizationSamlConnection activeConnection() {
        OrganizationSamlConnection connection = new OrganizationSamlConnection(organization, ALIAS);
        connection.setStatus(OrganizationSamlConnection.Status.ACTIVE);
        return connection;
    }

    private void stubTeamStatus(boolean supportsTeam, int maxMembers, int currentMembers, int pendingInvitations) {
        when(memberService.getTeamStatus(ORG_ID))
                .thenReturn(new OrganizationMemberService.TeamStatus(
                        supportsTeam,
                        maxMembers,
                        currentMembers,
                        pendingInvitations,
                        supportsTeam ? "TEAM" : "FREE"));
    }

    // ── sso_member_joined analytics ────────────────────────────────────────────

    private com.apimarketplace.auth.analytics.AuthAnalyticsEmitter wireAnalytics() {
        com.apimarketplace.auth.analytics.AuthAnalyticsEmitter analytics =
                org.mockito.Mockito.mock(com.apimarketplace.auth.analytics.AuthAnalyticsEmitter.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "analytics", analytics);
        return analytics;
    }

    @Test
    @DisplayName("sso_member_joined: a new member is counted as joined with its role and workspace")
    void analyticsJoined() {
        var analytics = wireAnalytics();
        when(samlRepository.findByIdpAlias(ALIAS)).thenReturn(Optional.of(activeConnection()));
        when(memberRepository.findActiveByOrganizationIdAndUserId(ORG_ID, 42L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty());
        when(organizationRepository.findByIdForUpdate(ORG_ID)).thenReturn(Optional.of(organization));
        stubTeamStatus(true, 10, 1, 0);
        when(memberRepository.findActiveDefaultByUserId(42L)).thenReturn(Optional.empty());
        when(memberRepository.save(any(OrganizationMember.class))).thenAnswer(inv -> inv.getArgument(0));

        service.ensureMembershipForIdentityProvider(user, ALIAS);

        verify(analytics).ssoMemberJoined(42L, ORG_ID.toString(), "joined", null, OrganizationRole.MEMBER);
    }

    @Test
    @DisplayName("sso_member_joined: an existing member is NOT counted (the check runs per resolution, not per sign-in)")
    void analyticsExistingMemberNotCounted() {
        var analytics = wireAnalytics();
        OrganizationMember membership = new OrganizationMember(organization, user, OrganizationRole.MEMBER, true);
        when(samlRepository.findByIdpAlias(ALIAS)).thenReturn(Optional.of(activeConnection()));
        when(memberRepository.findActiveByOrganizationIdAndUserId(ORG_ID, 42L)).thenReturn(Optional.of(membership));

        service.ensureMembershipForIdentityProvider(user, ALIAS);

        verifyNoInteractions(analytics);
    }

    @Test
    @DisplayName("sso_member_joined: each refusal is counted with its reason BEFORE the exception, and still throws")
    void analyticsRejections() {
        var analytics = wireAnalytics();

        // Unknown alias: no connection, so no workspace to attribute.
        when(samlRepository.findByIdpAlias(ALIAS)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.ensureMembershipForIdentityProvider(user, ALIAS))
                .isInstanceOf(SamlMembershipException.class);
        verify(analytics).ssoMemberJoined(42L, null, "rejected", "connection_inactive", null);

        // Off-domain email.
        when(samlRepository.findByIdpAlias(ALIAS)).thenReturn(Optional.of(activeConnection()));
        when(memberRepository.findActiveByOrganizationIdAndUserId(ORG_ID, 42L)).thenReturn(Optional.empty());
        when(domainService.isEmailOnVerifiedDomain(ORG_ID, "member@example.com")).thenReturn(false);
        assertThatThrownBy(() -> service.ensureMembershipForIdentityProvider(user, ALIAS))
                .isInstanceOf(SamlMembershipException.class);
        verify(analytics).ssoMemberJoined(42L, ORG_ID.toString(), "rejected", "domain_not_verified", null);

        // Plan without teams, then a full workspace.
        when(domainService.isEmailOnVerifiedDomain(ORG_ID, "member@example.com")).thenReturn(true);
        when(organizationRepository.findByIdForUpdate(ORG_ID)).thenReturn(Optional.of(organization));
        when(memberService.getTeamStatus(ORG_ID))
                .thenReturn(new OrganizationMemberService.TeamStatus(false, 1, 1, 0, "FREE"))
                .thenReturn(new OrganizationMemberService.TeamStatus(true, 2, 2, 0, "TEAM"));
        assertThatThrownBy(() -> service.ensureMembershipForIdentityProvider(user, ALIAS))
                .hasMessageContaining("Team or Enterprise");
        verify(analytics).ssoMemberJoined(42L, ORG_ID.toString(), "rejected", "plan_not_team", null);
        assertThatThrownBy(() -> service.ensureMembershipForIdentityProvider(user, ALIAS))
                .hasMessageContaining("Member limit reached");
        verify(analytics).ssoMemberJoined(42L, ORG_ID.toString(), "rejected", "member_limit", null);

        // The insert itself refused.
        when(memberService.getTeamStatus(ORG_ID)).thenReturn(new OrganizationMemberService.TeamStatus(true, 10, 1, 0, "TEAM"));
        when(memberRepository.findActiveDefaultByUserId(42L)).thenReturn(Optional.empty());
        when(memberRepository.save(any(OrganizationMember.class)))
                .thenThrow(new DataIntegrityViolationException("uq_org_member"));
        assertThatThrownBy(() -> service.ensureMembershipForIdentityProvider(user, ALIAS))
                .hasMessageContaining("Could not join SAML workspace")
                .hasCauseInstanceOf(DataIntegrityViolationException.class);
        verify(analytics).ssoMemberJoined(42L, ORG_ID.toString(), "rejected", "save_failed", null);
    }

    @Test
    @DisplayName("sso_member_joined: a failing emitter changes nothing about the admission")
    void analyticsFailureDoesNotChangeOutcome() {
        var analytics = wireAnalytics();
        org.mockito.Mockito.doThrow(new RuntimeException("posthog down"))
                .when(analytics).ssoMemberJoined(any(), any(), any(), any(), any());
        OrganizationMember membership = new OrganizationMember(organization, user, OrganizationRole.MEMBER, true);
        when(samlRepository.findByIdpAlias(ALIAS)).thenReturn(Optional.of(activeConnection()));
        when(memberRepository.findActiveByOrganizationIdAndUserId(ORG_ID, 42L)).thenReturn(Optional.of(membership));

        assertThat(service.ensureMembershipForIdentityProvider(user, ALIAS)).contains(ORG_ID);

        // And a refusal still surfaces as the refusal, not as the emitter's error.
        OrganizationSamlConnection inactive = activeConnection();
        inactive.setStatus(OrganizationSamlConnection.Status.ERROR);
        when(samlRepository.findByIdpAlias(ALIAS)).thenReturn(Optional.of(inactive));
        assertThatThrownBy(() -> service.ensureMembershipForIdentityProvider(user, ALIAS))
                .isInstanceOf(SamlMembershipException.class)
                .hasMessageContaining("not active");
    }

    @Test
    @DisplayName("sso_member_joined: a non-SAML identity provider emits nothing")
    void analyticsNotSaml() {
        var analytics = wireAnalytics();

        service.ensureMembershipForIdentityProvider(user, "google");

        org.mockito.Mockito.verifyNoInteractions(analytics);
    }

    @Test
    @DisplayName("sso_member_joined: a repeat resolution (reportRejection=false) still refuses but reports nothing")
    void analyticsRejectionNotReportedOnRepeatResolution() {
        var analytics = wireAnalytics();
        when(samlRepository.findByIdpAlias(ALIAS)).thenReturn(Optional.of(activeConnection()));
        when(memberRepository.findActiveByOrganizationIdAndUserId(ORG_ID, 42L)).thenReturn(Optional.empty());
        when(domainService.isEmailOnVerifiedDomain(ORG_ID, "member@example.com")).thenReturn(false);

        assertThatThrownBy(() -> service.ensureMembershipForIdentityProvider(user, ALIAS, false))
                .isInstanceOf(SamlMembershipException.class)
                .hasMessageContaining("not on a domain verified");

        verifyNoInteractions(analytics);
    }

    @Test
    @DisplayName("sso_member_joined: a join is reported even off a real sign-in (a membership is created once)")
    void analyticsJoinReportedRegardlessOfFlag() {
        var analytics = wireAnalytics();
        when(samlRepository.findByIdpAlias(ALIAS)).thenReturn(Optional.of(activeConnection()));
        when(memberRepository.findActiveByOrganizationIdAndUserId(ORG_ID, 42L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty());
        when(organizationRepository.findByIdForUpdate(ORG_ID)).thenReturn(Optional.of(organization));
        stubTeamStatus(true, 10, 1, 0);
        when(memberRepository.findActiveDefaultByUserId(42L)).thenReturn(Optional.empty());
        when(memberRepository.save(any(OrganizationMember.class))).thenAnswer(inv -> inv.getArgument(0));

        service.ensureMembershipForIdentityProvider(user, ALIAS, false);

        verify(analytics).ssoMemberJoined(42L, ORG_ID.toString(), "joined", null, OrganizationRole.MEMBER);
    }
}
