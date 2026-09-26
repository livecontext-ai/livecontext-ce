package com.apimarketplace.credential.client;

import com.apimarketplace.credential.client.dto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import com.apimarketplace.common.web.OrgContextHeaderForwarder;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * HTTP client for credential operations on auth-service.
 * Follows the same pattern as AgentClient, DataSourceClient, etc.
 */
public class CredentialClient {

    private static final Logger log = LoggerFactory.getLogger(CredentialClient.class);
    private static final String INTERNAL_PROVIDER_ID = "internal-credential-client";

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String gatewaySecretKey;

    public CredentialClient(String baseUrl) {
        this(baseUrl, null);
    }

    public CredentialClient(String baseUrl, String gatewaySecretKey) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(2).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(3).toMillis());
        this.restTemplate = new RestTemplate(factory);
        this.baseUrl = baseUrl;
        this.gatewaySecretKey = gatewaySecretKey;
    }

    // ========== User Credentials ==========

    /**
     * Get decrypted access token for a user credential.
     */
    public Optional<String> getAccessToken(String userId, String credentialName) {
        return getAccessTokenInfo(userId, credentialName).map(AccessTokenResult::getAccessToken);
    }

    /**
     * Distinct ACTIVE-credential integration names configured for one tenant.
     *
     * <p>Agent-facing list tools call this <b>once per request</b> and intersect locally
     * with each item's required-integration set to compute the
     * {@code requirements.integrations[].configured} flag in the agent envelope. Avoids
     * the N+1 trap of per-integration {@code getAccessToken} calls.
     *
     * <p>Best-effort: a credential-service failure returns an empty set so the caller
     * degrades to "configured: false everywhere" - strictly more conservative than a
     * false-positive that lies to the agent.
     */
    public java.util.Set<String> getConfiguredIntegrations(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) return java.util.Set.of();
        String url = baseUrl + "/api/internal/credentials/configured-integrations/" + tenantId;
        try {
            // Pass headers (not a null entity) so OrgContextHeaderForwarder propagates
            // X-Organization-ID - lets auth-service answer "configured" org-aware
            // (workspace-shared credentials count as configured).
            ResponseEntity<java.util.Set<String>> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(buildHeaders(tenantId)),
                    new ParameterizedTypeReference<>() {});
            java.util.Set<String> body = response.getBody();
            return body != null ? body : java.util.Set.of();
        } catch (Exception e) {
            log.warn("Failed to fetch configured-integrations for tenant={}: {}", tenantId, e.getMessage());
            return java.util.Set.of();
        }
    }

    /**
     * V103 variant-aware lookup: same token resolution as {@link #getAccessToken}
     * but also exposes the credential's auth type so catalog-service can pick the
     * matching {@code tool_credentials} injection variant. Returns empty when no
     * credential is found; returns a result with null {@code type} for PLATFORM
     * tokens where variant is decided by admin configuration.
     */
    public Optional<AccessTokenResult> getAccessTokenInfo(String userId, String credentialName) {
        try {
            String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
                    .path("/api/internal/credentials/access-token")
                    .queryParam("userId", userId)
                    .queryParam("name", credentialName)
                    .toUriString();
            ResponseEntity<AccessTokenResult> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(buildHeaders(userId)),
                    AccessTokenResult.class);
            AccessTokenResult result = resp.getBody();
            if (result != null && result.isFound()) {
                return Optional.of(result);
            }
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Failed to get access token for user={}, cred={}: {}", userId, credentialName, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Variant-aware token lookup for a concrete user credential id selected by
     * a workflow node.
     */
    public Optional<AccessTokenResult> getAccessTokenInfoById(String userId, Long credentialId) {
        try {
            String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
                    .path("/api/internal/credentials/access-token/by-id")
                    .queryParam("userId", userId)
                    .queryParam("credentialId", credentialId)
                    .toUriString();
            ResponseEntity<AccessTokenResult> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(buildHeaders(userId)),
                    AccessTokenResult.class);
            AccessTokenResult result = resp.getBody();
            if (result != null && result.isFound()) {
                return Optional.of(result);
            }
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Failed to get access token by id={} for user={}: {}", credentialId, userId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Opaque value reserved for state-version lookups that could not reach
     * auth-service. Keeps the consumer's cache key stable during an auth
     * outage (fail-open: cached responses keep serving, exactly like before
     * the state-version existed).
     */
    public static final String STATE_VERSION_UNAVAILABLE = "na";

    /**
     * Opaque credential-state version for (user, active workspace). Changes
     * whenever a credential mutation could change resolution (connect, delete,
     * set-as-default, edit, token refresh). Compare for equality / embed in a
     * cache key - never parse.
     *
     * <p>Fail-open: any transport error returns {@link #STATE_VERSION_UNAVAILABLE}
     * so the caller's cache keeps functioning while auth-service is degraded
     * (a stale-credential window during an outage is the pre-existing behavior;
     * an outage must not disable response caching entirely).
     */
    public String getCredentialStateVersion(String userId) {
        if (userId == null || userId.isBlank()) {
            return STATE_VERSION_UNAVAILABLE;
        }
        try {
            String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
                    .path("/api/internal/credentials/state-version")
                    .queryParam("userId", userId)
                    .toUriString();
            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(buildHeaders(userId)),
                    new ParameterizedTypeReference<>() {});
            Object version = resp.getBody() != null ? resp.getBody().get("version") : null;
            return version instanceof String s && !s.isBlank() ? s : STATE_VERSION_UNAVAILABLE;
        } catch (Exception e) {
            log.warn("Failed to get credential state-version for user={}: {}", userId, e.getMessage());
            return STATE_VERSION_UNAVAILABLE;
        }
    }

    /**
     * Force OAuth2 refresh and return new access token.
     */
    public Optional<String> forceRefreshAndGetToken(String userId, String credentialName) {
        try {
            String url = baseUrl + "/api/internal/credentials/force-refresh-token";
            Map<String, String> body = Map.of("userId", userId, "credentialName", credentialName);
            ResponseEntity<AccessTokenResult> resp = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(body, buildHeaders(userId)),
                    AccessTokenResult.class);
            AccessTokenResult result = resp.getBody();
            if (result != null && result.isFound()) {
                return Optional.ofNullable(result.getAccessToken());
            }
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Failed to force-refresh token for user={}, cred={}: {}", userId, credentialName, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Force OAuth2 refresh for a concrete user credential id.
     */
    public Optional<String> forceRefreshAndGetTokenById(String userId, Long credentialId) {
        try {
            String url = baseUrl + "/api/internal/credentials/force-refresh-token/by-id";
            Map<String, Object> body = Map.of("userId", userId, "credentialId", credentialId);
            ResponseEntity<AccessTokenResult> resp = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(body, buildHeaders(userId)),
                    AccessTokenResult.class);
            AccessTokenResult result = resp.getBody();
            if (result != null && result.isFound()) {
                return Optional.ofNullable(result.getAccessToken());
            }
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Failed to force-refresh token by id={} for user={}: {}", credentialId, userId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Try OAuth2 refresh.
     */
    public Optional<String> refreshAccessToken(String userId, String credentialName) {
        try {
            String url = baseUrl + "/api/internal/credentials/refresh-token";
            Map<String, String> body = Map.of("userId", userId, "credentialName", credentialName);
            ResponseEntity<AccessTokenResult> resp = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(body, buildHeaders(userId)),
                    AccessTokenResult.class);
            AccessTokenResult result = resp.getBody();
            if (result != null && result.isFound()) {
                return Optional.ofNullable(result.getAccessToken());
            }
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Failed to refresh token for user={}, cred={}: {}", userId, credentialName, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Get all decrypted credential fields as a map.
     */
    public Map<String, String> getCredentialDataMap(String userId, String credentialName) {
        try {
            String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
                    .path("/api/internal/credentials/data-map")
                    .queryParam("userId", userId)
                    .queryParam("name", credentialName)
                    .toUriString();
            ResponseEntity<CredentialDataMapDto> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(buildHeaders(userId)),
                    CredentialDataMapDto.class);
            CredentialDataMapDto result = resp.getBody();
            return result != null && result.getData() != null ? result.getData() : Map.of();
        } catch (Exception e) {
            log.warn("Failed to get credential data map for user={}, cred={}: {}", userId, credentialName, e.getMessage());
            return Map.of();
        }
    }

    /**
     * Get all decrypted credential fields for a concrete user credential id.
     */
    public Map<String, String> getCredentialDataMapById(String userId, Long credentialId) {
        try {
            String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
                    .path("/api/internal/credentials/data-map/by-id")
                    .queryParam("userId", userId)
                    .queryParam("credentialId", credentialId)
                    .toUriString();
            ResponseEntity<CredentialDataMapDto> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(buildHeaders(userId)),
                    CredentialDataMapDto.class);
            CredentialDataMapDto result = resp.getBody();
            return result != null && result.getData() != null ? result.getData() : Map.of();
        } catch (Exception e) {
            log.warn("Failed to get credential data map by id={} for user={}: {}", credentialId, userId, e.getMessage());
            return Map.of();
        }
    }

    /**
     * V166: returns the credential's auth type and granted OAuth scopes used by
     * {@code HttpExecutionService.preflightScopeCheck} in catalog-service.
     *
     * <p>Returns {@link Optional#empty()} on 404 (no credential by that name) OR on any
     * HTTP / network error - fail-open so a transient auth-service hiccup doesn't block
     * tool execution. The caller handles {@code Optional.empty()} as "skip preflight";
     * if a real scope mismatch existed, the upstream provider will return 403 and the
     * existing error path takes over.
     *
     * <p>The {@code scopes} field of the response is null when the credential is not
     * OAuth2 (api_key, bearer_token, etc.). Callers must treat null scopes as "scope
     * concept does not apply, no preflight needed."
     */
    public Optional<CredentialScopesDto> getCredentialScopes(String userId, String credentialName) {
        try {
            String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
                    .path("/api/internal/credentials/scopes")
                    .queryParam("userId", userId)
                    .queryParam("name", credentialName)
                    .toUriString();
            ResponseEntity<CredentialScopesDto> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(buildHeaders(userId)),
                    CredentialScopesDto.class);
            return Optional.ofNullable(resp.getBody());
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Failed to fetch credential scopes for user={}, cred={}: {} (failing open)",
                    userId, credentialName, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Scope metadata for a concrete user credential id selected by a workflow.
     */
    public Optional<CredentialScopesDto> getCredentialScopesById(String userId, Long credentialId) {
        try {
            String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
                    .path("/api/internal/credentials/scopes/by-id")
                    .queryParam("userId", userId)
                    .queryParam("credentialId", credentialId)
                    .toUriString();
            ResponseEntity<CredentialScopesDto> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(buildHeaders(userId)),
                    CredentialScopesDto.class);
            return Optional.ofNullable(resp.getBody());
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Failed to fetch credential scopes by id={} for user={}: {} (failing open)",
                    credentialId, userId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Take every usable credential of an integration OUT OF SERVICE, across tenants, without
     * deleting it. Used when an API's auth type changes during a catalog re-import.
     *
     * <p>This replaced a delete. An automated caller may stop a credential being used; it may not
     * destroy it, because the owner usually cannot get the secret back (a revoked OAuth refresh
     * token, an API key shown once at creation).
     *
     * @return the number of credentials moved to needs_reauth, or -1 on failure
     */
    public int markCredentialsNeedReauthByIntegration(String integrationName) {
        try {
            String url = baseUrl + "/api/internal/credentials/by-integration/" + integrationName
                    + "/needs-reauth";
            ResponseEntity<Map> resp = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(buildHeaders("SYSTEM")), Map.class);
            Map<String, Object> body = resp.getBody();
            if (body != null && body.containsKey("marked")) {
                return ((Number) body.get("marked")).intValue();
            }
            return 0;
        } catch (Exception e) {
            log.warn("Failed to mark credentials needing re-auth for integration {}: {}",
                    integrationName, e.getMessage());
            return -1;
        }
    }

    // ========== Platform Credentials ==========

    /**
     * List all platform credentials (status info only, no sensitive data).
     */
    @SuppressWarnings("unchecked")
    public List<PlatformCredentialStatusDto> listPlatformCredentials() {
        try {
            String url = baseUrl + "/api/internal/credentials/platform";
            ResponseEntity<List<PlatformCredentialStatusDto>> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(buildHeaders("PLATFORM")),
                    new ParameterizedTypeReference<>() {});
            return resp.getBody() != null ? resp.getBody() : List.of();
        } catch (Exception e) {
            log.warn("Failed to list platform credentials: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Get a specific platform credential by name.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getPlatformCredentialByName(String name) {
        try {
            String url = baseUrl + "/api/internal/credentials/platform/" + name;
            ResponseEntity<Map> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(buildHeaders("PLATFORM")), Map.class);
            return resp.getBody() != null ? resp.getBody() : Map.of();
        } catch (Exception e) {
            log.warn("Failed to get platform credential {}: {}", name, e.getMessage());
            return Map.of();
        }
    }

    /**
     * Check whether a platform credential is usable for the given integration -
     * i.e. it exists, is enabled, and has a stored secret (api_key / client_secret
     * / password). Mirrors {@code PlatformCredentialsController.publicInfo}'s
     * {@code available} field.
     *
     * <p>Fails open on any transport error (returns {@code true}) to avoid false
     * "missing credential" warnings when auth-service is temporarily unreachable.
     */
    @SuppressWarnings("unchecked")
    public boolean platformCredentialAvailable(String integrationName) {
        if (integrationName == null || integrationName.isBlank()) {
            return false;
        }
        try {
            String url = baseUrl + "/api/platform-credentials/" + integrationName + "/public-info";
            ResponseEntity<Map> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(buildHeaders("PLATFORM")), Map.class);
            Map<String, Object> body = resp.getBody();
            if (body == null) {
                log.warn("Platform credential public-info for '{}' returned empty body; treating as available (fail-open)", integrationName);
                return true;
            }
            Object available = body.get("available");
            if (available == null) {
                log.warn("Platform credential public-info for '{}' lacks 'available' field; treating as available (fail-open)", integrationName);
                return true;
            }
            return !(available instanceof Boolean) || (Boolean) available;
        } catch (Exception e) {
            log.warn("Failed to check platform credential availability for {}: {}", integrationName, e.getMessage());
            return true;
        }
    }

    /**
     * Create/update a platform credential.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> savePlatformCredential(SavePlatformCredentialRequest request) {
        try {
            String url = baseUrl + "/api/internal/credentials/platform";
            ResponseEntity<Map> resp = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(request, buildHeaders("PLATFORM")), Map.class);
            return resp.getBody() != null ? resp.getBody() : Map.of();
        } catch (Exception e) {
            log.warn("Failed to save platform credential: {}", e.getMessage());
            return Map.of("error", e.getMessage());
        }
    }

    /**
     * Create/update a tenant-scoped platform credential.
     * Used for custom APIs where the user provides their own OAuth/API key config.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> saveTenantPlatformCredential(SavePlatformCredentialRequest request, String tenantId) {
        try {
            String url = baseUrl + "/api/internal/credentials/platform-tenant";
            ResponseEntity<Map> resp = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(request, buildHeaders(tenantId)), Map.class);
            return resp.getBody() != null ? resp.getBody() : Map.of();
        } catch (Exception e) {
            log.warn("Failed to save tenant platform credential: {}", e.getMessage());
            return Map.of("error", e.getMessage());
        }
    }

    /**
     * Delete a tenant-scoped platform credential.
     */
    public boolean deleteTenantPlatformCredential(String integrationName, String tenantId) {
        try {
            String url = baseUrl + "/api/internal/credentials/platform-tenant/" + integrationName;
            restTemplate.exchange(url, HttpMethod.DELETE, new HttpEntity<>(buildHeaders(tenantId)), Void.class);
            return true;
        } catch (Exception e) {
            log.warn("Failed to delete tenant platform credential {}: {}", integrationName, e.getMessage());
            return false;
        }
    }

    /**
     * Delete a platform credential.
     */
    public boolean deletePlatformCredential(String name) {
        try {
            String url = baseUrl + "/api/internal/credentials/platform/" + name;
            restTemplate.exchange(url, HttpMethod.DELETE, new HttpEntity<>(buildHeaders("PLATFORM")), Void.class);
            return true;
        } catch (Exception e) {
            log.warn("Failed to delete platform credential {}: {}", name, e.getMessage());
            return false;
        }
    }

    /**
     * Get platform credential for an integration, tenant-aware.
     * Tenant-scoped credential takes priority over platform-wide.
     */
    public Optional<String> getPlatformCredentialForIntegration(String integrationName, String tenantId) {
        try {
            UriComponentsBuilder uriBuilder = UriComponentsBuilder
                    .fromHttpUrl(baseUrl + "/api/internal/credentials/platform-integration/" + integrationName);
            if (tenantId != null && !tenantId.isBlank()) {
                uriBuilder.queryParam("tenantId", tenantId);
            }
            String url = uriBuilder.toUriString();
            ResponseEntity<AccessTokenResult> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(buildHeaders("PLATFORM")),
                    AccessTokenResult.class);
            AccessTokenResult result = resp.getBody();
            if (result != null && result.isFound()) {
                return Optional.ofNullable(result.getAccessToken());
            }
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Failed to get platform credential for integration {}: {}", integrationName, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Get platform credential for an integration (platform-wide only).
     */
    public Optional<String> getPlatformCredentialForIntegration(String integrationName) {
        return getPlatformCredentialForIntegration(integrationName, null);
    }

    // ========== Credential Lookup ==========

    /**
     * Get a credential by its ID.
     */
    public Optional<CredentialSummaryDto> getCredentialById(String userId, Long credentialId) {
        try {
            String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
                    .path("/api/internal/credentials/{id}")
                    .queryParam("userId", userId)
                    .buildAndExpand(credentialId)
                    .toUriString();
            ResponseEntity<CredentialSummaryDto> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(buildHeaders(userId)),
                    CredentialSummaryDto.class);
            return Optional.ofNullable(resp.getBody());
        } catch (Exception e) {
            log.warn("Failed to get credential by id={} for user={}: {}", credentialId, userId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Get the default credential for an integration.
     */
    public Optional<CredentialSummaryDto> getDefaultCredential(String userId, String integration) {
        try {
            String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
                    .path("/api/internal/credentials/default")
                    .queryParam("userId", userId)
                    .queryParam("integration", integration)
                    .toUriString();
            ResponseEntity<CredentialSummaryDto> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(buildHeaders(userId)),
                    CredentialSummaryDto.class);
            return Optional.ofNullable(resp.getBody());
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            // 404 = the user simply has no default credential for this integration (e.g. an
            // LLM provider they never configured). This is normal - callers fall back to the
            // platform credential. DEBUG so it does not pollute WARN metrics. A genuine
            // transport failure (below) still WARNs. Audit 2026-06-14.
            log.debug("No default credential for user={}, integration={} (404)", userId, integration);
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Failed to get default credential for user={}, integration={}: {}", userId, integration, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * The IDENTITIES of a user's credentials: id, name, integration, status.
     *
     * <p>Deliberately NOT {@link #getAllCredentials}, which answers with whole
     * records, decrypted secrets included. A caller that only needs to work out
     * WHICH credential was meant must never receive the material of the ones it
     * is about to reject.
     *
     * <p>Empty on any failure, like every other lookup here. Callers that must
     * not proceed without an answer have to treat empty as "unknown" themselves.
     */
    public List<CredentialIdentityDto> getCredentialIdentities(String userId) {
        return tryGetCredentialIdentities(userId).orElseGet(List::of);
    }

    /**
     * The same listing, but able to say that it could not look.
     *
     * <p>{@link #getCredentialIdentities} answers an empty list for a failure, which is
     * the right default for a caller that is MATCHING a credential: nothing matches, and
     * the call is refused. It is the wrong answer for a caller that is DESCRIBING what
     * the account holds, because "the credential service is unreachable" and "you have
     * no accounts" are then the same answer, and only one of them is safe to tell a
     * person. So the distinction is offered here rather than changed there.
     *
     * @return the identities, or empty when the listing could not be read
     */
    public Optional<List<CredentialIdentityDto>> tryGetCredentialIdentities(String userId) {

        try {
            String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
                    .path("/api/internal/credentials/identities")
                    .queryParam("userId", userId)
                    .toUriString();
            ResponseEntity<List<CredentialIdentityDto>> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(buildHeaders(userId)),
                    new ParameterizedTypeReference<>() {});
            return Optional.of(resp.getBody() != null ? resp.getBody() : List.of());
        } catch (Exception e) {
            log.warn("Failed to list credential identities for user={}: {}", userId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Get all credentials for a user.
     */
    public List<CredentialSummaryDto> getAllCredentials(String userId) {
        try {
            String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
                    .path("/api/internal/credentials/all")
                    .queryParam("userId", userId)
                    .toUriString();
            ResponseEntity<List<CredentialSummaryDto>> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(buildHeaders(userId)),
                    new ParameterizedTypeReference<>() {});
            return resp.getBody() != null ? resp.getBody() : List.of();
        } catch (Exception e) {
            log.warn("Failed to get all credentials for user={}: {}", userId, e.getMessage());
            return List.of();
        }
    }

    // ========== Platform Credential Markup ==========

    /**
     * Pin a workflow run to a specific pricing version. Called once per
     * platform-sourced credential at run-init so mid-run rate changes don't
     * leak into the execution. Idempotent on {@code (runId, credentialId)}.
     */
    public Optional<RunPricingPinDto> saveRunPricingPin(String runId, Long userId,
                                                         Long platformCredentialId,
                                                         Long pricingVersionId) {
        try {
            String url = baseUrl + "/api/internal/credentials/run-pricing-pin";
            Map<String, Object> body = Map.of(
                    "runId", runId,
                    "userId", userId,
                    "platformCredentialId", platformCredentialId,
                    "pricingVersionId", pricingVersionId);
            ResponseEntity<RunPricingPinDto> resp = restTemplate.exchange(
                    url, HttpMethod.POST,
                    new HttpEntity<>(body, buildHeaders(String.valueOf(userId))),
                    RunPricingPinDto.class);
            return Optional.ofNullable(resp.getBody());
        } catch (Exception e) {
            log.warn("Failed to save run pricing pin run={}, cred={}, version={}: {}",
                    runId, platformCredentialId, pricingVersionId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Cancel every live pin attached to a run. Invoked by the orchestrator's
     * run-terminal chokepoint so stragglers don't keep billing markup.
     * Idempotent - a second call returns 0.
     *
     * @return pins cancelled, or -1 on transport failure
     */
    public int cancelRunPricingPins(String runId) {
        try {
            String url = baseUrl + "/api/internal/credentials/run-pricing-pin/" + runId;
            ResponseEntity<Map> resp = restTemplate.exchange(
                    url, HttpMethod.DELETE,
                    new HttpEntity<>(buildHeaders("SYSTEM")), Map.class);
            Map<String, Object> body = resp.getBody();
            if (body != null && body.containsKey("cancelled")) {
                return ((Number) body.get("cancelled")).intValue();
            }
            return 0;
        } catch (Exception e) {
            log.warn("Failed to cancel run pricing pins for run={}: {}", runId, e.getMessage());
            return -1;
        }
    }

    /**
     * V148+ pricing-version bootstrap. Idempotent: returns the existing v1+
     * if any, else publishes v1. Called by catalog-service's
     * {@code ApiMigrationImporter} once api_tools UUIDs are seeded.
     *
     * <p>Returns the published version id on success, empty on transport
     * failure (caller should log + retry next deploy).
     */
    public Optional<Long> bootstrapPricingVersion(Long credentialId,
                                                    java.math.BigDecimal defaultMarkup,
                                                    Map<String, java.math.BigDecimal> perToolOverrides,
                                                    String createdBy) {
        if (credentialId == null) return Optional.empty();
        String url = baseUrl + "/api/internal/credentials/pricing-versions/bootstrap";
        HttpHeaders headers = buildHeaders("SYSTEM");
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("credentialId", credentialId);
        body.put("defaultMarkupCredits", defaultMarkup);
        body.put("perToolOverrides", perToolOverrides);
        body.put("createdBy", createdBy);
        try {
            ResponseEntity<Map> resp = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
            Map<?, ?> rb = resp.getBody();
            if (rb == null || rb.get("pricingVersionId") == null) return Optional.empty();
            Object id = rb.get("pricingVersionId");
            if (id instanceof Number n) return Optional.of(n.longValue());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("bootstrapPricingVersion failed for credential {}: {}", credentialId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * V430 PUBLISHER half: the prices the platform owner has published for the
     * given generation endpoints, keyed by integration name so they can be
     * carried in the signed API-catalog bundle and re-attached on the far side.
     *
     * <p>Both arguments are required to be non-empty and neither is defaulted:
     * an empty request means "publish nothing", never "publish every price the
     * owner has". Returns an empty list on any transport failure - a bundle
     * built without prices is a bundle that carries no price update, which is
     * the pre-V430 behaviour and is safe; a bundle built with a WRONG price list
     * would be signed and distributed.
     */
    public List<BundleGenerationPriceDto> fetchPublishedGenerationPrices(
            java.util.Collection<String> integrationNames,
            java.util.Collection<String> apiToolIds) {
        if (integrationNames == null || integrationNames.isEmpty()
                || apiToolIds == null || apiToolIds.isEmpty()) {
            return List.of();
        }
        String url = baseUrl + "/api/internal/credentials/pricing-versions/published-prices";
        HttpHeaders headers = buildHeaders("SYSTEM");
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("integrationNames", List.copyOf(integrationNames));
        body.put("apiToolIds", List.copyOf(apiToolIds));
        try {
            ResponseEntity<PublishedPricesResponse> resp = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(body, headers),
                    PublishedPricesResponse.class);
            PublishedPricesResponse rb = resp.getBody();
            return rb == null || rb.prices() == null ? List.of() : rb.prices();
        } catch (Exception e) {
            log.warn("fetchPublishedGenerationPrices failed ({} integrations, {} endpoints): {}",
                    integrationNames.size(), apiToolIds.size(), e.getMessage());
            return List.of();
        }
    }

    /** Wire shape of {@code /pricing-versions/published-prices}. */
    public record PublishedPricesResponse(List<BundleGenerationPriceDto> prices) {
    }

    /**
     * V430 CONSUMER half: apply the prices a VERIFIED API-catalog bundle
     * carried. Only ever called after the bundle's Ed25519 signature has been
     * checked against the pinned key - this client cannot tell an authentic
     * price from an invented one and does not try.
     *
     * <p>Returns the auth-side summary, or empty on transport failure. Failing
     * to price is never a reason to fail the catalog apply that carried it: the
     * endpoints still land, and the next sync tick re-offers the same prices
     * (auth publishes nothing when nothing changed).
     */
    public Optional<Map<String, Object>> applyCatalogBundlePrices(
            Long bundleVersion, List<BundleGenerationPriceDto> prices) {
        return applyCatalogBundlePrices(bundleVersion, prices, null);
    }

    /**
     * The same apply, for the other producer of bundle-owned prices: the
     * boot-time generation seed shipped inside the image, which an install that
     * has never synced a bundle depends on.
     *
     * @param origin recorded as the author of the published pricing version.
     *               Null means the signed bundle, which is the default label.
     *               This does NOT change the row's {@code source}: a seeded
     *               price is {@code bundle}-owned exactly like a carried one,
     *               because both are prices this install did not decide. It only
     *               keeps the pricing history from naming a bundle version that
     *               never existed.
     */
    public Optional<Map<String, Object>> applyCatalogBundlePrices(
            Long bundleVersion, List<BundleGenerationPriceDto> prices, String origin) {
        if (prices == null || prices.isEmpty()) return Optional.empty();
        String url = baseUrl + "/api/internal/credentials/pricing-versions/apply-catalog-bundle";
        HttpHeaders headers = buildHeaders("SYSTEM");
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("bundleVersion", bundleVersion);
        body.put("prices", prices);
        if (origin != null && !origin.isBlank()) {
            body.put("origin", origin);
        }
        try {
            ResponseEntity<Map> resp = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> rb = resp.getBody();
            return Optional.ofNullable(rb);
        } catch (Exception e) {
            log.warn("applyCatalogBundlePrices failed for bundle v{} ({} price(s)): {}",
                    bundleVersion, prices.size(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Offer the catalog's starting price of every generation model, so auth-service
     * publishes the ones a platform credential has NEVER priced and leaves every
     * other row alone. Idempotent: re-offering the same list publishes nothing.
     *
     * @return the per-call summary, or empty when auth-service could not be reached
     */
    public Optional<Map<String, Object>> addNeverPricedGenerationPrices(
            List<BundleGenerationPriceDto> prices, String origin) {
        if (prices == null || prices.isEmpty()) return Optional.empty();
        String url = baseUrl + "/api/internal/credentials/pricing-versions/add-never-priced";
        HttpHeaders headers = buildHeaders("SYSTEM");
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("prices", prices);
        if (origin != null && !origin.isBlank()) {
            body.put("origin", origin);
        }
        try {
            ResponseEntity<Map> resp = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> rb = resp.getBody();
            return Optional.ofNullable(rb);
        } catch (Exception e) {
            log.warn("addNeverPricedGenerationPrices failed ({} price(s)): {}", prices.size(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * V148+ helper: resolve a platform credential id (and provider_kind) by
     * its catalog-side integration name (e.g. {@code "llm_openai"}). Catalog
     * stores credentials by name; the markup subsystem keys on numeric id.
     *
     * <p>Returns empty when no platform credential is registered for the name
     * (a free user-key API with no platform fallback). Caller (catalog billing)
     * treats empty as "no platform credential involved → skip billing".
     */
    public Optional<PlatformCredentialLookupDto> findPlatformCredentialByName(String integrationName) {
        if (integrationName == null || integrationName.isBlank()) return Optional.empty();
        try {
            String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
                    .path("/api/internal/credentials/platform/by-name")
                    .queryParam("integrationName", integrationName)
                    .toUriString();
            ResponseEntity<PlatformCredentialLookupDto> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(buildHeaders("SYSTEM")),
                    PlatformCredentialLookupDto.class);
            PlatformCredentialLookupDto dto = resp.getBody();
            if (dto == null || !dto.isFound()) return Optional.empty();
            return Optional.of(dto);
        } catch (Exception e) {
            log.warn("findPlatformCredentialByName({}) failed: {}", integrationName, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * V148+ scope-aware unified lookup. Returns the per-call markup for a
     * (scope, credential, tool) tuple. Creates the pin lazily on first call;
     * touches {@code last_used_at} on cache hit (drives STREAM-pin TTL sweeper).
     *
     * <p>Returns empty when the credential has no published pricing version -
     * caller (catalog) interprets that as "fail-closed: refuse the call".
     *
     * <p>Replaces {@link #resolveRunMarkupRate} for new code; the legacy method
     * stays alive during the 90-day deprecation window for existing workflow
     * callers.
     */
    public Optional<ResolvedScopeMarkupDto> resolveScopeMarkupRate(String scopeKind, String scopeId,
                                                                     Long userId, Long platformCredentialId,
                                                                     UUID apiToolId) {
        return resolveScopeMarkupRate(scopeKind, scopeId, userId, platformCredentialId, apiToolId, null, null);
    }

    /**
     * V428 unit-pricing variant of {@link #resolveScopeMarkupRate(String, String, Long, Long, UUID)}.
     *
     * <p>An endpoint can back several generation models at different prices,
     * and a generation's cost usually scales on a dimension of the call
     * (seconds of video, characters of speech). Passing those two lets auth
     * resolve the exact amount instead of the caller re-deriving pricing rules
     * it should not own.
     *
     * <p>Both are optional. Omitting them resolves the tool-level flat price,
     * which is what every pre-V428 caller gets through the 5-arg overload.
     *
     * @param modelId  generation model being called, or null for the
     *                 tool-level price
     * @param quantity size of the call in PLATFORM units (seconds, assets,
     *                 characters), or null when the caller has no notion of a
     *                 size. Never pre-converted into the price's unit: what the
     *                 rate is charged per is a property of the published row,
     *                 which auth reads, so auth is what converts. Converting
     *                 here against a different unit is a silent mis-charge, not
     *                 a rounding difference.
     */
    public Optional<ResolvedScopeMarkupDto> resolveScopeMarkupRate(String scopeKind, String scopeId,
                                                                     Long userId, Long platformCredentialId,
                                                                     UUID apiToolId,
                                                                     String modelId,
                                                                     java.math.BigDecimal quantity) {
        return resolveScopeMarkupRate(scopeKind, scopeId, userId, platformCredentialId, apiToolId,
                modelId, quantity, null);
    }

    /**
     * Same lookup, carrying what the CHOICES in this call do to the published
     * rate.
     *
     * <p>A published price scales on one dimension: the size of the call.
     * Everything else the caller picked is invisible to it, which is correct
     * while those choices are free and a silent loss when they are not (a
     * render at 1080p, a reference image the provider charges to read). The
     * factor is derived by the caller that holds the parameters, from the
     * model's own declared modifiers, and applied here to the amount rather
     * than to the quantity: a ten second clip stays ten seconds whatever it
     * costs.
     *
     * @param priceMultiplier factor to apply to the resolved amount, or null
     *                        for a call at the published rate. Null and 1 mean
     *                        the same thing and both leave the amount exactly
     *                        as every pre-modifier caller received it.
     */
    public Optional<ResolvedScopeMarkupDto> resolveScopeMarkupRate(String scopeKind, String scopeId,
                                                                     Long userId, Long platformCredentialId,
                                                                     UUID apiToolId,
                                                                     String modelId,
                                                                     java.math.BigDecimal quantity,
                                                                     java.math.BigDecimal priceMultiplier) {
        try {
            UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseUrl)
                    .path("/api/internal/credentials/markup/scope-rate")
                    .queryParam("scopeKind", scopeKind)
                    .queryParam("scopeId", scopeId)
                    .queryParam("userId", userId)
                    .queryParam("platformCredentialId", platformCredentialId)
                    .queryParam("apiToolId", apiToolId.toString());
            if (modelId != null && !modelId.isBlank()) {
                builder.queryParam("modelId", modelId);
            }
            if (quantity != null) {
                builder.queryParam("quantity", quantity.toPlainString());
            }
            // Sent only when it changes something, so an ordinary lookup is
            // byte-for-byte the request it was before modifiers existed and an
            // absent parameter keeps meaning "at the published rate".
            if (priceMultiplier != null
                    && priceMultiplier.compareTo(java.math.BigDecimal.ONE) != 0) {
                builder.queryParam("priceMultiplier", priceMultiplier.toPlainString());
            }
            String url = builder.toUriString();
            ResponseEntity<ResolvedScopeMarkupDto> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(buildHeaders("SYSTEM")),
                    ResolvedScopeMarkupDto.class);
            ResolvedScopeMarkupDto dto = resp.getBody();
            if (dto == null || !dto.isFound()) {
                return Optional.empty();
            }
            return Optional.of(dto);
        } catch (Exception e) {
            log.warn("Failed to resolve scope markup rate scope={}/{}, cred={}, tool={}: {}",
                    scopeKind, scopeId, platformCredentialId, apiToolId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * V148+ existence check for the delinquent in-flight bypass branch in
     * {@code CreditService.tryReserveMarkup} (the 10-arg reservation entry
     * point). Lookup-only.
     */
    public boolean existsScopePin(String scopeKind, String scopeId, Long platformCredentialId) {
        try {
            String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
                    .path("/api/internal/credentials/markup/scope-pin-exists")
                    .queryParam("scopeKind", scopeKind)
                    .queryParam("scopeId", scopeId)
                    .queryParam("platformCredentialId", platformCredentialId)
                    .toUriString();
            ResponseEntity<Map> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(buildHeaders("SYSTEM")), Map.class);
            Map<?, ?> body = resp.getBody();
            return body != null && Boolean.TRUE.equals(body.get("exists"));
        } catch (Exception e) {
            log.warn("existsScopePin failed scope={}/{}, cred={}: {} - fail-open false",
                    scopeKind, scopeId, platformCredentialId, e.getMessage());
            return false;
        }
    }

    /**
     * V148+ scope-aware cancel. Workflow run-terminal calls this with
     * {@code (RUN, runId)}; chat end-of-conversation hooks call with
     * {@code (STREAM, streamId)}.
     */
    public int cancelScopePin(String scopeKind, String scopeId) {
        try {
            String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
                    .path("/api/internal/credentials/markup/scope-pin")
                    .queryParam("scopeKind", scopeKind)
                    .queryParam("scopeId", scopeId)
                    .toUriString();
            ResponseEntity<Map> resp = restTemplate.exchange(
                    url, HttpMethod.DELETE, new HttpEntity<>(buildHeaders("SYSTEM")), Map.class);
            Map<?, ?> body = resp.getBody();
            if (body != null && body.get("cancelled") instanceof Number n) {
                return n.intValue();
            }
            return 0;
        } catch (Exception e) {
            log.warn("cancelScopePin failed scope={}/{}: {}", scopeKind, scopeId, e.getMessage());
            return -1;
        }
    }

    /**
     * Composed hot-path lookup: given a run + credential + tool, return the
     * frozen per-call markup via whichever pricing version the run was pinned
     * to at init. Empty when no live pin covers the credential for this run,
     * which is also the signal to the caller that markup does not apply for
     * this tool call.
     *
     * @deprecated use {@link #resolveScopeMarkupRate} with {@code scopeKind="RUN"}.
     *     Kept during 90-day deprecation window for legacy callers.
     */
    @Deprecated
    public Optional<FrozenMarkupDto> resolveRunMarkupRate(String runId,
                                                           Long platformCredentialId,
                                                           UUID apiToolId) {
        try {
            String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
                    .path("/api/internal/credentials/markup/run-rate")
                    .queryParam("runId", runId)
                    .queryParam("platformCredentialId", platformCredentialId)
                    .queryParam("apiToolId", apiToolId.toString())
                    .toUriString();
            ResponseEntity<FrozenMarkupDto> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(buildHeaders("SYSTEM")),
                    FrozenMarkupDto.class);
            FrozenMarkupDto dto = resp.getBody();
            if (dto == null || !dto.isFound()) {
                return Optional.empty();
            }
            return Optional.of(dto);
        } catch (Exception e) {
            log.warn("Failed to resolve run markup rate run={}, cred={}, tool={}: {}",
                    runId, platformCredentialId, apiToolId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Resolve the frozen per-call markup for a pinned pricing version + tool.
     * Hot path: called on every platform-sourced MCP debit. Empty when the
     * pricing version id is unknown (callers must skip markup billing).
     */
    public Optional<FrozenMarkupDto> resolveFrozenMarkup(Long pricingVersionId, UUID apiToolId) {
        return resolveFrozenMarkup(pricingVersionId, apiToolId, null, null);
    }

    /**
     * V428 unit-pricing variant, mirroring
     * {@link #resolveScopeMarkupRate(String, String, Long, Long, UUID, String, java.math.BigDecimal)}.
     *
     * <p>The two-argument form cannot price a generation: an endpoint can back
     * several models at different rates, and a per-unit rate resolved without a
     * size is the price of ONE unit, which is why every relayed generation from
     * a cloud-linked self-hosted install was refused. Naming the model and the
     * measured size lets the published row do the conversion, in the one place
     * that owns it.
     *
     * @param modelId  generation model, or null for the endpoint-wide row
     * @param quantity PLATFORM measurement (seconds, assets, characters), or
     *                 null when the call could not be measured
     */
    public Optional<FrozenMarkupDto> resolveFrozenMarkup(Long pricingVersionId, UUID apiToolId,
                                                          String modelId, java.math.BigDecimal quantity) {
        return resolveFrozenMarkup(pricingVersionId, apiToolId, modelId, quantity, null);
    }

    /**
     * Same resolution, carrying what the CHOICES in this call do to the rate.
     *
     * <p>The relay reads that factor back out of the provider-shaped body it
     * was sent, from the model's own declared modifiers, for the same reason it
     * reads the size there: a self-hosted install that stated its own factor
     * could state it as 1 and pay the base rate for a call the platform owner
     * is charged extra for.
     *
     * @param priceMultiplier factor for this call, or null for one at the
     *                        published rate
     */
    public Optional<FrozenMarkupDto> resolveFrozenMarkup(Long pricingVersionId, UUID apiToolId,
                                                          String modelId, java.math.BigDecimal quantity,
                                                          java.math.BigDecimal priceMultiplier) {
        try {
            UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseUrl)
                    .path("/api/internal/credentials/resolve-markup")
                    .queryParam("pricingVersionId", pricingVersionId)
                    .queryParam("apiToolId", apiToolId.toString());
            if (modelId != null && !modelId.isBlank()) {
                builder.queryParam("modelId", modelId);
            }
            if (quantity != null) {
                builder.queryParam("quantity", quantity.toPlainString());
            }
            if (priceMultiplier != null
                    && priceMultiplier.compareTo(java.math.BigDecimal.ONE) != 0) {
                builder.queryParam("priceMultiplier", priceMultiplier.toPlainString());
            }
            String url = builder.toUriString();
            ResponseEntity<FrozenMarkupDto> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(buildHeaders("SYSTEM")),
                    FrozenMarkupDto.class);
            FrozenMarkupDto dto = resp.getBody();
            if (dto == null || !dto.isFound()) {
                return Optional.empty();
            }
            return Optional.of(dto);
        } catch (Exception e) {
            log.warn("Failed to resolve markup version={}, tool={}: {}",
                    pricingVersionId, apiToolId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Latest published pricing version for a credential. Used at run-init to
     * decide which version to pin. Empty when the credential has never had a
     * pricing version published (markup off).
     */
    public Optional<PricingVersionDto> getLatestPricingVersion(Long credentialId) {
        try {
            String url = baseUrl + "/api/internal/credentials/pricing/" + credentialId + "/latest";
            ResponseEntity<PricingVersionDto> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(buildHeaders("SYSTEM")),
                    PricingVersionDto.class);
            PricingVersionDto dto = resp.getBody();
            if (dto == null || !dto.isFound()) {
                return Optional.empty();
            }
            return Optional.of(dto);
        } catch (Exception e) {
            log.warn("Failed to get latest pricing version for credential {}: {}",
                    credentialId, e.getMessage());
            return Optional.empty();
        }
    }

    // ========== Workflow Variables ==========

    /**
     * Decrypted, typed workflow-variable bundle for a run owner's scope - feeds
     * {@code {{$vars.*}}} template resolution. The orchestrator fetches this ONCE
     * per workflow run. The organization id is passed EXPLICITLY: runs execute
     * asynchronously with no inbound request context, so the usual
     * OrgContextHeaderForwarder propagation has nothing to forward.
     *
     * <p>Best-effort: an auth-service failure returns an empty map, degrading the
     * run to "no variables defined" (unresolved {@code $vars.x} references) rather
     * than failing it outright.
     */
    public Map<String, Object> getWorkflowVariablesBundle(String tenantId, String organizationId) {
        if (tenantId == null || tenantId.isBlank()) {
            return Map.of();
        }
        try {
            String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
                    .path("/api/internal/variables/bundle")
                    .queryParam("tenantId", tenantId)
                    .toUriString();
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url, HttpMethod.GET,
                    new HttpEntity<>(buildHeadersWithExplicitOrg(tenantId, organizationId)),
                    new ParameterizedTypeReference<>() {});
            Map<String, Object> body = response.getBody();
            if (body == null) {
                return Map.of();
            }
            Object variables = body.get("variables");
            if (variables instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) map;
                return typed;
            }
            return Map.of();
        } catch (Exception e) {
            log.warn("Failed to fetch workflow variables bundle for tenant={} org={}: {}",
                    tenantId, organizationId, e.getMessage());
            return Map.of();
        }
    }

    /**
     * Like {@link #buildHeaders} but with the workspace set explicitly instead of
     * forwarded from the (absent) inbound request - required on async run paths.
     * The gateway signature must be computed AFTER the org header is set (the org
     * id is part of the signed payload).
     */
    private HttpHeaders buildHeadersWithExplicitOrg(String tenantId, String organizationId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (tenantId != null) {
            headers.set("X-User-ID", tenantId);
        }
        if (organizationId != null && !organizationId.isBlank()) {
            headers.set("X-Organization-ID", organizationId);
        }
        applyGatewaySignature(headers);
        return headers;
    }

    private HttpHeaders buildHeaders(String tenantId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (tenantId != null) {
            headers.set("X-User-ID", tenantId);
        }
        // PR16 - forward X-Organization-ID / X-Organization-Role from the
        // inbound request to keep workspace context across cross-service hops.
        OrgContextHeaderForwarder.forward(headers);
        applyGatewaySignature(headers);
        return headers;
    }

    /**
     * Sign the identity the request ACTUALLY carries, never the one the caller
     * had in hand.
     *
     * <p>{@code GatewayAuthenticationFilter} recomputes the HMAC over the
     * {@code X-User-ID} / {@code X-Organization-ID} headers it reads off the
     * wire, so those headers are the only safe input. The {@code userId}
     * argument is not equivalent: {@link #buildHeaders} runs
     * {@code OrgContextHeaderForwarder.forward} first, which copies
     * {@code X-User-ID} off the inbound servlet request whenever the caller
     * passed none. Signing the argument would then sign an empty user while
     * shipping an inherited one, and the filter answers 401 "Invalid gateway
     * secret" - the exact shape that silently killed markup commits from
     * {@code CreditConsumptionClient}. The org id was already read from the
     * headers; the user id now is too. The method takes NO identity argument,
     * so there is nothing left to pass that could disagree with what is sent.
     */
    /**
     * Stamps the three gateway headers through the shared signer. The whole ritual lives there,
     * including the blank-secret no-op and the header NAMES: a private copy of those beside a
     * delegating signer is the drift this consolidation removes.
     */
    private void applyGatewaySignature(HttpHeaders headers) {
        com.apimarketplace.common.web.InternalGatewaySigner.stamp(
                headers, INTERNAL_PROVIDER_ID, gatewaySecretKey);
    }


}
