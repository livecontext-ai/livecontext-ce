package com.apimarketplace.auth.credential.web;

import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.CreatePlatformCredentialRequest;
import com.apimarketplace.auth.credential.domain.PlatformCredentialPricingVersion;
import com.apimarketplace.auth.credential.domain.PriceSpec;
import com.apimarketplace.auth.credential.domain.PriceUnit;
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
import java.util.List;
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
        // Filtered lookup: `name` is the requirement slug, so a credential merely LABELLED
        // with it (but belonging to another provider) must not answer the scope preflight -
        // it would compare one provider's granted scopes against another's requirement.
        return userCredentialService.findByNameIdentifyingIntegration(userId, name)
                .<ResponseEntity<Map<String, Object>>>map(c -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("type", c.type() != null ? c.type().name() : null);
                    // Only OAuth2 credentials have a meaningful scope set. For other types we
                    // return null so the catalog-side caller can no-op without misinterpreting
                    // an empty array as "credential has zero scopes."
                    boolean isOauth2 = c.type() != null && "oauth2".equalsIgnoreCase(c.type().name());
                    body.put("scopes", isOauth2 ? c.scopes() : null);
                    // WHICH credential this is, carrying nothing OF it. Lets a
                    // caller check that a pinned credential belongs to the
                    // endpoint it is about to be sent to without pulling the
                    // record, whose fields come back decrypted.
                    body.put("integration", c.integration());
                    body.put("name", c.name());
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
                    // WHICH credential this is, carrying nothing OF it. Lets a
                    // caller check that a pinned credential belongs to the
                    // endpoint it is about to be sent to without pulling the
                    // record, whose fields come back decrypted.
                    body.put("integration", c.integration());
                    body.put("name", c.name());
                    return ResponseEntity.ok(body);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Mark every usable credential of an integration as needing re-authentication, across tenants.
     * Called when an API's auth type changes during a catalog re-import: the stored shape may no
     * longer fit the new auth, so the credential must stop being USED.
     *
     * <p>There is deliberately NO delete-by-integration endpoint. The previous one ran
     * {@code DELETE FROM auth.credentials WHERE integration = ?} for every tenant, with no backup
     * and no confirmation, and a user lost a credential to it. A secret the platform destroys is
     * usually not recoverable by its owner, so an automated caller may take a credential out of
     * service but may never take it away.
     */
    @PostMapping("/by-integration/{integrationName}/needs-reauth")
    public ResponseEntity<Map<String, Object>> markNeedsReauthByIntegration(
            @PathVariable String integrationName) {
        if (integrationName == null || integrationName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "integrationName is required"));
        }
        int marked = userCredentialService.markNeedsReauthByIntegration(integrationName);
        log.info("Marked {} credential(s) as needs_reauth for integration '{}' (none deleted)",
                marked, integrationName);
        return ResponseEntity.ok(Map.of("marked", marked, "integration", integrationName));
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
            @RequestParam("apiToolId") String apiToolIdStr,
            // V428: a generation names its model and the size of the call. Both
            // optional, because every pre-existing caller is a flat per-call
            // debit and must keep behaving identically.
            @RequestParam(value = "modelId", required = false) String modelId,
            @RequestParam(value = "quantity", required = false) java.math.BigDecimal quantity,
            // What the call's own choices do to the published rate, measured by
            // the cloud from the body it was sent. Optional: absent means the
            // call sits at that rate, which is every pre-existing caller.
            @RequestParam(value = "priceMultiplier", required = false)
                    java.math.BigDecimal priceMultiplier) {
        UUID apiToolId;
        try {
            apiToolId = UUID.fromString(apiToolIdStr);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "apiToolId must be a UUID"));
        }
        if (quantity != null && quantity.signum() < 0) {
            // Never bill a negative amount, and never guess what was meant.
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "quantity must not be negative"));
        }
        String multiplierError = rejectAbsurdMultiplier(priceMultiplier);
        if (multiplierError != null) {
            return ResponseEntity.badRequest().body(Map.of("error", multiplierError));
        }

        Optional<FrozenMarkup> frozen = pricingVersionService.resolveFrozenMarkup(
                pricingVersionId, apiToolId, modelId, quantity, priceMultiplier);
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
        // The row SHAPE, not just the amount. This path carries no quantity, so a
        // per-unit row yields the price of ONE unit; a caller given only the
        // number would charge a ten second video as one second.
        response.put("priceUnit", f.priceUnit());
        response.put("unitCredits", f.unitCredits());
        // Whether the owner priced THIS endpoint or the amount fell back to the
        // credential-wide default. A default reports "call" and zero unit
        // credits, so without this it is indistinguishable from a flat price
        // chosen on purpose.
        response.put("pricedByPublishedRow", f.pricedByPublishedRow());
        // The factor this amount was resolved WITH, echoed for the same reason the scope-rate
        // answer echoes it: so the caller can explain the total rather than only state it.
        //
        // Given to the direct path and not to this one, which is the RELAY's leg. A relayed
        // generation was therefore charged a surcharge whose reason appeared in no log line and no
        // ledger row, and support answering "why 182 credits when the rate is 152 per second and
        // the clip was one second" could only re-derive it from the body.
        //
        // It is the factor this resolution was ASKED with, not proof that it moved the amount:
        // `MarkupPolicy` returns the credential-wide default untouched when no per-tool row
        // matched, and clamps to minCredits/maxCredits AFTER multiplying. On a clamped row the
        // amount is the ceiling and this number did not produce it. Reading it as "rate = total
        // divided by factor" is therefore wrong in exactly those two cases, which is why it is
        // named as the factor that was applied to the RESOLUTION rather than to the total.
        if (priceMultiplier != null) {
            response.put("priceMultiplier", priceMultiplier);
        }
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
        // The row SHAPE, not just the amount. This path carries no quantity, so a
        // per-unit row yields the price of ONE unit; a caller given only the
        // number would charge a ten second video as one second.
        response.put("priceUnit", f.priceUnit());
        response.put("unitCredits", f.unitCredits());
        // Whether the owner priced THIS endpoint or the amount fell back to the
        // credential-wide default. A default reports "call" and zero unit
        // credits, so without this it is indistinguishable from a flat price
        // chosen on purpose.
        response.put("pricedByPublishedRow", f.pricedByPublishedRow());
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
     *
     * <p><b>{@code quantity} is a PLATFORM measurement</b> (seconds, assets,
     * characters), never a count of the published unit: the caller cannot know
     * what the row is charged per, and pre-converting against the unit it
     * assumes is how a per-minute rate came to be multiplied by 60 seconds. The
     * {@code quantity} echoed back IS in the published unit, because by then it
     * has been converted here.
     */
    @GetMapping("/markup/scope-rate")
    public ResponseEntity<Map<String, Object>> resolveScopeMarkupRate(
            @RequestParam("scopeKind") String scopeKind,
            @RequestParam("scopeId") String scopeId,
            @RequestParam("userId") Long userId,
            @RequestParam("platformCredentialId") Long platformCredentialId,
            @RequestParam("apiToolId") String apiToolIdStr,
            @RequestParam(value = "modelId", required = false) String modelId,
            @RequestParam(value = "quantity", required = false) java.math.BigDecimal quantity,
            @RequestParam(value = "priceMultiplier", required = false)
                    java.math.BigDecimal priceMultiplier) {
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
        if (quantity != null && quantity.signum() < 0) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "quantity must be >= 0"));
        }
        // Refused rather than ignored. A factor outside the band cannot be derived
        // from any descriptor this platform accepts, so one arriving here means
        // something upstream is wrong; charging the unmodified amount anyway would
        // hide that behind a price that looks ordinary.
        String multiplierError = rejectAbsurdMultiplier(priceMultiplier);
        if (multiplierError != null) {
            return ResponseEntity.badRequest().body(Map.of("error", multiplierError));
        }

        Optional<PlatformCredentialPricingService.ResolvedMarkup> resolved =
                pricingService.resolveScopeMarkup(scopeKind, scopeId, userId,
                        platformCredentialId, apiToolId, modelId, quantity, priceMultiplier);
        if (resolved.isEmpty()) {
            return ResponseEntity.ok(Map.of("found", false));
        }
        PlatformCredentialPricingService.ResolvedMarkup r = resolved.get();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("found", true);
        response.put("pinId", r.pinId());
        response.put("pricingVersionId", r.pricingVersionId());
        response.put("effectiveMarkup", r.effectiveMarkup());
        // V428: the components the amount came from, so a surface can explain
        // the price rather than only state it. Absent when the version default
        // applied (there is no row to describe).
        // Say outright which of the two the amount came from. A caller that
        // must not sell an endpoint the owner never priced deliberately reads
        // this; everyone else ignores it, which is what the default is for.
        response.put("pricedByPublishedRow", r.entry() != null);
        if (r.entry() != null) {
            response.put("priceUnit", r.entry().getPriceUnit());
            response.put("baseCredits", r.entry().getMarkupCredits());
            response.put("unitCredits", r.entry().getUnitCredits());
            response.put("minCredits", r.entry().getMinCredits());
            response.put("maxCredits", r.entry().getMaxCredits());
        }
        if (r.quantity() != null) {
            response.put("quantity", r.quantity());
        }
        // Echoed so the caller can EXPLAIN the amount rather than only state it:
        // without it, a total that is not rate x quantity has no visible reason,
        // and the first assumption a reader makes about an unexplained total is
        // that one of the two numbers beside it is wrong.
        if (r.multiplier() != null) {
            response.put("priceMultiplier", r.multiplier());
        }
        return ResponseEntity.ok(response);
    }

    /**
     * V148+ pricing-version bootstrap. Idempotent: if the credential already
     * has any published version, returns it; else publishes v1 with the supplied
     * default + prices. Called by catalog-service's {@code ApiMigrationImporter}
     * after api_tools seed completes (migration-service can't do it because
     * Flyway runs before catalog seed).
     *
     * <p><b>Never overwrites.</b> A caller re-running the import after the
     * platform owner has tuned prices in the admin screens gets the existing
     * version back, unchanged. The seed only ever proposes a starting price.
     *
     * <p>Two ways to express a price, both accepted in the same request:
     * {@code perToolOverrides} is the pre-V428 flat {@code {toolId: credits}}
     * map, and {@code entries} carries the full V428 shape (model, unit, base,
     * per-unit rate, clamps). They are merged into one price list, so a caller
     * that only knows the old field keeps working verbatim.
     */
    @PostMapping("/pricing-versions/bootstrap")
    public ResponseEntity<Map<String, Object>> bootstrapPricingVersion(
            @RequestBody BootstrapPricingRequest request) {
        if (request.credentialId() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "credentialId required"));
        }
        List<PriceSpec> prices = new java.util.ArrayList<>();
        if (request.perToolOverrides() != null) {
            for (var e : request.perToolOverrides().entrySet()) {
                UUID toolId = parseToolId(e.getKey());
                if (toolId != null) {
                    prices.add(PriceSpec.flat(toolId, e.getValue()));
                }
            }
        }
        if (request.entries() != null) {
            for (BootstrapPriceEntry e : request.entries()) {
                if (e == null) continue;
                UUID toolId = parseToolId(e.apiToolId());
                if (toolId == null) continue;
                // Strict on the way IN, exactly as the admin API is. This is the
                // seed import, so a typo here would ship a mispriced generation
                // to every install rather than to one credential.
                prices.add(new PriceSpec(toolId, e.modelId(),
                        PriceUnit.parseStrict(e.priceUnit()).wire(),
                        e.baseCredits(), e.unitCredits(), e.minCredits(), e.maxCredits()));
            }
        }
        try {
            PlatformCredentialPricingVersion v = pricingService.bootstrapV1IfAbsent(
                    request.credentialId(),
                    request.defaultMarkupCredits(),
                    prices,
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

    /**
     * A tool key that is not a UUID is catalog drift, never a reason to fail the
     * whole bootstrap: the remaining prices still deserve to be published.
     */
    /**
     * The wire unit of a bundle price row, or null when this build cannot price
     * by it and the row must therefore be left alone.
     *
     * <p>Absent means flat, which is a real answer and not an unknown one: a row
     * that names no unit is charged per call, so it publishes normally.
     */
    private static String normalisedBundleUnit(String rawUnit) {
        if (rawUnit == null || rawUnit.isBlank()) {
            return PriceUnit.CALL.wire();
        }
        try {
            return PriceUnit.parseStrict(rawUnit).wire();
        } catch (RuntimeException unknownToThisBuild) {
            return null;
        }
    }

    private UUID parseToolId(String raw) {
        if (raw == null || raw.isBlank()) {
            log.warn("bootstrapPricingVersion: skipping price with no apiToolId");
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException nfe) {
            log.warn("bootstrapPricingVersion: skipping non-UUID tool key {}", raw);
            return null;
        }
    }

    public record BootstrapPricingRequest(
            Long credentialId,
            java.math.BigDecimal defaultMarkupCredits,
            Map<String, java.math.BigDecimal> perToolOverrides,
            List<BootstrapPriceEntry> entries,
            String createdBy
    ) {
    }

    /**
     * V428 wire form of one price row. {@code modelId} null = the endpoint-wide
     * price; {@code priceUnit} null = {@code call}, i.e. a flat amount.
     */
    public record BootstrapPriceEntry(
            String apiToolId,
            String modelId,
            String priceUnit,
            java.math.BigDecimal baseCredits,
            java.math.BigDecimal unitCredits,
            java.math.BigDecimal minCredits,
            java.math.BigDecimal maxCredits
    ) {
    }

    // ===== V430: generation prices travelling in the signed API-catalog bundle =====

    /**
     * PUBLISHER half. The prices the platform owner has published for a set of
     * generation endpoints, keyed by INTEGRATION NAME rather than by credential
     * id.
     *
     * <p>The id is a serial that means something different on every install, so
     * it cannot travel; the integration name is the same key
     * {@code /platform/by-name} resolves and the same one the catalog stores on
     * {@code apis.platform_credential_name}, which is what makes the price
     * re-attachable on the far side.
     *
     * <p>The caller states BOTH what it is asking about (the endpoints it found
     * carrying a generation descriptor) and where to look (the integrations
     * those endpoints belong to). Neither is inferred here: a request that
     * named nothing would otherwise be answered with the platform owner's whole
     * price list, and the whole list is not what a catalog bundle distributes.
     */
    @PostMapping("/pricing-versions/published-prices")
    public ResponseEntity<Map<String, Object>> publishedPrices(
            @RequestBody PublishedPricesRequest request) {
        java.util.Set<UUID> toolIds = new java.util.LinkedHashSet<>();
        if (request != null && request.apiToolIds() != null) {
            for (String raw : request.apiToolIds()) {
                UUID id = parseToolId(raw);
                if (id != null) toolIds.add(id);
            }
        }
        List<Map<String, Object>> prices = new java.util.ArrayList<>();
        if (request != null && request.integrationNames() != null && !toolIds.isEmpty()) {
            for (String integrationName : new java.util.LinkedHashSet<>(request.integrationNames())) {
                if (integrationName == null || integrationName.isBlank()) continue;
                Optional<com.apimarketplace.auth.credential.domain.PlatformCredentialModels.PlatformCredential>
                        credential = platformCredentialService.getRawCredential(integrationName);
                if (credential.isEmpty()) continue;
                for (var e : pricingService.findLatestPublishedPrices(credential.get().id(), toolIds)) {
                    prices.add(priceRow(integrationName, e));
                }
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("prices", prices);
        return ResponseEntity.ok(body);
    }

    /**
     * Amounts are emitted as PLAIN STRINGS, never as JSON numbers. The bundle
     * that carries them is signed byte-for-byte, and a rate that round-trips
     * through a double is not the rate that was signed.
     */
    private static Map<String, Object> priceRow(
            String integrationName,
            com.apimarketplace.auth.credential.domain.PricingVersionEntry e) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("integrationName", integrationName);
        row.put("apiToolId", String.valueOf(e.getApiToolId()));
        if (e.getModelId() != null) row.put("modelId", e.getModelId());
        row.put("priceUnit", e.unit().wire());
        row.put("baseCredits", plain(e.getMarkupCredits()));
        row.put("unitCredits", plain(e.getUnitCredits()));
        if (e.getMinCredits() != null) row.put("minCredits", plain(e.getMinCredits()));
        if (e.getMaxCredits() != null) row.put("maxCredits", plain(e.getMaxCredits()));
        return row;
    }

    private static String plain(java.math.BigDecimal value) {
        return value == null ? "0" : value.toPlainString();
    }

    /**
     * CONSUMER half. Apply the prices a verified API-catalog bundle carried.
     *
     * <p>Grouped by integration name and applied one credential at a time, so
     * an integration this install has no platform key for (nothing to hang a
     * price off) and a bundle row that contradicts a live unit both cost their
     * own credential only, never the rest of the catalog's prices.
     *
     * <p>Idempotent by construction: {@code applyBundlePrices} publishes nothing
     * when nothing changed, so the caller may re-offer the same prices on every
     * sync tick. That is what eventually prices an integration whose platform
     * key the operator pastes long after the bundle carrying its price landed.
     */
    @PostMapping("/pricing-versions/apply-catalog-bundle")
    public ResponseEntity<Map<String, Object>> applyCatalogBundlePrices(
            @RequestBody ApplyBundlePricesRequest request) {
        if (request == null || request.prices() == null || request.prices().isEmpty()) {
            return ResponseEntity.ok(applyResponse(0, 0, 0, 0, List.of()));
        }
        // The label the pricing history shows. Two producers write bundle-owned
        // prices: the signed bundle (no origin, the default) and the boot-time
        // generation seed shipped inside a self-hosted image, which names itself
        // so an operator is not sent looking for a bundle version that never
        // existed. The row's source is BUNDLE either way - that is the statement
        // "not decided here", and it is what lets an admin edit survive.
        String origin = request.origin() == null || request.origin().isBlank()
                ? "api-catalog-bundle"
                : request.origin().trim();
        String createdBy = origin
                + (request.bundleVersion() == null ? "" : " v" + request.bundleVersion());

        Map<String, List<PriceSpec>> byIntegration = new LinkedHashMap<>();
        for (BundlePriceEntry e : request.prices()) {
            if (e == null || e.integrationName() == null || e.integrationName().isBlank()) continue;
            UUID toolId = parseToolId(e.apiToolId());
            if (toolId == null) continue;
            // A unit this build does not know means a NEWER cloud, not a typo:
            // the payload is signed, so nobody hand-wrote it. That reading is
            // right, and the conclusion drawn from it was not.
            //
            // Degrading the row to a flat 'call' price KEEPS unitCredits while
            // discarding what it counts, so a per-second row at 60 bills 60
            // for a ten second clip instead of 600, always in the undercharging
            // direction, and validateUnitChange accepts "-> CALL"
            // unconditionally so the degraded row REPLACES the correct live one.
            // The choice was framed as "degrade or drop the whole integration's
            // prices", which is a false pair: dropping THIS ROW alone leaves
            // every sibling published and lets carry-forward keep the row's own
            // last known-good price, which is the outcome the signed payload
            // deserves.
            String unit = normalisedBundleUnit(e.priceUnit());
            if (unit == null) {
                log.warn("Skipping bundle price for tool {} (integration {}): price unit '{}' is not one "
                                + "this build understands, so its previous price is kept rather than "
                                + "republished at a flat rate.",
                        toolId, e.integrationName().trim(), e.priceUnit());
                continue;
            }
            byIntegration.computeIfAbsent(e.integrationName().trim(), k -> new java.util.ArrayList<>())
                    .add(new PriceSpec(toolId, e.modelId(), unit,
                            e.baseCredits(), e.unitCredits(), e.minCredits(), e.maxCredits(),
                            com.apimarketplace.auth.credential.domain.PriceSource.BUNDLE));
        }

        int publishedCredentials = 0;
        int applied = 0;
        int preserved = 0;
        int skipped = 0;
        List<String> failures = new java.util.ArrayList<>();
        for (var entry : byIntegration.entrySet()) {
            Optional<com.apimarketplace.auth.credential.domain.PlatformCredentialModels.PlatformCredential>
                    credential = platformCredentialService.getRawCredential(entry.getKey());
            if (credential.isEmpty()) {
                // Nothing is resold for an integration this install has no key
                // for, so an unpriced one is not a failure. The next tick tries
                // again, which is what prices it once the key exists.
                skipped++;
                log.debug("Catalog-bundle prices: no platform credential '{}' - {} price(s) not published",
                        entry.getKey(), entry.getValue().size());
                continue;
            }
            try {
                var result = pricingService.applyBundlePrices(
                        credential.get().id(), entry.getValue(), createdBy);
                if (result.published()) publishedCredentials++;
                applied += result.applied();
                preserved += result.preserved();
            } catch (IllegalArgumentException ex) {
                failures.add(entry.getKey() + ": " + ex.getMessage());
                log.warn("Catalog-bundle prices rejected for integration '{}': {}",
                        entry.getKey(), ex.getMessage());
            }
        }
        return ResponseEntity.ok(
                applyResponse(publishedCredentials, applied, preserved, skipped, failures));
    }

    private static Map<String, Object> applyResponse(int publishedCredentials, int applied,
                                                      int preserved, int skipped, List<String> failures) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("publishedCredentials", publishedCredentials);
        body.put("appliedPrices", applied);
        body.put("preservedLocalPrices", preserved);
        body.put("skippedIntegrations", skipped);
        body.put("failures", failures);
        return body;
    }

    /** Endpoints and integrations the caller wants the published prices of. */
    public record PublishedPricesRequest(List<String> integrationNames, List<String> apiToolIds) {
    }

    /**
     * Prices a verified API-catalog bundle carried, plus the version that
     * carried them.
     *
     * @param origin optional author label for the published pricing version.
     *               Absent (the bundle's own case) reads as
     *               {@code api-catalog-bundle}. It names the writer only; the
     *               per-row provenance is BUNDLE for every caller of this
     *               endpoint, which is what makes an admin-published row
     *               survive them all.
     */
    public record ApplyBundlePricesRequest(Long bundleVersion, List<BundlePriceEntry> prices,
                                            String origin) {

        /** Back-compat constructor for the signed-bundle caller, which has no origin. */
        public ApplyBundlePricesRequest(Long bundleVersion, List<BundlePriceEntry> prices) {
            this(bundleVersion, prices, null);
        }
    }

    /**
     * One price as it travels in the bundle. Identical to
     * {@link BootstrapPriceEntry} plus {@code integrationName}, which is what
     * replaces the install-local credential id.
     */
    public record BundlePriceEntry(
            String integrationName,
            String apiToolId,
            String modelId,
            String priceUnit,
            java.math.BigDecimal baseCredits,
            java.math.BigDecimal unitCredits,
            java.math.BigDecimal minCredits,
            java.math.BigDecimal maxCredits
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

    /**
     * Why an absurd price factor is REFUSED here and dropped on the quote doors.
     *
     * <p>These two endpoints decide an amount. A factor they cannot explain means
     * something upstream is wrong, and charging the unmodified price anyway would
     * hide that behind a number that looks ordinary. The quote endpoints make the
     * opposite trade on purpose: they only read, so a malformed factor there must
     * leave the reader looking at the published rate rather than at an error.
     *
     * <p>The band is the descriptor parser's own, through the one shared constant:
     * the parser refuses a model whose modifiers can reach more than that TOGETHER,
     * so nothing inside it can produce a factor above it. Only {@code <= 0} was
     * checked before, which let a caller that reached this port directly name a
     * factor of a million on a row with no maxCredits.
     *
     * @return the error to answer with, or {@code null} when the factor is usable
     */
    private static String rejectAbsurdMultiplier(java.math.BigDecimal priceMultiplier) {
        if (priceMultiplier == null) {
            return null;
        }
        if (priceMultiplier.signum() <= 0) {
            return "priceMultiplier must be > 0";
        }
        if (priceMultiplier.compareTo(
                com.apimarketplace.common.web.BillingContextHeaders.MAX_GENERATION_MULTIPLIER) > 0) {
            return "priceMultiplier must be <= "
                    + com.apimarketplace.common.web.BillingContextHeaders.MAX_GENERATION_MULTIPLIER
                            .toPlainString();
        }
        return null;
    }
}
