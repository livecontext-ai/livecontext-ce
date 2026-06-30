package com.apimarketplace.auth.credential.service;

import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.*;
import com.apimarketplace.auth.credential.repository.PlatformCredentialRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Service for managing platform-level credentials.
 * DB-only lookup -- no fallback to application.yml config.
 */
@Service
public class PlatformCredentialService {

    private static final Logger log = LoggerFactory.getLogger(PlatformCredentialService.class);

    private final PlatformCredentialRepository repository;

    public PlatformCredentialService(PlatformCredentialRepository repository) {
        this.repository = repository;
    }

    // ========== OAuth2 Credentials Lookup (for OAuth2Service) ==========

    /**
     * Get OAuth2 credentials for an integration, tenant-aware.
     * Tenant-scoped credential takes priority over platform-wide.
     */
    public Optional<OAuth2Credentials> getOAuth2Credentials(String integrationName, String tenantId) {
        String normalizedName = normalizeIntegrationName(integrationName);
        log.debug("Looking up OAuth2 credentials for: {} (normalized: {}, tenant: {})",
                integrationName, normalizedName, tenantId);

        Optional<PlatformCredential> dbCredential = tenantId != null
                ? repository.findOAuth2ByIntegrationName(normalizedName, tenantId)
                : repository.findOAuth2ByIntegrationName(normalizedName);

        if (dbCredential.isPresent()) {
            PlatformCredential cred = dbCredential.get();
            if (cred.isEnabled() && cred.hasOAuth2Credentials()) {
                log.info("Using DB credentials for integration: {} (tenant-scoped: {})",
                        integrationName, cred.tenantId() != null);
                return Optional.of(new OAuth2Credentials(
                        cred.clientId(),
                        cred.clientSecret()
                ));
            }
        }

        log.warn("No platform credentials found in DB for integration: {}", integrationName);
        return Optional.empty();
    }

    /**
     * Get OAuth2 credentials for an integration (platform-wide only).
     */
    public Optional<OAuth2Credentials> getOAuth2Credentials(String integrationName) {
        return getOAuth2Credentials(integrationName, null);
    }

    /**
     * Get the full OAuth2 platform_credential <em>row</em> for an integration, tenant-aware.
     *
     * <p>Sibling of {@link #getOAuth2Credentials(String, String)} which projects only
     * {@code (clientId, clientSecret)}. Callers that also need {@code tenantId} (to tell a BYOK
     * row apart from the platform-wide one) or {@code defaultScopes} (to honour the user's own
     * scope set) use this instead. Same tenant-priority ordering ({@code tenant_id NULLS LAST}),
     * same enabled + secrets gate, so for the {@code (clientId, clientSecret)} subset the two
     * methods resolve to the identical row.
     */
    public Optional<PlatformCredential> getRawOAuth2Credential(String integrationName, String tenantId) {
        String normalizedName = normalizeIntegrationName(integrationName);
        return (tenantId != null
                ? repository.findOAuth2ByIntegrationName(normalizedName, tenantId)
                : repository.findOAuth2ByIntegrationName(normalizedName))
                .filter(c -> c.isEnabled() && c.hasOAuth2Credentials());
    }

    // ========== Org-Aware Resolution (V362) ==========
    // These mirror the tenant-aware lookups above but thread the active workspace
    // so a workspace BYOK wins over a personal one, which wins over the global
    // platform app. {@code organizationId} may be null (personal scope). Callers
    // that have the active org (OAuth connect-time, and execution-time once the
    // org is in scope) use these; tenant-only callers keep the 2-arg overloads.

    /**
     * Org-aware OAuth2 credential lookup (V362). Resolution priority:
     * workspace-org BYOK &gt; personal BYOK &gt; platform-wide.
     */
    public Optional<OAuth2Credentials> getOAuth2Credentials(String integrationName, String tenantId, String organizationId) {
        if (tenantId == null) {
            return getOAuth2Credentials(integrationName, null);
        }
        String normalizedName = normalizeIntegrationName(integrationName);
        return repository.findOAuth2ByIntegrationName(normalizedName, tenantId, organizationId)
                .filter(c -> c.isEnabled() && c.hasOAuth2Credentials())
                .map(c -> new OAuth2Credentials(c.clientId(), c.clientSecret()));
    }

    /**
     * Org-aware counterpart of {@link #getRawOAuth2Credential(String, String)} (V362).
     */
    public Optional<PlatformCredential> getRawOAuth2Credential(String integrationName, String tenantId, String organizationId) {
        if (tenantId == null) {
            return getRawOAuth2Credential(integrationName, null);
        }
        String normalizedName = normalizeIntegrationName(integrationName);
        return repository.findOAuth2ByIntegrationName(normalizedName, tenantId, organizationId)
                .filter(c -> c.isEnabled() && c.hasOAuth2Credentials());
    }

    /**
     * Org-aware counterpart of {@link #getRawCredential(String, String)} (V362).
     */
    public Optional<PlatformCredential> getRawCredential(String integrationName, String tenantId, String organizationId) {
        if (tenantId == null) {
            return getRawCredential(integrationName, null);
        }
        String normalizedName = normalizeIntegrationName(integrationName);
        return repository.findByIntegrationName(normalizedName, tenantId, organizationId)
                .filter(PlatformCredential::isEnabled);
    }

    /**
     * Check if OAuth2 credentials are available for an integration.
     */
    public boolean hasOAuth2Credentials(String integrationName) {
        return getOAuth2Credentials(integrationName).isPresent();
    }

    /**
     * Check whether the platform OAuth flow is available and whether the
     * frontend should show the provider verification heads-up for it.
     */
    public PlatformCredentialsAvailability getPlatformCredentialsAvailability(String integrationName) {
        String normalizedName = normalizeIntegrationName(integrationName);
        Optional<PlatformCredential> dbCredential = repository.findOAuth2ByIntegrationName(normalizedName);
        boolean available = dbCredential
                .filter(PlatformCredential::isEnabled)
                .filter(PlatformCredential::hasOAuth2Credentials)
                .isPresent();
        boolean showWarning = available
                && dbCredential.map(PlatformCredential::showUnverifiedAppWarning).orElse(false);
        return new PlatformCredentialsAvailability(available, showWarning);
    }

    /**
     * Check if credentials come from DB (vs fallback config).
     */
    public boolean hasDbCredentials(String integrationName) {
        String normalizedName = normalizeIntegrationName(integrationName);
        Optional<PlatformCredential> dbCredential = repository.findOAuth2ByIntegrationName(normalizedName);
        return dbCredential.isPresent() && dbCredential.get().isEnabled() && dbCredential.get().hasOAuth2Credentials();
    }

    // ========== CRUD Operations ==========

    /**
     * Get all platform credentials visible to a tenant (tenant-scoped + platform-wide).
     */
    public List<PlatformCredentialResponse> getAllCredentials(String tenantId) {
        return (tenantId != null ? repository.findAll(tenantId) : repository.findAll()).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Get all platform credentials (platform-wide only).
     */
    public List<PlatformCredentialResponse> getAllCredentials() {
        return getAllCredentials(null);
    }

    /**
     * Maximum number of tenant-owned BYOK platform_credential rows. Bounded so
     * a runaway client (or a UI bug that retries inserts in a loop) cannot
     * unbounded-grow {@code auth.platform_credentials}. The cap is enforced at
     * INSERT time only - updates of existing rows are unrestricted because
     * they don't grow the count.
     *
     * <p><b>Counted rows include disabled ones.</b> The cap is a row-count guard
     * against pathological growth, not an "active connections" semantic. A
     * tenant with 50 disabled rows still hits the cap; the user must delete an
     * unused row to add another. This matches the user-visible message
     * "Delete an unused one to add a new one."
     *
     * <p><b>TOCTOU note.</b> The check ({@code countByTenantId(tenantId) >= MAX_BYOK_PER_TENANT})
     * and the subsequent {@code repository.save(credential)} are not atomic.
     * Two concurrent inserts at count=49 can both pass the check and both
     * commit, ending at 51. This is acceptable for v5 scope: the cap exists
     * to bound DoS surface, not to enforce a strict invariant. The repo-level
     * {@code findOwnedByTenant LIMIT 100} gives 2x headroom for races without
     * adding a partial UNIQUE constraint or a {@code DataIntegrityViolationException}
     * retry loop. Phase 2 may tighten if a real abuse pattern surfaces.
     */
    public static final int MAX_BYOK_PER_TENANT = 50;

    /**
     * Return the raw {@link PlatformCredential} rows owned strictly by a tenant
     * (excludes platform-wide rows). Used by the {@code GET /my} endpoint, which
     * then maps each row through {@link com.apimarketplace.auth.credential.web.dto.MyOAuthAppDto#from}.
     *
     * <p>Returns the raw {@link PlatformCredential} rather than the
     * {@link PlatformCredentialResponse} so the user-facing path uses an
     * explicit allowlist DTO instead of the admin response shape (which leaks
     * markup/billing config).
     */
    public List<PlatformCredential> findOwnedByTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return List.of();
        }
        return repository.findOwnedByTenant(tenantId);
    }

    /**
     * Org-aware (V362) counterpart of {@link #findOwnedByTenant(String)}: lists
     * the BYOK rows owned by {@code tenantId} in scope {@code organizationId}
     * (strict isolation - workspace rows in a workspace, personal rows in
     * personal scope). The {@code GET /my} endpoint passes the active workspace.
     */
    public List<PlatformCredential> findOwnedByTenant(String tenantId, String organizationId) {
        if (tenantId == null || tenantId.isBlank()) {
            return List.of();
        }
        return repository.findOwnedByTenant(tenantId, organizationId);
    }

    /**
     * Get platform credentials by category, tenant-aware.
     */
    public List<PlatformCredentialResponse> getCredentialsByCategory(String category, String tenantId) {
        return (tenantId != null ? repository.findByCategory(category, tenantId) : repository.findByCategory(category))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Get platform credentials by category (platform-wide only).
     */
    public List<PlatformCredentialResponse> getCredentialsByCategory(String category) {
        return getCredentialsByCategory(category, null);
    }

    /**
     * Get a single platform credential, tenant-aware.
     */
    public Optional<PlatformCredentialResponse> getCredential(String integrationName, String tenantId) {
        String normalizedName = normalizeIntegrationName(integrationName);
        return (tenantId != null
                ? repository.findByIntegrationName(normalizedName, tenantId)
                : repository.findByIntegrationName(normalizedName))
                .map(this::toResponse);
    }

    /**
     * Get a single platform credential (platform-wide only).
     */
    public Optional<PlatformCredentialResponse> getCredential(String integrationName) {
        return getCredential(integrationName, null);
    }

    /**
     * Get a raw platform credential (with sensitive fields) for internal use, tenant-aware.
     */
    public Optional<PlatformCredential> getRawCredential(String integrationName, String tenantId) {
        String normalizedName = normalizeIntegrationName(integrationName);
        return (tenantId != null
                ? repository.findByIntegrationName(normalizedName, tenantId)
                : repository.findByIntegrationName(normalizedName))
                .filter(PlatformCredential::isEnabled);
    }

    /**
     * Get a raw platform credential (platform-wide only).
     */
    public Optional<PlatformCredential> getRawCredential(String integrationName) {
        return getRawCredential(integrationName, null);
    }

    /**
     * Create or update a platform credential, tenant-aware.
     * When tenantId is provided, creates/updates a tenant-scoped credential.
     * When tenantId is null, creates/updates a platform-wide credential (admin).
     */
    @Transactional
    public PlatformCredentialResponse saveCredential(CreatePlatformCredentialRequest request, String tenantId, String organizationId) {
        String normalizedName = normalizeIntegrationName(request.integrationName());

        // Variant-aware lookup (V103 / Phase 2d admin dialog). When the request
        // carries a variant we target that exact (integration_name, variant)
        // row - so editing Airtable's bearer_token tab does not clobber the
        // oauth2 row and vice versa. Null variant keeps legacy behaviour
        // (first/primary row) for older tests.
        //
        // Tenant-scoped saves intentionally ignore request.variant(): tenant
        // rows are single-row-per-(integration, tenant, org) by design (a user
        // overriding a single OAuth2 key per workspace), so the admin's
        // per-variant concept must not leak and chase a platform-wide
        // bearer_token row. This is locked in by
        // `saveCredential_tenantScopedIgnoresRequestVariant`.
        //
        // V362: tenant saves match the row for THIS (tenant, workspace) scope
        // via findOwnedRow so editing a connection in one workspace never
        // clobbers the same integration's row in another workspace, and a fresh
        // workspace gets its own row (the V362 unique index allows one row per
        // (integration, tenant, org, variant)).
        String requestedVariant = request.variant();
        Optional<PlatformCredential> existing;
        if (tenantId != null) {
            existing = repository.findOwnedRow(normalizedName, tenantId, organizationId);
        } else if (requestedVariant != null && !requestedVariant.isBlank()) {
            existing = repository.findByIntegrationNameAndVariant(normalizedName, requestedVariant);
        } else {
            existing = repository.findByIntegrationName(normalizedName);
        }

        PlatformCredential credential;
        if (existing.isPresent()) {
            // Update existing
            PlatformCredential current = existing.get();
            Map<String, String> mergedCustomFields = new java.util.HashMap<>(
                    current.customFields() != null ? current.customFields() : Map.of());
            if (request.customFields() != null) {
                mergedCustomFields.putAll(request.customFields());
            }

            credential = new PlatformCredential(
                    current.id(),
                    normalizedName,
                    request.displayName() != null ? request.displayName() : current.displayName(),
                    request.authTypeEnum(),
                    request.clientId() != null ? request.clientId() : current.clientId(),
                    request.clientSecret() != null ? request.clientSecret() : null,
                    request.apiKey() != null ? request.apiKey() : null,
                    request.username(),
                    request.password() != null ? request.password() : null,
                    request.authUrl(),
                    request.tokenUrl(),
                    request.defaultScopes(),
                    request.iconSlug() != null ? request.iconSlug() : current.iconSlug(),
                    request.category() != null ? request.category() : current.category(),
                    request.description(),
                    request.showUnverifiedAppWarning() != null
                            ? request.showUnverifiedAppWarning() : current.showUnverifiedAppWarning(),
                    current.isEnabled(),
                    mergedCustomFields,
                    request.defaultMarkupCredits() != null
                            ? request.defaultMarkupCredits() : current.defaultMarkupCredits(),
                    request.maxCallsPerRun() != null
                            ? request.maxCallsPerRun() : current.maxCallsPerRun(),
                    current.createdAt(),
                    Instant.now(),
                    current.createdBy(),
                    current.tenantId(),
                    tenantId != null ? PlatformCredential.DEFAULT_VARIANT : current.variant(),
                    current.organizationId()
            );
            log.info("Updating platform credential: {}/{} (tenant: {}, org: {})",
                    normalizedName, current.variant(), tenantId, current.organizationId());
        } else {
            // Per-tenant insert cap - bounds the BYOK row growth without
            // touching admin platform-wide saves (tenantId == null skips it).
            // Updates of an existing row never reach this branch, so the cap
            // only fires on genuine row creation.
            if (tenantId != null) {
                // V362: cap is per (tenant, workspace) so a tenant maxed out in
                // one workspace can still register a connection in another.
                int currentCount = repository.countByTenantIdAndOrganizationId(tenantId, organizationId);
                if (currentCount >= MAX_BYOK_PER_TENANT) {
                    log.warn("Tenant {} (org {}) hit BYOK cap ({} of {} rows); rejecting create of {}",
                            tenantId, organizationId, currentCount, MAX_BYOK_PER_TENANT, normalizedName);
                    throw new TooManyByokAppsException(MAX_BYOK_PER_TENANT);
                }
            }

            // Create new. Platform-owned rows honor the admin dialog variant so
            // the (integration_name, variant) UNIQUE key stays satisfied; tenant
            // BYOK rows always use the legacy primary variant.
            String variantToStore = tenantId != null
                    ? PlatformCredential.DEFAULT_VARIANT
                    : ((requestedVariant != null && !requestedVariant.isBlank())
                            ? requestedVariant
                            : PlatformCredential.DEFAULT_VARIANT);
            // V166 BYOK fix: OAuth2 platform_credentials rows MUST have
            // default_markup_credits = 0 AND max_calls_per_run = 0 (CHECK constraint
            // `platform_credentials_oauth2_no_markup` from V101). OAuth2 uses
            // user-side billing (per-token), not platform markup. Non-OAuth2 rows
            // keep the legacy `500` default for max_calls_per_run.
            boolean isOauth2 = request.authTypeEnum() != null
                    && "OAUTH2".equalsIgnoreCase(request.authTypeEnum().name());
            java.math.BigDecimal markupDefault = isOauth2
                    ? java.math.BigDecimal.ZERO
                    : (request.defaultMarkupCredits() != null
                            ? request.defaultMarkupCredits() : java.math.BigDecimal.ZERO);
            int maxCallsDefault = isOauth2
                    ? 0
                    : (request.maxCallsPerRun() != null ? request.maxCallsPerRun() : 500);
            credential = new PlatformCredential(
                    null,
                    normalizedName,
                    request.displayName() != null ? request.displayName() : request.integrationName(),
                    request.authTypeEnum(),
                    request.clientId(),
                    request.clientSecret(),
                    request.apiKey(),
                    request.username(),
                    request.password(),
                    request.authUrl(),
                    request.tokenUrl(),
                    request.defaultScopes(),
                    request.iconSlug(),
                    request.category(),
                    request.description(),
                    request.showUnverifiedAppWarning() != null
                            ? request.showUnverifiedAppWarning() : true,
                    true,
                    request.customFields() != null ? request.customFields() : Map.of(),
                    request.defaultMarkupCredits() != null && !isOauth2
                            ? request.defaultMarkupCredits() : markupDefault,
                    request.maxCallsPerRun() != null && !isOauth2
                            ? request.maxCallsPerRun() : maxCallsDefault,
                    Instant.now(),
                    Instant.now(),
                    null,
                    tenantId,
                    variantToStore,
                    // Tenant BYOK rows carry the active workspace (null = personal
                    // scope). Platform-wide rows (tenantId == null) stay org-NULL.
                    tenantId != null ? organizationId : null
            );
            log.info("Creating platform credential: {}/{} (tenant: {}, org: {})",
                    normalizedName, variantToStore, tenantId, tenantId != null ? organizationId : null);
        }

        PlatformCredential saved = repository.save(credential);
        return toPersistedResponse(saved);
    }

    /**
     * Tenant-scoped save without an explicit workspace - defaults to personal
     * scope (organization_id null). Kept for back-compat with callers that
     * pre-date V362; the user-facing controller passes the active workspace via
     * the 3-arg overload.
     */
    @Transactional
    public PlatformCredentialResponse saveCredential(CreatePlatformCredentialRequest request, String tenantId) {
        return saveCredential(request, tenantId, null);
    }

    /**
     * Create or update a platform-wide credential (admin).
     */
    @Transactional
    public PlatformCredentialResponse saveCredential(CreatePlatformCredentialRequest request) {
        return saveCredential(request, null, null);
    }

    /**
     * Update an existing platform credential, tenant-aware.
     */
    @Transactional
    public Optional<PlatformCredentialResponse> updateCredential(
            String integrationName,
            UpdatePlatformCredentialRequest request,
            String tenantId
    ) {
        String normalizedName = normalizeIntegrationName(integrationName);
        Optional<PlatformCredential> existing = tenantId != null
                ? repository.findByIntegrationName(normalizedName, tenantId)
                : repository.findByIntegrationName(normalizedName);

        if (existing.isEmpty()) {
            return Optional.empty();
        }

        PlatformCredential current = existing.get();
        PlatformCredential updated = new PlatformCredential(
                current.id(),
                current.integrationName(),
                request.displayName() != null ? request.displayName() : current.displayName(),
                current.authType(),
                request.clientId() != null ? request.clientId() : current.clientId(),
                request.clientSecret(),
                request.apiKey(),
                request.username() != null ? request.username() : current.username(),
                request.password(),
                request.authUrl() != null ? request.authUrl() : current.authUrl(),
                request.tokenUrl() != null ? request.tokenUrl() : current.tokenUrl(),
                request.defaultScopes() != null ? request.defaultScopes() : current.defaultScopes(),
                request.iconSlug() != null ? request.iconSlug() : current.iconSlug(),
                request.category() != null ? request.category() : current.category(),
                request.description() != null ? request.description() : current.description(),
                request.showUnverifiedAppWarning() != null
                        ? request.showUnverifiedAppWarning() : current.showUnverifiedAppWarning(),
                request.isEnabled() != null ? request.isEnabled() : current.isEnabled(),
                current.customFields(),
                request.defaultMarkupCredits() != null
                        ? request.defaultMarkupCredits() : current.defaultMarkupCredits(),
                request.maxCallsPerRun() != null
                        ? request.maxCallsPerRun() : current.maxCallsPerRun(),
                current.createdAt(),
                Instant.now(),
                current.createdBy(),
                current.tenantId(),
                current.variant(),
                current.organizationId()
        );

        PlatformCredential saved = repository.save(updated);
        return Optional.of(toPersistedResponse(saved));
    }

    /**
     * Update an existing platform credential (platform-wide only).
     */
    @Transactional
    public Optional<PlatformCredentialResponse> updateCredential(
            String integrationName,
            UpdatePlatformCredentialRequest request
    ) {
        return updateCredential(integrationName, request, null);
    }

    /**
     * Delete a tenant-scoped platform credential.
     */
    @Transactional
    public boolean deleteCredential(String integrationName, String tenantId) {
        if (tenantId != null) {
            return repository.deleteByIntegrationName(normalizeIntegrationName(integrationName), tenantId);
        }
        return repository.deleteByIntegrationName(normalizeIntegrationName(integrationName));
    }

    /**
     * Org-aware (V362) tenant-scoped delete: removes the BYOK row owned by
     * {@code tenantId} in scope {@code organizationId} only. The user-facing
     * {@code DELETE /my} endpoint passes the active workspace so a delete in one
     * workspace never removes a same-named connection in another.
     */
    @Transactional
    public boolean deleteCredential(String integrationName, String tenantId, String organizationId) {
        if (tenantId == null) {
            return repository.deleteByIntegrationName(normalizeIntegrationName(integrationName));
        }
        return repository.deleteByIntegrationName(normalizeIntegrationName(integrationName), tenantId, organizationId);
    }

    /**
     * Delete a platform-wide credential (admin).
     */
    @Transactional
    public boolean deleteCredential(String integrationName) {
        return deleteCredential(integrationName, null);
    }

    /**
     * Enable or disable a platform credential, tenant-aware.
     */
    @Transactional
    public boolean setEnabled(String integrationName, boolean enabled, String tenantId) {
        if (tenantId != null) {
            return repository.setEnabled(normalizeIntegrationName(integrationName), enabled, tenantId);
        }
        return repository.setEnabled(normalizeIntegrationName(integrationName), enabled);
    }

    /**
     * Enable or disable a platform credential (platform-wide).
     */
    @Transactional
    public boolean setEnabled(String integrationName, boolean enabled) {
        return setEnabled(integrationName, enabled, null);
    }

    /**
     * Toggle a single variant row of a platform-wide credential (admin Phase 2d).
     * Returns {@code false} when the (integration_name, variant) pair does not
     * exist - the controller maps that to a 404 so the UI can flag a stale row.
     */
    @Transactional
    public boolean setVariantEnabled(String integrationName, String variant, boolean enabled) {
        return setVariantEnabled(integrationName, variant, enabled, null);
    }

    /**
     * Tenant-scoped counterpart of {@link #setVariantEnabled(String, String, boolean)}.
     * When {@code tenantId} is null this falls back to the platform-wide query.
     */
    @Transactional
    public boolean setVariantEnabled(String integrationName, String variant, boolean enabled, String tenantId) {
        String normalizedName = normalizeIntegrationName(integrationName);
        if (tenantId != null) {
            return repository.setEnabledForVariant(normalizedName, variant, enabled, tenantId);
        }
        return repository.setEnabledForVariant(normalizedName, variant, enabled);
    }

    // ========== Categories ==========

    /**
     * Get all distinct categories, tenant-aware.
     */
    public List<String> getCategories(String tenantId) {
        return tenantId != null ? repository.findDistinctCategories(tenantId) : repository.findDistinctCategories();
    }

    /**
     * Get all distinct categories (platform-wide only).
     */
    public List<String> getCategories() {
        return getCategories(null);
    }

    /**
     * Get category info with counts, tenant-aware.
     */
    public List<CategoryInfo> getCategoryInfo(String tenantId) {
        return tenantId != null ? repository.countByCategory(tenantId) : repository.countByCategory();
    }

    /**
     * Get category info with counts (platform-wide only).
     */
    public List<CategoryInfo> getCategoryInfo() {
        return getCategoryInfo(null);
    }

    // ========== Endpoint Management ==========

    /**
     * Get endpoints for a platform credential.
     */
    public List<EndpointStatusResponse> getEndpoints(String integrationName) {
        Optional<PlatformCredential> credential = repository.findByIntegrationName(
                normalizeIntegrationName(integrationName)
        );

        if (credential.isEmpty()) {
            return List.of();
        }

        return repository.findEndpointsByCredentialId(credential.get().id()).stream()
                .map(e -> new EndpointStatusResponse(
                        e.toolId(),
                        e.toolName(),
                        null,
                        null,
                        e.isEnabled()
                ))
                .toList();
    }

    /**
     * Toggle an endpoint's enabled status.
     */
    @Transactional
    public boolean toggleEndpoint(String integrationName, String toolId, boolean enabled) {
        Optional<PlatformCredential> credential = repository.findByIntegrationName(
                normalizeIntegrationName(integrationName)
        );

        if (credential.isEmpty()) {
            return false;
        }

        return repository.toggleEndpoint(credential.get().id(), toolId, enabled);
    }

    /**
     * Check if an endpoint is enabled.
     */
    public boolean isEndpointEnabled(String integrationName, String toolId) {
        Optional<PlatformCredential> credential = repository.findByIntegrationName(
                normalizeIntegrationName(integrationName)
        );

        if (credential.isEmpty() || !credential.get().isEnabled()) {
            return false;
        }

        return repository.isEndpointEnabled(credential.get().id(), toolId);
    }

    // ========== Helper Methods ==========

    private PlatformCredentialResponse toResponse(PlatformCredential credential) {
        List<PlatformCredentialEndpoint> endpoints = repository.findEndpointsByCredentialId(credential.id());
        List<EndpointStatusResponse> endpointResponses = endpoints.stream()
                .map(e -> new EndpointStatusResponse(
                        e.toolId(),
                        e.toolName(),
                        null,
                        null,
                        e.isEnabled()
                ))
                .toList();

        return PlatformCredentialResponse.from(credential, endpointResponses);
    }

    private PlatformCredentialResponse toPersistedResponse(PlatformCredential saved) {
        if (saved.id() == null) {
            return toResponse(saved);
        }
        return repository.findById(saved.id())
                .map(this::toResponse)
                .orElseGet(() -> toResponse(saved));
    }

    /**
     * Normalize an integration name to the canonical key used as
     * {@code auth.platform_credentials.integration_name}.
     *
     * <p>Must stay in sync with {@code IconSlugNormalizer.normalizeForKey()} in
     * {@code catalog-service-import} - that's the class the importer uses to write these
     * rows, so any divergence between the two normalizers produces silent lookup failures
     * (e.g. {@code "Google Pub/Sub"} → importer writes {@code "googlepubsub"} but this
     * method used to produce {@code "googlepub/sub"} because {@code /} wasn't stripped).
     *
     * <p>Algorithm, identical on both sides:
     * <ol>
     *   <li>NFD-normalize and strip combining marks (removes accents)</li>
     *   <li>Lowercase</li>
     *   <li>Trim a trailing {@code " credential"} suffix (legacy callers pass names like
     *       {@code "Airtable Credential"}; the importer never has this suffix, so this is a
     *       no-op for DB lookups but keeps the old behavior for existing callers)</li>
     *   <li>Strip every character that is not {@code [a-z0-9]} - including spaces,
     *       {@code .}, {@code /}, {@code -}, {@code _}, and any punctuation</li>
     * </ol>
     */
    public static String normalizeIntegrationName(String name) {
        if (name == null) {
            return null;
        }
        // Strip legacy " credential" suffix before delegating to the shared normalizer
        String cleaned = name;
        if (cleaned.toLowerCase(java.util.Locale.ROOT).endsWith(" credential")) {
            cleaned = cleaned.substring(0, cleaned.length() - " credential".length());
        }
        return com.apimarketplace.common.icon.IconSlugNormalizer.normalize(cleaned);
    }
}
