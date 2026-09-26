package com.apimarketplace.auth.web;

import com.apimarketplace.auth.service.OrganizationSsoDomainService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Anonymous "Sign in with SSO" lookup: an email in, the workspace identity provider to use out.
 *
 * <p>POST so the address never lands in an access log or a browser history. The answer is the
 * same shape whether the domain is unknown, pending or its connection is inactive
 * ({@code found=false}). A positive answer does tell an anonymous caller that a domain signs in
 * to LiveContext through SSO, and which workspace: that is what the button needs, it is what
 * Stripe-style SSO pages disclose too, and the workspace id is already derivable from the alias.
 */
@RestController
@RequestMapping("/api/auth/sso")
public class SsoDiscoveryController {

    private final OrganizationSsoDomainService domainService;

    public SsoDiscoveryController(OrganizationSsoDomainService domainService) {
        this.domainService = domainService;
    }

    public record DiscoverRequest(String email) {
    }

    @PostMapping("/discover")
    public ResponseEntity<Map<String, Object>> discover(@RequestBody(required = false) DiscoverRequest request) {
        return domainService.discover(request == null ? null : request.email())
                .<ResponseEntity<Map<String, Object>>>map(d -> ResponseEntity.ok(Map.of(
                        "found", true,
                        "organizationId", d.organizationId().toString(),
                        "idpHint", d.idpHint())))
                .orElseGet(() -> ResponseEntity.ok(Map.of("found", false)));
    }
}
