package com.apimarketplace.auth.credential.web;

import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.*;
import com.apimarketplace.auth.credential.domain.PlatformCredentialPricingVersion;
import com.apimarketplace.auth.credential.domain.PriceSpec;
import com.apimarketplace.auth.credential.domain.PriceUnit;
import com.apimarketplace.auth.credential.domain.PricingVersionEntry;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
     *
     * <p>{@code generation} is the endpoint's own nature travelling in from the
     * caller. It decides whether the credential-wide default may be quoted, and
     * the answer has to match the one the billing path reaches or this endpoint
     * advertises a price the server then refuses. That fact lives on
     * {@code catalog.api_tools.generation_spec}, a table this service does not
     * read and must not: each service queries only its own schema. So the
     * caller that already holds the catalog row (the inspector, which fetched
     * the tool it is bound to) states it. See {@link #isGenerationCall}.
     */
    @GetMapping("/{integrationName}/public-info")
    public ResponseEntity<Map<String, Object>> publicInfo(
            @PathVariable String integrationName,
            @RequestParam(value = "apiToolId", required = false) String apiToolIdRaw,
            @RequestParam(value = "modelId", required = false) String modelId,
            @RequestParam(value = "quantity", required = false) BigDecimal quantity,
            @RequestParam(value = "generation", required = false) Boolean generationEndpoint,
            @RequestParam(value = "quantityUnit", required = false) String quantityUnit,
            // What the call's own CHOICES do to the published rate, computed by
            // the surface from the model's declared modifiers. A quote is a
            // display and never a charge, so a caller understating it only
            // misquotes a price to itself; understating it is also the one
            // direction that shows up immediately, since the amount charged is
            // resolved again server-side from parameters this endpoint never sees.
            @RequestParam(value = "priceMultiplier", required = false) BigDecimal priceMultiplier
    ) {
        var credOpt = service.getCredential(integrationName);
        if (credOpt.isEmpty()) {
            // CE-only: no LOCAL platform credential row - ask the cloud whether ITS
            // platform credential can be relayed for this integration (bean absent on
            // the cloud deployment, empty on unlinked/BYOK/failure = legacy shape).
            // The model and the size travel too: this endpoint already receives
            // them for the local quote, and dropping them on the relay leg is
            // what made a CE install read "not sold on the platform key" for a
            // model the cloud then executed and charged.
            //
            // The `generation` flag is deliberately NOT forwarded: the cloud
            // answers this probe from catalog-service, which owns the descriptor
            // and already applies the same version-default refusal to it
            // (CeCatalogRelayService.priceFor). Sending the install's opinion of
            // what the endpoint is would add a second, weaker source for a fact
            // the responder can read first-hand.
            Optional<Map<String, Object>> cloudInfo = cloudRelayPublicInfo(
                    integrationName, apiToolIdRaw, modelId, quantity,
                    // The factor travels too, because the cloud CHARGES it: the relay reads it
                    // back out of the body it executes, so a quote that dropped it would show
                    // this install one amount and bill another for the same request.
                    sanitizeMultiplier(priceMultiplier));
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
            // V428: when a generation model (and optionally the size of the call)
            // is supplied, quote THAT model rather than the endpoint-wide rate.
            // One endpoint can back several models at different prices, and a
            // generation's price usually scales with the call, so quoting the
            // endpoint would show a number the customer is never charged.
            //
            // `quantity` is the PLATFORM measurement of the call (seconds,
            // assets, characters), the same one the billing path sends. The
            // published unit converts it, so the quote and the invoice are
            // reached by the same arithmetic instead of two copies of it.
            var quote = pricingService.quoteLatest(cred.id(), apiToolId, modelId, quantity,
                    sanitizeMultiplier(priceMultiplier));
            var entry = quote.map(PlatformCredentialPricingService.Quote::entry).orElse(null);
            // A GENERATION quote that resolved out of the credential-wide
            // DEFAULT is not a price this platform will honour. The billing path
            // refuses that exact call - a generation is never sold on a
            // catch-all, because the owner priced the ordinary calls of an API
            // and never made a decision about a video - so reporting an amount
            // here quotes a number the server then refuses to charge, which is
            // the one thing a quote must never do.
            //
            // `entry == null` IS "the version default applied": the billing path
            // reads the same fact off the wire as `pricedByPublishedRow`, which
            // is written as `entry != null` one method away.
            //
            // Reported as an explicit fact rather than left to be inferred from
            // the missing components: the CE cloud relay legitimately carries a
            // real flat price with no components at all, so "no priceUnit" alone
            // cannot tell the two apart.
            boolean versionDefaultOnly =
                    quote.isPresent() && isGenerationCall(generationEndpoint, modelId) && entry == null;
            // A rate cannot price a measurement of another dimension, and BOTH
            // billers refuse that pair. Quoting it anyway shows a number, then
            // every run of that model is refused, which is the one thing a
            // quote must never do.
            //
            // The unit TRAVELS because auth cannot look it up: the model's
            // measurement lives in the catalog descriptor. Absent means "the
            // caller could not say", so every existing caller keeps today's
            // answer, and the flag cannot be used to buy anything: it can only
            // ever turn a quote OFF.
            boolean dimensionsDisagree = entry != null
                    && !PriceUnit.fromWire(entry.getPriceUnit())
                            .canPriceMeasurementFor(measuredUnitOrNull(quantityUnit));
            // THE BILLER'S THIRD REFUSAL, which this quote was missing while the
            // CE relay applied it. A per-unit row resolved without a size is an
            // amount for ONE unit: quoting it prints "60 credits per second"
            // for a ten second clip, and then every run of that step is refused
            // GENERATION_SIZE_UNKNOWN. An mcp: step bound straight to a
            // generation endpoint is exactly that caller, since it sends the
            // endpoint but never a size.
            //
            // Only for a GENERATION. An ordinary endpoint priced per unit is
            // sold and billed at its base amount, so suppressing its quote
            // would hide a price that is charged.
            boolean unitPricedWithoutASize = entry != null
                    && isGenerationCall(generationEndpoint, modelId)
                    && entry.getUnitCredits() != null && entry.getUnitCredits().signum() > 0
                    && (quantity == null || quantity.signum() <= 0);
            boolean hasPricing = quote.isPresent() && quote.get().credits().signum() > 0
                    && !versionDefaultOnly && !dimensionsDisagree && !unitPricedWithoutASize;
            out.put("hasPricing", hasPricing);
            if (versionDefaultOnly) {
                out.put("versionDefaultOnly", true);
            }
            if (hasPricing) {
                out.put("markupCredits", formatDecimal(quote.get().credits()));
                if (entry != null) {
                    // Components so the surface can explain the number
                    // ("60 credits per second") instead of only stating it.
                    out.put("priceUnit", entry.getPriceUnit());
                    out.put("baseCredits", formatDecimal(entry.getMarkupCredits()));
                    out.put("unitCredits", formatDecimal(entry.getUnitCredits()));
                    if (entry.getMinCredits() != null) {
                        out.put("minCredits", formatDecimal(entry.getMinCredits()));
                    }
                    if (entry.getMaxCredits() != null) {
                        out.put("maxCredits", formatDecimal(entry.getMaxCredits()));
                    }
                }
                // The quantity the price was actually reached with, in the
                // PUBLISHED unit, not the platform measurement that was sent.
                // The surface prints the two together ("480 credits per minute,
                // quantity 1"), so echoing the raw 60 seconds back would have it
                // contradict its own rate.
                if (quote.get().quantity() != null) {
                    out.put("quantity", formatDecimal(quote.get().quantity()));
                }
                // What this call's own choices did to that rate. Echoed rather
                // than left implicit: the surface prints the rate, the quantity
                // and the total together, and a total that is not their product
                // reads as an arithmetic error unless the third factor is there
                // to be named.
                if (quote.get().multiplier() != null) {
                    out.put("priceMultiplier", formatDecimal(quote.get().multiplier()));
                }
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
    private Optional<Map<String, Object>> cloudRelayPublicInfo(String integrationName, String apiToolIdRaw,
                                                                String modelId, BigDecimal quantity,
                                                                BigDecimal priceMultiplier) {
        if (cloudPlatformInfoAccess == null) {
            return Optional.empty();
        }
        CloudPlatformCredentialInfoAccess access = cloudPlatformInfoAccess.getIfAvailable();
        if (access == null) {
            return Optional.empty();
        }
        Map<String, Object> info;
        try {
            info = access.fetchPlatformInfo(integrationName, apiToolIdRaw, modelId,
                    quantity == null ? null : quantity.toPlainString(),
                    priceMultiplier == null ? null : priceMultiplier.toPlainString()).orElse(null);
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

    /**
     * Whether this quote is about a resold generation, asked exactly the way the
     * billing path asks it.
     *
     * <p>{@code CatalogToolBillingService.isResoldGeneration} answers YES when
     * the call names a generation model OR the endpoint carries a generation
     * descriptor, and it refuses a version-default amount on that verdict. A
     * quote that used only the first half agreed with it on the generation
     * surface (which always names a model) and disagreed on the one caller that
     * cannot: an {@code mcp:} step bound straight to a generation endpoint sends
     * an {@code apiToolId} and no model, so the quote printed the
     * credential-wide default as a price beside a step the server refuses to
     * run.
     *
     * <p>The descriptor half arrives as a request parameter because it lives in
     * the catalog schema, which auth-service never queries. Absent means "the
     * caller could not say", which keeps every pre-existing caller on the
     * behaviour it has today: an ordinary endpoint inherits the default, which
     * is what a default is for.
     */
    /**
     * The unit a call is measured in, or null when the caller did not say.
     *
     * <p>Null is "cannot tell" and never "no unit": it leaves the dimension
     * question unasked, which is what every caller predating this parameter
     * needs. A spelling this build does not know lands in the same place,
     * through {@link PriceUnit#fromWire}'s lenient reading, because refusing to
     * quote on ignorance would blank out prices that are perfectly chargeable.
     */
    private static PriceUnit measuredUnitOrNull(String quantityUnit) {
        return quantityUnit == null || quantityUnit.isBlank() ? null : PriceUnit.fromWire(quantityUnit);
    }

    private static boolean isGenerationCall(Boolean generationEndpoint, String modelId) {
        return Boolean.TRUE.equals(generationEndpoint) || modelId != null;
    }

    /**
     * A price factor this endpoint is willing to quote with.
     *
     * <p>Dropped rather than refused when it is absurd. This is a READ: a
     * malformed factor must leave the screen showing the published rate, which
     * is the true price of a call carrying no surcharge, instead of turning a
     * price panel into an error. The amount actually charged is resolved again
     * server-side from the parameters, so nothing here can buy anything.
     *
     * <p>The ceiling is literally the same CONSTANT the descriptor parser
     * enforces, not a copy of it: both read
     * {@code BillingContextHeaders.MAX_GENERATION_MULTIPLIER}. It used to be a
     * 100 restated here under a javadoc claiming parity, which is the shape that
     * drifts: raise the parser's ceiling to admit a legitimate modifier and this
     * screen would go on silently dropping the factor, showing a reader the
     * published rate while the server charges the surcharge.
     *
     * <p>The parser applies it to a model's modifiers TOGETHER, not to one
     * factor, so a value arriving here above the ceiling cannot have come from
     * any descriptor this platform accepts.
     */
    private static BigDecimal sanitizeMultiplier(BigDecimal raw) {
        return com.apimarketplace.common.web.BillingContextHeaders
                .sanitizeGenerationMultiplier(raw);
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
     * <p>Body, all fields optional except that a version must end up with either
     * a default or at least one price:
     * <pre>
     * {
     *   "defaultMarkupCredits": "0.05",
     *   "overrides": {"&lt;apiToolId&gt;": "0.10"},          // flat, one amount per endpoint
     *   "prices": [{"apiToolId": "&lt;uuid&gt;", "modelId": "seedance-2.0",
     *               "priceUnit": "second", "baseCredits": "0",
     *               "unitCredits": "60", "minCredits": null, "maxCredits": null}],
     *   "replace": true
     * }
     * </pre>
     *
     * <p><b>{@code overrides} is the pre-V428 shape and still means exactly what
     * it always meant</b>: one flat amount charged per call on that endpoint,
     * whatever model is used. It is kept because a client that speaks it must not
     * break, and because most endpoints really are priced that way.
     * {@code prices} is the full shape: which model, what the rate is charged per,
     * the fixed part, the variable part and the two clamps. Both may be sent; a
     * {@code prices} entry wins over an {@code overrides} entry for the same
     * endpoint, since it is the more specific statement of the same thing.
     *
     * <p><b>{@code replace} decides what a republish does to rows this request
     * does not mention.</b> Default {@code false}: they are carried forward from
     * the latest version, so a client that can only express flat amounts cannot
     * silently collapse a per-second video price into a flat one. Send
     * {@code true} only from a surface that renders and edits EVERY row, because
     * it then publishes that row set verbatim, deletions included.
     *
     * <p>Serialized under an advisory lock to keep version numbers monotonic.
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
        // layer rejects the degenerate case (null default AND no prices).
        BigDecimal defaultMarkup;
        List<PriceSpec> prices;
        boolean replace;
        try {
            defaultMarkup = parseBigDecimalNullable(body.get("defaultMarkupCredits"));
            prices = combinePrices(parseOverrides(body.get("overrides")),
                    parsePrices(body.get("prices")));
            replace = parseBoolean(body.get("replace"));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }

        try {
            PlatformCredentialPricingVersion saved = pricingService.publishNextVersion(
                    id, defaultMarkup, prices, userId, !replace);
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
        // Legacy view: the flat amounts, and ONLY those. A per-model or
        // unit-priced row has no honest one-number form (see findOverrides), so it
        // is absent here and present in full under "prices".
        Map<UUID, BigDecimal> overrides = pricingService.findOverrides(v.getId());
        Map<String, String> overridesOut = new HashMap<>(overrides.size());
        for (var e : overrides.entrySet()) {
            overridesOut.put(e.getKey().toString(), formatDecimal(e.getValue()));
        }
        out.put("overrides", overridesOut);
        // Full view: every published row, so the admin can SEE what is live
        // before deciding what to change.
        List<Map<String, Object>> pricesOut = new ArrayList<>();
        for (PricingVersionEntry entry : pricingService.findPrices(v.getId())) {
            pricesOut.add(toPriceRow(entry));
        }
        out.put("prices", pricesOut);
        return out;
    }

    /**
     * One published price row, with the six numbers that define it. Amounts are
     * strings for the same reason the rest of this response uses them: a decimal
     * rate must survive the trip without being rounded through a double.
     */
    private static Map<String, Object> toPriceRow(PricingVersionEntry entry) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("apiToolId", entry.getApiToolId() == null ? null : entry.getApiToolId().toString());
        row.put("modelId", entry.getModelId());
        row.put("priceUnit", entry.getPriceUnit());
        row.put("baseCredits", formatDecimalOrZero(entry.getMarkupCredits()));
        row.put("unitCredits", formatDecimalOrZero(entry.getUnitCredits()));
        row.put("minCredits", entry.getMinCredits() == null ? null : formatDecimal(entry.getMinCredits()));
        row.put("maxCredits", entry.getMaxCredits() == null ? null : formatDecimal(entry.getMaxCredits()));
        return row;
    }

    private static String formatDecimal(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    /**
     * A price row's base and per-unit amounts are NOT NULL in the schema, but a
     * hand-written row or a stale entity could still hand us a null. Reporting
     * "0" is the only reading that matches how the billing path resolves it.
     */
    private static String formatDecimalOrZero(BigDecimal value) {
        return value == null ? "0" : formatDecimal(value);
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

    /**
     * The pre-V428 shape: one flat amount per endpoint. It stays exactly that,
     * {@link PriceSpec#flat} resolves to the amount given, with no unit and no
     * clamps, so a client that speaks only this map keeps billing what it always
     * billed.
     */
    private static List<PriceSpec> parseOverrides(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) return List.of();
        List<PriceSpec> out = new ArrayList<>();
        for (var e : m.entrySet()) {
            String key = e.getKey() == null ? null : e.getKey().toString();
            if (key == null) continue;
            try {
                out.add(PriceSpec.flat(UUID.fromString(key), parseBigDecimalRequired(e.getValue())));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("invalid override for apiToolId " + key + ": " + ex.getMessage());
            }
        }
        return out;
    }

    /**
     * The full V428 shape: an array of rows, each naming the endpoint, optionally
     * the model, and the five numbers that define the price. A blank or absent
     * {@code modelId} is the endpoint-wide price, which is what every pre-V428 row
     * is; {@link PriceSpec} normalises that (and an unknown unit) at construction.
     *
     * <p>Amounts are read as decimals from either JSON numbers or strings, so a
     * client can send {@code "60"} and keep the precision a double would lose.
     */
    private static List<PriceSpec> parsePrices(Object raw) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException("prices must be an array of price objects");
        }
        List<PriceSpec> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) {
                throw new IllegalArgumentException("each entry of prices must be an object");
            }
            Object toolRaw = m.get("apiToolId");
            String toolKey = toolRaw == null ? null : toolRaw.toString().trim();
            if (toolKey == null || toolKey.isEmpty()) {
                throw new IllegalArgumentException("each entry of prices requires an apiToolId");
            }
            try {
                out.add(new PriceSpec(
                        UUID.fromString(toolKey),
                        stringOrNull(m.get("modelId")),
                        // Strict on the way IN. The lenient reader turns a typo
                        // into a flat price, so "seconds" would publish a
                        // per-second rate as per-call and bill a ten second clip
                        // as one, silently and always cheaper than intended.
                        PriceUnit.parseStrict(stringOrNull(m.get("priceUnit"))).wire(),
                        parseBigDecimalNullable(m.get("baseCredits")),
                        parseBigDecimalNullable(m.get("unitCredits")),
                        parseBigDecimalNullable(m.get("minCredits")),
                        parseBigDecimalNullable(m.get("maxCredits"))));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("invalid price for apiToolId " + toolKey + ": " + ex.getMessage());
            }
        }
        return out;
    }

    /**
     * Both request shapes describe the same table, so they are merged on that
     * table's key, (endpoint, model), rather than concatenated: sending a flat
     * override and a full price for one endpoint is a client stating the same row
     * twice, not two rows. The richer statement wins, and the service still
     * rejects a genuine duplicate WITHIN {@code prices}.
     */
    private static List<PriceSpec> combinePrices(List<PriceSpec> overrides, List<PriceSpec> prices) {
        if (prices.isEmpty()) return overrides;
        if (overrides.isEmpty()) return prices;
        Map<String, PriceSpec> byKey = new LinkedHashMap<>();
        for (PriceSpec p : overrides) {
            byKey.put(p.apiToolId() + "|" + (p.modelId() == null ? "" : p.modelId()), p);
        }
        for (PriceSpec p : prices) {
            byKey.put(p.apiToolId() + "|" + (p.modelId() == null ? "" : p.modelId()), p);
        }
        return new ArrayList<>(byKey.values());
    }

    /** Trimmed text, or null when the field is absent, null or blank. */
    private static String stringOrNull(Object raw) {
        if (raw == null) return null;
        String s = raw.toString().trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * Absent means false. Accepts a JSON boolean and the string form a form-encoded
     * client would send; anything else is refused rather than guessed, because
     * guessing here decides whether a republish keeps or drops existing rows.
     */
    private static boolean parseBoolean(Object raw) {
        if (raw == null) return false;
        if (raw instanceof Boolean b) return b;
        String s = raw.toString().trim();
        if (s.equalsIgnoreCase("true")) return true;
        if (s.isEmpty() || s.equalsIgnoreCase("false")) return false;
        throw new IllegalArgumentException("replace must be true or false (got '" + s + "')");
    }
}
