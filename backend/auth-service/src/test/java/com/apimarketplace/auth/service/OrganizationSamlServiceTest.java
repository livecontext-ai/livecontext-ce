package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.AuthProvider;
import com.apimarketplace.auth.domain.Organization;
import com.apimarketplace.auth.domain.OrganizationAuditEvent;
import com.apimarketplace.auth.domain.OrganizationMember;
import com.apimarketplace.auth.domain.OrganizationRole;
import com.apimarketplace.auth.domain.OrganizationSamlConnection;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.dto.OrganizationSamlConnectionDto;
import com.apimarketplace.auth.dto.UpsertOrganizationSamlConnectionRequest;
import com.apimarketplace.auth.repository.OrganizationMemberRepository;
import com.apimarketplace.auth.repository.OrganizationRepository;
import com.apimarketplace.auth.repository.OrganizationSamlConnectionRepository;
import com.apimarketplace.auth.repository.OrganizationSsoDomainRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrganizationSamlService")
class OrganizationSamlServiceTest {

    private static final UUID ORG_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final Long ACTOR_ID = 42L;
    private static final String CERTIFICATE = "AQIDBA==";

    @Mock private OrganizationSamlConnectionRepository samlRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private OrganizationMemberRepository memberRepository;
    @Mock private OrganizationMemberService memberService;
    @Mock private OrganizationAuditService auditService;
    @Mock private OrganizationSsoDomainRepository domainRepository;
    @Mock private ObjectProvider<KeycloakSamlIdentityProviderClient> keycloakClientProvider;
    @Mock private KeycloakSamlIdentityProviderClient keycloakClient;

    private Organization organization;
    private OrganizationSamlService service;

    @BeforeEach
    void setUp() {
        User owner = new User("owner", "owner@example.com", AuthProvider.KEYCLOAK, "kc-owner");
        owner.setId(ACTOR_ID);

        organization = new Organization("Acme", "acme", false, owner);
        organization.setId(ORG_ID);

        when(keycloakClientProvider.getIfAvailable()).thenReturn(keycloakClient);
        service = new OrganizationSamlService(
                samlRepository,
                organizationRepository,
                memberRepository,
                memberService,
                auditService,
                domainRepository,
                keycloakClientProvider,
                "https://auth.example.com/realms/livecontext/");
    }

    @Test
    @DisplayName("ownerAdminCanConfigureSamlAndProvisionKeycloak")
    void ownerAdminCanConfigureSamlAndProvisionKeycloak() {
        stubMembership(OrganizationRole.ADMIN);
        stubTeamPlan(true);
        when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(organization));
        when(samlRepository.findByOrganization_Id(ORG_ID)).thenReturn(Optional.empty());
        when(samlRepository.save(any(OrganizationSamlConnection.class))).thenAnswer(inv -> inv.getArgument(0));

        OrganizationSamlConnectionDto dto = service.upsert(ORG_ID, ACTOR_ID, request(CERTIFICATE));

        assertThat(dto.configured()).isTrue();
        assertThat(dto.status()).isEqualTo("ACTIVE");
        assertThat(dto.idpAlias()).isEqualTo("org-aaaaaaaabbbbccccddddeeeeeeeeeeee-saml");
        assertThat(dto.ssoStartPath()).isEqualTo("/auth/sso?org=aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee&hint=org-aaaaaaaabbbbccccddddeeeeeeeeeeee-saml");
        assertThat(dto.assertionConsumerServiceUrl())
                .isEqualTo("https://auth.example.com/realms/livecontext/broker/org-aaaaaaaabbbbccccddddeeeeeeeeeeee-saml/endpoint");

        ArgumentCaptor<OrganizationSamlConnection> connectionCaptor = ArgumentCaptor.forClass(OrganizationSamlConnection.class);
        verify(keycloakClient).upsert(connectionCaptor.capture(), org.mockito.ArgumentMatchers.anyBoolean());
        assertThat(connectionCaptor.getValue().getX509Certificate()).isEqualTo(CERTIFICATE);
        assertThat(connectionCaptor.getValue().getStatus()).isEqualTo(OrganizationSamlConnection.Status.ACTIVE);
        verify(auditService).record(
                eq(ORG_ID),
                eq(ACTOR_ID),
                eq(OrganizationAuditEvent.Type.SAML_SSO_CONFIGURED),
                any());
    }

    @Test
    @DisplayName("blankCertificateKeepsExistingConnectionCertificate")
    void blankCertificateKeepsExistingConnectionCertificate() {
        OrganizationSamlConnection existing = existingConnection();
        existing.setX509Certificate(CERTIFICATE);

        stubMembership(OrganizationRole.OWNER);
        stubTeamPlan(true);
        when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(organization));
        when(samlRepository.findByOrganization_Id(ORG_ID)).thenReturn(Optional.of(existing));
        when(samlRepository.save(any(OrganizationSamlConnection.class))).thenAnswer(inv -> inv.getArgument(0));

        service.upsert(ORG_ID, ACTOR_ID, request("   "));

        ArgumentCaptor<OrganizationSamlConnection> connectionCaptor = ArgumentCaptor.forClass(OrganizationSamlConnection.class);
        verify(keycloakClient).upsert(connectionCaptor.capture(), org.mockito.ArgumentMatchers.anyBoolean());
        assertThat(connectionCaptor.getValue().getX509Certificate()).isEqualTo(CERTIFICATE);
    }

    @Test
    @DisplayName("memberCannotConfigureSamlConnection")
    void memberCannotConfigureSamlConnection() {
        stubMembership(OrganizationRole.MEMBER);

        assertThatThrownBy(() -> service.upsert(ORG_ID, ACTOR_ID, request(CERTIFICATE)))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Only OWNER or ADMIN");

        verify(memberService, never()).getTeamStatus(any());
        verify(keycloakClient, never()).upsert(any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("freePlanCannotConfigureSamlConnection")
    void freePlanCannotConfigureSamlConnection() {
        stubMembership(OrganizationRole.OWNER);
        stubTeamPlan(false);

        assertThatThrownBy(() -> service.upsert(ORG_ID, ACTOR_ID, request(CERTIFICATE)))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("Team or Enterprise");

        verify(organizationRepository, never()).findById(any());
        verify(keycloakClient, never()).upsert(any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("invalidHttpSsoUrlRejected")
    void invalidHttpSsoUrlRejected() {
        stubMembership(OrganizationRole.OWNER);
        stubTeamPlan(true);
        when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(organization));
        when(samlRepository.findByOrganization_Id(ORG_ID)).thenReturn(Optional.empty());

        UpsertOrganizationSamlConnectionRequest invalid = new UpsertOrganizationSamlConnectionRequest(
                "Acme SSO",
                "https://idp.example.com/metadata",
                "http://idp.example.com/sso",
                CERTIFICATE);

        assertThatThrownBy(() -> service.upsert(ORG_ID, ACTOR_ID, invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("https");

        verify(keycloakClient, never()).upsert(any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("keycloakProvisioningErrorPersistsErrorStatus")
    void keycloakProvisioningErrorPersistsErrorStatus() {
        stubMembership(OrganizationRole.OWNER);
        stubTeamPlan(true);
        when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(organization));
        when(samlRepository.findByOrganization_Id(ORG_ID)).thenReturn(Optional.empty());
        when(samlRepository.save(any(OrganizationSamlConnection.class))).thenAnswer(inv -> inv.getArgument(0));
        org.mockito.Mockito.doThrow(new RuntimeException("KC down")).when(keycloakClient).upsert(any(), org.mockito.ArgumentMatchers.anyBoolean());

        assertThatThrownBy(() -> service.upsert(ORG_ID, ACTOR_ID, request(CERTIFICATE)))
                .isInstanceOf(SamlProvisioningException.class)
                .hasMessage("KC down");

        ArgumentCaptor<OrganizationSamlConnection> connectionCaptor = ArgumentCaptor.forClass(OrganizationSamlConnection.class);
        verify(samlRepository).save(connectionCaptor.capture());
        assertThat(connectionCaptor.getValue().getStatus()).isEqualTo(OrganizationSamlConnection.Status.ERROR);
        assertThat(connectionCaptor.getValue().getLastError()).isEqualTo("KC down");
    }

    @Test
    @DisplayName("IdP stays DISABLED in Keycloak until the workspace has verified a domain, then is enabled on save")
    void identityProviderIsDisabledUntilADomainIsVerified() {
        stubMembership(OrganizationRole.OWNER);
        stubTeamPlan(true);
        when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(organization));
        when(samlRepository.findByOrganization_Id(ORG_ID)).thenReturn(Optional.empty());
        when(samlRepository.save(any(OrganizationSamlConnection.class))).thenAnswer(inv -> inv.getArgument(0));
        when(domainRepository.existsByOrganization_IdAndVerifiedAtIsNotNull(ORG_ID)).thenReturn(false, true);

        service.upsert(ORG_ID, ACTOR_ID, request(CERTIFICATE));
        service.upsert(ORG_ID, ACTOR_ID, request(CERTIFICATE));

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(keycloakClient);
        order.verify(keycloakClient).upsert(any(), eq(false));
        order.verify(keycloakClient).upsert(any(), eq(true));
    }

    @Test
    @DisplayName("the saved connection is always hidden from the platform login page, and the DTO no longer offers the choice")
    void savedConnectionIsAlwaysHiddenFromLoginPage() {
        OrganizationSamlConnection existing = existingConnection();
        existing.setX509Certificate(CERTIFICATE);
        existing.setHideOnLoginPage(false); // a row written before the flag was pinned
        stubMembership(OrganizationRole.OWNER);
        stubTeamPlan(true);
        when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(organization));
        when(samlRepository.findByOrganization_Id(ORG_ID)).thenReturn(Optional.of(existing));
        when(samlRepository.save(any(OrganizationSamlConnection.class))).thenAnswer(inv -> inv.getArgument(0));

        service.upsert(ORG_ID, ACTOR_ID, request(CERTIFICATE));

        assertThat(existing.isHideOnLoginPage()).isTrue();
        assertThat(java.util.Arrays.stream(UpsertOrganizationSamlConnectionRequest.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)).doesNotContain("hideOnLoginPage");
        assertThat(java.util.Arrays.stream(OrganizationSamlConnectionDto.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)).doesNotContain("hideOnLoginPage");
    }

    @Test
    @DisplayName("sync inside a transaction: Keycloak is called only after commit, never on rollback")
    void syncRunsAfterCommitOnly() {
        OrganizationSamlConnection connection = existingConnection();
        connection.setStatus(OrganizationSamlConnection.Status.ACTIVE);
        when(samlRepository.findByOrganization_Id(ORG_ID)).thenReturn(Optional.of(connection));
        stubTeamPlan(false);
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            service.syncIdentityProviderEnabled(ORG_ID);
            org.mockito.Mockito.verifyNoInteractions(keycloakClient);

            var syncs = org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations();
            assertThat(syncs).hasSize(1);
            syncs.get(0).afterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK);
            org.mockito.Mockito.verifyNoInteractions(keycloakClient);

            syncs.get(0).afterCommit();
            verify(keycloakClient).setEnabled(OrganizationSamlService.aliasFor(ORG_ID), false);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("sync: a soft-deleted workspace is disabled even on a Team plan with a verified domain")
    void syncDisablesDeletedWorkspace() {
        OrganizationSamlConnection connection = existingConnection();
        connection.setStatus(OrganizationSamlConnection.Status.ACTIVE);
        organization.setDeletedAt(java.time.LocalDateTime.now());
        when(samlRepository.findByOrganization_Id(ORG_ID)).thenReturn(Optional.of(connection));

        service.syncIdentityProviderEnabled(ORG_ID);

        verify(keycloakClient).setEnabled(OrganizationSamlService.aliasFor(ORG_ID), false);
    }

    @Test
    @DisplayName("sync: an ACTIVE connection on a Team plan with a verified domain is enabled")
    void syncEnablesWhenEveryConditionHolds() {
        OrganizationSamlConnection connection = existingConnection();
        connection.setStatus(OrganizationSamlConnection.Status.ACTIVE);
        when(samlRepository.findByOrganization_Id(ORG_ID)).thenReturn(Optional.of(connection));
        stubTeamPlan(true);
        when(domainRepository.existsByOrganization_IdAndVerifiedAtIsNotNull(ORG_ID)).thenReturn(true);

        service.syncIdentityProviderEnabled(ORG_ID);

        verify(keycloakClient).setEnabled(OrganizationSamlService.aliasFor(ORG_ID), true);
    }

    @Test
    @DisplayName("sync: a plan below Team disables the IdP (downgrade), whatever the domains")
    void syncDisablesBelowTeamPlan() {
        OrganizationSamlConnection connection = existingConnection();
        connection.setStatus(OrganizationSamlConnection.Status.ACTIVE);
        when(samlRepository.findByOrganization_Id(ORG_ID)).thenReturn(Optional.of(connection));
        stubTeamPlan(false);

        service.syncIdentityProviderEnabled(ORG_ID);

        verify(keycloakClient).setEnabled(OrganizationSamlService.aliasFor(ORG_ID), false);
    }

    @Test
    @DisplayName("sync: no verified domain left, or a connection not ACTIVE, disables the IdP")
    void syncDisablesWithoutVerifiedDomainOrActiveConnection() {
        OrganizationSamlConnection connection = existingConnection();
        connection.setStatus(OrganizationSamlConnection.Status.ACTIVE);
        when(samlRepository.findByOrganization_Id(ORG_ID)).thenReturn(Optional.of(connection));
        stubTeamPlan(true);
        when(domainRepository.existsByOrganization_IdAndVerifiedAtIsNotNull(ORG_ID)).thenReturn(false);
        service.syncIdentityProviderEnabled(ORG_ID);

        connection.setStatus(OrganizationSamlConnection.Status.ERROR);
        service.syncIdentityProviderEnabled(ORG_ID);

        verify(keycloakClient, org.mockito.Mockito.times(2)).setEnabled(OrganizationSamlService.aliasFor(ORG_ID), false);
    }

    @Test
    @DisplayName("sync: no connection means no Keycloak call, and a Keycloak failure is swallowed")
    void syncIsANoOpWithoutConnectionAndNeverThrows() {
        when(samlRepository.findByOrganization_Id(ORG_ID)).thenReturn(Optional.empty());
        service.syncIdentityProviderEnabled(ORG_ID);
        org.mockito.Mockito.verifyNoInteractions(keycloakClient);

        OrganizationSamlConnection connection = existingConnection();
        connection.setStatus(OrganizationSamlConnection.Status.ACTIVE);
        when(samlRepository.findByOrganization_Id(ORG_ID)).thenReturn(Optional.of(connection));
        stubTeamPlan(true);
        when(domainRepository.existsByOrganization_IdAndVerifiedAtIsNotNull(ORG_ID)).thenReturn(true);
        when(keycloakClient.setEnabled(any(), org.mockito.ArgumentMatchers.anyBoolean())).thenThrow(new RuntimeException("KC down"));

        service.syncIdentityProviderEnabled(ORG_ID);
    }

    private UpsertOrganizationSamlConnectionRequest request(String certificate) {
        return new UpsertOrganizationSamlConnectionRequest(
                "Acme SSO",
                "https://idp.example.com/metadata",
                "https://idp.example.com/sso",
                certificate);
    }

    private OrganizationSamlConnection existingConnection() {
        OrganizationSamlConnection connection = new OrganizationSamlConnection(organization, OrganizationSamlService.aliasFor(ORG_ID));
        connection.setId(UUID.fromString("11111111-2222-3333-4444-555555555555"));
        connection.setDisplayName("Acme SSO");
        connection.setIdpEntityId("https://idp.example.com/metadata");
        connection.setSsoUrl("https://idp.example.com/sso");
        connection.setHideOnLoginPage(true);
        return connection;
    }

    private void stubMembership(OrganizationRole role) {
        User actor = new User("actor", "actor@example.com", AuthProvider.KEYCLOAK, "kc-actor");
        actor.setId(ACTOR_ID);
        OrganizationMember member = new OrganizationMember(organization, actor, role, true);
        when(memberRepository.findActiveByOrganizationIdAndUserId(ORG_ID, ACTOR_ID)).thenReturn(Optional.of(member));
    }

    private void stubTeamPlan(boolean supportsTeam) {
        when(memberService.getTeamStatus(ORG_ID))
                .thenReturn(new OrganizationMemberService.TeamStatus(supportsTeam, supportsTeam ? 10 : 1, 1, 0, supportsTeam ? "TEAM" : "FREE"));
    }

    @Test
    @DisplayName("isOrganizationSamlAlias accepts only the exact alias shape aliasFor produces")
    void isOrganizationSamlAliasMatchesOnlyTheGeneratedShape() {
        java.util.UUID orgId = java.util.UUID.fromString("00000000-0000-0000-0000-000000000000");
        assertThat(OrganizationSamlService.isOrganizationSamlAlias(OrganizationSamlService.aliasFor(orgId))).isTrue();
        assertThat(OrganizationSamlService.isOrganizationSamlAlias("org-E1375D5DA0854513987145620539DFA1-saml")).isTrue();
        assertThat(OrganizationSamlService.isOrganizationSamlAlias(null)).isFalse();
        assertThat(OrganizationSamlService.isOrganizationSamlAlias("google")).isFalse();
        assertThat(OrganizationSamlService.isOrganizationSamlAlias("org-e1375d5da0854513987145620539dfa-saml")).isFalse();
        assertThat(OrganizationSamlService.isOrganizationSamlAlias("org-e1375d5da0854513987145620539dfa1-saml-x")).isFalse();
        assertThat(OrganizationSamlService.isOrganizationSamlAlias("org-00000000-0000-0000-0000-000000000000-saml")).isFalse();
    }
}
