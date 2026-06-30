package com.apimarketplace.auth.credential.web;

import com.apimarketplace.auth.credential.domain.CredentialModels.*;
import com.apimarketplace.auth.credential.service.CredentialService;
import com.apimarketplace.auth.credential.util.RequestParameterExtractor;
import com.apimarketplace.common.web.TenantResolver;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST controller for Credential functionality.
 *
 * <p>Uses centralized infrastructure:
 * <ul>
 *   <li>{@link TenantResolver} for X-User-ID header extraction</li>
 *   <li>{@link RequestParameterExtractor} for type-safe parameter extraction</li>
 *   <li>GlobalExceptionHandler for error responses</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/credentials")
public class CredentialController {

    private final CredentialService credentialService;
    private final TenantResolver tenantResolver;
    private final RequestParameterExtractor extractor;

    public CredentialController(
            CredentialService credentialService,
            TenantResolver tenantResolver,
            RequestParameterExtractor extractor) {
        this.credentialService = credentialService;
        this.tenantResolver = tenantResolver;
        this.extractor = extractor;
    }

    /**
     * GET /api/credentials - Strict-isolation credentials listing.
     *
     * <p>When {@code X-Organization-ID} is set, returns ONLY org credentials
     * (visible to every org member). When absent, returns ONLY personal
     * credentials (visible to {@code tenant_id} only). No mixing - owner has
     * the same view as any other member when in org workspace.</p>
     */
    @GetMapping
    public ResponseEntity<PaginatedCredentialsResponse> getCredentials(
            HttpServletRequest request,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "10") int pageSize,
            @RequestParam(value = "status", required = false) String statusFilter) {

        String tenantId = tenantResolver.resolveOrNull(request);
        String organizationId = tenantResolver.resolveOrgId(request);

        CredentialStatus status = null;
        if (statusFilter != null && !statusFilter.equals("all")) {
            try {
                status = CredentialStatus.valueOf(statusFilter);
            } catch (IllegalArgumentException e) {
                // Invalid status filter, ignore and return all
            }
        }

        PaginatedCredentialsResponse response = credentialService.getCredentialsForScope(
            tenantId, organizationId, page, pageSize, status
        );

        // Strip secrets from public API response
        var safeCredentials = response.credentials().stream()
                .map(Credential::withoutSecrets)
                .collect(Collectors.toList());
        return ResponseEntity.ok(new PaginatedCredentialsResponse(
                safeCredentials, response.page(), response.pageSize(),
                response.totalItems(), response.totalPages(),
                response.hasNext(), response.hasPrevious()
        ));
    }

    /**
     * POST /api/credentials - Create a new credential in the active workspace.
     *
     * <p>The new row is tagged with {@code X-Organization-ID} when present
     * (org scope); otherwise it lands in personal scope (organization_id NULL).
     * Members can create credentials in their org; switching to personal
     * workspace lets them create personal ones - the two scopes never mix.</p>
     */
    @PostMapping
    public ResponseEntity<Credential> createCredential(
            HttpServletRequest httpRequest,
            @RequestBody Map<String, Object> request) {

        String tenantId = tenantResolver.resolveOrNull(httpRequest);
        String organizationId = tenantResolver.resolveOrgId(httpRequest);

        String typeStr = extractor.getString(request, "type");
        String environmentStr = extractor.getString(request, "environment");

        CredentialType type = typeStr != null ?
            CredentialType.fromString(typeStr) : CredentialType.OAuth2;
        CredentialEnvironment environment = environmentStr != null ?
            CredentialEnvironment.valueOf(environmentStr) : CredentialEnvironment.Production;

        Credential credential = credentialService.createCredential(
            tenantId,
            organizationId,
            extractor.getString(request, "name"),
            extractor.getString(request, "integration"),
            type,
            environment,
            extractor.getString(request, "description"),
            extractor.getMap(request, "credential_data"),
            extractor.getList(request, "scopes"),
            extractor.getList(request, "tags"),
            extractor.getString(request, "owner"),
            extractor.getString(request, "iconUrl") != null
                ? extractor.getString(request, "iconUrl")
                : extractor.getString(request, "icon_url")
        );

        return ResponseEntity.ok(credential.withoutSecrets());
    }

    /**
     * GET /api/credentials/{id} - Strict-isolation single fetch. Returns 404 if
     * the credential exists but belongs to a different scope than the caller's
     * active workspace.
     */
    @GetMapping("/{id}")
    public ResponseEntity<Credential> getCredential(
            @PathVariable("id") Long id,
            HttpServletRequest request,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {

        String tenantId = tenantResolver.resolveOrNull(request);
        String organizationId = tenantResolver.resolveOrgId(request);
        return credentialService.getCredentialForScope(id, tenantId, organizationId)
            .map(cred -> ResponseEntity.ok(cred.withoutSecrets()))
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * DELETE /api/credentials/{id} - Strict-isolation delete. Returns 404 if
     * the credential exists in a different scope.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteCredential(
            @PathVariable("id") Long id,
            HttpServletRequest request,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {

        String tenantId = tenantResolver.resolveOrNull(request);
        String organizationId = tenantResolver.resolveOrgId(request);
        boolean deleted = credentialService.deleteCredentialForScope(id, tenantId, organizationId);
        if (deleted) {
            return ResponseEntity.noContent().build();
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * GET /api/credentials/by-integration/{integration} - Strict-isolation
     * fetch by integration. Returns ONLY rows in the active workspace's scope.
     */
    @GetMapping("/by-integration/{integration}")
    public ResponseEntity<List<Credential>> getCredentialsByIntegration(
            @PathVariable("integration") String integration,
            HttpServletRequest request,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {

        String tenantId = tenantResolver.resolveOrNull(request);
        tenantResolver.validate(tenantId);
        String organizationId = tenantResolver.resolveOrgId(request);

        List<Credential> credentials = credentialService
                .getCredentialsByIntegrationForScope(tenantId, organizationId, integration)
                .stream().map(Credential::withoutSecrets).collect(Collectors.toList());
        return ResponseEntity.ok(credentials);
    }

    /**
     * GET /api/credentials/all - Strict-isolation listing of every credential
     * in the active workspace. Org workspace returns org rows only; personal
     * returns personal-only.
     */
    @GetMapping("/all")
    public ResponseEntity<List<Credential>> getAllCredentials(
            HttpServletRequest request,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {

        String tenantId = tenantResolver.resolveOrNull(request);
        tenantResolver.validate(tenantId);
        String organizationId = tenantResolver.resolveOrgId(request);

        List<Credential> credentials = credentialService
                .getAllCredentialsForScope(tenantId, organizationId)
                .stream().map(Credential::withoutSecrets).collect(Collectors.toList());
        return ResponseEntity.ok(credentials);
    }

    /**
     * POST /api/credentials/{id}/set-default - Scope-aware set-default. The
     * sibling-clear sweep is narrowed to the active scope so toggling an org
     * credential's default flag never touches the caller's personal defaults.
     */
    @PostMapping("/{id}/set-default")
    public ResponseEntity<Void> setAsDefault(
            @PathVariable("id") Long id,
            HttpServletRequest request,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {

        String tenantId = tenantResolver.resolveOrNull(request);
        tenantResolver.validate(tenantId);
        String organizationId = tenantResolver.resolveOrgId(request);

        credentialService.setAsDefault(tenantId, organizationId, id);
        return ResponseEntity.ok().build();
    }

    /**
     * POST /api/credentials/{id}/clear-default - Scope-aware clear-default.
     * "Only credential for integration" guard counts scope-locally.
     */
    @PostMapping("/{id}/clear-default")
    public ResponseEntity<Void> clearDefault(
            @PathVariable("id") Long id,
            HttpServletRequest request,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {

        String tenantId = tenantResolver.resolveOrNull(request);
        tenantResolver.validate(tenantId);
        String organizationId = tenantResolver.resolveOrgId(request);

        try {
            credentialService.clearDefault(tenantId, organizationId, id);
            return ResponseEntity.ok().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).build();
        }
    }
}
