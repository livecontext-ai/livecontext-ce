package com.apimarketplace.catalog.service.http;

import com.apimarketplace.catalog.domain.ApiEntity;
import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.repository.ApiToolParameterRepository;
import com.apimarketplace.catalog.service.UserCredentialService;
import com.apimarketplace.common.web.UrlSafetyValidator;
import com.apimarketplace.common.security.CredentialEncryptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.AfterEach;
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
 * A provider answering 429 was previously the end of the step: the call failed, the run failed,
 * and the handful of seconds the provider asked us to wait were never waited. These pin the wiring
 * between the dispatch and {@link ErrorPolicyEngine}: that a retryable refusal really is re-sent,
 * that an ordinary failure and a network error still are not, that the wait is bounded, and that
 * the tool's own HTTP method reaches the engine, which is what stops a publish being re-sent.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HttpExecutionService - retry on a provider's rate-limit refusal")
class HttpExecutionServiceRetryTest {

    @Mock private ApiToolParameterRepository apiToolParameterRepository;
    @Mock private UserCredentialService userCredentialService;
    @Mock private CredentialEncryptionService encryptionService;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private RestTemplate restTemplate;

    private HttpExecutionService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void clearProviderRetryContext() {
        // This class drives exchangeWithRetry, which counts each re-send on a thread-local. The
        // count is per-CALL and nothing here opens a call properly, so without this every retry
        // exercised below is still on the thread when the next test class runs in the same fork -
        // and a class asserting "this call was answered first time" then sees somebody else's
        // re-sends. That is how it failed in CI while passing when run alone.
        ProviderRetryContext.clear();
    }

    @BeforeEach
    void setUp() {
        lenient().when(apiToolParameterRepository.findByApiToolId(any())).thenReturn(List.of());
        // A 1s budget: enough for the two 250ms floors a Retry-After of 0 now produces, small
        // enough that the suite does not sleep for real. The budget itself, and the case where a
        // provider asks for longer than it, are exercised explicitly below.
        service = new HttpExecutionService(
                apiToolParameterRepository, userCredentialService, encryptionService,
                objectMapper, jdbcTemplate, restTemplate, new ErrorPolicyEngine(2, 1_000L));
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
    @DisplayName("FIX: a 429 is re-sent and the call can still succeed")
    void retriesA429AndSucceeds() {
        try (MockedStatic<UrlSafetyValidator> urlValidator = mockStatic(UrlSafetyValidator.class)) {
            urlValidator.when(() -> UrlSafetyValidator.validateUrl(anyString())).thenAnswer(i -> null);
            ArrayNode parameters = objectMapper.createArrayNode();

            when(restTemplate.exchange(any(java.net.URI.class), any(HttpMethod.class), any(), eq(Object.class)))
                    .thenThrow(tooManyRequests("0"))
                    .thenReturn(new ResponseEntity<>(Map.of("ok", true), HttpStatus.OK));

            Map<String, Object> result = service.executeHttpCallWithCredentials(
                    api(), tool(), parameters, Set.of(), null, null);

            assertThat(result.get("success"))
                    .as("pre-fix the first 429 was the final answer and the step failed")
                    .isEqualTo(true);
            verify(restTemplate, times(2))
                    .exchange(any(java.net.URI.class), any(HttpMethod.class), any(), eq(Object.class));
        }
    }

    @Test
    @DisplayName("gives up after the configured attempts and returns the provider's failure")
    void stopsAfterMaxRetries() {
        try (MockedStatic<UrlSafetyValidator> urlValidator = mockStatic(UrlSafetyValidator.class)) {
            urlValidator.when(() -> UrlSafetyValidator.validateUrl(anyString())).thenAnswer(i -> null);
            ArrayNode parameters = objectMapper.createArrayNode();

            when(restTemplate.exchange(any(java.net.URI.class), any(HttpMethod.class), any(), eq(Object.class)))
                    .thenThrow(tooManyRequests("0"));

            Map<String, Object> result = service.executeHttpCallWithCredentials(
                    api(), tool(), parameters, Set.of(), null, null);

            assertThat(result.get("success")).isEqualTo(false);
            assertThat(result.get("status")).isEqualTo(429);
            // One initial call plus the two configured retries: a bounded loop, never an
            // open-ended one against a provider that is already asking us to stop.
            verify(restTemplate, times(3))
                    .exchange(any(java.net.URI.class), any(HttpMethod.class), any(), eq(Object.class));
        }
    }

    @Test
    @DisplayName("a wait longer than the budget is not slept through")
    void longRetryAfterIsReportedNotSlept() {
        try (MockedStatic<UrlSafetyValidator> urlValidator = mockStatic(UrlSafetyValidator.class)) {
            urlValidator.when(() -> UrlSafetyValidator.validateUrl(anyString())).thenAnswer(i -> null);
            ArrayNode parameters = objectMapper.createArrayNode();

            when(restTemplate.exchange(any(java.net.URI.class), any(HttpMethod.class), any(), eq(Object.class)))
                    .thenThrow(tooManyRequests("600"));

            long startedAt = System.currentTimeMillis();
            Map<String, Object> result = service.executeHttpCallWithCredentials(
                    api(), tool(), parameters, Set.of(), null, null);

            assertThat(System.currentTimeMillis() - startedAt)
                    .as("holding a request thread for ten minutes is what the cap exists to prevent")
                    .isLessThan(5_000L);
            verify(restTemplate, times(1))
                    .exchange(any(java.net.URI.class), any(HttpMethod.class), any(), eq(Object.class));
            assertThat(result.get("success")).isEqualTo(false);
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
    @DisplayName("FIX: the typed path retries a 429 too, not only the legacy one")
    void typedPathRetriesA429() {
        try (MockedStatic<UrlSafetyValidator> urlValidator = mockStatic(UrlSafetyValidator.class)) {
            urlValidator.when(() -> UrlSafetyValidator.validateUrl(anyString())).thenAnswer(i -> null);
            ArrayNode parameters = objectMapper.createArrayNode();

            when(restTemplate.exchange(any(java.net.URI.class), any(HttpMethod.class), any(), eq(Object.class)))
                    .thenThrow(tooManyRequests("0"))
                    .thenReturn(new ResponseEntity<>(Map.of("ok", true), HttpStatus.OK));

            Map<String, Object> result = service.executeHttpCallTyped(
                    api(), typedTool(), parameters, Set.of(), null, null, null);

            assertThat(result.get("success"))
                    .as("uploads and form-encoded publishes go through this path, and it dispatches "
                            + "separately from the legacy one")
                    .isEqualTo(true);
            verify(restTemplate, times(2))
                    .exchange(any(java.net.URI.class), any(HttpMethod.class), any(), eq(Object.class));
        }
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
    @DisplayName("the wait budget is the TOTAL across retries, not a cap on each one")
    void waitBudgetIsTotalNotPerWait() {
        try (MockedStatic<UrlSafetyValidator> urlValidator = mockStatic(UrlSafetyValidator.class)) {
            urlValidator.when(() -> UrlSafetyValidator.validateUrl(anyString())).thenAnswer(i -> null);
            // Two allowed retries of a 10 s cap each would hold a request thread for 20 s while three
            // places promise 10. The budget is what makes the promise true.
            HttpExecutionService budgeted = new HttpExecutionService(
                    apiToolParameterRepository, userCredentialService, encryptionService,
                    objectMapper, jdbcTemplate, restTemplate, new ErrorPolicyEngine(2, 1_500L));
            ArrayNode parameters = objectMapper.createArrayNode();

            when(restTemplate.exchange(any(java.net.URI.class), any(HttpMethod.class), any(), eq(Object.class)))
                    .thenThrow(tooManyRequests("1"));

            budgeted.executeHttpCallWithCredentials(api(), tool(), parameters, Set.of(), null, null);

            // First retry spends 1000ms of the 1500ms budget; a second 1000ms would exceed it, so
            // there is no third dispatch even though maxRetries is 2.
            verify(restTemplate, times(2))
                    .exchange(any(java.net.URI.class), any(HttpMethod.class), any(), eq(Object.class));
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
    @DisplayName("FIX: the binary-response path retries a 429 as well")
    void binaryPathRetriesA429() throws Exception {
        try (MockedStatic<UrlSafetyValidator> urlValidator = mockStatic(UrlSafetyValidator.class)) {
            urlValidator.when(() -> UrlSafetyValidator.validateUrl(anyString())).thenAnswer(i -> null);
            // Image, audio and video generation dispatch here, separately from the JSON typed path,
            // and these are the providers that throttle hardest. Missed, the retry would apply
            // everywhere except where it is needed most.
            var handler = org.mockito.Mockito.mock(
                    com.apimarketplace.catalog.service.execution.BinaryResponseHandler.class);
            when(handler.handle(any(), any(), any(), any(), any())).thenReturn(Map.of("path", "f.png"));
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

            assertThat(result.get("success")).isEqualTo(true);
            verify(restTemplate, times(2))
                    .exchange(any(java.net.URI.class), any(HttpMethod.class), any(), eq(byte[].class));
        }
    }

    @Test
    @DisplayName("max-retries 0 is a real kill switch: no retry, and no waiting")
    void killSwitchDisablesEveryRetry() {
        HttpExecutionService disabled = new HttpExecutionService(
                apiToolParameterRepository, userCredentialService, encryptionService,
                objectMapper, jdbcTemplate, restTemplate, new ErrorPolicyEngine(0, 10_000L));

        try (MockedStatic<UrlSafetyValidator> urlValidator = mockStatic(UrlSafetyValidator.class)) {
            urlValidator.when(() -> UrlSafetyValidator.validateUrl(anyString())).thenAnswer(i -> null);

            when(restTemplate.exchange(any(java.net.URI.class), any(HttpMethod.class), any(), eq(Object.class)))
                    .thenThrow(tooManyRequests("1"));

            Map<String, Object> result = disabled.executeHttpCallWithCredentials(
                    api(), tool(), objectMapper.createArrayNode(), Set.of(), null, null);

            assertThat(result.get("status")).isEqualTo(429);
            verify(restTemplate, times(1))
                    .exchange(any(java.net.URI.class), any(HttpMethod.class), any(), eq(Object.class));
        }
    }

    @Test
    @DisplayName("the kill switch stops the retries and keeps the declared messages")
    void killSwitchLeavesMessageRewritingWorking() {
        // Turning retries off is an availability decision. It must not also blind the reader:
        // an operator who sets it to stop the sleeping still wants a refusal explained.
        HttpExecutionService disabled = new HttpExecutionService(
                apiToolParameterRepository, userCredentialService, encryptionService,
                objectMapper, jdbcTemplate, restTemplate, new ErrorPolicyEngine(0, 10_000L));

        try (MockedStatic<UrlSafetyValidator> urlValidator = mockStatic(UrlSafetyValidator.class)) {
            urlValidator.when(() -> UrlSafetyValidator.validateUrl(anyString())).thenAnswer(i -> null);
            ApiEntity api = api();
            api.setErrorPolicy("[{\"match\":{\"bodyContains\":\"spam_risk_too_many_posts\"},"
                    + "\"action\":\"user_error\","
                    + "\"message\":\"This account reached its daily posting limit.\"}]");

            when(restTemplate.exchange(any(java.net.URI.class), any(HttpMethod.class), any(), eq(Object.class)))
                    .thenThrow(HttpClientErrorException.create(
                            HttpStatus.BAD_REQUEST, "Bad Request", new HttpHeaders(),
                            "{\"code\":\"spam_risk_too_many_posts\"}".getBytes(), null));

            Map<String, Object> result = disabled.executeHttpCallWithCredentials(
                    api, tool(), objectMapper.createArrayNode(), Set.of(), null, null);

            assertThat(String.valueOf(result.get("error")))
                    .isEqualTo("This account reached its daily posting limit.");
        }
    }

    @Test
    @DisplayName("an interrupted wait ends the call with the provider's error, not another attempt")
    void interruptedWaitDoesNotBecomeARetry() {
        // A shutdown or a cancelled request lands here. Turning it into a retry would re-send a
        // request while the thread is being torn down, on the one path that publishes.
        try (MockedStatic<UrlSafetyValidator> urlValidator = mockStatic(UrlSafetyValidator.class)) {
            urlValidator.when(() -> UrlSafetyValidator.validateUrl(anyString())).thenAnswer(i -> null);

            when(restTemplate.exchange(any(java.net.URI.class), any(HttpMethod.class), any(), eq(Object.class)))
                    .thenAnswer(invocation -> {
                        Thread.currentThread().interrupt();
                        throw tooManyRequests("0");
                    });

            Map<String, Object> result = service.executeHttpCallWithCredentials(
                    api(), tool(), objectMapper.createArrayNode(), Set.of(), null, null);

            assertThat(result.get("status")).isEqualTo(429);
            verify(restTemplate, times(1))
                    .exchange(any(java.net.URI.class), any(HttpMethod.class), any(), eq(Object.class));
            assertThat(Thread.interrupted())
                    .as("the flag must be restored for whoever is shutting the thread down")
                    .isTrue();
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
    @DisplayName("FIX: the request's METHOD reaches the engine, so a POST is not re-sent on a 5xx")
    void theMethodReachesTheEngine() {
        // exchangeWithRetry passes tool.getMethod() into classify. Replace that argument with a
        // literal "GET" and the engine's duplicate-publish guard is dead at the only place it
        // actually runs, while every engine unit test keeps passing. This is that one line.
        try (MockedStatic<UrlSafetyValidator> urlValidator = mockStatic(UrlSafetyValidator.class)) {
            urlValidator.when(() -> UrlSafetyValidator.validateUrl(anyString())).thenAnswer(i -> null);
            ApiEntity api = api();
            api.setErrorPolicy("[{\"match\":{\"bodyContains\":\"rate_limit_exceeded\"},"
                    + "\"action\":\"retry\",\"waitMs\":250}]");

            ApiToolEntity publish = new ApiToolEntity();
            publish.setId(UUID.randomUUID());
            publish.setMethod("POST");
            publish.setEndpoint("/publish");

            when(restTemplate.exchange(any(java.net.URI.class), any(HttpMethod.class), any(), eq(Object.class)))
                    .thenThrow(HttpClientErrorException.create(
                            org.springframework.http.HttpStatus.BAD_GATEWAY, "Bad Gateway",
                            new HttpHeaders(), "{\"error\":\"rate_limit_exceeded\"}".getBytes(), null));

            Map<String, Object> result = service.executeHttpCallWithCredentials(
                    api, publish, objectMapper.createArrayNode(), Set.of(), null, null);

            assertThat(result.get("status")).isEqualTo(502);
            verify(restTemplate, times(1))
                    .exchange(any(java.net.URI.class), any(HttpMethod.class), any(), eq(Object.class));
        }
    }

    @Test
    @DisplayName("and the same rule still retries that 5xx for a read")
    void theMethodReachesTheEngineForReadsToo() {
        try (MockedStatic<UrlSafetyValidator> urlValidator = mockStatic(UrlSafetyValidator.class)) {
            urlValidator.when(() -> UrlSafetyValidator.validateUrl(anyString())).thenAnswer(i -> null);
            ApiEntity api = api();
            api.setErrorPolicy("[{\"match\":{\"bodyContains\":\"rate_limit_exceeded\"},"
                    + "\"action\":\"retry\",\"waitMs\":250}]");

            when(restTemplate.exchange(any(java.net.URI.class), any(HttpMethod.class), any(), eq(Object.class)))
                    .thenThrow(HttpClientErrorException.create(
                            org.springframework.http.HttpStatus.BAD_GATEWAY, "Bad Gateway",
                            new HttpHeaders(), "{\"error\":\"rate_limit_exceeded\"}".getBytes(), null))
                    .thenReturn(new ResponseEntity<>(Map.of("ok", true), HttpStatus.OK));

            // tool() is a GET: re-sending it cannot duplicate anything, so the gate lets it through.
            Map<String, Object> result = service.executeHttpCallWithCredentials(
                    api, tool(), objectMapper.createArrayNode(), Set.of(), null, null);

            assertThat(result.get("success")).isEqualTo(true);
            verify(restTemplate, times(2))
                    .exchange(any(java.net.URI.class), any(HttpMethod.class), any(), eq(Object.class));
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

    @Test
    @DisplayName("a retry is counted, so a provider-wide throttle is attributable")
    void retriesAreCounted() throws Exception {
        // The wait happens on the serving thread, so without a counter a throttling provider
        // shows up only as latency across the whole catalogue, with nothing saying which one.
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        java.lang.reflect.Field f = HttpExecutionService.class.getDeclaredField("meterRegistry");
        f.setAccessible(true);
        f.set(service, registry);

        try (MockedStatic<UrlSafetyValidator> urlValidator = mockStatic(UrlSafetyValidator.class)) {
            urlValidator.when(() -> UrlSafetyValidator.validateUrl(anyString())).thenAnswer(i -> null);
            ApiEntity api = api();
            api.setIconSlug("tiktok");

            when(restTemplate.exchange(any(java.net.URI.class), any(HttpMethod.class), any(), eq(Object.class)))
                    .thenThrow(tooManyRequests("0"))
                    .thenReturn(new ResponseEntity<>(Map.of("ok", true), HttpStatus.OK));

            service.executeHttpCallWithCredentials(
                    api, tool(), objectMapper.createArrayNode(), Set.of(), null, null);

            assertThat(registry.counter("catalog_tool_retry_total",
                    "integration", "tiktok", "status", "429").count())
                    .isEqualTo(1.0);
        }
    }

    @Test
    @DisplayName("no registry is not a reason to fail a provider call")
    void missingRegistryDoesNotBreakTheRetry() {
        // The field is optional, and a metric must never be what stops a call from going out.
        try (MockedStatic<UrlSafetyValidator> urlValidator = mockStatic(UrlSafetyValidator.class)) {
            urlValidator.when(() -> UrlSafetyValidator.validateUrl(anyString())).thenAnswer(i -> null);

            when(restTemplate.exchange(any(java.net.URI.class), any(HttpMethod.class), any(), eq(Object.class)))
                    .thenThrow(tooManyRequests("0"))
                    .thenReturn(new ResponseEntity<>(Map.of("ok", true), HttpStatus.OK));

            Map<String, Object> result = service.executeHttpCallWithCredentials(
                    api(), tool(), objectMapper.createArrayNode(), Set.of(), null, null);

            assertThat(result.get("success")).isEqualTo(true);
        }
    }
}
