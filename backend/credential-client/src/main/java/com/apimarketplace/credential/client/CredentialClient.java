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
    private static final String HMAC_ALGO = "HmacSHA256";
    private static final String SIGNATURE_PREFIX = "gw_";
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
     * Delete all user credentials for a given integration name (across all tenants).
     * Used when an API's auth type changes during catalog reimport.
     *
     * @return the number of deleted credentials, or -1 on failure
     */
    public int deleteCredentialsByIntegration(String integrationName) {
        try {
            String url = baseUrl + "/api/internal/credentials/by-integration/" + integrationName;
            ResponseEntity<Map> resp = restTemplate.exchange(
                    url, HttpMethod.DELETE, new HttpEntity<>(buildHeaders("SYSTEM")), Map.class);
            Map<String, Object> body = resp.getBody();
            if (body != null && body.containsKey("deleted")) {
                return ((Number) body.get("deleted")).intValue();
            }
            return 0;
        } catch (Exception e) {
            log.warn("Failed to delete credentials for integration {}: {}", integrationName, e.getMessage());
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
        try {
            String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
                    .path("/api/internal/credentials/markup/scope-rate")
                    .queryParam("scopeKind", scopeKind)
                    .queryParam("scopeId", scopeId)
                    .queryParam("userId", userId)
                    .queryParam("platformCredentialId", platformCredentialId)
                    .queryParam("apiToolId", apiToolId.toString())
                    .toUriString();
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
        try {
            String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
                    .path("/api/internal/credentials/resolve-markup")
                    .queryParam("pricingVersionId", pricingVersionId)
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

    private HttpHeaders buildHeaders(String tenantId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (tenantId != null) {
            headers.set("X-User-ID", tenantId);
        }
        // PR16 - forward X-Organization-ID / X-Organization-Role from the
        // inbound request to keep workspace context across cross-service hops.
        OrgContextHeaderForwarder.forward(headers);
        applyGatewaySignature(headers, tenantId);
        return headers;
    }

    private void applyGatewaySignature(HttpHeaders headers, String userId) {
        if (gatewaySecretKey == null || gatewaySecretKey.isBlank()) {
            return;
        }
        String timestamp = String.valueOf(System.currentTimeMillis());
        headers.set("X-Provider-ID", INTERNAL_PROVIDER_ID);
        headers.set("X-Gateway-Timestamp", timestamp);
        headers.set("X-Gateway-Secret", computeGatewaySignature(
                INTERNAL_PROVIDER_ID,
                userId,
                headers.getFirst("X-Organization-ID"),
                timestamp));
    }

    private String computeGatewaySignature(String providerId, String userId, String organizationId, String timestamp) {
        String safeUser = userId != null ? userId : "";
        String safeOrg = organizationId != null ? organizationId : "";
        String data = providerId + "|" + safeUser + "|" + safeOrg + "|" + timestamp;
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(gatewaySecretKey.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return SIGNATURE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

}
