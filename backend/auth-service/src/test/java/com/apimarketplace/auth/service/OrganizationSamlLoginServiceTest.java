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

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

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
                auditService);
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
}
