package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.AuthProvider;
import com.apimarketplace.auth.domain.Organization;
import com.apimarketplace.auth.domain.OrganizationMember;
import com.apimarketplace.auth.domain.OrganizationRole;
import com.apimarketplace.auth.domain.OrganizationSamlConnection;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.dto.UserResolutionResponse;
import com.apimarketplace.auth.repository.BillingCustomerRepository;
import com.apimarketplace.auth.repository.OrganizationMemberRepository;
import com.apimarketplace.auth.repository.OrganizationRepository;
import com.apimarketplace.auth.repository.OrganizationSamlConnectionRepository;
import com.apimarketplace.auth.repository.PlanRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.validation.AgeValidator;
import com.apimarketplace.auth.validation.UsernameValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Workspace SAML hardening, end to end through {@link UserResolutionService} with the REAL
 * {@link OrganizationSamlLoginService} (its repositories mocked), so the tests exercise the
 * decision the gateway actually gets, not a mocked admission.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserResolutionService: workspace SAML hardening")
class UserResolutionServiceSamlHardeningTest {

    private static final UUID ORG_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final String ALIAS = "org-aaaaaaaabbbbccccddddeeeeeeeeeeee-saml";
    private static final String OTHER_ALIAS = "org-11111111222233334444555555555555-saml";
    private static final String SUB = "f47ac10b-58cc-4372-a567-0e02b2c3d479";

    @Mock private UserRepository userRepository;
    @Mock private UsernameValidator usernameValidator;
    @Mock private AgeValidator ageValidator;
    @Mock private OnboardingService onboardingService;
    @Mock private OrganizationService organizationService;
    @Mock private CreditService creditService;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private BillingCustomerRepository billingCustomerRepository;
    @Mock private PlanRepository planRepository;
    @Mock private FreeSubscriptionProvisioner freeSubscriptionProvisioner;
    @Mock private CreditAttributionService creditAttributionService;
    @Mock private KeycloakSamlIdentityProviderClient keycloakClient;

    @Mock private OrganizationSamlConnectionRepository samlRepository;
    @Mock private OrganizationMemberRepository memberRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private OrganizationMemberService memberService;
    @Mock private OrganizationAuditService auditService;
    @Mock private OrganizationSsoDomainService domainService;

    private UserResolutionService service;
    private Organization organization;

    @BeforeEach
    void setUp() {
        service = new UserResolutionService(userRepository, creditService, usernameValidator, ageValidator,
                onboardingService, organizationService, subscriptionRepository, billingCustomerRepository,
                planRepository, creditAttributionService, new PlanStorageQuotaSyncer(null, null),
                freeSubscriptionProvisioner);
        ReflectionTestUtils.setField(service, "self", service);
        ReflectionTestUtils.setField(service, "samlLoginService", new OrganizationSamlLoginService(
                samlRepository, memberRepository, organizationRepository, memberService, auditService, domainService));
        ReflectionTestUtils.setField(service, "samlIdentityProviderClient", keycloakClient);
        lenient().when(userRepository.updateLastLoginIfStale(anyLong(), any(), any())).thenReturn(1);

        User owner = new User("owner", "owner@acme.com", AuthProvider.KEYCLOAK, "kc-owner");
        owner.setId(1L);
        organization = new Organization("Acme", "acme", false, owner);
        organization.setId(ORG_ID);
        OrganizationSamlConnection connection = new OrganizationSamlConnection(organization, ALIAS);
        connection.setStatus(OrganizationSamlConnection.Status.ACTIVE);
        lenient().when(samlRepository.findByIdpAlias(ALIAS)).thenReturn(Optional.of(connection));
        lenient().when(memberService.getTeamStatus(ORG_ID))
                .thenReturn(new OrganizationMemberService.TeamStatus(true, 10, 1, 0, "TEAM"));
    }

    @Test
    @DisplayName("takeover: a SAML token resolving to an existing PASSWORD account that is a member is refused, and the link is released")
    void samlTokenOnExistingPasswordMemberIsRefused() {
        // Keycloak linked the workspace admin's IdP to the member's own account (same email): the
        // token's sub is the member's Keycloak id. Before the fix the existing-member return made
        // this resolve to the member's full account.
        User victim = user(7L, "victim@acme.com", AuthProvider.KEYCLOAK, null);
        when(userRepository.findByProviderId(SUB)).thenReturn(Optional.of(victim));
        lenient().when(memberRepository.findActiveByOrganizationIdAndUserId(ORG_ID, 7L))
                .thenReturn(Optional.of(new OrganizationMember(organization, victim, OrganizationRole.MEMBER, true)));

        UserResolutionResponse response = service.resolveUser(SUB, jwt("victim@acme.com", ALIAS));

        assertThat(response).as("the workspace IdP must never sign in as an account it did not create").isNull();
        // An app account sits on this Keycloak user: unlink and log out, never delete.
        verify(keycloakClient).releaseRefusedBrokeredUser(SUB, ALIAS, false);
        verify(freeSubscriptionProvisioner, never()).provisionIfMissing(any());
        verify(memberRepository, never()).save(any());
    }

    @Test
    @DisplayName("takeover: a Google account reached through a workspace IdP is refused as well")
    void samlTokenOnGoogleAccountIsRefused() {
        User google = user(8L, "someone@acme.com", AuthProvider.GOOGLE, null);
        when(userRepository.findByProviderId(SUB)).thenReturn(Optional.of(google));

        assertThat(service.resolveUser(SUB, jwt("someone@acme.com", ALIAS))).isNull();
        verify(keycloakClient).releaseRefusedBrokeredUser(SUB, ALIAS, false);
    }

    @Test
    @DisplayName("the account a workspace IdP created still signs in through it")
    void samlProvisionedMemberStillResolves() {
        User member = user(9L, "member@acme.com", AuthProvider.SAML, ALIAS);
        when(userRepository.findByProviderId(SUB)).thenReturn(Optional.of(member));
        when(memberRepository.findActiveByOrganizationIdAndUserId(ORG_ID, 9L))
                .thenReturn(Optional.of(new OrganizationMember(organization, member, OrganizationRole.MEMBER, true)));

        assertThat(service.resolveUser(SUB, jwt("member@acme.com", ALIAS))).isNotNull();
        verifyNoInteractions(keycloakClient);
    }

    @Test
    @DisplayName("reservation: a NEW SAML login off the verified domains creates no account, subscription or credit, and releases the Keycloak user")
    void newSamlLoginOffDomainCreatesNothing() {
        when(userRepository.findByProviderId(SUB)).thenReturn(Optional.empty());
        when(domainService.isEmailOnVerifiedDomain(ORG_ID, "ceo@competitor.com")).thenReturn(false);

        UserResolutionResponse response = service.resolveUser(SUB, jwt("ceo@competitor.com", ALIAS));

        assertThat(response).isNull();
        verify(userRepository, never()).save(any());
        verify(freeSubscriptionProvisioner, never()).provisionIfMissing(any());
        verifyNoInteractions(creditAttributionService);
        verify(keycloakClient).releaseRefusedBrokeredUser(SUB, ALIAS, true);
    }

    @Test
    @DisplayName("reservation: a NEW SAML login on a workspace below Team creates nothing either")
    void newSamlLoginBelowTeamCreatesNothing() {
        when(userRepository.findByProviderId(SUB)).thenReturn(Optional.empty());
        when(domainService.isEmailOnVerifiedDomain(ORG_ID, "new@acme.com")).thenReturn(true);
        when(memberService.getTeamStatus(ORG_ID))
                .thenReturn(new OrganizationMemberService.TeamStatus(false, 1, 1, 0, "FREE"));

        assertThat(service.resolveUser(SUB, jwt("new@acme.com", ALIAS))).isNull();
        verify(userRepository, never()).save(any());
        verify(freeSubscriptionProvisioner, never()).provisionIfMissing(any());
        verify(keycloakClient).releaseRefusedBrokeredUser(SUB, ALIAS, true);
    }

    @Test
    @DisplayName("an admitted NEW SAML login creates a SAML account that records the IdP which created it")
    void admittedNewSamlLoginRecordsItsIdp() {
        when(userRepository.findByProviderId(SUB)).thenReturn(Optional.empty());
        when(userRepository.findByEmail("new@acme.com")).thenReturn(Optional.empty());
        when(domainService.isEmailOnVerifiedDomain(ORG_ID, "new@acme.com")).thenReturn(true);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User saved = inv.getArgument(0);
            if (saved.getId() == null) saved.setId(20L);
            return saved;
        });
        when(memberRepository.findActiveByOrganizationIdAndUserId(ORG_ID, 20L)).thenReturn(Optional.empty());
        when(organizationRepository.findByIdForUpdate(ORG_ID)).thenReturn(Optional.of(organization));
        when(memberRepository.save(any(OrganizationMember.class))).thenAnswer(inv -> inv.getArgument(0));

        UserResolutionResponse response = service.resolveUser(SUB, jwt("new@acme.com", ALIAS));

        assertThat(response).isNotNull();
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues().get(0).getAuthProvider()).isEqualTo(AuthProvider.SAML);
        assertThat(saved.getAllValues().get(0).getSamlIdpAlias()).isEqualTo(ALIAS);
        verify(memberRepository).save(any(OrganizationMember.class));
        verifyNoInteractions(keycloakClient);
    }

    @Test
    @DisplayName("a Keycloak user recreated through ANOTHER workspace's IdP never takes over the SAML account by email")
    void recreationThroughAnotherWorkspaceIdpIsRefused() {
        OrganizationSamlConnection other = new OrganizationSamlConnection(organization, OTHER_ALIAS);
        other.setStatus(OrganizationSamlConnection.Status.ACTIVE);
        when(samlRepository.findByIdpAlias(OTHER_ALIAS)).thenReturn(Optional.of(other));
        when(domainService.isEmailOnVerifiedDomain(ORG_ID, "member@acme.com")).thenReturn(true);
        when(userRepository.findByProviderId(SUB)).thenReturn(Optional.empty());
        User existing = user(9L, "member@acme.com", AuthProvider.SAML, ALIAS);
        when(userRepository.findByEmail("member@acme.com")).thenReturn(Optional.of(existing));

        assertThat(service.resolveUser(SUB, jwt("member@acme.com", OTHER_ALIAS))).isNull();
        verify(userRepository, never()).save(any());
        assertThat(existing.getProviderId()).isEqualTo("kc-9");
        verify(keycloakClient).releaseRefusedBrokeredUser(SUB, OTHER_ALIAS, true);
    }

    @Test
    @DisplayName("a refused token coming back on every request releases Keycloak ONCE per (user, IdP)")
    void releaseIsMemoizedPerUserAndIdp() {
        User victim = user(7L, "victim@acme.com", AuthProvider.KEYCLOAK, null);
        when(userRepository.findByProviderId(SUB)).thenReturn(Optional.of(victim));

        String token = jwt("victim@acme.com", ALIAS);
        service.resolveUser(SUB, token);
        service.resolveUser(SUB, token);
        service.resolveUser(SUB, token);

        verify(keycloakClient, org.mockito.Mockito.times(1)).releaseRefusedBrokeredUser(SUB, ALIAS, false);
    }

    @Test
    @DisplayName("a Keycloak failure during release keeps the refusal, and the next request retries the release")
    void releaseFailureStillRefusesAndIsRetried() {
        User victim = user(7L, "victim@acme.com", AuthProvider.KEYCLOAK, null);
        when(userRepository.findByProviderId(SUB)).thenReturn(Optional.of(victim));
        when(keycloakClient.releaseRefusedBrokeredUser(SUB, ALIAS, false))
                .thenThrow(new IllegalStateException("KC down"))
                .thenReturn(KeycloakSamlIdentityProviderClient.ReleaseOutcome.UNLINKED);

        String token = jwt("victim@acme.com", ALIAS);
        assertThat(service.resolveUser(SUB, token)).isNull();
        assertThat(service.resolveUser(SUB, token)).isNull();
        service.resolveUser(SUB, token);

        verify(keycloakClient, org.mockito.Mockito.times(2)).releaseRefusedBrokeredUser(SUB, ALIAS, false);
    }

    @Test
    @DisplayName("re-point: a member whose Keycloak user was recreated signs back in even when the workspace is full")
    void repointOfExistingMemberSkipsTheSeatCheck() {
        when(userRepository.findByProviderId(SUB)).thenReturn(Optional.empty());
        User member = user(9L, "member@acme.com", AuthProvider.SAML, ALIAS);
        when(userRepository.findByEmail("member@acme.com")).thenReturn(Optional.of(member));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(domainService.isEmailOnVerifiedDomain(ORG_ID, "member@acme.com")).thenReturn(true);
        // Full workspace: the new-account seat check would refuse.
        lenient().when(memberService.getTeamStatus(ORG_ID))
                .thenReturn(new OrganizationMemberService.TeamStatus(true, 2, 2, 0, "TEAM"));
        when(memberRepository.findActiveByOrganizationIdAndUserId(ORG_ID, 9L))
                .thenReturn(Optional.of(new OrganizationMember(organization, member, OrganizationRole.MEMBER, true)));

        assertThat(service.resolveUser(SUB, jwt("member@acme.com", ALIAS))).isNotNull();
        assertThat(member.getProviderId()).isEqualTo(SUB);
        verifyNoInteractions(keycloakClient);
    }

    @Test
    @DisplayName("without the SAML login service a workspace SAML token fails closed")
    void missingSamlLoginServiceFailsClosed() {
        ReflectionTestUtils.setField(service, "samlLoginService", null);
        User member = user(9L, "member@acme.com", AuthProvider.SAML, ALIAS);
        when(userRepository.findByProviderId(SUB)).thenReturn(Optional.of(member));

        assertThat(service.resolveUser(SUB, jwt("member@acme.com", ALIAS))).isNull();
    }

    @Test
    @DisplayName("a password login (no identity_provider) never touches the SAML path or Keycloak")
    void nonSamlLoginIsUntouched() {
        User victim = user(7L, "victim@acme.com", AuthProvider.KEYCLOAK, null);
        when(userRepository.findByProviderId(SUB)).thenReturn(Optional.of(victim));

        assertThat(service.resolveUser(SUB, jwt("victim@acme.com", null))).isNotNull();
        verifyNoInteractions(keycloakClient, samlRepository, domainService);
    }

    private static User user(Long id, String email, AuthProvider provider, String samlAlias) {
        User u = new User();
        u.setId(id);
        u.setProviderId("kc-" + id);
        u.setUsername("u" + id);
        u.setEmail(email);
        u.setAuthProvider(provider);
        u.setSamlIdpAlias(samlAlias);
        u.setEnabled(true);
        u.setRoles(Set.of("USER"));
        u.setUserVersion(1L);
        return u;
    }

    private static String jwt(String email, String identityProvider) {
        try {
            com.nimbusds.jwt.JWTClaimsSet.Builder claims = new com.nimbusds.jwt.JWTClaimsSet.Builder()
                    .subject(SUB)
                    .claim("email", email);
            if (identityProvider != null) {
                claims.claim("identity_provider", identityProvider);
            }
            com.nimbusds.jwt.SignedJWT jwt = new com.nimbusds.jwt.SignedJWT(
                    new com.nimbusds.jose.JWSHeader(com.nimbusds.jose.JWSAlgorithm.HS256), claims.build());
            jwt.sign(new com.nimbusds.jose.crypto.MACSigner("super-secret-key-that-is-at-least-32-bytes-long!!"));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
