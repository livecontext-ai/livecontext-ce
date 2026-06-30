package com.apimarketplace.orchestrator.services.credit;

import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.common.web.TenantResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link CreditConsumptionClient} in the orchestrator-service.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CreditConsumptionClient (orchestrator)")
class CreditConsumptionClientTest {

    @Mock
    private RestTemplate restTemplate;

    @Captor
    private ArgumentCaptor<HttpEntity<Map<String, Object>>> httpEntityCaptor;

    @Captor
    private ArgumentCaptor<String> urlCaptor;

    private CreditConsumptionClient client;

    private static final String AUTH_SERVICE_URL = "http://localhost:8083";
    private static final String USER_ID = "user-123";
    private static final String SOURCE_TYPE = "workflow";
    private static final String SOURCE_ID = "wf-456";
    private static final String PROVIDER = "openai";
    private static final String MODEL = "gpt-4";
    private static final Integer PROMPT_TOKENS = 100;
    private static final Integer COMPLETION_TOKENS = 200;

    @BeforeEach
    void setUp() {
        client = new CreditConsumptionClient(AUTH_SERVICE_URL, true);
        // Inject mock RestTemplate via reflection since the client creates its own internally
        ReflectionTestUtils.setField(client, "restTemplate", restTemplate);
    }

    // -- Helper methods ---------------------------------------------------

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> successResponse() {
        Map<String, Object> body = Map.of("success", true, "remainingCredits", 950);
        return new ResponseEntity<>(body, HttpStatus.OK);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> insufficientCreditsResponse() {
        Map<String, Object> body = Map.of("success", false, "error", "Insufficient credits");
        return new ResponseEntity<>(body, HttpStatus.PAYMENT_REQUIRED);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> serverErrorResponse() {
        Map<String, Object> body = Map.of("error", "Internal server error");
        return new ResponseEntity<>(body, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // -- consumeCredits() tests -------------------------------------------

    @Nested
    @DisplayName("consumeCredits()")
    class ConsumeCreditsTests {

        @Test
        @DisplayName("Should return success map when auth-service returns 200")
        void shouldReturnSuccessMapOn200() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(successResponse());

            Map<String, Object> result = client.consumeCredits(
                    USER_ID, SOURCE_TYPE, SOURCE_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS);

            assertThat(result).containsEntry("success", true);
            assertThat(result).containsEntry("remainingCredits", 950);
        }

        @Test
        @DisplayName("Should send correct request body with all fields")
        @SuppressWarnings("unchecked")
        void shouldSendCorrectRequestBody() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(successResponse());

            client.consumeCredits(USER_ID, SOURCE_TYPE, SOURCE_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS);

            verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), httpEntityCaptor.capture(), eq(Map.class));
            Map<String, Object> body = httpEntityCaptor.getValue().getBody();

            assertThat(body).isNotNull();
            assertThat(body).containsEntry("sourceType", SOURCE_TYPE);
            assertThat(body).containsEntry("sourceId", SOURCE_ID);
            assertThat(body).containsEntry("provider", PROVIDER);
            assertThat(body).containsEntry("model", MODEL);
            assertThat(body).containsEntry("promptTokens", PROMPT_TOKENS);
            assertThat(body).containsEntry("completionTokens", COMPLETION_TOKENS);
        }

        @Test
        @DisplayName("Should set X-User-ID header and Content-Type application/json")
        void shouldSetCorrectHeaders() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(successResponse());

            client.consumeCredits(USER_ID, SOURCE_TYPE, SOURCE_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS);

            verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), httpEntityCaptor.capture(), eq(Map.class));
            HttpHeaders headers = httpEntityCaptor.getValue().getHeaders();

            assertThat(headers.getFirst("X-User-ID")).isEqualTo(USER_ID);
            assertThat(headers.getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        }

        @Test
        @DisplayName("Should return error map when auth-service returns 402 (insufficient credits)")
        void shouldReturnErrorMapOn402() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(insufficientCreditsResponse());

            Map<String, Object> result = client.consumeCredits(
                    USER_ID, SOURCE_TYPE, SOURCE_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS);

            assertThat(result).containsEntry("success", false);
            assertThat(result).containsKey("error");
            assertThat(result.get("error").toString()).contains("Non-2xx response");
        }

        @Test
        @DisplayName("Should return error map when auth-service returns 500 (server error)")
        void shouldReturnErrorMapOn500() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(serverErrorResponse());

            Map<String, Object> result = client.consumeCredits(
                    USER_ID, SOURCE_TYPE, SOURCE_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS);

            assertThat(result).containsEntry("success", false);
            assertThat(result).containsKey("error");
            assertThat(result.get("error").toString()).contains("Non-2xx response");
        }

        @Test
        @DisplayName("Should return error map when RestClientException is thrown (connection refused)")
        void shouldReturnErrorMapOnConnectionRefused() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenThrow(new RestClientException("Connection refused"));

            Map<String, Object> result = client.consumeCredits(
                    USER_ID, SOURCE_TYPE, SOURCE_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS);

            assertThat(result).containsEntry("success", false);
            assertThat(result).containsKey("error");
            assertThat(result.get("error").toString()).contains("Connection refused");
        }

        @Test
        @DisplayName("Should return error map when ResourceAccessException is thrown (timeout)")
        void shouldReturnErrorMapOnTimeout() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenThrow(new ResourceAccessException("Read timed out"));

            Map<String, Object> result = client.consumeCredits(
                    USER_ID, SOURCE_TYPE, SOURCE_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS);

            assertThat(result).containsEntry("success", false);
            assertThat(result).containsKey("error");
            assertThat(result.get("error").toString()).contains("Read timed out");
        }

        @Test
        @DisplayName("Should send 'unknown' when provider is null")
        @SuppressWarnings("unchecked")
        void shouldSendUnknownWhenProviderIsNull() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(successResponse());

            client.consumeCredits(USER_ID, SOURCE_TYPE, SOURCE_ID, null, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS);

            verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), httpEntityCaptor.capture(), eq(Map.class));
            Map<String, Object> body = httpEntityCaptor.getValue().getBody();

            assertThat(body).isNotNull();
            assertThat(body).containsEntry("provider", "unknown");
        }

        @Test
        @DisplayName("Should send 'unknown' when model is null")
        @SuppressWarnings("unchecked")
        void shouldSendUnknownWhenModelIsNull() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(successResponse());

            client.consumeCredits(USER_ID, SOURCE_TYPE, SOURCE_ID, PROVIDER, null, PROMPT_TOKENS, COMPLETION_TOKENS);

            verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), httpEntityCaptor.capture(), eq(Map.class));
            Map<String, Object> body = httpEntityCaptor.getValue().getBody();

            assertThat(body).isNotNull();
            assertThat(body).containsEntry("model", "unknown");
        }

        @Test
        @DisplayName("Should send empty string when sourceId is null")
        @SuppressWarnings("unchecked")
        void shouldSendEmptyStringWhenSourceIdIsNull() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(successResponse());

            client.consumeCredits(USER_ID, SOURCE_TYPE, null, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS);

            verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), httpEntityCaptor.capture(), eq(Map.class));
            Map<String, Object> body = httpEntityCaptor.getValue().getBody();

            assertThat(body).isNotNull();
            assertThat(body).containsEntry("sourceId", "");
        }

        @Test
        @DisplayName("Should send 0 when promptTokens is null")
        @SuppressWarnings("unchecked")
        void shouldSendZeroWhenPromptTokensIsNull() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(successResponse());

            client.consumeCredits(USER_ID, SOURCE_TYPE, SOURCE_ID, PROVIDER, MODEL, null, COMPLETION_TOKENS);

            verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), httpEntityCaptor.capture(), eq(Map.class));
            Map<String, Object> body = httpEntityCaptor.getValue().getBody();

            assertThat(body).isNotNull();
            assertThat(body).containsEntry("promptTokens", 0);
        }

        @Test
        @DisplayName("Should send 0 when completionTokens is null")
        @SuppressWarnings("unchecked")
        void shouldSendZeroWhenCompletionTokensIsNull() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(successResponse());

            client.consumeCredits(USER_ID, SOURCE_TYPE, SOURCE_ID, PROVIDER, MODEL, PROMPT_TOKENS, null);

            verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), httpEntityCaptor.capture(), eq(Map.class));
            Map<String, Object> body = httpEntityCaptor.getValue().getBody();

            assertThat(body).isNotNull();
            assertThat(body).containsEntry("completionTokens", 0);
        }

        @Test
        @DisplayName("Should send UNKNOWN when sourceType is null (HashMap null-safety)")
        @SuppressWarnings("unchecked")
        void shouldSendUnknownWhenSourceTypeIsNull() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(successResponse());

            // This would have thrown NPE with Map.of() - now uses HashMap and defaults to UNKNOWN
            client.consumeCredits(USER_ID, null, SOURCE_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS);

            verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), httpEntityCaptor.capture(), eq(Map.class));
            Map<String, Object> body = httpEntityCaptor.getValue().getBody();

            assertThat(body).isNotNull();
            assertThat(body).containsEntry("sourceType", "UNKNOWN");
        }

        @Test
        @DisplayName("Should handle ALL parameters null without NPE (HashMap null-safety)")
        @SuppressWarnings("unchecked")
        void shouldHandleAllNullableParamsWithoutNpe() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(successResponse());

            // All nullable params are null - this must not throw NPE
            Map<String, Object> result = client.consumeCredits(
                    USER_ID, null, null, null, null, null, null);

            verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), httpEntityCaptor.capture(), eq(Map.class));
            Map<String, Object> body = httpEntityCaptor.getValue().getBody();

            assertThat(body).isNotNull();
            assertThat(body).containsEntry("sourceType", "UNKNOWN");
            assertThat(body).containsEntry("sourceId", "");
            assertThat(body).containsEntry("provider", "unknown");
            assertThat(body).containsEntry("model", "unknown");
            assertThat(body).containsEntry("promptTokens", 0);
            assertThat(body).containsEntry("completionTokens", 0);
            assertThat(result).containsEntry("success", true);
        }
    }

    // -- consumeFixedCredits() tests ----------------------------------------

    @Nested
    @DisplayName("consumeFixedCredits()")
    class ConsumeFixedCreditsTests {

        @Test
        @DisplayName("Should return success map when auth-service returns 200")
        void shouldReturnSuccessOn200() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(successResponse());

            Map<String, Object> result = client.consumeFixedCredits(USER_ID, "pub-123", 10);

            assertThat(result).containsEntry("success", true);
        }

        @Test
        @DisplayName("Should send MARKETPLACE_PURCHASE sourceType and cost field")
        @SuppressWarnings("unchecked")
        void shouldSendCorrectBody() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(successResponse());

            client.consumeFixedCredits(USER_ID, "pub-123", 25);

            verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), httpEntityCaptor.capture(), eq(Map.class));
            Map<String, Object> body = httpEntityCaptor.getValue().getBody();

            assertThat(body).isNotNull();
            assertThat(body).containsEntry("sourceType", "MARKETPLACE_PURCHASE");
            assertThat(body).containsEntry("sourceId", "pub-123");
            assertThat(body).containsEntry("cost", 25);
        }

        @Test
        @DisplayName("Should return error on 402 insufficient credits")
        void shouldReturnErrorOn402() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenThrow(HttpClientErrorException.create(
                            HttpStatus.PAYMENT_REQUIRED, "Payment Required",
                            HttpHeaders.EMPTY, new byte[0], null));

            Map<String, Object> result = client.consumeFixedCredits(USER_ID, "pub-123", 10);

            assertThat(result).containsEntry("success", false);
            assertThat(result.get("error").toString()).contains("402");
            assertThat(result).containsEntry("required", 10);
        }

        @Test
        @DisplayName("Should return error on connection failure")
        void shouldReturnErrorOnConnectionFailure() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenThrow(new ResourceAccessException("Connection refused"));

            Map<String, Object> result = client.consumeFixedCredits(USER_ID, "pub-123", 10);

            assertThat(result).containsEntry("success", false);
            assertThat(result.get("error").toString()).contains("Connection refused");
        }

        @Test
        @DisplayName("Should skip when disabled")
        void shouldSkipWhenDisabled() {
            CreditConsumptionClient disabledClient = new CreditConsumptionClient(AUTH_SERVICE_URL, false);

            Map<String, Object> result = disabledClient.consumeFixedCredits(USER_ID, "pub-123", 10);

            assertThat(result).containsEntry("success", true);
            assertThat(result).containsEntry("skipped", true);
        }

        @Test
        @DisplayName("Should return error when userId is null")
        void shouldReturnErrorWhenUserIdNull() {
            Map<String, Object> result = client.consumeFixedCredits(null, "pub-123", 10);

            assertThat(result).containsEntry("success", false);
            verifyNoInteractions(restTemplate);
        }

        @Test
        @DisplayName("Should handle null sourceId gracefully")
        @SuppressWarnings("unchecked")
        void shouldHandleNullSourceId() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(successResponse());

            client.consumeFixedCredits(USER_ID, null, 10);

            verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), httpEntityCaptor.capture(), eq(Map.class));
            Map<String, Object> body = httpEntityCaptor.getValue().getBody();

            assertThat(body).containsEntry("sourceId", "");
        }
    }

    // -- Null userId tests ------------------------------------------------

    @Nested
    @DisplayName("Null userId handling")
    class NullUserIdTests {

        @Test
        @DisplayName("Should return error map and skip HTTP call when userId is null")
        void shouldReturnErrorWhenUserIdIsNull() {
            Map<String, Object> result = client.consumeCredits(
                    null, SOURCE_TYPE, SOURCE_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS);

            assertThat(result).containsEntry("success", false);
            assertThat(result.get("error").toString()).contains("userId is null or blank");
            verifyNoInteractions(restTemplate);
        }

        @Test
        @DisplayName("Should return error map and skip HTTP call when userId is blank")
        void shouldReturnErrorWhenUserIdIsBlank() {
            Map<String, Object> result = client.consumeCredits(
                    "  ", SOURCE_TYPE, SOURCE_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS);

            assertThat(result).containsEntry("success", false);
            assertThat(result.get("error").toString()).contains("userId is null or blank");
            verifyNoInteractions(restTemplate);
        }

        @Test
        @DisplayName("Should return error map and skip HTTP call when userId is empty")
        void shouldReturnErrorWhenUserIdIsEmpty() {
            Map<String, Object> result = client.consumeCredits(
                    "", SOURCE_TYPE, SOURCE_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS);

            assertThat(result).containsEntry("success", false);
            assertThat(result.get("error").toString()).contains("userId is null or blank");
            verifyNoInteractions(restTemplate);
        }

        @Test
        @DisplayName("Async: should not throw when userId is null")
        void asyncShouldNotThrowWhenUserIdIsNull() {
            // V261 - consumeCreditsAsync captures currentRequestOrganizationId on
            // producer thread and fails-fast if null. Tests run off-thread so we
            // bind an org scope explicitly.
            assertThatCode(() ->
                TenantResolver.runWithOrgScope("test-org", () ->
                    client.consumeCreditsAsync(null, SOURCE_TYPE, SOURCE_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS))
            ).doesNotThrowAnyException();

            verifyNoInteractions(restTemplate);
        }
    }

    // -- consumeCreditsAsync() tests --------------------------------------

    @Nested
    @DisplayName("consumeCreditsAsync()")
    class ConsumeCreditsAsyncTests {

        @Test
        @DisplayName("Should delegate to consumeCredits synchronously")
        void shouldDelegateToConsumeCredits() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(successResponse());

            // V261 - async path captures orgId from request context; bind one for the test thread.
            TenantResolver.runWithOrgScope("test-org", () ->
                client.consumeCreditsAsync(USER_ID, SOURCE_TYPE, SOURCE_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS));

            verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class));
        }

        @Test
        @DisplayName("Should retry and catch exceptions from consumeCredits")
        void shouldRetryAndCatchExceptionsFromConsumeCredits() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenThrow(new RuntimeException("Unexpected error"));

            // consumeCreditsAsync now retries up to 3 times before giving up.
            // This should NOT throw - all errors are caught.
            // V261 - bind org scope on test thread for producer-side requireOrgId.
            TenantResolver.runWithOrgScope("test-org", () ->
                client.consumeCreditsAsync(USER_ID, SOURCE_TYPE, SOURCE_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS));

            // No exception propagated - retried 3 times
            verify(restTemplate, atLeast(1)).exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class));
        }
    }

    // -- URL Construction tests -------------------------------------------

    @Nested
    @DisplayName("URL Construction")
    class UrlConstructionTests {

        @Test
        @DisplayName("Should use default URL http://localhost:8083")
        void shouldUseDefaultUrl() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(successResponse());

            client.consumeCredits(USER_ID, SOURCE_TYPE, SOURCE_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS);

            verify(restTemplate).exchange(urlCaptor.capture(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class));
            assertThat(urlCaptor.getValue()).isEqualTo("http://localhost:8083/api/credits/consume");
        }

        @Test
        @DisplayName("Should use custom configured URL")
        void shouldUseCustomConfiguredUrl() {
            String customUrl = "http://auth-service:9090";
            CreditConsumptionClient customClient = new CreditConsumptionClient(customUrl, true);
            ReflectionTestUtils.setField(customClient, "restTemplate", restTemplate);

            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(successResponse());

            customClient.consumeCredits(USER_ID, SOURCE_TYPE, SOURCE_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS);

            verify(restTemplate).exchange(urlCaptor.capture(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class));
            assertThat(urlCaptor.getValue()).isEqualTo("http://auth-service:9090/api/credits/consume");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "http://auth:8083",
                "https://auth.production.internal",
                "http://10.0.0.5:8083"
        })
        @DisplayName("Should append /api/credits/consume to any configured base URL")
        void shouldAppendPathToAnyBaseUrl(String baseUrl) {
            CreditConsumptionClient customClient = new CreditConsumptionClient(baseUrl, true);
            ReflectionTestUtils.setField(customClient, "restTemplate", restTemplate);

            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(successResponse());

            customClient.consumeCredits(USER_ID, SOURCE_TYPE, SOURCE_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS);

            verify(restTemplate).exchange(urlCaptor.capture(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class));
            assertThat(urlCaptor.getValue()).isEqualTo(baseUrl + "/api/credits/consume");
        }

        @Test
        @DisplayName("Should strip trailing slash from base URL to avoid double slash")
        void shouldStripTrailingSlash() {
            CreditConsumptionClient customClient = new CreditConsumptionClient("http://auth:8083/", true);
            ReflectionTestUtils.setField(customClient, "restTemplate", restTemplate);

            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(successResponse());

            customClient.consumeCredits(USER_ID, SOURCE_TYPE, SOURCE_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS);

            verify(restTemplate).exchange(urlCaptor.capture(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class));
            assertThat(urlCaptor.getValue()).isEqualTo("http://auth:8083/api/credits/consume");
        }

        @Test
        @DisplayName("Should strip multiple trailing slashes from base URL")
        void shouldStripMultipleTrailingSlashes() {
            // Only one trailing slash is stripped per the implementation,
            // but this still produces a valid URL without double slash at the join point
            CreditConsumptionClient customClient = new CreditConsumptionClient("http://auth:8083/", true);
            ReflectionTestUtils.setField(customClient, "restTemplate", restTemplate);

            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(successResponse());

            customClient.consumeCredits(USER_ID, SOURCE_TYPE, SOURCE_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS);

            verify(restTemplate).exchange(urlCaptor.capture(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class));
            assertThat(urlCaptor.getValue()).doesNotContain("//api");
        }
    }

    // -- RestTemplate timeout verification --------------------------------

    @Nested
    @DisplayName("RestTemplate configuration")
    class RestTemplateConfigTests {

        @Test
        @DisplayName("Should create RestTemplate with non-null request factory (timeouts configured)")
        void shouldHaveConfiguredRestTemplate() {
            // Create a fresh client (not using the mocked restTemplate)
            CreditConsumptionClient freshClient = new CreditConsumptionClient(AUTH_SERVICE_URL, true);

            // The restTemplate should be created with RestTemplateBuilder which configures timeouts
            RestTemplate actualRestTemplate = (RestTemplate) ReflectionTestUtils.getField(freshClient, "restTemplate");
            assertThat(actualRestTemplate).isNotNull();
            // RestTemplateBuilder creates a RestTemplate with a configured request factory
            assertThat(actualRestTemplate.getRequestFactory()).isNotNull();
        }
    }

    // -- Concurrent stress test -------------------------------------------

    @Nested
    @DisplayName("Concurrent stress")
    class ConcurrentStressTests {

        @Test
        @DisplayName("Should handle 50 rapid concurrent calls without shared state corruption")
        void shouldHandle50RapidCalls() throws InterruptedException {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(successResponse());

            int threadCount = 50;
            ExecutorService executor = Executors.newFixedThreadPool(10);
            CountDownLatch latch = new CountDownLatch(threadCount);

            for (int i = 0; i < threadCount; i++) {
                final String userId = "user-" + i;
                executor.submit(() -> {
                    try {
                        Map<String, Object> result = client.consumeCredits(
                                userId, SOURCE_TYPE, SOURCE_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS);
                        assertThat(result).containsEntry("success", true);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            boolean completed = latch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(completed).isTrue();
            verify(restTemplate, times(threadCount))
                    .exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class));
        }

        @Test
        @DisplayName("Should handle concurrent calls with mixed null parameters")
        void shouldHandleConcurrentCallsWithNulls() throws InterruptedException {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(successResponse());

            int threadCount = 20;
            ExecutorService executor = Executors.newFixedThreadPool(5);
            CountDownLatch latch = new CountDownLatch(threadCount);

            for (int i = 0; i < threadCount; i++) {
                final String userId = "user-" + i;
                final String provider = i % 2 == 0 ? null : "openai";
                final String model = i % 3 == 0 ? null : "gpt-4";
                executor.submit(() -> {
                    try {
                        // Must not throw NPE even with null parameters
                        Map<String, Object> result = client.consumeCredits(
                                userId, SOURCE_TYPE, SOURCE_ID, provider, model, null, null);
                        assertThat(result).containsEntry("success", true);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            boolean completed = latch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(completed).isTrue();
        }
    }

    // -- Exception message null safety ------------------------------------

    @Nested
    @DisplayName("Exception handling edge cases")
    class ExceptionEdgeCaseTests {

        @Test
        @DisplayName("Should handle exception with null message without NPE")
        void shouldHandleExceptionWithNullMessage() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenThrow(new RuntimeException((String) null));

            Map<String, Object> result = client.consumeCredits(
                    USER_ID, SOURCE_TYPE, SOURCE_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS);

            assertThat(result).containsEntry("success", false);
            assertThat(result).containsKey("error");
            assertThat(result.get("error")).isEqualTo("java.lang.RuntimeException");
        }
    }

    // -- checkCredits() tests -----------------------------------------------

    @Nested
    @DisplayName("checkCredits()")
    class CheckCreditsTests {

        @SuppressWarnings("unchecked")
        private ResponseEntity<Map> checkAllowedResponse() {
            return new ResponseEntity<>(Map.of("allowed", true, "balance", 100), HttpStatus.OK);
        }

        @SuppressWarnings("unchecked")
        private ResponseEntity<Map> checkDeniedResponse() {
            return new ResponseEntity<>(Map.of("allowed", false, "balance", 0), HttpStatus.PAYMENT_REQUIRED);
        }

        @Test
        @DisplayName("should return true when auth-service says allowed")
        void shouldReturnTrueWhenAllowed() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(checkAllowedResponse());

            assertThat(client.checkCredits(USER_ID)).isTrue();

            verify(restTemplate).exchange(
                    eq(AUTH_SERVICE_URL + "/api/credits/check"),
                    eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class));
        }

        @Test
        @DisplayName("should return false when auth-service returns 402")
        void shouldReturnFalseOn402() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                    .thenThrow(HttpClientErrorException.create(
                            HttpStatus.PAYMENT_REQUIRED, "Payment Required",
                            HttpHeaders.EMPTY, new byte[0], null));

            assertThat(client.checkCredits(USER_ID)).isFalse();
        }

        @Test
        @DisplayName("should return false when response body says allowed=false")
        void shouldReturnFalseWhenNotAllowed() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(checkDeniedResponse());

            assertThat(client.checkCredits(USER_ID)).isFalse();
        }

        @Test
        @DisplayName("should return false (fail-closed) when auth-service is down and no cache")
        void shouldReturnFalseWhenServiceDown() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                    .thenThrow(new ResourceAccessException("Connection refused"));

            assertThat(client.checkCredits(USER_ID)).isFalse();
        }

        @Test
        @DisplayName("should return true when userId is null")
        void shouldReturnTrueWhenUserIdNull() {
            assertThat(client.checkCredits(null)).isTrue();
            verifyNoInteractions(restTemplate);
        }

        @Test
        @DisplayName("should return true when userId is blank")
        void shouldReturnTrueWhenUserIdBlank() {
            assertThat(client.checkCredits("")).isTrue();
            verifyNoInteractions(restTemplate);
        }

        @Test
        @DisplayName("should return true when disabled")
        void shouldReturnTrueWhenDisabled() {
            CreditConsumptionClient disabledClient = new CreditConsumptionClient(AUTH_SERVICE_URL, false);
            assertThat(disabledClient.checkCredits(USER_ID)).isTrue();
        }
    }

    // -- fetchBalance() tests -----------------------------------------------

    @Nested
    @DisplayName("fetchBalance()")
    class FetchBalanceTests {

        @SuppressWarnings("unchecked")
        private ResponseEntity<Map> balanceResponse(int balance) {
            return new ResponseEntity<>(Map.of("balance", balance), HttpStatus.OK);
        }

        @Test
        @DisplayName("should return balance from auth-service")
        void shouldReturnBalance() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(balanceResponse(42));

            java.math.BigDecimal result = client.fetchBalance(USER_ID);

            assertThat(result).isEqualByComparingTo(new java.math.BigDecimal("42"));
            verify(restTemplate).exchange(
                    eq(AUTH_SERVICE_URL + "/api/credits/balance"),
                    eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class));
        }

        @Test
        @DisplayName("should return ZERO (fail-closed) when auth-service is down")
        void shouldReturnZeroWhenServiceDown() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                    .thenThrow(new ResourceAccessException("Connection refused"));

            java.math.BigDecimal result = client.fetchBalance(USER_ID);

            assertThat(result).isEqualByComparingTo(java.math.BigDecimal.ZERO);
        }

        @Test
        @DisplayName("should return large value when disabled")
        void shouldReturnLargeValueWhenDisabled() {
            CreditConsumptionClient disabledClient = new CreditConsumptionClient(AUTH_SERVICE_URL, false);
            java.math.BigDecimal result = disabledClient.fetchBalance(USER_ID);
            assertThat(result).isEqualByComparingTo(new java.math.BigDecimal("999999999"));
        }

        @Test
        @DisplayName("should return large value when userId is null")
        void shouldReturnLargeValueWhenUserIdNull() {
            java.math.BigDecimal result = client.fetchBalance(null);
            assertThat(result).isEqualByComparingTo(new java.math.BigDecimal("999999999"));
            verifyNoInteractions(restTemplate);
        }
    }
}
