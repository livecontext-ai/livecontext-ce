package com.apimarketplace.auth.credential.service;

import com.apimarketplace.auth.credential.domain.CredentialModels.*;
import com.apimarketplace.auth.credential.domain.OAuth2Models.*;
import com.apimarketplace.auth.credential.domain.OAuth2ProviderConfig;
import com.apimarketplace.auth.credential.domain.PlatformCredentialModels;
import com.apimarketplace.auth.credential.metrics.OAuth2RefreshMetrics;
import com.apimarketplace.auth.credential.service.oauth2.refresh.LogSafeBody;
import com.apimarketplace.auth.credential.service.oauth2.refresh.RefreshBackoff;
import com.apimarketplace.auth.credential.service.oauth2.refresh.RefreshErrorBucket;
import com.apimarketplace.auth.credential.service.oauth2.refresh.RefreshErrorClassifier;
import com.apimarketplace.auth.credential.service.oauth2.refresh.RefreshTerminalException;
import com.apimarketplace.auth.credential.service.oauth2.refresh.RefreshTransientException;
import com.apimarketplace.common.security.CredentialEncryptionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Orchestrator for the OAuth2 authorization-code flow for platform credentials.
 *
 * <p>This class owns the end-to-end user journey - initiate, callback, refresh - but delegates
 * all OAuth2 wire-protocol work to {@link OAuth2Engine} and PKCE generation to
 * {@link PkceService}. It is intentionally thin: catalog lookup, platform-credential resolution,
 * Redis state management, credential persistence, and frontend redirect building. No provider
 * branching lives here.
 */
@Service
public class OAuth2Service {

    private static final Logger log = LoggerFactory.getLogger(OAuth2Service.class);
    private static final long STATE_TTL_MINUTES = 10;
    private static final String REDIS_STATE_PREFIX = "oauth2:state:";
    /**
     * Per-credential lock held while a refresh is in flight. Blocks the scheduler's
     * proactive path from colliding with a user-initiated force-refresh on rotating
     * providers (Xero/HubSpot/Zoom) where the second caller would invalidate the first
     * caller's refresh_token mid-flight. 60 s is comfortably longer than any token
     * endpoint round-trip; the scheduler retries on the next tick.
     */
    private static final String REDIS_REFRESH_LOCK_PREFIX = "oauth2:refresh-lock:";
    // 180s covers the p99 of OAuth2 token-endpoint round-trips we've observed
    // (Keycloak under load + slow SaaS providers like Salesforce sandboxes).
    // Shorter TTLs invite the Lampson-lock failure where caller A's lock
    // expires mid-refresh, caller B acquires, A finishes and would otherwise
    // delete B's lock. We still guard with compare-and-delete below.
    private static final Duration REFRESH_LOCK_TTL = Duration.ofSeconds(180);

    /**
     * Redis mirror of {@code credential.status in (error, needs_reauth)}. Low-latency fast-path
     * so 99 parallel callers hitting a credential whose refresh_token was just revoked don't each
     * pay a DB round-trip to learn "don't try." Value is {@code terminal_user} or
     * {@code terminal_config}; TTL matches {@link #REFRESH_DISABLED_TTL}. DB remains source of
     * truth if this key is evicted or Redis is partitioned.
     */
    // Public so peer services in the same package (CredentialService.revokeForByokDelete)
    // can invalidate the sentinels for terminal-state transitions they own without a
    // circular dependency back into OAuth2Service.
    public static final String REDIS_REFRESH_DISABLED_PREFIX = "oauth2:refresh-disabled:";

    /**
     * Redis mirror of {@code credential_data.refresh_cooldown_until}. Same rationale as
     * {@link #REDIS_REFRESH_DISABLED_PREFIX}: avoid deserializing the full credential JSONB on
     * every fast-path check during a provider outage. TTL = remaining cooldown duration.
     */
    public static final String REDIS_REFRESH_COOLDOWN_PREFIX = "oauth2:refresh-cooldown:";
    private static final String TEMPLATE_ID_FIELD = "credential_template_id";
    /**
     * credential_data marker set at connect time for providers renewed via a non-RFC
     * access-token grant (Meta family - no refresh_token exists). The refresh scheduler's
     * SQL predicate selects on this marker since it cannot read the template config.
     */
    static final String REFRESH_MODE_FIELD = "refresh_mode";
    static final String REFRESH_MODE_ACCESS_TOKEN = "access_token";
    private static final String TEMPLATE_KEY_FIELD = "credential_template_key";
    private static final String TEMPLATE_ICON_SLUG_FIELD = "credential_template_icon_slug";
    private static final String TEMPLATE_VARIANT_FIELD = "credential_template_variant";

    /**
     * How long the terminal sentinel lives before re-allowing a refresh attempt. Matches the
     * initial jitter window so a freshly terminal credential cannot be probed for ~15 min even
     * if the DB status flip is delayed. Re-setting happens on every terminal persist, so the
     * sentinel effectively lives forever as long as the credential stays terminal.
     */
    private static final Duration REFRESH_DISABLED_TTL = Duration.ofMinutes(15);

    // Lua compare-and-delete: only release the lock if we still own it
    // (the stored value matches the UUID we wrote at acquisition). Prevents
    // the ABA race when a TTL-expired lock is re-acquired by a second caller.
    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final CredentialService credentialService;
    private final PlatformCredentialService platformCredentialService;
    private final WebClient catalogClient;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final CredentialEncryptionService encryptionService;
    private final StringRedisTemplate redisTemplate;
    private final OAuth2Engine engine;
    private final PkceService pkceService;
    private final RefreshErrorClassifier errorClassifier;
    private final RefreshBackoff backoff;
    private final OAuth2RefreshMetrics refreshMetrics;

    @Value("${oauth2.callback-url:http://localhost:8083/api/credentials/oauth2/callback}")
    private String callbackUrl;

    @Value("${oauth2.frontend-url:http://localhost:3000}")
    private String frontendUrl;

    /**
     * Deployment auth mode - {@code embedded} = CE monolith, {@code keycloak} = Cloud.
     * Drives the CE runtime branch in {@link #initiate}: CE has no platform-shared
     * OAuth app, so the platform/BYOK scope split does not apply there.
     */
    @Value("${auth.mode:keycloak}")
    private String authMode;

    public OAuth2Service(
            CredentialService credentialService,
            PlatformCredentialService platformCredentialService,
            CredentialEncryptionService encryptionService,
            StringRedisTemplate redisTemplate,
            @Value("${services.catalog-url:http://localhost:8081}") String catalogServiceUrl,
            ObjectMapper objectMapper,
            OAuth2Engine engine,
            PkceService pkceService,
            RefreshErrorClassifier errorClassifier,
            RefreshBackoff backoff,
            OAuth2RefreshMetrics refreshMetrics
    ) {
        this.credentialService = credentialService;
        this.platformCredentialService = platformCredentialService;
        this.encryptionService = encryptionService;
        this.redisTemplate = redisTemplate;
        this.catalogClient = WebClient.builder()
                .baseUrl(catalogServiceUrl)
                .build();
        this.restTemplate = new RestTemplate();
        this.objectMapper = objectMapper;
        this.engine = engine;
        this.pkceService = pkceService;
        this.errorClassifier = errorClassifier;
        this.backoff = backoff;
        this.refreshMetrics = refreshMetrics;
    }

    /**
     * Initiate OAuth2 flow by generating authorization URL.
     * If client_id/client_secret are not provided, uses platform credentials from configuration.
     */
    /**
     * Legacy entry point - defaults organizationId to null (personal scope).
     * New callers (controllers reading X-Organization-ID) should use the
     * 3-arg overload that captures the active workspace.
     */
    public OAuth2InitiateResponse initiate(OAuth2InitiateRequest request, String userId) {
        return initiate(request, userId, null);
    }

    /**
     * PR19 - capture the active workspace at initiate-time so the resulting
     * credential lands in the correct scope on callback. The {@code state}
     * blob persisted to Redis carries the {@code organizationId} through the
     * OAuth redirect (which may bounce through a third-party domain). Pre-PR19
     * callers that don't pass {@code organizationId} get null (personal scope).
     */
    public OAuth2InitiateResponse initiate(OAuth2InitiateRequest request, String userId, String organizationId) {
        return initiate(request, userId, organizationId, null);
    }

    /**
     * Locale-aware initiate. Forwards the app UI locale (the next-intl locale the user is browsing
     * in) to {@link OAuth2Engine#buildAuthorizationUrl}, so a provider whose template declares a
     * {@link OAuth2Engine#UI_LOCALE_PLACEHOLDER} param (Google {@code hl}) renders its consent
     * screen + scope descriptions in that language. {@code uiLocale} null/blank is a no-op: the
     * provider falls back to the account/browser language exactly as before, and the authorize URL
     * is byte-identical to the pre-locale behaviour.
     */
    public OAuth2InitiateResponse initiate(OAuth2InitiateRequest request, String userId,
                                           String organizationId, String uiLocale) {
        log.info("Initiating OAuth2 flow for user {} with template {}", userId, request.credentialTemplateId());

        // Fetch credential template from catalog
        JsonNode template = fetchCredentialTemplate(request.credentialTemplateId());
        if (template == null) {
            throw new IllegalArgumentException("Credential template not found: " + request.credentialTemplateId());
        }

        // Extract OAuth2 provider configuration (typed, validated).
        OAuth2ProviderConfig providerConfig = extractOAuth2Config(template);

        // Extract template fields
        String iconSlug = template.path("icon_slug").asText(null);
        String iconUrl = template.path("icon_url").asText(null);
        String displayName = template.path("display_name").asText("");

        // For custom APIs: template may not have OAuth URLs. Fall back to platform credential.
        if (providerConfig == null) {
            String credName = template.path("credential_name").asText(null);
            var rawCred = credName != null
                    ? platformCredentialService.getRawCredential(credName, userId, organizationId)
                    : Optional.<PlatformCredentialModels.PlatformCredential>empty();
            if (rawCred.isEmpty() && iconSlug != null) {
                rawCred = platformCredentialService.getRawCredential(iconSlug, userId, organizationId);
            }
            if (rawCred.isPresent()) {
                var pc = rawCred.get();
                if (pc.authUrl() != null && !pc.authUrl().isBlank()
                        && pc.tokenUrl() != null && !pc.tokenUrl().isBlank()) {
                    providerConfig = new OAuth2ProviderConfig(
                            pc.authUrl(), pc.tokenUrl(), null,
                            pc.defaultScopes() != null ? List.of(pc.defaultScopes().split("\\s+")) : List.of(),
                            " ", OAuth2ProviderConfig.AuthMethod.POST, false, Map.of(),
                            OAuth2ProviderConfig.RefreshConfig.STANDARD);
                    log.info("Using platform credential URLs for {} (authUrl={}, tokenUrl={})",
                            credName, pc.authUrl(), pc.tokenUrl());
                }
            }
        }
        if (providerConfig == null) {
            throw new IllegalArgumentException(
                    "Template is not configured for OAuth2: missing authorizationUrl or tokenUrl");
        }

        log.debug("Template fields extracted - iconSlug: '{}', displayName: '{}', iconUrl: '{}'",
                  iconSlug, displayName, iconUrl);

        // Use icon_slug as integration identifier (for credential.integration field)
        String integrationIdentifier = iconSlug != null && !iconSlug.isBlank()
                ? iconSlug
                : displayName;

        log.info("Integration identifier for credential: '{}' (from {})",
                 integrationIdentifier,
                 (iconSlug != null && !iconSlug.isBlank()) ? "icon_slug" : "display_name fallback");

        String integrationName = request.integration() != null
                ? request.integration()
                : displayName;

        // Resolve client credentials (user-provided or platform).
        String clientId;
        String clientSecret;
        PlatformCredentialModels.PlatformCredential platformRow = null;
        if (request.hasUserCredentials()) {
            clientId = request.clientId();
            clientSecret = request.clientSecret();
            log.debug("Using user-provided credentials for {}", integrationName);
        } else {
            Optional<PlatformCredentialModels.PlatformCredential> oauthRow =
                    resolveOAuth2PlatformRow(iconSlug, integrationName, template, userId, organizationId);

            if (oauthRow.isPresent()) {
                platformRow = oauthRow.get();
                clientId = platformRow.clientId();
                clientSecret = platformRow.clientSecret();

                // BYOK (tenant-scoped row): the user brought their own OAuth client, so they are
                // NOT capped by the platform template's narrow scope set. Their own (verified)
                // OAuth app can grant the integration's restricted scopes - request the full set.
                // Without this, a BYOK connect silently dropped to the platform scopes (e.g. Gmail
                // → only send+labels), making BYOK behave identically to the platform flow.
                if (platformRow.tenantId() != null) {
                    List<String> byokScopes = resolveByokScopes(template, providerConfig);
                    if (!byokScopes.isEmpty()) {
                        providerConfig = providerConfig.withScopes(byokScopes);
                        log.info("BYOK OAuth2 for {}: requesting {} catalog scopes (platform + byok-only)",
                                integrationName, byokScopes.size());
                    }
                }
                log.info("Using {} credentials for {} (source: database)",
                        platformRow.tenantId() != null ? "tenant BYOK" : "platform", integrationName);
            } else {
                throw new IllegalArgumentException(
                        "No credentials available for " + integrationName +
                        ". Please configure platform credentials in Settings or provide your own."
                );
            }
        }

        // CE (auth.mode=embedded): there is no platform-shared OAuth app - whatever supplied
        // the client_id/secret (inline user credentials, or the install-wide row the operator
        // saved in Settings, which is tenant-less so the tenant-BYOK branch above never fires),
        // the OAuth client is the user's own. The platform/BYOK scope split is a Cloud concept
        // (shared verified consent screen vs restricted scopes); in CE it is meaningless, so
        // always request the full catalog scope set (template scopes ∪ byokOnlyScopes).
        // No-op for templates without byokOnlyScopes and for connects the tenant-BYOK branch
        // already widened.
        if (isCeEmbeddedMode()) {
            List<String> fullScopes = resolveByokScopes(template, providerConfig);
            if (!fullScopes.isEmpty() && !fullScopes.equals(providerConfig.scopes())) {
                providerConfig = providerConfig.withScopes(fullScopes);
                log.info("CE OAuth2 for {}: requesting full catalog scope set ({} scopes, platform + byok-only)",
                        integrationName, fullScopes.size());
            }
        }

        // Generate PKCE challenge if the provider requires it.
        PkceService.PkceChallenge pkce = engine.shouldUsePkce(providerConfig)
                ? pkceService.generate()
                : null;

        // Generate state token
        String state = UUID.randomUUID().toString();

        // Generate default credential name if not provided
        String finalCredentialName = request.credentialName();
        if (finalCredentialName == null || finalCredentialName.isBlank()) {
            finalCredentialName = integrationName + " Credential";
            log.debug("No credential name provided, using default: {}", finalCredentialName);
        }

        if (providerConfig.isClientCredentials()) {
            return initiateClientCredentials(
                    request,
                    userId,
                    organizationId,
                    providerConfig,
                    clientId,
                    clientSecret,
                    platformRow,
                    template,
                    finalCredentialName,
                    integrationIdentifier,
                    iconUrl);
        }

        // Resolve per-instance URL host placeholders (Shopify {shop}, Zendesk {subdomain},
        // NetSuite {account_id}, Workday {tenant}/{hostname}, ...) from the values the user
        // supplied at connect time. This is fully data-driven: the importer derived which
        // credential fields feed these placeholders from the OAuth URL templates, the wizard
        // collected them BEFORE the redirect, and they arrive in request.templateVars(). Values
        // are normalized against the URL template (scheme/path stripped, a pasted host suffix like
        // ".myshopify.com" removed) so both the redirect and the stored value stay consistent.
        // No-op for the vast majority of providers (empty map -> URLs unchanged).
        Map<String, String> templateVars =
                normalizeHostVarsAgainstTemplate(request.templateVarsOrEmpty(), providerConfig.authorizationUrl());
        if (!templateVars.isEmpty()) {
            providerConfig = providerConfig.withUrls(
                    substituteHostVars(providerConfig.authorizationUrl(), templateVars),
                    substituteHostVars(providerConfig.tokenUrl(), templateVars));
        }
        // Fail fast if a required host placeholder is still unresolved: a literal {var} left in the
        // host produces a DNS failure at redirect time with no actionable error (the exact bug this
        // feature fixes). Path/query placeholders are a different mechanism and are not checked here.
        assertHostFullyResolved(providerConfig.authorizationUrl(), integrationName);

        // Persist state (including PKCE verifier + active workspace) to Redis with TTL.
        OAuth2State oAuth2State = new OAuth2State(
                userId,
                request.credentialTemplateId(),
                finalCredentialName,
                clientId,
                clientSecret,
                providerConfig.authorizationUrl(),
                providerConfig.tokenUrl(),
                providerConfig.joinedScopes(),
                request.environment() != null ? request.environment() : "Production",
                integrationIdentifier,
                iconUrl,
                request.returnUrl(),
                Instant.now(),
                pkce != null ? pkce.verifier() : null,
                // PR19: capture-at-initiate so callback can tag the new
                // credential with the right org_id, even if the user switches
                // workspace mid-flow (the state blob is the source of truth).
                organizationId,
                // Captured so the callback can resolve the token URL and persist these into
                // credential_data for runtime base-URL substitution.
                templateVars.isEmpty() ? null : templateVars
        );
        saveStateToRedis(state, oAuth2State);

        // Delegate URL assembly to the stateless engine. uiLocale (when present) drives the
        // provider's UI-locale param so the consent screen renders in the app language.
        String authorizationUrl = engine.buildAuthorizationUrl(
                providerConfig, clientId, state, callbackUrl, pkce, uiLocale);

        log.info("Generated authorization URL for state {} (pkce={})", state, pkce != null);
        return new OAuth2InitiateResponse(authorizationUrl, state);
    }

    private OAuth2InitiateResponse initiateClientCredentials(
            OAuth2InitiateRequest request,
            String userId,
            String organizationId,
            OAuth2ProviderConfig providerConfig,
            String clientId,
            String clientSecret,
            PlatformCredentialModels.PlatformCredential platformRow,
            JsonNode template,
            String credentialName,
            String integrationIdentifier,
            String iconUrl
    ) {
        Map<String, Object> credentialData = new HashMap<>();
        credentialData.put("client_id", clientId);
        credentialData.put("oauth_client_id", clientId);
        credentialData.put("oauth_client_secret", encryptionService.encrypt(clientSecret));
        credentialData.put("client_secret_masked", maskSecret(clientSecret));
        if (platformRow != null && platformRow.customFields() != null) {
            credentialData.putAll(platformRow.customFields());
        }
        // Per-instance host vars supplied at connect time (Marketo {munchkin_id}, ...). Merged into
        // credential_data BEFORE the token request so resolveCredentialTemplateUrl below resolves the
        // token URL host, AND so the runtime base-URL + refresh URL resolve later. Normalized against
        // the token URL template so a pasted full host collapses to the bare id. The
        // authorization_code branch handles its own vars earlier (this path returns before it); this
        // is the client_credentials counterpart. No-op for providers with no host vars.
        credentialData.putAll(
                normalizeHostVarsAgainstTemplate(request.templateVarsOrEmpty(), providerConfig.tokenUrl()));

        HttpEntity<?> requestEntity =
                engine.buildClientCredentialsRequest(providerConfig, clientId, clientSecret);
        OAuth2Engine.TokenRequest tokenRequest = engine.materializeTokenRequest(
                providerConfig,
                resolveCredentialTemplateUrl(providerConfig.tokenUrl(), credentialData),
                requestEntity);

        ResponseEntity<JsonNode> response = restTemplate.postForEntity(
                tokenRequest.url(), tokenRequest.entity(), JsonNode.class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new RuntimeException("Client credentials token request failed: " + response.getStatusCode());
        }

        OAuth2TokenResponse tokens = engine.parseTokenResponse(response.getBody(), providerConfig);
        credentialData.put("access_token", encryptionService.encrypt(tokens.accessToken()));
        credentialData.put("token_type", tokens.tokenType() != null ? tokens.tokenType() : "Bearer");
        if (tokens.expiresIn() != null) {
            credentialData.put("expires_at", Instant.now().plusSeconds(tokens.expiresIn()).toString());
        }
        credentialData.putAll(engine.extractQuirkFields(response.getBody(), providerConfig));
        String grantedScope = tokens.scope() != null && !tokens.scope().isBlank()
                ? tokens.scope()
                : providerConfig.joinedScopes();
        if (grantedScope != null && !grantedScope.isBlank()) {
            credentialData.put("scope", grantedScope);
        }
        credentialData.put(TEMPLATE_ID_FIELD, request.credentialTemplateId());
        rememberCredentialTemplateReference(credentialData, template);

        List<String> scopes = grantedScope != null && !grantedScope.isBlank()
                ? Arrays.asList(grantedScope.split("\\s+"))
                : List.of();
        Credential credential = credentialService.createCredential(
                userId,
                organizationId,
                credentialName,
                integrationIdentifier,
                CredentialType.OAuth2,
                CredentialEnvironment.valueOf(request.environment() != null ? request.environment() : "Production"),
                "OAuth2 credential created via client_credentials flow",
                credentialData,
                scopes,
                List.of("oauth2", "client_credentials", "auto-created"),
                userId,
                iconUrl
        );

        return new OAuth2InitiateResponse(
                buildClientCredentialsReturnUrl(request.returnUrl(), credential.id()),
                "client_credentials");
    }

    private String buildClientCredentialsReturnUrl(String returnUrl, Long credentialId) {
        String target = returnUrl != null && !returnUrl.isBlank()
                ? returnUrl
                : "/app/settings/credentials";
        String separator = target.contains("?") ? "&" : "?";
        return target + separator + "success=true&credentialId=" + credentialId;
    }

    /**
     * Simplified initiation using only platform credentials. Legacy entry -
     * defaults organizationId to null.
     */
    public OAuth2InitiateResponse initiateSimple(OAuth2SimpleInitiateRequest request, String userId) {
        return initiateSimple(request, userId, null);
    }

    /**
     * PR19 - initiateSimple with org capture for strict-isolation OAuth flows.
     */
    public OAuth2InitiateResponse initiateSimple(OAuth2SimpleInitiateRequest request, String userId,
                                                  String organizationId) {
        return initiateSimple(request, userId, organizationId, null);
    }

    /**
     * Locale-aware {@code initiateSimple} - threads {@code uiLocale} through to the locale-aware
     * {@link #initiate(OAuth2InitiateRequest, String, String, String)} so the simple connect flow
     * also renders the consent screen in the app language.
     */
    public OAuth2InitiateResponse initiateSimple(OAuth2SimpleInitiateRequest request, String userId,
                                                  String organizationId, String uiLocale) {
        OAuth2InitiateRequest fullRequest = new OAuth2InitiateRequest(
                request.credentialTemplateId(),
                request.credentialName(),
                null,
                null,
                request.environment(),
                request.integration(),
                null,
                request.templateVars()
        );
        return initiate(fullRequest, userId, organizationId, uiLocale);
    }

    /**
     * Check if platform credentials are available for an integration
     * (from database or configuration fallback).
     */
    public boolean hasPlatformCredentials(String integrationName) {
        return platformCredentialService.hasOAuth2Credentials(integrationName);
    }

    public PlatformCredentialModels.PlatformCredentialsAvailability getPlatformCredentialsAvailability(
            String integrationName
    ) {
        return platformCredentialService.getPlatformCredentialsAvailability(integrationName);
    }

    /**
     * Build redirect URL using returnUrl from state or default to settings page.
     */
    private String buildRedirectUrl(OAuth2State oAuth2State, Map<String, String> params) {
        String baseUrl = oAuth2State != null && oAuth2State.returnUrl() != null && !oAuth2State.returnUrl().isBlank()
                ? frontendUrl + oAuth2State.returnUrl()
                : frontendUrl + "/app/settings/credentials";

        if (params.isEmpty()) {
            return baseUrl;
        }

        String queryString = params.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                        + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .reduce((a, b) -> a + "&" + b)
                .orElse("");

        return baseUrl + "?" + queryString;
    }

    /**
     * Handle OAuth2 callback - exchange code for tokens.
     */
    public String handleCallback(String code, String state) {
        log.info("Handling OAuth2 callback with state {}", state);

        OAuth2State oAuth2State = loadStateFromRedis(state);
        if (oAuth2State == null) {
            log.error("Invalid or expired state: {}", state);
            return buildRedirectUrl(null, Map.of("error", "invalid_state"));
        }

        try {
            // Re-fetch template to get the full config for token exchange. We rebuild from
            // template (not from state) so the engine has access to authMethod, extras, etc.
            JsonNode template = fetchCredentialTemplate(oAuth2State.credentialTemplateId());
            OAuth2ProviderConfig providerConfig = template != null ? extractOAuth2Config(template) : null;
            if (providerConfig == null) {
                // Fallback: rebuild a minimal config from the state so the callback still works
                // even if the catalog is unreachable. This is last-resort - we lose authMethod,
                // authorizeExtraParams, and any provider-specific wiring, so the token exchange
                // will fail for BASIC-auth providers (Twitter/X, Notion on /oauth/token). Loud
                // log so an operator can correlate.
                log.warn("Catalog unreachable or template missing for {} - falling back to "
                        + "minimal config reconstructed from OAuth2State. Non-POST auth methods "
                        + "and extra params are LOST on this path.",
                        oAuth2State.credentialTemplateId());
                providerConfig = new OAuth2ProviderConfig(
                        oAuth2State.authUrl(),
                        oAuth2State.accessTokenUrl(),
                        null,
                        oAuth2State.scope() == null || oAuth2State.scope().isEmpty()
                                ? List.of()
                                : List.of(oAuth2State.scope().split("\\s+")),
                        " ",
                        OAuth2ProviderConfig.AuthMethod.POST,
                        oAuth2State.codeVerifier() != null,
                        Map.of(),
                        OAuth2ProviderConfig.RefreshConfig.STANDARD
                );
            }

            HttpEntity<?> request = engine.buildTokenExchangeRequest(
                    providerConfig,
                    code,
                    oAuth2State.clientId(),
                    oAuth2State.clientSecret(),
                    callbackUrl,
                    oAuth2State.codeVerifier()
            );

            // Resolve any per-instance host placeholder in the token URL from the values captured
            // at initiate (Shopify {shop}, Zendesk {subdomain}, ...). No-op when none were captured
            // (the vast majority of providers), keeping the token URL byte-identical.
            String resolvedTokenUrl = substituteHostVars(providerConfig.tokenUrl(), oAuth2State.templateVarsOrEmpty());

            log.info("Token exchange request - tokenUrl: {}, authMethod: {}, pkce: {}",
                    resolvedTokenUrl,
                    providerConfig.tokenAuthMethod(),
                    oAuth2State.codeVerifier() != null);

            OAuth2Engine.TokenRequest tokenRequest = engine.materializeTokenRequest(
                    providerConfig, resolvedTokenUrl, request);
            ResponseEntity<JsonNode> response = restTemplate.postForEntity(
                    tokenRequest.url(), tokenRequest.entity(), JsonNode.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new RuntimeException("Token exchange failed: " + response.getStatusCode());
            }

            OAuth2TokenResponse tokens = engine.parseTokenResponse(response.getBody(), providerConfig);

            // Non-RFC long-lived exchange (Meta): swap the short-lived callback token for the
            // ~60-day one BEFORE persisting, so the credential is born long-lived with a real
            // expires_at. Best-effort: on failure the short-lived token is kept (connect still
            // succeeds, degraded to today's behavior).
            tokens = maybeExchangeLongLived(providerConfig, tokens,
                    oAuth2State.clientId(), oAuth2State.clientSecret());

            // Persist credential.
            Map<String, Object> credentialData = new HashMap<>();
            // Persist the per-instance URL host vars (shop, subdomain, ...) so runtime base-URL
            // substitution (HttpExecutionService.replaceUrlTemplateVariables) can rebuild the
            // provider host at API-call time. Keyed by the placeholder name; empty for most providers.
            oAuth2State.templateVarsOrEmpty().forEach(credentialData::put);
            credentialData.put("client_id", oAuth2State.clientId());
            credentialData.put("oauth_client_id", oAuth2State.clientId());
            credentialData.put("oauth_client_secret", encryptionService.encrypt(oAuth2State.clientSecret()));
            credentialData.put("client_secret_masked", maskSecret(oAuth2State.clientSecret()));
            credentialData.put("access_token", encryptionService.encrypt(tokens.accessToken()));
            if (tokens.refreshToken() != null) {
                credentialData.put("refresh_token", encryptionService.encrypt(tokens.refreshToken()));
                // Track when the refresh_token itself was issued - needed to detect expiry for
                // providers with a bounded refresh_token TTL (Xero=60d, QuickBooks=100d, Google=180d).
                credentialData.put("refresh_token_issued_at", Instant.now().toString());
            }
            credentialData.put("token_type", tokens.tokenType() != null ? tokens.tokenType() : "Bearer");
            if (tokens.expiresIn() != null) {
                credentialData.put("expires_at", Instant.now().plusSeconds(tokens.expiresIn()).toString());
            }
            // Providers renewed via an access-token grant (no refresh_token) mark the credential
            // so the refresh scheduler's SQL predicate can select it - the predicate cannot see
            // the template config, only credential_data.
            if (providerConfig.refresh().supported() && providerConfig.refresh().accessTokenGrant() != null) {
                credentialData.put(REFRESH_MODE_FIELD, REFRESH_MODE_ACCESS_TOKEN);
            }
            // Harvest non-RFC-6749 fields (Salesforce instance_url, Xero tenant_id, …) declared
            // as quirks in the provider JSON. Nothing is harvested when no quirks are declared.
            credentialData.putAll(engine.extractQuirkFields(response.getBody(), providerConfig));
            // Prefer scopes actually GRANTED by the provider (tokens.scope()) over the list we
            // REQUESTED (oAuth2State.scope()). Google returns the granted subset when the user
            // unchecks scopes at the consent screen - saving the requested list would make us
            // think we have permissions we don't, producing surprising 403s at runtime.
            String grantedScope = tokens.scope() != null && !tokens.scope().isBlank()
                    ? tokens.scope()
                    : (oAuth2State.scope() != null && !oAuth2State.scope().isBlank()
                            ? oAuth2State.scope() : null);
            if (grantedScope != null) {
                credentialData.put("scope", grantedScope);
            }
            credentialData.put(TEMPLATE_ID_FIELD, oAuth2State.credentialTemplateId());
            rememberCredentialTemplateReference(credentialData, template);

            List<String> scopes = grantedScope != null
                    ? Arrays.asList(grantedScope.split("\\s+"))
                    : List.of();

            // PR19 - thread the org context captured at initiate-time. Without
            // this, an OAuth flow started from an org workspace would land
            // the credential in personal scope (audit-1 CRITICAL-1).
            Credential credential = credentialService.createCredential(
                    oAuth2State.userId(),
                    oAuth2State.organizationId(),
                    oAuth2State.credentialName(),
                    oAuth2State.integration(),
                    CredentialType.OAuth2,
                    CredentialEnvironment.valueOf(oAuth2State.environment()),
                    "OAuth2 credential created via authorization flow",
                    credentialData,
                    scopes,
                    List.of("oauth2", "auto-created"),
                    oAuth2State.userId(),
                    oAuth2State.iconUrl()
            );

            removeStateFromRedis(state);

            log.info("Successfully created credential {} for user {}", credential.id(), oAuth2State.userId());
            return buildRedirectUrl(oAuth2State, Map.of(
                    "success", "true",
                    "credentialId", String.valueOf(credential.id())
            ));

        } catch (org.springframework.web.client.HttpClientErrorException e) {
            // Keep the provider error body server-side only - it can echo the auth code,
            // redirect_uri, or fragments of credentials that must never land in browser
            // history / access logs / Referer headers. Surface a stable opaque code instead.
            // LogSafeBody extracts only `error`+`error_description`, truncates, and scrubs
            // token-shaped substrings - providers occasionally echo the submitted refresh/access
            // token back in their error payload.
            log.error("Token exchange HTTP error: status={} body={}",
                    e.getStatusCode(), LogSafeBody.scrub(e.getResponseBodyAsString()));
            removeStateFromRedis(state);
            return buildRedirectUrl(oAuth2State, Map.of("error", "token_exchange_failed"));
        } catch (Exception e) {
            log.error("Failed to exchange code for tokens: {}", e.getMessage(), e);
            removeStateFromRedis(state);
            return buildRedirectUrl(oAuth2State, Map.of("error", "token_exchange_failed"));
        }
    }

    /**
     * Refresh an expired access token.
     *
     * <p>Five-layer thundering-herd defense runs before the provider is contacted:
     * <ol>
     *   <li><strong>Fast-path Redis gate</strong> - check disabled + cooldown sentinels
     *       (<em>before</em> any DB read). A freshly-terminal credential is rejected in
     *       sub-millisecond time even under 100-way fan-in.</li>
     *   <li><strong>SETNX lock</strong> with UUID owner token - serializes scheduler vs.
     *       lazy-401 races, blocking rotating providers from invalidating mid-flight.</li>
     *   <li><strong>Double-check under lock</strong> - re-read Redis sentinels to close the
     *       race where a credential flips terminal between fast-path and lock acquisition.</li>
     *   <li><strong>DB authoritative gate</strong> - load credential once, verify status + DB
     *       cooldown. DB is source of truth; Redis mirrors are best-effort.</li>
     *   <li><strong>Proceed</strong> - make the provider POST; classify failures; persist
     *       new tokens or scrub terminal state + diagnostic fields + cooldown sentinels.</li>
     * </ol>
     *
     * <p>Thrown exceptions:
     * <ul>
     *   <li>{@link IllegalStateException} {@code "refresh_in_progress"} - SETNX lost; caller
     *       should backoff and retry (unchanged contract).</li>
     *   <li>{@link IllegalStateException} {@code "refresh_not_supported: …"} - provider has
     *       {@code refresh.supported=false} (unchanged contract).</li>
     *   <li>{@link RefreshTerminalException} - user must re-OAuth or admin must fix config.
     *       Tokens already scrubbed, status flipped. Do NOT retry, do NOT fall back to
     *       stored token.</li>
     *   <li>{@link RefreshTransientException} - provider blip or rate limit. Cooldown set.
     *       Caller MAY fall back to stored access_token.</li>
     * </ul>
     */
    public Credential refreshToken(Long credentialId, String userId) {
        log.info("Refreshing token for credential {} user {}", credentialId, userId);
        // Mutable so fastPathGate can seed the provider label from the sentinel value before the
        // typed exception propagates to the outer catch - otherwise a purely Redis-path failure
        // (sentinel hit with no DB load) would record every metric as provider="unknown".
        // Bounded cardinality - always an integration slug or "unknown".
        String[] providerRef = new String[]{null};
        try {
            // (a) Fast-path: Redis-only gate, cheap and cache-friendly.
            fastPathGate(credentialId, providerRef);

            String lockKey = REDIS_REFRESH_LOCK_PREFIX + credentialId;
            String lockToken = UUID.randomUUID().toString();
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(lockKey, lockToken, REFRESH_LOCK_TTL);
            if (!Boolean.TRUE.equals(acquired)) {
                log.info("Refresh already in progress for credential {} - caller should retry", credentialId);
                throw new IllegalStateException("refresh_in_progress");
            }
            try {
                // (c) Double-check under lock: close the race where the credential flipped terminal
                // between the fast-path read and the SETNX write.
                fastPathGate(credentialId, providerRef);

                // (d)+(e) Authoritative DB gate + provider POST. Load once, reuse in doRefreshToken.
                Credential credential = credentialService.getCredential(credentialId)
                        .filter(c -> c.tenantId().equals(userId))
                        .orElseThrow(() -> new IllegalArgumentException("Credential not found"));
                providerRef[0] = credential.integration();
                authoritativeGate(credential);
                Credential refreshed = doRefreshToken(credential, userId);
                refreshMetrics.recordSuccess(providerRef[0]);
                return refreshed;
            } finally {
                try {
                    Long released = redisTemplate.execute(
                            RELEASE_LOCK_SCRIPT, List.of(lockKey), lockToken);
                    if (released == null || released == 0L) {
                        // Lock was already gone (TTL expired mid-refresh) or owned by a different
                        // caller. We MUST NOT delete blindly - that would stomp a second holder's
                        // lock. Log so ops can see if provider latency routinely exceeds the TTL.
                        log.warn("Refresh lock for credential {} was already released before we returned " +
                                "- TTL may be too short for this provider's token endpoint", credentialId);
                    }
                } catch (Exception e) {
                    log.warn("Failed to release refresh lock for credential {}: {}", credentialId, e.getMessage());
                }
            }
        } catch (RefreshTerminalException terminal) {
            refreshMetrics.recordTerminal(providerRef[0], terminal.bucket(), terminal.providerCode());
            throw terminal;
        } catch (RefreshTransientException transientErr) {
            refreshMetrics.recordTransient(providerRef[0]);
            throw transientErr;
        } catch (IllegalStateException ise) {
            String msg = ise.getMessage() != null ? ise.getMessage() : "";
            if ("refresh_in_progress".equals(msg)) {
                refreshMetrics.recordInProgress(providerRef[0]);
            } else if (msg.startsWith("refresh_not_supported")) {
                refreshMetrics.recordUnsupported(providerRef[0]);
            } else {
                refreshMetrics.recordFailed(providerRef[0]);
            }
            throw ise;
        } catch (RuntimeException other) {
            refreshMetrics.recordFailed(providerRef[0]);
            throw other;
        }
    }

    /**
     * Check the Redis disabled + cooldown sentinels. Throws the appropriate typed exception if
     * either is set. Safe to call multiple times in one request - Redis GETs are sub-millisecond
     * and idempotent.
     *
     * <p>Sentinel values are stored as {@code "<state>|<provider>"} (e.g. {@code
     * "terminal_user|gmail"}, {@code "2026-04-21T12:00:00Z|slack"}). When a hit occurs we parse
     * the hint into {@code providerRef[0]} so the outer catch can record the metric with a
     * proper {@code provider} label instead of {@code "unknown"}. Bare values (no pipe) are
     * accepted for backward compatibility - if any pre-upgrade sentinels are still in Redis
     * they just emit with provider={@code "unknown"}, same behaviour as before.
     */
    private void fastPathGate(Long credentialId, String[] providerRef) {
        try {
            String disabled = redisTemplate.opsForValue().get(REDIS_REFRESH_DISABLED_PREFIX + credentialId);
            if (disabled != null) {
                String state = parseSentinelState(disabled);
                String hint = parseProviderHint(disabled);
                if (hint != null && providerRef[0] == null) providerRef[0] = hint;
                RefreshErrorBucket bucket = "terminal_user".equals(state)
                        ? RefreshErrorBucket.TERMINAL_USER
                        : RefreshErrorBucket.TERMINAL_CONFIG;
                throw new RefreshTerminalException(bucket, null, null,
                        "refresh disabled by prior terminal failure (sentinel=" + state + ")");
            }
            String cooldown = redisTemplate.opsForValue().get(REDIS_REFRESH_COOLDOWN_PREFIX + credentialId);
            if (cooldown != null) {
                String hint = parseProviderHint(cooldown);
                if (hint != null && providerRef[0] == null) providerRef[0] = hint;
                throw new RefreshTransientException(
                        RefreshErrorBucket.TRANSIENT, null, null, null, 0, null);
            }
        } catch (RefreshTerminalException | RefreshTransientException typed) {
            throw typed;
        } catch (Exception redisDown) {
            // Redis unreachable - fall through to the authoritative DB gate. Don't fail the
            // refresh on a Redis outage when we can still read the credential status from DB.
            log.debug("Redis fast-path gate skipped for credential {}: {}",
                    credentialId, redisDown.getMessage());
        }
    }

    /**
     * Parse the state portion (before the pipe) of a pipe-delimited sentinel value. Returns the
     * whole value unchanged if no pipe is present (backward-compat with pre-upgrade sentinels).
     */
    private static String parseSentinelState(String sentinelValue) {
        if (sentinelValue == null) return null;
        int pipe = sentinelValue.indexOf('|');
        return pipe < 0 ? sentinelValue : sentinelValue.substring(0, pipe);
    }

    /**
     * Parse the provider hint (after the pipe) of a sentinel value. Returns null if absent or
     * blank - callers treat null as "no hint" and fall back to {@code "unknown"}.
     */
    private static String parseProviderHint(String sentinelValue) {
        if (sentinelValue == null) return null;
        int pipe = sentinelValue.indexOf('|');
        if (pipe < 0 || pipe >= sentinelValue.length() - 1) return null;
        String hint = sentinelValue.substring(pipe + 1);
        return hint.isBlank() ? null : hint;
    }

    /**
     * Check credential status + {@code refresh_cooldown_until} from the already-loaded DB
     * record. This is the source of truth when Redis sentinels are absent (eviction, Redis
     * restart, initial deployment).
     */
    private void authoritativeGate(Credential credential) {
        CredentialStatus status = credential.status();
        if (status == CredentialStatus.needs_reauth) {
            throw new RefreshTerminalException(
                    RefreshErrorBucket.TERMINAL_USER, null, null,
                    "credential status=needs_reauth (user must re-authorize)");
        }
        if (status == CredentialStatus.error) {
            throw new RefreshTerminalException(
                    RefreshErrorBucket.TERMINAL_CONFIG, null, null,
                    "credential status=error (admin must fix configuration)");
        }
        Map<String, Object> data = credential.credentialData();
        if (data != null) {
            Object cooldownRaw = data.get("refresh_cooldown_until");
            if (cooldownRaw instanceof String cooldownStr && !cooldownStr.isBlank()) {
                try {
                    Instant cooldownUntil = Instant.parse(cooldownStr);
                    if (cooldownUntil.isAfter(Instant.now())) {
                        throw new RefreshTransientException(
                                RefreshErrorBucket.TRANSIENT, null, null, null, 0, null);
                    }
                } catch (java.time.format.DateTimeParseException malformed) {
                    log.warn("Malformed refresh_cooldown_until on credential {}: {}",
                            credential.id(), cooldownStr);
                }
            }
        }
    }

    private Credential doRefreshToken(Credential credential, String userId) {
        Long credentialId = credential.id();
        Map<String, Object> data = credential.credentialData() == null
                ? new HashMap<>()
                : new HashMap<>(credential.credentialData());
        String refreshTokenEncrypted = (String) data.get("refresh_token");
        String refreshToken = refreshTokenEncrypted != null
                ? encryptionService.decrypt(refreshTokenEncrypted)
                : null;
        String templateId = stringValue(data.get(TEMPLATE_ID_FIELD));

        JsonNode template = resolveCredentialTemplateForRefresh(credential, userId, data);
        OAuth2ProviderConfig providerConfig = template != null ? extractOAuth2Config(template) : null;
        templateId = stringValue(data.get(TEMPLATE_ID_FIELD));

        // For custom APIs: template may not have OAuth URLs. Fall back to platform credential.
        // V362: resolve the BYOK client in the workspace the credential belongs to.
        if (providerConfig == null) {
            var rawCred = platformCredentialService.getRawCredential(
                    credential.integration(), userId, credential.organizationId());
            if (rawCred.isPresent()) {
                var pc = rawCred.get();
                if (pc.authUrl() != null && !pc.authUrl().isBlank()
                        && pc.tokenUrl() != null && !pc.tokenUrl().isBlank()) {
                    providerConfig = new OAuth2ProviderConfig(
                            pc.authUrl(), pc.tokenUrl(), null,
                            pc.defaultScopes() != null ? List.of(pc.defaultScopes().split("\\s+")) : List.of(),
                            " ", OAuth2ProviderConfig.AuthMethod.POST, false, Map.of(),
                            OAuth2ProviderConfig.RefreshConfig.STANDARD);
                    log.info("Using platform credential URLs for refresh of credential {}", credentialId);
                }
            }
        }
        if (providerConfig == null) {
            throw new IllegalStateException("Cannot load OAuth2 configuration for template " + templateId);
        }

        if (providerConfig.isClientCredentials()) {
            return refreshClientCredentials(credentialId, userId, credential, data, providerConfig);
        }

        // Non-RFC access-token grant renewal (Meta ig_refresh_token / fb_exchange_token):
        // there is NO refresh_token on these providers - the current access token IS the
        // renewal credential. Must branch BEFORE the refresh_token null check below.
        if (providerConfig.refresh().supported() && providerConfig.refresh().accessTokenGrant() != null) {
            return refreshViaAccessTokenGrant(credentialId, userId, credential, data, providerConfig);
        }

        if (refreshToken == null || refreshToken.isBlank()) {
            throw new IllegalStateException("No refresh token available");
        }

        // Reject refresh up-front for providers that don't support it (client_credentials flows,
        // long-lived PATs). Surfaces the declared reason so callers know what to do instead of
        // retrying an endpoint that will 400 forever.
        OAuth2ProviderConfig.RefreshConfig refreshCfg = providerConfig.refresh();
        if (!refreshCfg.supported()) {
            String reason = refreshCfg.unsupportedReason() != null
                    ? refreshCfg.unsupportedReason()
                    : "provider does not support refresh_token grant";
            log.warn("Refresh rejected for credential {} (template {}): {}",
                    credentialId, templateId, reason);
            throw new IllegalStateException("refresh_not_supported: " + reason);
        }

        String clientId = (String) data.get("client_id");
        String clientSecret;
        String encryptedClientSecret = (String) data.get("oauth_client_secret");
        if (encryptedClientSecret == null) {
            encryptedClientSecret = (String) data.get("client_secret");
        }
        if (encryptedClientSecret != null) {
            clientSecret = encryptionService.decrypt(encryptedClientSecret);
        } else {
            log.warn("No oauth_client_secret in credential data for credential {}, trying platform credentials",
                    credentialId);
            clientSecret = platformCredentialService.getRawCredential(
                            credential.integration(), userId, credential.organizationId())
                    .map(pc -> pc.clientSecret())
                    .orElse(null);
        }

        int currentAttempts = readRefreshAttempts(data);

        try {
            HttpEntity<?> request = engine.buildRefreshRequest(
                    providerConfig, refreshToken, clientId, clientSecret);
            OAuth2Engine.TokenRequest tokenRequest = engine.materializeTokenRequest(
                    providerConfig,
                    resolveCredentialTemplateUrl(providerConfig.effectiveRefreshUrl(), data),
                    request);

            ResponseEntity<JsonNode> response = restTemplate.postForEntity(
                    tokenRequest.url(), tokenRequest.entity(), JsonNode.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new RuntimeException("Failed to refresh token: " + response.getStatusCode());
            }

            OAuth2TokenResponse tokens = engine.parseTokenResponse(response.getBody(), providerConfig);

            Map<String, Object> newData = new HashMap<>(data);
            newData.put("access_token", encryptionService.encrypt(tokens.accessToken()));
            if (tokens.refreshToken() != null) {
                // Some providers rotate the refresh token; others return the same one or none.
                newData.put("refresh_token", encryptionService.encrypt(tokens.refreshToken()));
                // Rotated refresh_token starts its own TTL clock; the proactive refresher uses
                // this timestamp to detect the refresh_token itself about to expire.
                if (refreshCfg.rotatesRefreshToken()) {
                    newData.put("refresh_token_issued_at", Instant.now().toString());
                }
            }
            if (tokens.expiresIn() != null) {
                newData.put("expires_at", Instant.now().plusSeconds(tokens.expiresIn()).toString());
            }
            // Re-harvest non-RFC-6749 fields - providers that rotate scoping info (Zoho region,
            // Xero tenant_id, Salesforce instance_url) can change them on refresh.
            newData.putAll(engine.extractQuirkFields(response.getBody(), providerConfig));
            // Success erases any prior transient failure state: the next refresh attempt starts
            // fresh, otherwise a single recovery would still count against the 5-attempt budget.
            clearFailureFields(newData);
            Credential saved = credentialService.updateCredentialData(credentialId, userId, newData);
            clearRedisCooldown(credentialId);
            return saved;
        } catch (HttpClientErrorException | HttpServerErrorException e) {
            // PII-safe log: only `error` + `error_description` fields, token-shaped substrings
            // stripped, both truncated to 200 chars. Never the raw body - providers have been
            // known to echo refresh_tokens in error_description.
            log.error("Refresh HTTP error for credential {}: status={} body_safe=[{}]",
                    credentialId, e.getStatusCode(), LogSafeBody.scrub(e.getResponseBodyAsString()));
            RuntimeException classified = errorClassifier.classify(e, currentAttempts);
            handleRefreshFailure(credential, data, classified);
            throw classified;
        } catch (ResourceAccessException socketFailure) {
            log.warn("Refresh socket failure for credential {}: {}",
                    credentialId, socketFailure.getMessage());
            RuntimeException classified = errorClassifier.classify(socketFailure, currentAttempts);
            handleRefreshFailure(credential, data, classified);
            throw classified;
        } catch (Exception e) {
            log.warn("Refresh unexpected failure for credential {}: {}", credentialId, e.getMessage());
            RuntimeException classified = errorClassifier.classify(e, currentAttempts);
            handleRefreshFailure(credential, data, classified);
            throw classified;
        }
    }

    /**
     * Persist the outcome of a failed refresh. Terminal → scrub tokens, flip status, set
     * disabled sentinel. Transient → compute cooldown, increment attempts; promote to
     * terminal if budget exhausted.
     */
    private Credential refreshClientCredentials(
            Long credentialId,
            String userId,
            Credential credential,
            Map<String, Object> data,
            OAuth2ProviderConfig providerConfig
    ) {
        String clientId = stringValue(data.get("client_id"));
        if (clientId == null) {
            clientId = stringValue(data.get("oauth_client_id"));
        }
        String encryptedClientSecret = stringValue(data.get("client_secret"));
        if (encryptedClientSecret == null) {
            encryptedClientSecret = stringValue(data.get("oauth_client_secret"));
        }
        if (clientId == null || encryptedClientSecret == null) {
            throw new IllegalStateException("client_credentials requires client_id and client_secret");
        }
        String clientSecret = encryptionService.decrypt(encryptedClientSecret);

        try {
            HttpEntity<?> request = engine.buildClientCredentialsRequest(
                    providerConfig, clientId, clientSecret);
            OAuth2Engine.TokenRequest tokenRequest = engine.materializeTokenRequest(
                    providerConfig,
                    resolveCredentialTemplateUrl(providerConfig.tokenUrl(), data),
                    request);
            ResponseEntity<JsonNode> response = restTemplate.postForEntity(
                    tokenRequest.url(), tokenRequest.entity(), JsonNode.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new RuntimeException("Client credentials token request failed: " + response.getStatusCode());
            }

            OAuth2TokenResponse tokens = engine.parseTokenResponse(response.getBody(), providerConfig);
            Map<String, Object> newData = new HashMap<>(data);
            newData.put("client_id", clientId);
            newData.put("access_token", encryptionService.encrypt(tokens.accessToken()));
            newData.put("token_type", tokens.tokenType() != null ? tokens.tokenType() : "Bearer");
            if (tokens.expiresIn() != null) {
                newData.put("expires_at", Instant.now().plusSeconds(tokens.expiresIn()).toString());
            }
            newData.putAll(engine.extractQuirkFields(response.getBody(), providerConfig));
            clearFailureFields(newData);
            Credential saved = credentialService.updateCredentialData(credentialId, userId, newData);
            clearRedisCooldown(credentialId);
            return saved;
        } catch (Exception e) {
            log.warn("client_credentials token refresh failed for credential {}: {}", credentialId, e.getMessage());
            RuntimeException classified = errorClassifier.classify(e, readRefreshAttempts(data));
            handleRefreshFailure(credential, data, classified);
            throw classified;
        }
    }

    /**
     * Exchange the callback's short-lived token for the provider's long-lived one
     * ({@code oauth2Config.longLivedExchange} - Meta's {@code ig_exchange_token} /
     * {@code fb_exchange_token}). Returns the original tokens when the provider declares no
     * exchange. Best-effort: any failure keeps the short-lived token so the connect still
     * completes (identical to pre-feature behavior), with a loud status-only log line.
     *
     * <p>The granted {@code scope} is preserved from the ORIGINAL response - the exchange
     * response carries only token fields (Meta returns {access_token, token_type, expires_in}).
     */
    private OAuth2TokenResponse maybeExchangeLongLived(
            OAuth2ProviderConfig providerConfig,
            OAuth2TokenResponse tokens,
            String clientId,
            String clientSecret
    ) {
        OAuth2ProviderConfig.AccessTokenGrant exchange = providerConfig.longLivedExchange();
        if (exchange == null) {
            return tokens;
        }
        try {
            // The URL query carries the token (and possibly the client secret): NEVER log it,
            // and never log exception messages from this call (RestTemplate embeds the URL).
            // getForEntity(URI) - the String overload would re-encode the already-encoded
            // query (%XX -> %25XX), corrupting any token with reserved characters.
            String url = engine.buildAccessTokenGrantUrl(exchange, tokens.accessToken(), clientId, clientSecret);
            ResponseEntity<JsonNode> response = restTemplate.getForEntity(java.net.URI.create(url), JsonNode.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("Long-lived token exchange ({}) returned status {} - keeping short-lived token",
                        exchange.grantType(), response.getStatusCode());
                return tokens;
            }
            OAuth2TokenResponse longLived = engine.parseTokenResponse(response.getBody());
            log.info("Long-lived token exchange ({}) succeeded, expires_in={}s",
                    exchange.grantType(), longLived.expiresIn());
            return new OAuth2TokenResponse(
                    longLived.accessToken(),
                    longLived.refreshToken(),
                    longLived.tokenType() != null ? longLived.tokenType() : tokens.tokenType(),
                    longLived.expiresIn(),
                    tokens.scope());
        } catch (HttpClientErrorException | HttpServerErrorException e) {
            log.warn("Long-lived token exchange ({}) failed: status={} body_safe=[{}] - keeping short-lived token",
                    exchange.grantType(), e.getStatusCode(), LogSafeBody.scrub(e.getResponseBodyAsString()));
            return tokens;
        } catch (Exception e) {
            log.warn("Long-lived token exchange ({}) failed: {} - keeping short-lived token",
                    exchange.grantType(), e.getClass().getSimpleName());
            return tokens;
        }
    }

    /**
     * Renew an access token via the provider's non-RFC access-token grant
     * ({@code oauth2Config.refresh.accessTokenGrant} - Meta's {@code ig_refresh_token} /
     * {@code fb_exchange_token} re-exchange). The CURRENT access token is the renewal
     * credential; there is no refresh_token. Mirrors the RFC refresh path's failure handling
     * (classifier + cooldown/terminal bookkeeping) so the scheduler treats both modes alike.
     */
    private Credential refreshViaAccessTokenGrant(
            Long credentialId,
            String userId,
            Credential credential,
            Map<String, Object> data,
            OAuth2ProviderConfig providerConfig
    ) {
        OAuth2ProviderConfig.AccessTokenGrant grant = providerConfig.refresh().accessTokenGrant();
        String encryptedAccessToken = stringValue(data.get("access_token"));
        if (encryptedAccessToken == null) {
            throw new IllegalStateException("No access token available for access-token grant refresh");
        }
        String accessToken = encryptionService.decrypt(encryptedAccessToken);

        String clientId = stringValue(data.get("client_id"));
        if (clientId == null) {
            clientId = stringValue(data.get("oauth_client_id"));
        }
        String clientSecret = null;
        if (grant.sendClientSecret()) {
            String encryptedClientSecret = stringValue(data.get("oauth_client_secret"));
            if (encryptedClientSecret == null) {
                encryptedClientSecret = stringValue(data.get("client_secret"));
            }
            if (encryptedClientSecret != null) {
                clientSecret = encryptionService.decrypt(encryptedClientSecret);
            } else {
                // Legacy rows connected before secrets were always persisted: same platform
                // fallback as the RFC path (V362 - BYOK client resolved in the credential's
                // workspace).
                clientSecret = platformCredentialService.getRawCredential(
                                credential.integration(), userId, credential.organizationId())
                        .map(pc -> pc.clientSecret())
                        .orElse(null);
            }
        }

        int currentAttempts = readRefreshAttempts(data);
        try {
            // URL query carries the current token (and possibly the client secret): never log
            // the URL or raw exception messages (RestTemplate embeds the URL in them).
            // getForEntity(URI) - the String overload would re-encode the already-encoded
            // query (%XX -> %25XX), corrupting any token with reserved characters.
            String url = engine.buildAccessTokenGrantUrl(grant, accessToken, clientId, clientSecret);
            ResponseEntity<JsonNode> response = restTemplate.getForEntity(java.net.URI.create(url), JsonNode.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new IllegalStateException("Access-token grant refresh failed: " + response.getStatusCode());
            }

            OAuth2TokenResponse tokens = engine.parseTokenResponse(response.getBody());
            Map<String, Object> newData = new HashMap<>(data);
            newData.put("access_token", encryptionService.encrypt(tokens.accessToken()));
            if (tokens.expiresIn() != null) {
                newData.put("expires_at", Instant.now().plusSeconds(tokens.expiresIn()).toString());
            } else {
                // Success without expires_in (Meta re-issuing an unchanged token): keep the
                // STALE expires_at and the sweeper would re-refresh this row every tick
                // forever (success clears the failure/cooldown fields). Drop expires_at so
                // the credential falls back to the lazy 401-retry path instead.
                newData.remove("expires_at");
            }
            newData.put(REFRESH_MODE_FIELD, REFRESH_MODE_ACCESS_TOKEN);
            clearFailureFields(newData);
            Credential saved = credentialService.updateCredentialData(credentialId, userId, newData);
            clearRedisCooldown(credentialId);
            log.info("Access-token grant refresh ({}) succeeded for credential {}, expires_in={}s",
                    grant.grantType(), credentialId, tokens.expiresIn());
            return saved;
        } catch (HttpClientErrorException | HttpServerErrorException e) {
            log.error("Access-token grant refresh ({}) HTTP error for credential {}: status={} body_safe=[{}]",
                    grant.grantType(), credentialId, e.getStatusCode(),
                    LogSafeBody.scrub(e.getResponseBodyAsString()));
            RuntimeException classified = errorClassifier.classify(e, currentAttempts);
            handleRefreshFailure(credential, data, classified);
            throw classified;
        } catch (ResourceAccessException socketFailure) {
            log.warn("Access-token grant refresh ({}) socket failure for credential {}: {}",
                    grant.grantType(), credentialId, socketFailure.getClass().getSimpleName());
            RuntimeException classified = errorClassifier.classify(socketFailure, currentAttempts);
            handleRefreshFailure(credential, data, classified);
            throw classified;
        } catch (Exception e) {
            // Same bookkeeping parity as the RFC path's generic catch: without it a 200 with
            // a garbage body (parseTokenResponse throws) escapes with no cooldown and the
            // sweeper hammers the provider every tick. Log the exception CLASS only - a
            // malformed grant URL throws IllegalArgumentException whose message embeds the
            // full query string (token + client secret).
            log.warn("Access-token grant refresh ({}) unexpected failure for credential {}: {}",
                    grant.grantType(), credentialId, e.getClass().getSimpleName());
            RuntimeException classified = errorClassifier.classify(e, currentAttempts);
            handleRefreshFailure(credential, data, classified);
            throw classified;
        }
    }

    private String resolveCredentialTemplateUrl(String url, Map<String, Object> data) {
        if (url == null || !url.contains("{")) {
            return url;
        }
        String resolved = url;
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            String value = stringValue(entry.getValue());
            if (value != null) {
                resolved = resolved.replace("{" + entry.getKey() + "}", value);
            }
        }
        return resolved;
    }

    /**
     * Normalize the user-supplied per-instance host vars against a URL template. For each
     * placeholder present in {@code urlTemplate} (e.g. {@code {shop}} in
     * {@code https://{shop}.myshopify.com/...}) the value is cleaned: scheme + path stripped, and a
     * pasted host suffix matching the literal that follows the placeholder in the template
     * (e.g. {@code .myshopify.com}) removed - so "store", "store.myshopify.com" and
     * "https://store.myshopify.com/admin" all normalize to "store". Deriving the suffix from the
     * template keeps this generic across providers (no per-provider constant). Empty results are
     * dropped so the fail-fast guard can flag a required-but-missing var.
     */
    Map<String, String> normalizeHostVarsAgainstTemplate(Map<String, String> vars, String urlTemplate) {
        if (vars == null || vars.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : vars.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                continue;
            }
            String cleaned = normalizeHostVarValue(e.getValue(), hostSuffixFor(urlTemplate, e.getKey()));
            if (!cleaned.isEmpty()) {
                out.put(e.getKey(), cleaned);
            }
        }
        return out;
    }

    /** Literal text following {@code {key}} in {@code url}, up to the next path/port/placeholder. */
    private String hostSuffixFor(String url, String key) {
        if (url == null) {
            return "";
        }
        String token = "{" + key + "}";
        int idx = url.indexOf(token);
        if (idx < 0) {
            return "";
        }
        int start = idx + token.length();
        int end = start;
        while (end < url.length() && "/:?{".indexOf(url.charAt(end)) < 0) {
            end++;
        }
        return url.substring(start, end);
    }

    /** Strip scheme/path from a user-entered host var and remove a pasted template suffix. */
    private String normalizeHostVarValue(String raw, String suffix) {
        if (raw == null) {
            return "";
        }
        String v = raw.trim();
        int scheme = v.indexOf("://");
        if (scheme >= 0) {
            v = v.substring(scheme + 3);
        }
        int slash = v.indexOf('/');
        if (slash >= 0) {
            v = v.substring(0, slash);
        }
        v = v.trim();
        if (suffix != null && !suffix.isBlank()
                && v.toLowerCase(Locale.ROOT).endsWith(suffix.toLowerCase(Locale.ROOT))) {
            v = v.substring(0, v.length() - suffix.length());
        }
        while (v.endsWith(".")) {
            v = v.substring(0, v.length() - 1);
        }
        return v.trim();
    }

    /** Replace {@code {key}} placeholders in {@code url} with the (already normalized) values. */
    String substituteHostVars(String url, Map<String, String> vars) {
        if (url == null || vars == null || vars.isEmpty() || !url.contains("{")) {
            return url;
        }
        String resolved = url;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                resolved = resolved.replace("{" + e.getKey() + "}", e.getValue());
            }
        }
        return resolved;
    }

    /**
     * Guard against a literal {@code {var}} left in the authorization URL HOST - it would produce a
     * DNS failure at redirect time with no actionable error (the exact bug this feature fixes). Only
     * the host is checked; a placeholder in the path/query is a different (path-param) mechanism.
     */
    void assertHostFullyResolved(String authorizationUrl, String integration) {
        if (authorizationUrl == null) {
            return;
        }
        int schemeEnd = authorizationUrl.indexOf("://");
        int hostStart = schemeEnd >= 0 ? schemeEnd + 3 : 0;
        int hostEnd = hostStart;
        while (hostEnd < authorizationUrl.length()
                && "/?".indexOf(authorizationUrl.charAt(hostEnd)) < 0) {
            hostEnd++;
        }
        String host = authorizationUrl.substring(hostStart, hostEnd);
        if (host.contains("{") && host.contains("}")) {
            throw new IllegalArgumentException(
                    "Connecting " + integration + " needs an instance value (e.g. your store domain "
                    + "or subdomain) before it can start: the authorization URL host still contains an "
                    + "unresolved placeholder. Provide the required field and try again.");
        }
    }

    private void handleRefreshFailure(
            Credential credential,
            Map<String, Object> latestCredentialData,
            RuntimeException classified) {
        handleRefreshFailure(withCredentialData(credential, latestCredentialData), classified);
    }

    private void handleRefreshFailure(Credential credential, RuntimeException classified) {
        if (classified instanceof RefreshTerminalException terminal) {
            releaseTerminal(credential, terminal);
        } else if (classified instanceof RefreshTransientException transient_) {
            releaseTransient(credential, transient_);
        }
        // If classified is something else (shouldn't happen - classifier always returns one of
        // the two), we deliberately leave the credential untouched. Throwing the exception up
        // still surfaces the failure; absent a bucket we can't decide between scrub vs cooldown.
    }

    private Credential withCredentialData(Credential credential, Map<String, Object> latestCredentialData) {
        Map<String, Object> data = latestCredentialData == null
                ? credential.credentialData()
                : new HashMap<>(latestCredentialData);
        return new Credential(
                credential.id(),
                credential.tenantId(),
                credential.organizationId(),
                credential.name(),
                credential.integration(),
                credential.type(),
                credential.environment(),
                credential.status(),
                credential.description(),
                data,
                credential.scopes(),
                credential.tags(),
                credential.owner(),
                credential.iconUrl(),
                credential.isDefault(),
                credential.lastUsed(),
                credential.createdAt(),
                credential.updatedAt());
    }

    // Package-private so {@code OAuth2ServiceTest} can exercise it directly. The defensive
    // try/catch around scrubSensitiveFields is a contract with ops (persistence failure must not
    // mask the original refresh error) and deserves explicit test coverage. All callers live in
    // this file, so the relaxed visibility has no blast radius.
    void releaseTerminal(Credential credential, RefreshTerminalException terminal) {
        CredentialStatus newStatus = terminal.bucket() == RefreshErrorBucket.TERMINAL_USER
                ? CredentialStatus.needs_reauth
                : CredentialStatus.error;
        Map<String, Object> diag = buildDiagFields(
                terminal.bucket(), terminal.providerCode(), terminal.httpStatus(),
                terminal.reason(), readRefreshAttempts(credential.credentialData()));
        // Remove transient-only fields so a future un-error (admin repair, user re-OAuth) starts
        // cleanly - otherwise a stale refresh_cooldown_until would block the next refresh.
        diag.put("refresh_cooldown_until", null);
        Map<String, Object> diagNoNulls = new HashMap<>();
        diag.forEach((k, v) -> { if (v != null) diagNoNulls.put(k, v); });
        try {
            credentialService.scrubSensitiveFields(
                    credential.id(), credential.tenantId(),
                    Set.of("access_token", "refresh_token", "refresh_token_issued_at",
                            "refresh_cooldown_until"),
                    newStatus,
                    diagNoNulls);
        } catch (Exception persistFailure) {
            log.error("Failed to persist terminal state for credential {}: {}",
                    credential.id(), persistFailure.getMessage());
        }
        try {
            // "<state>|<provider>" - the fast-path gate parses the provider hint so a purely
            // Redis-path refresh rejection can still record its metric with a typed provider label.
            String state = terminal.bucket() == RefreshErrorBucket.TERMINAL_USER
                    ? "terminal_user" : "terminal_config";
            redisTemplate.opsForValue().set(
                    REDIS_REFRESH_DISABLED_PREFIX + credential.id(),
                    encodeSentinel(state, credential.integration()),
                    REFRESH_DISABLED_TTL);
            redisTemplate.delete(REDIS_REFRESH_COOLDOWN_PREFIX + credential.id());
        } catch (Exception redisDown) {
            log.debug("Redis unreachable while setting terminal sentinel for credential {}: {}",
                    credential.id(), redisDown.getMessage());
        }
    }

    /**
     * Encode a sentinel value as {@code "<state>|<provider>"} when the integration slug is
     * present, or just {@code "<state>"} when it isn't. The fast-path gate tolerates both forms.
     */
    private static String encodeSentinel(String state, String provider) {
        if (provider == null || provider.isBlank()) return state;
        return state + "|" + provider;
    }

    private void releaseTransient(Credential credential, RefreshTransientException transient_) {
        int nextAttempt = readRefreshAttempts(credential.credentialData()) + 1;
        if (backoff.isExhausted(nextAttempt)) {
            // Budget exhausted - promote to TERMINAL_CONFIG so ops sees a distinct reason and
            // the credential stops being swept. Reuses the terminal release path end-to-end.
            RefreshTerminalException promoted = new RefreshTerminalException(
                    RefreshErrorBucket.TERMINAL_CONFIG,
                    transient_.providerCode(),
                    transient_.httpStatus(),
                    "max_transient_retries_exceeded (attempts=" + nextAttempt + ")");
            releaseTerminal(credential, promoted);
            return;
        }
        Duration sleep = transient_.retryAfter() != null
                ? transient_.retryAfter()
                : backoff.nextSleep(nextAttempt);
        Instant cooldownUntil = Instant.now().plus(sleep);

        Map<String, Object> data = credential.credentialData() == null
                ? new HashMap<>()
                : new HashMap<>(credential.credentialData());
        data.put("refresh_cooldown_until", cooldownUntil.toString());
        data.put("refresh_attempts_before_terminal", nextAttempt);
        data.putAll(buildDiagFields(
                transient_.bucket(), transient_.providerCode(), transient_.httpStatus(),
                null, nextAttempt));
        try {
            credentialService.updateCredentialData(credential.id(), credential.tenantId(), data);
        } catch (Exception persistFailure) {
            log.error("Failed to persist transient cooldown for credential {}: {}",
                    credential.id(), persistFailure.getMessage());
        }
        try {
            // "<cooldown-until>|<provider>" - fast-path gate parses the hint; the state portion
            // is informational (the gate just needs to know the key exists to throw TRANSIENT).
            redisTemplate.opsForValue().set(
                    REDIS_REFRESH_COOLDOWN_PREFIX + credential.id(),
                    encodeSentinel(cooldownUntil.toString(), credential.integration()),
                    sleep);
        } catch (Exception redisDown) {
            log.debug("Redis unreachable while setting cooldown sentinel for credential {}: {}",
                    credential.id(), redisDown.getMessage());
        }
    }

    private Map<String, Object> buildDiagFields(RefreshErrorBucket bucket, String providerCode,
                                                Integer httpStatus, String reason, int attempts) {
        Map<String, Object> diag = new HashMap<>();
        diag.put("refresh_error_reason", bucket.name().toLowerCase());
        if (httpStatus != null) diag.put("refresh_error_http_status", httpStatus);
        if (providerCode != null && !providerCode.isBlank()) diag.put("refresh_error_provider_code", providerCode);
        if (reason != null && !reason.isBlank()) diag.put("refresh_error_reason_detail", reason);
        diag.put("refresh_error_at", Instant.now().toString());
        diag.put("refresh_attempts_before_terminal", attempts);
        return diag;
    }

    private int readRefreshAttempts(Map<String, Object> data) {
        if (data == null) return 0;
        Object raw = data.get("refresh_attempts_before_terminal");
        if (raw instanceof Number n) return n.intValue();
        if (raw instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException unparseable) {
                return 0;
            }
        }
        return 0;
    }

    private void clearFailureFields(Map<String, Object> data) {
        data.remove("refresh_cooldown_until");
        data.remove("refresh_attempts_before_terminal");
        data.remove("refresh_error_reason");
        data.remove("refresh_error_reason_detail");
        data.remove("refresh_error_http_status");
        data.remove("refresh_error_provider_code");
        data.remove("refresh_error_at");
    }

    private void clearRedisCooldown(Long credentialId) {
        try {
            redisTemplate.delete(REDIS_REFRESH_COOLDOWN_PREFIX + credentialId);
        } catch (Exception redisDown) {
            log.debug("Redis unreachable while clearing cooldown sentinel for credential {}: {}",
                    credentialId, redisDown.getMessage());
        }
    }

    // ============================================================================
    // Helper methods
    // ============================================================================

    private JsonNode resolveCredentialTemplateForRefresh(Credential credential, String userId, Map<String, Object> data) {
        String templateId = stringValue(data.get(TEMPLATE_ID_FIELD));
        JsonNode template = fetchCredentialTemplate(templateId);
        if (template != null) {
            rememberCredentialTemplateReference(data, template);
            return template;
        }

        String variant = stringValue(data.get(TEMPLATE_VARIANT_FIELD));
        if (variant == null) {
            variant = "oauth2";
        }

        for (String key : credentialTemplateKeyCandidates(credential, data)) {
            template = fetchCredentialTemplateByStableKey(key, variant);
            if (template == null) {
                continue;
            }

            String oldTemplateId = templateId;
            rememberCredentialTemplateReference(data, template);
            String repairedTemplateId = stringValue(data.get(TEMPLATE_ID_FIELD));
            if (repairedTemplateId != null && !repairedTemplateId.equals(oldTemplateId)) {
                try {
                    credentialService.updateCredentialData(credential.id(), userId, new HashMap<>(data));
                    log.info("Repaired OAuth2 credential {} template reference from {} to {} using key {}",
                            credential.id(), oldTemplateId, repairedTemplateId, key);
                } catch (Exception e) {
                    log.warn("Failed to persist repaired OAuth2 template reference for credential {}: {}",
                            credential.id(), e.getMessage());
                }
            }
            return template;
        }
        return null;
    }

    private List<String> credentialTemplateKeyCandidates(Credential credential, Map<String, Object> data) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        addNonBlank(keys, stringValue(data.get(TEMPLATE_KEY_FIELD)));
        addNonBlank(keys, stringValue(data.get(TEMPLATE_ICON_SLUG_FIELD)));
        addNonBlank(keys, credential.integration());
        return List.copyOf(keys);
    }

    private JsonNode fetchCredentialTemplateByStableKey(String key, String variant) {
        try {
            return catalogClient.get()
                    .uri("/api/catalog/credentials/resolve?key={key}&variant={variant}&includeInactive=true",
                            key, variant)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
        } catch (Exception e) {
            log.warn("Failed to resolve credential template by key {} variant {}: {}",
                    key, variant, e.getMessage());
            return null;
        }
    }

    private void rememberCredentialTemplateReference(Map<String, Object> data, JsonNode template) {
        if (template == null || template.isMissingNode() || template.isNull()) {
            return;
        }
        putIfText(data, TEMPLATE_ID_FIELD, template.path("id"));
        putIfText(data, TEMPLATE_KEY_FIELD, template.path("credential_name"));
        putIfText(data, TEMPLATE_ICON_SLUG_FIELD, template.path("icon_slug"));
        String variant = template.path("variant").asText(null);
        if (variant == null || variant.isBlank()) {
            variant = template.path("auth_type").asText(null);
        }
        if (variant != null && !variant.isBlank()) {
            data.put(TEMPLATE_VARIANT_FIELD, variant);
        }
    }

    private void putIfText(Map<String, Object> data, String key, JsonNode value) {
        if (value != null && value.isTextual() && !value.asText().isBlank()) {
            data.put(key, value.asText());
        }
    }

    private void addNonBlank(Set<String> values, String value) {
        if (value != null && !value.isBlank()) {
            values.add(value);
        }
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    /**
     * Extract OAuth2 configuration from a template, checking {@code metadata.oauth2Config}
     * (current format written by the api-migration importer). Supports both the direct-object
     * shape and the legacy {@code metadata.value = "<json-string>"} shape written by older
     * importers.
     */
    private OAuth2ProviderConfig extractOAuth2Config(JsonNode template) {
        JsonNode metadata = template.path("metadata");

        // Try sources in order of precedence; first non-null wins. If any throws or returns
        // null, we just fall through to the next source instead of short-circuiting to the
        // legacy property path.

        // Source 1 - legacy wrapping: {"value": "<stringified JSON>"}
        if (metadata.has("value")) {
            try {
                JsonNode metadataValue = objectMapper.readTree(metadata.path("value").asText());
                OAuth2ProviderConfig cfg = OAuth2ProviderConfig.fromJson(metadataValue.path("oauth2Config"));
                if (cfg != null) return cfg;
            } catch (Exception e) {
                log.warn("Failed to parse metadata.value as JSON, falling through: {}", e.getMessage());
            }
        }

        // Source 2 - direct object shape: metadata.oauth2Config
        if (!metadata.isMissingNode() && metadata.isObject()) {
            OAuth2ProviderConfig cfg = OAuth2ProviderConfig.fromJson(metadata.path("oauth2Config"));
            if (cfg != null) return cfg;
        }

        // Source 3 - legacy property-based fallback: build a minimal config from template.properties.
        String authUrl = extractPropertyDefault(template, "authUrl");
        String tokenUrl = extractPropertyDefault(template, "accessTokenUrl");
        if (authUrl == null || tokenUrl == null) {
            return null;
        }
        String scope = extractPropertyDefault(template, "scope");
        return new OAuth2ProviderConfig(
                authUrl,
                tokenUrl,
                null,
                scope == null || scope.isEmpty() ? List.of() : List.of(scope.split("\\s+")),
                " ",
                OAuth2ProviderConfig.AuthMethod.POST,
                "pkce".equalsIgnoreCase(extractPropertyDefault(template, "grantType")),
                Map.of(),
                OAuth2ProviderConfig.RefreshConfig.STANDARD
        );
    }

    /**
     * Resolve the OAuth2 platform_credential row for this template, org-aware (V362), trying the
     * same key candidates the legacy {@code getOAuth2Credentials} chain used: icon_slug first (the
     * canonical key written by the importer), then the request/display integration name, then the
     * template's {@code credential_name}. Resolution priority (per key): workspace-org BYOK &gt;
     * personal BYOK &gt; platform-wide. {@code organizationId} is the workspace the user initiated
     * the connect from (may be null = personal scope).
     */
    private Optional<PlatformCredentialModels.PlatformCredential> resolveOAuth2PlatformRow(
            String iconSlug, String integrationName, JsonNode template, String userId, String organizationId) {
        var row = platformCredentialService.getRawOAuth2Credential(iconSlug, userId, organizationId);
        if (row.isEmpty()) {
            row = platformCredentialService.getRawOAuth2Credential(integrationName, userId, organizationId);
        }
        if (row.isEmpty()) {
            String templateCredName = template.path("credential_name").asText(null);
            if (templateCredName != null) {
                row = platformCredentialService.getRawOAuth2Credential(templateCredName, userId, organizationId);
            }
        }
        return row;
    }

    /**
     * Build the scope set to request for a BYOK connect. The CATALOG is the single source of
     * truth: the user's own OAuth client requests everything the integration declares -
     * <ol>
     *   <li>the platform-template scopes ({@code oauth2Config.scopes}), and</li>
     *   <li>the catalog {@code byokOnlyScopes} - restricted scopes the platform-shared client
     *       deliberately never asks for (e.g. Gmail {@code readonly}/{@code modify}), grantable
     *       only through the user's own verified app.</li>
     * </ol>
     * Deliberately does NOT read the per-tenant row's {@code default_scopes}: that is a save-time
     * copy of the catalog set that drifts when the catalog changes (it once re-introduced the
     * conflicting {@code gmail.metadata} scope after the catalog had dropped it - Gmail enforces
     * the metadata restriction even alongside {@code readonly}, breaking the {@code q} search).
     * Keeping the catalog authoritative means a catalog re-import is the only thing needed to
     * change scopes - never per-row data patching. For custom APIs (no catalog
     * {@code oauth2Config}) the scopes are already carried in {@code providerConfig.scopes()},
     * populated from the row by the custom-API fallback in {@link #initiate}, so they survive here.
     * Order-preserving and de-duplicated. Empty only when the template declares no scopes at all.
     */
    private List<String> resolveByokScopes(JsonNode template, OAuth2ProviderConfig providerConfig) {
        LinkedHashSet<String> scopes = new LinkedHashSet<>(providerConfig.scopes());
        scopes.addAll(extractByokOnlyScopes(template));
        return new ArrayList<>(scopes);
    }

    /**
     * True in the CE monolith ({@code auth.mode=embedded}). CE has no platform-shared OAuth
     * app - every OAuth client the install uses is supplied by the user - so {@link #initiate}
     * always requests the full catalog scope set (platform scopes + byokOnlyScopes) instead of
     * gating the byok-only scopes on a tenant-scoped (BYOK) credential row like Cloud does.
     */
    private boolean isCeEmbeddedMode() {
        return "embedded".equalsIgnoreCase(authMode);
    }

    /**
     * Pull {@code metadata.oauth2Config.byokOnlyScopes} out of a credential template, handling both
     * the legacy {@code metadata.value = "<json-string>"} wrapping and the direct-object shape -
     * the same two sources {@link #extractOAuth2Config} reads. Returns an empty list when the block
     * is absent or malformed (the vast majority of APIs declare no byokOnlyScopes).
     */
    private List<String> extractByokOnlyScopes(JsonNode template) {
        JsonNode metadata = template.path("metadata");
        JsonNode oauth2Config = null;
        if (metadata.has("value")) {
            try {
                JsonNode metadataValue = objectMapper.readTree(metadata.path("value").asText());
                oauth2Config = metadataValue.path("oauth2Config");
            } catch (Exception e) {
                log.debug("byokOnlyScopes: failed to parse metadata.value, trying direct shape: {}",
                        e.getMessage());
            }
        }
        if ((oauth2Config == null || oauth2Config.isMissingNode() || oauth2Config.isNull())
                && metadata.isObject()) {
            oauth2Config = metadata.path("oauth2Config");
        }
        if (oauth2Config == null) {
            return List.of();
        }
        JsonNode byokOnly = oauth2Config.path("byokOnlyScopes");
        if (!byokOnly.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(byokOnly.size());
        byokOnly.forEach(n -> {
            String s = n.asText(null);
            if (s != null && !s.isBlank()) {
                out.add(s);
            }
        });
        return out;
    }

    private JsonNode fetchCredentialTemplate(String templateId) {
        if (templateId == null || templateId.isBlank()) {
            return null;
        }
        try {
            return catalogClient.get()
                    .uri("/api/catalog/credentials/{id}", templateId)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
        } catch (Exception e) {
            log.warn("Failed to fetch credential template {}: {}", templateId, e.getMessage());
            return null;
        }
    }

    private String extractPropertyDefault(JsonNode template, String propertyName) {
        JsonNode properties = template.path("properties");
        if (properties.isArray()) {
            for (JsonNode prop : properties) {
                if (propertyName.equals(prop.path("name").asText())) {
                    return prop.path("default").asText(null);
                }
            }
        }
        return null;
    }

    private String maskSecret(String secret) {
        if (secret == null || secret.length() < 8) {
            return "****";
        }
        return secret.substring(0, 4) + "****" + secret.substring(secret.length() - 4);
    }

    // ============================================================================
    // Redis state store
    // ============================================================================

    private void saveStateToRedis(String state, OAuth2State oAuth2State) {
        try {
            String json = objectMapper.writeValueAsString(oAuth2State);
            redisTemplate.opsForValue().set(
                    REDIS_STATE_PREFIX + state,
                    json,
                    Duration.ofMinutes(STATE_TTL_MINUTES)
            );
        } catch (Exception e) {
            log.error("Failed to save OAuth2 state to Redis: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to persist OAuth2 state", e);
        }
    }

    private OAuth2State loadStateFromRedis(String state) {
        try {
            String json = redisTemplate.opsForValue().get(REDIS_STATE_PREFIX + state);
            if (json == null) {
                return null;
            }
            return objectMapper.readValue(json, OAuth2State.class);
        } catch (Exception e) {
            log.error("Failed to load OAuth2 state from Redis: {}", e.getMessage(), e);
            return null;
        }
    }

    private void removeStateFromRedis(String state) {
        try {
            redisTemplate.delete(REDIS_STATE_PREFIX + state);
        } catch (Exception e) {
            log.warn("Failed to remove OAuth2 state from Redis: {}", e.getMessage());
        }
    }
}
