package com.apimarketplace.orchestrator.tools.workflow.builder;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.apimarketplace.common.web.GatewayAuthenticationFilter;
import com.apimarketplace.common.web.GatewayFilterProperties;
import com.apimarketplace.common.web.GatewaySignatureV2;
import com.apimarketplace.common.web.InternalGatewaySigner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Pins the {@link ToolSchemaFetcher#checkToolExists} dispatch across three id shapes:
 * real UUID, compound {@code apiSlug/toolSlug} legacy form, and garbage.
 *
 * <p>Prod incident: validator returned 16 TOOL_NOT_FOUND errors for a workflow whose
 * stored {@code id} fields were {@code gmail/gmail-list-messages} style strings. Before
 * this change, the fetcher short-circuited every non-UUID to NOT_FOUND, rejecting valid
 * slug-form ids that the execution layer actually accepts (CatalogV1Controller exposes
 * both a {@code /tools/{uuid}/execute} and a {@code /tools/{apiSlug}/{toolSlug}/execute}
 * endpoint, proving the slug form is a legitimate identifier).
 *
 * <p>Prod incident 2026-09-26: the slug lookup hit the HMAC-gated {@code /api/workflow-inspector}
 * unsigned, so every call 401'd, became UNKNOWN (validator permissive) and was logged as a
 * "transient" WARN. The class also pins the gateway signature (replayed through the real
 * catalog filter), the ERROR log on 401/403, and which outcomes are cached.
 */
@DisplayName("ToolSchemaFetcher - UUID + slug dispatch")
class ToolSchemaFetcherSlugTest {

    private RestTemplate restTemplate;
    private ToolSchemaFetcher fetcher;

    private static final String CATALOG_URL = "http://catalog:8081";
    private static final String UUID_FORM = "85f92897-77c6-4d94-b2b7-77774eeb6aa7";
    private static final String GATEWAY_SECRET = "test-gateway-secret-key-0123456789";

    private Logger fetcherLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        fetcher = new ToolSchemaFetcher();
        restTemplate = mock(RestTemplate.class);
        ReflectionTestUtils.setField(fetcher, "restTemplate", restTemplate);
        ReflectionTestUtils.setField(fetcher, "catalogServiceUrl", CATALOG_URL);
        ReflectionTestUtils.setField(fetcher, "gatewaySecretKey", GATEWAY_SECRET);

        fetcherLogger = (Logger) LoggerFactory.getLogger(ToolSchemaFetcher.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        fetcherLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        fetcherLogger.detachAppender(logAppender);
    }

    @Test
    @DisplayName("UUID form hits /api/catalog/tools/{uuid}/info and returns EXISTS on 200")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void uuidHitsUuidEndpoint() {
        when(restTemplate.exchange(contains("/api/catalog/tools/" + UUID_FORM + "/info"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
            .thenReturn(ResponseEntity.ok((Map) Map.of("id", UUID_FORM, "name", "get_message")));

        ToolSchemaFetcher.ToolExistence out = fetcher.checkToolExists(UUID_FORM);

        assertThat(out).isEqualTo(ToolSchemaFetcher.ToolExistence.EXISTS);
        verify(restTemplate).exchange(contains("/api/catalog/tools/" + UUID_FORM + "/info"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class));
    }

    @Test
    @DisplayName("apiSlug/toolSlug form hits /api/workflow-inspector/tools/{toolSlug} (slug endpoint)")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void compoundSlugHitsSlugEndpoint() {
        // Stored form in some legacy plans (prod Gmail Auto-Labeler) - not a UUID but
        // a real catalogued tool; the slug endpoint MUST resolve it for the validator
        // to not reject the whole plan.
        when(restTemplate.exchange(contains("/api/workflow-inspector/tools/gmail-list-messages"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
            .thenReturn(ResponseEntity.ok((Map) Map.of("slug", "gmail-list-messages", "apiSlug", "gmail")));

        ToolSchemaFetcher.ToolExistence out = fetcher.checkToolExists("gmail/gmail-list-messages");

        assertThat(out).isEqualTo(ToolSchemaFetcher.ToolExistence.EXISTS);
        // Must not have hit the UUID endpoint - that would 400 on a non-UUID id.
        verify(restTemplate, never()).exchange(contains("/api/catalog/tools/"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class));
    }

    @Test
    @DisplayName("Garbage (fabricated Label_1 string) is deterministically NOT_FOUND without HTTP")
    void garbageIsDeterministicNotFound() {
        ToolSchemaFetcher.ToolExistence out = fetcher.checkToolExists("Label_1");

        assertThat(out).isEqualTo(ToolSchemaFetcher.ToolExistence.NOT_FOUND);
        // No network call - the LLM fabrication is rejected locally.
        verify(restTemplate, never()).exchange(any(String.class), eq(HttpMethod.GET),
                any(HttpEntity.class), eq(Map.class));
    }

    @Test
    @DisplayName("Slug endpoint 404 → NOT_FOUND (real unknown tool)")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void slugEndpoint404() {
        when(restTemplate.exchange(contains("/api/workflow-inspector/tools/unknown-tool"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
            .thenThrow(HttpClientErrorException.NotFound.class);

        ToolSchemaFetcher.ToolExistence out = fetcher.checkToolExists("api/unknown-tool");

        assertThat(out).isEqualTo(ToolSchemaFetcher.ToolExistence.NOT_FOUND);
    }

    @Test
    @DisplayName("Slug endpoint transient error (5xx) → UNKNOWN (soft fail, validator tolerates)")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void slugEndpointTransientError() {
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET),
                any(HttpEntity.class), eq(Map.class)))
            .thenThrow(new org.springframework.web.client.ResourceAccessException("conn reset"));

        ToolSchemaFetcher.ToolExistence out = fetcher.checkToolExists("gmail/gmail-list-messages");

        assertThat(out).isEqualTo(ToolSchemaFetcher.ToolExistence.UNKNOWN);
    }

    @Test
    @DisplayName("Tool without slash (plain slug) treated as unknown fabrication - not_found")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void plainSlugWithoutSlashIsNotFound() {
        // The stored contract is either UUID or apiSlug/toolSlug - a lone "gmail-list-messages"
        // shouldn't reach this method from a real plan. Keep the strict "deterministic
        // not_found" behaviour so a typo doesn't silently accept a nonexistent tool.
        ToolSchemaFetcher.ToolExistence out = fetcher.checkToolExists("gmail-list-messages");

        assertThat(out).isEqualTo(ToolSchemaFetcher.ToolExistence.NOT_FOUND);
        verify(restTemplate, never()).exchange(any(String.class), eq(HttpMethod.GET),
                any(HttpEntity.class), eq(Map.class));
    }

    // --- Prod 2026-09-26: every slug lookup 401'd "Missing gateway authentication headers" ---
    // /api/workflow-inspector is HMAC-gated on catalog-service; the slug call was unsigned, so
    // the 401 became UNKNOWN (validator permissive), logged as "transient" and never alerted.

    @Test
    @DisplayName("Slug lookup is signed so catalog's real GatewayAuthenticationFilter lets it through")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void slugLookupPassesRealGatewayFilter() throws Exception {
        ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        when(restTemplate.exchange(contains("/api/workflow-inspector/tools/deepseek-chat"),
                eq(HttpMethod.GET), entityCaptor.capture(), eq(Map.class)))
            .thenReturn(ResponseEntity.ok((Map) Map.of("slug", "deepseek-chat")));

        fetcher.checkToolExists("deepseek/deepseek-chat");

        HttpHeaders headers = entityCaptor.getValue().getHeaders();
        assertThat(headers.getFirst(InternalGatewaySigner.HEADER_PROVIDER_ID))
            .isEqualTo(ToolSchemaFetcher.INTERNAL_PROVIDER_ID);

        MockHttpServletResponse response = replayThroughCatalogFilter(headers,
                "/api/workflow-inspector/tools/deepseek-chat");
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("Blank gateway secret: call goes out unsigned, catalog refuses it, ERROR says configured=false")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void blankSecretIsUnsignedAndReportedAsMisconfiguration() throws Exception {
        ReflectionTestUtils.setField(fetcher, "gatewaySecretKey", "");
        ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        when(restTemplate.exchange(contains("/api/workflow-inspector/tools/deepseek-chat"),
                eq(HttpMethod.GET), entityCaptor.capture(), eq(Map.class)))
            .thenThrow(HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized", null, null, null));

        assertThat(fetcher.checkToolExists("deepseek/deepseek-chat"))
            .isEqualTo(ToolSchemaFetcher.ToolExistence.UNKNOWN);

        HttpHeaders headers = entityCaptor.getValue().getHeaders();
        assertThat(headers.getFirst(InternalGatewaySigner.HEADER_SECRET)).isNull();
        assertThat(headers.getFirst(InternalGatewaySigner.HEADER_TIMESTAMP)).isNull();
        // The same unsigned request is exactly what prod's catalog filter answered 401 to.
        assertThat(replayThroughCatalogFilter(headers, "/api/workflow-inspector/tools/deepseek-chat").getStatus())
            .isEqualTo(401);
        assertThat(logAppender.list)
            .filteredOn(e -> e.getLevel() == Level.ERROR)
            .singleElement()
            .satisfies(e -> assertThat(e.getFormattedMessage()).contains("configured=false"));
    }

    // --- CASA LC-035 cutover: the slug lookup signed v1 only, through a bare RestTemplate with no
    // v2 interceptor, so it would 401 ("Catalog slug lookup REJECTED") once accept-v1 is false.
    // These run the fetcher's OWN RestTemplate (MockRestServiceServer keeps its interceptors).

    @Test
    @DisplayName("Regression v1-only slug lookup: carries the v2 signature and passes catalog's filter with accept-v1=false")
    void slugLookupCarriesV2SignatureAndPassesFilterWithV1Off() throws Exception {
        ToolSchemaFetcher realFetcher = fetcherWithItsOwnRestTemplate(GATEWAY_SECRET);
        MockRestServiceServer server = MockRestServiceServer.bindTo(
                (RestTemplate) ReflectionTestUtils.getField(realFetcher, "restTemplate")).build();
        AtomicReference<HttpHeaders> sent = new AtomicReference<>();
        server.expect(requestTo(CATALOG_URL + "/api/workflow-inspector/tools/deepseek-chat"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(request -> sent.set(copyOf(request.getHeaders())))
                .andRespond(withSuccess("{\"slug\":\"deepseek-chat\"}", MediaType.APPLICATION_JSON));

        assertThat(realFetcher.checkToolExists("deepseek/deepseek-chat"))
                .isEqualTo(ToolSchemaFetcher.ToolExistence.EXISTS);
        server.verify();

        assertThat(sent.get().getFirst(GatewaySignatureV2.HEADER)).startsWith(GatewaySignatureV2.PREFIX);
        assertThat(replayThroughCatalogFilter(sent.get(), "/api/workflow-inspector/tools/deepseek-chat", false)
                .getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("The public UUID lookup stays unsigned: the v2 interceptor adds nothing to an unstamped call")
    void publicUuidLookupStaysUnsigned() {
        ToolSchemaFetcher realFetcher = fetcherWithItsOwnRestTemplate(GATEWAY_SECRET);
        MockRestServiceServer server = MockRestServiceServer.bindTo(
                (RestTemplate) ReflectionTestUtils.getField(realFetcher, "restTemplate")).build();
        AtomicReference<HttpHeaders> sent = new AtomicReference<>();
        server.expect(requestTo(CATALOG_URL + "/api/catalog/tools/" + UUID_FORM + "/info"))
                .andExpect(request -> sent.set(copyOf(request.getHeaders())))
                .andRespond(withSuccess("{\"id\":\"" + UUID_FORM + "\"}", MediaType.APPLICATION_JSON));

        assertThat(realFetcher.checkToolExists(UUID_FORM)).isEqualTo(ToolSchemaFetcher.ToolExistence.EXISTS);
        server.verify();

        assertThat(sent.get().getFirst(GatewaySignatureV2.HEADER)).isNull();
        assertThat(sent.get().getFirst(InternalGatewaySigner.HEADER_SECRET)).isNull();
    }

    private static HttpHeaders copyOf(HttpHeaders headers) {
        HttpHeaders copy = new HttpHeaders();
        headers.forEach((name, values) -> copy.put(name, List.copyOf(values)));
        return copy;
    }

    private static ToolSchemaFetcher fetcherWithItsOwnRestTemplate(String secret) {
        ToolSchemaFetcher realFetcher = new ToolSchemaFetcher();
        ReflectionTestUtils.setField(realFetcher, "catalogServiceUrl", CATALOG_URL);
        ReflectionTestUtils.setField(realFetcher, "gatewaySecretKey", secret);
        return realFetcher;
    }

    /**
     * Replays the captured outgoing headers through the filter catalog-service runs, configured
     * with catalog's public paths (which do NOT include /api/workflow-inspector).
     */
    private static MockHttpServletResponse replayThroughCatalogFilter(HttpHeaders headers, String path)
            throws Exception {
        return replayThroughCatalogFilter(headers, path, true);
    }

    private static MockHttpServletResponse replayThroughCatalogFilter(HttpHeaders headers, String path,
                                                                      boolean acceptV1) throws Exception {
        GatewayFilterProperties props = new GatewayFilterProperties();
        props.setSecretKey(GATEWAY_SECRET);
        props.setVerificationEnabled(true);
        // Mirrors catalog-service application.yml gateway.filter.public-paths (2026-09-26): the
        // UUID path (/api/catalog) is public, /api/workflow-inspector is NOT.
        props.setPublicPaths(List.of("/health", "/actuator", "/api/internal/", "/api/agent-tools",
                "/api/tools", "/api/catalog", "/api/v1", "/catalog/v1", "/api/tool-responses",
                "/api/mcp", "/api/tool-categories", "/api/apis"));
        GatewayAuthenticationFilter filter = new GatewayAuthenticationFilter(props, acceptV1);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        headers.forEach((name, values) -> values.forEach(v -> request.addHeader(name, v)));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    @DisplayName("Real slug (signed call answered 200) -> EXISTS, cached for the next lookup")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void realSlugIsExistsAndCached() {
        when(restTemplate.exchange(contains("/api/workflow-inspector/tools/telegram-send-message"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
            .thenReturn(ResponseEntity.ok((Map) Map.of("slug", "telegram-send-message")));

        assertThat(fetcher.checkToolExists("telegram/telegram-send-message"))
            .isEqualTo(ToolSchemaFetcher.ToolExistence.EXISTS);
        assertThat(fetcher.checkToolExists("telegram/telegram-send-message"))
            .isEqualTo(ToolSchemaFetcher.ToolExistence.EXISTS);
        verify(restTemplate, times(1)).exchange(contains("/api/workflow-inspector/tools/telegram-send-message"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class));
    }

    @Test
    @DisplayName("Unknown slug (404) -> NOT_FOUND, cached, no ERROR log")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void unknownSlugIsNotFoundAndCached() {
        when(restTemplate.exchange(contains("/api/workflow-inspector/tools/invented-tool"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
            .thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", null, null, null));

        assertThat(fetcher.checkToolExists("fake/invented-tool"))
            .isEqualTo(ToolSchemaFetcher.ToolExistence.NOT_FOUND);
        assertThat(fetcher.checkToolExists("fake/invented-tool"))
            .isEqualTo(ToolSchemaFetcher.ToolExistence.NOT_FOUND);
        verify(restTemplate, times(1)).exchange(contains("/api/workflow-inspector/tools/invented-tool"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class));
        assertThat(logAppender.list).noneMatch(e -> e.getLevel() == Level.ERROR);
    }

    @Test
    @DisplayName("401 on the slug lookup -> UNKNOWN, logged at ERROR (not 'transient'), never cached")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void unauthorizedSlugLookupIsErrorAndNotCached() {
        assertAuthRefusalIsLoudAndUncached(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("403 on the slug lookup -> UNKNOWN, logged at ERROR (not 'transient'), never cached")
    void forbiddenSlugLookupIsErrorAndNotCached() {
        assertAuthRefusalIsLoudAndUncached(HttpStatus.FORBIDDEN);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void assertAuthRefusalIsLoudAndUncached(HttpStatus status) {
        when(restTemplate.exchange(contains("/api/workflow-inspector/tools/openai-admin-cost"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
            .thenThrow(HttpClientErrorException.create(status, status.getReasonPhrase(), null,
                "{\"error\":\"Unauthorized\",\"message\":\"Missing gateway authentication headers\"}"
                    .getBytes(), null));

        assertThat(fetcher.checkToolExists("openai/openai-admin-cost"))
            .isEqualTo(ToolSchemaFetcher.ToolExistence.UNKNOWN);
        assertThat(fetcher.checkToolExists("openai/openai-admin-cost"))
            .isEqualTo(ToolSchemaFetcher.ToolExistence.UNKNOWN);

        // Not cached: both calls reached the catalog.
        verify(restTemplate, times(2)).exchange(contains("/api/workflow-inspector/tools/openai-admin-cost"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class));
        assertThat(logAppender.list)
            .filteredOn(e -> e.getLevel() == Level.ERROR)
            .hasSize(2)
            .allSatisfy(e -> {
                assertThat(e.getFormattedMessage()).contains("openai/openai-admin-cost")
                    .contains(String.valueOf(status.value()))
                    .doesNotContain("transient");
            });
        assertThat(logAppender.list).noneMatch(e -> e.getFormattedMessage().contains("transient"));
    }

    @Test
    @DisplayName("Transient 5xx on the slug lookup stays WARN 'transient' and is not cached")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void serverErrorStaysTransientWarn() {
        when(restTemplate.exchange(contains("/api/workflow-inspector/tools/gmail-list-messages"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
            .thenThrow(org.springframework.web.client.HttpServerErrorException.create(
                HttpStatus.SERVICE_UNAVAILABLE, "Unavailable", null, null, null));

        assertThat(fetcher.checkToolExists("gmail/gmail-list-messages"))
            .isEqualTo(ToolSchemaFetcher.ToolExistence.UNKNOWN);
        fetcher.checkToolExists("gmail/gmail-list-messages");

        verify(restTemplate, times(2)).exchange(contains("/api/workflow-inspector/tools/gmail-list-messages"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class));
        assertThat(logAppender.list).noneMatch(e -> e.getLevel() == Level.ERROR);
        assertThat(logAppender.list).anyMatch(e -> e.getLevel() == Level.WARN
                && e.getFormattedMessage().contains("transient"));
    }
}
