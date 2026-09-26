package com.apimarketplace.auth.web;

import com.apimarketplace.auth.service.OrganizationSsoDomainService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Email domains of a workspace's SAML SSO. Routed by the existing {@code /api/organizations/**}
 * gateway route.
 */
@RestController
@RequestMapping("/api/organizations/{orgId}/saml-sso/domains")
public class OrganizationSsoDomainController {

    private final OrganizationSsoDomainService domainService;

    public OrganizationSsoDomainController(OrganizationSsoDomainService domainService) {
        this.domainService = domainService;
    }

    public record AddDomainRequest(String domain) {
    }

    @GetMapping
    public ResponseEntity<?> list(
            @PathVariable UUID orgId,
            @RequestHeader(value = "X-User-ID", required = false) Long userId
    ) {
        return handle(() -> ResponseEntity.ok(domainService.list(orgId, userId)));
    }

    @PostMapping
    public ResponseEntity<?> add(
            @PathVariable UUID orgId,
            @RequestHeader(value = "X-User-ID", required = false) Long userId,
            @RequestBody AddDomainRequest request
    ) {
        return handle(() -> ResponseEntity.status(HttpStatus.CREATED)
                .body(domainService.add(orgId, userId, request == null ? null : request.domain())));
    }

    @PostMapping("/{domainId}/verify")
    public ResponseEntity<?> verify(
            @PathVariable UUID orgId,
            @PathVariable UUID domainId,
            @RequestHeader(value = "X-User-ID", required = false) Long userId
    ) {
        return handle(() -> ResponseEntity.ok(domainService.verify(orgId, userId, domainId)));
    }

    @DeleteMapping("/{domainId}")
    public ResponseEntity<?> delete(
            @PathVariable UUID orgId,
            @PathVariable UUID domainId,
            @RequestHeader(value = "X-User-ID", required = false) Long userId
    ) {
        return handle(() -> {
            domainService.delete(orgId, userId, domainId);
            return ResponseEntity.noContent().build();
        });
    }

    private ResponseEntity<?> handle(Supplier<ResponseEntity<?>> action) {
        try {
            return action.get();
        } catch (OrganizationSsoDomainService.DomainClaimedException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(error("DOMAIN_CLAIMED", e.getMessage()));
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Lost a concurrent verification to the partial unique index (the other workspace won),
            // or a duplicate add that slipped past the pre-check. Both are a conflict, never a 500.
            return ResponseEntity.status(HttpStatus.CONFLICT).body(error("DOMAIN_CLAIMED",
                    "This domain is already verified by another workspace, or already listed"));
        } catch (UnsupportedOperationException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error("PLAN_REQUIRED", e.getMessage()));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error("FORBIDDEN", e.getMessage()));
        } catch (IllegalStateException e) {
            // DNS resolver failure: the record may well be published, so never report "not found".
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(error("DNS_LOOKUP_FAILED", e.getMessage()));
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().endsWith("not found")) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error("NOT_FOUND", e.getMessage()));
            }
            return ResponseEntity.badRequest().body(error("INVALID_DOMAIN", e.getMessage()));
        }
    }

    private static Map<String, String> error(String code, String message) {
        return Map.of("errorCode", code, "message", message == null ? "" : message);
    }
}
