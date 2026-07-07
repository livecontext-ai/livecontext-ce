package com.apimarketplace.auth.credential.web;

import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.*;
import com.apimarketplace.auth.credential.domain.PlatformCredentialPricingVersion;
import com.apimarketplace.auth.credential.service.CredentialService;
import com.apimarketplace.auth.credential.service.PlatformCredentialPricingService;
import com.apimarketplace.auth.credential.service.PlatformCredentialService;
import com.apimarketplace.auth.credential.service.TooManyByokAppsException;
import com.apimarketplace.auth.credential.web.dto.MyOAuthAppDto;
import com.apimarketplace.common.credential.CloudPlatformCredentialInfoAccess;
import com.apimarketplace.common.web.AdminRoleGuard;
import com.apimarketplace.common.web.TenantResolver;
import com.fasterxml.jackson.databind.node.NullNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * REST Controller for platform credential management.
 * These are the OAuth2 Client ID/Secret and API Keys owned by the platform.
 */
@RestController
@RequestMapping("/api/platform-credentials")
public class PlatformCredentialsController {

    private static final Logger log = LoggerFactory.getLogger(PlatformCredentialsController.class);

    private final PlatformCredentialService service;
    private final PlatformCredentialPricingService pricingService;
    private final CredentialService credentialService;
    private final TenantResolver tenantResolver;
    // Optional CE-only bridge to the cloud's platform-credential public info (bean
    // absent on the cloud deployment - see CloudPlatformCredentialInfoAccess).
    private final ObjectProvider<CloudPlatformCredentialInfoAccess> cloudPlatformInfoAccess;

    public PlatformCredentialsController(PlatformCredentialService service,
                                          PlatformCredentialPricingService pricingService,
                                          CredentialService credentialService,
                                          TenantResolver tenantResolver,
                                          ObjectProvider<CloudPlatformCredentialInfoAccess> cloudPlatformInfoAccess) {
        this.service = service;
        this.pricingService = pricingService;
        this.credentialService = credentialService;
        this.tenantResolver = tenantResolver;
        this.cloudPlatformInfoAccess = cloudPlatformInfoAccess;
    }

    /**
     * GET /api/platform-credentials - List all platform credentials
     */
    @GetMapping
    public ResponseEntity<?> listAll(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @RequestParam(required = false) String category
    ) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;
        List<PlatformCredentialResponse> credentials;
        if (category != null && !category.isBlank()) {
            credentials = service.getCredentialsByCategory(category);
        } else {
            credentials = service.getAllCredentials();
        }
        return ResponseEntity.ok(credentials);
    }

    /**
     * GET /api/platform-credentials/categories - Get all categories with counts
     */
    @GetMapping("/categories")
    public ResponseEntity<?> getCategories(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;
        return ResponseEntity.ok(service.getCategoryInfo());
    }

    /**
     * GET /api/platform-credentials/{integrationName} - Get single credential
     */
    @GetMapping("/{integrationName}")
    public ResponseEntity<?> getByIntegration(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @PathVariable String integrationName
    ) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;
        return service.getCredential(integrationName)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * POST /api/platform-credentials - Create or update credential
     */
    @PostMapping
    public ResponseEntity<?> save(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @RequestBody CreatePlatformCredentialRequest request
    ) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;
        log.info("Saving platform credential: {}", request.integrationName());
        var response = service.saveCredential(request);
        return ResponseEntity.ok(response);
    }

    /**
     * PUT /api/platform-credentials/{integrationName} - Update credential
     */
    @PutMapping("/{integrationName}")
    public ResponseEntity<?> update(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @PathVariable String integrationName,
            @RequestBody UpdatePlatformCredentialRequest request
    ) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;
        log.info("Updating platform credential: {}", integrationName);
        return service.updateCredential(integrationName, request)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * DELETE /api/platform-credentials/{integrationName} - Delete credential
     */
    @DeleteMapping("/{integrationName}")
    public ResponseEntity<?> delete(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @PathVariable String integrationName
    ) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;
        log.info("Deleting platform credential: {}", integrationName);
        boolean deleted = service.deleteCredential(integrationName);
        return ResponseEntity.ok(Map.of(
                "deleted", deleted,
                "integrationName", integrationName
        ));
    }

    /**
     * PUT /api/platform-credentials/{integrationName}/enable - Enable credential
     */
    @PutMapping("/{integrationName}/enable")
    public ResponseEntity<?> enable(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @PathVariable String integrationName
    ) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;
        boolean success = service.setEnabled(integrationName, true);
        return ResponseEntity.ok(Map.of(
                "success", success,
                "integrationName", integrationName,
                "enabled", true
        ));
    }

    /**
     * PUT /api/platform-credentials/{integrationName}/disable - Disable credential
     */
    @PutMapping("/{integrationName}/disable")
    public ResponseEntity<?> disable(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @PathVariable String integrationName
    ) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;
        boolean success = service.setEnabled(integrationName, false);
        return ResponseEntity.ok(Map.of(
                "success", success,
                "integrationName", integrationName,
                "enabled", false
        ));
    }

    /**
     * PUT /api/platform-credentials/{integrationName}/{variant}/enable - Phase 2d
     * per-variant toggle. Toggles exactly the one row matching the UNIQUE
     * {@code (integration_name, variant)} key. Returns 404 if no row matched
     * so the admin UI can show a stale-row warning and refresh the list.
     */
    @PutMapping("/{integrationName}/{variant}/enable")
    public ResponseEntity<?> enableVariant(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @PathVariable String integrationName,
            @PathVariable String variant
    ) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;
        return toggleVariant(integrationName, variant, true);
    }

    /**
     * PUT /api/platform-credentials/{integrationName}/{variant}/disable - Phase 2d
     * per-variant toggle (disable path).
     */
    @PutMapping("/{integrationName}/{variant}/disable")
    public ResponseEntity<?> disableVariant(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @PathVariable String integrationName,
            @PathVariable String variant
    ) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;
        return toggleVariant(integrationName, variant, false);
    }

    private ResponseEntity<?> toggleVariant(String integrationName, String variant, boolean enabled) {
        boolean success = service.setVariantEnabled(integrationName, variant, enabled);
        if (!success) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "Platform credential variant not found",
                    "integrationName", integrationName,
                    "variant", variant
            ));
        }
        log.info("Toggled platform credential variant {}:{} → enabled={}",
                integrationName, variant, enabled);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "integrationName", integrationName,
                "variant", variant,
                "enabled", enabled
        ));
    }

    /**
     * GET /api/platform-credentials/{integrationName}/endpoints - Get endpoints
     */
    @GetMapping("/{integrationName}/endpoints")
    public ResponseEntity<?> getEndpoints(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @PathVariable String integrationName
    ) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;
        return ResponseEntity.ok(service.getEndpoints(integrationName));
    }

    /**
     * PUT /api/platform-credentials/{integrationName}/endpoints/{toolId}/toggle - Toggle endpoint
     */
    @PutMapping("/{integrationName}/endpoints/{toolId}/toggle")
    public ResponseEntity<?> toggleEndpoint(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @PathVariable String integrationName,
            @PathVariable String toolId,
            @RequestBody ToggleEndpointRequest request
    ) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;
        log.info("Toggling endpoint {} for {}: {}", toolId, integrationName, request.enabled());
        boolean success = service.toggleEndpoint(integrationName, toolId, request.enabled());
        return ResponseEntity.ok(Map.of(
                "success", success,
                "integrationName", integrationName,
                "toolId", toolId,
                "enabled", request.enabled()
        ));
    }

    /**
     * GET /api/platform-credentials/{integrationName}/has-credentials - Check if configured
     */
    @GetMapping("/{integrationName}/has-credentials")
    public ResponseEntity<Map<String, Object>> hasCredentials(
            @PathVariable String integrationName
    ) {
        boolean hasDb = service.hasDbCredentials(integrationName);
        boolean hasAny = service.hasOAuth2Credentials(integrationName);
        return ResponseEntity.ok(Map.of(
                "integrationName", integrationName,
                "hasDbCredentials", hasDb,
                "hasCredentials", hasAny,
                "source", hasDb ? "database" : (hasAny ? "config" : "none")
        ));
    }

    /**
     * GET /api/platform-credentials/my - list the requesting tenant's custom OAuth
     * connections (BYOK rows), without secrets.
     *
     * <p>User-facing. Returns only tenant-owned rows - platform-wide
     * ({@code tenant_id IS NULL}) rows are intentionally excluded so a regular
     * user never sees the global LiveContext OAuth apps. Each row is mapped
     * through {@link MyOAuthAppDto#from} which uses an explicit allowlist of
     * fields ({@code MyOAuthAppDtoLeakTest} is the regression guard).
     *
     * <p>Requires {@code X-Authenticated: true} from the gateway. Anonymous
     * requests can never reach a 200 here even if the gateway is misconfigured;
     * the per-tenant {@code findOwnedByTenant} also returns nothing for a blank
     * tenant id, so the worst-case body is {@code []}.
     */
    @GetMapping("/my")
    public ResponseEntity<?> listMy(
            HttpServletRequest httpRequest,
            @RequestHeader(value = "X-Authenticated", required = false) String authenticated,
            @RequestHeader("X-User-ID") String tenantId
    ) {
        if (!"true".equalsIgnoreCase(authenticated)) {
            return ResponseEntity.status(401).body(Map.of("error", "authentication required"));
        }
        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "X-User-ID header is required"));
        }
        // V362: scope the listing to the active workspace (null = personal scope).
        String organizationId = tenantResolver.resolveOrgId(httpRequest);
        List<MyOAuthAppDto> apps = service.findOwnedByTenant(tenantId, organizationId).stream()
                .map(MyOAuthAppDto::from)
                .toList();
        return ResponseEntity.ok(apps);
    }

    /**
     * POST /api/platform-credentials/my - Create or update a tenant-scoped platform credential.
     * User-accessible (no admin check). The credential is scoped to the requesting user's tenant.
     *
     * <p>Insertion of a brand-new row is capped at {@link PlatformCredentialService#MAX_BYOK_PER_TENANT}
     * per tenant; updates of an existing row are unrestricted. Hitting the cap
     * surfaces as HTTP 409 with a stable {@code error: "too_many_byok_apps"} code
     * so the frontend can present a deterministic message.
     */
    @PostMapping("/my")
    public ResponseEntity<?> saveMy(
            HttpServletRequest httpRequest,
            @RequestHeader("X-User-ID") String tenantId,
            @RequestBody CreatePlatformCredentialRequest request
    ) {
        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "X-User-ID header is required"));
        }
        if (request.integrationName() == null || request.integrationName().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "integrationName is required"));
        }
        // V362: tag the row with the active workspace (null = personal scope).
        String organizationId = tenantResolver.resolveOrgId(httpRequest);
        log.info("Saving tenant platform credential '{}' for tenant {} (org {})",
                request.integrationName(), tenantId, organizationId);
        try {
            var response = service.saveCredential(request, tenantId, organizationId);
            return ResponseEntity.ok(MyOAuthAppDto.from(response));
        } catch (TooManyByokAppsException ex) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", "too_many_byok_apps",
                    "message", ex.getMessage(),
                    "maxAllowed", ex.maxAllowed()
            ));
        }
    }

    /**
     * GET /api/platform-credentials/my/{integrationName}/delete-impact - peek the
     * cascade effect before the user confirms deletion.
     *
     * <p>Returns the count of dependent {@code auth.credentials} rows that will
     * actually be transitioned to {@code needs_reauth} when the BYOK
     * platform_credential is deleted. Counts are filtered to {@code active}
     * and {@code expiring} only - rows already in {@code error} or
     * {@code needs_reauth} are intentionally excluded so the displayed number
     * matches what the cascade truly affects (no over-reporting on
     * already-terminal rows). Capped at 999 in the response so a precise
     * tenant-size signal cannot be fingerprinted by an external observer;
     * truncation is flagged separately.
     *
     * <p>The match uses normalized-integration comparison (strip non-alphanumeric,
     * lowercase) so an integration with a non-trivial iconSlug like
     * {@code "audit-tracking"} or {@code "azure_translator"} resolves correctly
     * against its normalized BYOK row name - see
     * {@link com.apimarketplace.auth.credential.repository.CredentialRepository#countActiveByTenantIdAndIntegrationNormalized}.
     */
    @GetMapping("/my/{integrationName}/delete-impact")
    public ResponseEntity<?> deleteImpact(
            @RequestHeader(value = "X-Authenticated", required = false) String authenticated,
            @RequestHeader("X-User-ID") String tenantId,
            @PathVariable String integrationName
    ) {
        if (!"true".equalsIgnoreCase(authenticated)) {
            return ResponseEntity.status(401).body(Map.of("error", "authentication required"));
        }
        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "X-User-ID header is required"));
        }
        int affected = credentialService.countDependentForByokDelete(tenantId, integrationName);
        int displayed = Math.min(affected, 999);
        boolean truncated = affected > 999;
        return ResponseEntity.ok(Map.of(
                "integrationName", integrationName,
                "affectedCredentialCount", displayed,
                "truncated", truncated
        ));
    }

    /**
     * DELETE /api/platform-credentials/my/{integrationName} - Delete a tenant-scoped platform credential.
     * User-accessible (no admin check). Only deletes the credential if it belongs to the requesting tenant.
     *
     * <p><b>Cascade contract.</b> Dependent user credentials are revoked FIRST
     * (status flipped to {@code needs_reauth}, inline {@code oauth_client_secret}
     * scrubbed, OAuth2 fast-path Redis sentinels invalidated), THEN the BYOK row
     * itself is removed. The order matters: a partial failure mid-cascade leaves
     * the BYOK row intact so the user can retry, never an orphan with revoked
     * dependents and a still-live BYOK row.
     *
     * <p>Wrapped in {@code @Transactional} so DB rollback covers both the dependent
     * UPDATEs and the BYOK DELETE. Redis side-effects (refresh-disabled / refresh-cooldown
     * sentinel deletes) ARE issued from inside the transaction by
     * {@link CredentialService#revokeForByokDelete}, but they are NOT rolled back on
     * abort - Redis has no transactional boundary in this codebase. The asymmetry
     * is intentional and benign: on rollback the DB row reverts to {@code active},
     * and the worst-case Redis state (empty sentinel) makes the fast-path gate fall
     * back to DB on the next refresh attempt - which sees {@code active} and proceeds
     * normally. A redundant Redis delete on a follow-up successful retry is also
     * benign. The DB status flip is always the source of truth.
     */
    @DeleteMapping("/my/{integrationName}")
    @Transactional
    public ResponseEntity<?> deleteMy(
            HttpServletRequest httpRequest,
            @RequestHeader(value = "X-Authenticated", required = false) String authenticated,
            @RequestHeader("X-User-ID") String tenantId,
            @PathVariable String integrationName
    ) {
        if (!"true".equalsIgnoreCase(authenticated)) {
            return ResponseEntity.status(401).body(Map.of("error", "authentication required"));
        }
        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "X-User-ID header is required"));
        }
        // V362: delete only THIS workspace's BYOK row (null = personal scope).
        String organizationId = tenantResolver.resolveOrgId(httpRequest);
        log.info("Deleting tenant platform credential '{}' for tenant {} (org {})",
                integrationName, tenantId, organizationId);

        // The dependent-token revoke cascade stays tenant-keyed for now (it
        // operates on auth.credentials and is recoverable via re-auth); the BYOK
        // row removal itself is workspace-scoped.
        int revokedCredentialCount = credentialService.revokeForByokDelete(tenantId, integrationName);
        boolean deleted = service.deleteCredential(integrationName, tenantId, organizationId);

        return ResponseEntity.ok(Map.of(
                "deleted", deleted,
                "integrationName", integrationName,
                "revokedCredentialCount", revokedCredentialCount
        ));
    }

    /**
     * GET /api/platform-credentials/{integrationName}/public-info - minimal,
     * non-admin view used by the workflow inspector to decide whether a user
     * can switch an MCP step to the "platform credential" source.
     *
     * <p>Returns only non-secret fields: the credential id, whether it is
     * enabled with a configured secret, and the default markup (if a pricing
     * version has been published). Shape is deliberately small - no client id,
     * no scopes, no endpoints.
     */
    @GetMapping("/{integrationName}/public-info")
    public ResponseEntity<Map<String, Object>> publicInfo(
            @PathVariable String integrationName,
            @RequestParam(value = "apiToolId", required = false) String apiToolIdRaw
    ) {
        var credOpt = service.getCredential(integrationName);
        if (credOpt.isEmpty()) {
            // CE-only: no LOCAL platform credential row - ask the cloud whether ITS
            // platform credential can be relayed for this integration (bean absent on
            // the cloud deployment, empty on unlinked/BYOK/failure = legacy shape).
            Optional<Map<String, Object>> cloudInfo = cloudRelayPublicInfo(integrationName, apiToolIdRaw);
            if (cloudInfo.isPresent()) {
                return ResponseEntity.ok(cloudInfo.get());
            }
            return ResponseEntity.ok(Map.of(
                    "integrationName", integrationName,
                    "available", false,
                    "showUnverifiedAppWarning", false,
                    "hasPricing", false
            ));
        }
        var cred = credOpt.get();
        boolean hasSecret = cred.hasClientSecret() || cred.hasApiKey() || cred.hasBasicAuth();
        boolean available = cred.isEnabled() && hasSecret;
        var latest = pricingService.findLatest(cred.id());

        Map<String, Object> out = new HashMap<>();
        out.put("integrationName", cred.integrationName());
        out.put("platformCredentialId", cred.id());
        out.put("available", available);
        out.put("showUnverifiedAppWarning",
                available && "oauth2".equalsIgnoreCase(cred.authType()) && cred.showUnverifiedAppWarning());

        // When a specific tool is supplied, report pricing for THAT endpoint so
        // the inspector can hide the toggle on tools without a non-zero rate,
        // even if the API as a whole has some pricing. When no tool is
        // supplied, fall back to integration-level "has any non-zero rate".
        UUID apiToolId = parseUuid(apiToolIdRaw);
        if (apiToolId != null) {
            var perToolMarkup = pricingService.resolveLatestMarkupForTool(cred.id(), apiToolId);
            boolean hasPricing = perToolMarkup.isPresent() && perToolMarkup.get().signum() > 0;
            out.put("hasPricing", hasPricing);
            if (hasPricing) {
                out.put("markupCredits", formatDecimal(perToolMarkup.get()));
            }
        } else {
            out.put("hasPricing", pricingService.hasAnyNonZeroMarkup(cred.id()));
        }

        if (latest.isPresent()) {
            BigDecimal def = latest.get().getDefaultMarkupCredits();
            if (def != null) {
                out.put("defaultMarkupCredits", formatDecimal(def));
            }
            out.put("pricingVersion", latest.get().getVersion());
        }
        return ResponseEntity.ok(out);
    }

    /**
     * CE cloud-relay half of {@link #publicInfo}. Consulted ONLY when no local platform
     * credential row exists (local always wins). Maps the cloud's platform-info payload
     * onto the public-info response shape:
     * <ul>
     *   <li>Cloud credential available + active subscription + relay-eligible → the
     *       builder toggle unlocks exactly as if the credential were local
     *       ({@code available:true} + id + pricing), tagged {@code cloudRelay:true}.</li>
     *   <li>Cloud credential available but no active subscription → stays
     *       {@code available:false} but carries {@code subscriptionRequired:true} so the
     *       frontend can upsell the linked-account upgrade.</li>
     *   <li>Anything else (bean absent, unlinked, BYOK source, transport failure,
     *       credential not offered) → empty, caller returns the legacy not-found shape.</li>
     * </ul>
     */
    private Optional<Map<String, Object>> cloudRelayPublicInfo(String integrationName, String apiToolIdRaw) {
        if (cloudPlatformInfoAccess == null) {
            return Optional.empty();
        }
        CloudPlatformCredentialInfoAccess access = cloudPlatformInfoAccess.getIfAvailable();
        if (access == null) {
            return Optional.empty();
        }
        Map<String, Object> info;
        try {
            info = access.fetchPlatformInfo(integrationName, apiToolIdRaw).orElse(null);
        } catch (RuntimeException e) {
            log.debug("public-info: cloud platform-info delegation failed for '{}': {}",
                    integrationName, e.getMessage());
            return Optional.empty();
        }
        if (info == null) {
            return Optional.empty();
        }

        boolean available = Boolean.TRUE.equals(info.get("available"));
        boolean subscriptionActive = Boolean.TRUE.equals(info.get("subscriptionActive"));
        boolean relayEligible = Boolean.TRUE.equals(info.get("relayEligible"));

        if (available && subscriptionActive && relayEligible) {
            Map<String, Object> out = new HashMap<>();
            out.put("integrationName", integrationName);
            out.put("available", true);
            out.put("platformCredentialId", info.get("platformCredentialId"));
            out.put("hasPricing", Boolean.TRUE.equals(info.get("hasPricing")));
            Object markupCredits = info.get("markupCredits");
            if (markupCredits != null) {
                out.put("markupCredits", String.valueOf(markupCredits));
            }
            out.put("showUnverifiedAppWarning", false);
            out.put("cloudRelay", true);
            return Optional.of(out);
        }

        if (available && !subscriptionActive) {
            // Toggle stays locked (available:false) but the frontend gets the upsell hook.
            Map<String, Object> out = new HashMap<>();
            out.put("integrationName", integrationName);
            out.put("available", false);
            out.put("showUnverifiedAppWarning", false);
            out.put("hasPricing", false);
            out.put("cloudRelay", true);
            out.put("subscriptionRequired", true);
            return Optional.of(out);
        }

        return Optional.empty();
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            // Silent fallback to integration-level hasPricing is the contract
            // (see invalidToolId_fallsBackToIntegrationWide). Log at debug so
            // forensics still has a breadcrumb when a corrupted node keeps
            // reporting a garbage apiToolId.
            log.debug("public-info: ignoring unparseable apiToolId '{}'", raw);
            return null;
        }
    }

    // ========== Markup pricing (V63) ==========

    /**
     * POST /api/platform-credentials/{id}/pricing-versions - publish a new
     * immutable pricing version for the credential.
     *
     * <p>Body: {@code {"defaultMarkupCredits": "0.05", "overrides":
     * {"<apiToolId-uuid>": "0.10"}}}. Serialized under an advisory lock to
     * keep version numbers monotonic.
     */
    @PostMapping("/{id}/pricing-versions")
    public ResponseEntity<?> publishPricingVersion(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;

        // Null / missing / empty-string defaultMarkupCredits is valid and means
        // "no API-wide default - only per-tool overrides apply". The service
        // layer rejects the degenerate case (null default AND no overrides).
        BigDecimal defaultMarkup;
        Map<UUID, BigDecimal> overrides;
        try {
            defaultMarkup = parseBigDecimalNullable(body.get("defaultMarkupCredits"));
            overrides = parseOverrides(body.get("overrides"));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }

        try {
            PlatformCredentialPricingVersion saved = pricingService.publishNextVersion(
                    id, defaultMarkup, overrides, userId);
            return ResponseEntity.ok(toPricingResponse(saved));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    /**
     * GET /api/platform-credentials/{id}/pricing-versions/latest
     */
    @GetMapping("/{id}/pricing-versions/latest")
    public ResponseEntity<?> getLatestPricingVersion(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @PathVariable Long id) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;
        return pricingService.findLatest(id)
                .<ResponseEntity<?>>map(v -> ResponseEntity.ok(toPricingResponse(v)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /api/platform-credentials/{id}/pricing-versions - full history,
     * newest version first. Each entry includes its overrides so the admin
     * UI can render the complete snapshot without a second round-trip.
     */
    @GetMapping("/{id}/pricing-versions")
    public ResponseEntity<?> listPricingVersions(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @PathVariable Long id) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;
        List<Map<String, Object>> out = pricingService.findAllVersions(id).stream()
                .map(this::toPricingResponse)
                .toList();
        return ResponseEntity.ok(out);
    }

    private Map<String, Object> toPricingResponse(PlatformCredentialPricingVersion v) {
        Map<String, Object> out = new HashMap<>();
        out.put("id", v.getId());
        out.put("credentialId", v.getPlatformCredentialId());
        out.put("version", v.getVersion());
        // defaultMarkupCredits is nullable post-V135; serialize as JSON null so
        // the admin UI can distinguish "no default set" from "default = 0".
        out.put("defaultMarkupCredits",
                v.getDefaultMarkupCredits() == null ? NullNode.getInstance() : formatDecimal(v.getDefaultMarkupCredits()));
        out.put("createdAt", v.getCreatedAt());
        out.put("createdBy", v.getCreatedBy());
        Map<UUID, BigDecimal> overrides = pricingService.findOverrides(v.getId());
        Map<String, String> overridesOut = new HashMap<>(overrides.size());
        for (var e : overrides.entrySet()) {
            overridesOut.put(e.getKey().toString(), formatDecimal(e.getValue()));
        }
        out.put("overrides", overridesOut);
        return out;
    }

    private static String formatDecimal(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    /**
     * POST /api/platform-credentials/pins:cancel-active - admin kill-switch.
     * Body: {@code {"userId": 123}}. Omit {@code userId} to error - blanket
     * cross-tenant cancel is not exposed via this endpoint.
     */
    @PostMapping("/pins:cancel-active")
    public ResponseEntity<?> cancelActivePins(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @RequestBody Map<String, Object> body) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;

        Object raw = body == null ? null : body.get("userId");
        if (raw == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "userId is required"));
        }
        long userId;
        try {
            userId = Long.parseLong(raw.toString());
        } catch (NumberFormatException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", "userId must be numeric"));
        }

        int cancelled = pricingService.cancelActivePinsForUser(userId);
        log.info("Admin cancelled {} active markup pins for user {}", cancelled, userId);
        return ResponseEntity.ok(Map.of("cancelledCount", cancelled, "userId", userId));
    }

    /**
     * Parse a numeric field from the JSON body. Returns null when the key is
     * missing, null, or an empty/blank string - callers that require a value
     * must reject null themselves. Override entries may not hold a null
     * markup, so this is only used where null is semantically valid (the
     * version-wide default).
     */
    private static BigDecimal parseBigDecimalNullable(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Number n) return new BigDecimal(n.toString());
        String s = raw.toString();
        if (s.isBlank()) return null;
        return new BigDecimal(s);
    }

    private static BigDecimal parseBigDecimalRequired(Object raw) {
        BigDecimal v = parseBigDecimalNullable(raw);
        if (v == null) throw new IllegalArgumentException("markup must not be null");
        return v;
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, BigDecimal> parseOverrides(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) return Map.of();
        Map<UUID, BigDecimal> out = new HashMap<>();
        for (var e : m.entrySet()) {
            String key = e.getKey() == null ? null : e.getKey().toString();
            if (key == null) continue;
            try {
                out.put(UUID.fromString(key), parseBigDecimalRequired(e.getValue()));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("invalid override for apiToolId " + key + ": " + ex.getMessage());
            }
        }
        return out;
    }
}
