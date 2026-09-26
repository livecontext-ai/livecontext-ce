package com.apimarketplace.catalog.service.http;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.apimarketplace.catalog.domain.ApiEntity;
import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.repository.ApiToolParameterRepository;
import com.apimarketplace.catalog.service.UserCredentialService;
import com.apimarketplace.catalog.service.execution.StreamingResponseHandler;
import com.apimarketplace.common.security.CredentialEncryptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Regression, 2026-09-25: the catalog-service INFO log carried a Telegram bot token in clear on
 * every {@code sendMessage}, because that API puts its credential IN the URL path
 * ({@code /bot{token}/sendMessage}) and the "Final URL" and "Calling" lines printed the URL after
 * substitution. A transport failure was worse: RestTemplate words it around the full URL, and
 * that message went back to the caller as the tool's {@code error}, i.e. into a workflow's
 * step output.
 *
 * <p>Every test drives the real execution method and inspects BOTH what it returns and every
 * log event it emitted, with the throwable chain rendered, because a secret printed in a stack
 * trace leaks as surely as one in a message.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HttpExecutionService - a credential never reaches a log line or an error message")
class HttpExecutionServiceCredentialLogRedactionTest {

    private static final String TOKEN = "1234567890:FAKE-bot-token-for-redaction-test";
    private static final String API_KEY = "sk_test_FAKE_query_key_42/+=";

    @Mock private ApiToolParameterRepository apiToolParameterRepository;
    @Mock private UserCredentialService userCredentialService;
    @Mock private CredentialEncryptionService encryptionService;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private RestTemplate restTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpExecutionService service;
    private ListAppender<ILoggingEvent> logs;
    private Logger serviceLogger;

    @BeforeEach
    void setUp() {
        lenient().when(apiToolParameterRepository.findByApiToolId(any())).thenReturn(List.of());
        lenient().when(encryptionService.decrypt(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(jdbcTemplate.queryForList(anyString(), (Object) any())).thenReturn(new ArrayList<>());
        lenient().when(jdbcTemplate.queryForList(anyString(), (Object) any(), (Object) any()))
                .thenReturn(new ArrayList<>());
        lenient().when(userCredentialService.getAccessTokenInfo(anyString(), anyString()))
                .thenReturn(Optional.empty());
        lenient().when(userCredentialService.getAccessToken(anyString(), anyString()))
                .thenReturn(Optional.empty());
        service = new HttpExecutionService(
                apiToolParameterRepository, userCredentialService, encryptionService,
                objectMapper, jdbcTemplate, restTemplate, new ErrorPolicyEngine(2, 10_000L));
        CredentialModeContext.clear();

        // ROOT, not the service's own logger: the streaming handler and the SSE consumer log too.
        serviceLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        logs = new ListAppender<>();
        logs.start();
        serviceLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(logs);
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    /** Telegram's shape: the credential is a path variable filled from credential data. */
    private void givenTelegramCredential() {
        when(userCredentialService.getCredentialDataMap("user1", "telegram"))
                .thenReturn(Map.of("token", TOKEN));
    }

    /** A key injected as a query parameter, the other way a credential ends up in the URL. */
    private void givenQueryInjectedKey() {
        Map<String, Object> row = new HashMap<>();
        row.put("metadata", "{\"injection\":{\"type\":\"query\",\"key\":\"api_key\"},\"field\":\"api_key\"}");
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row);
        lenient().when(jdbcTemplate.queryForList(anyString(), (Object) any())).thenReturn(rows);
        lenient().when(jdbcTemplate.queryForList(anyString(), (Object) any(), (Object) any())).thenReturn(rows);
        when(userCredentialService.getAccessToken(anyString(), anyString())).thenReturn(Optional.of(API_KEY));
    }

    private static ApiEntity api(String baseUrl) {
        ApiEntity api = new ApiEntity();
        api.setBaseUrl(baseUrl);
        return api;
    }

    private static ApiToolEntity tool(String method, String endpoint) {
        ApiToolEntity t = new ApiToolEntity();
        t.setId(UUID.randomUUID());
        t.setMethod(method);
        t.setEndpoint(endpoint);
        return t;
    }

    /**
     * RestTemplate's own wording for a transport failure (spring-web 6.2): the request URL in
     * quotes, WITH ITS QUERY STRING REMOVED. A fixture that kept the query would let a scrubber
     * that only matches the whole URL look like it works.
     */
    private static ResourceAccessException transportFailure(URI uri, String method) {
        String shown = uri.toString();
        int q = shown.indexOf('?');
        if (q >= 0) {
            shown = shown.substring(0, q);
        }
        return new ResourceAccessException("I/O error on " + method + " request for \"" + shown + "\": Connection reset");
    }

    /** Every rendered log line, throwable chain included. */
    private String everythingLogged() {
        StringBuilder sb = new StringBuilder();
        for (ILoggingEvent e : logs.list) {
            sb.append(e.getFormattedMessage()).append('\n');
            for (IThrowableProxy t = e.getThrowableProxy(); t != null; t = t.getCause()) {
                sb.append(t.getClassName()).append(": ").append(t.getMessage()).append('\n');
            }
        }
        return sb.toString();
    }

    private static String encoded(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    // ── legacy path ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("executeHttpCallWithCredentials (legacy sync/JSON path)")
    class LegacyPath {

        @Test
        @DisplayName("Regression: a path-variable credential is sent, but no log line shows it")
        void pathCredentialSentButNeverLogged() {
            givenTelegramCredential();
            when(restTemplate.exchange(any(URI.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(Object.class)))
                    .thenReturn(ResponseEntity.ok(Map.of("ok", true)));

            service.executeHttpCallWithCredentials(api("https://api.telegram.org"), tool("POST", "/bot{token}/sendMessage"),
                    objectMapper.createObjectNode(), Set.of(), "user1", "telegram");

            org.mockito.Mockito.verify(restTemplate).exchange(
                    eq(URI.create("https://api.telegram.org/bot" + TOKEN + "/sendMessage")),
                    eq(HttpMethod.POST), any(HttpEntity.class), eq(Object.class));
            assertThat(everythingLogged())
                    .doesNotContain(TOKEN)
                    .contains("https://api.telegram.org/bot{token}/sendMessage");
        }

        @Test
        @DisplayName("Regression: a transport failure returns and logs the URL with {token}, never the token")
        void transportFailureScrubbedInResultAndLog() {
            givenTelegramCredential();
            when(restTemplate.exchange(any(URI.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(Object.class)))
                    .thenAnswer(inv -> { throw transportFailure(inv.getArgument(0), "POST"); });

            Map<String, Object> result = service.executeHttpCallWithCredentials(
                    api("https://api.telegram.org"), tool("POST", "/bot{token}/sendMessage"),
                    objectMapper.createObjectNode(), Set.of(), "user1", "telegram");

            assertThat(result.get("success")).isEqualTo(false);
            assertThat(String.valueOf(result.get("error")))
                    .doesNotContain(TOKEN)
                    .contains("request for \"https://api.telegram.org/bot{token}/sendMessage\"");
            assertThat(String.valueOf(result.get("httpStatus"))).doesNotContain(TOKEN);
            assertThat(everythingLogged()).doesNotContain(TOKEN);
        }

        @Test
        @DisplayName("A query-injected key: sent encoded, logged and returned as api_key=<redacted>")
        void queryInjectedKeyScrubbed() {
            givenQueryInjectedKey();
            when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(Object.class)))
                    .thenAnswer(inv -> { throw transportFailure(inv.getArgument(0), "GET"); });

            Map<String, Object> result = service.executeHttpCallWithCredentials(
                    api("https://api.clickup.com"), tool("GET", "/v1/items"),
                    objectMapper.createObjectNode(), Set.of(), "user1", "example");

            String error = String.valueOf(result.get("error"));
            assertThat(error).doesNotContain(API_KEY).doesNotContain(encoded(API_KEY))
                    .contains("https://api.clickup.com/v1/items");
            assertThat(everythingLogged()).doesNotContain(API_KEY).doesNotContain(encoded(API_KEY))
                    .contains("api_key=<redacted>");
        }

        @Test
        @DisplayName("Regression (audit): a path token read from the DATA MAP is scrubbed even when the URL has a query")
        void pathTokenWithQueryStringScrubbed() {
            // Telegram getUpdates: the token is not the primary credential value, it comes from the
            // credential's data map, and the URL carries a query that RestTemplate drops from its
            // message, so neither "replace the whole URL" nor "mask the primary value" catches it.
            givenTelegramCredential();
            when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(Object.class)))
                    .thenAnswer(inv -> { throw transportFailure(inv.getArgument(0), "GET"); });

            Map<String, Object> result = service.executeHttpCallWithCredentials(
                    api("https://api.telegram.org"), tool("GET", "/bot{token}/getUpdates?offset=5"),
                    objectMapper.createObjectNode(), Set.of(), "user1", "telegram");

            assertThat(String.valueOf(result.get("error")))
                    .doesNotContain(TOKEN)
                    .contains("https://api.telegram.org/bot{token}/getUpdates");
            assertThat(everythingLogged()).doesNotContain(TOKEN);
        }

        @Test
        @DisplayName("401 -> refresh -> retry failing in transport: neither the old nor the refreshed token leaks")
        void refreshRetryTransportFailureScrubbed() {
            givenQueryInjectedKey();
            String refreshed = "sk_test_FAKE_refreshed_token_99";
            when(userCredentialService.forceRefreshAndGetToken("user1", "example")).thenReturn(Optional.of(refreshed));
            when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(Object.class)))
                    .thenThrow(HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "expired", null, new byte[0], null));
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Object.class)))
                    .thenAnswer(inv -> {
                        throw new ResourceAccessException("I/O error on GET request for \"" + inv.getArgument(0)
                                + "\" with " + refreshed + ": Connection reset");
                    });

            Map<String, Object> result = service.executeHttpCallWithCredentials(
                    api("https://api.clickup.com"), tool("GET", "/v1/items"),
                    objectMapper.createObjectNode(), Set.of(), "user1", "example");

            String error = String.valueOf(result.get("error"));
            assertThat(error).doesNotContain(API_KEY).doesNotContain(encoded(API_KEY)).doesNotContain(refreshed);
            assertThat(everythingLogged()).doesNotContain(API_KEY).doesNotContain(encoded(API_KEY))
                    .doesNotContain(refreshed);
        }

        @Test
        @DisplayName("The 429 retry log names the safe URL: the path token never reaches it")
        void retryLogScrubbed() {
            givenTelegramCredential();
            HttpHeaders retryAfter = new HttpHeaders();
            retryAfter.add(HttpHeaders.RETRY_AFTER, "0");
            when(restTemplate.exchange(any(URI.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(Object.class)))
                    .thenThrow(HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "slow down",
                            retryAfter, new byte[0], null))
                    .thenReturn(ResponseEntity.ok(Map.of("ok", true)));

            service.executeHttpCallWithCredentials(api("https://api.telegram.org"), tool("POST", "/bot{token}/sendMessage"),
                    objectMapper.createObjectNode(), Set.of(), "user1", "telegram");

            assertThat(everythingLogged())
                    .doesNotContain(TOKEN)
                    .contains("answered 429");
        }
    }

    // ── typed path ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("executeHttpCallTyped (typed path)")
    class TypedPath {

        private ApiToolEntity typedTool(String specJson) {
            ApiToolEntity t = tool("POST", "/bot{token}/sendMessage");
            t.setExecutionSpec(specJson);
            t.setOutputSchema("[]");
            return t;
        }

        @Test
        @DisplayName("Regression: a transport failure returns and logs the safe URL, stack trace included")
        void typedTransportFailureScrubbed() {
            givenTelegramCredential();
            when(restTemplate.exchange(any(URI.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(Object.class)))
                    .thenAnswer(inv -> { throw transportFailure(inv.getArgument(0), "POST"); });

            Map<String, Object> result = service.executeHttpCallTyped(
                    api("https://api.telegram.org"), typedTool("{\"mode\":\"sync\"}"),
                    objectMapper.createArrayNode(), Set.of(), "user1", "telegram", "tenant-1");

            assertThat(result.get("success")).isEqualTo(false);
            assertThat(String.valueOf(result.get("error")))
                    .doesNotContain(TOKEN)
                    .contains("bot{token}/sendMessage");
            assertThat(everythingLogged()).doesNotContain(TOKEN).contains("bot{token}/sendMessage");
        }

        @Test
        @DisplayName("A failure with no URL in it keeps its stack trace: nothing was scrubbed, nothing is lost")
        void unrelatedFailureKeepsStackTrace() {
            givenTelegramCredential();
            when(restTemplate.exchange(any(URI.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(Object.class)))
                    .thenThrow(new IllegalStateException("converter blew up"));

            service.executeHttpCallTyped(
                    api("https://api.telegram.org"), typedTool("{\"mode\":\"sync\"}"),
                    objectMapper.createArrayNode(), Set.of(), "user1", "telegram", "tenant-1");

            assertThat(logs.list).anySatisfy(e -> {
                assertThat(e.getFormattedMessage()).contains("converter blew up");
                assertThat(e.getThrowableProxy()).isNotNull();
            });
        }

        @Test
        @DisplayName("Typed path, query-injected key: sent, but never logged or returned")
        void typedQueryInjectedKeyScrubbed() {
            givenQueryInjectedKey();
            ApiToolEntity t = tool("GET", "/v1/items");
            t.setExecutionSpec("{\"mode\":\"sync\"}");
            t.setOutputSchema("[]");
            when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(Object.class)))
                    .thenAnswer(inv -> {
                        URI uri = inv.getArgument(0);
                        assertThat(uri.toString()).contains("api_key=" + encoded(API_KEY));
                        throw transportFailure(uri, "GET");
                    });

            Map<String, Object> result = service.executeHttpCallTyped(
                    api("https://api.clickup.com"), t, objectMapper.createArrayNode(), Set.of(),
                    "user1", "example", "tenant-1");

            assertThat(String.valueOf(result.get("error"))).doesNotContain(API_KEY).doesNotContain(encoded(API_KEY));
            assertThat(everythingLogged()).doesNotContain(API_KEY).doesNotContain(encoded(API_KEY))
                    .contains("api_key=<redacted>");
        }

        @Test
        @DisplayName("Binary response path: a transport failure is scrubbed like the JSON path")
        void binaryPathTransportFailureScrubbed() throws Exception {
            givenTelegramCredential();
            Field f = HttpExecutionService.class.getDeclaredField("binaryResponseHandler");
            f.setAccessible(true);
            f.set(service, org.mockito.Mockito.mock(com.apimarketplace.catalog.service.execution.BinaryResponseHandler.class));
            when(restTemplate.exchange(any(URI.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(byte[].class)))
                    .thenAnswer(inv -> { throw transportFailure(inv.getArgument(0), "POST"); });

            Map<String, Object> result = service.executeHttpCallTyped(
                    api("https://api.telegram.org"),
                    typedTool("{\"mode\":\"sync\",\"response\":{\"type\":\"binary\"}}"),
                    objectMapper.createArrayNode(), Set.of(), "user1", "telegram", "tenant-1");

            assertThat(String.valueOf(result.get("error"))).doesNotContain(TOKEN).contains("bot{token}/sendMessage");
            assertThat(everythingLogged()).doesNotContain(TOKEN);
        }

        @Test
        @DisplayName("Streaming through the REAL handler: its logs and the returned error carry no token")
        void streamingErrorScrubbed() throws Exception {
            givenTelegramCredential();
            com.apimarketplace.sse.SseStreamConsumer consumer =
                    org.mockito.Mockito.mock(com.apimarketplace.sse.SseStreamConsumer.class);
            when(consumer.consume(anyString(), any(), any(), any(), any()))
                    .thenAnswer(inv -> com.apimarketplace.sse.SseAggregatedResponse.withError(
                            List.of(), "stream to " + inv.getArgument(0) + " closed early"));
            Field f = HttpExecutionService.class.getDeclaredField("streamingResponseHandler");
            f.setAccessible(true);
            f.set(service, new StreamingResponseHandler(consumer));

            Map<String, Object> result = service.executeHttpCallTyped(
                    api("https://api.telegram.org"), typedTool("{\"mode\":\"streaming\"}"),
                    objectMapper.createArrayNode(), Set.of(), "user1", "telegram", "tenant-1");

            assertThat(result.toString()).doesNotContain(TOKEN).contains("bot{token}/sendMessage");
            assertThat(everythingLogged()).doesNotContain(TOKEN);
        }
    }

    @Test
    @DisplayName("StreamingResponseHandler logs the host only, never the path or query")
    void streamingHandlerHostOnly() {
        assertThat(StreamingResponseHandler.hostOf("https://api.telegram.org/bot" + TOKEN + "/x?k=v"))
                .isEqualTo("api.telegram.org");
        assertThat(StreamingResponseHandler.hostOf("not a url")).isEqualTo("<unparseable url>");
    }
}
