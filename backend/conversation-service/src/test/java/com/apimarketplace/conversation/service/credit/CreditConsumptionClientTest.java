package com.apimarketplace.conversation.service.credit;

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
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link CreditConsumptionClient} in the conversation-service.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CreditConsumptionClient (conversation)")
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
    private static final String SOURCE_TYPE = "chat";
    private static final String SOURCE_ID = "conv-789";
    private static final String PROVIDER = "anthropic";
    private static final String MODEL = "claude-3";
    private static final Integer PROMPT_TOKENS = 150;
    private static final Integer COMPLETION_TOKENS = 250;

    @BeforeEach
    void setUp() {
        client = new CreditConsumptionClient(AUTH_SERVICE_URL);
        // Inject mock RestTemplate via reflection since the client creates its own internally
        ReflectionTestUtils.setField(client, "restTemplate", restTemplate);
    }

    // ── Helper methods ───────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> successResponse() {
        Map<String, Object> body = Map.of("success", true, "remainingCredits", 850);
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

    // ── consumeCredits() tests ───────────────────────────────────────

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
            assertThat(result).containsEntry("remainingCredits", 850);
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
        @DisplayName("Should send 'unknown' when sourceType is null (no NPE from Map.of)")
        @SuppressWarnings("unchecked")
        void shouldSendUnknownWhenSourceTypeIsNull() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(successResponse());

            client.consumeCredits(USER_ID, null, SOURCE_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS);

            verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), httpEntityCaptor.capture(), eq(Map.class));
            Map<String, Object> body = httpEntityCaptor.getValue().getBody();

            assertThat(body).isNotNull();
            assertThat(body).containsEntry("sourceType", "UNKNOWN");
        }

        @Test
        @DisplayName("Should handle exception with null message (no NPE from Map.of in error response)")
        void shouldHandleExceptionWithNullMessage() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenThrow(new NullPointerException());

            Map<String, Object> result = client.consumeCredits(
                    USER_ID, SOURCE_TYPE, SOURCE_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS);

            assertThat(result).containsEntry("success", false);
            assertThat(result).containsKey("error");
            // Should fall back to class name when message is null
            assertThat(result.get("error").toString()).contains("NullPointerException");
        }

        @Test
        @DisplayName("Should handle all parameters null without NPE")
        @SuppressWarnings("unchecked")
        void shouldHandleAllNullableParametersWithoutNpe() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(successResponse());

            // All nullable parameters are null at once
            client.consumeCredits(USER_ID, null, null, null, null, null, null);

            verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), httpEntityCaptor.capture(), eq(Map.class));
            Map<String, Object> body = httpEntityCaptor.getValue().getBody();

            assertThat(body).isNotNull();
            assertThat(body).containsEntry("sourceType", "UNKNOWN");
            assertThat(body).containsEntry("sourceId", "");
            assertThat(body).containsEntry("provider", "unknown");
            assertThat(body).containsEntry("model", "unknown");
            assertThat(body).containsEntry("promptTokens", 0);
            assertThat(body).containsEntry("completionTokens", 0);
        }
    }

    // ── consumeCreditsAsync() tests ──────────────────────────────────

    @Nested
    @DisplayName("consumeCreditsAsync()")
    class ConsumeCreditsAsyncTests {

        @Test
        @DisplayName("Should delegate to consumeCredits synchronously")
        void shouldDelegateToConsumeCredits() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(successResponse());

            // V261/V263: consumeCreditsAsync captures orgId from TenantResolver and
            // fails-fast via requireOrgId on the producer thread. Bind a scope so
            // this unit test (no request context) doesn't trip the IllegalArgumentException.
            TenantResolver.runWithOrgScope("test-org", () ->
                    client.consumeCreditsAsync(USER_ID, SOURCE_TYPE, SOURCE_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS));

            verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class));
        }

        @Test
        @DisplayName("Should catch and not propagate exceptions from consumeCredits")
        void shouldCatchExceptionsFromConsumeCredits() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenThrow(new RuntimeException("Unexpected error"));

            // consumeCredits catches exceptions internally and returns error map,
            // but consumeCreditsAsync also wraps with try-catch for safety.
            // This should NOT throw.
            // V261/V263: bind org scope so the producer-thread requireOrgId guard passes.
            TenantResolver.runWithOrgScope("test-org", () ->
                    client.consumeCreditsAsync(USER_ID, SOURCE_TYPE, SOURCE_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS));

            // No exception propagated - test passes if we reach here
            // consumeCreditsAsync retries up to MAX_RETRIES (3) times on failure
            verify(restTemplate, times(3)).exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class));
        }
    }

    // ── URL Construction tests ───────────────────────────────────────

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
            CreditConsumptionClient customClient = new CreditConsumptionClient(customUrl);
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
            CreditConsumptionClient customClient = new CreditConsumptionClient(baseUrl);
            ReflectionTestUtils.setField(customClient, "restTemplate", restTemplate);

            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(successResponse());

            customClient.consumeCredits(USER_ID, SOURCE_TYPE, SOURCE_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS);

            verify(restTemplate).exchange(urlCaptor.capture(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class));
            assertThat(urlCaptor.getValue()).isEqualTo(baseUrl + "/api/credits/consume");
        }
    }

    // ── userId validation tests ─────────────────────────────────────

    @Nested
    @DisplayName("userId validation")
    class UserIdValidationTests {

        @Test
        @DisplayName("Should skip credit consumption when userId is null")
        void shouldSkipWhenUserIdIsNull() {
            Map<String, Object> result = client.consumeCredits(
                    null, SOURCE_TYPE, SOURCE_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS);

            assertThat(result).containsEntry("success", false);
            assertThat(result.get("error").toString()).contains("userId is null or blank");
            // Should NOT call RestTemplate at all
            verifyNoInteractions(restTemplate);
        }

        @Test
        @DisplayName("Should skip credit consumption when userId is blank")
        void shouldSkipWhenUserIdIsBlank() {
            Map<String, Object> result = client.consumeCredits(
                    "   ", SOURCE_TYPE, SOURCE_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS);

            assertThat(result).containsEntry("success", false);
            assertThat(result.get("error").toString()).contains("userId is null or blank");
            verifyNoInteractions(restTemplate);
        }

        @Test
        @DisplayName("Should skip credit consumption when userId is empty string")
        void shouldSkipWhenUserIdIsEmpty() {
            Map<String, Object> result = client.consumeCredits(
                    "", SOURCE_TYPE, SOURCE_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS);

            assertThat(result).containsEntry("success", false);
            assertThat(result.get("error").toString()).contains("userId is null or blank");
            verifyNoInteractions(restTemplate);
        }
    }

    // ── URL trailing slash tests ─────────────────────────────────────

    @Nested
    @DisplayName("Trailing slash handling")
    class TrailingSlashTests {

        @Test
        @DisplayName("Should strip trailing slash from configured URL")
        void shouldStripTrailingSlash() {
            CreditConsumptionClient customClient = new CreditConsumptionClient("http://auth-service:8083/");
            ReflectionTestUtils.setField(customClient, "restTemplate", restTemplate);

            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(successResponse());

            customClient.consumeCredits(USER_ID, SOURCE_TYPE, SOURCE_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS);

            verify(restTemplate).exchange(urlCaptor.capture(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class));
            assertThat(urlCaptor.getValue()).isEqualTo("http://auth-service:8083/api/credits/consume");
        }
    }

    // ── checkCredits() tests ──────────────────────────────────────

    @Nested
    @DisplayName("checkCredits()")
    class CheckCreditsTests {

        @SuppressWarnings("unchecked")
        private ResponseEntity<Map> checkAllowedResponse(boolean allowed) {
            Map<String, Object> body = Map.of("allowed", allowed);
            return new ResponseEntity<>(body, HttpStatus.OK);
        }

        // ── Happy path ──

        @Test
        @DisplayName("Should return true when auth-service returns allowed=true")
        void shouldReturnTrueWhenAllowed() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(checkAllowedResponse(true));

            boolean result = client.checkCredits(USER_ID);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("Should return false when auth-service returns allowed=false")
        void shouldReturnFalseWhenNotAllowed() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(checkAllowedResponse(false));

            boolean result = client.checkCredits(USER_ID);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("Should call correct URL with X-User-ID header")
        void shouldCallCorrectUrlWithHeader() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(checkAllowedResponse(true));

            client.checkCredits(USER_ID);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<HttpEntity<Map<String, Object>>> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(
                    eq(AUTH_SERVICE_URL + "/api/credits/check"),
                    eq(HttpMethod.GET), entityCaptor.capture(), eq(Map.class));
            assertThat(entityCaptor.getValue().getHeaders().getFirst("X-User-ID")).isEqualTo(USER_ID);
        }

        // ── Null/blank userId bypass ──

        @Test
        @DisplayName("Should return true when userId is null (no restriction)")
        void shouldReturnTrueWhenUserIdIsNull() {
            boolean result = client.checkCredits(null);

            assertThat(result).isTrue();
            verifyNoInteractions(restTemplate);
        }

        @Test
        @DisplayName("Should return true when userId is blank (no restriction)")
        void shouldReturnTrueWhenUserIdIsBlank() {
            boolean result = client.checkCredits("   ");

            assertThat(result).isTrue();
            verifyNoInteractions(restTemplate);
        }

        // ── 402 handling ──

        @Test
        @DisplayName("Should return false on 402 response status")
        @SuppressWarnings("unchecked")
        void shouldReturnFalseOn402ResponseStatus() {
            ResponseEntity<Map> response = new ResponseEntity<>(Map.of("allowed", false), HttpStatus.PAYMENT_REQUIRED);
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(response);

            boolean result = client.checkCredits(USER_ID);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("Should return false on HttpClientErrorException with 402")
        void shouldReturnFalseOnHttpClientErrorException402() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                    .thenThrow(new org.springframework.web.client.HttpClientErrorException(HttpStatus.PAYMENT_REQUIRED));

            boolean result = client.checkCredits(USER_ID);

            assertThat(result).isFalse();
        }

        // ── Error + fail-closed ──

        @Test
        @DisplayName("Should return false on generic exception with no cache (fail-closed)")
        void shouldReturnFalseOnExceptionNoCache() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                    .thenThrow(new ResourceAccessException("Connection refused"));

            boolean result = client.checkCredits(USER_ID);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("Should return false on HttpClientErrorException non-402 with no cache")
        void shouldReturnFalseOnNon402HttpClientError() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                    .thenThrow(new org.springframework.web.client.HttpClientErrorException(HttpStatus.INTERNAL_SERVER_ERROR));

            boolean result = client.checkCredits(USER_ID);

            assertThat(result).isFalse();
        }

        // ── Cache behavior ──

        @Test
        @DisplayName("Should use cached allowed=true on subsequent error")
        void shouldUseCachedAllowedOnError() {
            // First call: success → cache allowed=true
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(checkAllowedResponse(true));
            client.checkCredits(USER_ID);

            // Second call: error → should use cache
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                    .thenThrow(new ResourceAccessException("Connection refused"));
            boolean result = client.checkCredits(USER_ID);

            assertThat(result).isTrue(); // cached value
        }

        @Test
        @DisplayName("Should use cached allowed=false on subsequent error")
        void shouldUseCachedNotAllowedOnError() {
            // First call: 402 → cache allowed=false
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                    .thenThrow(new org.springframework.web.client.HttpClientErrorException(HttpStatus.PAYMENT_REQUIRED));
            client.checkCredits(USER_ID);

            // Second call: error → should use cache
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                    .thenThrow(new ResourceAccessException("Connection refused"));
            boolean result = client.checkCredits(USER_ID);

            assertThat(result).isFalse(); // cached value
        }

        @Test
        @DisplayName("Should update cache on fresh successful response")
        void shouldUpdateCacheOnFreshResponse() {
            // First call: allowed=false
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(checkAllowedResponse(false));
            assertThat(client.checkCredits(USER_ID)).isFalse();

            // Second call: allowed=true (user purchased credits)
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(checkAllowedResponse(true));
            assertThat(client.checkCredits(USER_ID)).isTrue();

            // Third call: error → should use updated cache (true)
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                    .thenThrow(new ResourceAccessException("Connection refused"));
            assertThat(client.checkCredits(USER_ID)).isTrue();
        }

        // ── Edge cases ──

        @Test
        @DisplayName("Should return false on unexpected non-2xx status with no cache")
        @SuppressWarnings("unchecked")
        void shouldReturnFalseOnUnexpectedStatusNoCache() {
            ResponseEntity<Map> response = new ResponseEntity<>(Map.of(), HttpStatus.SERVICE_UNAVAILABLE);
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(response);

            boolean result = client.checkCredits(USER_ID);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("Should handle null response body gracefully")
        @SuppressWarnings("unchecked")
        void shouldHandleNullResponseBody() {
            ResponseEntity<Map> response = new ResponseEntity<>(null, HttpStatus.OK);
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(response);

            boolean result = client.checkCredits(USER_ID);

            // null body → allowed check fails → goes to useCacheOrFailClosed → no cache → false
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("Should handle missing 'allowed' key in response body")
        @SuppressWarnings("unchecked")
        void shouldHandleMissingAllowedKey() {
            ResponseEntity<Map> response = new ResponseEntity<>(Map.of("foo", "bar"), HttpStatus.OK);
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(response);

            boolean result = client.checkCredits(USER_ID);

            // allowed=null → Boolean.TRUE.equals(null) → false → cached as false
            assertThat(result).isFalse();
        }
    }

    // ── checkChatBudget() tests ───────────────────────────────────

    @Nested
    @DisplayName("checkChatBudget()")
    class CheckChatBudgetTests {

        @SuppressWarnings("unchecked")
        private ResponseEntity<Map> okAllowed() {
            return new ResponseEntity<>(Map.of("allowed", true), HttpStatus.OK);
        }

        @SuppressWarnings("unchecked")
        private ResponseEntity<Map> pr402Rejected() {
            return new ResponseEntity<>(Map.of("allowed", false), HttpStatus.PAYMENT_REQUIRED);
        }

        @Test
        @DisplayName("POSTs to /api/credits/check-chat with provider/model/estimated tokens")
        @SuppressWarnings("unchecked")
        void postsExpectedBody() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(okAllowed());

            client.checkChatBudget(USER_ID, "claude-code", "claude-sonnet-4-6", 4100, 8192);

            ArgumentCaptor<HttpEntity<Map<String, Object>>> captor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(
                    eq(AUTH_SERVICE_URL + "/api/credits/check-chat"),
                    eq(HttpMethod.POST), captor.capture(), eq(Map.class));
            Map<String, Object> body = captor.getValue().getBody();
            assertThat(body)
                    .containsEntry("provider", "claude-code")
                    .containsEntry("model", "claude-sonnet-4-6")
                    .containsEntry("estimatedPromptTokens", 4100)
                    .containsEntry("estimatedCompletionTokens", 8192);
            assertThat(captor.getValue().getHeaders().getFirst("X-User-ID")).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("Returns true on 200 allowed=true")
        void trueOn200Allowed() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(okAllowed());

            assertThat(client.checkChatBudget(USER_ID, "openai", "gpt-4", 100, 200)).isTrue();
        }

        @Test
        @DisplayName("Returns false on 402 - the regression guard")
        void falseOn402() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenThrow(new org.springframework.web.client.HttpClientErrorException(HttpStatus.PAYMENT_REQUIRED));

            assertThat(client.checkChatBudget(USER_ID, "openai", "gpt-4", 100, 200)).isFalse();
        }

        @Test
        @DisplayName("Returns false on 402 ResponseEntity status")
        void falseOn402ResponseEntity() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(pr402Rejected());

            assertThat(client.checkChatBudget(USER_ID, "openai", "gpt-4", 100, 200)).isFalse();
        }

        @Test
        @DisplayName("Fails closed on transport error with no cache")
        void failsClosedOnTransportError() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenThrow(new ResourceAccessException("connection refused"));

            // Fail-closed: if we can't confirm the user can pay, we refuse the turn.
            // Opposite would risk giving free inference when auth-service flaps.
            assertThat(client.checkChatBudget(USER_ID, "openai", "gpt-4", 100, 200)).isFalse();
        }

        @Test
        @DisplayName("Returns true for null/blank userId (caller injected tenant bypass)")
        void trueForNullUserId() {
            assertThat(client.checkChatBudget(null, "openai", "gpt-4", 100, 200)).isTrue();
            assertThat(client.checkChatBudget("  ", "openai", "gpt-4", 100, 200)).isTrue();
            verifyNoInteractions(restTemplate);
        }

        @Test
        @DisplayName("Fails closed without hitting the server when provider is null/blank - cannot price 'unknown'")
        void failsClosedOnNullProvider() {
            // Regression guard for audit P0-1: previously we'd send provider="unknown"
            // to the server, which would fall through to default mid-tier rates and
            // wave the turn through for any frontier model. That defeats the point of
            // the budget gate. With null/blank, refuse without calling the server.
            assertThat(client.checkChatBudget(USER_ID, null, "gpt-4", 100, 200)).isFalse();
            assertThat(client.checkChatBudget(USER_ID, "  ", "gpt-4", 100, 200)).isFalse();
            verifyNoInteractions(restTemplate);
        }

        @Test
        @DisplayName("Fails closed without hitting the server when model is null/blank")
        void failsClosedOnNullModel() {
            assertThat(client.checkChatBudget(USER_ID, "openai", null, 100, 200)).isFalse();
            assertThat(client.checkChatBudget(USER_ID, "openai", "  ", 100, 200)).isFalse();
            verifyNoInteractions(restTemplate);
        }

        @Test
        @DisplayName("Chat-budget rejection does NOT poison the generic checkCredits cache")
        @SuppressWarnings("unchecked")
        void chatRejectDoesNotPoisonGenericCache() {
            // Regression guard for audit P1-1: the two checks now live in distinct
            // cache keyspaces. A chat-turn rejection for "too expensive for 10,000
            // tokens of Opus" must not block a later generic `balance > 0` check for
            // the same user (a cheap workflow-node call, say).
            when(restTemplate.exchange(
                    eq(AUTH_SERVICE_URL + "/api/credits/check-chat"),
                    eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(pr402Rejected());
            assertThat(client.checkChatBudget(USER_ID, "openai", "gpt-4", 100, 200)).isFalse();

            // Now the generic GET /check path - auth-service is transiently down, so the
            // only way this can succeed is via a cached entry. If the chat-budget false
            // had been cached under `USER_ID`, this would come back false. It must not.
            when(restTemplate.exchange(
                    eq(AUTH_SERVICE_URL + "/api/credits/check"),
                    eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                    .thenThrow(new ResourceAccessException("connection refused"));
            assertThat(client.checkCredits(USER_ID)).isFalse(); // fail-closed because no cache, NOT because of stale false
            // The point: no cached entry under `USER_ID` exists (the chat rejection was stored
            // under a composite key), so this path runs fail-closed on its own merits - and
            // a later repaired auth-service would correctly say "allowed=true" on the generic path.
        }
    }

    // ── fetchBalance() tests ───────────────────────────────────────

    @Nested
    @DisplayName("fetchBalance()")
    class FetchBalanceTests {

        @SuppressWarnings("unchecked")
        private ResponseEntity<Map> balanceResponse(Object balance) {
            Map<String, Object> body = Map.of("balance", balance);
            return new ResponseEntity<>(body, HttpStatus.OK);
        }

        @Test
        @DisplayName("Should return balance on 200 OK")
        void shouldReturnBalanceOn200() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(balanceResponse(42.50));

            BigDecimal result = client.fetchBalance(USER_ID);

            assertThat(result).isEqualByComparingTo(new BigDecimal("42.5"));
            verify(restTemplate).exchange(
                    eq(AUTH_SERVICE_URL + "/api/credits/balance"),
                    eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class));
        }

        @Test
        @DisplayName("Should return ZERO on exception (fail-closed)")
        void shouldReturnZeroOnError() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                    .thenThrow(new ResourceAccessException("Connection refused"));

            BigDecimal result = client.fetchBalance(USER_ID);

            assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("Should return large value for null userId (no restriction)")
        void shouldReturnLargeValueForNullUserId() {
            BigDecimal result = client.fetchBalance(null);

            assertThat(result).isEqualByComparingTo(new BigDecimal("999999999"));
            verifyNoInteractions(restTemplate);
        }

        @Test
        @DisplayName("Should return large value for blank userId (no restriction)")
        void shouldReturnLargeValueForBlankUserId() {
            BigDecimal result = client.fetchBalance("   ");

            assertThat(result).isEqualByComparingTo(new BigDecimal("999999999"));
            verifyNoInteractions(restTemplate);
        }

        @Test
        @DisplayName("Should return ZERO on non-2xx response")
        void shouldReturnZeroOnNon2xxResponse() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(new ResponseEntity<>(Map.of(), HttpStatus.INTERNAL_SERVER_ERROR));

            BigDecimal result = client.fetchBalance(USER_ID);

            assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    // ── Stress test ──────────────────────────────────────────────────

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
    }
}
