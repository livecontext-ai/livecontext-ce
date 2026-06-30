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
        verify(keycloakClient).upsert(connectionCaptor.capture());
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
        verify(keycloakClient).upsert(connectionCaptor.capture());
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
        verify(keycloakClient, never()).upsert(any());
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
        verify(keycloakClient, never()).upsert(any());
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
                CERTIFICATE,
                true);

        assertThatThrownBy(() -> service.upsert(ORG_ID, ACTOR_ID, invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("https");

        verify(keycloakClient, never()).upsert(any());
    }

    @Test
    @DisplayName("keycloakProvisioningErrorPersistsErrorStatus")
    void keycloakProvisioningErrorPersistsErrorStatus() {
        stubMembership(OrganizationRole.OWNER);
        stubTeamPlan(true);
        when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(organization));
        when(samlRepository.findByOrganization_Id(ORG_ID)).thenReturn(Optional.empty());
        when(samlRepository.save(any(OrganizationSamlConnection.class))).thenAnswer(inv -> inv.getArgument(0));
        org.mockito.Mockito.doThrow(new RuntimeException("KC down")).when(keycloakClient).upsert(any());

        assertThatThrownBy(() -> service.upsert(ORG_ID, ACTOR_ID, request(CERTIFICATE)))
                .isInstanceOf(SamlProvisioningException.class)
                .hasMessage("KC down");

        ArgumentCaptor<OrganizationSamlConnection> connectionCaptor = ArgumentCaptor.forClass(OrganizationSamlConnection.class);
        verify(samlRepository).save(connectionCaptor.capture());
        assertThat(connectionCaptor.getValue().getStatus()).isEqualTo(OrganizationSamlConnection.Status.ERROR);
        assertThat(connectionCaptor.getValue().getLastError()).isEqualTo("KC down");
    }

    private UpsertOrganizationSamlConnectionRequest request(String certificate) {
        return new UpsertOrganizationSamlConnectionRequest(
                "Acme SSO",
                "https://idp.example.com/metadata",
                "https://idp.example.com/sso",
                certificate,
                true);
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
}
