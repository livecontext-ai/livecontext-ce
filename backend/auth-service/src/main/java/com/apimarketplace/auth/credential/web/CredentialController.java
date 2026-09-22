package com.apimarketplace.auth.credential.web;

import com.apimarketplace.auth.credential.domain.CredentialModels.*;
import com.apimarketplace.auth.credential.domain.CredentialRenameRefusedException;
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
    public ResponseEntity<?> createCredential(
            HttpServletRequest httpRequest,
            @RequestBody Map<String, Object> request) {

        String tenantId = tenantResolver.resolveOrNull(httpRequest);
        String organizationId = tenantResolver.resolveOrgId(httpRequest);

        // Same 400 the rename path returns, for the same rule. The service validates the
        // name too, but auth-service registers no exception handler, so letting its
        // IllegalArgumentException escape a controller turns "your name is too long" into a
        // 500 and a generic "something went wrong" toast. Creating and renaming must fail
        // the same way, or the user learns the rule only from whichever path they tried.
        try {
            CredentialService.validateName(extractor.getString(request, "name"));
        } catch (IllegalArgumentException e) {
            return badRequest(String.valueOf(e.getMessage()), "invalid_name");
        }

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
     * PATCH /api/credentials/{id} - Rename a credential. Body: {@code {"name": "..."}}.
     *
     * <p>Strict isolation, same rule as GET/DELETE {@code /{id}}: 404 when the row
     * lives in another workspace. 400 when there is no active workspace, or the name
     * is missing, not a string, blank, over {@code CredentialService.MAX_NAME_LENGTH},
     * or carries control characters. 409 / 422 when the rename is refused
     * ({@link CredentialRenameRefusedException}: a name already taken, or a credential
     * whose name is its identity). Every failure carries
     * {@code {"error": "...", "code": "..."}}, so a caller branches on the code rather
     * than on prose.
     *
     * <p>Only the display label moves. The credential keeps its id, its
     * {@code integration} slug, its default flag and its secrets.
     */
    @PatchMapping("/{id}")
    public ResponseEntity<?> renameCredential(
            @PathVariable("id") Long id,
            HttpServletRequest httpRequest,
            @RequestBody Map<String, Object> request) {

        String tenantId = tenantResolver.resolveOrNull(httpRequest);
        tenantResolver.validate(tenantId);
        String organizationId = tenantResolver.resolveOrgId(httpRequest);
        if (organizationId == null || organizationId.isBlank()) {
            // Answered here so the response says what the CALLER got wrong, instead of
            // relaying TenantResolver's internal "required after V261" wording.
            return badRequest("An active workspace is required", "workspace_required");
        }

        // RequestParameterExtractor.getString coerces with toString(), which would
        // persist "{a=1}" for {"name": {"a": 1}}. A rename takes a string or nothing.
        Object rawName = request.get("name");
        if (!(rawName instanceof String)) {
            return badRequest("'name' must be a string", "invalid_name");
        }

        // Validated here so an invalid NAME cannot be confused with any other
        // IllegalArgumentException the service may raise (TenantResolver's, whose message
        // names an internal migration). The service validates again for non-HTTP callers.
        try {
            CredentialService.validateName((String) rawName);
        } catch (IllegalArgumentException e) {
            return badRequest(String.valueOf(e.getMessage()), "invalid_name");
        }

        try {
            return credentialService
                    .renameCredentialForScope(id, tenantId, organizationId, (String) rawName)
                    .<ResponseEntity<?>>map(cred -> ResponseEntity.ok(cred.withoutSecrets()))
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (CredentialRenameRefusedException e) {
            return ResponseEntity.status(e.reason().httpStatus())
                    .body(Map.of("error", String.valueOf(e.getMessage()), "code", e.reason().code()));
        }
    }

    private static ResponseEntity<Map<String, String>> badRequest(String message, String code) {
        return ResponseEntity.badRequest().body(Map.of("error", message, "code", code));
    }

    /**
     * Switch whose key serves the caller's executions on one provider (body
     * {@code {"mode": "no_proxy" | "proxy"}}). Same scoping as rename: the caller's
     * active workspace, 404 for anything it cannot see.
     */
    @PatchMapping("/{id}/llm-mode")
    public ResponseEntity<?> setLlmKeyMode(
            @PathVariable("id") Long id,
            HttpServletRequest httpRequest,
            @RequestBody Map<String, Object> request) {

        String tenantId = tenantResolver.resolveOrNull(httpRequest);
        tenantResolver.validate(tenantId);
        String organizationId = tenantResolver.resolveOrgId(httpRequest);
        if (organizationId == null || organizationId.isBlank()) {
            return badRequest("An active workspace is required", "workspace_required");
        }
        Object rawMode = request.get("mode");
        if (!(rawMode instanceof String mode) || !CredentialService.LLM_KEY_MODES.contains(mode)) {
            return badRequest("'mode' must be one of " + CredentialService.LLM_KEY_MODES, "invalid_mode");
        }
        try {
            return credentialService
                    .setLlmKeyModeForScope(id, tenantId, organizationId, mode)
                    .<ResponseEntity<?>>map(cred -> ResponseEntity.ok(cred.withoutSecrets()))
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (IllegalArgumentException e) {
            return badRequest(String.valueOf(e.getMessage()), "invalid_mode");
        }
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
