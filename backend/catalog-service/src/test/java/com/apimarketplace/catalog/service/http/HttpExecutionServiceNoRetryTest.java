package com.apimarketplace.catalog.service.http;

import com.apimarketplace.catalog.domain.ApiEntity;
import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.repository.ApiToolParameterRepository;
import com.apimarketplace.catalog.service.UserCredentialService;
import com.apimarketplace.common.web.UrlSafetyValidator;
import com.apimarketplace.common.security.CredentialEncryptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The platform never re-sends a provider call on its own. A refusal, a 429 included, is returned
 * on the first answer on every dispatch path, and retrying is the caller's decision (a workflow
 * node's retryCount, or the agent). What {@link ErrorPolicyEngine} still does is wording: a
 * declared {@code user_error} replaces the provider's raw body on every path.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HttpExecutionService - a provider call is sent once, never re-sent by the platform")
class HttpExecutionServiceNoRetryTest {

    @Mock private ApiToolParameterRepository apiToolParameterRepository;
    @Mock private UserCredentialService userCredentialService;
    @Mock private CredentialEncryptionService encryptionService;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private RestTemplate restTemplate;

    private HttpExecutionService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        lenient().when(apiToolParameterRepository.findByApiToolId(any())).thenReturn(List.of());
        service = new HttpExecutionService(
                apiToolParameterRepository, userCredentialService, encryptionService,
                objectMapper, jdbcTemplate, restTemplate, new ErrorPolicyEngine());
    }

    private ApiEntity api() {
        ApiEntity api = new ApiEntity();
        api.setId(UUID.randomUUID());
        api.setBaseUrl("http://api.example.com");
        api.setApiName("Test API");
        return api;
    }

    private ApiToolEntity tool() {
        ApiToolEntity tool = new ApiToolEntity();
        tool.setId(UUID.randomUUID());
        tool.setEndpoint("/things");
        tool.setMethod("GET");
        return tool;
    }

    private static HttpClientErrorException tooManyRequests(String retryAfter) {
        HttpHeaders headers = new HttpHeaders();
        if (retryAfter != null) {
            headers.set(HttpHeaders.RETRY_AFTER, retryAfter);
        }
        return HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", headers,
                "{\"error\":\"rate limited\"}".getBytes(), null);
    }

    @Test
    @DisplayName("REGRESSION: a 429 is sent exactly once and returned as it stands")
    void a429IsSentOnceOnTheCredentialedPath() {
        // Prod 2026-09-29: an OpenAI admin endpoint answered 429 and the platform re-sent the
        // same GET twice more within four seconds, invisibly, against a one-minute window. The
        // platform no longer re-sends: the caller (a node's retryCount, or the agent) decides.
        try (MockedStatic<UrlSafetyValidator> urlValidator = mockStatic(UrlSafetyValidator.class)) {
            urlValidator.when(() -> UrlSafetyValidator.validateUrl(anyString())).thenAnswer(i -> null);

            when(restTemplate.exchange(any(java.net.URI.class), any(HttpMethod.class), any(), eq(Object.class)))
                    .thenThrow(tooManyRequests("0"))
                    .thenReturn(new ResponseEntity<>(Map.of("ok", true), HttpStatus.OK));

            Map<String, Object> result = service.executeHttpCallWithCredentials(
                    api(), tool(), objectMapper.createArrayNode(), Set.of(), null, null);

            assertThat(result.get("success")).isEqualTo(false);
            assertThat(result.get("status")).isEqualTo(429);
            assertThat(String.valueOf(result.get("errorBody"))).contains("rate limited");
            verify(restTemplate, times(1))
                    .exchange(any(java.net.URI.class), any(HttpMethod.class), any(), eq(Object.class));
        }
    }

    @Test
    @DisplayName("REGRESSION: the typed path sends a 429 once too")
    void a429IsSentOnceOnTheTypedPath() {
        try (MockedStatic<UrlSafetyValidator> urlValidator = mockStatic(UrlSafetyValidator.class)) {
            urlValidator.when(() -> UrlSafetyValidator.validateUrl(anyString())).thenAnswer(i -> null);

            when(restTemplate.exchange(any(java.net.URI.class), any(HttpMethod.class), any(), eq(Object.class)))
                    .thenThrow(tooManyRequests("0"))
                    .thenReturn(new ResponseEntity<>(Map.of("ok", true), HttpStatus.OK));

            Map<String, Object> result = service.executeHttpCallTyped(
                    api(), typedTool(), objectMapper.createArrayNode(), Set.of(), null, null, null);

            assertThat(result.get("success")).isEqualTo(false);
            verify(restTemplate, times(1))
                    .exchange(any(java.net.URI.class), any(HttpMethod.class), any(), eq(Object.class));
        }
    }

    @Test
    @DisplayName("REGRESSION: the binary-response path sends a 429 once too")
    void a429IsSentOnceOnTheBinaryPath() throws Exception {
        try (MockedStatic<UrlSafetyValidator> urlValidator = mockStatic(UrlSafetyValidator.class)) {
            urlValidator.when(() -> UrlSafetyValidator.validateUrl(anyString())).thenAnswer(i -> null);
            var handler = org.mockito.Mockito.mock(
                    com.apimarketplace.catalog.service.execution.BinaryResponseHandler.class);
            lenient().when(handler.handle(any(), any(), any(), any(), any())).thenReturn(Map.of("path", "f.png"));
            java.lang.reflect.Field f =
                    HttpExecutionService.class.getDeclaredField("binaryResponseHandler");
            f.setAccessible(true);
            f.set(service, handler);

            ApiToolEntity binaryTool = new ApiToolEntity();
            binaryTool.setId(UUID.randomUUID());
            binaryTool.setMethod("POST");
            binaryTool.setEndpoint("/render");
            binaryTool.setExecutionSpec("{\"mode\":\"sync\",\"request\":{\"bodyType\":\"json\"},"
                    + "\"response\":{\"type\":\"binary\"}}");
            binaryTool.setOutputSchema("[]");

            when(restTemplate.exchange(any(java.net.URI.class), any(HttpMethod.class), any(), eq(byte[].class)))
                    .thenThrow(tooManyRequests("0"))
                    .thenReturn(new ResponseEntity<>(new byte[] {1, 2, 3}, HttpStatus.OK));

            Map<String, Object> result = service.executeHttpCallTyped(
                    api(), binaryTool, objectMapper.createArrayNode(), Set.of(), null, null, null);

            assertThat(result.get("success")).isEqualTo(false);
            verify(restTemplate, times(1))
                    .exchange(any(java.net.URI.class), any(HttpMethod.class), any(), eq(byte[].class));
        }
    }

    @Test
    @DisplayName("REGRESSION: the legacy (non-credentialed) path sends a 429 once too")
    void a429IsSentOnceOnTheLegacyPath() {
        try (MockedStatic<UrlSafetyValidator> urlValidator = mockStatic(UrlSafetyValidator.class)) {
            urlValidator.when(() -> UrlSafetyValidator.validateUrl(anyString())).thenAnswer(i -> null);

            lenient().when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(), eq(Object.class)))
                    .thenThrow(tooManyRequests("0"))
                    .thenReturn(new ResponseEntity<>(Map.of("ok", true), HttpStatus.OK));

            Map<String, Object> result = service.executeHttpCall(
                    api(), tool(), objectMapper.createArrayNode(), Set.of());

            assertThat(result.get("success")).isNotEqualTo(true);
            verify(restTemplate, times(1))
                    .exchange(anyString(), any(HttpMethod.class), any(), eq(Object.class));
        }
    }

    @Test
    @DisplayName("REGRESSION: a GET answered 503 with a Retry-After is sent once too")
    void a503WithRetryAfterIsSentOnce() {
        // The removed built-in rule also re-sent a safe method on a 503 carrying a Retry-After.
        try (MockedStatic<UrlSafetyValidator> urlValidator = mockStatic(UrlSafetyValidator.class)) {
            urlValidator.when(() -> UrlSafetyValidator.validateUrl(anyString())).thenAnswer(i -> null);
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.RETRY_AFTER, "0");

            when(restTemplate.exchange(any(java.net.URI.class), any(HttpMethod.class), any(), eq(Object.class)))
                    .thenThrow(org.springframework.web.client.HttpServerErrorException.create(
                            HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable", headers,
                            "{}".getBytes(), null))
                    .thenReturn(new ResponseEntity<>(Map.of("ok", true), HttpStatus.OK));

            Map<String, Object> result = service.executeHttpCallWithCredentials(
                    api(), tool(), objectMapper.createArrayNode(), Set.of(), null, null);

            assertThat(result.get("status")).isEqualTo(503);
            verify(restTemplate, times(1))
                    .exchange(any(java.net.URI.class), any(HttpMethod.class), any(), eq(Object.class));
        }
    }

    @Test
    @DisplayName("a 429's Retry-After (delta-seconds) is returned to the caller as retryAfterSeconds")
    void retryAfterDeltaSecondsIsReturned() {
        try (MockedStatic<UrlSafetyValidator> urlValidator = mockStatic(UrlSafetyValidator.class)) {
            urlValidator.when(() -> UrlSafetyValidator.validateUrl(anyString())).thenAnswer(i -> null);
            when(restTemplate.exchange(any(java.net.URI.class), any(HttpMethod.class), any(), eq(Object.class)))
                    .thenThrow(tooManyRequests("42"));

            Map<String, Object> result = service.executeHttpCallWithCredentials(
                    api(), tool(), objectMapper.createArrayNode(), Set.of(), null, null);

            assertThat(result).containsEntry(HttpExecutionService.RETRY_AFTER_SECONDS, 42L);
        }
    }

    @Test
    @DisplayName("REGRESSION: a 403 with a Retry-After on the credentialed path keeps it (GitHub secondary limit)")
    void forbiddenKeepsRetryAfter() {
        try (MockedStatic<UrlSafetyValidator> urlValidator = mockStatic(UrlSafetyValidator.class)) {
            urlValidator.when(() -> UrlSafetyValidator.validateUrl(anyString())).thenAnswer(i -> null);
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.RETRY_AFTER, "60");
            when(restTemplate.exchange(any(java.net.URI.class), any(HttpMethod.class), any(), eq(Object.class)))
                    .thenThrow(HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden", headers,
                            "{\"message\":\"You have exceeded a secondary rate limit\"}".getBytes(), null));

            Map<String, Object> result = service.executeHttpCallWithCredentials(
                    api(), tool(), objectMapper.createArrayNode(), Set.of(), null, null);

            assertThat(result.get("status")).isEqualTo(403);
            assertThat(result).containsEntry(HttpExecutionService.RETRY_AFTER_SECONDS, 60L);
        }
    }

    @Test
    @DisplayName("the legacy (non-credentialed) path returns the Retry-After too")
    void retryAfterOnTheLegacyPath() {
        try (MockedStatic<UrlSafetyValidator> urlValidator = mockStatic(UrlSafetyValidator.class)) {
            urlValidator.when(() -> UrlSafetyValidator.validateUrl(anyString())).thenAnswer(i -> null);
            lenient().when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(), eq(Object.class)))
                    .thenThrow(tooManyRequests("9"));

            Map<String, Object> result = service.executeHttpCall(
                    api(), tool(), objectMapper.createArrayNode(), Set.of());

            assertThat(result).containsEntry(HttpExecutionService.RETRY_AFTER_SECONDS, 9L);
        }
    }

    @Test
    @DisplayName("a refusal right after a token refresh returns the Retry-After too")
    void retryAfterAfterTokenRefresh() {
        try (MockedStatic<UrlSafetyValidator> urlValidator = mockStatic(UrlSafetyValidator.class)) {
            urlValidator.when(() -> UrlSafetyValidator.validateUrl(anyString())).thenAnswer(i -> null);
            ApiEntity api = api();
            api.setPlatformCredentialName("tiktok");
            when(userCredentialService.forceRefreshAndGetToken(any(), any()))
                    .thenReturn(java.util.Optional.of("fresh-token"));
            when(restTemplate.exchange(any(java.net.URI.class), any(HttpMethod.class), any(), eq(Object.class)))
                    .thenThrow(HttpClientErrorException.create(
                            HttpStatus.UNAUTHORIZED, "Unauthorized", new HttpHeaders(), "{}".getBytes(), null));
            when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(), eq(Object.class)))
                    .thenThrow(tooManyRequests("12"));

            Map<String, Object> result = service.executeHttpCallWithCredentials(
                    api, tool(), objectMapper.createArrayNode(), Set.of(), null, null);

            assertThat(result.get("status")).isEqualTo(429);
            assertThat(result).containsEntry(HttpExecutionService.RETRY_AFTER_SECONDS, 12L);
        }
    }

    @Test
    @DisplayName("the typed path returns the Retry-After too")
    void retryAfterOnTheTypedPath() {
        try (MockedStatic<UrlSafetyValidator> urlValidator = mockStatic(UrlSafetyValidator.class)) {
            urlValidator.when(() -> UrlSafetyValidator.validateUrl(anyString())).thenAnswer(i -> null);
            when(restTemplate.exchange(any(java.net.URI.class), any(HttpMethod.class), any(), eq(Object.class)))
                    .thenThrow(tooManyRequests("5"));

            Map<String, Object> result = service.executeHttpCallTyped(
                    api(), typedTool(), objectMapper.createArrayNode(), Set.of(), null, null, null);

            assertThat(result).containsEntry(HttpExecutionService.RETRY_AFTER_SECONDS, 5L);
        }
    }

    @Test
    @DisplayName("Retry-After is read in both forms, rounded up, on any refusal, never on a success")
    void retryAfterParsing() {
        HttpHeaders delta = new HttpHeaders();
        delta.set(HttpHeaders.RETRY_AFTER, "30");
        assertThat(HttpExecutionService.retryAfterSecondsOf(429, delta)).isEqualTo(30L);
        assertThat(HttpExecutionService.retryAfterSecondsOf(503, delta)).isEqualTo(30L);
        assertThat(HttpExecutionService.retryAfterSecondsOf(403, delta))
                .as("GitHub's secondary rate limit is a 403 that still says when to come back")
                .isEqualTo(30L);
        assertThat(HttpExecutionService.retryAfterSecondsOf(200, delta)).isNull();

        HttpHeaders huge = new HttpHeaders();
        huge.set(HttpHeaders.RETRY_AFTER, "99999999999999999");
        assertThat(HttpExecutionService.retryAfterSecondsOf(429, huge))
                .as("REGRESSION: a 17-digit header overflowed toMillis() and threw inside the catch")
                .isEqualTo(86_400L);

        HttpHeaders date = new HttpHeaders();
        date.set(HttpHeaders.RETRY_AFTER, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(
                java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).plusSeconds(90)));
        assertThat(HttpExecutionService.retryAfterSecondsOf(429, date)).isBetween(85L, 91L);

        HttpHeaders junk = new HttpHeaders();
        junk.set(HttpHeaders.RETRY_AFTER, "soon");
        assertThat(HttpExecutionService.retryAfterSecondsOf(429, junk)).isNull();
        assertThat(HttpExecutionService.retryAfterSecondsOf(429, new HttpHeaders())).isNull();
        assertThat(HttpExecutionService.retryAfterSecondsOf(429, null)).isNull();
    }

    @Test
    @DisplayName("a stored legacy `retry` rule is never executed, and its re-send wording is not shown")
    void aLegacyRetryRuleIsNotExecuted() {
        // Rows imported before the retry was removed still carry the rule until re-imported, and
        // a self-hosted install can receive one in a bundle from an older cloud.
        try (MockedStatic<UrlSafetyValidator> urlValidator = mockStatic(UrlSafetyValidator.class)) {
            urlValidator.when(() -> UrlSafetyValidator.validateUrl(anyString())).thenAnswer(i -> null);
            ApiEntity api = api();
            api.setErrorPolicy("[{\"match\":{\"bodyContains\":\"rate_limit_exceeded\"},"
                    + "\"action\":\"retry\",\"waitMs\":250,"
                    + "\"message\":\"The platform already waited and re-sent the call once.\"}]");

            when(restTemplate.exchange(any(java.net.URI.class), any(HttpMethod.class), any(), eq(Object.class)))
                    .thenThrow(HttpClientErrorException.create(
                            HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", new HttpHeaders(),
                            "{\"error\":\"rate_limit_exceeded\"}".getBytes(), null))
                    .thenReturn(new ResponseEntity<>(Map.of("ok", true), HttpStatus.OK));

            Map<String, Object> result = service.executeHttpCallWithCredentials(
                    api, tool(), objectMapper.createArrayNode(), Set.of(), null, null);

            assertThat(result.get("status")).isEqualTo(429);
            assertThat(String.valueOf(result.get("error"))).doesNotContain("re-sent");
            verify(restTemplate, times(1))
                    .exchange(any(java.net.URI.class), any(HttpMethod.class), any(), eq(Object.class));
        }
    }

    @Test
    @DisplayName("an ordinary failure is not retried")
    void doesNotRetryOrdinaryFailures() {
        try (MockedStatic<UrlSafetyValidator> urlValidator = mockStatic(UrlSafetyValidator.class)) {
            urlValidator.when(() -> UrlSafetyValidator.validateUrl(anyString())).thenAnswer(i -> null);
            ArrayNode parameters = objectMapper.createArrayNode();

            when(restTemplate.exchange(any(java.net.URI.class), any(HttpMethod.class), any(), eq(Object.class)))
                    .thenThrow(HttpClientErrorException.create(
                            HttpStatus.BAD_REQUEST, "Bad Request", new HttpHeaders(),
                            "{\"error\":\"nope\"}".getBytes(), null));

            Map<String, Object> result = service.executeHttpCallWithCredentials(
                    api(), tool(), parameters, Set.of(), null, null);

            assertThat(result.get("status")).isEqualTo(400);
            verify(restTemplate, times(1))
                    .exchange(any(java.net.URI.class), any(HttpMethod.class), any(), eq(Object.class));
        }
    }

    @Test
    @DisplayName("a network error is never retried: the outcome is unknown, not a refusal")
    void doesNotRetryNetworkErrors() {
        try (MockedStatic<UrlSafetyValidator> urlValidator = mockStatic(UrlSafetyValidator.class)) {
            urlValidator.when(() -> UrlSafetyValidator.validateUrl(anyString())).thenAnswer(i -> null);
            ArrayNode parameters = objectMapper.createArrayNode();

            when(restTemplate.exchange(any(java.net.URI.class), any(HttpMethod.class), any(), eq(Object.class)))
                    .thenThrow(new org.springframework.web.client.ResourceAccessException("timeout"));

            Map<String, Object> result = service.executeHttpCallWithCredentials(
                    api(), tool(), parameters, Set.of(), null, null);

            assertThat(result.get("success")).isEqualTo(false);
            // This is the safety line of the whole feature: a timed-out POST may have been
            // applied, so re-sending it could publish twice. Only an explicit refusal retries.
            verify(restTemplate, times(1))
                    .exchange(any(java.net.URI.class), any(HttpMethod.class), any(), eq(Object.class));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Typed path - uploads, form-encoded bodies, async submissions. This is the
    // endpoint class a publishing step actually uses, and it returns the provider's RAW
    // body as the error, so both halves of the feature matter more here than anywhere.
    // ─────────────────────────────────────────────────────────────────────────

    private ApiToolEntity typedTool() {
        ApiToolEntity t = new ApiToolEntity();
        t.setId(UUID.randomUUID());
        t.setMethod("POST");
        t.setEndpoint("/publish");
        // mode=upload is enough to take the typed branch (needsTypedExecutionPath), while a plain
        // JSON body keeps the test free of the optional encoders this unit does not wire.
        t.setExecutionSpec("{\"mode\":\"upload\",\"request\":{\"bodyType\":\"json\"},"
                + "\"response\":{\"type\":\"json\"}}");
        t.setOutputSchema("[]");
        return t;
    }

    @Test
    @DisplayName("a declared user_error replaces the raw provider body on the typed path")
    void typedPathUsesTheDeclaredMessage() {
        try (MockedStatic<UrlSafetyValidator> urlValidator = mockStatic(UrlSafetyValidator.class)) {
            urlValidator.when(() -> UrlSafetyValidator.validateUrl(anyString())).thenAnswer(i -> null);
            ArrayNode parameters = objectMapper.createArrayNode();
            ApiEntity api = api();
            api.setErrorPolicy("[{\"match\":{\"bodyContains\":\"unaudited_client\"},"
                    + "\"action\":\"user_error\","
                    + "\"message\":\"This app is not audited: privacy_level must be SELF_ONLY.\"}]");

            when(restTemplate.exchange(any(java.net.URI.class), any(HttpMethod.class), any(), eq(Object.class)))
                    .thenThrow(HttpClientErrorException.create(
                            HttpStatus.BAD_REQUEST, "Bad Request", new HttpHeaders(),
                            "{\"error\":{\"code\":\"unaudited_client_can_only_post_to_private_accounts\"}}"
                                    .getBytes(), null));

            Map<String, Object> result = service.executeHttpCallTyped(
                    api, typedTool(), parameters, Set.of(), null, null, null);

            assertThat(String.valueOf(result.get("error")))
                    .as("the raw body names a provider error code the reader cannot act on")
                    .contains("privacy_level must be SELF_ONLY")
                    .doesNotContain("unaudited_client_can_only_post_to_private_accounts");
            // This is the upload and publish path, so the provider's own words have to survive
            // somewhere: a needle that ever collides with an unrelated failure would otherwise
            // leave nothing saying what really happened.
            assertThat(String.valueOf(result.get("errorBody")))
                    .contains("unaudited_client_can_only_post_to_private_accounts");
        }
    }

    @Test
    @DisplayName("a declared user_error replaces the message on the credentialed path too")
    void credentialedPathUsesTheDeclaredMessage() {
        try (MockedStatic<UrlSafetyValidator> urlValidator = mockStatic(UrlSafetyValidator.class)) {
            urlValidator.when(() -> UrlSafetyValidator.validateUrl(anyString())).thenAnswer(i -> null);
            ArrayNode parameters = objectMapper.createArrayNode();
            ApiEntity api = api();
            api.setErrorPolicy("[{\"match\":{\"bodyContains\":\"spam_risk_too_many_posts\"},"
                    + "\"action\":\"user_error\","
                    + "\"message\":\"This account reached its daily posting limit.\"}]");

            when(restTemplate.exchange(any(java.net.URI.class), any(HttpMethod.class), any(), eq(Object.class)))
                    .thenThrow(HttpClientErrorException.create(
                            HttpStatus.BAD_REQUEST, "Bad Request", new HttpHeaders(),
                            "{\"error\":{\"code\":\"spam_risk_too_many_posts\"}}".getBytes(), null));

            Map<String, Object> result = service.executeHttpCallWithCredentials(
                    api, tool(), parameters, Set.of(), null, null);

            assertThat(String.valueOf(result.get("error")))
                    .isEqualTo("This account reached its daily posting limit.");
            // The raw body is still there for anyone debugging; only what the reader sees changed.
            assertThat(String.valueOf(result.get("errorBody"))).contains("spam_risk_too_many_posts");
        }
    }

    @Test
    @DisplayName("a declared message replaces the 403 wording too")
    void declaredMessageAppliesToForbidden() {
        try (MockedStatic<UrlSafetyValidator> urlValidator = mockStatic(UrlSafetyValidator.class)) {
            urlValidator.when(() -> UrlSafetyValidator.validateUrl(anyString())).thenAnswer(i -> null);
            ApiEntity api = api();
            api.setErrorPolicy("[{\"match\":{\"status\":403,\"bodyContains\":\"unaudited_client\"},"
                    + "\"action\":\"user_error\","
                    + "\"message\":\"This app is not approved for public posting.\"}]");

            when(restTemplate.exchange(any(java.net.URI.class), any(HttpMethod.class), any(), eq(Object.class)))
                    .thenThrow(HttpClientErrorException.create(
                            HttpStatus.FORBIDDEN, "Forbidden", new HttpHeaders(),
                            "{\"code\":\"unaudited_client\"}".getBytes(), null));

            Map<String, Object> result = service.executeHttpCallWithCredentials(
                    api, tool(), objectMapper.createArrayNode(), Set.of(), null, null);

            // The 403 branch builds its own "Access forbidden for X" wording, which is the least
            // useful thing to show when the seed knows exactly what happened.
            assertThat(String.valueOf(result.get("error")))
                    .isEqualTo("This app is not approved for public posting.");
        }
    }

    @Test
    @DisplayName("a failure right after a token refresh still gets the declared message")
    void failureAfterTokenRefreshIsPoliced() {
        try (MockedStatic<UrlSafetyValidator> urlValidator = mockStatic(UrlSafetyValidator.class)) {
            urlValidator.when(() -> UrlSafetyValidator.validateUrl(anyString())).thenAnswer(i -> null);
            ApiEntity api = api();
            api.setErrorPolicy("[{\"match\":{\"bodyContains\":\"spam_risk_too_many_posts\"},"
                    + "\"action\":\"user_error\","
                    + "\"message\":\"Daily posting limit reached.\"}]");

            // First call 401, the refresh succeeds, the re-dispatch then fails for a reason the
            // seed knows about. That re-dispatch sits inside the 401 catch, so its failure used to
            // skip every sibling catch and land in the generic handler as status 0.
            // Refreshed through the platform pool so the test does not have to stand up the whole
            // user-credential resolution surface; the re-dispatch is the same either way.
            api.setPlatformCredentialName("tiktok");
            when(userCredentialService.forceRefreshAndGetToken(any(), any()))
                    .thenReturn(java.util.Optional.of("fresh-token"));
            // Two different overloads on purpose: the first dispatch passes a URI, the
            // post-refresh one still passes the String form, so a single stub would leave the
            // second call unmocked and returning null.
            when(restTemplate.exchange(any(java.net.URI.class), any(HttpMethod.class), any(), eq(Object.class)))
                    .thenThrow(HttpClientErrorException.create(
                            HttpStatus.UNAUTHORIZED, "Unauthorized", new HttpHeaders(),
                            "{}".getBytes(), null));
            when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(), eq(Object.class)))
                    .thenThrow(HttpClientErrorException.create(
                            HttpStatus.BAD_REQUEST, "Bad Request", new HttpHeaders(),
                            "{\"code\":\"spam_risk_too_many_posts\"}".getBytes(), null));

            Map<String, Object> result = service.executeHttpCallWithCredentials(
                    api, tool(), objectMapper.createArrayNode(), Set.of(), null, null);

            assertThat(result.get("status"))
                    .as("the real provider status, not the generic 0 of an unhandled exception")
                    .isEqualTo(400);
            assertThat(String.valueOf(result.get("error")))
                    .isEqualTo("Daily posting limit reached.");
        }
    }

    @Test
    @DisplayName("a 401 that cannot be refreshed still gets the declared message")
    void unrefreshable401IsPoliced() {
        // The other half of the 401 branch: no token to refresh with, so it builds its own
        // "Authentication expired" result. That wording is the least useful thing to show when
        // the provider's body says something specific and the seed knows how to phrase it.
        try (MockedStatic<UrlSafetyValidator> urlValidator = mockStatic(UrlSafetyValidator.class)) {
            urlValidator.when(() -> UrlSafetyValidator.validateUrl(anyString())).thenAnswer(i -> null);
            ApiEntity api = api();
            api.setErrorPolicy("[{\"match\":{\"status\":401,\"bodyContains\":\"app_suspended\"},"
                    + "\"action\":\"user_error\","
                    + "\"message\":\"This app is suspended; reconnecting will not help.\"}]");

            when(restTemplate.exchange(any(java.net.URI.class), any(HttpMethod.class), any(), eq(Object.class)))
                    .thenThrow(HttpClientErrorException.create(
                            HttpStatus.UNAUTHORIZED, "Unauthorized", new HttpHeaders(),
                            "{\"error\":\"app_suspended\"}".getBytes(), null));

            Map<String, Object> result = service.executeHttpCallWithCredentials(
                    api, tool(), objectMapper.createArrayNode(), Set.of(), null, null);

            assertThat(result.get("status")).isEqualTo(401);
            assertThat(String.valueOf(result.get("error")))
                    .isEqualTo("This app is suspended; reconnecting will not help.");
        }
    }
}
