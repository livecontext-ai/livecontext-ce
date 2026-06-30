package com.apimarketplace.auth.credential.web;

import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.CreatePlatformCredentialRequest;
import com.apimarketplace.auth.credential.domain.PlatformCredentialPricingVersion;
import com.apimarketplace.auth.credential.domain.WorkflowRunPricingPin;
import com.apimarketplace.auth.credential.service.CredentialService;
import com.apimarketplace.auth.credential.service.InternalCredentialService;
import com.apimarketplace.auth.credential.service.PlatformCredentialPricingService;
import com.apimarketplace.auth.credential.service.PlatformCredentialService;
import com.apimarketplace.auth.credential.service.PricingVersionService;
import com.apimarketplace.auth.credential.service.PricingVersionService.FrozenMarkup;
import com.apimarketplace.common.security.CredentialEncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Internal API controller for credential operations.
 * Used by catalog-service (via CredentialClient) to replace cross-schema SQL.
 * Not exposed through the public gateway.
 *
 * <p>Note: Coexists with {@link InternalCredentialLookupController} which handles
 * /{id}, /default, and /all endpoints under the same base path.
 * Spring MVC resolves literal paths (like /access-token, /data-map, /platform)
 * before path variables (like /{id}), so there is no collision.
 */
@RestController
@RequestMapping("/api/internal/credentials")
public class InternalCredentialController {

    private static final Logger log = LoggerFactory.getLogger(InternalCredentialController.class);

    private final InternalCredentialService credentialService;
    private final CredentialService userCredentialService;
    private final PlatformCredentialService platformCredentialService;
    private final PlatformCredentialPricingService pricingService;
    private final PricingVersionService pricingVersionService;
    private final CredentialEncryptionService encryptionService;

    public InternalCredentialController(InternalCredentialService credentialService,
                                         CredentialService userCredentialService,
                                         PlatformCredentialService platformCredentialService,
                                         PlatformCredentialPricingService pricingService,
                                         PricingVersionService pricingVersionService,
                                         CredentialEncryptionService encryptionService) {
        this.credentialService = credentialService;
        this.userCredentialService = userCredentialService;
        this.platformCredentialService = platformCredentialService;
        this.pricingService = pricingService;
        this.pricingVersionService = pricingVersionService;
        this.encryptionService = encryptionService;
    }

    // ========== User Credentials ==========

    /**
     * Distinct ACTIVE-credential integration names for a tenant. Agent-facing list
     * tools call this once per request and intersect locally with each item's
     * required-integration set - closes the N+1 path that would otherwise hit
     * {@code /access-token} per integration per workflow.
     */
    @GetMapping("/configured-integrations/{tenantId}")
    public ResponseEntity<java.util.Set<String>> getConfiguredIntegrations(
            @PathVariable String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        return ResponseEntity.ok(
                userCredentialService.findActiveIntegrationsForScope(tenantId, organizationId));
    }

    /**
     * Opaque credential-state version for (user, active workspace). Catalog's
     * agent response cache includes it in its key so cached tool responses
     * never survive a credential switch (connect / delete / set-as-default).
     * The value is compared for equality only, never parsed.
     */
    @GetMapping("/state-version")
    public ResponseEntity<Map<String, Object>> getCredentialStateVersion(
            @RequestParam String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        return ResponseEntity.ok(Map.of(
                "version", userCredentialService.getCredentialStateVersion(userId, organizationId)));
    }

    @GetMapping("/access-token")
    public ResponseEntity<Map<String, Object>> getAccessToken(
            @RequestParam String userId,
            @RequestParam String name,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        return credentialService.getAccessTokenInfo(userId, name, organizationId)
                .<ResponseEntity<Map<String, Object>>>map(info -> {
                    // V103: include the auth type so catalog-service can pick the
                    // matching tool_credentials variant. `type` is nullable for
                    // PLATFORM tokens; Jackson serializes absent entries as missing
                    // keys, not nulls, so legacy clients stay unaffected.
                    Map<String, Object> body = new HashMap<>();
                    body.put("accessToken", info.accessToken());
                    body.put("found", true);
                    if (info.type() != null) body.put("type", info.type());
                    return ResponseEntity.ok(body);
                })
                .orElse(ResponseEntity.ok(Map.of("found", false)));
    }

    @GetMapping("/access-token/by-id")
    public ResponseEntity<Map<String, Object>> getAccessTokenById(
            @RequestParam String userId,
            @RequestParam Long credentialId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        return credentialService.getAccessTokenInfoById(userId, credentialId, organizationId)
                .<ResponseEntity<Map<String, Object>>>map(info -> {
                    Map<String, Object> body = new HashMap<>();
                    body.put("accessToken", info.accessToken());
                    body.put("found", true);
                    if (info.type() != null) body.put("type", info.type());
                    return ResponseEntity.ok(body);
                })
                .orElse(ResponseEntity.ok(Map.of("found", false)));
    }

    @PostMapping("/refresh-token")
    public ResponseEntity<Map<String, Object>> refreshToken(
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        String userId = body.get("userId");
        String credentialName = body.get("credentialName");
        Optional<String> token = credentialService.refreshAccessToken(userId, credentialName, organizationId);
        if (token.isPresent()) {
            return ResponseEntity.ok(Map.of("accessToken", token.get(), "found", true));
        }
        return ResponseEntity.ok(Map.of("found", false));
    }

    @PostMapping("/force-refresh-token")
    public ResponseEntity<Map<String, Object>> forceRefreshToken(
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        String userId = body.get("userId");
        String credentialName = body.get("credentialName");
        Optional<String> token = credentialService.forceRefreshAndGetToken(userId, credentialName, organizationId);
        if (token.isPresent()) {
            return ResponseEntity.ok(Map.of("accessToken", token.get(), "found", true));
        }
        return ResponseEntity.ok(Map.of("found", false));
    }

    @PostMapping("/force-refresh-token/by-id")
    public ResponseEntity<Map<String, Object>> forceRefreshTokenById(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        String userId = (String) body.get("userId");
        Long credentialId = toLong(body.get("credentialId"));
        Optional<String> token = credentialService.forceRefreshAndGetTokenById(userId, credentialId, organizationId);
        if (token.isPresent()) {
            return ResponseEntity.ok(Map.of("accessToken", token.get(), "found", true));
        }
        return ResponseEntity.ok(Map.of("found", false));
    }

    @GetMapping("/data-map")
    public ResponseEntity<Map<String, Object>> getCredentialDataMap(
            @RequestParam String userId,
            @RequestParam String name,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        Map<String, String> data = credentialService.getCredentialDataMap(userId, name, organizationId);
        return ResponseEntity.ok(Map.of("data", data));
    }

    @GetMapping("/data-map/by-id")
    public ResponseEntity<Map<String, Object>> getCredentialDataMapById(
            @RequestParam String userId,
            @RequestParam Long credentialId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        Map<String, String> data = credentialService.getCredentialDataMapById(userId, credentialId, organizationId);
        return ResponseEntity.ok(Map.of("data", data));
    }

    /**
     * V166: returns the credential's auth type and granted OAuth scopes for the
     * preflight check in catalog-service ({@code HttpExecutionService.preflightScopeCheck}).
     *
     * <p>Returns {@code 404} when no credential matches {@code (userId, name)}. On a hit:
     * {@code type} is the credential's stored type (e.g. {@code "oauth2"}), and {@code scopes}
     * is the granted-scope list captured at OAuth callback time. Non-OAuth2 credentials
     * have a null/absent {@code scopes} field - the catalog-side caller treats that as
     * "scope concept does not apply, skip preflight."
     */
    // NOTE: intentionally NOT org-aware. The OAuth-scope preflight
    // ({@code HttpExecutionService.preflightScopeCheck}) is best-effort / fail-open
    // (a missing credential → skip the check). Resolving the workspace-shared
    // credential here would ACTIVATE a previously-dormant preflight for credentials
    // whose display name differs from the integration key - a behavior change beyond
    // the org-aware credential-resolution fix. Leaving it name+tenant scoped means an
    // org-shared credential simply skips the preflight (same as today) and execution
    // still proceeds via the org-aware token resolution; a real scope gap then surfaces
    // as the provider's own 403, handled by the existing error path.
    @GetMapping("/scopes")
    public ResponseEntity<Map<String, Object>> getCredentialScopes(
            @RequestParam String userId,
            @RequestParam String name) {
        return userCredentialService.getCredentialByTenantAndName(userId, name)
                .<ResponseEntity<Map<String, Object>>>map(c -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("type", c.type() != null ? c.type().name() : null);
                    // Only OAuth2 credentials have a meaningful scope set. For other types we
                    // return null so the catalog-side caller can no-op without misinterpreting
                    // an empty array as "credential has zero scopes."
                    boolean isOauth2 = c.type() != null && "oauth2".equalsIgnoreCase(c.type().name());
                    body.put("scopes", isOauth2 ? c.scopes() : null);
                    return ResponseEntity.ok(body);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/scopes/by-id")
    public ResponseEntity<Map<String, Object>> getCredentialScopesById(
            @RequestParam String userId,
            @RequestParam Long credentialId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        return credentialService.getActiveUserCredentialById(userId, credentialId, organizationId)
                .<ResponseEntity<Map<String, Object>>>map(c -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("type", c.type() != null ? c.type().name() : null);
                    boolean isOauth2 = c.type() != null && "oauth2".equalsIgnoreCase(c.type().name());
                    body.put("scopes", isOauth2 ? c.scopes() : null);
                    return ResponseEntity.ok(body);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Delete all user credentials for a given integration name (across all tenants).
     * Used when an API's auth type changes during catalog reimport.
     */
    @DeleteMapping("/by-integration/{integrationName}")
    public ResponseEntity<Map<String, Object>> deleteByIntegration(
            @PathVariable String integrationName) {
        if (integrationName == null || integrationName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "integrationName is required"));
        }
        int deleted = userCredentialService.deleteByIntegration(integrationName);
        log.info("Deleted {} credentials for integration '{}'", deleted, integrationName);
        return ResponseEntity.ok(Map.of("deleted", deleted, "integration", integrationName));
    }

    // ========== Platform Credentials ==========

    @GetMapping("/platform")
    public ResponseEntity<?> listPlatformCredentials() {
        return ResponseEntity.ok(platformCredentialService.getAllCredentials());
    }

    @GetMapping("/platform/{name}")
    public ResponseEntity<?> getPlatformCredential(@PathVariable String name) {
        return platformCredentialService.getCredential(name)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/platform")
    public ResponseEntity<?> savePlatformCredential(@RequestBody Map<String, Object> request) {
        try {
            String credentialName = (String) request.get("credentialName");
            String credentialType = (String) request.get("credentialType");
            @SuppressWarnings("unchecked")
            Map<String, Object> credentialData = (Map<String, Object>) request.get("credentialData");

            if (credentialName == null || credentialName.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "credentialName is required"));
            }
            if (credentialData == null || credentialData.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "credentialData is required"));
            }

            // Encrypt sensitive fields
            Map<String, Object> encryptedData = encryptionService.encryptSensitiveFields(credentialData);

            // Determine auth type from credential data
            String authType = "api_key";
            if (encryptedData.containsKey("client_id") && encryptedData.containsKey("client_secret")) {
                authType = "oauth2";
            }

            // Build platform credential request (markup fields default to null → service uses defaults)
            var pcRequest = new CreatePlatformCredentialRequest(
                    credentialName, credentialName, authType,
                    encryptedData.containsKey("client_id") ? (String) encryptedData.get("client_id") : null,
                    encryptedData.containsKey("client_secret") ? (String) encryptedData.get("client_secret") : null,
                    encryptedData.containsKey("api_key") ? (String) encryptedData.get("api_key") : null,
                    null, null, null, null, null, null, null, null, null,  // username..customFields
                    null, null  // defaultMarkupCredits, maxCallsPerRun
            );

            var result = platformCredentialService.saveCredential(pcRequest);
            return ResponseEntity.ok(Map.of("success", true, "credentialId", result.id()));
        } catch (Exception e) {
            log.error("Error saving platform credential: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Create or update a tenant-scoped platform credential.
     * Called by the frontend CredentialWizard when a user configures their own
     * OAuth/API key settings for a custom API. This is a user-initiated action,
     * separate from API registration (which only creates the catalog template).
     * The credential is scoped to the tenant (X-User-ID) and does not affect other tenants.
     */
    @PostMapping("/platform-tenant")
    public ResponseEntity<?> saveTenantPlatformCredential(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestBody CreatePlatformCredentialRequest request) {
        try {
            if (tenantId == null || tenantId.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "X-User-ID header is required"));
            }
            if (request.integrationName() == null || request.integrationName().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "integrationName is required"));
            }

            // V362: tag the row with the active workspace (forwarded by the
            // credential-client) so this catalog-wizard save path is workspace
            // scoped like the user-facing /my endpoint. Null = personal scope.
            var result = platformCredentialService.saveCredential(request, tenantId, organizationId);
            return ResponseEntity.ok(Map.of("success", true, "credentialId", result.id()));
        } catch (Exception e) {
            log.error("Error saving tenant platform credential: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Delete a tenant-scoped platform credential by integration name.
     */
    @DeleteMapping("/platform-tenant/{integrationName}")
    public ResponseEntity<?> deleteTenantPlatformCredential(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @PathVariable String integrationName) {
        // V362: scope the delete to the active workspace, matching the save path.
        boolean deleted = platformCredentialService.deleteCredential(integrationName, tenantId, organizationId);
        if (deleted) {
            return ResponseEntity.ok(Map.of("success", true));
        }
        return ResponseEntity.notFound().build();
    }

    @DeleteMapping("/platform/{name}")
    public ResponseEntity<?> deletePlatformCredential(@PathVariable String name) {
        boolean deleted = platformCredentialService.deleteCredential(name);
        if (deleted) {
            return ResponseEntity.ok(Map.of("success", true));
        }
        return ResponseEntity.notFound().build();
    }

    @GetMapping("/platform-integration/{integrationName}")
    public ResponseEntity<Map<String, Object>> getPlatformCredentialForIntegration(
            @PathVariable String integrationName,
            @RequestParam(required = false) String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        // V362: thread the active workspace (forwarded by the credential-client's
        // OrgContextHeaderForwarder) so a BYOK key resolves workspace-isolated;
        // null org falls back to tenant-keyed for backward compatibility.
        Optional<String> token = credentialService.getPlatformAccessToken(integrationName, tenantId, organizationId);
        if (token.isPresent()) {
            return ResponseEntity.ok(Map.of("accessToken", token.get(), "found", true));
        }
        return ResponseEntity.ok(Map.of("found", false));
    }

    // ========== Platform Credential Markup - Orchestrator Internal API ==========

    /**
     * Create a run→pricing-version pin. Called by the orchestrator at run-init
     * for every platform-sourced MCP step, so subsequent debits bill against a
     * frozen rate even if an admin publishes a new pricing version mid-run.
     * Idempotent on {@code (runId, platformCredentialId)}.
     */
    @PostMapping("/run-pricing-pin")
    public ResponseEntity<Map<String, Object>> saveRunPricingPin(
            @RequestBody Map<String, Object> body) {
        String runId = (String) body.get("runId");
        Number userIdNum = (Number) body.get("userId");
        Number credIdNum = (Number) body.get("platformCredentialId");
        Number versionIdNum = (Number) body.get("pricingVersionId");

        if (runId == null || runId.isBlank()
                || userIdNum == null || credIdNum == null || versionIdNum == null) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "runId, userId, platformCredentialId, pricingVersionId are required"));
        }

        WorkflowRunPricingPin pin = pricingService.savePin(
                runId, userIdNum.longValue(), credIdNum.longValue(), versionIdNum.longValue());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", pin.getId());
        response.put("runId", pin.getRunId());
        response.put("userId", pin.getUserId());
        response.put("platformCredentialId", pin.getPlatformCredentialId());
        response.put("pricingVersionId", pin.getPricingVersionId());
        response.put("cancelled", pin.isCancelled());
        return ResponseEntity.ok(response);
    }

    /**
     * Cancel every live pin attached to a run. Invoked by the orchestrator
     * terminal-state chokepoint so stragglers don't continue billing markup
     * after the run is cancelled/failed. Idempotent.
     */
    @DeleteMapping("/run-pricing-pin/{runId}")
    public ResponseEntity<Map<String, Object>> cancelRunPricingPins(
            @PathVariable String runId) {
        int cancelled = pricingService.cancelPinsForRun(runId);
        return ResponseEntity.ok(Map.of("runId", runId, "cancelled", cancelled));
    }

    /**
     * Resolve the frozen markup rate for a pinned pricing version + api tool.
     * Called on every platform-sourced MCP debit. Returns {@code found=false}
     * if the version is stale or the orchestrator passed an unknown id.
     */
    @GetMapping("/resolve-markup")
    public ResponseEntity<Map<String, Object>> resolveMarkup(
            @RequestParam("pricingVersionId") Long pricingVersionId,
            @RequestParam("apiToolId") String apiToolIdStr) {
        UUID apiToolId;
        try {
            apiToolId = UUID.fromString(apiToolIdStr);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "apiToolId must be a UUID"));
        }

        Optional<FrozenMarkup> frozen =
                pricingVersionService.resolveFrozenMarkup(pricingVersionId, apiToolId);
        if (frozen.isEmpty()) {
            return ResponseEntity.ok(Map.of("found", false));
        }

        FrozenMarkup f = frozen.get();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("found", true);
        response.put("pricingVersionId", f.pricingVersionId());
        response.put("credentialId", f.credentialId());
        response.put("version", f.version());
        response.put("effectiveMarkup", f.effectiveMarkup());
        return ResponseEntity.ok(response);
    }

    /**
     * Composed hot-path lookup: given a run, credential, and tool, return the
     * frozen per-call markup via the pinned pricing version. Collapses the
     * pin-lookup + rate-resolve sequence into a single RPC so the orchestrator's
     * per-step debit path touches auth-service exactly once. {@code found=false}
     * means the run has no live pin for the credential - markup does not apply.
     */
    @GetMapping("/markup/run-rate")
    public ResponseEntity<Map<String, Object>> resolveRunMarkupRate(
            @RequestParam("runId") String runId,
            @RequestParam("platformCredentialId") Long platformCredentialId,
            @RequestParam("apiToolId") String apiToolIdStr) {
        UUID apiToolId;
        try {
            apiToolId = UUID.fromString(apiToolIdStr);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "apiToolId must be a UUID"));
        }

        Optional<WorkflowRunPricingPin> pin =
                pricingService.findLivePin(runId, platformCredentialId);
        if (pin.isEmpty()) {
            return ResponseEntity.ok(Map.of("found", false));
        }

        Optional<FrozenMarkup> frozen = pricingVersionService
                .resolveFrozenMarkup(pin.get().getPricingVersionId(), apiToolId);
        if (frozen.isEmpty()) {
            return ResponseEntity.ok(Map.of("found", false));
        }

        FrozenMarkup f = frozen.get();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("found", true);
        response.put("pricingVersionId", f.pricingVersionId());
        response.put("credentialId", f.credentialId());
        response.put("version", f.version());
        response.put("effectiveMarkup", f.effectiveMarkup());
        return ResponseEntity.ok(response);
    }

    /**
     * V148+ scope-aware unified rate resolver. Replaces the {@code /markup/run-rate}
     * endpoint for new code (the legacy endpoint stays alive during deprecation).
     *
     * <p>Single round-trip from catalog: pass {@code (scopeKind, scopeId,
     * userId, credentialId, apiToolId)} → receive {@code (pinId, versionId,
     * effectiveMarkup)}. Creates the pin lazily on first call (idempotent -
     * subsequent calls touch {@code last_used_at}). Returns {@code found=false}
     * when the credential has no published pricing version (caller fail-closes).
     */
    @GetMapping("/markup/scope-rate")
    public ResponseEntity<Map<String, Object>> resolveScopeMarkupRate(
            @RequestParam("scopeKind") String scopeKind,
            @RequestParam("scopeId") String scopeId,
            @RequestParam("userId") Long userId,
            @RequestParam("platformCredentialId") Long platformCredentialId,
            @RequestParam("apiToolId") String apiToolIdStr) {
        if (!"RUN".equals(scopeKind) && !"STREAM".equals(scopeKind)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "scopeKind must be RUN or STREAM"));
        }
        UUID apiToolId;
        try {
            apiToolId = UUID.fromString(apiToolIdStr);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "apiToolId must be a UUID"));
        }

        Optional<PlatformCredentialPricingService.ResolvedMarkup> resolved =
                pricingService.resolveScopeMarkup(scopeKind, scopeId, userId,
                        platformCredentialId, apiToolId);
        if (resolved.isEmpty()) {
            return ResponseEntity.ok(Map.of("found", false));
        }
        PlatformCredentialPricingService.ResolvedMarkup r = resolved.get();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("found", true);
        response.put("pinId", r.pinId());
        response.put("pricingVersionId", r.pricingVersionId());
        response.put("effectiveMarkup", r.effectiveMarkup());
        return ResponseEntity.ok(response);
    }

    /**
     * V148+ pricing-version bootstrap. Idempotent: if the credential already
     * has any published version, returns it; else publishes v1 with the supplied
     * default + per-tool overrides. Called by catalog-service's
     * {@code ApiMigrationImporter} after api_tools seed completes (migration-
     * service can't do it because Flyway runs before catalog seed).
     */
    @PostMapping("/pricing-versions/bootstrap")
    public ResponseEntity<Map<String, Object>> bootstrapPricingVersion(
            @RequestBody BootstrapPricingRequest request) {
        if (request.credentialId() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "credentialId required"));
        }
        Map<UUID, java.math.BigDecimal> overrides = new java.util.LinkedHashMap<>();
        if (request.perToolOverrides() != null) {
            for (var e : request.perToolOverrides().entrySet()) {
                try {
                    overrides.put(UUID.fromString(e.getKey()), e.getValue());
                } catch (IllegalArgumentException nfe) {
                    log.warn("bootstrapPricingVersion: skipping non-UUID tool key {}", e.getKey());
                }
            }
        }
        try {
            PlatformCredentialPricingVersion v = pricingService.bootstrapV1IfAbsent(
                    request.credentialId(),
                    request.defaultMarkupCredits(),
                    overrides,
                    request.createdBy() != null ? request.createdBy() : "ApiMigrationImporter");
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("pricingVersionId", v.getId());
            resp.put("credentialId", v.getPlatformCredentialId());
            resp.put("version", v.getVersion());
            resp.put("defaultMarkupCredits", v.getDefaultMarkupCredits());
            resp.put("created", v.getVersion() == 1);
            return ResponseEntity.ok(resp);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    public record BootstrapPricingRequest(
            Long credentialId,
            java.math.BigDecimal defaultMarkupCredits,
            Map<String, java.math.BigDecimal> perToolOverrides,
            String createdBy
    ) {
    }

    /**
     * V148+ helper: resolve a platform credential's id (and provider_kind hint
     * for the bridge skip branch) from its integration name. Catalog-service
     * stores credentials by name (e.g. {@code "llm_openai"}); the markup
     * subsystem keys on the numeric id, so callers must translate before
     * billing. Returns {@code found=false} when no platform credential exists
     * for the name (a free user-key API with no platform fallback).
     */
    @GetMapping("/platform/by-name")
    public ResponseEntity<Map<String, Object>> findPlatformCredentialByName(
            @RequestParam("integrationName") String integrationName) {
        if (integrationName == null || integrationName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "integrationName required"));
        }
        Optional<com.apimarketplace.auth.credential.domain.PlatformCredentialModels.PlatformCredential> opt =
                platformCredentialService.getRawCredential(integrationName);
        if (opt.isEmpty()) {
            return ResponseEntity.ok(Map.of("found", false));
        }
        var pc = opt.get();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("found", true);
        resp.put("id", pc.id());
        resp.put("integrationName", pc.integrationName());
        // provider_kind is not yet a field on PlatformCredential; use a heuristic
        // based on auth_type for the bridge skip. The real bridge discriminator
        // lives in auth.model_pricing.provider_kind which is unrelated to
        // platform_credentials. Default to "cloud" - bridge billing isn't yet
        // routed through this catalog path anyway (CE bridge uses different
        // accounting; see project_v120_bridge_pricing_fix).
        resp.put("providerKind", "cloud");
        return ResponseEntity.ok(resp);
    }

    /**
     * V148+ existence check for the delinquent in-flight bypass branch.
     * Lookup-only - never creates a pin.
     */
    @GetMapping("/markup/scope-pin-exists")
    public ResponseEntity<Map<String, Object>> existsScopePin(
            @RequestParam("scopeKind") String scopeKind,
            @RequestParam("scopeId") String scopeId,
            @RequestParam("platformCredentialId") Long platformCredentialId) {
        boolean exists = pricingService.existsPin(scopeKind, scopeId, platformCredentialId);
        return ResponseEntity.ok(Map.of("exists", exists));
    }

    /**
     * V148+ scope-aware cancel. Replaces {@code DELETE /run-pricing-pin/{runId}}
     * for new callers. Idempotent.
     */
    @DeleteMapping("/markup/scope-pin")
    public ResponseEntity<Map<String, Object>> cancelScopePin(
            @RequestParam("scopeKind") String scopeKind,
            @RequestParam("scopeId") String scopeId) {
        int cancelled = pricingService.cancelPinsForScope(scopeKind, scopeId);
        return ResponseEntity.ok(Map.of("scopeKind", scopeKind, "scopeId", scopeId, "cancelled", cancelled));
    }

    /**
     * Latest pricing version for a credential - used by the orchestrator at
     * run-init to decide which version to pin. {@code found=false} means the
     * credential has never had a pricing version published (markup is off).
     */
    @GetMapping("/pricing/{credentialId}/latest")
    public ResponseEntity<Map<String, Object>> getLatestPricingVersion(
            @PathVariable Long credentialId) {
        Optional<PlatformCredentialPricingVersion> latest =
                pricingService.findLatest(credentialId);
        if (latest.isEmpty()) {
            return ResponseEntity.ok(Map.of("found", false));
        }

        PlatformCredentialPricingVersion v = latest.get();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("found", true);
        response.put("pricingVersionId", v.getId());
        response.put("credentialId", v.getPlatformCredentialId());
        response.put("version", v.getVersion());
        response.put("defaultMarkupCredits", v.getDefaultMarkupCredits());
        response.put("createdAt", v.getCreatedAt());
        return ResponseEntity.ok(response);
    }

    private static Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Long.parseLong(text);
        }
        return null;
    }
}
