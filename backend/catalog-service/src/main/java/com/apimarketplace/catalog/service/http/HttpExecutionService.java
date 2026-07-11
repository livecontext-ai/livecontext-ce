package com.apimarketplace.catalog.service.http;

import com.apimarketplace.catalog.domain.ApiEntity;
import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.domain.ApiToolParameterEntity;
import com.apimarketplace.catalog.repository.ApiToolParameterRepository;
import com.apimarketplace.common.security.CredentialEncryptionService;
import com.apimarketplace.common.web.UrlSafetyValidator;
import com.apimarketplace.catalog.service.UserCredentialService;
import com.apimarketplace.catalog.service.exception.ApiAuthenticationException;
import com.apimarketplace.catalog.service.exception.InsufficientScopesException;
import com.apimarketplace.catalog.service.http.bodypath.BodyPathExecutor;
import com.apimarketplace.catalog.service.http.bodypath.BodyPathParser;
import com.apimarketplace.credential.client.dto.AccessTokenResult;
import com.apimarketplace.credential.client.dto.CredentialScopesDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Service responsible for executing HTTP calls to external APIs.
 * Handles URL building, parameter processing, headers, credentials, and body transformation.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HttpExecutionService {

    private final ApiToolParameterRepository apiToolParameterRepository;
    private final UserCredentialService userCredentialService;
    private final CredentialEncryptionService encryptionService;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final RestTemplate restTemplate;

    /**
     * Cache of resource-scoped sub-tokens (e.g. Facebook Page tokens) resolved via the generic
     * {@code sub_resource_token} credential-resolution rule. Keyed by {@code user|credential|matchValue},
     * short TTL. See {@link #resolveSubResourceToken}.
     */
    private final Map<String, CachedSubToken> subResourceTokenCache = new java.util.concurrent.ConcurrentHashMap<>();
    /** Sub-token cache TTL (ms). Package-private + non-final so tests can force expiry. */
    long subTokenTtlMs = 10 * 60 * 1000L;
    /** Hard cap on the sub-token cache to bound growth (users × credentials × sub-resources). Settable for tests. */
    int subTokenCacheMax = 5_000;
    private record CachedSubToken(String token, long expiresAtMs) {}

    // Typed-execution refactor (Phases 8/9/10) - strategies for binary, multipart, async.
    // Optional so tests using the legacy 6-arg constructor still compile.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.apimarketplace.catalog.service.execution.BinaryResponseHandler binaryResponseHandler;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.apimarketplace.catalog.service.execution.MultipartBodyEncoder multipartBodyEncoder;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.apimarketplace.catalog.service.execution.MultipartRelatedBodyEncoder multipartRelatedBodyEncoder;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.apimarketplace.catalog.service.execution.FormUrlencodedBodyEncoder formUrlencodedBodyEncoder;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.apimarketplace.catalog.service.execution.RawBinaryBodyEncoder rawBinaryBodyEncoder;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.apimarketplace.catalog.service.execution.GraphqlBodyEncoder graphqlBodyEncoder;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.apimarketplace.catalog.service.execution.AwsSigV4Signer awsSigV4Signer;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.apimarketplace.catalog.service.execution.AsyncPollExecutor asyncPollExecutor;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.apimarketplace.catalog.service.execution.StreamingResponseHandler streamingResponseHandler;

    // Platform tenant ID for shared credentials
    private static final String PLATFORM_TENANT_ID = "PLATFORM";

    /**
     * Credential-data field names whose value IS the resolved "primary" token. A field-aware
     * injection on one of these reuses the already-resolved {@code credentialValue} (which honors
     * OAuth refresh) rather than re-reading a possibly-stale value from the raw data map.
     */
    private static final java.util.Set<String> PRIMARY_TOKEN_FIELDS =
            java.util.Set.of("access_token", "api_key", "api_token", "bearer_token", "oauth_access_token");

    /**
     * Record for credential injection configuration.
     */
    /**
     * Catalog credential-injection metadata read from {@code tool_credentials.metadata}.
     *
     * @param type   {@code header} or {@code query}
     * @param key    header name (e.g. {@code Authorization}) or query param name
     * @param field  credential JSON field to read (e.g. {@code api_token}, {@code api_key})
     * @param prefix value transport prefix from the migration JSON's
     *               {@code apiKeyConfig.prefix} (e.g. {@code "Bearer "}). May be null when
     *               the API does not declare one (raw header injection like {@code X-API-Key}).
     *               When the resolved credential value already starts with this prefix
     *               (case-insensitive), {@link #prepareHeadersWithCredentials} strips it
     *               before re-applying - guards against users pasting {@code "Bearer xxx"}
     *               into a credential field that already auto-prefixes.
     */
    public record CredentialInjection(String type, String key, String field, String prefix,
                                      List<CredentialInjection> fields) {
        public CredentialInjection(String type, String key, String field, String prefix) {
            this(type, key, field, prefix, List.of());
        }
        public CredentialInjection(String type, String key, String field) {
            this(type, key, field, null, List.of());
        }
    }

    /**
     * Record for parameter metadata (parameterType and dataType).
     * inlineBody=true means the param's converted value IS the entire JSON body
     * (no field-name wrapping). At most one body param per endpoint may set it.
     * encoding="strict" forces full percent-encoding of a path parameter value
     * (':' → %3A, '/' → %2F, …) for endpoints whose {placeholder} is an opaque
     * identifier such as a full URL or URN (Search Console siteUrl/feedpath,
     * Sendbird channel_url, LinkedIn URNs). Null/absent → the conservative
     * default that keeps '/' ':' '@' literal for multi-segment values (S3 keys,
     * GitLab file_path, GCS object). See processPathParameters().
     */
    public record ParameterMetadata(String parameterType, String dataType, String bodyPath, boolean inlineBody, String encoding, String defaultValue) {
        public ParameterMetadata(String parameterType, String dataType, String bodyPath) {
            this(parameterType, dataType, bodyPath, false, null, null);
        }
        public ParameterMetadata(String parameterType, String dataType, String bodyPath, boolean inlineBody) {
            this(parameterType, dataType, bodyPath, inlineBody, null, null);
        }
    }

    /**
     * Builds a standardized httpStatus object for all responses.
     * @param code HTTP status code
     * @param error Error message (null if no error)
     * @return Map with "code" and "error" keys
     */
    public Map<String, Object> buildHttpStatus(int code, String error) {
        Map<String, Object> httpStatus = new HashMap<>();
        httpStatus.put("code", code);
        httpStatus.put("error", error);
        return httpStatus;
    }

    /**
     * Extracts error message from response body (JSON) or falls back to default message.
     * Tries to parse common error formats: {"error": {"message": "..."}}, {"message": "..."}, {"error": "..."}
     * @param responseBody The raw response body
     * @param defaultMessage Fallback message if parsing fails
     * @return Extracted error message
     */
    public String extractErrorMessage(String responseBody, String defaultMessage) {
        if (responseBody == null || responseBody.isBlank()) {
            return defaultMessage;
        }

        try {
            JsonNode root = objectMapper.readTree(responseBody);

            // Try {"error": {"message": "..."}} format (Google APIs)
            JsonNode errorNode = root.path("error");
            if (!errorNode.isMissingNode()) {
                JsonNode messageNode = errorNode.path("message");
                if (!messageNode.isMissingNode()) {
                    return messageNode.asText();
                }
                // Try {"error": "..."} format
                if (errorNode.isTextual()) {
                    return errorNode.asText();
                }
            }

            // Try {"message": "..."} format
            JsonNode messageNode = root.path("message");
            if (!messageNode.isMissingNode()) {
                return messageNode.asText();
            }

            // Try {"detail": "..."} format
            JsonNode detailNode = root.path("detail");
            if (!detailNode.isMissingNode()) {
                return detailNode.asText();
            }

            // Return the raw body if it's short enough
            if (responseBody.length() < 500) {
                return responseBody;
            }

        } catch (Exception e) {
            log.debug("[HttpExecutionService.extractErrorMessage] Failed to parse error body: {}", e.getMessage());
        }

        return defaultMessage;
    }

    /**
     * V166: preflight check that the user's bound credential has the OAuth scopes
     * required by the tool. Runs BEFORE credential resolution and billing - when this
     * throws, no provider call is dispatched and no credit is deducted.
     *
     * <p>The method is a no-op (returns silently) in any of these cases - keeping the
     * 95% of the catalog that doesn't declare {@code requiredScopes} unchanged:
     * <ul>
     *   <li>tool's {@code requiredScopes} is null or empty</li>
     *   <li>{@code credentialName} is null or blank (platform-only path; covered downstream)</li>
     *   <li>auth-service is unreachable or returns 404 (fail-open - existing 403 path
     *       handles real scope mismatches)</li>
     *   <li>credential is not OAuth2 (api_key, bearer_token, basic_auth, custom)</li>
     * </ul>
     *
     * <p>When the credential IS OAuth2 and the granted scope set does not cover the
     * required scopes, throws {@link InsufficientScopesException}. The caller
     * ({@code ApiService.executeApiTool}) catches this exception explicitly and
     * converts it into a structured error map.
     */
    public void preflightScopeCheck(String userId, String credentialName, ApiEntity api, ApiToolEntity tool) {
        List<String> required = tool.getRequiredScopes();
        if (required == null || required.isEmpty()) {
            return;
        }
        if ("platform".equals(CredentialModeContext.getExplicitSource())) {
            return;
        }
        if (credentialName == null || credentialName.isBlank()) {
            return;
        }
        Optional<CredentialScopesDto> scopesOpt = getCredentialScopesForUserSelection(userId, credentialName);
        if (scopesOpt.isEmpty()) {
            return;
        }
        CredentialScopesDto resp = scopesOpt.get();
        if (resp.getType() == null || !"oauth2".equalsIgnoreCase(resp.getType())) {
            return;
        }
        Set<String> granted = resp.getScopes() != null
                ? new HashSet<>(resp.getScopes())
                : Collections.emptySet();
        Set<String> missing = new HashSet<>(required);
        missing.removeAll(granted);
        if (!missing.isEmpty()) {
            throw new InsufficientScopesException(
                    tool.getToolNameId(),
                    api.getId(),
                    credentialName,
                    api.getPlatformCredentialName(),
                    missing);
        }
    }

    private Optional<CredentialScopesDto> getCredentialScopesForUserSelection(String userId, String credentialName) {
        if (userCredentialService == null) {
            return Optional.empty();
        }
        Long selectedCredentialId = selectedUserCredentialId();
        if (selectedCredentialId != null) {
            Optional<CredentialScopesDto> byId = userCredentialService.getCredentialScopesById(userId, selectedCredentialId);
            if (byId.isPresent()) {
                return byId;
            }
            // Pinned credential deleted → fall through to the user's default
            // credential for this integration (take pinned, else default).
        }
        return userCredentialService.getCredentialScopes(userId, credentialName);
    }

    private Optional<AccessTokenResult> getAccessTokenInfoForUserSelection(String userId, String credentialName) {
        if (userCredentialService == null) {
            return Optional.empty();
        }
        Long selectedCredentialId = selectedUserCredentialId();
        if (selectedCredentialId != null) {
            Optional<AccessTokenResult> byId = userCredentialService.getAccessTokenInfoById(userId, selectedCredentialId);
            if (byId.isPresent()) {
                return byId;
            }
            // Pinned credential deleted → fall back to the integration default.
        }
        return userCredentialService.getAccessTokenInfo(userId, credentialName);
    }

    private Map<String, String> getCredentialDataMapForUserSelection(String userId, String credentialName) {
        if (userCredentialService == null) {
            return Map.of();
        }
        Long selectedCredentialId = selectedUserCredentialId();
        if (selectedCredentialId != null) {
            Map<String, String> byId = userCredentialService.getCredentialDataMapById(userId, selectedCredentialId);
            if (!byId.isEmpty()) {
                return byId;
            }
            // Pinned credential deleted → fall back to the integration default.
        }
        return credentialName != null ? userCredentialService.getCredentialDataMap(userId, credentialName) : Map.of();
    }

    private Long selectedUserCredentialId() {
        return "user".equals(CredentialModeContext.getExplicitSource())
                ? CredentialModeContext.getSelectedCredentialId()
                : null;
    }

    /**
     * Executes the HTTP call to the external API (without credentials - legacy)
     */
    public Map<String, Object> executeHttpCall(ApiEntity api, ApiToolEntity tool, JsonNode parameters) {
        return executeHttpCall(api, tool, parameters, null);
    }

    /**
     * Executes the HTTP call to the external API
     * @param allowedParamNames Set of allowed parameter names (can be null to fetch from DB)
     */
    public Map<String, Object> executeHttpCall(ApiEntity api, ApiToolEntity tool, JsonNode parameters, Set<String> allowedParamNames) {
        try {
            log.info("[HttpExecutionService.executeHttpCall] Tool: {}, Parameters before filtering: {}", tool.getId(), parameters);

            // Filter parameters to keep only those defined in api_tool_parameters
            JsonNode filteredParameters = filterParametersByToolDefinition(tool, parameters, allowedParamNames);
            log.info("[HttpExecutionService.executeHttpCall] Tool: {}, Parameters after filtering: {}", tool.getId(), filteredParameters);

            String url = buildFullUrl(api, tool);
            log.info("[HttpExecutionService.executeHttpCall] Tool: {}, Base URL: {}, Endpoint: {}, Full URL before path processing: {}",
                    tool.getId(), api.getBaseUrl(), tool.getEndpoint(), url);

            url = processPathParameters(url, tool, filteredParameters);
            log.info("[HttpExecutionService.executeHttpCall] Tool: {}, URL after path parameters: {}", tool.getId(), url);

            // Dynamic-URL endpoints first (placeholder reject + host allow-list, no DNS toward
            // non-allowed hosts), then the generic SSRF validation for every URL.
            enforceDynamicUrlConstraints(tool, url);
            // SSRF protection: validate after path parameter substitution so {placeholders} don't break URI parsing
            UrlSafetyValidator.validateUrl(url);

            url = processQueryParameters(url, tool, filteredParameters);
            log.info("[HttpExecutionService.executeHttpCall] Tool: {}, Final URL: {}", tool.getId(), url);

            HttpHeaders headers = prepareHeaders(api, tool);
            applyHeaderParameters(headers, tool, filteredParameters);
            Object body = prepareRequestBody(tool, filteredParameters);
            log.info("[HttpExecutionService.executeHttpCall] Tool: {}, Request body: {}", tool.getId(), body);

            // Check if URL still contains unexpanded variables
            if (url.contains("{") && url.contains("}")) {
                Pattern pattern = Pattern.compile("\\{([^}]+)\\}");
                Matcher matcher = pattern.matcher(url);
                List<String> remainingVars = new ArrayList<>();
                while (matcher.find()) {
                    remainingVars.add(matcher.group(1));
                }
                log.error("[HttpExecutionService.executeHttpCall] Tool: {}, URL still contains unexpanded variables: {}",
                        tool.getId(), remainingVars);
            }

            HttpEntity<Object> request = new HttpEntity<>(body, headers);

            log.info("[HttpExecutionService.executeHttpCall] Tool: {}, About to call REST with URL: {}, Method: {}",
                    tool.getId(), url, tool.getMethod());

            ResponseEntity<Object> response = restTemplate.exchange(
                    url,
                    HttpMethod.valueOf(tool.getMethod()),
                    request,
                    Object.class
            );

            // Create mutable Map to allow adding fields later
            int statusCode = response.getStatusCode().value();
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("status", statusCode);
            result.put("httpStatus", buildHttpStatus(statusCode, null));
            result.put("data", response.getBody() != null ? response.getBody() : Map.of());
            result.put("headers", response.getHeaders().toSingleValueMap());

            return result;

        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            // HTTP error with status code - return error info instead of throwing
            int statusCode = e.getStatusCode().value();
            String errorBody = e.getResponseBodyAsString();
            String errorMessage = extractErrorMessage(errorBody, e.getMessage());

            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("status", statusCode);
            result.put("httpStatus", buildHttpStatus(statusCode, errorMessage));
            result.put("data", Map.of());
            result.put("error", errorMessage);
            result.put("errorBody", errorBody);

            log.error("[HttpExecutionService.executeHttpCall] HTTP error: status={}, error={}", statusCode, errorMessage);
            return result;

        } catch (Exception e) {
            // Non-HTTP error (network, etc.)
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("status", 0);
            result.put("httpStatus", buildHttpStatus(0, e.getMessage()));
            result.put("data", Map.of());
            result.put("error", e.getMessage());

            log.error("[HttpExecutionService.executeHttpCall] Error: {}", e.getMessage());
            return result;
        }
    }

    /**
     * Executes the HTTP call with user credentials (OAuth support)
     * @param userId User ID for retrieving OAuth credentials
     * @param credentialName Name of the credential required by the tool
     */
    public Map<String, Object> executeHttpCallWithCredentials(ApiEntity api, ApiToolEntity tool, JsonNode parameters,
                                                               Set<String> allowedParamNames, String userId, String credentialName) {
        // Captured early and injected into success result maps so downstream
        // billing dispatchers can distinguish BYOK (user key) from platform-cost
        // passthrough. Lower-cased ("user" / "platform") for ToolExecutionResponse.metadata.credentialSource.
        String resolvedCredentialSource = null;
        try {
            log.info("[HttpExecutionService.executeHttpCallWithCredentials] Tool: {}, userId: {}, credentialName: {}",
                    tool.getId(), userId, credentialName);

            // Filter parameters
            JsonNode filteredParameters = filterParametersByToolDefinition(tool, parameters, allowedParamNames);

            // Build URL
            String url = buildFullUrl(api, tool);
            url = processPathParameters(url, tool, filteredParameters);

            // Dynamic-URL endpoints first (placeholder reject + host allow-list, no DNS toward
            // non-allowed hosts), then the generic SSRF validation for every URL.
            enforceDynamicUrlConstraints(tool, url);
            // SSRF protection: validate after path parameter substitution so {placeholders} don't break URI parsing
            UrlSafetyValidator.validateUrl(url);

            url = processQueryParameters(url, tool, filteredParameters);

            // Get credential injection metadata - V103 variant-aware so multi-variant
            // APIs (OAuth2 + PAT, …) route through the injection row matching the
            // variant of the credential the user actually configured.
            String variant = resolveCredentialVariant(userId, credentialName, api);
            CredentialInjection injection = getCredentialInjection(tool.getId(), variant);

            // Get the credential value AND its source (user vs platform) - source is
            // injected into the success result map for billing dispatch.
            Optional<CredentialResolution> credResolution = tryGetCredentialResolution(userId, credentialName, api);
            Optional<String> credentialValue = credResolution.map(CredentialResolution::value);
            if (credResolution.isPresent()) {
                resolvedCredentialSource = credResolution.get().source().name().toLowerCase();
            }
            // Field-aware: a custom credential whose secret lives under injection.field() (not a
            // primary token) resolves to an empty primary value above - read that field instead.
            credentialValue = applyFieldAwareFallback(injection, credentialValue, userId, credentialName);

            // Generic, migration-driven sub-resource token resolution (e.g. Facebook Page token):
            // swaps the base token for a resource-scoped sub-token when the tool declares the rule
            // AND the call carries the trigger param. Strict no-op for every other API/tool.
            credentialValue = resolveSubResourceToken(api, tool, filteredParameters, credentialValue, userId, credentialName);

            // Inject credential based on metadata configuration
            if (injection != null && credentialValue.isPresent()) {
                String value = credentialValue.get();

                if ("query".equalsIgnoreCase(injection.type())) {
                    // Add credential as query parameter
                    url += (url.contains("?") ? "&" : "?") + injection.key() + "=" +
                           URLEncoder.encode(value, StandardCharsets.UTF_8);
                    log.info("[HttpExecutionService.executeHttpCallWithCredentials] Injected credential as query parameter: {}", injection.key());
                } else if ("header".equalsIgnoreCase(injection.type())) {
                    // Will be handled in prepareHeadersWithCredentials (pass injection metadata)
                    log.debug("[HttpExecutionService.executeHttpCallWithCredentials] Credential will be injected as header: {}", injection.key());
                }
            }

            // Replace URL template variables ({token}, {domain}, etc.) with credential data
            if (url.contains("{") && url.contains("}")) {
                url = replaceUrlTemplateVariables(url, userId, credentialName, credentialValue.orElse(null));
            }

            log.info("[HttpExecutionService.executeHttpCallWithCredentials] Final URL: {}", url);

            // Prepare headers with OAuth credentials if available
            HttpHeaders headers = prepareHeadersWithCredentials(api, tool, userId, credentialName, injection, credentialValue);
            // Multi-field custom auth (≥2 header fields in customConfig: Algolia, Plaid, Datadog, …)
            // - apply every header from THIS row's customConfig; no-op for single/non-custom auth.
            applyCustomFieldHeaderInjections(headers, injection, userId, credentialName, credentialValue);
            applyHeaderParameters(headers, tool, filteredParameters);
            Object body = prepareRequestBody(tool, filteredParameters);
            log.info("[HttpExecutionService.executeHttpCallWithCredentials] Request body: {}", body);

            // AWS SigV4: sign *.amazonaws.com requests from access_key_id/secret_access_key.
            // Mirrors the typed path (executeTyped); without it the legacy sync/JSON path sent an
            // unsigned request (a bogus Bearer/custom header from prepareHeadersWithCredentials) →
            // AWS 403. The signer signs only host + x-amz-* and sets Authorization, so it overrides
            // any stray header and is a no-op for non-AWS hosts.
            maybeSignAws(tool, url, headers, body, userId, credentialName);

            // Check for unexpanded variables
            if (url.contains("{") && url.contains("}")) {
                Pattern pattern = Pattern.compile("\\{([^}]+)\\}");
                Matcher matcher = pattern.matcher(url);
                List<String> remainingVars = new ArrayList<>();
                while (matcher.find()) {
                    remainingVars.add(matcher.group(1));
                }
                log.error("[HttpExecutionService.executeHttpCallWithCredentials] URL still contains unexpanded variables: {}", remainingVars);
            }

            HttpEntity<Object> request = new HttpEntity<>(body, headers);

            log.info("[HttpExecutionService.executeHttpCallWithCredentials] Calling {} {}", tool.getMethod(), url);

            try {
                // Use URI.create to prevent Spring from trying to expand {variables} as URI templates
                ResponseEntity<Object> response = restTemplate.exchange(
                    java.net.URI.create(url),
                    HttpMethod.valueOf(tool.getMethod()),
                    request,
                    Object.class
                );

                int statusCode = response.getStatusCode().value();
                Map<String, Object> result = new HashMap<>();
                result.put("success", true);
                result.put("status", statusCode);
                result.put("httpStatus", buildHttpStatus(statusCode, null));
                result.put("data", response.getBody() != null ? response.getBody() : Map.of());
                result.put("headers", response.getHeaders().toSingleValueMap());
                if (resolvedCredentialSource != null) result.put("credentialSource", resolvedCredentialSource);

                return result;

            } catch (org.springframework.web.client.HttpClientErrorException.Unauthorized e) {
                // 401 Unauthorized - try to refresh token and retry once
                log.warn("[HttpExecutionService.executeHttpCallWithCredentials] Got 401 Unauthorized, attempting token refresh for userId={}", userId);

                Optional<String> newToken = Optional.empty();
                boolean fellBackToPlatform = false;
                String explicitSource = com.apimarketplace.catalog.service.http.CredentialModeContext.getExplicitSource();

                // Refresh strategy mirrors tryGetCredentialResolution exactly:
                //   • explicitSource='user' (workflow toggle) → user-only, no
                //     platform fallback ever.
                //   • explicitSource='platform' → platform-only, skip the user
                //     refresh entirely (the initial lookup never touched the
                //     user pool, so refreshing user creds we never sent is a
                //     wasted RPC).
                //   • explicitSource=null (agentic path) → user first, platform
                //     fallback when a platform credential is configured.
                boolean tryUser = !"platform".equals(explicitSource);
                if (tryUser && userId != null && userCredentialService != null) {
                    Long selectedCredentialId = selectedUserCredentialId();
                    if (selectedCredentialId != null) {
                        newToken = userCredentialService.forceRefreshAndGetTokenById(userId, selectedCredentialId);
                    }
                    if (newToken.isEmpty() && credentialName != null) {
                        // Pinned credential gone (or had no token) → refresh the
                        // integration default. Mirrors the resolution fallback so the
                        // 401-retry path picks the same credential.
                        newToken = userCredentialService.forceRefreshAndGetToken(userId, credentialName);
                    }
                }

                if (newToken.isEmpty() && userCredentialService != null) {
                    boolean platformFallbackAllowed = !"user".equals(explicitSource);
                    String platformCredName = api.getPlatformCredentialName();
                    if (platformFallbackAllowed && platformCredName != null && !platformCredName.isBlank()) {
                        log.info("[HttpExecutionService.executeHttpCallWithCredentials] {} attempting PLATFORM credential refresh: {}",
                                "platform".equals(explicitSource) ? "Workflow toggle=platform -" : "User credential refresh failed, trying PLATFORM fallback for",
                                platformCredName);
                        newToken = userCredentialService.forceRefreshAndGetToken(PLATFORM_TENANT_ID, platformCredName);
                        if (newToken.isPresent()) {
                            fellBackToPlatform = true;
                        }
                    } else if (platformCredName != null) {
                        log.info("[HttpExecutionService.executeHttpCallWithCredentials] User credential refresh failed; PLATFORM fallback NOT allowed by explicit source='{}' (workflow toggle: user-only)", explicitSource);
                    }
                }

                if (newToken.isPresent()) {
                    log.info("[HttpExecutionService.executeHttpCallWithCredentials] Token refreshed, retrying request");

                    // Stamp the source as PLATFORM if the retry uses the
                    // platform pool - otherwise downstream billing dispatchers
                    // will see credentialSource='user' and skip the platform
                    // debit for an actually-platform-funded call.
                    if (fellBackToPlatform) {
                        resolvedCredentialSource = CredentialSource.PLATFORM.name().toLowerCase();
                    }

                    // Rebuild headers with new token
                    HttpHeaders retryHeaders = new HttpHeaders();
                    retryHeaders.add("Content-Type", "application/json");
                    retryHeaders.add("Accept", "application/json");
                    retryHeaders.add("Authorization", "Bearer " + newToken.get());
                    // Re-apply tool header params (e.g. Google Ads developer-token / login-customer-id).
                    // This retry rebuilds headers from scratch, so without this the OAuth refresh-retry
                    // (401 → refresh → retry - exactly the Google Ads case) would drop them and fail.
                    applyHeaderParameters(retryHeaders, tool, filteredParameters);

                    HttpEntity<Object> retryRequest = new HttpEntity<>(body, retryHeaders);

                    ResponseEntity<Object> response = restTemplate.exchange(
                        url,
                        HttpMethod.valueOf(tool.getMethod()),
                        retryRequest,
                        Object.class
                    );

                    int statusCode = response.getStatusCode().value();
                    Map<String, Object> result = new HashMap<>();
                    result.put("success", true);
                    result.put("status", statusCode);
                    result.put("httpStatus", buildHttpStatus(statusCode, null));
                    result.put("data", response.getBody() != null ? response.getBody() : Map.of());
                    result.put("headers", response.getHeaders().toSingleValueMap());
                    if (resolvedCredentialSource != null) result.put("credentialSource", resolvedCredentialSource);

                    return result;
                }

                // Refresh failed or not possible - return error result
                String serviceName = api.getIconSlug() != null ? api.getIconSlug() : api.getApiName();
                String errorMessage = "Authentication expired for " + serviceName + ". Please reconnect your account.";

                Map<String, Object> result = new HashMap<>();
                result.put("success", false);
                result.put("status", 401);
                result.put("httpStatus", buildHttpStatus(401, errorMessage));
                result.put("data", Map.of());
                result.put("error", errorMessage);

                log.error("[HttpExecutionService.executeHttpCallWithCredentials] Auth error: {}", errorMessage);
                return result;

            } catch (org.springframework.web.client.HttpClientErrorException.Forbidden e) {
                // 403 Forbidden - surface provider-specific reason when available (e.g. Google:
                // "Request had insufficient authentication scopes"). Without it, users see only a
                // generic message and cannot tell whether to reconnect, re-consent, or enable the API.
                String serviceName = api.getIconSlug() != null ? api.getIconSlug() : api.getApiName();
                String errorBody = e.getResponseBodyAsString();
                String providerReason = extractErrorMessage(errorBody, null);
                String errorMessage = providerReason != null
                        ? "Access forbidden for " + serviceName + ": " + providerReason
                        : "Access forbidden for " + serviceName + ". Check your permissions.";

                Map<String, Object> result = new HashMap<>();
                result.put("success", false);
                result.put("status", 403);
                result.put("httpStatus", buildHttpStatus(403, errorMessage));
                result.put("data", Map.of());
                result.put("error", errorMessage);
                result.put("errorBody", errorBody);

                log.error("[HttpExecutionService.executeHttpCallWithCredentials] Forbidden error: {} body={}", errorMessage, errorBody);
                return result;

            } catch (org.springframework.web.client.HttpStatusCodeException e) {
                // Other HTTP errors (400, 404, 500, etc.) - return error result
                int statusCode = e.getStatusCode().value();
                String errorBody = e.getResponseBodyAsString();
                String errorMessage = extractErrorMessage(errorBody, e.getMessage());

                Map<String, Object> result = new HashMap<>();
                result.put("success", false);
                result.put("status", statusCode);
                result.put("httpStatus", buildHttpStatus(statusCode, errorMessage));
                result.put("data", Map.of());
                result.put("error", errorMessage);
                result.put("errorBody", errorBody);

                log.error("[HttpExecutionService.executeHttpCallWithCredentials] HTTP error: status={}, error={}", statusCode, errorMessage);
                return result;
            }

        } catch (Exception e) {
            // Non-HTTP error (network, etc.)
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("status", 0);
            result.put("httpStatus", buildHttpStatus(0, e.getMessage()));
            result.put("data", Map.of());
            result.put("error", e.getMessage());

            log.error("[HttpExecutionService.executeHttpCallWithCredentials] Error: {}", e.getMessage());
            return result;
        }
    }

    /**
     * Legacy single-variant lookup. Prefer {@link #getCredentialInjection(UUID, String)}
     * so the returned injection matches the variant of the credential the user
     * actually configured.
     */
    public CredentialInjection getCredentialInjection(UUID toolId) {
        return getCredentialInjection(toolId, null);
    }

    /**
     * V103 variant-aware injection lookup. Returns the {@code catalog.tool_credentials}
     * row whose {@code metadata->>'variant'} matches {@code variant}. When no row
     * matches (or {@code variant} is null - legacy callers), falls back to any
     * required injection row so single-variant APIs keep working without the
     * caller having to know the variant up-front.
     *
     * @param toolId the API tool id
     * @param variant the auth variant identifier (e.g. "oauth2", "api_key") -
     *                typically {@link #resolveCredentialVariant} of the user's
     *                credential type; may be null
     */
    public CredentialInjection getCredentialInjection(UUID toolId, String variant) {
        try {
            List<Map<String, Object>> results = Collections.emptyList();
            if (variant != null && !variant.isBlank()) {
                String sql = """
                    SELECT tc.metadata
                    FROM catalog.tool_credentials tc
                    WHERE tc.api_tool_id = ?
                      AND tc.is_required = true
                      AND tc.metadata->>'variant' = ?
                    LIMIT 1
                    """;
                results = jdbcTemplate.queryForList(sql, toolId, variant);
            }
            if (results.isEmpty()) {
                // Fallback: single-variant APIs import with variant='primary' or the
                // declared auth_type - the old LIMIT 1 still finds their one row.
                String sql = """
                    SELECT tc.metadata
                    FROM catalog.tool_credentials tc
                    WHERE tc.api_tool_id = ? AND tc.is_required = true
                    LIMIT 1
                    """;
                results = jdbcTemplate.queryForList(sql, toolId);
            }
            if (results.isEmpty()) {
                return null;
            }
            return parseInjectionMetadata(results.get(0).get("metadata"));
        } catch (Exception e) {
            log.warn("[HttpExecutionService] Error getting credential injection for tool {} (variant={}): {}",
                    toolId, variant, e.getMessage());
            return null;
        }
    }

    /** Parse one {@code tool_credentials.metadata} blob into a {@link CredentialInjection} (null if none). */
    private CredentialInjection parseInjectionMetadata(Object metadataObj) {
        if (metadataObj == null) {
            return null;
        }
        try {
            JsonNode metadata = objectMapper.readTree(metadataObj.toString());
            JsonNode injection = metadata.path("injection");
            if (injection.isMissingNode()) {
                return null;
            }
            String type = injection.path("type").asText(null);
            String key = injection.path("key").asText(null);
            String field = metadata.path("field").asText("api_key");
            // Prefix: canonical injection.prefix, else fakeAuth.apiKeyConfig.prefix (older imports);
            // null when absent (downstream defaults to "Bearer " for Authorization headers).
            String prefix = injection.path("prefix").asText(null);
            if (prefix == null || prefix.isEmpty()) {
                prefix = metadata.path("fakeAuth").path("apiKeyConfig").path("prefix").asText(null);
                if (prefix != null && prefix.isEmpty()) prefix = null;
            }
            // Multi-field custom auth carries one entry PER field in fakeAuth.customConfig.fields[]
            // (Algolia X-Algolia-Application-Id + X-Algolia-API-Key, Plaid client-id + secret, …),
            // while the top-level injection above is only the FIRST field. Parse them all so the
            // runtime can apply every header - they all belong to this single row's ONE variant.
            List<CredentialInjection> fields = new ArrayList<>();
            JsonNode customFields = metadata.path("fakeAuth").path("customConfig").path("fields");
            if (customFields.isArray()) {
                for (JsonNode f : customFields) {
                    String fName = f.path("name").asText(null);
                    String fType = f.path("injectionType").asText(null);
                    String fKey = f.path("injectionKey").asText(fName);
                    if (fName != null && fType != null && fKey != null) {
                        fields.add(new CredentialInjection(fType, fKey, fName, null));
                    }
                }
            }
            return (type != null && key != null)
                    ? new CredentialInjection(type, key, field, prefix, fields)
                    : null;
        } catch (Exception e) {
            log.warn("[HttpExecutionService] parseInjectionMetadata failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Multi-field custom auth: apply EVERY header field declared in the resolved injection's
     * {@code customConfig.fields} - e.g. Algolia (X-Algolia-Application-Id + X-Algolia-API-Key),
     * Plaid (PLAID-CLIENT-ID + PLAID-SECRET), Datadog (DD-API-KEY + DD-APPLICATION-KEY). These all
     * come from ONE variant's single {@code tool_credentials} row (parsed alongside the top-level
     * injection, no extra query), so there is NO cross-variant leak. Gated to ≥2 header fields:
     * single-field customs and non-custom auth (oauth2/api_key/bearer/basic) carry no
     * {@code customConfig.fields}, so this is a no-op for them. A primary-token field reuses
     * {@code primaryValue} (OAuth-refresh-safe); any other field is read from the decrypted data map.
     */
    private void applyCustomFieldHeaderInjections(HttpHeaders headers, CredentialInjection injection,
                                                  String userId, String credentialName, Optional<String> primaryValue) {
        if (injection == null || injection.fields() == null) {
            return;
        }
        List<CredentialInjection> headerFields = injection.fields().stream()
                .filter(f -> "header".equalsIgnoreCase(f.type()) && f.key() != null)
                .toList();
        if (headerFields.size() < 2) {
            return;
        }
        Map<String, String> dataMap = null;
        for (CredentialInjection f : headerFields) {
            String value;
            if (f.field() != null && PRIMARY_TOKEN_FIELDS.contains(f.field()) && primaryValue.isPresent()) {
                value = primaryValue.get();
            } else {
                if (dataMap == null) {
                    dataMap = getCredentialDataMapForUserSelection(userId, credentialName);
                }
                value = f.field() != null ? dataMap.get(f.field()) : null;
            }
            if (value == null || value.isBlank()) {
                continue;
            }
            headers.set(f.key(), value.strip());
        }
    }

    /**
     * Field-aware single-value fallback. When the resolved primary credential value is empty but the
     * injection names a NON-primary field (a custom API whose single secret is keyed on e.g.
     * application_id / secret_key), read THAT field from the decrypted data map - otherwise a
     * custom-field credential resolves to an empty/Bearer header (the basic_auth class of bug). No-op
     * when the primary value is present (OAuth/api_key) or the field is a primary-token field, so it
     * costs nothing on the working paths and fires only for an otherwise-empty resolution.
     */
    private Optional<String> applyFieldAwareFallback(CredentialInjection injection, Optional<String> credentialValue,
                                                     String userId, String credentialName) {
        if (injection == null || credentialValue.isPresent() || injection.field() == null
                || PRIMARY_TOKEN_FIELDS.contains(injection.field())) {
            return credentialValue;
        }
        String fv = getCredentialDataMapForUserSelection(userId, credentialName).get(injection.field());
        return (fv != null && !fv.isBlank()) ? Optional.of(fv.strip()) : credentialValue;
    }

    /**
     * V103: resolve the variant identifier to filter {@code tool_credentials} by.
     *
     * <p>For {@code user_key}/{@code both} APIs we read the auth type off the
     * user's credential (OAuth2 → "oauth2", API_Key → "api_key", …). For
     * {@code platform_key} mode, variant is decided by admin configuration
     * (Phase 2c) - until then we return null and let the fallback LIMIT 1 pick
     * the sole enabled row.
     *
     * <p>Returns null when the user has no credential yet. Callers MUST treat
     * null as "no variant filter" so APIs with a single variant still resolve.
     */
    public String resolveCredentialVariant(String userId, String credentialName, ApiEntity api) {
        // Workflow toggle 'platform' (or callers that explicitly target the
        // platform pool) bypass the per-user variant lookup - there is no user
        // credential to project a variant from. Same semantic as the legacy
        // 'platform_key' stored mode.
        String explicitSource = com.apimarketplace.catalog.service.http.CredentialModeContext.getExplicitSource();
        if ("platform".equals(explicitSource)) {
            return null;
        }
        if (userId == null || userId.isBlank() || credentialName == null || credentialName.isBlank()) {
            return null;
        }
        return getAccessTokenInfoForUserSelection(userId, credentialName)
                .map(info -> info.getType())
                .map(HttpExecutionService::normalizeVariant)
                .orElse(null);
    }

    /**
     * Normalize a {@code CredentialType} enum name to the variant identifier used
     * in {@code tool_credentials.metadata->>'variant'} and {@code catalog.credentials.variant}.
     * Mapping mirrors {@code IconSlugNormalizer}-style lowercase+underscore:
     * "OAuth2" → "oauth2", "API_Key" → "api_key", "Basic_Auth" → "basic_auth",
     * "Webhook" → "webhook". Returns null when the input is blank.
     *
     * <p>Note: V103 valid variants are {@code oauth2 | api_key | basic_auth | bearer_token | custom}.
     * {@code CredentialType.Webhook} normalizes to {@code "webhook"} which is not a V103 variant,
     * so the variant-filtered query in {@link #getCredentialInjection(UUID, String)} will miss and
     * the unfiltered LIMIT 1 fallback takes over. Conversely, {@code bearer_token} and {@code custom}
     * have no {@code CredentialType} enum value - APIs using those variants exclusively will also
     * fall through. Both paths are acceptable when the API exposes a single variant.
     */
    static String normalizeVariant(String credentialType) {
        if (credentialType == null || credentialType.isBlank()) {
            return null;
        }
        return credentialType.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Filters parameters to keep only those defined in api_tool_parameters
     * @param allowedParamNames Set of allowed parameter names (can be null)
     */
    public JsonNode filterParametersByToolDefinition(ApiToolEntity tool, JsonNode parameters, Set<String> allowedParamNames) {
        if (parameters == null || !parameters.isArray()) {
            return parameters;
        }

        try {
            // If allowedParamNames is not provided, fetch from database
            if (allowedParamNames == null) {
                List<ApiToolParameterEntity> definedParameters = apiToolParameterRepository.findByApiToolId(tool.getId());
                allowedParamNames = definedParameters.stream()
                        .map(ApiToolParameterEntity::getName)
                        .collect(Collectors.toSet());
            }

            if (allowedParamNames.isEmpty()) {
                // A tool whose declared parameter set is empty takes NO input. Earlier this
                // branch forwarded every provided field as a safety net for tools whose param
                // metadata hadn't been imported yet - but that let orchestrator-injected
                // context leak into the request. A manual trigger feeds a no-param downstream
                // node the blob {"trigger": {...}}; for a GET, processQueryParameters() then
                // turned the undeclared "trigger" field into ?trigger=... which Google's API
                // rejected with 400 "Cannot bind query parameter. Field 'trigger' could not be
                // found". Sending zero params for a zero-declared-param tool is the correct
                // contract and stops the leak at the single filtering chokepoint (both query
                // and body builders consume the filtered result).
                //
                // Visibility: a genuine zero-param tool (list_sites, list_calendars-style) is
                // normally called with no provided fields → stay quiet. But if fields WERE
                // provided yet the tool declares none, we are dropping data - that is either
                // the trigger-blob leak (expected, now neutralized) OR a param-import gap
                // (a tool whose JSON declares params but whose rows failed to import, which
                // used to self-heal via forward-all). Warn in that case so an import gap is
                // visible in prod instead of silently sending an empty request.
                // parameters is guaranteed to be a non-null ArrayNode here (the !isArray()
                // guard at the top already returned), so size() is the count of provided fields.
                int providedCount = parameters.size();
                if (providedCount > 0) {
                    log.warn("Tool {} declares zero parameters but {} field(s) were provided - "
                            + "dropping them (trigger-context leak or a param-import gap)", tool.getId(), providedCount);
                }
                return objectMapper.createArrayNode();
            }

            // Filter parameters to keep only those defined
            List<JsonNode> filteredParams = new ArrayList<>();
            for (JsonNode param : parameters) {
                if (!param.fieldNames().hasNext()) continue; // skip empty {} from null values
                String paramName = param.fieldNames().next();
                if (allowedParamNames.contains(paramName)) {
                    filteredParams.add(param);
                } else {
                    log.debug("Filtering out parameter '{}' not defined in tool parameters", paramName);
                }
            }

            return objectMapper.valueToTree(filteredParams);

        } catch (Exception e) {
            // Fail closed, consistent with the zero-declared-param branch above: if we cannot
            // determine the allowed set (e.g. a transient param-metadata fetch error) we must
            // NOT forward arbitrary provided fields, or undeclared context (the {"trigger":...}
            // blob) would leak into the request exactly as in the original bug. Sending no
            // params surfaces a clean upstream failure instead of a corrupted request.
            log.warn("Error filtering parameters for tool {}; sending no params (fail-closed): {}",
                    tool.getId(), e.getMessage());
            return objectMapper.createArrayNode();
        }
    }

    /**
     * Prepare HTTP headers with credentials based on injection metadata.
     * Supports header injection with custom header names or OAuth Bearer tokens.
     * @param injection Credential injection metadata (can be null for legacy behavior)
     * @param credentialValue The credential value to inject (can be empty for legacy behavior)
     */
    public HttpHeaders prepareHeadersWithCredentials(ApiEntity api, ApiToolEntity tool, String userId, String credentialName,
                                                      CredentialInjection injection, Optional<String> credentialValue) {
        HttpHeaders headers = new HttpHeaders();

        // Default headers
        headers.add("Content-Type", "application/json");
        headers.add("Accept", "application/json");

        // Basic auth (2-field credentials like Twilio: username=Account SID, password=Auth Token).
        // The injection type is "basic_auth" → build "Authorization: Basic base64(username:password)"
        // from the full credential data map. Without this branch, basic_auth fell through to the
        // legacy Bearer fallback below, which sent "Authorization: Bearer <username>" (just the
        // Account SID) and NEVER transmitted the password → the provider rejected every call with 401.
        if (injection != null && "basic_auth".equalsIgnoreCase(injection.type())) {
            Map<String, String> dataMap = getCredentialDataMapForUserSelection(userId, credentialName);
            // Strip leading/trailing whitespace - a pasted trailing space or newline in the
            // Account SID / Auth Token would otherwise corrupt the Base64 (or split the header)
            // and produce a silent 401, mirroring the stripUserTypedPrefix defense on the
            // Bearer/header branches below.
            String username = dataMap.get("username") != null ? dataMap.get("username").strip() : null;
            String password = dataMap.get("password") != null ? dataMap.get("password").strip() : null;
            if (username != null && !username.isBlank() && password != null && !password.isBlank()) {
                String encoded = Base64.getEncoder().encodeToString(
                        (username + ":" + password).getBytes(StandardCharsets.UTF_8));
                headers.add("Authorization", "Basic " + encoded);
                log.info("[HttpExecutionService.prepareHeadersWithCredentials] Added Basic auth header (2-field credential)");
            } else {
                log.warn("[HttpExecutionService.prepareHeadersWithCredentials] basic_auth injection but username/password missing in credential data - sending no auth header");
            }
            return headers;
        }

        // If injection metadata exists and credential is present, use it for header injection
        if (injection != null && "header".equalsIgnoreCase(injection.type()) && credentialValue.isPresent()) {
            String headerKey = injection.key();
            String value = credentialValue.get();

            // Check if this is a Bearer token (Authorization header)
            if ("Authorization".equalsIgnoreCase(headerKey) || "Bearer".equalsIgnoreCase(headerKey)) {
                // Source-of-truth prefix from the migration JSON's apiKeyConfig.prefix
                // (e.g. "Bearer "). Default to "Bearer " when the metadata row predates
                // the prefix column - historical behavior of this branch.
                String prefix = injection.prefix() != null && !injection.prefix().isEmpty()
                    ? injection.prefix()
                    : "Bearer ";
                value = stripUserTypedPrefix(value, prefix);
                headers.add("Authorization", prefix + value);
                log.info("[HttpExecutionService.prepareHeadersWithCredentials] Added Authorization header with prefix={}",
                        prefix);
            } else {
                // Custom header injection (e.g., X-API-Key). Some APIs declare a prefix
                // even on custom headers ("Token xxx" in X-Auth-Token); honour it here
                // too and strip the same prefix off the user-pasted value defensively.
                String prefix = injection.prefix();
                if (prefix != null && !prefix.isEmpty()) {
                    value = stripUserTypedPrefix(value, prefix);
                    headers.add(headerKey, prefix + value);
                } else {
                    headers.add(headerKey, value);
                }
                log.info("[HttpExecutionService.prepareHeadersWithCredentials] Added custom header: {}", headerKey);
            }

            return headers;
        }

        // Query-injected credentials (e.g. Google Gemini's ?key=...) are
        // ALREADY embedded in the URL by the caller. The legacy Bearer
        // fallback below would also stamp them into Authorization, which
        // makes Google reject the request as a malformed OAuth token (401).
        // Skip auth header injection entirely in that case - the URL carries
        // the credential.
        if (injection != null && "query".equalsIgnoreCase(injection.type())) {
            log.debug("[HttpExecutionService.prepareHeadersWithCredentials] Query-injected credential - no auth header added");
            return headers;
        }

        // Credentials injected into the request BODY (body_field - e.g. Authorize.Net's
        // merchantAuthentication) or the URL PATH (url_variable - e.g. Telegram's /bot{token},
        // Firebase {project_id}) must NOT also be stamped into an Authorization header. The legacy
        // Bearer fallback below would otherwise leak the credential value into a spurious
        // "Authorization: Bearer <value>" the provider doesn't expect - harmless on hosts that
        // ignore an extra header, but actively breaking on OAuth-gated hosts (e.g. Firebase). The
        // body / URL already carries the credential. (Multi-field body assembly - e.g. Authorize.Net
        // name + transactionKey - is the workflow author's responsibility by design; see the
        // importer's body_field metadata note. A future nested-body injection mode may automate it.)
        if (injection != null
                && ("body_field".equalsIgnoreCase(injection.type())
                    || "url_variable".equalsIgnoreCase(injection.type()))) {
            log.debug("[HttpExecutionService.prepareHeadersWithCredentials] {}-injected credential - no auth header added",
                    injection.type());
            return headers;
        }

        // Legacy behavior: try to get credential and add as Bearer token (for backward compatibility)
        if (credentialValue == null || credentialValue.isEmpty()) {
            credentialValue = tryGetCredentialValue(userId, credentialName, api);
        }

        if (credentialValue.isPresent()) {
            // Same defensive strip as the injection-driven branch above: a user who
            // pastes "Bearer xxx" into the credential field would otherwise produce
            // "Authorization: Bearer Bearer xxx" → 401 from the upstream provider.
            String value = stripUserTypedPrefix(credentialValue.get(), "Bearer ");
            headers.add("Authorization", "Bearer " + value);
            log.info("[HttpExecutionService.prepareHeadersWithCredentials] Added OAuth Bearer token (legacy mode)");
            return headers;
        }

        // Fallback to API-level auth (legacy behavior)
        if (api.getAuthType() != null && !"none".equals(api.getAuthType())) {
            if (api.getAuthHeaderName() != null && api.getAuthHeaderValue() != null && !api.getAuthHeaderValue().isBlank()) {
                headers.add(api.getAuthHeaderName(), encryptionService.decrypt(api.getAuthHeaderValue()));
                log.info("[HttpExecutionService.prepareHeadersWithCredentials] Using API-level auth header: {}", api.getAuthHeaderName());
            }
        }

        return headers;
    }

    /**
     * If {@code value} starts with {@code prefix} (case-insensitive, after a left-trim),
     * return the suffix with leading whitespace stripped. Otherwise return {@code value}
     * unchanged (also left-trimmed if it had stray leading whitespace).
     *
     * <p>Defensive guard for a recurring user mistake: pasting {@code "Bearer apify_..."}
     * (or {@code "Token xxx"}, etc.) into a credential form whose injection metadata
     * already auto-applies the same prefix. Without this strip, the runtime emits
     * {@code "Authorization: Bearer Bearer apify_..."} and the upstream provider returns
     * 401 - which the catalog then surfaces as the misleading "Authentication expired"
     * message because the OAuth2 refresh path can't recover a static bearer.
     */
    static String stripUserTypedPrefix(String value, String prefix) {
        if (value == null) return null;
        String trimmed = value.replaceFirst("^\\s+", "");
        if (prefix == null || prefix.isEmpty()) return trimmed;
        if (trimmed.length() >= prefix.length()
                && trimmed.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return trimmed.substring(prefix.length()).replaceFirst("^\\s+", "");
        }
        return trimmed;
    }

    /**
     * Helper method to get user credential token
     */
    public Optional<String> tryGetUserCredential(String tenantId, String credentialName) {
        if (tenantId != null && credentialName != null && userCredentialService != null) {
            Optional<String> accessToken = userCredentialService.getAccessToken(tenantId, credentialName);
            if (accessToken.isPresent()) {
                log.debug("[HttpExecutionService.tryGetUserCredential] Found token for tenant={}, credential={}", tenantId, credentialName);
                return accessToken;
            }
        }
        return Optional.empty();
    }

    /**
     * Generic, migration-driven <b>sub-resource token resolution</b>.
     *
     * <p>Some providers issue a user/app token but require a <i>resource-scoped</i> sub-token for
     * operations on a sub-resource - the canonical case is Facebook Pages: the user token must be
     * exchanged for the per-Page {@code access_token} (returned by {@code GET /me/accounts}) to
     * publish to / read insights of that Page. This is declared <b>entirely in the catalog</b> via
     * a tool's {@code runtime_metadata.credentialResolution} block (strategy
     * {@code sub_resource_token}); there is NO provider-specific code here.
     *
     * <p>Behavior - for a tool that declares the rule AND a call that carries the trigger path param
     * (e.g. {@code page_id}): look up the sub-token once (cached, short TTL) and return it in place
     * of the base token. In every other case - no rule, wrong strategy, missing trigger value, empty
     * base value, lookup miss, or any error - the <b>base value is returned unchanged</b> (the call
     * proceeds with the base token and surfaces the provider's own error). This makes the feature a
     * strict no-op for the entire rest of the catalog.
     */
    Optional<String> resolveSubResourceToken(ApiEntity api, ApiToolEntity tool, JsonNode params,
                                             Optional<String> baseValue, String userId, String credentialName) {
        String runtime = tool == null ? null : tool.getRuntimeMetadata();
        if (baseValue.isEmpty() || runtime == null || runtime.isBlank()) {
            return baseValue;
        }
        try {
            JsonNode rule = objectMapper.readTree(runtime).path("credentialResolution");
            if (!rule.isObject() || !"sub_resource_token".equals(rule.path("strategy").asText())) {
                return baseValue;
            }
            JsonNode trigger = rule.path("trigger");
            String triggerParam = trigger.path("pathParam").asText("");
            // Runtime params are an array of single-key wrapper objects ([{"page_id":"222"}, …]) -
            // the same shape every sibling reader uses; a flat object is also tolerated defensively.
            // The match value is the trigger pathParam when the call carries it, otherwise a value
            // DERIVED from another param per the rule's trigger.deriveFrom (e.g. a Facebook post id
            // "{pageId}_{postId}" → pageId) - so post-scoped tools (create_comment, list_post_comments)
            // that carry no page_id can still resolve the per-Page token.
            String matchValue = resolveTriggerMatchValue(trigger, params);
            if (matchValue.isBlank()) {
                // Tool is sub-resource-scoped but this call didn't carry (or couldn't derive) the trigger value - leave the base token.
                return baseValue;
            }
            String apiKey = api == null ? "" : String.valueOf(api.getBaseUrl());
            String cacheKey = userId + "|" + credentialName + "|" + apiKey + "|" + matchValue;
            long now = System.currentTimeMillis();
            CachedSubToken cached = subResourceTokenCache.get(cacheKey);
            if (cached != null && cached.expiresAtMs() > now) {
                return Optional.of(cached.token());
            }
            Optional<String> sub = lookupSubResourceToken(api, rule, matchValue, baseValue.get());
            if (sub.isPresent()) {
                cachePutSubToken(cacheKey, new CachedSubToken(sub.get(), now + subTokenTtlMs), now);
                log.info("[HttpExecutionService.resolveSubResourceToken] Using resource-scoped sub-token for {}={} (credential={})",
                        triggerParam, matchValue, credentialName);
                return sub;
            }
            log.warn("[HttpExecutionService.resolveSubResourceToken] No sub-token for {}={} via {} - using base token",
                    triggerParam, matchValue, rule.path("lookup").path("endpoint").asText());
            return baseValue;
        } catch (Exception e) {
            log.warn("[HttpExecutionService.resolveSubResourceToken] resolution failed ({}) - using base token", e.toString());
            return baseValue;
        }
    }

    /** Calls the rule's lookup endpoint with the base token and extracts the matching sub-token. */
    private Optional<String> lookupSubResourceToken(ApiEntity api, JsonNode rule, String matchValue, String baseToken) {
        JsonNode lookup = rule.path("lookup");
        String endpoint = lookup.path("endpoint").asText("");
        if (endpoint.isBlank() || api == null || api.getBaseUrl() == null) {
            return Optional.empty();
        }
        String itemsPath = lookup.path("itemsPath").asText("data");
        String matchField = lookup.path("matchField").asText("id");
        String tokenField = lookup.path("tokenField").asText("access_token");
        String base = api.getBaseUrl().replaceAll("/+$", "");
        String url = base + (endpoint.startsWith("/") ? endpoint : "/" + endpoint);
        UrlSafetyValidator.validateUrl(url); // SSRF guard, mirroring the main execution path

        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", "Bearer " + baseToken);
        ResponseEntity<JsonNode> resp = restTemplate.exchange(
                java.net.URI.create(url), HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        JsonNode body = resp.getBody();
        JsonNode items = body == null ? null : body.path(itemsPath);
        if (items != null && items.isArray()) {
            for (JsonNode item : items) {
                if (matchValue.equals(item.path(matchField).asText())) {
                    String t = item.path(tokenField).asText("");
                    return t.isBlank() ? Optional.empty() : Optional.of(t);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Reads a parameter value from the runtime params, which are an array of single-key wrapper
     * objects ({@code [{"page_id":"222"}, …]}) - the canonical shape produced by
     * {@link #filterParametersByToolDefinition} and read by every sibling (path/query/header)
     * processor. A flat object is tolerated defensively for direct callers.
     */
    static String extractParamValue(JsonNode params, String name) {
        if (params == null || name == null || name.isBlank()) return "";
        if (params.isArray()) {
            for (JsonNode p : params) {
                if (p.has(name) && !p.path(name).isNull()) return p.path(name).asText("");
            }
            return "";
        }
        return params.path(name).asText("");
    }

    /**
     * Resolves the value used to match the sub-resource (e.g. a Facebook {@code page_id}).
     *
     * <p>Primary source is {@code trigger.pathParam} read straight from the call params. When that
     * param is absent, the rule may declare a {@code trigger.deriveFrom} list of fallbacks - each
     * names a source {@code param} and an optional {@code split}/{@code index} transform. The
     * canonical case is a Facebook post id of shape {@code {pageId}_{postId}}, from which the page
     * id is the first {@code "_"}-split segment ({@code split:"_", index:0}). Post-scoped tools
     * ({@code create_comment} on {@code /{object_id}/comments}, {@code list_post_comments} on
     * {@code /{post_id}/comments}) carry no {@code page_id} but do carry that composite id, so this
     * lets them resolve the per-Page token too. Returns {@code ""} when nothing resolves - the
     * caller then keeps the base token, a strict no-op.
     */
    static String resolveTriggerMatchValue(JsonNode trigger, JsonNode params) {
        if (trigger == null) return "";
        String pathParam = trigger.path("pathParam").asText("");
        if (!pathParam.isBlank()) {
            String direct = extractParamValue(params, pathParam);
            if (!direct.isBlank()) {
                return direct;
            }
        }
        JsonNode deriveFrom = trigger.path("deriveFrom");
        if (deriveFrom.isArray()) {
            for (JsonNode d : deriveFrom) {
                String srcParam = d.path("param").asText("");
                if (srcParam.isBlank()) continue;
                String raw = extractParamValue(params, srcParam);
                if (raw.isBlank()) continue;
                String split = d.path("split").asText("");
                if (split.isBlank()) {
                    return raw;
                }
                String[] parts = raw.split(java.util.regex.Pattern.quote(split));
                int idx = d.path("index").asInt(0);
                if (idx >= 0 && idx < parts.length && !parts[idx].isBlank()) {
                    return parts[idx];
                }
            }
        }
        return "";
    }

    /** Bounded put: sweep expired entries (then best-effort drop one) before exceeding the cap. */
    private void cachePutSubToken(String key, CachedSubToken value, long now) {
        if (subResourceTokenCache.size() >= subTokenCacheMax) {
            subResourceTokenCache.values().removeIf(v -> v.expiresAtMs() <= now);
            if (subResourceTokenCache.size() >= subTokenCacheMax) {
                java.util.Iterator<String> it = subResourceTokenCache.keySet().iterator();
                if (it.hasNext()) { it.next(); it.remove(); }
            }
        }
        subResourceTokenCache.put(key, value);
    }

    /** Test hook: current sub-token cache size. */
    int subTokenCacheSize() {
        return subResourceTokenCache.size();
    }

    private Optional<String> tryGetSelectedUserCredential(String userId) {
        Long selectedCredentialId = selectedUserCredentialId();
        if (userId == null || selectedCredentialId == null || userCredentialService == null) {
            return Optional.empty();
        }
        return userCredentialService.getAccessTokenInfoById(userId, selectedCredentialId)
                .map(AccessTokenResult::getAccessToken);
    }

    /**
     * Identifies which credential pool resolved a tool call's API key -
     * propagated to {@code ToolExecutionResponse.metadata.credentialSource}
     * so downstream billing dispatchers (e.g. orchestrator's
     * {@code CatalogBillingDispatcher}) can decide between platform-cost
     * passthrough and BYOK trace.
     */
    public enum CredentialSource { USER, PLATFORM }

    /**
     * Resolved credential value + the pool it came from. Used by
     * {@link #tryGetCredentialResolution}. Callers that only need the
     * value can use {@link #tryGetCredentialValue} (back-compat wrapper).
     */
    public record CredentialResolution(String value, CredentialSource source) {}

    /**
     * Get credential value (OAuth token or API key) based on credential
     * mode AND record which credential pool answered. Mirrors the legacy
     * {@link #tryGetCredentialValue} branches but augments the result with
     * the source so billing logic can distinguish user-key from
     * platform-key calls.
     */
    public Optional<CredentialResolution> tryGetCredentialResolution(String userId, String credentialName, ApiEntity api) {
        // 1) Workflow node toggle (durci, no fallback) wins over everything.
        //    Set from ToolExecutionRequest.credentialSource via the controller.
        String explicitSource = com.apimarketplace.catalog.service.http.CredentialModeContext.getExplicitSource();
        if (explicitSource != null) {
            switch (explicitSource) {
                case "user": {
                    Long selectedCredentialId = selectedUserCredentialId();
                    Optional<String> v = selectedCredentialId != null
                            ? tryGetSelectedUserCredential(userId)
                            : Optional.empty();
                    if (v.isEmpty()) {
                        // Pinned credential missing/deleted → fall back to the user's
                        // DEFAULT credential for this integration (take pinned, else
                        // default). Stays on the user pool - an explicit "user" source
                        // never leaks to platform.
                        v = tryGetUserCredential(userId, credentialName);
                    }
                    return v.map(s -> new CredentialResolution(s, CredentialSource.USER));
                }
                case "platform": {
                    String platformCredName = api.getPlatformCredentialName();
                    if (platformCredName == null) return Optional.empty();
                    Optional<String> v = tryGetUserCredential(PLATFORM_TENANT_ID, platformCredName);
                    if (v.isPresent()) {
                        log.debug("[tryGetCredentialResolution] Workflow toggle PLATFORM, using credential: {}", platformCredName);
                    }
                    return v.map(s -> new CredentialResolution(s, CredentialSource.PLATFORM));
                }
                default:
                    log.warn("[tryGetCredentialResolution] Unknown explicit source: {}, ignoring", explicitSource);
            }
        }

        // 2) Agentic path (no explicit source) → user-then-platform fallback.
        //    The legacy `credentialModeOverride` thread-local is whitelisted
        //    only to "both" (CredentialModeContext.AGENTIC_ALLOWED), which is
        //    semantically identical to the default - kept solely so legacy
        //    bodies don't trip the whitelist rejection log. Either way we
        //    apply user→platform fallback, so we don't even branch on it.
        Optional<String> userV = tryGetUserCredential(userId, credentialName);
        if (userV.isPresent()) {
            return userV.map(s -> new CredentialResolution(s, CredentialSource.USER));
        }
        String platformCred = api.getPlatformCredentialName();
        if (platformCred == null) return Optional.empty();
        Optional<String> v = tryGetUserCredential(PLATFORM_TENANT_ID, platformCred);
        if (v.isPresent()) {
            log.debug("[tryGetCredentialResolution] User credential not found, using PLATFORM fallback: {}", platformCred);
        }
        return v.map(s -> new CredentialResolution(s, CredentialSource.PLATFORM));
    }

    /**
     * Back-compat wrapper for callers that only need the credential value
     * (no source). New code should call
     * {@link #tryGetCredentialResolution} directly.
     */
    public Optional<String> tryGetCredentialValue(String userId, String credentialName, ApiEntity api) {
        return tryGetCredentialResolution(userId, credentialName, api).map(CredentialResolution::value);
    }


    /**
     * Replaces URL template variables like {token}, {domain}, {project_id} with credential data values.
     * Looks up each variable name in the full credential_data map. Falls back to common aliases,
     * then to the primary credential value if no match is found.
     */
    private String replaceUrlTemplateVariables(String url, String userId, String credentialName, String credentialValue) {
        // Load full credential data map for field-by-field matching
        Map<String, String> credentialDataMap = getCredentialDataMapForUserSelection(userId, credentialName);

        Pattern pattern = Pattern.compile("\\{([^}]+)\\}");
        Matcher matcher = pattern.matcher(url);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String varName = matcher.group(1);
            String replacement = resolveUrlVariable(varName, credentialDataMap, credentialValue);
            if (replacement != null) {
                log.info("[HttpExecutionService] Replacing URL variable {{{}}} from credential data", varName);
                matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            } else {
                log.warn("[HttpExecutionService] No value found for URL variable {{{}}}", varName);
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Resolves a URL template variable name to a value from credential data.
     * Tries: exact match → lowercase match → common aliases → fallback to primary credential value.
     */
    private String resolveUrlVariable(String varName, Map<String, String> credentialDataMap, String fallbackValue) {
        if (credentialDataMap.isEmpty()) return fallbackValue;

        // 1. Exact match
        if (credentialDataMap.containsKey(varName)) return credentialDataMap.get(varName);

        // 2. Case-insensitive match
        String lowerVar = varName.toLowerCase(java.util.Locale.ROOT);
        for (Map.Entry<String, String> entry : credentialDataMap.entrySet()) {
            if (entry.getKey().toLowerCase(java.util.Locale.ROOT).equals(lowerVar)) return entry.getValue();
        }

        // 3. Common aliases for token-like variables
        List<String> tokenAliases = List.of("api_token", "api_key", "access_token", "bearer_token", "token", "key", "secret");
        if (tokenAliases.stream().anyMatch(a -> a.equalsIgnoreCase(varName))) {
            for (String alias : tokenAliases) {
                for (Map.Entry<String, String> entry : credentialDataMap.entrySet()) {
                    if (entry.getKey().equalsIgnoreCase(alias)) return entry.getValue();
                }
            }
        }

        // 4. Fallback to the primary credential value - ONLY for genuinely single-field credentials.
        // For a multi-field credential, injecting the primary value into the URL would silently send
        // a WRONG-but-non-empty secret into the path; return null so the unresolved {var} fails loudly
        // downstream (URI parse / SSRF validation) instead of making a wrong-credential call.
        return credentialDataMap.size() <= 1 ? fallbackValue : null;
    }

    /**
     * Loads parameter metadata (parameterType and dataType) for all parameters of a tool.
     * @param toolId The tool ID
     * @return Map of parameter name to ParameterMetadata
     */
    public Map<String, ParameterMetadata> loadParameterMetadata(UUID toolId) {
        Map<String, ParameterMetadata> metadata = new HashMap<>();
        try {
            List<ApiToolParameterEntity> params = apiToolParameterRepository.findByApiToolId(toolId);
            for (ApiToolParameterEntity param : params) {
                String bodyPath = extractBodyPath(param.getExtras());
                boolean inlineBody = extractInlineBody(param.getExtras());
                String encoding = extractEncoding(param.getExtras());
                metadata.put(param.getName(), new ParameterMetadata(
                    param.getParameterType(),
                    param.getDataType(),
                    bodyPath,
                    inlineBody,
                    encoding,
                    param.getDefaultValue()
                ));
            }
            log.debug("[HttpExecutionService.loadParameterMetadata] Loaded {} parameter metadata entries for tool {}",
                metadata.size(), toolId);
        } catch (Exception e) {
            log.warn("[HttpExecutionService.loadParameterMetadata] Error loading parameter metadata for tool {}: {}",
                toolId, e.getMessage());
        }
        return metadata;
    }

    private String extractBodyPath(String extrasJson) {
        if (extrasJson == null || extrasJson.isBlank() || "{}".equals(extrasJson)) {
            return null;
        }
        try {
            JsonNode extras = objectMapper.readTree(extrasJson);
            JsonNode bodyPathNode = extras.path("bodyPath");
            return bodyPathNode.isMissingNode() || bodyPathNode.isNull() ? null : bodyPathNode.asText();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean extractInlineBody(String extrasJson) {
        if (extrasJson == null || extrasJson.isBlank() || "{}".equals(extrasJson)) {
            return false;
        }
        try {
            JsonNode extras = objectMapper.readTree(extrasJson);
            JsonNode inlineNode = extras.path("inlineBody");
            return !inlineNode.isMissingNode() && !inlineNode.isNull() && inlineNode.asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Reads the optional per-parameter {@code encoding} directive from the param's
     * extras JSON. Currently only {@code "strict"} is meaningful (full path-segment
     * percent-encoding). Returns null when absent → conservative default applies.
     */
    private String extractEncoding(String extrasJson) {
        if (extrasJson == null || extrasJson.isBlank() || "{}".equals(extrasJson)) {
            return null;
        }
        try {
            JsonNode extras = objectMapper.readTree(extrasJson);
            JsonNode encNode = extras.path("encoding");
            return encNode.isMissingNode() || encNode.isNull() ? null : encNode.asText();
        } catch (Exception e) {
            return null;
        }
    }

    private void setNestedValue(Map<String, Object> body, String path, Object value) {
        BodyPathExecutor.apply(body, path, value);
    }

    /**
     * Converts a value to the expected type based on dataType.
     * Handles array conversion: "value" -> ["value"], "[]" -> []
     * @param value The raw value (can be JsonNode or String)
     * @param dataType The expected data type (e.g., "array", "string", "integer")
     * @return The converted value
     */
    public Object convertToExpectedType(Object value, String dataType) {
        if (value == null) {
            return null;
        }

        // Handle JsonNode
        if (value instanceof JsonNode jsonNode) {
            value = extractJsonNodeValue(jsonNode);
        }

        // Convert to array if needed
        if ("array".equalsIgnoreCase(dataType)) {
            if (value instanceof List) {
                return value; // Already a list
            }
            if (value instanceof String strValue) {
                // Handle "[]" as empty array
                if ("[]".equals(strValue.trim())) {
                    return List.of();
                }
                // Handle "[item1, item2]" format
                if (strValue.startsWith("[") && strValue.endsWith("]")) {
                    try {
                        // Try to parse as JSON array
                        JsonNode arrayNode = objectMapper.readTree(strValue);
                        if (arrayNode.isArray()) {
                            List<Object> list = new ArrayList<>();
                            for (JsonNode item : arrayNode) {
                                list.add(extractJsonNodeValue(item));
                            }
                            return list;
                        }
                    } catch (Exception e) {
                        // Not valid JSON, treat as single value
                        log.debug("[HttpExecutionService.convertToExpectedType] Could not parse '{}' as JSON array, wrapping as single element", strValue);
                    }
                }
                // CSV-string fallback for legacy plans (pre-2026-05-06): callers
                // who authored "Subject,From" instead of ["Subject","From"] used
                // to land here as List.of("Subject,From") - a single-element
                // list with a literal comma. Form-urlencoded body encoding then
                // emitted `?key=Subject%2CFrom` instead of repeated, and JSON
                // body emitted `{"key":["Subject,From"]}` instead of
                // `{"key":["Subject","From"]}` - rejected/misinterpreted by
                // strict APIs. Split on commas like the query-side helper
                // (extractArrayValues) for shape consistency. Single value
                // (no comma) still produces a 1-element list as before.
                //
                // Contract: callers that need a literal comma inside an element
                // (e.g. tag named "Smith, John") MUST pass a real JSON array -
                // ["Smith, John"] - which is matched by the JSON-array branch
                // above and never reaches this CSV split. This trade-off is
                // identical to and consistent with the query-side helper.
                if (strValue.contains(",")) {
                    List<Object> list = new ArrayList<>();
                    for (String part : strValue.split(",")) {
                        String trimmed = part.trim();
                        if (!trimmed.isEmpty()) list.add(trimmed);
                    }
                    if (!list.isEmpty()) return list;
                }
                // Single value -> wrap in array
                return List.of(strValue);
            }
            // Other types -> wrap in array
            return List.of(value);
        }

        // Convert to object (Map) if needed - mirror of the array branch above.
        // Object-typed body params arrive from workflows as JSON strings
        // (e.g. snippet="{\"title\":...}"). Without parsing, a json-body endpoint
        // emits {"snippet":"{...}"} (a literal string) and strict APIs reject it -
        // e.g. YouTube videos.update: "Invalid value at 'resource.snippet'
        // (type ... VideoSnippet)". The multipart_related encoder already parses
        // these; this brings the plain json-body path to parity. Fails safe:
        // non-JSON-object strings pass through unchanged.
        if ("object".equalsIgnoreCase(dataType)) {
            if (value instanceof Map) {
                return value; // already an object
            }
            if (value instanceof String strValue) {
                String trimmed = strValue.trim();
                if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                    try {
                        JsonNode objNode = objectMapper.readTree(trimmed);
                        if (objNode.isObject()) {
                            return extractJsonNodeValue(objNode);
                        }
                    } catch (Exception e) {
                        // Not valid JSON object, leave as-is
                        log.debug("[HttpExecutionService.convertToExpectedType] Could not parse '{}' as JSON object, passing through", strValue);
                    }
                }
            }
            return value; // not a JSON-object string - unchanged
        }

        // Coerce stringified scalars when the upstream API declared a typed param.
        // Workflow form inputs and SpEL-rendered expressions arrive as strings
        // even when the param spec says "integer"/"number"/"boolean" - strict
        // validators (e.g. Apify Actors, REST APIs with JSON Schema) then reject
        // with "must be integer". Apply the same strict patterns used by
        // coerceInlineBodyScalars so leading-zero IDs and '+'-prefixed phones
        // stay as strings; ambiguous values pass through untouched.
        if (value instanceof String strValue && dataType != null) {
            // Locale.ROOT defends against the Turkish-locale "i" trap:
            // "INTEGER".toLowerCase() under tr_TR → "ınteger" (dotless ı), which
            // would silently miss the switch arm. Locale.ROOT is canonical
            // ASCII-fold, safe across all JVM locales.
            String dt = dataType.toLowerCase(java.util.Locale.ROOT);
            switch (dt) {
                case "integer", "int", "long" -> {
                    if (STRICT_INT_RE.matcher(strValue).matches()) {
                        try { return Long.parseLong(strValue); } catch (NumberFormatException ignored) {}
                    }
                }
                case "number", "double", "float" -> {
                    if (STRICT_INT_RE.matcher(strValue).matches()) {
                        try { return Long.parseLong(strValue); } catch (NumberFormatException ignored) {}
                    }
                    if (STRICT_DECIMAL_RE.matcher(strValue).matches()) {
                        try { return Double.parseDouble(strValue); } catch (NumberFormatException ignored) {}
                    }
                }
                case "boolean", "bool" -> {
                    if ("true".equals(strValue)) return Boolean.TRUE;
                    if ("false".equals(strValue)) return Boolean.FALSE;
                }
                default -> { /* string/object/unknown: pass through */ }
            }
        }

        return value;
    }

    private static final Pattern STRICT_INT_RE = Pattern.compile("^-?(0|[1-9]\\d*)$");
    private static final Pattern STRICT_DECIMAL_RE = Pattern.compile("^-?(0|[1-9]\\d*)\\.\\d+$");

    /**
     * Recursively coerces stringified scalars in an inline-body value.
     *
     * <p>Workflow form inputs and SpEL-rendered expressions arrive as strings
     * even when the upstream API expects typed scalars. Without coercion,
     * Apify Actors and similar strict validators reject the request with
     * "must be integer" / "must be boolean".
     *
     * <p><b>Coercion rules (strict by design):</b>
     * <ul>
     *   <li>{@code "10"} → {@code 10} (Long); {@code "-3"} → {@code -3}; {@code "0"} → {@code 0}.</li>
     *   <li>{@code "01234"}, {@code "+33"}, {@code "1e3"} → left as string (zip codes,
     *       phone fragments, scientific notation are NOT coerced - too risky).</li>
     *   <li>{@code "3.14"} → {@code 3.14} (Double); {@code "0.5"} → {@code 0.5}.</li>
     *   <li>{@code "true"} / {@code "false"} (lowercase only) → boolean.</li>
     *   <li>Anything else → unchanged.</li>
     * </ul>
     *
     * <p>Maps and Lists are traversed recursively; keys are never coerced.
     * Already-typed values (Integer, Long, Double, Boolean) pass through.
     */
    @SuppressWarnings("unchecked")
    Object coerceInlineBodyScalars(Object value) {
        if (value == null) return null;
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>(m.size());
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), coerceInlineBodyScalars(e.getValue()));
            }
            return out;
        }
        if (value instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object item : l) out.add(coerceInlineBodyScalars(item));
            return out;
        }
        if (value instanceof String s) {
            if (s.isEmpty()) return s;
            if ("true".equals(s)) return Boolean.TRUE;
            if ("false".equals(s)) return Boolean.FALSE;
            if (STRICT_INT_RE.matcher(s).matches()) {
                try { return Long.parseLong(s); } catch (NumberFormatException ignored) {}
            }
            if (STRICT_DECIMAL_RE.matcher(s).matches()) {
                try { return Double.parseDouble(s); } catch (NumberFormatException ignored) {}
            }
        }
        return value;
    }

    /**
     * Extracts the Java value from a JsonNode.
     */
    private Object extractJsonNodeValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isNumber()) {
            if (node.isInt()) {
                return node.asInt();
            }
            if (node.isLong()) {
                return node.asLong();
            }
            return node.asDouble();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonNode item : node) {
                list.add(extractJsonNodeValue(item));
            }
            return list;
        }
        if (node.isObject()) {
            Map<String, Object> map = new HashMap<>();
            node.fields().forEachRemaining(entry -> map.put(entry.getKey(), extractJsonNodeValue(entry.getValue())));
            return map;
        }
        return node.asText();
    }

    /**
     * Builds the complete URL
     */
    public String buildFullUrl(ApiEntity api, ApiToolEntity tool) {
        String baseUrl = api.getBaseUrl();
        String endpoint = tool.getEndpoint();

        // Absolute-URL escape hatch: when an endpoint path is itself a full URL
        // (http:// or https://), use it verbatim and ignore the API baseUrl. This
        // lets a single endpoint live on a different host or path prefix than the
        // rest of the API - e.g. Google media uploads, which must target
        // https://www.googleapis.com/upload/youtube/v3/videos (the /upload/ prefix)
        // while the API baseUrl is https://www.googleapis.com/youtube/v3. Path
        // params and credential injection still apply to the returned URL.
        if (endpoint != null
                && (endpoint.startsWith("http://") || endpoint.startsWith("https://"))) {
            return endpoint;
        }

        // Dynamic-URL escape hatch: when the endpoint path BEGINS with a {placeholder}, the
        // full request URL comes from a runtime parameter (a provider-issued signed URL that a
        // prior call returned) - prepending the API baseUrl would corrupt it into
        // "https://base/https://provider/...". Return the template verbatim; the placeholder is
        // substituted by processPathParameters (declare extras.encoding="verbatim" on the param
        // so its query string survives) and the result is SSRF-validated like any other URL.
        // Users: TikTok Content Posting upload_url, WhatsApp Cloud media_url.
        if (endpoint != null && endpoint.startsWith("{")) {
            return endpoint;
        }

        String fullUrl;
        if (baseUrl.endsWith("/") && endpoint.startsWith("/")) {
            fullUrl = baseUrl + endpoint.substring(1);
        } else if (!baseUrl.endsWith("/") && !endpoint.startsWith("/")) {
            fullUrl = baseUrl + "/" + endpoint;
        } else {
            fullUrl = baseUrl + endpoint;
        }

        return fullUrl;
    }

    /**
     * Security constraints for dynamic-URL endpoints - endpoints whose path template BEGINS
     * with a {placeholder} (see the dynamic-URL escape hatch in {@link #buildFullUrl}), where a
     * RUNTIME parameter chooses the request host. Three checks, all fail-closed:
     *
     * <ol>
     *   <li><b>No residual placeholders.</b> The substituted URL must be a complete literal.
     *       A value like {@code https://{api_key}.evil.com/} would pass a naive SSRF check
     *       (the validator skips DNS on templated hosts) and then have the user's credential
     *       substituted into it by {@code replaceUrlTemplateVariables} downstream - a
     *       credential-exfiltration primitive. Rejecting any remaining '{' closes that hole and
     *       doubles as a clear error when the URL parameter was simply not provided.</li>
     *   <li><b>Host allow-list.</b> The endpoint MUST declare
     *       {@code execution.request.allowedUrlHostSuffixes}; the URL's host must equal one of
     *       the suffixes or end with "." + suffix. Provider-signed URLs live on known provider
     *       hosts (TikTok {@code *.tiktokapis.com}, WhatsApp {@code lookaside.fbsbx.com}) -
     *       any other destination would receive the user's injected credential, so an endpoint
     *       without a declared allow-list is refused outright rather than left open.
     *       Checked BEFORE DNS resolution so attacker-chosen domains are never even resolved.</li>
     *   <li><b>SSRF validation</b> of the final URL (private/loopback/link-local rejection),
     *       same validator as the legacy path.</li>
     * </ol>
     *
     * <p>No-op for fixed-host endpoints (template does not start with '{'): their host is the
     * registered, trusted baseUrl and existing behaviour must not change.
     *
     * @throws IllegalArgumentException when any constraint fails (callers surface it as a
     *         failed tool result; no outbound request is made)
     */
    void enforceDynamicUrlConstraints(ApiToolEntity tool, String url) {
        String endpointTemplate = tool.getEndpoint();
        if (endpointTemplate == null || !endpointTemplate.startsWith("{")) {
            return;
        }
        if (url == null || url.contains("{")) {
            throw new IllegalArgumentException(
                "Dynamic-URL endpoint " + endpointTemplate + " requires its URL parameter to be a "
                + "complete literal URL from the provider's init/lookup response - got unresolved "
                + "placeholders: " + url);
        }
        List<String> suffixes = readAllowedUrlHostSuffixes(tool);
        if (suffixes.isEmpty()) {
            throw new IllegalArgumentException(
                "Dynamic-URL endpoint " + endpointTemplate + " declares no "
                + "execution.request.allowedUrlHostSuffixes - refusing an open-destination request");
        }
        String host;
        try {
            host = java.net.URI.create(url.trim()).getHost();
        } catch (Exception e) {
            host = null;
        }
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Dynamic URL has no parseable host: " + url);
        }
        String lowerHost = host.toLowerCase(Locale.ROOT);
        boolean allowed = suffixes.stream()
            .map(s -> s.toLowerCase(Locale.ROOT))
            .anyMatch(s -> lowerHost.equals(s) || lowerHost.endsWith("." + s));
        if (!allowed) {
            throw new IllegalArgumentException(
                "Dynamic URL host '" + host + "' is not among the endpoint's allowed hosts "
                + suffixes + " - refusing the request");
        }
        UrlSafetyValidator.validateUrl(url);
    }

    /** Read execution.request.allowedUrlHostSuffixes off the tool's execution spec (empty when absent). */
    private List<String> readAllowedUrlHostSuffixes(ApiToolEntity tool) {
        JsonNode spec = parseExecutionSpec(tool.getExecutionSpec());
        JsonNode arr = spec.path("request").path("allowedUrlHostSuffixes");
        if (arr == null || !arr.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode n : arr) {
            String s = n.asText("").trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * Conservative URL encoding for path parameter values: only escape the
     * chars that would actively break URL structure ({@code ' '}, {@code '#'},
     * {@code '?'}). Everything else - including {@code '/'}, {@code ':'},
     * {@code '@'}, sub-delims, and unicode - passes through literally so
     * multi-segment tools (S3, Firebase, GitHub Contents, …) keep working.
     *
     * <p>See the comment in {@link #processPathParameters} for the rationale.
     */
    String encodePathValueConservative(String value) {
        if (value == null) return "";
        // Most ids contain no dangerous chars - fast path.
        if (value.indexOf('?') < 0 && value.indexOf('#') < 0 && value.indexOf(' ') < 0) {
            return value;
        }
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '?' -> sb.append("%3F");
                case '#' -> sb.append("%23");
                case ' ' -> sb.append("%20");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Strict path-segment encoding: treats the value as an OPAQUE identifier and
     * percent-encodes every reserved character, including ':' → %3A and '/' → %2F.
     * Opt-in per parameter via {@code "encoding": "strict"} in the param's JSON
     * (carried through extras → ParameterMetadata.encoding). Required by endpoints
     * whose {placeholder} is a full URL or URN, e.g. Search Console
     * {@code /sites/{siteUrl}/sitemaps/{feedpath}} with siteUrl="https://site/"
     * → "https%3A%2F%2Fsite%2F". Uses URLEncoder (same encoder as the query-param
     * path, line ~1583) then normalizes '+' → %20 since '+' is form-encoding for
     * space and would be taken literally in a path segment.
     */
    String encodePathValueStrict(String value) {
        if (value == null) return "";
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * Processes path parameters in the URL.
     * SSRF validation runs AFTER path parameter substitution so that
     * template variables like {userId} don't break URI parsing.
     */
    public String processPathParameters(String url, ApiToolEntity tool, JsonNode parameters) {
        try {
            log.info("[HttpExecutionService.processPathParameters] Tool: {}, URL before processing: {}", tool.getId(), url);
            log.info("[HttpExecutionService.processPathParameters] Tool: {}, Parameters JSON: {}", tool.getId(), parameters);

            // Extract expected path parameters from URL
            Pattern pattern = Pattern.compile("\\{([^}]+)\\}");
            Matcher matcher = pattern.matcher(url);
            Set<String> expectedPathParams = new HashSet<>();
            while (matcher.find()) {
                expectedPathParams.add(matcher.group(1));
            }
            log.info("[HttpExecutionService.processPathParameters] Tool: {}, Expected path parameters in URL: {}",
                    tool.getId(), expectedPathParams);

            if (parameters != null && parameters.isArray()) {
                Map<String, String> availableParams = new HashMap<>();
                for (JsonNode param : parameters) {
                    if (!param.fieldNames().hasNext()) continue; // skip empty {} from null values
                    String paramName = param.fieldNames().next();
                    JsonNode valueNode = param.get(paramName);
                    if (valueNode == null || valueNode.isNull()) continue; // skip explicit nulls
                    String value = valueNode.asText();
                    availableParams.put(paramName, value);
                    log.info("[HttpExecutionService.processPathParameters] Tool: {}, Available parameter: {} = {}",
                            tool.getId(), paramName, value);
                }

                log.info("[HttpExecutionService.processPathParameters] Tool: {}, Available parameters map: {}",
                        tool.getId(), availableParams);

                // Per-param metadata drives the encoding strategy below. Loaded once
                // here (same DB source as processQueryParameters) so a param can opt
                // into strict encoding via extras.encoding = "strict".
                Map<String, ParameterMetadata> paramMetadata = loadParameterMetadata(tool.getId());

                // Replace {paramName} in URL. Default = conservative URL encoding;
                // opt-in strict per param via metadata.encoding == "strict".
                //
                // CONSERVATIVE (default) encodes ONLY the chars that would actively
                // break URL structure (terminate path, start a query/fragment, or
                // trigger URI parser failure):  ' ' → %20   #  → %23   ?  → %3F
                //
                // Why not full RFC 3986 path-segment encoding by default:
                // ~50 catalog tools intentionally pass MULTI-SEGMENT values into a
                // single `{path}` placeholder - AWS S3 `/{bucket}/{key}` where
                // key="images/2025/photo.jpg", Firebase RTDB `/{path}.json`,
                // Firestore `{collection_path}`, GitHub `/repos/.../{path}` for
                // `src/main/Foo.java`, dbt artifacts, Cloudinary folders, etc.
                // Encoding '/' as %2F would silently route to a non-existent
                // resource for all of them. Same logic for ':' and '@' - keep
                // literal so URLs like /timestamps/2026:05:06 keep working.
                //
                // STRICT (opt-in) treats the value as an OPAQUE identifier and
                // encodes everything incl. ':' and '/'. Required by endpoints whose
                // {placeholder} is a full URL or URN - Search Console
                // /sites/{siteUrl}/sitemaps/{feedpath}, Sendbird {channel_url},
                // LinkedIn {entityUrn}. Without it the raw "https://site/" splits the
                // path and the upstream API 404s. Declared per-param in the JSON
                // (extras.encoding) so it is data-driven, never hard-coded per API.
                //
                // VERBATIM (opt-in) applies NO encoding at all. Required when the
                // {placeholder} IS the entire request URL (dynamic-URL endpoints whose
                // path starts with the placeholder - TikTok upload_url, WhatsApp
                // media_url): those provider-issued signed URLs carry their own query
                // string, and conservative's '?' → %3F would corrupt it. Only sensible
                // for full-URL params; the substituted result still passes
                // UrlSafetyValidator before any request is sent.
                for (String paramName : expectedPathParams) {
                    if (availableParams.containsKey(paramName)) {
                        String rawValue = availableParams.get(paramName);
                        ParameterMetadata meta = paramMetadata.get(paramName);
                        String encoding = meta != null && meta.encoding() != null
                            ? meta.encoding().toLowerCase(java.util.Locale.ROOT)
                            : "";
                        String value = switch (encoding) {
                            case "strict" -> encodePathValueStrict(rawValue);
                            case "verbatim" -> rawValue;
                            default -> encodePathValueConservative(rawValue);
                        };
                        url = url.replace("{" + paramName + "}", value);
                        log.info("[HttpExecutionService.processPathParameters] Tool: {}, Replaced {{{}}} with {} (encoding={})",
                                tool.getId(), paramName, value, encoding.isEmpty() ? "conservative" : encoding);
                    } else {
                        log.warn("[HttpExecutionService.processPathParameters] Tool: {}, Missing path parameter: {} in available params: {}",
                                tool.getId(), paramName, availableParams.keySet());
                    }
                }
            } else {
                log.warn("[HttpExecutionService.processPathParameters] Tool: {}, Parameters is null or not an array", tool.getId());
            }

            log.info("[HttpExecutionService.processPathParameters] Tool: {}, URL after processing: {}", tool.getId(), url);
        } catch (Exception e) {
            log.error("[HttpExecutionService.processPathParameters] Tool: {}, Error processing path parameters: {}",
                    tool.getId(), e.getMessage(), e);
        }

        return url;
    }

    /**
     * Extract values for a query-array parameter. Supports both input shapes:
     * <ul>
     *   <li>JSON array node - {@code ["a","b"]} → {@code ["a", "b"]}</li>
     *   <li>CSV string node - {@code "a,b"} → {@code ["a", "b"]}
     *       (legacy plans authored before 2026-05-06 still pass strings)</li>
     * </ul>
     *
     * <p>Trims whitespace per element and drops blank entries. Returns an empty
     * list for null / null-node / missing-node input so the caller can iterate
     * without an extra guard.
     */
    private List<String> extractArrayValues(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node == null || node.isNull() || node.isMissingNode()) {
            return out;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (item == null || item.isNull()) continue;
                String s = item.asText();
                if (s != null && !s.isBlank()) out.add(s.trim());
            }
            return out;
        }
        // String fallback - legacy plans store "a,b" instead of ["a","b"].
        String raw = node.asText();
        if (raw == null) return out;
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out;
    }

    /**
     * Processes query parameters.
     * Only processes parameters that have parameterType = 'query' in the database.
     * Falls back to legacy behavior (all non-path params) if no metadata is available.
     */
    public String processQueryParameters(String url, ApiToolEntity tool, JsonNode parameters) {
        try {
            if (parameters != null && parameters.isArray()) {
                List<String> queryParts = new ArrayList<>();

                // Load parameter metadata to check parameterType
                Map<String, ParameterMetadata> paramMetadata = loadParameterMetadata(tool.getId());

                // Extract path parameter names from original endpoint (for fallback)
                Set<String> pathParamNames = new HashSet<>();
                String originalEndpoint = tool.getEndpoint();
                if (originalEndpoint != null) {
                    Pattern pattern = Pattern.compile("\\{([^}]+)\\}");
                    Matcher matcher = pattern.matcher(originalEndpoint);
                    while (matcher.find()) {
                        pathParamNames.add(matcher.group(1));
                    }
                }

                for (JsonNode param : parameters) {
                    if (!param.fieldNames().hasNext()) continue; // skip empty {} from null values
                    String paramName = param.fieldNames().next();
                    JsonNode valueNode = param.get(paramName);
                    if (valueNode == null || valueNode.isNull()) continue; // skip explicit nulls
                    ParameterMetadata meta = paramMetadata.get(paramName);

                    // Determine if this parameter should be in query string
                    boolean isQueryParam;
                    if (meta != null && meta.parameterType() != null) {
                        // Use parameterType from database
                        isQueryParam = "query".equalsIgnoreCase(meta.parameterType());
                    } else {
                        // Legacy fallback: treat as query if not a path param and method is GET
                        // For POST/PUT/PATCH, non-path params go to body by default.
                        //
                        // NOTE (latent footgun): when meta == null the param is UNDECLARED, so
                        // this fallback will happily turn any stray field into ?field=... on a
                        // GET. Today every caller pre-filters via filterParametersByToolDefinition
                        // (undeclared fields are dropped before reaching here), so the only way an
                        // undeclared field arrives is a tool with a non-empty allowed-set whose
                        // matched param row lacks parameterType - rare. This was the mechanism
                        // behind the {"trigger":...} → ?trigger= leak (closed at the filter
                        // chokepoint). If a future caller bypasses the filter, make this branch
                        // fail-closed (drop undeclared/typeless fields) rather than emitting them.
                        isQueryParam = !pathParamNames.contains(paramName) && "GET".equalsIgnoreCase(tool.getMethod());
                    }

                    if (isQueryParam) {
                        boolean isArrayParam = meta != null && "array".equalsIgnoreCase(meta.dataType());

                        if (isArrayParam) {
                            // OpenAPI 3 default for query arrays: style=form, explode=true →
                            // repeated-query (?k=v1&k=v2). Most modern APIs (Gmail
                            // metadataHeaders, Airtable fields[], AWS Tags, …) expect this.
                            // The pre-2026-05-06 code emitted a single CSV value via
                            // .asText(), which Gmail interpreted as a literal header named
                            // "Subject,From" → 0 headers returned. Same shape silently
                            // misfired for ~234 query+array params across the catalog.
                            // See extractArrayValues() for the JSON-array vs legacy-CSV
                            // input handling.
                            List<String> arrayValues = extractArrayValues(valueNode);
                            for (String v : arrayValues) {
                                queryParts.add(paramName + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8));
                            }
                            log.debug("[HttpExecutionService.processQueryParameters] Added repeated-query array param: {} ({} values)",
                                paramName, arrayValues.size());
                        } else {
                            String value = valueNode.asText();
                            queryParts.add(paramName + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8));
                            log.debug("[HttpExecutionService.processQueryParameters] Added query param: {}={}", paramName, value);
                        }
                    }
                }

                if (!queryParts.isEmpty()) {
                    url += (url.contains("?") ? "&" : "?") + String.join("&", queryParts);
                }
            }
        } catch (Exception e) {
            log.warn("Error processing query parameters: {}", e.getMessage());
        }

        return url;
    }

    /**
     * Headers owned by the HTTP client / transport layer - a tool param must never set these
     * (a stale Content-Length or a wrong Host corrupts the request). Lower-cased for case-insensitive match.
     */
    private static final Set<String> TRANSPORT_MANAGED_HEADERS = Set.of(
        // computed/transport headers
        "content-length", "host", "connection", "transfer-encoding", "expect", "upgrade",
        // hop-by-hop headers (RFC 7230 §6.1) - never carried end-to-end, never from a tool param
        "te", "trailer", "keep-alive", "proxy-authenticate", "proxy-authorization");

    /**
     * Applies parameters declared with {@code parameterType='header'} as HTTP request headers.
     *
     * <p>Sibling of {@link #processQueryParameters} but targets headers instead of the URL.
     * Without this, a header-typed param (e.g. Google Ads {@code developer-token} /
     * {@code login-customer-id}) fell through to the query string → the provider 404s/rejects.
     *
     * <p>Values are stripped of CR/LF (RFC 7230 bans them in header values; this also kills a
     * stray trailing {@code \r} from a pasted credential) then trimmed. Uses {@code set()} so a
     * declared header param overrides any default of the same name. No-op for tools without
     * header params, so it is safe to call on every request path.
     */
    public void applyHeaderParameters(HttpHeaders headers, ApiToolEntity tool, JsonNode parameters) {
        if (parameters == null || !parameters.isArray()) return;
        try {
            Map<String, ParameterMetadata> paramMetadata = loadParameterMetadata(tool.getId());
            for (JsonNode param : parameters) {
                if (!param.fieldNames().hasNext()) continue;
                String paramName = param.fieldNames().next();
                JsonNode valueNode = param.get(paramName);
                if (valueNode == null || valueNode.isNull()) continue;
                ParameterMetadata meta = paramMetadata.get(paramName);
                if (meta == null || !"header".equalsIgnoreCase(meta.parameterType())) continue;
                // Never let a tool param override transport/computed headers - the HTTP client owns
                // these and a manual value corrupts the request (e.g. a stale Content-Length, or a
                // Host that breaks routing). Catalog JSONs declare some of these as header params
                // (Content-Length on upload APIs, etc.); skip them so a broad re-import stays safe.
                if (TRANSPORT_MANAGED_HEADERS.contains(paramName.toLowerCase(java.util.Locale.ROOT))) {
                    log.debug("[HttpExecutionService.applyHeaderParameters] Skipping transport-managed header param: {}", paramName);
                    continue;
                }
                // Strip CR/LF (header-injection guard + stray trailing \r from pasted tokens), then trim.
                String value = valueNode.asText().replaceAll("[\\r\\n]", "").trim();
                if (value.isEmpty()) continue;
                headers.set(paramName, value);
                log.debug("[HttpExecutionService.applyHeaderParameters] Set header param: {}", paramName);
            }
            // Inject declared defaults for header params the caller omitted - e.g. version-pin
            // headers (X-Api-Version, SHIPPO-API-VERSION, Ngrok-Version) imported from a JSON
            // `headers`/`requiredHeaders` block. Without this a header default never reaches the
            // wire: the loop above only sets caller-supplied params. A caller value and the
            // auth/transport headers already on `headers` win (we skip names already present);
            // unresolved `{...}` placeholder defaults are never sent.
            for (Map.Entry<String, ParameterMetadata> entry : paramMetadata.entrySet()) {
                ParameterMetadata meta = entry.getValue();
                if (meta == null || !"header".equalsIgnoreCase(meta.parameterType())) {
                    continue;
                }
                String headerName = entry.getKey();
                if (headerName == null || headerName.isBlank() || headers.containsKey(headerName)) {
                    continue;
                }
                if (TRANSPORT_MANAGED_HEADERS.contains(headerName.toLowerCase(java.util.Locale.ROOT))) {
                    continue;
                }
                String def = meta.defaultValue();
                if (def == null) {
                    continue;
                }
                def = def.replaceAll("[\\r\\n]", "").trim();
                if (def.isEmpty() || def.indexOf('{') >= 0 || def.indexOf('}') >= 0) {
                    continue;
                }
                headers.set(headerName, def);
                log.debug("[HttpExecutionService.applyHeaderParameters] Injected header default: {}", headerName);
            }
        } catch (Exception e) {
            log.warn("Error applying header parameters: {}", e.getMessage());
        }
    }

    /**
     * Prepares HTTP headers
     */
    public HttpHeaders prepareHeaders(ApiEntity api, ApiToolEntity tool) {
        HttpHeaders headers = new HttpHeaders();

        // API authentication headers
        if (api.getAuthType() != null && !"none".equals(api.getAuthType())) {
            if (api.getAuthHeaderName() != null && api.getAuthHeaderValue() != null) {
                headers.add(api.getAuthHeaderName(), encryptionService.decrypt(api.getAuthHeaderValue()));
            }
        }

        // Default headers
        headers.add("Content-Type", "application/json");
        headers.add("Accept", "application/json");

        return headers;
    }

    /**
     * Prepares request body for POST/PUT/PATCH.
     * Only includes parameters that have parameterType = 'body' (or null for legacy compatibility).
     * Converts values to their expected types (e.g., array conversion).
     */
    public Object prepareRequestBody(ApiToolEntity tool, JsonNode parameters) {
        if ("GET".equals(tool.getMethod()) || "DELETE".equals(tool.getMethod())) {
            return null;
        }

        try {
            if (parameters != null && parameters.isArray()) {
                Map<String, Object> body = new HashMap<>();

                // Load parameter metadata to check parameterType and dataType
                Map<String, ParameterMetadata> paramMetadata = loadParameterMetadata(tool.getId());

                // Extract path parameter names from endpoint (for fallback)
                Set<String> pathParamNames = new HashSet<>();
                String originalEndpoint = tool.getEndpoint();
                if (originalEndpoint != null) {
                    Pattern pattern = Pattern.compile("\\{([^}]+)\\}");
                    Matcher matcher = pattern.matcher(originalEndpoint);
                    while (matcher.find()) {
                        pathParamNames.add(matcher.group(1));
                    }
                }

                // First pass: detect inlineBody passthrough (at most one per endpoint).
                // When set, the param's converted value IS the entire JSON body - no wrapping.
                for (JsonNode param : parameters) {
                    if (!param.fieldNames().hasNext()) continue; // skip empty {} from null values
                    String paramName = param.fieldNames().next();
                    ParameterMetadata meta = paramMetadata.get(paramName);
                    if (meta == null || !meta.inlineBody()) continue;
                    if (!"body".equalsIgnoreCase(meta.parameterType())) continue;

                    Object rawValue = param.get(paramName);
                    Object convertedValue = convertToExpectedType(rawValue, meta.dataType());
                    // Workflow form inputs and SpEL-rendered expressions arrive as strings even
                    // when the upstream API expects typed scalars (Apify Actors validate strictly:
                    // "Field input.maxItems must be integer"). Walk the inline-body tree once and
                    // coerce stringified ints/numbers/booleans to their JSON-typed forms. Strict
                    // patterns only - anything ambiguous (leading zeros, '+' prefix, decimals
                    // without digits) is left as a string to avoid corrupting IDs/zip codes/phones.
                    convertedValue = coerceInlineBodyScalars(convertedValue);
                    log.debug("[HttpExecutionService.prepareRequestBody] Inline body passthrough via param '{}'", paramName);

                    String bodyTransformInline = getBodyTransformType(tool);
                    if (bodyTransformInline != null && convertedValue instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> asMap = (Map<String, Object>) convertedValue;
                        return applyBodyTransform(asMap, bodyTransformInline);
                    }
                    return convertedValue;
                }

                for (JsonNode param : parameters) {
                    if (!param.fieldNames().hasNext()) continue; // skip empty {} from null values
                    String paramName = param.fieldNames().next();
                    ParameterMetadata meta = paramMetadata.get(paramName);

                    // Determine if this parameter should be in body
                    boolean isBodyParam;
                    if (meta != null && meta.parameterType() != null) {
                        // Use parameterType from database
                        isBodyParam = "body".equalsIgnoreCase(meta.parameterType());
                    } else {
                        // Legacy fallback: include if not a path param (for POST/PUT/PATCH)
                        isBodyParam = !pathParamNames.contains(paramName);
                    }

                    if (isBodyParam) {
                        Object rawValue = param.get(paramName);
                        String dataType = (meta != null) ? meta.dataType() : null;

                        // Convert to expected type (handles array conversion)
                        Object convertedValue = convertToExpectedType(rawValue, dataType);

                        // Use bodyPath for nested placement / array indexing / array-mapping
                        // (e.g., "properties.title", "requests[0].addSheet.x",
                        //  "message.toRecipients[].emailAddress.address").
                        String bodyPath = (meta != null) ? meta.bodyPath() : null;
                        if (BodyPathParser.isStructuredPath(bodyPath)) {
                            setNestedValue(body, bodyPath, convertedValue);
                            log.debug("[HttpExecutionService.prepareRequestBody] Added structured body param: {} -> {}={}", paramName, bodyPath, convertedValue);
                        } else {
                            body.put(bodyPath != null ? bodyPath : paramName, convertedValue);
                            log.debug("[HttpExecutionService.prepareRequestBody] Added body param: {}={} (dataType={}, converted={})",
                                paramName, rawValue, dataType, convertedValue);
                        }
                    }
                }

                // Check if body transformation is required (e.g., RFC 2822 for Gmail)
                String bodyTransform = getBodyTransformType(tool);
                if (bodyTransform != null) {
                    return applyBodyTransform(body, bodyTransform);
                }

                return body.isEmpty() ? null : body;
            }
        } catch (Exception e) {
            log.warn("Error processing body: {}", e.getMessage());
        }

        return null;
    }

    /**
     * Get body transform type from tool's runtime_metadata.
     * Returns null if no transformation is needed.
     */
    public String getBodyTransformType(ApiToolEntity tool) {
        String runtimeMetadata = tool.getRuntimeMetadata();
        if (runtimeMetadata == null || runtimeMetadata.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(runtimeMetadata);
            String transform = node.path("bodyTransform").asText(null);
            return (transform != null && !transform.isBlank()) ? transform : null;
        } catch (Exception e) {
            log.debug("Failed to parse runtime_metadata for bodyTransform: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Body transformations that require imperative Java code because they cannot be
     * expressed declaratively via per-param bodyPath. Adding a value here REQUIRES:
     *   1. A corresponding case in {@link #applyBodyTransform(Map, String)} below.
     *   2. A justification comment of why the transform cannot be a bodyPath.
     *   3. An entry in scripts/api-migrations/validate_apis.py BODY_TRANSFORM_ALLOW_LIST.
     * Symmetric guards: BodyTransformImplementationGuardTest pins JSON->Java; BodyTransformJavaOrphanGuardTest pins Java->JSON.
     */
    public static final java.util.Set<String> IMPLEMENTED_BODY_TRANSFORMS = java.util.Set.of(
            "rfc2822",
            "rfc2822_draft"
    );

    public Object applyBodyTransform(Map<String, Object> body, String transformType) {
        return switch (transformType) {
            case "rfc2822" -> buildRfc2822Body(body);
            case "rfc2822_draft" -> buildRfc2822DraftBody(body);
            default -> {
                log.warn("Unknown body transform type: {} - falling back to body unchanged. "
                        + "Declare the transform in HttpExecutionService.IMPLEMENTED_BODY_TRANSFORMS "
                        + "or express it via per-param bodyPath.", transformType);
                yield body;
            }
        };
    }

    /**
     * Build RFC 2822 message body from user-friendly parameters.
     * Used for email APIs like Gmail send_message.
     */
    public Map<String, String> buildRfc2822Body(Map<String, Object> params) {
        StringBuilder message = new StringBuilder();

        // Extract parameters
        String to = getStringParam(params, "to");
        String subject = getStringParam(params, "subject");
        String body = getStringParam(params, "body");
        String cc = getStringParam(params, "cc");
        String bcc = getStringParam(params, "bcc");
        String replyTo = getStringParam(params, "replyTo");
        boolean isHtml = Boolean.parseBoolean(getStringParam(params, "isHtml"));

        // Build RFC 2822 headers
        if (to != null && !to.isBlank()) {
            message.append("To: ").append(to).append("\r\n");
        }
        if (cc != null && !cc.isBlank()) {
            message.append("Cc: ").append(cc).append("\r\n");
        }
        if (bcc != null && !bcc.isBlank()) {
            message.append("Bcc: ").append(bcc).append("\r\n");
        }
        if (replyTo != null && !replyTo.isBlank()) {
            message.append("Reply-To: ").append(replyTo).append("\r\n");
        }
        if (subject != null) {
            message.append("Subject: ").append(subject).append("\r\n");
        }

        // Content type header
        if (isHtml) {
            message.append("Content-Type: text/html; charset=utf-8\r\n");
        } else {
            message.append("Content-Type: text/plain; charset=utf-8\r\n");
        }

        // Empty line separates headers from body
        message.append("\r\n");

        // Body
        if (body != null) {
            message.append(body);
        }

        // Encode to Base64url (Gmail requires URL-safe Base64)
        String rawMessage = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(message.toString().getBytes(StandardCharsets.UTF_8));

        return Map.of("raw", rawMessage);
    }

    /**
     * Build RFC 2822 draft body. Wraps the RFC 2822 message in a draft structure.
     * Used for email APIs like Gmail create_draft.
     */
    public Map<String, Object> buildRfc2822DraftBody(Map<String, Object> params) {
        // Build the RFC 2822 message
        Map<String, String> messageBody = buildRfc2822Body(params);

        // Wrap in draft structure: { "message": { "raw": "..." } }
        return Map.of("message", messageBody);
    }

    private String getStringParam(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof com.fasterxml.jackson.databind.JsonNode) {
            return ((com.fasterxml.jackson.databind.JsonNode) value).asText();
        }
        return value.toString();
    }

    // ═════════════════════════════════════════════════════════════════════════════════════
    // Typed execution path (Phases 8/9/10 of the typed-execution refactor)
    // ═════════════════════════════════════════════════════════════════════════════════════

    /**
     * Execute a tool whose {@code execution_spec} declares a non-trivial mode (binary response,
     * multipart upload, or async polling). Reuses ALL the existing helpers - only the request
     * body encoding and the response parsing change.
     *
     * <p>This method is dispatched from {@link com.apimarketplace.catalog.service.ApiService}
     * when the tool's execution spec indicates that the legacy JSON-in / JSON-out path is not
     * sufficient. For sync JSON tools (the vast majority), the legacy
     * {@link #executeHttpCallWithCredentials(ApiEntity, ApiToolEntity, JsonNode, java.util.Set, String, String)}
     * path is unchanged.
     *
     * @param tenantId used to scope binary uploads and fileRef downloads in MinIO
     */
    public Map<String, Object> executeHttpCallTyped(ApiEntity api,
                                                    ApiToolEntity tool,
                                                    JsonNode parameters,
                                                    java.util.Set<String> allowedParamNames,
                                                    String userId,
                                                    String credentialName,
                                                    String tenantId) {
        // Captured early; injected into success result maps below for downstream
        // billing dispatch (see executeHttpCallWithCredentials for full rationale).
        String resolvedCredentialSource = null;
        try {
            log.info("[HttpExecutionService.executeTyped] Tool: {}, mode={}, userId={}",
                tool.getId(), tool.getExecutionMode(), userId);

            JsonNode executionSpec = parseExecutionSpec(tool.getExecutionSpec());
            String mode       = executionSpec.path("mode").asText("sync");
            String bodyType   = executionSpec.path("request").path("bodyType").asText("json");
            String responseType = executionSpec.path("response").path("type").asText("json");

            // V52 mode validation - fail-fast on unsupported modes instead of silently
            // routing them to the legacy sync path. Allowed: sync | async_poll | streaming
            // | upload (alias for sync+multipart). The 'webhook' mode was retired in V145
            // and is now rejected here as an unknown mode.
            if (!"sync".equals(mode) && !"async_poll".equals(mode)
                    && !"streaming".equals(mode) && !"upload".equals(mode)) {
                log.error("[HttpExecutionService.executeTyped] Tool {} declares unknown mode='{}'", tool.getId(), mode);
                return failure(0, "Unknown execution.mode: " + mode, tool);
            }

            // 1. Filter parameters
            JsonNode filteredParameters = filterParametersByToolDefinition(tool, parameters, allowedParamNames);

            // 2. Build URL - same helpers as legacy path
            String url = buildFullUrl(api, tool);
            url = processPathParameters(url, tool, filteredParameters);

            // Dynamic-URL endpoints ({upload_url}, {media_url}) let a runtime parameter choose
            // the request host - enforce their dedicated constraints (no residual placeholders,
            // declared host allow-list, SSRF check). No-op for fixed-host endpoints, so every
            // existing typed tool keeps its prior behaviour.
            enforceDynamicUrlConstraints(tool, url);

            url = processQueryParameters(url, tool, filteredParameters);

            // 3. Credential injection (URL/header/query) - V103 variant-aware.
            String variant = resolveCredentialVariant(userId, credentialName, api);
            CredentialInjection injection = getCredentialInjection(tool.getId(), variant);
            Optional<CredentialResolution> credResolution = tryGetCredentialResolution(userId, credentialName, api);
            Optional<String> credentialValue = credResolution.map(CredentialResolution::value);
            if (credResolution.isPresent()) {
                resolvedCredentialSource = credResolution.get().source().name().toLowerCase();
            }
            credentialValue = applyFieldAwareFallback(injection, credentialValue, userId, credentialName);
            // Generic sub-resource token resolution (e.g. Facebook Page token) - same hook as the
            // legacy path; no-op unless the tool declares the rule and the call carries the trigger.
            credentialValue = resolveSubResourceToken(api, tool, filteredParameters, credentialValue, userId, credentialName);
            if (injection != null && credentialValue.isPresent() && "query".equalsIgnoreCase(injection.type())) {
                url += (url.contains("?") ? "&" : "?") + injection.key() + "=" +
                       URLEncoder.encode(credentialValue.get(), StandardCharsets.UTF_8);
            }
            if (url.contains("{") && url.contains("}")) {
                url = replaceUrlTemplateVariables(url, userId, credentialName, credentialValue.orElse(null));
            }

            HttpHeaders headers = prepareHeadersWithCredentials(api, tool, userId, credentialName, injection, credentialValue);
            // Multi-field custom auth (≥2 header fields in customConfig) - apply every header.
            applyCustomFieldHeaderInjections(headers, injection, userId, credentialName, credentialValue);
            applyHeaderParameters(headers, tool, filteredParameters);

            // 4. Build body - JSON / multipart / form_urlencoded / raw_binary.
            //    Content-Type is set explicitly per bodyType; prior default ("application/json")
            //    set by prepareHeadersWithCredentials is overwritten via setContentType.
            Object body;
            if ("multipart".equals(bodyType)) {
                if (multipartBodyEncoder == null) {
                    return failure(0, "Multipart body encoder not available", tool);
                }
                Map<String, Object> paramsMap = jsonNodeToFlatMap(filteredParameters);
                body = multipartBodyEncoder.encode(executionSpec.path("request").path("multipartFields"), paramsMap, tenantId);
                // Spring needs the right Content-Type for multipart so the boundary is set automatically.
                headers.setContentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA);
            } else if ("multipart_related".equals(bodyType) || "multipart-related".equals(bodyType)) {
                // multipart/related: JSON metadata part + binary media part. Required by
                // Google media-upload endpoints (YouTube videos.insert uploadType=multipart),
                // which reject the multipart/form-data produced by bodyType=multipart.
                if (multipartRelatedBodyEncoder == null) {
                    return failure(0, "Multipart-related body encoder not available", tool);
                }
                Map<String, Object> paramsMap = jsonNodeToFlatMap(filteredParameters);
                var related = multipartRelatedBodyEncoder.encode(executionSpec.path("request"), paramsMap, tenantId);
                if (related == null) {
                    return failure(0, "Failed to build multipart/related body (missing media fileRef or metadata)", tool);
                }
                body = related.body();
                // Boundary is generated by the encoder and MUST be echoed in the Content-Type.
                headers.setContentType(org.springframework.http.MediaType.parseMediaType(
                    "multipart/related; boundary=" + related.boundary()));
            } else if ("form_urlencoded".equals(bodyType) || "form-urlencoded".equals(bodyType)) {
                if (formUrlencodedBodyEncoder == null) {
                    return failure(0, "Form-urlencoded body encoder not available", tool);
                }
                Object prepared = prepareRequestBody(tool, filteredParameters);
                @SuppressWarnings("unchecked")
                Map<String, Object> bodyMap = (prepared instanceof Map<?, ?>) ? (Map<String, Object>) prepared : java.util.Map.of();
                body = formUrlencodedBodyEncoder.encode(bodyMap);
                headers.setContentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED);
            } else if ("raw_binary".equals(bodyType) || "raw".equals(bodyType)) {
                if (rawBinaryBodyEncoder == null) {
                    return failure(0, "Raw binary body encoder not available", tool);
                }
                Object prepared = prepareRequestBody(tool, filteredParameters);
                @SuppressWarnings("unchecked")
                Map<String, Object> bodyMap = (prepared instanceof Map<?, ?>) ? (Map<String, Object>) prepared : java.util.Map.of();
                JsonNode requestSpec = executionSpec.path("request");
                body = rawBinaryBodyEncoder.encode(requestSpec, bodyMap, tenantId);
                String declaredCt = rawBinaryBodyEncoder.resolveContentType(requestSpec);
                try {
                    headers.setContentType(org.springframework.http.MediaType.parseMediaType(declaredCt));
                } catch (org.springframework.util.InvalidMimeTypeException e) {
                    log.warn("[HttpExecutionService] raw_binary tool {} declared invalid contentType '{}', falling back to application/octet-stream",
                        tool.getId(), declaredCt);
                    headers.setContentType(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM);
                }
            } else if ("graphql".equals(bodyType)) {
                if (graphqlBodyEncoder == null) {
                    return failure(0, "GraphQL body encoder not available", tool);
                }
                JsonNode graphqlCfg = executionSpec.path("request").path("graphql");
                String query = graphqlCfg.path("query").asText(null);
                if (query == null || query.isBlank()) {
                    return failure(0,
                        "execution.request.graphql.query is required for bodyType=graphql", tool);
                }
                String operationName = graphqlCfg.path("operationName").asText(null);
                Map<String, Object> paramsMap = jsonNodeToFlatMap(filteredParameters);
                body = graphqlBodyEncoder.encode(query, operationName, paramsMap, graphqlCfg);
                headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            } else {
                body = prepareRequestBody(tool, filteredParameters);
            }

            // 4b. AWS Signature V4 - applied after body is finalized (signature is computed over
            // the exact bytes). Triggered when the host matches *.amazonaws.com AND the request
            // uses credentials with the AWS field set (access_key_id + secret_access_key).
            maybeSignAws(tool, url, headers, body, userId, credentialName);

            HttpEntity<Object> request = new HttpEntity<>(body, headers);
            log.info("[HttpExecutionService.executeTyped] {} {} (bodyType={}, responseType={}, mode={})",
                tool.getMethod(), url, bodyType, responseType, mode);

            // 5a. Streaming mode: aggregate the SSE chunks via WebClient (separate transport
            // from the RestTemplate path because RestTemplate cannot consume SSE incrementally).
            // The aggregated response is then projected by ToolExecutionManager just like sync.
            if ("streaming".equals(mode)) {
                if (streamingResponseHandler == null) {
                    return failure(0, "Streaming response handler not available", tool);
                }
                Map<String, Object> aggregated = streamingResponseHandler.handle(
                        url,
                        HttpMethod.valueOf(tool.getMethod()),
                        headers,
                        body
                );
                Map<String, Object> result = new HashMap<>();
                result.put("success", aggregated.get("error") == null);
                result.put("status", aggregated.get("error") == null ? 200 : 0);
                result.put("httpStatus", buildHttpStatus(aggregated.get("error") == null ? 200 : 0,
                        (String) aggregated.get("error")));
                result.put("data", aggregated);
                result.put("headers", Map.of());
                if (resolvedCredentialSource != null) result.put("credentialSource", resolvedCredentialSource);
                return result;
            }

            // 5b. Issue the request - branch on response type to pick the right Class<?>
            if ("binary".equals(responseType)) {
                return executeBinaryResponse(url, tool, request, executionSpec, tenantId, resolvedCredentialSource);
            }

            ResponseEntity<Object> response = restTemplate.exchange(
                java.net.URI.create(url),
                HttpMethod.valueOf(tool.getMethod()),
                request,
                Object.class
            );
            int statusCode = response.getStatusCode().value();
            Object responseBody = response.getBody() != null ? response.getBody() : Map.of();

            // 6. async_poll: treat the body as the submit response and poll the upstream
            if ("async_poll".equals(mode)) {
                if (asyncPollExecutor == null) {
                    return failure(0, "Async poll executor not available", tool);
                }
                try {
                    Object resolved = asyncPollExecutor.pollUntilDone(
                        api.getBaseUrl(),
                        objectMapper.valueToTree(responseBody),
                        executionSpec.path("async"),
                        headers
                    );
                    Map<String, Object> result = new HashMap<>();
                    result.put("success", true);
                    result.put("status", statusCode);
                    result.put("httpStatus", buildHttpStatus(statusCode, null));
                    result.put("data", resolved == null ? Map.of() : resolved);
                    result.put("headers", response.getHeaders().toSingleValueMap());
                    if (resolvedCredentialSource != null) result.put("credentialSource", resolvedCredentialSource);
                    return result;
                } catch (com.apimarketplace.catalog.service.execution.AsyncPollExecutor.AsyncPollFailureException ape) {
                    return failure(0, ape.getMessage(), tool);
                }
            }

            // 6b. GraphQL auto-unwrap - translate the {data, errors} envelope into either:
            //     - failure(0, ...) when `errors` is non-empty (semantic failure even if HTTP 200)
            //     - the inner `data` payload (so downstream OutputProjector sees `data.x.y`
            //       referenced as `output.x.y`, matching how authors typically write outputSchema)
            // Non-Map responses (e.g. an HTML error page from a misconfigured proxy) pass through
            // unchanged so the existing diagnostic surface is preserved.
            if ("graphql".equals(bodyType) && responseBody instanceof Map<?, ?> bodyMap) {
                Object errorsField = bodyMap.get("errors");
                if (errorsField instanceof java.util.List<?> errorsList && !errorsList.isEmpty()) {
                    String errorsStr;
                    try {
                        errorsStr = objectMapper.writeValueAsString(errorsField);
                    } catch (com.fasterxml.jackson.core.JsonProcessingException jpe) {
                        errorsStr = errorsField.toString();
                    }
                    return failure(0, "GraphQL errors: " + errorsStr, tool);
                }
                Object dataField = bodyMap.get("data");
                if (dataField != null) {
                    responseBody = dataField;
                }
            }

            // 7. plain JSON / sync / upload
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("status", statusCode);
            result.put("httpStatus", buildHttpStatus(statusCode, null));
            result.put("data", responseBody);
            result.put("headers", response.getHeaders().toSingleValueMap());
            if (resolvedCredentialSource != null) result.put("credentialSource", resolvedCredentialSource);
            return result;

        } catch (org.springframework.web.client.HttpStatusCodeException httpEx) {
            int statusCode = httpEx.getStatusCode().value();
            String errorMessage = httpEx.getResponseBodyAsString();
            log.error("[HttpExecutionService.executeTyped] HTTP error: status={}, error={}", statusCode, errorMessage);
            return failure(statusCode, errorMessage, tool);
        } catch (Exception e) {
            log.error("[HttpExecutionService.executeTyped] Error: {}", e.getMessage(), e);
            return failure(0, e.getMessage() != null ? e.getMessage() : "Unknown error", tool);
        }
    }

    /**
     * Subcase of executeTyped: response.type=binary. Calls the upstream with byte[].class
     * and uploads the bytes via {@link com.apimarketplace.catalog.service.execution.BinaryResponseHandler}.
     */
    private Map<String, Object> executeBinaryResponse(String url,
                                                      ApiToolEntity tool,
                                                      HttpEntity<Object> request,
                                                      JsonNode executionSpec,
                                                      String tenantId,
                                                      String resolvedCredentialSource) {
        if (binaryResponseHandler == null) {
            return failure(0, "Binary response handler not available", tool);
        }
        ResponseEntity<byte[]> response = restTemplate.exchange(
            java.net.URI.create(url),
            HttpMethod.valueOf(tool.getMethod()),
            request,
            byte[].class
        );
        int statusCode = response.getStatusCode().value();
        byte[] bytes = response.getBody();
        String contentType = response.getHeaders().getFirst("Content-Type");

        Map<String, Object> projectedFile = binaryResponseHandler.handle(
            bytes, contentType, tenantId, tool.getOutputSchema(), tool.getToolSlug()
        );

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("status", statusCode);
        result.put("httpStatus", buildHttpStatus(statusCode, null));
        result.put("data", projectedFile);
        result.put("headers", response.getHeaders().toSingleValueMap());
        if (resolvedCredentialSource != null) result.put("credentialSource", resolvedCredentialSource);
        return result;
    }

    /** Parse a tool's execution_spec JSON, returning an empty MissingNode if absent/malformed. */
    private JsonNode parseExecutionSpec(String executionSpecJson) {
        if (executionSpecJson == null || executionSpecJson.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(executionSpecJson);
        } catch (Exception e) {
            log.warn("[HttpExecutionService.executeTyped] Failed to parse execution_spec: {}", e.getMessage());
            return objectMapper.createObjectNode();
        }
    }

    /** Build a uniform failure result. */
    private Map<String, Object> failure(int statusCode, String error, ApiToolEntity tool) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("status", statusCode);
        result.put("httpStatus", buildHttpStatus(statusCode, error));
        result.put("data", Map.of());
        result.put("error", error);
        return result;
    }

    /** Convert {@code [{"k":"v"},{"k2":"v2"}]} or {@code {"k":"v"}} to a flat Map<String,Object>. */
    private Map<String, Object> jsonNodeToFlatMap(JsonNode parameters) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        if (parameters == null || parameters.isMissingNode() || parameters.isNull()) return out;
        if (parameters.isArray()) {
            for (JsonNode element : parameters) {
                if (element.isObject()) {
                    element.fields().forEachRemaining(e -> out.put(e.getKey(), objectMapper.convertValue(e.getValue(), Object.class)));
                }
            }
        } else if (parameters.isObject()) {
            parameters.fields().forEachRemaining(e -> out.put(e.getKey(), objectMapper.convertValue(e.getValue(), Object.class)));
        }
        return out;
    }

    /**
     * Returns true when the tool's execution_spec requires the typed path
     * (anything beyond a plain JSON sync request).
     *
     * <p>The typed path is needed when ANY of the following holds:
     * <ul>
     *   <li>{@code mode != "sync"} (async_poll, streaming, upload)</li>
     *   <li>{@code response.type = "binary"} (BinaryResponseHandler)</li>
     *   <li>{@code request.bodyType} requires a dedicated encoder
     *       (multipart, graphql, form_urlencoded, raw_binary)</li>
     * </ul>
     * The legacy {@code ApiService} path only knows how to JSON-serialize the params
     * map; routing graphql/form_urlencoded/raw_binary there would silently drop the
     * encoder and send the body as plain JSON.
     */
    public boolean needsTypedExecutionPath(ApiToolEntity tool) {
        String specJson = tool.getExecutionSpec();
        if (specJson == null || specJson.isBlank()) return false;
        try {
            JsonNode spec = objectMapper.readTree(specJson);
            String mode = spec.path("mode").asText("sync");
            String bodyType = spec.path("request").path("bodyType").asText("json");
            String responseType = spec.path("response").path("type").asText("json");
            return !"sync".equals(mode)
                || "binary".equals(responseType)
                || "multipart".equals(bodyType)
                || "multipart_related".equals(bodyType)
                || "multipart-related".equals(bodyType)
                || "graphql".equals(bodyType)
                || "form_urlencoded".equals(bodyType)
                || "raw_binary".equals(bodyType);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Apply AWS Signature V4 to the pending request when the host is an AWS service host and
     * the user's credential has {@code access_key_id} + {@code secret_access_key} fields.
     *
     * <p>Called after the body is finalized so the SHA-256 payload hash is computed on the exact
     * bytes that will be sent over the wire (canonical request → string-to-sign dependence).
     *
     * <p>Body object is serialized to the same bytes Spring's RestTemplate will send:
     * <ul>
     *   <li>{@code byte[]} → direct use</li>
     *   <li>{@code String} → UTF-8 bytes</li>
     *   <li>{@code MultiValueMap<String,String>} (form_urlencoded) → URL-encoded form string</li>
     *   <li>{@link Map} / POJO → JSON via {@code objectMapper}</li>
     *   <li>{@code null} or multipart → empty body hash (matches AWS's signature expectation
     *       for streams where the hash cannot be precomputed; this falls back to the UNSIGNED_PAYLOAD
     *       convention on AWS SNS/SQS which does NOT accept multipart, so not an issue in practice)</li>
     * </ul>
     */
    private void maybeSignAws(ApiToolEntity tool, String url, HttpHeaders headers,
                              Object body, String userId, String credentialName) {
        if (awsSigV4Signer == null) return;
        try {
            java.net.URI uri = java.net.URI.create(url);
            String host = uri.getHost();
            if (host == null
                || (!host.endsWith(".amazonaws.com") && !host.endsWith(".amazonaws.com.cn"))) {
                return;
            }
            Map<String, String> credentialFields = getCredentialDataMapForUserSelection(userId, credentialName);
            if (credentialFields == null
                || credentialFields.get("access_key_id") == null
                || credentialFields.get("secret_access_key") == null) {
                log.debug("[HttpExecutionService] AWS host {} but no access_key_id/secret_access_key in credential - skipping SigV4", host);
                return;
            }
            // Temporary STS credentials carry a session token that MUST travel as
            // x-amz-security-token. The signer signs every x-amz-* header, so setting it here
            // automatically folds it into the SignedHeaders/signature. Long-lived IAM keys omit it.
            String sessionToken = credentialFields.get("session_token");
            if (sessionToken == null || sessionToken.isBlank()) {
                sessionToken = credentialFields.get("aws_session_token");
            }
            if (sessionToken != null && !sessionToken.isBlank()) {
                headers.set("x-amz-security-token", sessionToken.strip());
            }
            byte[] bodyBytes = serializeBodyForSigning(body);
            awsSigV4Signer.sign(tool.getMethod(), url, headers, bodyBytes, credentialFields);
        } catch (Exception e) {
            log.warn("[HttpExecutionService] AWS SigV4 signing failed for tool {}: {} - falling through unsigned", tool.getId(), e.getMessage());
        }
    }

    /** Serialize the request body object into the bytes that will be transmitted over the wire. */
    private byte[] serializeBodyForSigning(Object body) {
        if (body == null) return new byte[0];
        if (body instanceof byte[] arr) return arr;
        if (body instanceof String s) return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (body instanceof org.springframework.util.MultiValueMap<?, ?> mvm) {
            // Form-urlencoded serialization matches Spring's FormHttpMessageConverter:
            // join pairs with '&', URL-encode each key/value.
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<?, ?> entry : mvm.entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object vals = entry.getValue();
                if (vals instanceof java.util.List<?> list) {
                    for (Object v : list) {
                        if (sb.length() > 0) sb.append('&');
                        sb.append(java.net.URLEncoder.encode(key, java.nio.charset.StandardCharsets.UTF_8))
                          .append('=')
                          .append(java.net.URLEncoder.encode(String.valueOf(v), java.nio.charset.StandardCharsets.UTF_8));
                    }
                }
            }
            return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        // Fallback: JSON-serialize the body
        try {
            return objectMapper.writeValueAsBytes(body);
        } catch (Exception e) {
            log.warn("[HttpExecutionService] Failed to serialize body for SigV4 signing: {}", e.getMessage());
            return new byte[0];
        }
    }
}
