package com.apimarketplace.auth.web;

import com.apimarketplace.auth.service.OrganizationSsoDomainService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SSO domain + discovery controllers")
class SsoDomainControllersTest {

    private static final UUID ORG_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID DOMAIN_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock private OrganizationSsoDomainService domainService;

    @Test
    @DisplayName("discover answers found=false with nothing else for an unknown domain")
    void discoverUnknownSaysNothing() {
        when(domainService.discover("user@example.com")).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> r = new SsoDiscoveryController(domainService)
                .discover(new SsoDiscoveryController.DiscoverRequest("user@example.com"));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsExactly(Map.entry("found", false));
    }

    @Test
    @DisplayName("discover returns the workspace and the IdP hint for a routed domain")
    void discoverFound() {
        when(domainService.discover("jane@acme.com"))
                .thenReturn(Optional.of(new OrganizationSsoDomainService.Discovery(ORG_ID, "org-x-saml")));

        ResponseEntity<Map<String, Object>> r = new SsoDiscoveryController(domainService)
                .discover(new SsoDiscoveryController.DiscoverRequest("jane@acme.com"));

        assertThat(r.getBody()).containsEntry("found", true)
                .containsEntry("organizationId", ORG_ID.toString())
                .containsEntry("idpHint", "org-x-saml");
    }

    @Test
    @DisplayName("discover tolerates a missing body")
    void discoverNullBody() {
        when(domainService.discover(null)).thenReturn(Optional.empty());

        assertThat(new SsoDiscoveryController(domainService).discover(null).getBody()).containsEntry("found", false);
    }

    @Test
    @DisplayName("verify maps claimed or lost race -> 409, DNS failure -> 502, unknown id -> 404, bad input -> 400, not admin -> 403")
    void verifyErrorMapping() {
        OrganizationSsoDomainController c = new OrganizationSsoDomainController(domainService);

        when(domainService.verify(ORG_ID, 1L, DOMAIN_ID))
                .thenThrow(new OrganizationSsoDomainService.DomainClaimedException("claimed"));
        assertThat(c.verify(ORG_ID, DOMAIN_ID, 1L).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        when(domainService.verify(ORG_ID, 2L, DOMAIN_ID)).thenThrow(new IllegalStateException("DNS lookup failed"));
        assertThat(c.verify(ORG_ID, DOMAIN_ID, 2L).getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);

        when(domainService.verify(ORG_ID, 3L, DOMAIN_ID)).thenThrow(new IllegalArgumentException("Domain not found"));
        assertThat(c.verify(ORG_ID, DOMAIN_ID, 3L).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        when(domainService.verify(ORG_ID, 4L, DOMAIN_ID)).thenThrow(new SecurityException("no"));
        assertThat(c.verify(ORG_ID, DOMAIN_ID, 4L).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        when(domainService.verify(ORG_ID, 6L, DOMAIN_ID))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uq_org_sso_domain_verified"));
        assertThat(c.verify(ORG_ID, DOMAIN_ID, 6L).getStatusCode())
                .as("a verification lost to the unique index is a conflict, never a 500").isEqualTo(HttpStatus.CONFLICT);

        when(domainService.add(any(), any(), any())).thenThrow(new IllegalArgumentException("Enter a domain such as example.com"));
        assertThat(c.add(ORG_ID, 5L, new OrganizationSsoDomainController.AddDomainRequest("x")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
