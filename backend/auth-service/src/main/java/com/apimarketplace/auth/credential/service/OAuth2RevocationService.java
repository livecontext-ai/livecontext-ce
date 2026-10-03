package com.apimarketplace.auth.credential.service;

import com.apimarketplace.auth.credential.domain.CredentialModels.Credential;
import com.apimarketplace.auth.credential.domain.CredentialModels.CredentialStatus;
import com.apimarketplace.auth.credential.domain.CredentialModels.CredentialType;
import com.apimarketplace.auth.credential.util.OAuth2EndpointGuard;
import com.apimarketplace.common.security.CredentialEncryptionService;
import com.apimarketplace.common.web.NoRedirectSimpleClientHttpRequestFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Tells the PROVIDER that an OAuth2 credential is gone, before our copy of it is deleted (LC-065).
 *
 * <p>Disconnecting used to be a row delete and nothing else: the user believed the relationship
 * had ended while the provider-side grant stayed live for any copy of the refresh token taken
 * earlier. Every deletion path now calls {@link #revoke} first.
 *
 * <p><strong>Where the endpoint comes from.</strong> The catalog template's
 * {@code oauth2Config.revokeUrl} + optional {@code revokeMethod}. Both, together with the token
 * endpoint host and the client authentication style, are copied into {@code credential_data} at
 * connect time so a disconnect still revokes when the catalog is unreachable; a credential
 * connected before those fields existed falls back to its template by {@code credential_template_id}.
 *
 * <p><strong>Three refusals before any request</strong>:
 * <ul>
 *   <li>{@link Outcome#SHARED_GRANT} / {@link Outcome#SHARED_GRANT_UNKNOWN_SUBJECT}: at Google
 *       (and every provider that revokes per account x client), revoking ONE token kills the
 *       whole grant, so disconnecting one of two credentials of the same provider account, in
 *       any tenant, would silently break the other. Siblings are keyed on (client id, token
 *       host, provider subject) across all tenants; the last one to go revokes, and an unknown
 *       subject never revokes while a same-client sibling exists.</li>
 *   <li>{@link Outcome#HOST_MISMATCH}: a self-hosted provider (GitLab at {@code gitlab.acme.com})
 *       mints the token, but the template's static revoke endpoint is the SaaS one
 *       ({@code gitlab.com}). Sending the token there hands provider A's secret to provider B, so
 *       the revoke host must share the token host's registrable domain.</li>
 *   <li>{@link Outcome#ENDPOINT_REJECTED}: non-https, internal address, unresolvable, or a
 *       placeholder other than {@code {client_id}}.</li>
 * </ul>
 *
 * <p><strong>Never blocks the delete.</strong> Every failure is logged, counted ({@value #METRIC})
 * and audited, and the caller proceeds. No redirect is followed.
 */
@Service
public class OAuth2RevocationService {

    private static final Logger log = LoggerFactory.getLogger(OAuth2RevocationService.class);

    /** {@code credential_data} key holding the provider's revocation endpoint. */
    public static final String REVOKE_URL_FIELD = "oauth_revoke_url";
    /** {@code credential_data} key holding the revocation request shape ({@link RevokeMethod}). */
    public static final String REVOKE_METHOD_FIELD = "oauth_revoke_method";
    /** {@code credential_data} key holding the host that minted the tokens. */
    public static final String TOKEN_HOST_FIELD = "oauth_token_host";
    /** {@code credential_data} key: {@code basic} when the client authenticates with HTTP Basic. */
    public static final String CLIENT_AUTH_FIELD = "oauth_client_auth";
    /**
     * {@code credential_data} key: SHA-256 (base64url) of the provider account's stable subject
     * ({@code sub}). A hash, not the id: it is only ever compared, never shown or sent.
     */
    public static final String SUBJECT_FIELD = "oauth_subject";

    /** Counter {@code oauth2_revocation_total{outcome, provider}}. */
    public static final String METRIC = "oauth2_revocation_total";

    private static final String TEMPLATE_ID_FIELD = "credential_template_id";
    private static final int MAX_ATTEMPTS = 2;
    private static final long RETRY_BACKOFF_MS = 250L;

    /** Bounded outcome recorded on the audit trail and the metric. */
    public enum Outcome {
        /** The provider accepted the revocation. */
        REVOKED,
        /** Not an OAuth2 credential, or no token material stored. */
        NOT_APPLICABLE,
        /** The template declares no revocation endpoint. */
        NO_ENDPOINT,
        /** The declared endpoint failed validation (non-https, internal, unresolvable, placeholder). */
        ENDPOINT_REJECTED,
        /** The revoke host is not the token host's domain: the token would leave its provider. */
        HOST_MISMATCH,
        /** Another live credential of the SAME provider account uses the grant; revoking would break it. */
        SHARED_GRANT,
        /**
         * Another live credential uses the same client at the same host, and the provider account of
         * one of the two is unknown: it may be the same grant, so nothing is sent.
         */
        SHARED_GRANT_UNKNOWN_SUBJECT,
        /** The provider was contacted and refused, or was unreachable. The grant may be live. */
        FAILED;

        public boolean isSuccess() {
            return this == REVOKED;
        }

        public String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Request shape, from the template's optional {@code revokeMethod}. */
    public enum RevokeMethod {
        /** RFC 7009: form POST {@code token} + {@code token_type_hint}, client auth per authMethod. */
        RFC7009,
        /** POST with {@code Authorization: Bearer <access token>}, no body (Dropbox). */
        BEARER,
        /** DELETE with HTTP Basic client auth and JSON {@code {"access_token": ...}} (GitHub). */
        BASIC_DELETE;

        static RevokeMethod parse(String value) {
            if (value == null || value.isBlank()) {
                return RFC7009;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "bearer" -> BEARER;
                case "basic_delete" -> BASIC_DELETE;
                case "rfc7009" -> RFC7009;
                default -> null;
            };
        }

        String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** What a credential needs to be revoked; persisted into {@code credential_data} at connect. */
    public record RevocationSpec(String revokeUrl, String method, String tokenHost, boolean basicClientAuth) {
        /** Writes the spec into {@code credential_data} (no secret in it). */
        public void writeTo(Map<String, Object> data) {
            data.put(REVOKE_URL_FIELD, revokeUrl);
            data.put(REVOKE_METHOD_FIELD, method);
            if (tokenHost != null) {
                data.put(TOKEN_HOST_FIELD, tokenHost);
            }
            if (basicClientAuth) {
                data.put(CLIENT_AUTH_FIELD, "basic");
            }
        }
    }

    private final CredentialEncryptionService encryptionService;
    private final ObjectMapper objectMapper;
    private final WebClient catalogClient;
    private RestTemplate restTemplate;

    @Autowired(required = false)
    private CredentialAuditRecorder auditRecorder;

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    public OAuth2RevocationService(
            CredentialEncryptionService encryptionService,
            ObjectMapper objectMapper,
            @Value("${services.catalog-url:http://localhost:8081}") String catalogServiceUrl,
            WebClient.Builder webClientBuilder) {
        this.encryptionService = encryptionService;
        this.objectMapper = objectMapper;
        // CASA LC-032: in the CE monolith, catalog-url IS this JVM's own loopback port, so this is
        // an in-process call, not an external one. Building the client from a static
        // WebClient.builder() call (as before) makes an unstamped client:
        // InProcessCallStampingBeanPostProcessor only reaches WebClient.Builder BEANS, never a
        // WebClient already built inline - cloning the injected builder (never mutate the shared
        // instance) carries the in-process secret filter through to the built client. Same pattern
        // as OAuth2Service.catalogClient.
        this.catalogClient = webClientBuilder.clone().baseUrl(catalogServiceUrl).build();
        NoRedirectSimpleClientHttpRequestFactory factory = new NoRedirectSimpleClientHttpRequestFactory();
        // Bounded: this runs synchronously on the user's disconnect and after the purges commit.
        factory.setConnectTimeout(3_000);
        factory.setReadTimeout(5_000);
        this.restTemplate = decidingStatuses(new RestTemplate(factory));
    }

    /**
     * Statuses are DECIDED in {@link #send}, not thrown: the 4xx (final) and 5xx (retry) branches
     * there are the real path rather than dead code behind the default error handler.
     */
    private static RestTemplate decidingStatuses(RestTemplate template) {
        template.setErrorHandler(new ResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }

            @Override
            public void handleError(ClientHttpResponse response) {
                // Never called: hasError always answers false.
            }
        });
        return template;
    }

    /** Test seam: swap the HTTP client (a mock server); the status handling stays the same. */
    void setRestTemplate(RestTemplate restTemplate) {
        this.restTemplate = decidingStatuses(restTemplate);
    }

    /** Test seam: wire collaborators without a Spring context. */
    void setCollaborators(CredentialAuditRecorder auditRecorder, MeterRegistry meterRegistry) {
        this.auditRecorder = auditRecorder;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Revocation spec declared by a catalog template, or null when it declares no endpoint or an
     * unknown {@code revokeMethod}. {@code resolvedTokenUrl} is the token endpoint actually used
     * for this connection (per-instance hosts already substituted).
     */
    public static RevocationSpec specOf(JsonNode template, String resolvedTokenUrl, ObjectMapper objectMapper) {
        JsonNode config = oauth2Config(template, objectMapper);
        if (config == null) {
            return null;
        }
        String url = config.path("revokeUrl").asText(null);
        if (url == null || url.isBlank()) {
            return null;
        }
        RevokeMethod method = RevokeMethod.parse(config.path("revokeMethod").asText(null));
        if (method == null) {
            return null;
        }
        String tokenUrl = resolvedTokenUrl != null ? resolvedTokenUrl : config.path("tokenUrl").asText(null);
        boolean basic = config.path("authMethod").asText("").toLowerCase(Locale.ROOT).contains("basic");
        return new RevocationSpec(url.trim(), method.wire(), hostOf(tokenUrl), basic);
    }

    /** Single-credential revoke, no sibling check. Prefer {@link #revoke(Credential, Collection)}. */
    public Outcome revoke(Credential credential) {
        return revoke(credential, List.of());
    }

    /**
     * Best-effort revoke of {@code credential} at its provider. Never throws.
     *
     * @param survivors credentials that stay after this removal (same tenant); when one of them
     *                  still holds a token of the same grant, nothing is sent ({@link Outcome#SHARED_GRANT})
     * @return the bounded outcome, which the caller attaches to its delete audit event
     */
    public Outcome revoke(Credential credential, Collection<Credential> survivors) {
        try {
            return record(credential, doRevoke(credential, survivors == null ? List.of() : survivors));
        } catch (RuntimeException unexpected) {
            log.warn("Revocation of credential {} failed unexpectedly: {}",
                    credential != null ? credential.id() : null, unexpected.getClass().getSimpleName());
            return record(credential, Outcome.FAILED);
        }
    }

    private Outcome doRevoke(Credential credential, Collection<Credential> survivors) {
        if (credential == null || credential.type() != CredentialType.OAuth2) {
            return Outcome.NOT_APPLICABLE;
        }
        Map<String, Object> data = credential.credentialData();
        if (data == null || !holdsToken(data)) {
            return Outcome.NOT_APPLICABLE;
        }

        // The CURRENT template wins over what was stored at connect: a re-imported catalog that
        // fixed or removed a revoke endpoint must take effect on existing credentials too. The
        // stored spec is only the fallback for when the catalog cannot be reached.
        RevocationSpec spec;
        JsonNode template = fetchTemplate(asText(data.get(TEMPLATE_ID_FIELD)));
        if (template != null) {
            spec = specFromTemplate(template, data);
        } else {
            spec = storedSpec(data);
        }
        if (spec == null) {
            return Outcome.NO_ENDPOINT;
        }
        RevokeMethod method = RevokeMethod.parse(spec.method());
        if (method == null) {
            return Outcome.ENDPOINT_REJECTED;
        }

        String clientId = clientIdOf(data);
        GrantShare share = grantShare(credential, clientId, spec.tokenHost(), survivors);
        if (share != GrantShare.NONE) {
            log.info("Not revoking credential {} at its provider: another live credential may share its grant ({})",
                    credential.id(), share);
            return share == GrantShare.SAME_ACCOUNT ? Outcome.SHARED_GRANT : Outcome.SHARED_GRANT_UNKNOWN_SUBJECT;
        }

        String url = spec.revokeUrl();
        if (url.contains("{client_id}")) {
            if (clientId == null) {
                return Outcome.ENDPOINT_REJECTED;
            }
            url = url.replace("{client_id}", URLEncoder.encode(clientId, StandardCharsets.UTF_8));
        }
        try {
            OAuth2EndpointGuard.assertSafeForUse(url, "revokeUrl");
        } catch (IllegalArgumentException rejected) {
            log.warn("Refusing the revocation endpoint of credential {}: {}", credential.id(), rejected.getMessage());
            return Outcome.ENDPOINT_REJECTED;
        }
        if (!sameRegistrableDomain(hostOf(url), spec.tokenHost())) {
            log.warn("Not revoking credential {}: revoke host is not the token host's domain", credential.id());
            return Outcome.HOST_MISMATCH;
        }

        String clientSecret = decrypted(data, "oauth_client_secret");
        if (clientSecret == null) {
            clientSecret = decrypted(data, "client_secret");
        }
        HttpEntity<?> request = buildRequest(method, data, clientId, clientSecret, spec.basicClientAuth());
        if (request == null) {
            return Outcome.NOT_APPLICABLE;
        }
        HttpMethod verb = method == RevokeMethod.BASIC_DELETE ? HttpMethod.DELETE : HttpMethod.POST;
        return send(url, verb, request, credential.id());
    }

    private HttpEntity<?> buildRequest(RevokeMethod method, Map<String, Object> data,
                                       String clientId, String clientSecret, boolean basicClientAuth) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set(HttpHeaders.USER_AGENT, OAuth2Engine.TOKEN_REQUEST_USER_AGENT);
        String accessToken = decrypted(data, "access_token");
        switch (method) {
            case BEARER -> {
                if (accessToken == null) {
                    return null;
                }
                headers.setBearerAuth(accessToken);
                return new HttpEntity<>(null, headers);
            }
            case BASIC_DELETE -> {
                if (accessToken == null || clientId == null || clientSecret == null) {
                    return null;
                }
                headers.setBasicAuth(clientId, clientSecret, StandardCharsets.UTF_8);
                headers.setContentType(MediaType.APPLICATION_JSON);
                return new HttpEntity<>(Map.of("access_token", accessToken), headers);
            }
            default -> {
                String tokenTypeHint = "refresh_token";
                String token = decrypted(data, "refresh_token");
                if (token == null) {
                    token = accessToken;
                    tokenTypeHint = "access_token";
                }
                if (token == null) {
                    return null;
                }
                MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
                body.add("token", token);
                body.add("token_type_hint", tokenTypeHint);
                // One client authentication method only (RFC 6749 2.3): the template's.
                if (basicClientAuth && clientId != null && clientSecret != null) {
                    headers.setBasicAuth(clientId, clientSecret, StandardCharsets.UTF_8);
                } else {
                    if (clientId != null) {
                        body.add("client_id", clientId);
                    }
                    if (clientSecret != null) {
                        body.add("client_secret", clientSecret);
                    }
                }
                headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
                return new HttpEntity<>(body, headers);
            }
        }
    }

    private Outcome send(String url, HttpMethod verb, HttpEntity<?> request, Long credentialId) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                ResponseEntity<String> response = restTemplate.exchange(URI.create(url), verb, request, String.class);
                if (response.getStatusCode().is2xxSuccessful()) {
                    // Some providers (Slack) answer 200 with {"ok": false, "error": ...}: that is a
                    // refusal, not a revocation.
                    if (reportsNotOk(response.getBody())) {
                        log.warn("Provider answered ok=false to the revocation of credential {}", credentialId);
                        return Outcome.FAILED;
                    }
                    return Outcome.REVOKED;
                }
                if (response.getStatusCode().is4xxClientError()) {
                    // Final: RFC 7009 answers 400 for a token that is already invalid.
                    log.warn("Provider refused the revocation of credential {} with status {}",
                            credentialId, response.getStatusCode().value());
                    return Outcome.FAILED;
                }
                log.warn("Revocation attempt {}/{} for credential {} returned status {}",
                        attempt, MAX_ATTEMPTS, credentialId, response.getStatusCode().value());
            } catch (RuntimeException transientFailure) {
                // Class only: the request carries the token, and nothing here needs the message.
                log.warn("Revocation attempt {}/{} for credential {} failed: {}",
                        attempt, MAX_ATTEMPTS, credentialId, transientFailure.getClass().getSimpleName());
            }
            if (attempt < MAX_ATTEMPTS) {
                sleepBeforeRetry();
            }
        }
        return Outcome.FAILED;
    }

    /** Whether revoking would also end another live credential's grant. */
    enum GrantShare { NONE, SAME_ACCOUNT, UNKNOWN_ACCOUNT }

    /**
     * A provider revokes per (provider account x OAuth client), whichever of our tenants holds the
     * tokens: the same Google account connected by two users through the platform client is ONE
     * grant. So the siblings are keyed on (client id, token host, provider subject) across ALL
     * tenants. When either subject is unknown the grant might be shared, and we do not revoke.
     */
    static GrantShare grantShare(Credential credential, String clientId, String tokenHost,
                                 Collection<Credential> survivors) {
        if (clientId == null) {
            return GrantShare.NONE;
        }
        String mySubject = asText(credential.credentialData().get(SUBJECT_FIELD));
        boolean unknown = false;
        for (Credential other : survivors) {
            if (other == null || Objects.equals(other.id(), credential.id())
                    || other.type() != CredentialType.OAuth2
                    || other.status() == CredentialStatus.error
                    || other.status() == CredentialStatus.needs_reauth) {
                continue;
            }
            Map<String, Object> d = other.credentialData();
            if (d == null || !holdsToken(d) || !clientId.equals(clientIdOf(d))) {
                continue;
            }
            String otherHost = asText(d.get(TOKEN_HOST_FIELD));
            if (tokenHost != null && otherHost != null && !tokenHost.equalsIgnoreCase(otherHost)) {
                continue;
            }
            String otherSubject = asText(d.get(SUBJECT_FIELD));
            if (mySubject == null || otherSubject == null) {
                unknown = true;
            } else if (mySubject.equals(otherSubject)) {
                return GrantShare.SAME_ACCOUNT;
            }
        }
        return unknown ? GrantShare.UNKNOWN_ACCOUNT : GrantShare.NONE;
    }

    private boolean reportsNotOk(String body) {
        if (body == null || body.isBlank() || body.trim().charAt(0) != '{') {
            return false;
        }
        try {
            JsonNode json = objectMapper.readTree(body);
            return json.has("ok") && json.path("ok").isBoolean() && !json.path("ok").asBoolean();
        } catch (Exception notJson) {
            return false;
        }
    }

    private RevocationSpec storedSpec(Map<String, Object> data) {
        String url = asText(data.get(REVOKE_URL_FIELD));
        String tokenHost = asText(data.get(TOKEN_HOST_FIELD));
        if (url == null || tokenHost == null) {
            return null; // connected before the full spec was captured: use the template
        }
        String method = asText(data.get(REVOKE_METHOD_FIELD));
        return new RevocationSpec(url, method == null ? RevokeMethod.RFC7009.wire() : method, tokenHost,
                "basic".equals(asText(data.get(CLIENT_AUTH_FIELD))));
    }

    /**
     * The spec from the credential's current catalog template. The token host is the one stored at
     * connect (the resolved URL actually used) when present, else the template's token URL with
     * the credential's own per-instance values substituted.
     */
    private RevocationSpec specFromTemplate(JsonNode template, Map<String, Object> data) {
        JsonNode config = oauth2Config(template, objectMapper);
        if (config == null) {
            return null;
        }
        String tokenUrl = config.path("tokenUrl").asText(null);
        if (tokenUrl != null) {
            for (Map.Entry<String, Object> e : data.entrySet()) {
                if (e.getValue() instanceof String v && !v.isBlank()) {
                    tokenUrl = tokenUrl.replace("{" + e.getKey() + "}", v);
                }
            }
            if (tokenUrl.contains("{")) {
                tokenUrl = null; // host unknown: HOST_MISMATCH below, never a guess
            }
        }
        RevocationSpec spec = specOf(template, tokenUrl, objectMapper);
        if (spec == null) {
            return null;
        }
        String storedHost = asText(data.get(TOKEN_HOST_FIELD));
        String tokenHost = storedHost != null ? storedHost : (tokenUrl == null ? null : spec.tokenHost());
        return new RevocationSpec(spec.revokeUrl(), spec.method(), tokenHost, spec.basicClientAuth());
    }

    private JsonNode fetchTemplate(String templateId) {
        if (templateId == null) {
            return null;
        }
        try {
            return catalogClient.get()
                    .uri("/api/catalog/credentials/{id}", templateId)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
        } catch (Exception e) {
            log.warn("Could not load credential template {} for revocation: {}",
                    templateId, e.getClass().getSimpleName());
            return null;
        }
    }

    /** {@code metadata.oauth2Config} in both catalog shapes (direct object, legacy wrapped string). */
    static JsonNode oauth2Config(JsonNode template, ObjectMapper objectMapper) {
        if (template == null || template.isNull() || template.isMissingNode()) {
            return null;
        }
        JsonNode metadata = template.path("metadata");
        JsonNode config = metadata.path("oauth2Config");
        if (metadata.has("value")) {
            try {
                JsonNode wrapped = objectMapper.readTree(metadata.path("value").asText());
                if (wrapped.path("oauth2Config").isObject()) {
                    config = wrapped.path("oauth2Config");
                }
            } catch (Exception malformed) {
                // Fall back to the direct shape.
            }
        }
        return config.isObject() ? config : null;
    }

    /** Extracts {@code oauth2Config.revokeUrl} from a catalog credential template. */
    static String revokeUrlOf(JsonNode template, ObjectMapper objectMapper) {
        JsonNode config = oauth2Config(template, objectMapper);
        String url = config == null ? null : config.path("revokeUrl").asText(null);
        return url == null || url.isBlank() ? null : url.trim();
    }

    /**
     * Registrable-domain comparison without a public-suffix list: the last two labels, or the
     * last three when the second-level label is a short one under a two-letter country code
     * ({@code co.uk}, {@code com.au}). Conservative by design: an unknown host never matches.
     */
    static boolean sameRegistrableDomain(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        String ra = registrableDomain(a);
        return ra != null && ra.equals(registrableDomain(b));
    }

    static String registrableDomain(String host) {
        if (host == null || host.isBlank()) {
            return null;
        }
        String h = host.toLowerCase(Locale.ROOT);
        if (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        if (h.contains(":") || h.matches("[0-9.]+")) {
            return h; // IP literal: exact match only
        }
        String[] labels = h.split("\\.");
        if (labels.length <= 2) {
            return h;
        }
        int n = labels.length;
        boolean ccSld = labels[n - 1].length() == 2 && labels[n - 2].length() <= 3;
        int keep = ccSld ? 3 : 2;
        StringBuilder sb = new StringBuilder();
        for (int i = n - keep; i < n; i++) {
            if (sb.length() > 0) sb.append('.');
            sb.append(labels[i]);
        }
        return sb.toString();
    }

    static String hostOf(String url) {
        if (url == null || url.isBlank() || url.contains("{")) {
            return null;
        }
        try {
            String host = URI.create(url.trim()).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    private static boolean holdsToken(Map<String, Object> data) {
        return asText(data.get("refresh_token")) != null || asText(data.get("access_token")) != null;
    }

    private static String clientIdOf(Map<String, Object> data) {
        String clientId = asText(data.get("oauth_client_id"));
        return clientId != null ? clientId : asText(data.get("client_id"));
    }

    private Outcome record(Credential credential, Outcome outcome) {
        String provider = credential != null && credential.integration() != null
                ? credential.integration() : "unknown";
        if (meterRegistry != null) {
            try {
                Counter.builder(METRIC)
                        .description("OAuth2 provider revocations attempted before a credential delete")
                        .tag("outcome", outcome.tag())
                        .tag("provider", provider)
                        .register(meterRegistry)
                        .increment();
            } catch (Exception ignored) {
                // A metric must never break a delete.
            }
        }
        if (auditRecorder != null && credential != null && outcome != Outcome.NOT_APPLICABLE) {
            // SHARED_GRANT is the intended answer (the grant stays for its sibling), not a failure.
            auditRecorder.recordProviderRevocation(credential.tenantId(), credential.id(),
                    credential.integration(), outcome.tag(),
                    outcome.isSuccess() || outcome == Outcome.SHARED_GRANT
                            || outcome == Outcome.SHARED_GRANT_UNKNOWN_SUBJECT);
        }
        return outcome;
    }

    private String decrypted(Map<String, Object> data, String field) {
        String raw = asText(data.get(field));
        if (raw == null) {
            return null;
        }
        try {
            String value = encryptionService.decrypt(raw);
            return value == null || value.isBlank() ? null : value;
        } catch (RuntimeException undecryptable) {
            log.warn("Could not decrypt '{}' for revocation: {}", field,
                    undecryptable.getClass().getSimpleName());
            return null;
        }
    }

    private static String asText(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    private static void sleepBeforeRetry() {
        try {
            Thread.sleep(RETRY_BACKOFF_MS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
