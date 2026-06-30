package com.apimarketplace.auth.web;

import com.apimarketplace.auth.dto.OrganizationSamlConnectionDto;
import com.apimarketplace.auth.dto.UpsertOrganizationSamlConnectionRequest;
import com.apimarketplace.auth.service.OrganizationSamlService;
import com.apimarketplace.auth.service.SamlProvisioningException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/organizations/{orgId}/saml-sso")
public class OrganizationSamlController {

    private final OrganizationSamlService samlService;

    public OrganizationSamlController(OrganizationSamlService samlService) {
        this.samlService = samlService;
    }

    @GetMapping
    public ResponseEntity<?> get(
            @PathVariable UUID orgId,
            @RequestHeader(value = "X-User-ID", required = false) Long userId
    ) {
        try {
            return ResponseEntity.ok(samlService.get(orgId, userId));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error("FORBIDDEN", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PutMapping
    public ResponseEntity<?> upsert(
            @PathVariable UUID orgId,
            @RequestHeader(value = "X-User-ID", required = false) Long userId,
            @Valid @RequestBody UpsertOrganizationSamlConnectionRequest request
    ) {
        try {
            OrganizationSamlConnectionDto dto = samlService.upsert(orgId, userId, request);
            return ResponseEntity.ok(dto);
        } catch (SamlProvisioningException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(error("KEYCLOAK_PROVISIONING_FAILED", e.getMessage()));
        } catch (UnsupportedOperationException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error("PLAN_REQUIRED", e.getMessage()));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error("FORBIDDEN", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error("INVALID_SAML_CONFIG", e.getMessage()));
        }
    }

    @DeleteMapping
    public ResponseEntity<?> delete(
            @PathVariable UUID orgId,
            @RequestHeader(value = "X-User-ID", required = false) Long userId
    ) {
        try {
            samlService.delete(orgId, userId);
            return ResponseEntity.noContent().build();
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error("FORBIDDEN", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    private Map<String, String> error(String code, String message) {
        return Map.of("errorCode", code, "message", message == null ? "" : message);
    }
}
