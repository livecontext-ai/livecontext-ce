package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.AuthProvider;
import com.apimarketplace.auth.domain.Organization;
import com.apimarketplace.auth.domain.OrganizationAuditEvent;
import com.apimarketplace.auth.domain.OrganizationSamlConnection;
import com.apimarketplace.auth.domain.OrganizationSsoDomain;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.dto.OrganizationSsoDomainDto;
import com.apimarketplace.auth.repository.OrganizationRepository;
import com.apimarketplace.auth.repository.OrganizationSamlConnectionRepository;
import com.apimarketplace.auth.repository.OrganizationSsoDomainRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrganizationSsoDomainService")
class OrganizationSsoDomainServiceTest {

    private static final UUID ORG_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID DOMAIN_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final Long ACTOR = 42L;
    private static final String ALIAS = "org-aaaaaaaabbbbccccddddeeeeeeeeeeee-saml";

    @Mock private OrganizationSsoDomainRepository domainRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private OrganizationSamlConnectionRepository samlRepository;
    @Mock private OrganizationSamlService samlService;
    @Mock private OrganizationAuditService auditService;
    @Mock private SsoDomainDnsVerifier dnsVerifier;

    private Organization organization;
    private OrganizationSsoDomainService service;

    @BeforeEach
    void setUp() {
        User owner = new User("owner", "owner@acme.com", AuthProvider.KEYCLOAK, "kc-owner");
        owner.setId(ACTOR);
        organization = new Organization("Acme", "acme", false, owner);
        organization.setId(ORG_ID);
        service = new OrganizationSsoDomainService(domainRepository, organizationRepository, samlRepository,
                samlService, auditService, dnsVerifier);
        lenient().when(dnsVerifier.recordName(anyString())).thenAnswer(inv -> "_livecontext-sso." + inv.getArgument(0));
        lenient().when(dnsVerifier.expectedValue(anyString()))
                .thenAnswer(inv -> "livecontext-sso-verification=" + inv.getArgument(0));
    }

    @Nested
    @DisplayName("add")
    class Add {

        @Test
        @DisplayName("normalises the domain, stores a random token and returns the TXT record to publish")
        void addsPendingDomainWithRecord() {
            when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(organization));
            when(domainRepository.save(any(OrganizationSsoDomain.class))).thenAnswer(inv -> inv.getArgument(0));

            OrganizationSsoDomainDto dto = service.add(ORG_ID, ACTOR, "  @Acme.COM. ");

            assertThat(dto.domain()).isEqualTo("acme.com");
            assertThat(dto.verified()).isFalse();
            assertThat(dto.txtRecordName()).isEqualTo("_livecontext-sso.acme.com");
            assertThat(dto.txtRecordValue()).startsWith("livecontext-sso-verification=").hasSizeGreaterThan(40);
            verify(samlService).authorizeOwnerOrAdmin(ORG_ID, ACTOR);
            verify(samlService).enforcePlanSupportsSso(ORG_ID);
            verify(auditService).record(eq(ORG_ID), eq(ACTOR), eq(OrganizationAuditEvent.Type.SSO_DOMAIN_ADDED), any());
        }

        @Test
        @DisplayName("two claims never share a verification token")
        void tokensAreUnique() {
            when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(organization));
            when(domainRepository.save(any(OrganizationSsoDomain.class))).thenAnswer(inv -> inv.getArgument(0));

            String first = service.add(ORG_ID, ACTOR, "acme.com").txtRecordValue();
            String second = service.add(ORG_ID, ACTOR, "acme.io").txtRecordValue();

            assertThat(first).isNotEqualTo(second);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "acme", "acme.", "-acme.com", "acme..com", "ac me.com", "http://acme.com", "acme.com/x", "acmé.com"})
        @DisplayName("rejects anything that is not a plain hostname")
        void rejectsMalformedDomain(String raw) {
            assertThatThrownBy(() -> service.add(ORG_ID, ACTOR, raw)).isInstanceOf(IllegalArgumentException.class);
            verify(domainRepository, never()).save(any());
        }

        @Test
        @DisplayName("a duplicate in the same workspace is refused")
        void rejectsDuplicate() {
            when(domainRepository.existsByOrganization_IdAndDomain(ORG_ID, "acme.com")).thenReturn(true);

            assertThatThrownBy(() -> service.add(ORG_ID, ACTOR, "acme.com"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already listed");
        }

        @Test
        @DisplayName("the per-workspace cap is enforced")
        void enforcesCap() {
            when(domainRepository.countByOrganization_Id(ORG_ID))
                    .thenReturn((long) OrganizationSsoDomainService.MAX_DOMAINS_PER_ORGANIZATION);

            assertThatThrownBy(() -> service.add(ORG_ID, ACTOR, "acme.com"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at most");
        }

        @Test
        @DisplayName("a member who is not OWNER/ADMIN cannot add a domain")
        void requiresAdmin() {
            doThrow(new SecurityException("Only OWNER or ADMIN can manage SAML SSO"))
                    .when(samlService).authorizeOwnerOrAdmin(ORG_ID, 7L);

            assertThatThrownBy(() -> service.add(ORG_ID, 7L, "acme.com")).isInstanceOf(SecurityException.class);
            verify(domainRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("verify")
    class Verify {

        @Test
        @DisplayName("marks the domain verified when the TXT record carries its token")
        void verifiesWhenRecordFound() {
            OrganizationSsoDomain d = pending("acme.com");
            when(domainRepository.findByIdAndOrganization_Id(DOMAIN_ID, ORG_ID)).thenReturn(Optional.of(d));
            when(domainRepository.findVerifiedByDomain("acme.com")).thenReturn(Optional.empty());
            when(dnsVerifier.isVerified("acme.com", "tok")).thenReturn(true);

            OrganizationSsoDomainDto dto = service.verify(ORG_ID, ACTOR, DOMAIN_ID);

            assertThat(dto.verified()).isTrue();
            assertThat(d.getVerifiedAt()).isNotNull();
            assertThat(d.getLastCheckedAt()).isNotNull();
            verify(domainRepository).saveAndFlush(d);
            verify(auditService).record(eq(ORG_ID), eq(ACTOR), eq(OrganizationAuditEvent.Type.SSO_DOMAIN_VERIFIED), any());
        }

        @Test
        @DisplayName("an absent record is an ordinary answer: still pending, check time recorded, no audit")
        void staysPendingWhenRecordMissing() {
            OrganizationSsoDomain d = pending("acme.com");
            when(domainRepository.findByIdAndOrganization_Id(DOMAIN_ID, ORG_ID)).thenReturn(Optional.of(d));
            when(domainRepository.findVerifiedByDomain("acme.com")).thenReturn(Optional.empty());
            when(dnsVerifier.isVerified("acme.com", "tok")).thenReturn(false);

            OrganizationSsoDomainDto dto = service.verify(ORG_ID, ACTOR, DOMAIN_ID);

            assertThat(dto.verified()).isFalse();
            assertThat(d.getLastCheckedAt()).isNotNull();
            verify(auditService, never()).record(any(), any(), any(), any());
        }

        @Test
        @DisplayName("a domain another workspace already verified is refused without a DNS lookup")
        void refusesDomainClaimedElsewhere() {
            OrganizationSsoDomain d = pending("acme.com");
            when(domainRepository.findByIdAndOrganization_Id(DOMAIN_ID, ORG_ID)).thenReturn(Optional.of(d));
            when(domainRepository.findVerifiedByDomain("acme.com")).thenReturn(Optional.of(pending("acme.com")));

            assertThatThrownBy(() -> service.verify(ORG_ID, ACTOR, DOMAIN_ID))
                    .isInstanceOf(OrganizationSsoDomainService.DomainClaimedException.class);
            verify(dnsVerifier, never()).isVerified(anyString(), anyString());
        }

        @Test
        @DisplayName("losing a concurrent verification to the unique index propagates (the transaction is doomed), unaudited")
        void concurrentVerificationLoses() {
            OrganizationSsoDomain d = pending("acme.com");
            when(domainRepository.findByIdAndOrganization_Id(DOMAIN_ID, ORG_ID)).thenReturn(Optional.of(d));
            when(domainRepository.findVerifiedByDomain("acme.com")).thenReturn(Optional.empty());
            when(dnsVerifier.isVerified("acme.com", "tok")).thenReturn(true);
            when(domainRepository.saveAndFlush(d)).thenThrow(new DataIntegrityViolationException("uq_org_sso_domain_verified"));

            // Not caught in the service: a constraint violation has already put the transaction in
            // PostgreSQL's ERROR state, so the only honest move is to let it roll back. The
            // controller maps it to 409 (SsoDomainControllersTest).
            assertThatThrownBy(() -> service.verify(ORG_ID, ACTOR, DOMAIN_ID))
                    .isInstanceOf(DataIntegrityViolationException.class);
            verify(auditService, never()).record(any(), any(), any(), any());
        }

        @Test
        @DisplayName("a DNS failure propagates instead of reading as 'not published'")
        void dnsFailurePropagates() {
            OrganizationSsoDomain d = pending("acme.com");
            when(domainRepository.findByIdAndOrganization_Id(DOMAIN_ID, ORG_ID)).thenReturn(Optional.of(d));
            when(domainRepository.findVerifiedByDomain("acme.com")).thenReturn(Optional.empty());
            when(dnsVerifier.isVerified("acme.com", "tok")).thenThrow(new IllegalStateException("DNS lookup failed"));

            assertThatThrownBy(() -> service.verify(ORG_ID, ACTOR, DOMAIN_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .isNotInstanceOf(OrganizationSsoDomainService.DomainClaimedException.class);
            assertThat(d.getVerifiedAt()).isNull();
        }

        @Test
        @DisplayName("an already verified domain is returned as is, without a new lookup")
        void alreadyVerifiedIsIdempotent() {
            OrganizationSsoDomain d = pending("acme.com");
            d.setVerifiedAt(Instant.now());
            when(domainRepository.findByIdAndOrganization_Id(DOMAIN_ID, ORG_ID)).thenReturn(Optional.of(d));

            assertThat(service.verify(ORG_ID, ACTOR, DOMAIN_ID).verified()).isTrue();
            verify(dnsVerifier, never()).isVerified(anyString(), anyString());
        }

        @Test
        @DisplayName("another workspace's domain id is 'not found'")
        void otherWorkspaceDomainIsNotFound() {
            when(domainRepository.findByIdAndOrganization_Id(DOMAIN_ID, ORG_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.verify(ORG_ID, ACTOR, DOMAIN_ID))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Domain not found");
        }
    }

    @Test
    @DisplayName("delete removes the row and audits whether it was verified")
    void deleteRemovesAndAudits() {
        OrganizationSsoDomain d = pending("acme.com");
        when(domainRepository.findByIdAndOrganization_Id(DOMAIN_ID, ORG_ID)).thenReturn(Optional.of(d));

        service.delete(ORG_ID, ACTOR, DOMAIN_ID);

        verify(domainRepository).delete(d);
        verify(auditService).record(eq(ORG_ID), eq(ACTOR), eq(OrganizationAuditEvent.Type.SSO_DOMAIN_REMOVED), any());
    }

    @Nested
    @DisplayName("discover")
    class Discover {

        @Test
        @DisplayName("a verified domain with an ACTIVE connection routes to that workspace's alias")
        void routesVerifiedActiveDomain() {
            stubVerifiedOwner("acme.com");
            when(samlRepository.findByOrganization_Id(ORG_ID)).thenReturn(Optional.of(connection(OrganizationSamlConnection.Status.ACTIVE)));

            Optional<OrganizationSsoDomainService.Discovery> result = service.discover("  Jane.Doe@ACME.com ");

            assertThat(result).contains(new OrganizationSsoDomainService.Discovery(ORG_ID, ALIAS));
        }

        @Test
        @DisplayName("an inactive connection does not route, even on a verified domain")
        void inactiveConnectionDoesNotRoute() {
            stubVerifiedOwner("acme.com");
            when(samlRepository.findByOrganization_Id(ORG_ID)).thenReturn(Optional.of(connection(OrganizationSamlConnection.Status.ERROR)));

            assertThat(service.discover("jane@acme.com")).isEmpty();
        }

        @Test
        @DisplayName("a deleted workspace does not route")
        void deletedWorkspaceDoesNotRoute() {
            organization.setDeletedAt(LocalDateTime.now());
            stubVerifiedOwner("acme.com");

            assertThat(service.discover("jane@acme.com")).isEmpty();
            verify(samlRepository, never()).findByOrganization_Id(any());
        }

        @Test
        @DisplayName("an unverified or unknown domain does not route")
        void unknownDomainDoesNotRoute() {
            when(domainRepository.findVerifiedByDomain("gmail.com")).thenReturn(Optional.empty());

            assertThat(service.discover("user@example.com")).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "no-at-sign", "@acme.com", "jane@", "jane@localhost"})
        @DisplayName("an input that is not an email never reaches the repository")
        void malformedEmailIsEmpty(String email) {
            assertThat(service.discover(email)).isEmpty();
            verify(domainRepository, never()).findVerifiedByDomain(any());
        }

        @Test
        @DisplayName("a null email is empty")
        void nullEmailIsEmpty() {
            assertThat(service.discover(null)).isEmpty();
        }

        private void stubVerifiedOwner(String domain) {
            OrganizationSsoDomain d = pending(domain);
            d.setVerifiedAt(Instant.now());
            when(domainRepository.findVerifiedByDomain(domain)).thenReturn(Optional.of(d));
        }
    }

    @Test
    @DisplayName("isEmailOnVerifiedDomain asks for the lower-cased domain of THIS workspace")
    void admissionChecksThisWorkspace() {
        when(domainRepository.isVerifiedForOrganization(ORG_ID, "acme.com")).thenReturn(true);

        assertThat(service.isEmailOnVerifiedDomain(ORG_ID, "Jane@Acme.COM")).isTrue();
        assertThat(service.isEmailOnVerifiedDomain(ORG_ID, "jane@other.com")).isFalse();
        assertThat(service.isEmailOnVerifiedDomain(ORG_ID, null)).isFalse();
        ArgumentCaptor<String> domain = ArgumentCaptor.forClass(String.class);
        verify(domainRepository, org.mockito.Mockito.times(2)).isVerifiedForOrganization(eq(ORG_ID), domain.capture());
        assertThat(domain.getAllValues()).containsExactly("acme.com", "other.com");
    }

    private OrganizationSsoDomain pending(String domain) {
        OrganizationSsoDomain d = new OrganizationSsoDomain(organization, domain, "tok");
        d.setId(DOMAIN_ID);
        return d;
    }

    private OrganizationSamlConnection connection(OrganizationSamlConnection.Status status) {
        OrganizationSamlConnection c = new OrganizationSamlConnection(organization, ALIAS);
        c.setStatus(status);
        return c;
    }
}
