package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestTemplate;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.mockito.ArgumentCaptor;

/**
 * Unit tests for HttpRequestNode.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HttpRequestNode")
class HttpRequestNodeTest {

    @Mock private WorkflowPlan mockPlan;
    @Mock private RestTemplate mockRestTemplate;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        context = ExecutionContext.create("run-1", "wr-1", "tenant-1", "item-0", 0, Map.of(), mockPlan);
    }

    @Nested
    @DisplayName("Constructor and properties")
    class ConstructorTests {

        @Test
        @DisplayName("should have HTTP_REQUEST node type")
        void shouldHaveHttpRequestType() {
            HttpRequestNode node = HttpRequestNode.builder().nodeId("core:http").build();
            assertEquals(NodeType.HTTP_REQUEST, node.getType());
        }

        @Test
        @DisplayName("should default method to GET")
        void shouldDefaultMethodToGet() {
            HttpRequestNode node = HttpRequestNode.builder().nodeId("core:http").build();
            assertEquals("GET", node.getMethod());
        }

        @Test
        @DisplayName("should uppercase method")
        void shouldUppercaseMethod() {
            HttpRequestNode node = HttpRequestNode.builder().nodeId("core:http").method("post").build();
            assertEquals("POST", node.getMethod());
        }

        @Test
        @DisplayName("should default auth type to none")
        void shouldDefaultAuthTypeToNone() {
            HttpRequestNode node = HttpRequestNode.builder().nodeId("core:http").build();
            assertEquals("none", node.getAuthType());
        }

        @Test
        @DisplayName("should default body type to none")
        void shouldDefaultBodyTypeToNone() {
            HttpRequestNode node = HttpRequestNode.builder().nodeId("core:http").build();
            assertEquals("none", node.getBodyType());
        }

        @Test
        @DisplayName("should handle null query params and headers")
        void shouldHandleNullLists() {
            HttpRequestNode node = HttpRequestNode.builder()
                .nodeId("core:http")
                .queryParams(null)
                .headers(null)
                .build();

            assertNotNull(node.getQueryParams());
            assertTrue(node.getQueryParams().isEmpty());
            assertNotNull(node.getHeaders());
            assertTrue(node.getHeaders().isEmpty());
        }
    }

    @Nested
    @DisplayName("execute")
    class Execute {

        @Test
        @DisplayName("should fail when RestTemplate not configured")
        void shouldFailWithoutRestTemplate() {
            HttpRequestNode node = HttpRequestNode.builder()
                .nodeId("core:http")
                .urlExpression("http://example.com/api")
                .build();

            NodeExecutionResult result = node.execute(context);
            assertTrue(result.isFailure());
            assertTrue(result.errorMessage().get().contains("RestTemplate"));
        }

        @Test
        @DisplayName("should fail when URL is null")
        void shouldFailWhenUrlNull() {
            HttpRequestNode node = HttpRequestNode.builder()
                .nodeId("core:http")
                .urlExpression(null)
                .build();
            node.setRestTemplate(mockRestTemplate);

            NodeExecutionResult result = node.execute(context);
            assertTrue(result.isFailure());
            assertTrue(result.errorMessage().get().contains("URL"));
        }

        @Test
        @DisplayName("should fail when URL is blank")
        void shouldFailWhenUrlBlank() {
            HttpRequestNode node = HttpRequestNode.builder()
                .nodeId("core:http")
                .urlExpression("  ")
                .build();
            node.setRestTemplate(mockRestTemplate);

            NodeExecutionResult result = node.execute(context);
            assertTrue(result.isFailure());
            assertTrue(result.errorMessage().get().contains("URL"));
        }

        @Test
        @DisplayName("should make successful HTTP request")
        void shouldMakeSuccessfulRequest() {
            HttpRequestNode node = HttpRequestNode.builder()
                .nodeId("core:http")
                .urlExpression("http://example.com/api")
                .method("GET")
                .build();
            node.setRestTemplate(mockRestTemplate);

            ResponseEntity<String> response = new ResponseEntity<>(
                "{\"name\": \"test\"}", HttpStatus.OK
            );
            when(mockRestTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(response);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(true, result.output().get("success"));
            assertEquals(200, result.output().get("status"));
            assertEquals("HTTP_REQUEST", result.output().get("node_type"));
        }

        @Test
        @DisplayName("should send LinkedMultiValueMap for x-www-form-urlencoded body")
        void shouldSendMultiValueMapForFormUrlencoded() {
            HttpRequestNode node = HttpRequestNode.builder()
                .nodeId("core:http")
                .urlExpression("http://example.com/api")
                .method("POST")
                .bodyType("x-www-form-urlencoded")
                .bodyExpression("key1=value1&key2=value2")
                .build();
            node.setRestTemplate(mockRestTemplate);

            ResponseEntity<String> response = new ResponseEntity<>("{}", HttpStatus.OK);
            when(mockRestTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(response);

            NodeExecutionResult result = node.execute(context);
            assertTrue(result.isSuccess());

            // Capture the HttpEntity passed to exchange and verify body is a LinkedMultiValueMap
            @SuppressWarnings("unchecked")
            ArgumentCaptor<HttpEntity<Object>> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(mockRestTemplate).exchange(anyString(), any(), entityCaptor.capture(), eq(String.class));

            Object body = entityCaptor.getValue().getBody();
            assertInstanceOf(LinkedMultiValueMap.class, body);

            @SuppressWarnings("unchecked")
            LinkedMultiValueMap<String, String> formData = (LinkedMultiValueMap<String, String>) body;
            assertEquals("value1", formData.getFirst("key1"));
            assertEquals("value2", formData.getFirst("key2"));
        }

        @Test
        @DisplayName("should handle general exception")
        void shouldHandleGeneralException() {
            HttpRequestNode node = HttpRequestNode.builder()
                .nodeId("core:http")
                .urlExpression("http://example.com/api")
                .build();
            node.setRestTemplate(mockRestTemplate);

            when(mockRestTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenThrow(new RuntimeException("Connection refused"));

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
            assertTrue(result.errorMessage().get().contains("Connection refused"));
        }

        @Test
        @DisplayName("4xx with JSON error body → SUCCESS node, output.success=false + extracted error message + parsed data")
        void shouldBuildErrorResultOn4xxWithJsonBody() {
            HttpRequestNode node = HttpRequestNode.builder()
                .nodeId("core:http")
                .urlExpression("http://example.com/api")
                .build();
            node.setRestTemplate(mockRestTemplate);

            byte[] body = "{\"error\":{\"message\":\"Not found\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            when(mockRestTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenThrow(new org.springframework.web.client.HttpClientErrorException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Not Found", body,
                    java.nio.charset.StandardCharsets.UTF_8));

            NodeExecutionResult result = node.execute(context);

            // A 4xx is a COMPLETED node carrying the error in its output (workflow continues).
            assertTrue(result.isSuccess(), "HTTP error status is a node SUCCESS with success=false in output");
            assertEquals(false, result.output().get("success"));
            assertEquals(404, result.output().get("status"));
            assertEquals("Not found", result.output().get("error"),
                "extractErrorMessage reads error.message from the JSON body");
            assertTrue(result.output().get("data") instanceof Map, "JSON error body is parsed into data");
        }

        @Test
        @DisplayName("5xx with non-JSON body → output.success=false, raw body preserved in data")
        void shouldBuildErrorResultOn5xxWithNonJsonBody() {
            HttpRequestNode node = HttpRequestNode.builder()
                .nodeId("core:http")
                .urlExpression("http://example.com/api")
                .build();
            node.setRestTemplate(mockRestTemplate);

            byte[] body = "Internal boom".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            when(mockRestTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenThrow(new org.springframework.web.client.HttpServerErrorException(
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "Server Error", body,
                    java.nio.charset.StandardCharsets.UTF_8));

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(false, result.output().get("success"));
            assertEquals(500, result.output().get("status"));
            assertEquals("Internal boom", result.output().get("data"),
                "a non-JSON error body is preserved verbatim in data");
            assertNotNull(result.output().get("error"), "error is always populated (falls back to the exception message)");
        }
    }

    @Nested
    @DisplayName("Authentication - authConfig handling")
    class Authentication {

        /**
         * Contract: authType='none' with authConfig=null (the builder default)
         * must NOT add any Authorization header. applyAuthentication returns
         * early on the `authConfig == null || "none".equals(authType)` guard,
         * so the request carries no auth credentials.
         */
        @Test
        @DisplayName("authType='none' + authConfig=null adds NO Authorization header to the request")
        void authNoneNullConfigAddsNoAuthorizationHeader() {
            HttpRequestNode node = HttpRequestNode.builder()
                .nodeId("core:http")
                .urlExpression("http://example.com/api")
                .method("GET")
                // authType defaults to "none" and authConfig stays null
                .build();
            node.setRestTemplate(mockRestTemplate);
            when(mockRestTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{}", HttpStatus.OK));

            assertEquals("none", node.getAuthType(), "sanity: default auth type is none");
            assertNull(node.getAuthConfig(), "sanity: default authConfig is null");

            NodeExecutionResult result = node.execute(context);
            assertTrue(result.isSuccess());

            @SuppressWarnings("unchecked")
            ArgumentCaptor<HttpEntity<Object>> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(mockRestTemplate).exchange(anyString(), any(), entityCaptor.capture(), eq(String.class));

            org.springframework.http.HttpHeaders sentHeaders = entityCaptor.getValue().getHeaders();
            assertFalse(sentHeaders.containsKey(org.springframework.http.HttpHeaders.AUTHORIZATION),
                "authType='none' with null config must not emit an Authorization header");
            assertNull(sentHeaders.getFirst(org.springframework.http.HttpHeaders.AUTHORIZATION),
                "no auth credentials present when authConfig is null and authType is none");
        }

        /**
         * Null-safety: authType='basic' (or any non-none type) with a null
         * authConfig is an inconsistent state. applyAuthentication checks
         * `authConfig == null` FIRST, so it returns early instead of
         * dereferencing authConfig.username() - no NPE, no Authorization header.
         */
        @Test
        @DisplayName("authType='basic' + authConfig=null does NOT NPE and adds no Authorization header")
        void authBasicNullConfigDoesNotNpe() {
            HttpRequestNode node = HttpRequestNode.builder()
                .nodeId("core:http")
                .urlExpression("http://example.com/api")
                .method("GET")
                .authType("basic")
                // authConfig deliberately left null (inconsistent state)
                .build();
            node.setRestTemplate(mockRestTemplate);
            when(mockRestTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{}", HttpStatus.OK));

            NodeExecutionResult result = assertDoesNotThrow(() -> node.execute(context),
                "null authConfig with authType=basic must not throw NPE");
            assertTrue(result.isSuccess());

            @SuppressWarnings("unchecked")
            ArgumentCaptor<HttpEntity<Object>> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(mockRestTemplate).exchange(anyString(), any(), entityCaptor.capture(), eq(String.class));

            org.springframework.http.HttpHeaders sentHeaders = entityCaptor.getValue().getHeaders();
            assertFalse(sentHeaders.containsKey(org.springframework.http.HttpHeaders.AUTHORIZATION),
                "null authConfig must short-circuit before adding any Authorization header");
        }
    }

    @Nested
    @DisplayName("SSRF protection")
    class SsrfProtection {

        @ParameterizedTest
        @ValueSource(strings = {
            "http://127.0.0.1/admin",
            "http://localhost:8080/secret",
            "http://169.254.169.254/latest/meta-data/",
            "http://10.0.0.0:8080/internal",
            "http://192.168.1.1/router",
            "http://172.16.0.1/private",
            "ftp://example.com/file"
        })
        @DisplayName("should reject SSRF URLs")
        void shouldRejectSsrfUrls(String url) {
            HttpRequestNode node = HttpRequestNode.builder()
                .nodeId("core:http")
                .urlExpression(url)
                .build();
            node.setRestTemplate(mockRestTemplate);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
            // RestTemplate.exchange should never be called for blocked URLs
            verifyNoInteractions(mockRestTemplate);
        }
    }

    @Nested
    @DisplayName("acceptServices")
    class AcceptServices {

        @Test
        @DisplayName("should accept RestTemplate from registry")
        void shouldAcceptRestTemplate() {
            ServiceRegistry registry = ServiceRegistry.builder()
                .restTemplate(mockRestTemplate)
                .build();

            HttpRequestNode node = HttpRequestNode.builder().nodeId("core:http").build();
            node.acceptServices(registry);

            // The node should now have a RestTemplate
            assertDoesNotThrow(() -> node.execute(context));
        }
    }

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("should build HttpRequestNode with all properties")
        void shouldBuildWithAll() {
            HttpRequestNode node = HttpRequestNode.builder()
                .nodeId("core:http")
                .method("POST")
                .urlExpression("http://api.example.com")
                .authType("bearer")
                .bodyType("json")
                .bodyExpression("{\"key\": \"value\"}")
                .contentType("application/json")
                .timeout(30000)
                .build();

            assertEquals("core:http", node.getNodeId());
            assertEquals("POST", node.getMethod());
            assertEquals("http://api.example.com", node.getUrlExpression());
            assertEquals("bearer", node.getAuthType());
            assertEquals("json", node.getBodyType());
            assertEquals("{\"key\": \"value\"}", node.getBodyExpression());
            assertEquals("application/json", node.getContentType());
            assertEquals(30000, node.getTimeout());
        }
    }

    // =========================================================================
    // Audit follow-ups (2026-05-06): query key encoding, RestTemplate cache,
    // multi-value response headers.
    // =========================================================================

    @Nested
    @DisplayName("Query parameters - key encoding")
    class QueryKeyEncoding {

        /**
         * Pre-fix encoded only the value, leaving the key raw.
         * A user-authored key with a space would produce an invalid URI
         * ("?foo bar=v") that some HTTP libs reject. Worse, a key containing
         * '&' would silently smuggle a second pair into the query string.
         * Post-fix: both halves URL-encoded.
         */
        @Test
        @DisplayName("Regression: query param KEY with space is %20-encoded (no malformed URL)")
        void queryKeyWithSpaceIsEncoded() {
            HttpRequestNode node = HttpRequestNode.builder()
                .nodeId("core:http")
                .urlExpression("http://example.com/api")
                .method("GET")
                .queryParams(java.util.List.of(
                    new com.apimarketplace.orchestrator.domain.workflow.Core.HttpParam("p1", "foo bar", "value")))
                .build();
            node.setRestTemplate(mockRestTemplate);
            ResponseEntity<String> response = new ResponseEntity<>("{}", HttpStatus.OK);
            when(mockRestTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(response);

            node.execute(context);

            ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
            verify(mockRestTemplate).exchange(urlCaptor.capture(), any(), any(), eq(String.class));
            String calledUrl = urlCaptor.getValue();
            assertTrue(calledUrl.contains("foo+bar=value") || calledUrl.contains("foo%20bar=value"),
                "Pre-fix: 'foo bar=value' produced malformed URL. Post-fix: key encoded. Got: " + calledUrl);
        }

        /**
         * Pre-fix: a key containing '&' would split the query string and
         * inject an unintended pair into the upstream API.
         */
        @Test
        @DisplayName("Regression: query param KEY with '&' is %26-encoded (prevents injection)")
        void queryKeyWithAmpersandIsEncoded() {
            HttpRequestNode node = HttpRequestNode.builder()
                .nodeId("core:http")
                .urlExpression("http://example.com/api")
                .method("GET")
                .queryParams(java.util.List.of(
                    new com.apimarketplace.orchestrator.domain.workflow.Core.HttpParam("p1", "a&admin=1", "v")))
                .build();
            node.setRestTemplate(mockRestTemplate);
            when(mockRestTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{}", HttpStatus.OK));

            node.execute(context);

            ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
            verify(mockRestTemplate).exchange(urlCaptor.capture(), any(), any(), eq(String.class));
            String calledUrl = urlCaptor.getValue();
            // Single pair only - '&' inside the key is %26-encoded.
            assertTrue(calledUrl.contains("a%26admin%3D1=v"),
                "Pre-fix would smuggle a second pair via raw '&'. Got: " + calledUrl);
            // Verify only ONE '=' separator outside the encoded key portion
            // (the encoded "%3D" is the user's literal '=', not a separator).
            int firstEq = calledUrl.indexOf('=', calledUrl.indexOf('?'));
            int secondEq = calledUrl.indexOf('=', firstEq + 1);
            assertEquals(-1, secondEq,
                "URL should have a single '=' separator (encoded user '=' becomes %3D)");
        }
    }

    @Nested
    @DisplayName("RestTemplate cache - per-timeout reuse")
    class RestTemplateCache {

        /**
         * Pre-fix `resolveRestTemplate` allocated a fresh RestTemplate +
         * SimpleClientHttpRequestFactory on EVERY node execution when
         * timeout was configured. Under split/loop fan-out (Gmail
         * Auto-Labeler classifies ~30 emails in parallel) this burned one
         * pool + one factory per call with no warmup, no keep-alive reuse.
         * Post-fix caches by clamped timeout value.
         */
        @Test
        @DisplayName("Regression: two nodes with same timeout share the same RestTemplate instance")
        void sameTimeoutShareTemplate() throws Exception {
            HttpRequestNode nodeA = HttpRequestNode.builder()
                .nodeId("a").timeout(5000).urlExpression("http://example.com").build();
            HttpRequestNode nodeB = HttpRequestNode.builder()
                .nodeId("b").timeout(5000).urlExpression("http://example.com").build();

            java.lang.reflect.Method resolve = HttpRequestNode.class
                .getDeclaredMethod("resolveRestTemplate");
            resolve.setAccessible(true);
            RestTemplate fromA = (RestTemplate) resolve.invoke(nodeA);
            RestTemplate fromB = (RestTemplate) resolve.invoke(nodeB);

            assertSame(fromA, fromB,
                "Pre-fix: a fresh RestTemplate per call. Post-fix: same instance reused for the same timeout.");
        }

        /** Different timeouts → different (independent) cached RestTemplates. */
        @Test
        @DisplayName("Different timeouts produce different RestTemplate instances")
        void differentTimeoutsDifferentTemplates() throws Exception {
            HttpRequestNode nodeA = HttpRequestNode.builder()
                .nodeId("a").timeout(5000).urlExpression("http://example.com").build();
            HttpRequestNode nodeB = HttpRequestNode.builder()
                .nodeId("b").timeout(10000).urlExpression("http://example.com").build();

            java.lang.reflect.Method resolve = HttpRequestNode.class
                .getDeclaredMethod("resolveRestTemplate");
            resolve.setAccessible(true);
            RestTemplate fromA = (RestTemplate) resolve.invoke(nodeA);
            RestTemplate fromB = (RestTemplate) resolve.invoke(nodeB);

            assertNotSame(fromA, fromB,
                "Each distinct timeout must have its own configured RestTemplate.");
        }

        /** No timeout → shared injected RestTemplate (no allocation). */
        @Test
        @DisplayName("No timeout returns the shared injected RestTemplate")
        void noTimeoutReturnsSharedTemplate() throws Exception {
            HttpRequestNode node = HttpRequestNode.builder()
                .nodeId("a").urlExpression("http://example.com").build();
            node.setRestTemplate(mockRestTemplate);

            java.lang.reflect.Method resolve = HttpRequestNode.class
                .getDeclaredMethod("resolveRestTemplate");
            resolve.setAccessible(true);
            RestTemplate result = (RestTemplate) resolve.invoke(node);

            assertSame(mockRestTemplate, result,
                "When no timeout, the injected shared RestTemplate is used (connection pool reused).");
        }

        /**
         * The static TIMEOUT_TEMPLATE_CACHE is a synchronized access-order
         * LinkedHashMap whose removeEldestEntry caps it at 32 entries. A runaway
         * templating bug could otherwise produce an unbounded set of distinct
         * timeout values. Resolving 40 distinct timeouts must leave the cache at
         * its 32 ceiling (never above), evicting the least-recently-used entries.
         */
        @Test
        @DisplayName("Cache is bounded: resolving 40 distinct timeouts never grows past 32 entries (LRU eviction)")
        @SuppressWarnings("unchecked")
        void cacheBoundedAt32WithLruEviction() throws Exception {
            java.lang.reflect.Field cacheField = HttpRequestNode.class
                .getDeclaredField("TIMEOUT_TEMPLATE_CACHE");
            cacheField.setAccessible(true);
            Map<Integer, RestTemplate> cache = (Map<Integer, RestTemplate>) cacheField.get(null);

            java.lang.reflect.Method resolve = HttpRequestNode.class
                .getDeclaredMethod("resolveRestTemplate");
            resolve.setAccessible(true);

            // Use a non-overlapping high band so this test is independent of any
            // timeouts cached by sibling tests, then assert on those keys only.
            int base = 100_001;
            int last = base + 39; // 40th distinct timeout
            for (int i = 0; i < 40; i++) {
                int timeoutMs = base + i;
                HttpRequestNode node = HttpRequestNode.builder()
                    .nodeId("n" + i).timeout(timeoutMs).urlExpression("http://example.com").build();
                resolve.invoke(node);
            }

            // Capacity invariant: the cap is enforced for the whole shared map.
            assertTrue(cache.size() <= TIMEOUT_TEMPLATE_CACHE_SIZE_REFLECTED(),
                "cache must never exceed its 32-entry ceiling; was " + cache.size());

            // LRU semantics: the FIRST inserted timeout (least recently used) is
            // evicted once we add far more than 32 distinct keys, while the LAST
            // inserted one is still present.
            assertFalse(cache.containsKey(base),
                "oldest (least-recently-used) timeout must be evicted past capacity");
            assertTrue(cache.containsKey(last),
                "most-recently-resolved timeout must remain cached");
        }

        private int TIMEOUT_TEMPLATE_CACHE_SIZE_REFLECTED() throws Exception {
            java.lang.reflect.Field sizeField = HttpRequestNode.class
                .getDeclaredField("TIMEOUT_TEMPLATE_CACHE_SIZE");
            sizeField.setAccessible(true);
            return sizeField.getInt(null);
        }
    }

    @Nested
    @DisplayName("Response headers - multi-value preservation")
    class ResponseHeadersMulti {

        /**
         * `headers` (legacy single-value map) collapses repeated headers to
         * the first value. `headersMulti` exposes the full list per key.
         * Required for `Set-Cookie` (multiple cookies), `Link` (paginated APIs),
         * `Vary`, etc. Pre-fix only `headers` existed → caller couldn't see
         * any but the first value.
         */
        @Test
        @DisplayName("Regression: response with multiple Set-Cookie surfaces all values in headersMulti")
        void multiValueHeaderExposedInHeadersMulti() {
            HttpRequestNode node = HttpRequestNode.builder()
                .nodeId("core:http")
                .urlExpression("http://example.com/api")
                .method("GET")
                .build();
            node.setRestTemplate(mockRestTemplate);

            org.springframework.http.HttpHeaders responseHeaders = new org.springframework.http.HttpHeaders();
            responseHeaders.add("Set-Cookie", "session=abc; Path=/");
            responseHeaders.add("Set-Cookie", "csrf=xyz; Path=/");
            responseHeaders.add("Link", "<http://api/page2>; rel=\"next\"");
            responseHeaders.add("Link", "<http://api/page99>; rel=\"last\"");

            ResponseEntity<String> response = new ResponseEntity<>("{}", responseHeaders, HttpStatus.OK);
            when(mockRestTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(response);

            NodeExecutionResult result = node.execute(context);
            assertTrue(result.isSuccess());
            Map<String, Object> output = result.output();

            // Legacy single-value field - first value only (preserves backward compat).
            @SuppressWarnings("unchecked")
            Map<String, String> headers = (Map<String, String>) output.get("headers");
            assertEquals("session=abc; Path=/", headers.get("Set-Cookie"),
                "Legacy 'headers' map must keep returning the first value (backward compat)");

            // New multi-value field - all values per key.
            @SuppressWarnings("unchecked")
            Map<String, java.util.List<String>> headersMulti =
                (Map<String, java.util.List<String>>) output.get("headersMulti");
            assertNotNull(headersMulti, "headersMulti field must be present");
            java.util.List<String> cookies = headersMulti.get("Set-Cookie");
            assertEquals(2, cookies.size(), "Both Set-Cookie values must be exposed");
            assertEquals("session=abc; Path=/", cookies.get(0));
            assertEquals("csrf=xyz; Path=/", cookies.get(1));
            java.util.List<String> links = headersMulti.get("Link");
            assertEquals(2, links.size(), "Both Link values must be exposed");
        }
    }
}
