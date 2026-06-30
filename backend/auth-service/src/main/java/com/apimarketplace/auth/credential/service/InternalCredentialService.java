package com.apimarketplace.auth.credential.service;

import com.apimarketplace.auth.credential.domain.CredentialModels.Credential;
import com.apimarketplace.auth.credential.domain.CredentialModels.CredentialStatus;
import com.apimarketplace.auth.credential.service.oauth2.refresh.RefreshTerminalException;
import com.apimarketplace.auth.credential.service.oauth2.refresh.RefreshTransientException;
import com.apimarketplace.common.security.CredentialEncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Service that exposes credential operations for internal API consumption.
 * Combines CredentialService, OAuth2Service, and CredentialEncryptionService
 * to provide the same logic that catalog-service previously did with direct SQL.
 */
@Service
public class InternalCredentialService {

    private static final Logger log = LoggerFactory.getLogger(InternalCredentialService.class);
    private static final String PLATFORM_TENANT_ID = "PLATFORM";

    /**
     * Eager-refresh cache for OAuth2 client_credentials flow. When 100 parallel API calls land
     * on the same credential whose {@code access_token} is blank or just-expired, the naive path
     * would have every caller POST to the provider's token endpoint. Even though
     * {@link OAuth2Service#refreshToken} already serializes via SETNX, each caller still pays
     * the lock-contention + DB re-read cost. A short-lived Redis marker absorbs the herd: once
     * one caller finishes a successful refresh, the next N callers within
     * {@link #CLIENT_CREDS_CACHE_TTL} read the already-persisted token from DB and skip the
     * refresh attempt entirely.
     *
     * <p>TTL tuning: 90 seconds. Long enough to dampen a 100-way fan-in on a slow-mobile-network
     * workflow tick; short enough that a compromised client_credentials rotation converges
     * within two minutes of the admin revoking them.
     */
    private static final String REDIS_CLIENT_CREDS_CACHE_PREFIX = "oauth2:client-creds-cache:";
    private static final Duration CLIENT_CREDS_CACHE_TTL = Duration.ofSeconds(90);

    /**
     * Minimum gap between two {@code last_used} stamps on the same credential.
     * Execution resolves a credential on every API/agent call, so a hot workflow
     * could touch one credential many times per second. The settings "Last used"
     * display only needs minute-level accuracy, so we damp the write rate to at
     * most one UPDATE per credential per window. The throttle reads the
     * already-loaded credential's {@code last_used}, so it costs no extra query.
     */
    private static final Duration LAST_USED_THROTTLE = Duration.ofSeconds(60);

    private final CredentialService credentialService;
    private final OAuth2Service oAuth2Service;
    private final CredentialEncryptionService encryptionService;
    private final PlatformCredentialService platformCredentialService;
    private final StringRedisTemplate redisTemplate;

    public InternalCredentialService(CredentialService credentialService,
                                      OAuth2Service oAuth2Service,
                                      CredentialEncryptionService encryptionService,
                                      PlatformCredentialService platformCredentialService,
                                      StringRedisTemplate redisTemplate) {
        this.credentialService = credentialService;
        this.oAuth2Service = oAuth2Service;
        this.encryptionService = encryptionService;
        this.platformCredentialService = platformCredentialService;
        this.redisTemplate = redisTemplate;
    }

    /**
     * V103: token + auth-type pair returned by {@link #getAccessTokenInfo}. The
     * {@code type} carries the {@link com.apimarketplace.auth.credential.domain.CredentialModels.CredentialType}
     * name ("OAuth2", "API_Key", …) so catalog-service can map it to a variant
     * identifier when selecting {@code tool_credentials} injection metadata.
     * A null {@code type} means the token came from a PLATFORM path where the
     * variant is decided by admin configuration, not by the credential itself.
     */
    public record AccessTokenInfo(String accessToken, String type) {}

    /**
     * Get decrypted access token for a user credential.
     * Mirrors the logic in catalog's UserCredentialService.getAccessToken().
     */
    public Optional<String> getAccessToken(String userId, String credentialName) {
        return getAccessTokenInfo(userId, credentialName).map(AccessTokenInfo::accessToken);
    }

    /**
     * V103 variant-aware lookup: same resolution rules as {@link #getAccessToken}
     * but also surfaces the credential's {@link CredentialType} for callers that
     * need to pick a per-variant injection row.
     */
    public Optional<AccessTokenInfo> getAccessTokenInfo(String userId, String credentialName) {
        return getAccessTokenInfo(userId, credentialName, null);
    }

    /**
     * Org-aware variant of {@link #getAccessTokenInfo(String, String)}: when the
     * executing user has no own credential, resolves the workspace-shared one for
     * the active {@code organizationId}. See {@link #findCredential(String, String, String, String)}.
     */
    public Optional<AccessTokenInfo> getAccessTokenInfo(String userId, String credentialName, String organizationId) {
        if (userId == null || credentialName == null) return Optional.empty();

        // For PLATFORM credentials, check platform first. Platform tokens have no
        // per-user credential type - variant resolution belongs to admin config.
        if (PLATFORM_TENANT_ID.equals(userId)) {
            Optional<String> platformToken = getPlatformAccessToken(credentialName);
            if (platformToken.isPresent()) return Optional.of(new AccessTokenInfo(platformToken.get(), null));
        }

        // Find credential by name or integration (org-aware fallback)
        String integrationName = credentialName.replaceAll("-credential$", "");
        Credential cred = findCredential(userId, credentialName, integrationName, organizationId);
        markUsed(cred);
        return getAccessTokenInfoFromCredential(userId, cred);
    }

    /**
     * Variant-aware lookup for a concrete workflow-selected credential id.
     */
    public Optional<AccessTokenInfo> getAccessTokenInfoById(String userId, Long credentialId, String organizationId) {
        if (userId == null || credentialId == null) return Optional.empty();
        Credential cred = findActiveCredentialById(userId, credentialId, organizationId);
        markUsed(cred);
        return getAccessTokenInfoFromCredential(userId, cred);
    }

    private Optional<AccessTokenInfo> getAccessTokenInfoFromCredential(String userId, Credential cred) {
        if (cred == null) return Optional.empty();
        String type = cred.type() != null ? cred.type().name() : null;

        Map<String, Object> data = cred.credentialData();
        if (data == null) return Optional.empty();

        // Check access_token
        String accessToken = getDecryptedField(data, "access_token");
        if (accessToken != null && !accessToken.isBlank()) {
            return Optional.of(new AccessTokenInfo(accessToken, type));
        }

        // Check API key fields
        for (String field : List.of("api_key", "api_token", "bearer_token")) {
            String apiKey = getDecryptedField(data, field);
            if (apiKey != null && !apiKey.isBlank()) {
                return Optional.of(new AccessTokenInfo(apiKey, type));
            }
        }

        // Try OAuth2 client_credentials flow. Eager-refresh herd damper: if a refresh landed in
        // the last CLIENT_CREDS_CACHE_TTL window, trust the stored access_token instead of
        // re-hitting the provider. Without this, a 100-parallel API fan-in would stampede the
        // OAuth2Service lock even when the token was refreshed two seconds ago.
        String clientId = getField(data, "client_id", "oauth_client_id");
        String clientSecret = getDecryptedField(data, "client_secret", "oauth_client_secret");
        if (clientId != null && clientSecret != null) {
            String refreshUserId = credentialOwnerUserId(cred, userId);
            if (hasRecentClientCredsRefresh(cred.id())) {
                log.debug("Skipping client_credentials refresh for cred={} - within TTL window", cred.id());
                return Optional.empty();
            }
            try {
                Credential refreshed = oAuth2Service.refreshToken(cred.id(), refreshUserId);
                if (refreshed != null && refreshed.credentialData() != null) {
                    String newToken = getDecryptedField(refreshed.credentialData(), "access_token");
                    if (newToken != null) {
                        markClientCredsRefreshed(cred.id());
                        return Optional.of(new AccessTokenInfo(newToken, type));
                    }
                }
            } catch (RefreshTerminalException terminal) {
                // Provider said "never retry" (invalid_grant / invalid_client / scope drift). The
                // access_token has been scrubbed by OAuth2Service.releaseTerminal; surfacing the
                // stale field would return an empty/null anyway. Log once at debug - scheduler &
                // metrics record the terminal event - and bail.
                log.debug("OAuth2 refresh terminal for cred={}: bucket={} code={}",
                        cred.id(), terminal.bucket(), terminal.providerCode());
            } catch (RefreshTransientException transientErr) {
                // Provider hiccup (5xx, socket timeout, 429). The stored access_token may still
                // be valid for a few minutes. Mark the cache to damp the herd but don't return
                // the stale token here - caller paths that want the fallback use
                // forceRefreshAndGetToken (which has explicit fallback semantics).
                markClientCredsRefreshed(cred.id());
                log.debug("OAuth2 refresh transient for cred={}: bucket={} attempt={}",
                        cred.id(), transientErr.bucket(), transientErr.attempt());
            } catch (Exception e) {
                log.debug("OAuth2 refresh failed for cred={}: {}", cred.id(), e.getMessage());
            }
        }

        return Optional.empty();
    }

    /**
     * Force refresh token and return new access token.
     *
     * <p>Error-bucket-aware behavior:
     * <ul>
     *   <li>{@link RefreshTerminalException} → return {@link Optional#empty()} immediately.
     *       OAuth2Service has already scrubbed the stored {@code access_token}, so falling back
     *       would return nothing useful and would also mask the terminal state from the caller's
     *       401-retry logic (which would otherwise retry indefinitely against a revoked token).</li>
     *   <li>{@link RefreshTransientException} → fall back to the persisted access_token. A 5xx
     *       or timeout is usually a provider blip; the token we stored last cycle may still be
     *       valid for a few minutes. If it isn't, the caller's 401 path handles it.</li>
     *   <li>{@code refresh_in_progress} (SETNX lock held) → sleep 400ms, re-check credential
     *       status (it may have flipped to terminal during the race), then re-read the stored
     *       access_token. Only re-try the refresh if status is still active.</li>
     * </ul>
     */
    public Optional<String> forceRefreshAndGetToken(String userId, String credentialName) {
        return forceRefreshAndGetToken(userId, credentialName, null);
    }

    /**
     * Org-aware variant of {@link #forceRefreshAndGetToken(String, String)}: refreshes
     * whichever credential the org-aware resolution selects (the user's own, else the
     * workspace-shared one). Used by the 401-retry path so a refresh targets the same
     * credential the execution path actually used.
     */
    public Optional<String> forceRefreshAndGetToken(String userId, String credentialName, String organizationId) {
        String integrationName = credentialName.replaceAll("-credential$", "");
        Credential cred = findCredential(userId, credentialName, integrationName, organizationId);
        if (cred == null) return Optional.empty();
        markUsed(cred);

        // Refresh runs under the credential OWNER's identity (cred.tenantId()), not the
        // executing user - credentialOwnerUserId() already handles this inside the
        // (Credential, userId, label) overload, so a workspace-shared credential owned
        // by another member refreshes correctly.
        return forceRefreshAndGetToken(cred, userId, credentialName);
    }

    /**
     * Force refresh a concrete workflow-selected credential id.
     */
    public Optional<String> forceRefreshAndGetTokenById(String userId, Long credentialId, String organizationId) {
        if (userId == null || credentialId == null) return Optional.empty();
        Credential cred = findActiveCredentialById(userId, credentialId, organizationId);
        if (cred == null) return Optional.empty();
        markUsed(cred);
        return forceRefreshAndGetToken(cred, userId, "id=" + credentialId);
    }

    private Optional<String> forceRefreshAndGetToken(Credential cred, String userId, String credentialLabel) {
        String refreshUserId = credentialOwnerUserId(cred, userId);
        try {
            return refreshAndExtract(cred, refreshUserId);
        } catch (RefreshTerminalException terminal) {
            // No fallback: access_token has been scrubbed by OAuth2Service.releaseTerminal.
            // Returning a stale value would mislead callers into retrying against a revoked
            // credential; empty tells them to surface the re-auth requirement to the user.
            log.warn("Force refresh terminal for user={}, cred={}: bucket={} code={}",
                    userId, credentialLabel, terminal.bucket(), terminal.providerCode());
            return Optional.empty();
        } catch (RefreshTransientException transientErr) {
            log.warn("Force refresh transient for user={}, cred={}: bucket={} attempt={} - falling back to stored access_token",
                    userId, credentialLabel, transientErr.bucket(), transientErr.attempt());
            return readLatestAccessToken(cred.id(), refreshUserId);
        } catch (IllegalStateException e) {
            if (!"refresh_in_progress".equals(e.getMessage())) {
                log.warn("Force refresh failed for user={}, cred={}: {}", userId, credentialLabel, e.getMessage());
                return readLatestAccessToken(cred.id(), refreshUserId);
            }
            // Lock contention. Wait for the other caller's refresh to land, then
            // re-read the credential - the fresh access_token should already be persisted.
            try {
                Thread.sleep(REFRESH_RACE_BACKOFF_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
            // The race winner may have flipped the credential to terminal. Re-read the status
            // before attempting any further refresh: if needs_reauth / error, a retry would
            // trip the authoritativeGate and waste the SETNX round-trip.
            Optional<Credential> reread = credentialService.getCredential(cred.id())
                    .filter(c -> c.tenantId().equals(refreshUserId));
            if (reread.isPresent() && isTerminalStatus(reread.get().status())) {
                log.debug("Credential {} flipped to {} during race - not retrying refresh",
                        cred.id(), reread.get().status());
                return Optional.empty();
            }
            Optional<String> latest = readLatestAccessToken(cred.id(), refreshUserId);
            if (latest.isPresent()) {
                log.debug("Recovered fresh token from the winning refresher for cred={}", cred.id());
                return latest;
            }
            // The winner hadn't finished yet AND status is still non-terminal. Try once more
            // ourselves - by now the lock should be free (TTL or release).
            try {
                return refreshAndExtract(cred, refreshUserId);
            } catch (RefreshTerminalException terminalOnRetry) {
                log.warn("Force refresh terminal on retry for user={}, cred={}: bucket={}",
                        userId, credentialLabel, terminalOnRetry.bucket());
                return Optional.empty();
            } catch (RefreshTransientException transientOnRetry) {
                log.warn("Force refresh transient on retry for user={}, cred={}: attempt={}",
                        userId, credentialLabel, transientOnRetry.attempt());
                return readLatestAccessToken(cred.id(), refreshUserId);
            } catch (Exception retryEx) {
                log.warn("Force refresh retry after lock contention failed for user={}, cred={}: {}",
                        userId, credentialLabel, retryEx.getMessage());
                return Optional.empty();
            }
        } catch (Exception e) {
            // Unclassified failure (JSON parse blow-up, NPE, …). Fall back to the stored
            // access_token and let the caller's own 401-retry path take over.
            log.warn("Force refresh failed for user={}, cred={}: {} - falling back to stored access_token",
                    userId, credentialLabel, e.getMessage());
            return readLatestAccessToken(cred.id(), refreshUserId);
        }
    }

    private String credentialOwnerUserId(Credential cred, String fallbackUserId) {
        return cred != null && cred.tenantId() != null ? cred.tenantId() : fallbackUserId;
    }

    private boolean isTerminalStatus(CredentialStatus status) {
        return status == CredentialStatus.error || status == CredentialStatus.needs_reauth;
    }

    /** Backoff used once when we lose the per-credential refresh lock race. */
    private static final long REFRESH_RACE_BACKOFF_MS = 400L;

    private Optional<String> refreshAndExtract(Credential cred, String userId) {
        Credential refreshed = oAuth2Service.refreshToken(cred.id(), userId);
        if (refreshed != null && refreshed.credentialData() != null) {
            String newToken = getDecryptedField(refreshed.credentialData(), "access_token");
            if (newToken != null) return Optional.of(newToken);
        }
        return Optional.empty();
    }

    private Optional<String> readLatestAccessToken(Long credentialId, String userId) {
        return credentialService.getCredential(credentialId)
                .filter(c -> c.tenantId().equals(userId))
                .map(Credential::credentialData)
                .map(data -> getDecryptedField(data, "access_token"))
                .filter(tok -> tok != null && !tok.isBlank());
    }

    /**
     * Try OAuth2 refresh.
     */
    public Optional<String> refreshAccessToken(String userId, String credentialName) {
        return forceRefreshAndGetToken(userId, credentialName);
    }

    /** Org-aware variant of {@link #refreshAccessToken(String, String)}. */
    public Optional<String> refreshAccessToken(String userId, String credentialName, String organizationId) {
        return forceRefreshAndGetToken(userId, credentialName, organizationId);
    }

    /**
     * Get all decrypted credential fields as a map.
     */
    public Map<String, String> getCredentialDataMap(String userId, String credentialName) {
        return getCredentialDataMap(userId, credentialName, null);
    }

    /**
     * Org-aware variant of {@link #getCredentialDataMap(String, String)}: resolves the
     * workspace-shared credential when the executing user has none of their own.
     */
    public Map<String, String> getCredentialDataMap(String userId, String credentialName, String organizationId) {
        if (userId == null || credentialName == null) return Map.of();

        String integrationName = credentialName.replaceAll("-credential$", "");
        Credential cred = findCredential(userId, credentialName, integrationName, organizationId);
        markUsed(cred);
        return decryptCredentialData(cred);
    }

    /**
     * Get all decrypted fields for a concrete workflow-selected credential id.
     */
    public Map<String, String> getCredentialDataMapById(String userId, Long credentialId, String organizationId) {
        if (userId == null || credentialId == null) return Map.of();
        Credential cred = findActiveCredentialById(userId, credentialId, organizationId);
        markUsed(cred);
        return decryptCredentialData(cred);
    }

    private Map<String, String> decryptCredentialData(Credential cred) {
        if (cred == null || cred.credentialData() == null) return Map.of();
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : cred.credentialData().entrySet()) {
            if (entry.getValue() instanceof String strValue) {
                result.put(entry.getKey(), encryptionService.decrypt(strValue));
            }
        }
        return result;
    }

    /**
     * Return a workflow-selected active credential if the caller owns it or is
     * executing in the credential's organization scope.
     */
    public Optional<Credential> getActiveUserCredentialById(String userId, Long credentialId, String organizationId) {
        return Optional.ofNullable(findActiveCredentialById(userId, credentialId, organizationId));
    }

    /**
     * Get platform credential access token for an integration, tenant-aware.
     * Checks api_key first, then clientId/clientSecret for OAuth2 flows.
     */
    public Optional<String> getPlatformAccessToken(String integrationName, String tenantId) {
        return getPlatformAccessToken(integrationName, tenantId, null);
    }

    /**
     * Org-aware (V362) platform credential access token resolution. When an
     * {@code organizationId} is in scope the lookup is workspace-isolated
     * (workspace BYOK &gt; personal BYOK &gt; platform). When it is null - e.g. an
     * async execution path that has not (yet) threaded the org - it falls back to
     * the tenant-keyed lookup so backfilled BYOK rows keep resolving (backward
     * compatible, matching the V208 "runtime stays tenant-keyed until org is
     * threaded" posture).
     */
    public Optional<String> getPlatformAccessToken(String integrationName, String tenantId, String organizationId) {
        var rawCred = (organizationId != null && tenantId != null)
                ? platformCredentialService.getRawCredential(integrationName, tenantId, organizationId)
                : platformCredentialService.getRawCredential(integrationName, tenantId);
        if (rawCred.isEmpty()) return Optional.empty();

        var cred = rawCred.get();

        // API key auth -- return decrypted key directly
        if (cred.apiKey() != null && !cred.apiKey().isBlank()) {
            return Optional.of(encryptionService.decrypt(cred.apiKey()));
        }

        // OAuth2 credentials exist but no direct key -- caller should use OAuth2 flow
        return Optional.empty();
    }

    /**
     * Get platform credential access token (platform-wide only).
     */
    public Optional<String> getPlatformAccessToken(String integrationName) {
        return getPlatformAccessToken(integrationName, null, null);
    }

    // ========== Private Helpers ==========

    /**
     * Best-effort: stamp {@code last_used = now()} on a freshly resolved user
     * credential so the settings "Last used" column reflects real execution use,
     * not just the OAuth2-refresh path that {@link CredentialService#updateCredentialData}
     * already covered. Fires on the resolution paths that hand a credential to an
     * outbound call - data-map and access-token (by-name and by-id), plus the
     * force-refresh entry points (where a transient-error fallback returns the stored
     * token without writing the row) - so it covers Basic-auth / API-key / bearer
     * credentials that never refresh (e.g. Twilio).
     *
     * <p>Throttled via the credential's own {@code last_used} ({@link #LAST_USED_THROTTLE})
     * to avoid write amplification on hot workflows. Stamping happens on resolution,
     * before the remote responds: a credential that resolves but is rejected
     * downstream (e.g. a stale token → 401) still counts as used - it WAS exercised.
     * Failures are swallowed: a {@code last_used} write must never break credential
     * resolution (which would break the workflow).
     */
    private void markUsed(Credential cred) {
        if (cred == null || cred.id() == null) {
            return;
        }
        Instant last = cred.lastUsed();
        if (last != null && Duration.between(last, Instant.now()).compareTo(LAST_USED_THROTTLE) < 0) {
            return; // recently stamped - skip the write
        }
        try {
            credentialService.touchLastUsed(cred.id());
        } catch (Exception e) {
            log.debug("last_used stamp skipped for cred={}: {}", cred.id(), e.getMessage());
        }
    }

    /**
     * Resolve a user credential by name, then by integration. <b>Org-aware</b>: when
     * the executing user has no matching credential of their own AND an
     * {@code organizationId} (the active workspace) is supplied, fall back to the
     * workspace-shared credential for that integration ({@code is_default} first).
     *
     * <p>This mirrors the by-id path's "owner OR org scope" rule
     * ({@link #findActiveCredentialById}) so a workflow running under one member's
     * identity (e.g. the workflow owner) can use another member's workspace-shared
     * credential - the workspace sharing model. The user's OWN credential always
     * takes precedence; the org fallback only fires when the tenant-scoped lookup
     * returns nothing, making this a strictly additive change (no behavior change
     * when the executing user has their own credential, or when no org is supplied).
     */
    private Credential findCredential(String userId, String credentialName, String integrationName,
                                      String organizationId) {
        // Typed enum comparison - renaming CredentialStatus.active would then fail at compile
        // time rather than silently returning null here and leaving every caller token-less.
        Optional<Credential> byName = credentialService.getCredentialByTenantAndName(userId.trim(), credentialName);
        if (byName.isPresent() && byName.get().status() == CredentialStatus.active) {
            return byName.get();
        }
        var byIntegration = credentialService.getCredentialsByIntegration(userId.trim(), integrationName);
        Credential ownHit = byIntegration.stream()
                .filter(c -> c.status() == CredentialStatus.active)
                .findFirst()
                .orElse(null);
        if (ownHit != null) {
            return ownHit;
        }
        // Workspace fallback - a credential shared in the active org by any member,
        // is_default first. Guarded on a non-blank org because the scope finder
        // requires it (TenantResolver.requireOrgId). Org membership is enforced
        // upstream (gateway injects X-Organization-ID only for members).
        if (organizationId != null && !organizationId.isBlank()) {
            return credentialService
                    .getCredentialsByIntegrationForScope(userId.trim(), organizationId, integrationName)
                    .stream()
                    .filter(c -> c.status() == CredentialStatus.active)
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }

    private Credential findActiveCredentialById(String userId, Long credentialId, String organizationId) {
        if (userId == null || userId.isBlank() || credentialId == null) {
            return null;
        }
        return credentialService.getCredential(credentialId)
                .filter(c -> c.status() == CredentialStatus.active)
                .filter(c -> userId.trim().equals(c.tenantId())
                        || (organizationId != null
                            && !organizationId.isBlank()
                            && organizationId.equals(c.organizationId())))
                .orElse(null);
    }

    private String getDecryptedField(Map<String, Object> data, String... fieldNames) {
        for (String field : fieldNames) {
            Object val = data.get(field);
            if (val instanceof String strVal && !strVal.isBlank()) {
                return encryptionService.decrypt(strVal);
            }
        }
        return null;
    }

    private String getField(Map<String, Object> data, String... fieldNames) {
        for (String field : fieldNames) {
            Object val = data.get(field);
            if (val instanceof String strVal && !strVal.isBlank()) {
                return strVal;
            }
        }
        return null;
    }

    /**
     * True when a client_credentials refresh landed within {@link #CLIENT_CREDS_CACHE_TTL}.
     * Redis unavailability is swallowed - on failure we return {@code false} so the caller
     * goes through the normal OAuth2Service SETNX path (which already has its own defenses).
     */
    private boolean hasRecentClientCredsRefresh(Long credentialId) {
        try {
            // hasKey() returns Boolean (nullable on connection error); use equals to avoid NPE
            // when Redis is degraded or the connection factory returns null.
            return Boolean.TRUE.equals(
                    redisTemplate.hasKey(REDIS_CLIENT_CREDS_CACHE_PREFIX + credentialId));
        } catch (Exception redisDown) {
            log.debug("Redis client-creds cache check skipped for cred={}: {}",
                    credentialId, redisDown.getMessage());
            return false;
        }
    }

    private void markClientCredsRefreshed(Long credentialId) {
        try {
            redisTemplate.opsForValue().set(
                    REDIS_CLIENT_CREDS_CACHE_PREFIX + credentialId,
                    "1",
                    CLIENT_CREDS_CACHE_TTL);
        } catch (Exception redisDown) {
            log.debug("Redis client-creds cache write skipped for cred={}: {}",
                    credentialId, redisDown.getMessage());
        }
    }
}
