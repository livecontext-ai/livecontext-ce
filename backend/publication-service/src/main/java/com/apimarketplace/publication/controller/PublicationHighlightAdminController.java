package com.apimarketplace.publication.controller;

import com.apimarketplace.common.web.AdminRoleGuard;
import com.apimarketplace.common.web.GatewayFilterProperties;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.DisplayMode;
import com.apimarketplace.publication.dto.HighlightOrderRequest;
import com.apimarketplace.publication.service.PublicationHighlightService;
import com.apimarketplace.publication.service.PublicationHighlightService.HighlightedPublication;
import jakarta.annotation.PostConstruct;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Admin curation endpoint. Mounted under the existing {@code /api/publications/admin/**}
 * prefix (no new gateway route required).
 *
 * <p><b>Cloud-only.</b> The bean only loads when the env-driven property
 * {@code marketplace.curation.admin-enabled=true} is set. CE distributions
 * leave this unset, so any GET/PUT below returns 404.</p>
 *
 * <p><b>Defense in depth.</b> Three layers gate this controller:
 * <ol>
 *   <li>The gateway strips inbound {@code X-User-Roles} (and friends) from the
 *       client request before routing - see {@code AuthenticationFilter}.</li>
 *   <li>The downstream {@link com.apimarketplace.common.web.GatewayAuthenticationFilter}
 *       validates the HMAC {@code X-Gateway-Secret}, so requests that don't come
 *       through the gateway are rejected (401).</li>
 *   <li>{@link AdminRoleGuard#denyIfNotAdmin(String)} verifies the resolved role
 *       on every endpoint here.</li>
 * </ol>
 * If HMAC verification is disabled while curation is enabled (a misconfiguration
 * that would collapse the role-trust chain), {@link #assertGatewaySecretEnforced()}
 * fail-fasts at boot.</p>
 */
@RestController
@RequestMapping("/api/publications/admin/highlights")
@ConditionalOnProperty(name = "marketplace.curation.admin-enabled", havingValue = "true", matchIfMissing = false)
public class PublicationHighlightAdminController {

    private final PublicationHighlightService highlightService;
    private final GatewayFilterProperties gatewayProperties;

    public PublicationHighlightAdminController(PublicationHighlightService highlightService,
                                                 GatewayFilterProperties gatewayProperties) {
        this.highlightService = highlightService;
        this.gatewayProperties = gatewayProperties;
    }

    /**
     * Boot-time fail-fast: if curation is enabled (this bean exists) but HMAC
     * verification is disabled, the role check downstream is no longer trustworthy
     * (any process on the network could call this controller with forged headers).
     * Refuse to start rather than silently expose admin actions.
     */
    @PostConstruct
    void assertGatewaySecretEnforced() {
        if (!gatewayProperties.isVerificationEnabled()) {
            throw new IllegalStateException(
                    "FATAL: marketplace.curation.admin-enabled=true requires "
                            + "gateway.filter.verification-enabled=true. Refusing to start.");
        }
    }

    @GetMapping("/{displayMode}")
    public ResponseEntity<?> listHighlights(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @PathVariable DisplayMode displayMode) {
        ResponseEntity<Map<String, Object>> denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;

        List<HighlightedPublication> highlights = highlightService.listAdminHighlights(displayMode);
        return ResponseEntity.ok(Map.of(
                "displayMode", displayMode.name(),
                "highlights", highlights.stream()
                        .map(h -> Map.<String, Object>of(
                                "rank", h.rank(),
                                "publication", h.publication()
                        ))
                        .toList()
        ));
    }

    @PutMapping("/{displayMode}")
    public ResponseEntity<?> replaceHighlights(
            @RequestHeader("X-User-ID") String adminUserId,
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @PathVariable DisplayMode displayMode,
            @Valid @RequestBody HighlightOrderRequest body) {
        ResponseEntity<Map<String, Object>> denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;

        try {
            highlightService.replaceHighlights(displayMode, body.orderedIds(), adminUserId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }
        return ResponseEntity.noContent().build();
    }
}
