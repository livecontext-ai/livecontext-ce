package com.apimarketplace.auth.credential.service;

import com.apimarketplace.auth.credential.domain.CredentialModels.*;
import com.apimarketplace.auth.credential.repository.CredentialRepository;
import com.apimarketplace.common.web.TenantResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Service for managing user credentials (API keys, OAuth2 tokens, etc.).
 */
@Service
public class CredentialService {

    private static final Logger log = LoggerFactory.getLogger(CredentialService.class);

    private final CredentialRepository credentialRepository;
    private final StringRedisTemplate redisTemplate;

    public CredentialService(CredentialRepository credentialRepository,
                             StringRedisTemplate redisTemplate) {
        this.credentialRepository = credentialRepository;
        this.redisTemplate = redisTemplate;
    }

    /**
     * PR19 - create a new credential with explicit org scope. The controller
     * passes {@code organizationId} from the {@code X-Organization-ID} header.
     * The first credential per integration in a given org scope becomes the
     * default - defaults are NOT shared across scopes (strict isolation).
     *
     * <p>Post-V261: {@code organizationId} is REQUIRED. Personal workspace
     * resolves to the user's {@code is_default=true} personal org UUID via
     * gateway. The previous null-org / personal-scope branch is gone.
     */
    public Credential createCredential(
            String tenantId,
            String organizationId,
            String name,
            String integration,
            CredentialType type,
            CredentialEnvironment environment,
            String description,
            Map<String, Object> credentialData,
            List<String> scopes,
            List<String> tags,
            String owner,
            String iconUrl
    ) {
        if (tenantId == null || tenantId.trim().isEmpty()) {
            throw new IllegalArgumentException("tenantId cannot be null or empty");
        }
        TenantResolver.requireOrgId(organizationId);
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("name cannot be null or empty");
        }
        if (type == null) {
            throw new IllegalArgumentException("type cannot be null");
        }
        if (environment == null) {
            throw new IllegalArgumentException("environment cannot be null");
        }

        // First credential for this integration in the active org SCOPE becomes
        // the default. Scope-aware: org-scope defaults stay org-scope.
        boolean isFirstForIntegration = integration != null &&
            credentialRepository.findByScopeAndIntegration(tenantId, organizationId, integration).isEmpty();

        Credential credential = new Credential(
            null,
            tenantId,
            organizationId,
            name,
            integration,
            type,
            environment,
            CredentialStatus.active,
            description,
            credentialData != null ? credentialData : Map.of(),
            scopes != null ? scopes : List.of(),
            tags != null ? tags : List.of(),
            owner,
            iconUrl,
            isFirstForIntegration, // isDefault - true if first for this integration in this scope
            null,
            Instant.now(),
            Instant.now()
        );

        return credentialRepository.save(credential);
    }

    /**
     * Get a credential by ID.
     */
    public Optional<Credential> getCredential(Long id) {
        return credentialRepository.findById(id);
    }

    /**
     * PR19 - Get credentials for the active workspace with pagination. Strict
     * isolation: returns ONLY rows tagged with {@code organizationId}.
     *
     * <p>Post-V261: {@code organizationId} is REQUIRED. Personal workspace
     * resolves to the user's {@code is_default=true} personal org UUID; the
     * previous {@code organization_id IS NULL} branch is removed.
     */
    public PaginatedCredentialsResponse getCredentialsForScope(
            String tenantId,
            String organizationId,
            int page,
            int pageSize,
            CredentialStatus statusFilter
    ) {
        if (tenantId == null || tenantId.trim().isEmpty()) {
            throw new IllegalArgumentException("tenantId cannot be null or empty");
        }
        TenantResolver.requireOrgId(organizationId);
        if (page < 1) page = 1;
        if (pageSize < 1) pageSize = 10;
        if (pageSize > 100) pageSize = 100;

        List<Credential> credentials;
        int totalItems;
        if (statusFilter != null) {
            credentials = credentialRepository.findByOrganizationIdAndStatusStrict(
                    organizationId, statusFilter, page, pageSize);
            totalItems = credentialRepository.countByOrganizationIdAndStatusStrict(
                    organizationId, statusFilter);
        } else {
            credentials = credentialRepository.findByOrganizationIdStrict(organizationId, page, pageSize);
            totalItems = credentialRepository.countByOrganizationIdStrict(organizationId);
        }

        int totalPages = (int) Math.ceil((double) totalItems / pageSize);
        boolean hasNext = page < totalPages;
        boolean hasPrevious = page > 1;
        return new PaginatedCredentialsResponse(
                credentials, page, pageSize, totalItems, totalPages, hasNext, hasPrevious);
    }

    /**
     * Scope-aware single fetch - returns the credential only if it belongs to
     * the active workspace. Returns empty if the row exists but is in a
     * different scope (strict isolation).
     *
     * <p>Post-V261: {@code organizationId} is REQUIRED.
     */
    public Optional<Credential> getCredentialForScope(Long id, String tenantId, String organizationId) {
        TenantResolver.requireOrgId(organizationId);
        return credentialRepository.findById(id)
                .filter(cred -> matchesScope(cred, tenantId, organizationId));
    }

    /**
     * Strict-isolation delete. Returns false if the credential doesn't exist or
     * is in a different scope than the caller's active workspace.
     *
     * <p>Post-V261: {@code organizationId} is REQUIRED.
     */
    public boolean deleteCredentialForScope(Long id, String tenantId, String organizationId) {
        TenantResolver.requireOrgId(organizationId);
        Optional<Credential> opt = credentialRepository.findById(id)
                .filter(cred -> matchesScope(cred, tenantId, organizationId));
        if (opt.isEmpty()) {
            return false;
        }
        Credential credential = opt.get();
        boolean wasDefault = credential.isDefault();

        credentialRepository.deleteById(id);

        reassignDefaultAfterDelete(credential, wasDefault, organizationId);

        return true;
    }

    private void reassignDefaultAfterDelete(Credential deletedCredential, boolean wasDefault, String organizationId) {
        String integration = deletedCredential.integration();
        if (integration == null) {
            return;
        }

        Set<Long> vanishedFallbackIds = new HashSet<>();
        while (true) {
            List<Credential> remaining = credentialRepository.findByScopeAndIntegration(
                            deletedCredential.tenantId(), organizationId, integration)
                    .stream()
                    .filter(credential -> !vanishedFallbackIds.contains(credential.id()))
                    .toList();
            if (remaining.isEmpty()) {
                return;
            }

            boolean hasDefault = remaining.stream().anyMatch(Credential::isDefault);
            if (!wasDefault && hasDefault) {
                return;
            }

            Credential fallback = mostRecentCredential(remaining);
            try {
                credentialRepository.setAsDefaultInScope(
                        fallback.tenantId(), organizationId, fallback.id());
                return;
            } catch (RuntimeException ex) {
                // A concurrent request (e.g. a parallel bulk-delete of the same
                // scope) can delete the chosen fallback between our scope query and
                // the promote. setAsDefaultInScope raises that as an
                // IllegalArgumentException, but the @Repository proxy translates it
                // into a Spring DataAccessException (InvalidDataAccessApiUsageException),
                // so we CANNOT key off the exception type. Re-probe existence instead:
                // if the row is genuinely gone, skip it and pick the next best
                // candidate; if it still exists, the failure is unrelated (a real
                // error) and must propagate.
                if (credentialRepository.findById(fallback.id()).isPresent()) {
                    throw ex;
                }
                vanishedFallbackIds.add(fallback.id());
                log.debug(
                        "Skipping vanished credential {} while reassigning default after delete",
                        fallback.id());
            }
        }
    }

    private static Credential mostRecentCredential(List<Credential> credentials) {
        return credentials.stream()
                .max(Comparator.comparing(
                        Credential::createdAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElseThrow();
    }

    /**
     * Post-V261: scope match is purely org-equality. Caller guarantees a
     * non-null/non-blank {@code organizationId} (sweep enforced at every
     * scope-aware entry point).
     */
    private static boolean matchesScope(Credential cred, String tenantId, String organizationId) {
        return organizationId.equals(cred.organizationId());
    }

    /**
     * Get credentials for a tenant with pagination. Legacy entry point - kept
     * for back-compat callers that haven't migrated to scope-aware fetch.
     */
    public PaginatedCredentialsResponse getCredentialsByTenant(
            String tenantId,
            int page,
            int pageSize,
            CredentialStatus statusFilter
    ) {
        if (tenantId == null || tenantId.trim().isEmpty()) {
            throw new IllegalArgumentException("tenantId cannot be null or empty");
        }
        if (page < 1) {
            page = 1;
        }
        if (pageSize < 1) {
            pageSize = 10;
        }
        if (pageSize > 100) {
            pageSize = 100;
        }

        List<Credential> credentials;
        int totalItems;

        if (statusFilter != null) {
            credentials = credentialRepository.findByTenantIdAndStatus(tenantId, statusFilter, page, pageSize);
            totalItems = credentialRepository.countByTenantIdAndStatus(tenantId, statusFilter);
        } else {
            credentials = credentialRepository.findByTenantId(tenantId, page, pageSize);
            totalItems = credentialRepository.countByTenantId(tenantId);
        }

        int totalPages = (int) Math.ceil((double) totalItems / pageSize);
        boolean hasNext = page < totalPages;
        boolean hasPrevious = page > 1;

        return new PaginatedCredentialsResponse(
            credentials,
            page,
            pageSize,
            totalItems,
            totalPages,
            hasNext,
            hasPrevious
        );
    }

    /**
     * Get a credential by tenant and name.
     */
    public Optional<Credential> getCredentialByTenantAndName(String tenantId, String name) {
        return credentialRepository.findByTenantIdAndName(tenantId, name);
    }

    /**
     * Update a credential.
     */
    public Optional<Credential> updateCredential(Long id, String tenantId, Map<String, Object> updates) {
        Optional<Credential> existingOpt = credentialRepository.findById(id);
        if (existingOpt.isEmpty()) {
            return Optional.empty();
        }

        Credential existing = existingOpt.get();
        if (!existing.tenantId().equals(tenantId)) {
            return Optional.empty();
        }

        // Apply updates
        Credential updated = existing;
        if (updates.containsKey("name")) {
            updated = new Credential(
                updated.id(),
                updated.tenantId(),
                updated.organizationId(),
                (String) updates.get("name"),
                updated.integration(),
                updated.type(),
                updated.environment(),
                updated.status(),
                updated.description(),
                updated.credentialData(),
                updated.scopes(),
                updated.tags(),
                updated.owner(),
                updated.iconUrl(),
                updated.isDefault(),
                updated.lastUsed(),
                updated.createdAt(),
                Instant.now()
            );
        }
        // Add more update fields as needed

        return Optional.of(credentialRepository.save(updated));
    }

    /**
     * Update credential data (for OAuth2 token refresh).
     */
    public Credential updateCredentialData(Long id, String tenantId, Map<String, Object> newCredentialData) {
        Optional<Credential> existingOpt = credentialRepository.findById(id);
        if (existingOpt.isEmpty()) {
            throw new IllegalArgumentException("Credential not found: " + id);
        }

        Credential existing = existingOpt.get();
        if (!existing.tenantId().equals(tenantId)) {
            throw new IllegalArgumentException("Credential does not belong to this user");
        }

        Credential updated = new Credential(
            existing.id(),
            existing.tenantId(),
            existing.organizationId(),
            existing.name(),
            existing.integration(),
            existing.type(),
            existing.environment(),
            existing.status(),
            existing.description(),
            newCredentialData,
            existing.scopes(),
            existing.tags(),
            existing.owner(),
            existing.iconUrl(),
            existing.isDefault(),
            Instant.now(), // Update lastUsed
            existing.createdAt(),
            Instant.now()
        );

        return credentialRepository.save(updated);
    }

    /**
     * Record that a credential was exercised at execution time (drives the
     * "Last used" column in settings). Thin pass-through to
     * {@link CredentialRepository#touchLastUsed}: only {@code last_used} moves -
     * {@code credential_data} and {@code updated_at} are left untouched, unlike
     * {@link #updateCredentialData} which rewrites the whole row on an OAuth2
     * refresh. Callers throttle to avoid write amplification on hot workflows.
     */
    public void touchLastUsed(Long id) {
        credentialRepository.touchLastUsed(id);
    }

    /**
     * Scrub selected keys from {@code credential_data}, merge diagnostic fields, and flip the
     * credential's status - in one persisted snapshot.
     *
     * <p>Used exclusively by the OAuth2 refresh pipeline on a terminal error: the refresh_token
     * (or access_token) must be <em>removed</em> from the map, not set to {@code null} - a null
     * value would leave a sentinel key behind and confuse the expiring-token sweep predicate
     * (which uses {@code jsonb_exists}). Callers pass:
     * <ul>
     *   <li>{@code fieldsToRemove} - keys to drop from the existing credential_data.</li>
     *   <li>{@code newStatus} - typically {@code needs_reauth} (TERMINAL_USER) or {@code error}
     *       (TERMINAL_CONFIG). Pass {@code null} to keep the current status.</li>
     *   <li>{@code diagFieldsToMerge} - diagnostic fields to add/overwrite
     *       ({@code refresh_error_reason}, {@code refresh_error_http_status},
     *       {@code refresh_error_provider_code}, {@code refresh_error_at},
     *       {@code refresh_attempts_before_terminal}, {@code refresh_cooldown_until}).
     *       Pass an empty map to skip.</li>
     * </ul>
     *
     * <p>The encryption of remaining sensitive fields is preserved - {@link CredentialRepository}
     * re-encrypts on save by inspecting which keys are sensitive, so no double-encryption occurs.
     */
    public Credential scrubSensitiveFields(Long id,
                                           String tenantId,
                                           Set<String> fieldsToRemove,
                                           CredentialStatus newStatus,
                                           Map<String, Object> diagFieldsToMerge) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId cannot be null or empty");
        }
        Optional<Credential> existingOpt = credentialRepository.findById(id);
        if (existingOpt.isEmpty()) {
            throw new IllegalArgumentException("Credential not found: " + id);
        }
        Credential existing = existingOpt.get();
        if (!existing.tenantId().equals(tenantId)) {
            throw new IllegalArgumentException("Credential does not belong to this user");
        }

        Map<String, Object> newData = existing.credentialData() == null
                ? new HashMap<>()
                : new HashMap<>(existing.credentialData());
        if (fieldsToRemove != null) {
            for (String key : fieldsToRemove) {
                newData.remove(key);
            }
        }
        if (diagFieldsToMerge != null && !diagFieldsToMerge.isEmpty()) {
            newData.putAll(diagFieldsToMerge);
        }

        Credential updated = new Credential(
                existing.id(),
                existing.tenantId(),
                existing.organizationId(),
                existing.name(),
                existing.integration(),
                existing.type(),
                existing.environment(),
                newStatus != null ? newStatus : existing.status(),
                existing.description(),
                newData,
                existing.scopes(),
                existing.tags(),
                existing.owner(),
                existing.iconUrl(),
                existing.isDefault(),
                existing.lastUsed(),
                existing.createdAt(),
                Instant.now()
        );
        return credentialRepository.save(updated);
    }

    /**
     * Cascade-revoke every active user credential for {@code (tenantId, integration)}
     * when the underlying tenant-owned BYOK platform_credential row is being deleted.
     *
     * <p>This is the load-bearing fix for the silent-zombie bug raised in v3/v4 audits:
     * {@code OAuth2Service.handleOAuth2Callback} caches {@code oauth_client_secret} +
     * {@code client_secret_masked} INLINE in {@code auth.credentials.credential_data}
     * at OAuth-callback time, and {@code OAuth2Service.refreshAccessToken} reads that
     * inline copy first (only falling back to {@code platform_credentials} when absent).
     * Without this scrub, deleting the BYOK row leaves dependent user credentials
     * refreshing indefinitely against a platform_credential that no longer exists.
     *
     * <p>Steps for each dependent row already in {@code active}/{@code expiring}:
     * <ol>
     *   <li>Reuse {@link #scrubSensitiveFields} to drop the inline OAuth client copy
     *       AND the outstanding token material, and flip status to
     *       {@code needs_reauth}. The token scrub matches what
     *       {@code OAuth2Service.releaseTerminal} writes for any other terminal-user
     *       transition, so the rest of the refresh pipeline (scheduler, fast-path
     *       gate, internal-credential resolver) treats the row identically to a
     *       refresh-rejected credential.</li>
     *   <li>Set the Redis "refresh-disabled" sentinel + clear any "refresh-cooldown"
     *       so the in-flight fast-path gate immediately rejects further attempts
     *       without a DB round-trip. Same prefixes as {@link OAuth2Service}; making
     *       the constants {@code public} avoids a circular dep back into that service.</li>
     * </ol>
     *
     * <p>Rows already in a terminal state ({@code error}/{@code needs_reauth}) are
     * skipped - re-scrubbing would bump {@code updated_at} and might inadvertently
     * clear diagnostic fields useful for support. The method is idempotent: a second
     * call after every dependent has already been revoked returns {@code 0}.
     *
     * @return number of dependent credentials transitioned (excludes already-terminal rows)
     */
    @Transactional
    public int revokeForByokDelete(String tenantId, String integration) {
        if (tenantId == null || tenantId.isBlank() || integration == null || integration.isBlank()) {
            return 0;
        }
        // The BYOK row stores integration_name in normalized form (no separators, lowercased).
        // auth.credentials.integration stores the RAW iconSlug from the catalog template,
        // which may contain '-' or '_' (e.g. "audit-tracking", "azure_translator"). The
        // normalized-comparison repo method handles the asymmetry - see its javadoc.
        // Re-normalize on this side defensively in case the caller passes a non-normalized
        // form; the SQL is symmetric so a doubly-normalized input is a no-op.
        String normalizedIntegration = normalizeForCascade(integration);
        List<Credential> dependents = credentialRepository
                .findActiveByTenantIdAndIntegrationNormalized(tenantId, normalizedIntegration);
        if (dependents.isEmpty()) {
            return 0;
        }

        Set<String> fieldsToScrub = Set.of(
                // Tokens - match OAuth2Service.releaseTerminal so the row reaches the
                // same shape a refresh-rejected credential would.
                "access_token",
                "refresh_token",
                "refresh_token_issued_at",
                "refresh_cooldown_until",
                // BYOK-specific - the platform_credential row backing these is gone, so
                // the inline copy MUST also go or OAuth2Service.refreshAccessToken would
                // keep using it (line 725 reads inline first; only falls back when null).
                "oauth_client_secret",
                "client_secret_masked");

        Map<String, Object> diag = Map.of(
                "byok_revoked_at", Instant.now().toString(),
                "byok_revoke_reason", "platform_credential_deleted");

        int revoked = 0;
        for (Credential dep : dependents) {
            // Defense in depth: the SQL filter already limits to active/expiring, but a
            // lost-update race (status flipped between SELECT and UPDATE) shouldn't churn
            // a now-terminal row's diagnostics. Skip if status changed under us.
            if (dep.status() == CredentialStatus.error
                    || dep.status() == CredentialStatus.needs_reauth) {
                continue;
            }
            scrubSensitiveFields(dep.id(), tenantId, fieldsToScrub,
                    CredentialStatus.needs_reauth, diag);
            invalidateOAuth2RefreshSentinels(dep.id());
            revoked++;
        }

        if (revoked > 0) {
            log.info("Revoked {} user credentials following BYOK delete: tenant={}, integration={}",
                    revoked, tenantId, integration);
        }
        return revoked;
    }

    /**
     * Mirror of the SQL normalization used by
     * {@link CredentialRepository#findActiveByTenantIdAndIntegrationNormalized}: strip
     * everything except {@code [a-zA-Z0-9]}, then lowercase. Symmetric with the SQL so
     * the parameter and the row-side comparison reach the same canonical form.
     */
    private static String normalizeForCascade(String name) {
        if (name == null) return null;
        return name.replaceAll("[^a-zA-Z0-9]", "").toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Count the ACTIVE user credentials that would be transitioned to
     * {@code needs_reauth} if the BYOK platform_credential for
     * {@code (tenantId, integration)} were deleted right now. Mirror of
     * {@link #revokeForByokDelete} that does not mutate.
     *
     * <p>Uses the same normalized-integration comparison as the cascade itself
     * (see {@link CredentialRepository#countActiveByTenantIdAndIntegrationNormalized})
     * so the displayed delete-impact count matches what the cascade actually
     * transitions: rows already in {@code error} or {@code needs_reauth} are
     * excluded, and integrations with non-alphanumeric iconSlugs (e.g.
     * {@code "audit-tracking"}, {@code "azure_translator"}) are correctly
     * matched against their normalized BYOK row name.
     */
    public int countDependentForByokDelete(String tenantId, String integration) {
        if (tenantId == null || tenantId.isBlank() || integration == null || integration.isBlank()) {
            return 0;
        }
        return credentialRepository.countActiveByTenantIdAndIntegrationNormalized(
                tenantId, normalizeForCascade(integration));
    }

    /**
     * Delete the OAuth2 fast-path Redis sentinels for a credential. Same prefixes the
     * refresh pipeline uses ({@link OAuth2Service#REDIS_REFRESH_DISABLED_PREFIX} and
     * {@link OAuth2Service#REDIS_REFRESH_COOLDOWN_PREFIX}). Best-effort: a Redis outage
     * leaves the DB as the ground truth and the next refresh attempt will read
     * {@code status='needs_reauth'} from there.
     *
     * <p>We DELETE rather than SET-disabled here: the credential's terminal state is
     * already encoded in the DB row (status + scrubbed tokens), so the fast-path gate
     * hitting empty Redis falls back to DB and sees the new status. SET-disabled would
     * also work but adds a TTL window and an extra round-trip to no benefit.
     */
    private void invalidateOAuth2RefreshSentinels(Long credentialId) {
        try {
            redisTemplate.delete(OAuth2Service.REDIS_REFRESH_DISABLED_PREFIX + credentialId);
            redisTemplate.delete(OAuth2Service.REDIS_REFRESH_COOLDOWN_PREFIX + credentialId);
        } catch (Exception redisDown) {
            log.debug("Redis unreachable while invalidating refresh sentinels for credential {}: {}",
                    credentialId, redisDown.getMessage());
        }
    }

    /**
     * Delete a credential.
     * Ensures there's always a default credential if any remain for the integration:
     * - If the deleted credential was the default, reassigns to the most recent
     * - If only one credential remains and none is default, makes it the default
     */
    public boolean deleteCredential(Long id, String tenantId) {
        Optional<Credential> credentialOpt = credentialRepository.findById(id);
        if (credentialOpt.isEmpty()) {
            return false;
        }

        Credential credential = credentialOpt.get();
        if (!credential.tenantId().equals(tenantId)) {
            return false;
        }

        boolean wasDefault = credential.isDefault();
        String integration = credential.integration();

        credentialRepository.deleteById(id);

        // Ensure there's always a default if credentials remain for this integration
        if (integration != null) {
            List<Credential> remaining = credentialRepository.findByTenantIdAndIntegration(tenantId, integration);
            if (!remaining.isEmpty()) {
                // Check if any remaining credential is default
                boolean hasDefault = remaining.stream().anyMatch(Credential::isDefault);
                if (!hasDefault || wasDefault) {
                    // No default exists, or the deleted one was the default - assign to most recent
                    Credential mostRecent = remaining.get(0);
                    credentialRepository.setAsDefault(tenantId, mostRecent.id());
                }
            }
        }

        return true;
    }

    /**
     * Delete all credentials for a given integration name (across all tenants).
     * Used when an API's auth type changes during catalog reimport to invalidate
     * all user-stored credentials for that API.
     *
     * @return the number of deleted credentials
     */
    public int deleteByIntegration(String integration) {
        if (integration == null || integration.trim().isEmpty()) {
            throw new IllegalArgumentException("integration cannot be null or empty");
        }
        return credentialRepository.deleteByIntegration(integration.trim());
    }

    /**
     * Get credentials for a tenant by integration. Legacy entry point -
     * cross-scope (returns both personal and org rows). New callers should use
     * {@link #getCredentialsByIntegrationForScope}.
     */
    public List<Credential> getCredentialsByIntegration(String tenantId, String integration) {
        if (tenantId == null || tenantId.trim().isEmpty()) {
            throw new IllegalArgumentException("tenantId cannot be null or empty");
        }
        return credentialRepository.findByTenantIdAndIntegration(tenantId, integration);
    }

    /**
     * Distinct ACTIVE-credential integration names for one tenant. Used by agent-facing
     * list tools to compute the {@code requirements.integrations[].configured} flag in
     * one batch call instead of N+1 per-integration lookups.
     */
    public java.util.Set<String> findActiveIntegrationsByTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) return java.util.Set.of();
        return credentialRepository.findActiveIntegrationsByTenantId(tenantId);
    }

    /**
     * Org-aware "configured integrations": when an active workspace is supplied,
     * returns the integrations configured by ANY member of that workspace (so the
     * agent's {@code configured} flag matches the org-aware credential resolution).
     * Falls back to tenant scope when no org is supplied (back-compat / personal CLI).
     */
    public java.util.Set<String> findActiveIntegrationsForScope(String tenantId, String organizationId) {
        if (organizationId != null && !organizationId.isBlank()) {
            return credentialRepository.findActiveIntegrationsByOrganizationId(organizationId);
        }
        return findActiveIntegrationsByTenantId(tenantId);
    }

    /**
     * PR19 - strict-isolation by-integration lookup. Returns ONLY rows in
     * the active workspace's scope. Used by {@code /api/credentials/by-integration}.
     */
    public List<Credential> getCredentialsByIntegrationForScope(String tenantId,
                                                                String organizationId,
                                                                String integration) {
        if (tenantId == null || tenantId.trim().isEmpty()) {
            throw new IllegalArgumentException("tenantId cannot be null or empty");
        }
        return credentialRepository.findByScopeAndIntegration(tenantId, organizationId, integration);
    }

    /**
     * Get all credentials for a tenant. Legacy entry point - cross-scope.
     */
    public List<Credential> getAllCredentialsByTenant(String tenantId) {
        if (tenantId == null || tenantId.trim().isEmpty()) {
            throw new IllegalArgumentException("tenantId cannot be null or empty");
        }
        return credentialRepository.findAllByTenantId(tenantId);
    }

    /**
     * PR19 - strict-isolation "all credentials" for the active workspace.
     * Backed by the same finders as {@link #getCredentialsForScope} without
     * pagination. Used by frontend workflow-builder + chat-attachment paths
     * that need a non-paginated lookup.
     *
     * <p>Post-V261: {@code organizationId} is REQUIRED.
     */
    public List<Credential> getAllCredentialsForScope(String tenantId, String organizationId) {
        if (tenantId == null || tenantId.trim().isEmpty()) {
            throw new IllegalArgumentException("tenantId cannot be null or empty");
        }
        TenantResolver.requireOrgId(organizationId);
        // Unpaginated - use a large page-size so the existing scope-aware
        // repository finder returns every row in one shot. (For tenants with
        // > Integer.MAX_VALUE creds we have bigger problems; 10_000 covers
        // every realistic scope.)
        return credentialRepository.findByOrganizationIdStrict(organizationId, 1, 10_000);
    }

    /**
     * PR19 - scope-aware {@code setAsDefault}. The repository sweep that
     * clears sibling defaults is narrowed to the active scope so toggling an
     * org credential as default NEVER touches a different org's defaults.
     * Strict-isolation guard: caller must be an active member of the row's
     * org (membership is enforced upstream by the gateway).
     *
     * <p>Post-V261: {@code organizationId} is REQUIRED.
     */
    public void setAsDefault(String tenantId, String organizationId, Long credentialId) {
        if (tenantId == null || tenantId.trim().isEmpty()) {
            throw new IllegalArgumentException("tenantId cannot be null or empty");
        }
        TenantResolver.requireOrgId(organizationId);
        if (credentialId == null) {
            throw new IllegalArgumentException("credentialId cannot be null");
        }
        // Strict-isolation gate: refuse to mutate a row in a different scope.
        Credential cred = credentialRepository.findById(credentialId)
                .filter(c -> matchesScope(c, tenantId, organizationId))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Credential not found in active scope: " + credentialId));
        credentialRepository.setAsDefaultInScope(cred.tenantId(), organizationId, credentialId);
    }

    /**
     * PR19 - scope-aware {@code clearDefault}. The "only credential for
     * integration" guard counts rows in the active org scope only, so a
     * different org's Gmail credential never blocks clearing the default flag
     * from this org's Gmail credential.
     *
     * <p>Post-V261: {@code organizationId} is REQUIRED.
     */
    public void clearDefault(String tenantId, String organizationId, Long credentialId) {
        if (tenantId == null || tenantId.trim().isEmpty()) {
            throw new IllegalArgumentException("tenantId cannot be null or empty");
        }
        TenantResolver.requireOrgId(organizationId);
        if (credentialId == null) {
            throw new IllegalArgumentException("credentialId cannot be null");
        }

        Credential credential = credentialRepository.findById(credentialId)
                .filter(c -> matchesScope(c, tenantId, organizationId))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Credential not found in active scope: " + credentialId));

        // Scope-isolated "only credential for integration" check - never count
        // across scope boundaries.
        String integration = credential.integration();
        if (integration != null) {
            int count = credentialRepository.countByScopeAndIntegrationExact(
                    credential.tenantId(), organizationId, integration);
            if (count == 1 && credential.isDefault()) {
                throw new IllegalStateException(
                        "Cannot remove default status: this is the only credential for " + integration);
            }
        }

        credentialRepository.clearDefaultInScope(credential.tenantId(), organizationId, credentialId);
    }

    /**
     * Opaque credential-state version for one execution context (own rows +
     * active-workspace rows). Moves on every mutation that can change credential
     * resolution (connect / delete / set-as-default / edit / token refresh).
     * Consumed by catalog-service as a response-cache key component - see
     * {@code CredentialRepository.computeStateVersion}.
     */
    public String getCredentialStateVersion(String tenantId, String organizationId) {
        if (tenantId == null || tenantId.trim().isEmpty()) {
            throw new IllegalArgumentException("tenantId cannot be null or empty");
        }
        return credentialRepository.computeStateVersion(tenantId.trim(), organizationId);
    }

    /**
     * Get the default credential for an integration.
     * Returns the first credential marked as default, or empty if none.
     */
    public Optional<Credential> getDefaultCredential(String tenantId, String integration) {
        if (tenantId == null || tenantId.trim().isEmpty()) {
            throw new IllegalArgumentException("tenantId cannot be null or empty");
        }
        return credentialRepository.findDefaultByTenantIdAndIntegration(tenantId, integration);
    }
}
