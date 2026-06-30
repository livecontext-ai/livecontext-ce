package com.apimarketplace.catalog.service.http;

import com.apimarketplace.catalog.domain.ApiEntity;
import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.domain.ApiToolParameterEntity;
import com.apimarketplace.catalog.repository.ApiToolParameterRepository;
import com.apimarketplace.catalog.service.UserCredentialService;
import com.apimarketplace.common.security.CredentialEncryptionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;

import java.util.*;

import com.apimarketplace.common.web.UrlSafetyValidator;
import org.mockito.MockedStatic;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.web.client.HttpClientErrorException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for HttpExecutionService.
 *
 * HttpExecutionService handles HTTP call execution to external APIs,
 * including URL building, parameter processing, headers, and credentials.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HttpExecutionService")
class HttpExecutionServiceTest {

    @Mock
    private ApiToolParameterRepository apiToolParameterRepository;

    @Mock
    private UserCredentialService userCredentialService;

    @Mock
    private CredentialEncryptionService encryptionService;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private RestTemplate restTemplate;

    private ObjectMapper objectMapper;
    private HttpExecutionService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        // Mock decrypt to return the input value (simulates backward-compat for non-encrypted values)
        lenient().when(encryptionService.decrypt(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new HttpExecutionService(
            apiToolParameterRepository,
            userCredentialService,
            encryptionService,
            objectMapper,
            jdbcTemplate,
            restTemplate
        );
    }

    // ========================================================================
    // buildFullUrl tests
    // ========================================================================

    @Nested
    @DisplayName("buildFullUrl()")
    class BuildFullUrlTests {

        @Test
        @DisplayName("should build URL when both have slash")
        void shouldBuildUrlWhenBothHaveSlash() {
            ApiEntity api = createTestApi("http://api.example.com/");
            ApiToolEntity tool = createTestTool("/users");

            String result = service.buildFullUrl(api, tool);

            assertEquals("http://api.example.com/users", result);
        }

        @Test
        @DisplayName("should build URL when neither has slash")
        void shouldBuildUrlWhenNeitherHasSlash() {
            ApiEntity api = createTestApi("http://api.example.com");
            ApiToolEntity tool = createTestTool("users");

            String result = service.buildFullUrl(api, tool);

            assertEquals("http://api.example.com/users", result);
        }

        @Test
        @DisplayName("should build URL when only base has slash")
        void shouldBuildUrlWhenOnlyBaseHasSlash() {
            ApiEntity api = createTestApi("http://api.example.com/");
            ApiToolEntity tool = createTestTool("users");

            String result = service.buildFullUrl(api, tool);

            assertEquals("http://api.example.com/users", result);
        }

        @Test
        @DisplayName("should build URL when only endpoint has slash")
        void shouldBuildUrlWhenOnlyEndpointHasSlash() {
            ApiEntity api = createTestApi("http://api.example.com");
            ApiToolEntity tool = createTestTool("/users");

            String result = service.buildFullUrl(api, tool);

            assertEquals("http://api.example.com/users", result);
        }

        @Test
        @DisplayName("Absolute https endpoint overrides baseUrl (Google /upload/ media endpoint)")
        void absoluteEndpointOverridesBaseUrl() {
            // YouTube media upload lives on the /upload/ prefix, a different path than the
            // API baseUrl (.../youtube/v3). An absolute endpoint URL must be used verbatim.
            ApiEntity api = createTestApi("https://www.googleapis.com/youtube/v3");
            ApiToolEntity tool = createTestTool(
                "https://www.googleapis.com/upload/youtube/v3/videos?uploadType=multipart");

            String result = service.buildFullUrl(api, tool);

            assertEquals("https://www.googleapis.com/upload/youtube/v3/videos?uploadType=multipart", result,
                "An absolute http(s) endpoint must bypass baseUrl concatenation.");
        }

        @Test
        @DisplayName("Absolute http endpoint also overrides baseUrl")
        void absoluteHttpEndpointOverridesBaseUrl() {
            ApiEntity api = createTestApi("https://api.example.com/v1");
            ApiToolEntity tool = createTestTool("http://other-host.internal/raw");

            String result = service.buildFullUrl(api, tool);

            assertEquals("http://other-host.internal/raw", result);
        }
    }

    // ========================================================================
    // processPathParameters tests
    // ========================================================================

    @Nested
    @DisplayName("processPathParameters()")
    class ProcessPathParametersTests {

        @Test
        @DisplayName("should replace path parameter in URL")
        void shouldReplacePathParameterInUrl() throws Exception {
            String url = "http://api.example.com/users/{userId}";
            ApiToolEntity tool = createTestTool("/users/{userId}");
            ArrayNode parameters = objectMapper.createArrayNode();
            parameters.add(objectMapper.createObjectNode().put("userId", "123"));

            String result = service.processPathParameters(url, tool, parameters);

            assertEquals("http://api.example.com/users/123", result);
        }

        @Test
        @DisplayName("should replace multiple path parameters")
        void shouldReplaceMultiplePathParameters() throws Exception {
            String url = "http://api.example.com/users/{userId}/posts/{postId}";
            ApiToolEntity tool = createTestTool("/users/{userId}/posts/{postId}");
            ArrayNode parameters = objectMapper.createArrayNode();
            parameters.add(objectMapper.createObjectNode().put("userId", "123"));
            parameters.add(objectMapper.createObjectNode().put("postId", "456"));

            String result = service.processPathParameters(url, tool, parameters);

            assertEquals("http://api.example.com/users/123/posts/456", result);
        }

        @Test
        @DisplayName("should handle missing path parameter")
        void shouldHandleMissingPathParameter() throws Exception {
            String url = "http://api.example.com/users/{userId}";
            ApiToolEntity tool = createTestTool("/users/{userId}");
            ArrayNode parameters = objectMapper.createArrayNode();

            String result = service.processPathParameters(url, tool, parameters);

            // URL should still contain unexpanded variable
            assertEquals("http://api.example.com/users/{userId}", result);
        }

        @Test
        @DisplayName("should handle null parameters")
        void shouldHandleNullParameters() {
            String url = "http://api.example.com/users/{userId}";
            ApiToolEntity tool = createTestTool("/users/{userId}");

            String result = service.processPathParameters(url, tool, null);

            assertEquals("http://api.example.com/users/{userId}", result);
        }

        // =====================================================================
        // Regression: path values must escape the chars that would actively
        // break URL structure ('?', '#', ' ') while preserving '/' for tools
        // whose `{path}` placeholder intentionally consumes multiple segments
        // (AWS S3 keys, Firebase RTDB paths, Firestore document_path, GitHub
        // Contents path, dbt artifacts, Cloudinary folders - ~50 catalog tools).
        // Pre-fix (≤ 2026-05-06) `url.replace(value)` substituted raw - a
        // userId like "abc?bypass=1" could escape the path and start a query.
        // Post-fix uses conservative encoding: only ' ', '#', '?' are escaped.
        // =====================================================================

        /** '?' in the value would otherwise start the query string. */
        @Test
        @DisplayName("Regression: path value with '?' is %3F-encoded - does not start query")
        void pathValueWithQuestionMarkIsEncoded() throws Exception {
            String url = "http://api.example.com/items/{id}";
            ApiToolEntity tool = createTestTool("/items/{id}");
            ArrayNode parameters = objectMapper.createArrayNode();
            parameters.add(objectMapper.createObjectNode().put("id", "abc?bypass=1"));

            String result = service.processPathParameters(url, tool, parameters);

            // Only '?' is the URL-structural threat here. '=' is a sub-delim
            // allowed in path segments (RFC 3986 §3.3 pchar) so it stays literal.
            assertEquals("http://api.example.com/items/abc%3Fbypass=1", result,
                "Pre-fix: raw replace allowed ? to start a query. Post-fix encodes ?.");
        }

        /** '#' would otherwise start a URI fragment. */
        @Test
        @DisplayName("Regression: path value with '#' is %23-encoded - does not start fragment")
        void pathValueWithHashIsEncoded() throws Exception {
            String url = "http://api.example.com/notes/{noteId}";
            ApiToolEntity tool = createTestTool("/notes/{noteId}");
            ArrayNode parameters = objectMapper.createArrayNode();
            parameters.add(objectMapper.createObjectNode().put("noteId", "note#3"));

            String result = service.processPathParameters(url, tool, parameters);

            assertEquals("http://api.example.com/notes/note%233", result,
                "Pre-fix: raw replace let '#' start a fragment, dropping everything after. Post-fix encodes #.");
        }

        /** Spaces in the value would break URI parsers. */
        @Test
        @DisplayName("Regression: path value with space is %20-encoded - produces valid URI")
        void pathValueWithSpaceIsEncoded() throws Exception {
            String url = "http://api.example.com/items/{id}";
            ApiToolEntity tool = createTestTool("/items/{id}");
            ArrayNode parameters = objectMapper.createArrayNode();
            parameters.add(objectMapper.createObjectNode().put("id", "abc def"));

            String result = service.processPathParameters(url, tool, parameters);

            assertEquals("http://api.example.com/items/abc%20def", result,
                "Space must be encoded as %20 - raw space breaks URI parsing.");
        }

        /**
         * Critical backward-compatibility test: '/' inside a path value MUST stay
         * literal. ~50 catalog tools (S3 keys, Firebase RTDB paths, GitHub
         * Contents, …) intentionally pass multi-segment values into a single
         * `{path}` placeholder. Encoding '/' as %2F would silently route to a
         * non-existent resource on every one of those tools.
         */
        @Test
        @DisplayName("Backward-compat: path value with '/' STAYS literal (S3/Firebase/GitHub multi-segment)")
        void pathValueWithSlashStaysLiteral() throws Exception {
            String url = "http://api.example.com/buckets/{bucket}/objects/{key}";
            ApiToolEntity tool = createTestTool("/buckets/{bucket}/objects/{key}");
            ArrayNode parameters = objectMapper.createArrayNode();
            parameters.add(objectMapper.createObjectNode().put("bucket", "my-bucket"));
            // S3-style key: hierarchical path with literal slashes
            parameters.add(objectMapper.createObjectNode().put("key", "images/2025/photo.jpg"));

            String result = service.processPathParameters(url, tool, parameters);

            assertEquals("http://api.example.com/buckets/my-bucket/objects/images/2025/photo.jpg", result,
                "Multi-segment paths must NOT have '/' encoded - would break S3/Firebase/GitHub Contents.");
        }

        /** ':' and '@' are sub-delim/userinfo chars allowed in path segments - stay literal. */
        @Test
        @DisplayName("Backward-compat: ':', '@' in path values stay literal")
        void pathValueWithColonAndAtStayLiteral() throws Exception {
            String url = "http://api.example.com/timestamps/{ts}";
            ApiToolEntity tool = createTestTool("/timestamps/{ts}");
            ArrayNode parameters = objectMapper.createArrayNode();
            parameters.add(objectMapper.createObjectNode().put("ts", "2026-05-06T13:00:00@UTC"));

            String result = service.processPathParameters(url, tool, parameters);

            assertEquals("http://api.example.com/timestamps/2026-05-06T13:00:00@UTC", result,
                "':' and '@' are RFC 3986 pchar - must stay literal, esp. for ISO timestamps.");
        }

        /**
         * Already-encoded values pass through untouched - we don't double-encode.
         * A caller that pre-encoded `abc%2Fdef` deliberately gets the literal
         * %2F intact (because '%', '2', 'F' aren't in our encode set).
         */
        @Test
        @DisplayName("Already-encoded values are NOT double-encoded")
        void preEncodedValuesPassThrough() throws Exception {
            String url = "http://api.example.com/items/{id}";
            ApiToolEntity tool = createTestTool("/items/{id}");
            ArrayNode parameters = objectMapper.createArrayNode();
            parameters.add(objectMapper.createObjectNode().put("id", "abc%2Fdef"));

            String result = service.processPathParameters(url, tool, parameters);

            assertEquals("http://api.example.com/items/abc%2Fdef", result,
                "Pre-encoded %2F must NOT become %252F - '%' is not in the conservative encode set.");
        }

        /** Hex IDs (Gmail message ids, UUIDs) must not be touched. */
        @Test
        @DisplayName("Hex/UUID IDs pass through unchanged - no over-encoding")
        void hexIdsPassThrough() throws Exception {
            String url = "http://api.example.com/messages/{id}";
            ApiToolEntity tool = createTestTool("/messages/{id}");
            ArrayNode parameters = objectMapper.createArrayNode();
            parameters.add(objectMapper.createObjectNode().put("id", "19dfd4597412f09b"));

            String result = service.processPathParameters(url, tool, parameters);

            assertEquals("http://api.example.com/messages/19dfd4597412f09b", result,
                "Plain hex ids must be untouched.");
        }

        // =====================================================================
        // Strict path encoding (opt-in via param extras.encoding = "strict").
        // For path params whose value is an OPAQUE identifier - a full URL or
        // URN - the conservative default (keep '/' ':' literal) produces a
        // malformed URL. Prod run run_<id>: Search Console
        // submit_sitemap built /sites/https://livecontext.ai//sitemaps/... → 404.
        // Strict encoding percent-encodes ':' → %3A and '/' → %2F.
        // =====================================================================

        /** encodePathValueStrict helper: full percent-encoding of a URL value. */
        @Test
        @DisplayName("encodePathValueStrict encodes ':' and '/' for a full-URL value")
        void strictEncodesColonAndSlash() {
            assertEquals("https%3A%2F%2Flivecontext.ai%2F",
                service.encodePathValueStrict("https://livecontext.ai/"));
        }

        /** Strict encoder normalizes space to %20 (not '+'), unlike form encoding. */
        @Test
        @DisplayName("encodePathValueStrict normalizes space to %20 not +")
        void strictEncodesSpaceAsPct20() {
            String encoded = service.encodePathValueStrict("a b");
            assertEquals("a%20b", encoded);
            assertFalse(encoded.contains("+"), "space must be %20 in a path, never '+'");
        }

        /**
         * Strict expects RAW (un-encoded) values: it treats the value as opaque and
         * encodes '%' too, so a pre-encoded "%2F" becomes "%252F". This is the
         * documented contract (SCHEMA.md) - callers must pass raw values to a
         * strict-tagged param. Pinned so the behavior can't drift silently.
         */
        @Test
        @DisplayName("encodePathValueStrict double-encodes already-encoded input (expects raw values)")
        void strictDoubleEncodesPreEncodedInput() {
            assertEquals("a%252Fb", service.encodePathValueStrict("a%2Fb"));
        }

        /**
         * Regression for the Search Console submit_sitemap 404. A path param tagged
         * encoding=strict (carried via extras) must be fully encoded so the upstream
         * route matches. Mocks the param metadata to return encoding=strict.
         */
        @Test
        @DisplayName("Regression: strict-tagged path param is fully encoded (Search Console sitemap)")
        void strictTaggedPathParamIsFullyEncoded() {
            String url = "https://www.googleapis.com/webmasters/v3/sites/{siteUrl}/sitemaps/{feedpath}";
            ApiToolEntity tool = createTestTool("/sites/{siteUrl}/sitemaps/{feedpath}");

            ApiToolParameterEntity siteUrlParam = new ApiToolParameterEntity();
            siteUrlParam.setName("siteUrl");
            siteUrlParam.setParameterType("path");
            siteUrlParam.setDataType("string");
            siteUrlParam.setExtras("{\"encoding\":\"strict\"}");
            ApiToolParameterEntity feedpathParam = new ApiToolParameterEntity();
            feedpathParam.setName("feedpath");
            feedpathParam.setParameterType("path");
            feedpathParam.setDataType("string");
            feedpathParam.setExtras("{\"encoding\":\"strict\"}");
            when(apiToolParameterRepository.findByApiToolId(tool.getId()))
                .thenReturn(List.of(siteUrlParam, feedpathParam));

            ArrayNode parameters = objectMapper.createArrayNode();
            parameters.add(objectMapper.createObjectNode().put("siteUrl", "https://livecontext.ai/"));
            parameters.add(objectMapper.createObjectNode().put("feedpath", "https://livecontext.ai/sitemap.xml"));

            String result = service.processPathParameters(url, tool, parameters);

            assertEquals(
                "https://www.googleapis.com/webmasters/v3/sites/https%3A%2F%2Flivecontext.ai%2F/sitemaps/https%3A%2F%2Flivecontext.ai%2Fsitemap.xml",
                result,
                "Strict-tagged path params must percent-encode ':' and '/' so Google's route matches.");
        }

        /**
         * The strict opt-in must NOT change behavior for untagged params: a
         * multi-segment value (S3 key) with no encoding directive keeps '/' literal.
         * Guards against the strict path leaking into the default.
         */
        @Test
        @DisplayName("Untagged path param keeps conservative encoding even when another is strict")
        void untaggedParamStaysConservativeAlongsideStrict() {
            String url = "http://api.example.com/buckets/{bucket}/objects/{key}";
            ApiToolEntity tool = createTestTool("/buckets/{bucket}/objects/{key}");

            ApiToolParameterEntity keyParam = new ApiToolParameterEntity();
            keyParam.setName("key");
            keyParam.setParameterType("path");
            keyParam.setDataType("string");
            // no extras → conservative
            when(apiToolParameterRepository.findByApiToolId(tool.getId()))
                .thenReturn(List.of(keyParam));

            ArrayNode parameters = objectMapper.createArrayNode();
            parameters.add(objectMapper.createObjectNode().put("bucket", "my-bucket"));
            parameters.add(objectMapper.createObjectNode().put("key", "images/2025/photo.jpg"));

            String result = service.processPathParameters(url, tool, parameters);

            assertEquals("http://api.example.com/buckets/my-bucket/objects/images/2025/photo.jpg", result,
                "Untagged params must keep '/' literal - strict must be strictly opt-in.");
        }
    }

    // ========================================================================
    // applyHeaderParameters tests
    //
    // Regression: params declared location=header (Google Ads developer-token /
    // login-customer-id) were imported as parameterType='query' and emitted as
    // ?developer-token=... → Google front-end 404. Engine now emits parameterType=
    // 'header' params as real HTTP headers (with CR/LF stripping). Importer fix
    // (location=header → headers[] → parameter_type='header') is the paired half.
    // ========================================================================

    @Nested
    @DisplayName("applyHeaderParameters()")
    class ApplyHeaderParametersTests {

        @Test
        @DisplayName("parameterType=header is set as an HTTP header (regression: Google Ads developer-token was sent as ?query → 404)")
        void headerParamIsSetAsHttpHeader() {
            ApiToolEntity tool = createTestTool("/customers/{customerId}/googleAds:searchStream");
            ApiToolParameterEntity dev = new ApiToolParameterEntity();
            dev.setName("developer-token"); dev.setParameterType("header"); dev.setDataType("string");
            ApiToolParameterEntity login = new ApiToolParameterEntity();
            login.setName("login-customer-id"); login.setParameterType("header"); login.setDataType("string");
            when(apiToolParameterRepository.findByApiToolId(tool.getId())).thenReturn(List.of(dev, login));

            ArrayNode parameters = objectMapper.createArrayNode();
            parameters.add(objectMapper.createObjectNode().put("developer-token", "abc123"));
            parameters.add(objectMapper.createObjectNode().put("login-customer-id", "8796682449"));

            HttpHeaders headers = new HttpHeaders();
            service.applyHeaderParameters(headers, tool, parameters);

            assertEquals("abc123", headers.getFirst("developer-token"));
            assertEquals("8796682449", headers.getFirst("login-customer-id"));
        }

        @Test
        @DisplayName("CR/LF stripped from header value (regression: pasted token had a trailing %0D → malformed request)")
        void crlfStrippedFromHeaderValue() {
            ApiToolEntity tool = createTestTool("/x");
            ApiToolParameterEntity dev = new ApiToolParameterEntity();
            dev.setName("developer-token"); dev.setParameterType("header"); dev.setDataType("string");
            when(apiToolParameterRepository.findByApiToolId(tool.getId())).thenReturn(List.of(dev));

            ArrayNode parameters = objectMapper.createArrayNode();
            parameters.add(objectMapper.createObjectNode().put("developer-token", "abc123\r\n"));

            HttpHeaders headers = new HttpHeaders();
            service.applyHeaderParameters(headers, tool, parameters);

            assertEquals("abc123", headers.getFirst("developer-token"),
                "trailing CR/LF (paste artifact) must be stripped - a \\r in a header value is rejected/injectable");
        }

        @Test
        @DisplayName("query and path params are NOT emitted as headers")
        void nonHeaderParamsAreNotSetAsHeaders() {
            ApiToolEntity tool = createTestTool("/x");
            ApiToolParameterEntity body = new ApiToolParameterEntity();
            body.setName("query"); body.setParameterType("body"); body.setDataType("string");
            ApiToolParameterEntity path = new ApiToolParameterEntity();
            path.setName("customerId"); path.setParameterType("path"); path.setDataType("string");
            when(apiToolParameterRepository.findByApiToolId(tool.getId())).thenReturn(List.of(body, path));

            ArrayNode parameters = objectMapper.createArrayNode();
            parameters.add(objectMapper.createObjectNode().put("query", "SELECT 1"));
            parameters.add(objectMapper.createObjectNode().put("customerId", "123"));

            HttpHeaders headers = new HttpHeaders();
            service.applyHeaderParameters(headers, tool, parameters);

            assertFalse(headers.containsKey("query"));
            assertFalse(headers.containsKey("customerId"));
        }

        @Test
        @DisplayName("empty / whitespace-only header value is skipped (no blank header emitted)")
        void emptyHeaderValueSkipped() {
            ApiToolEntity tool = createTestTool("/x");
            ApiToolParameterEntity dev = new ApiToolParameterEntity();
            dev.setName("developer-token"); dev.setParameterType("header"); dev.setDataType("string");
            when(apiToolParameterRepository.findByApiToolId(tool.getId())).thenReturn(List.of(dev));

            ArrayNode parameters = objectMapper.createArrayNode();
            parameters.add(objectMapper.createObjectNode().put("developer-token", "   "));

            HttpHeaders headers = new HttpHeaders();
            service.applyHeaderParameters(headers, tool, parameters);

            assertFalse(headers.containsKey("developer-token"));
        }

        @Test
        @DisplayName("transport-managed + hop-by-hop headers (Content-Length, Host, TE) are NEVER set from params - broad re-import safety")
        void transportManagedHeadersSkipped() {
            ApiToolEntity tool = createTestTool("/x");
            ApiToolParameterEntity cl = new ApiToolParameterEntity();
            cl.setName("Content-Length"); cl.setParameterType("header"); cl.setDataType("string");
            ApiToolParameterEntity host = new ApiToolParameterEntity();
            host.setName("Host"); host.setParameterType("header"); host.setDataType("string");
            ApiToolParameterEntity te = new ApiToolParameterEntity();
            te.setName("TE"); te.setParameterType("header"); te.setDataType("string");
            when(apiToolParameterRepository.findByApiToolId(tool.getId())).thenReturn(List.of(cl, host, te));

            ArrayNode parameters = objectMapper.createArrayNode();
            parameters.add(objectMapper.createObjectNode().put("Content-Length", "12345"));
            parameters.add(objectMapper.createObjectNode().put("Host", "evil.example.com"));
            parameters.add(objectMapper.createObjectNode().put("TE", "trailers"));

            HttpHeaders headers = new HttpHeaders();
            service.applyHeaderParameters(headers, tool, parameters);

            assertNull(headers.getFirst("Content-Length"), "Content-Length is computed by the client - never from a param");
            assertNull(headers.getFirst("Host"), "Host must never be overridden by a param (routing/SSRF guard)");
            assertNull(headers.getFirst("TE"), "hop-by-hop header (RFC 7230 §6.1) must never come from a param");
        }

        @Test
        @DisplayName("header param DEFAULT is injected when the caller omits it (version-pin headers: X-Api-Version, SHIPPO-API-VERSION)")
        void headerDefaultInjectedWhenCallerOmits() {
            ApiToolEntity tool = createTestTool("/items");
            ApiToolParameterEntity ver = new ApiToolParameterEntity();
            ver.setName("X-Api-Version"); ver.setParameterType("header"); ver.setDataType("string");
            ver.setDefaultValue("3");
            when(apiToolParameterRepository.findByApiToolId(tool.getId())).thenReturn(List.of(ver));

            HttpHeaders headers = new HttpHeaders();
            service.applyHeaderParameters(headers, tool, objectMapper.createArrayNode());

            assertEquals("3", headers.getFirst("X-Api-Version"),
                "a declared header default must reach the wire even when the caller supplies nothing");
        }

        @Test
        @DisplayName("caller-supplied value overrides the header default")
        void callerValueOverridesHeaderDefault() {
            ApiToolEntity tool = createTestTool("/items");
            ApiToolParameterEntity ver = new ApiToolParameterEntity();
            ver.setName("X-Api-Version"); ver.setParameterType("header"); ver.setDataType("string");
            ver.setDefaultValue("3");
            when(apiToolParameterRepository.findByApiToolId(tool.getId())).thenReturn(List.of(ver));

            ArrayNode parameters = objectMapper.createArrayNode();
            parameters.add(objectMapper.createObjectNode().put("X-Api-Version", "9"));

            HttpHeaders headers = new HttpHeaders();
            service.applyHeaderParameters(headers, tool, parameters);

            assertEquals("9", headers.getFirst("X-Api-Version"), "explicit caller value wins over the default");
        }

        @Test
        @DisplayName("placeholder default ({{var}} / {token}) is NEVER injected (no credential shadow / literal {token})")
        void placeholderDefaultNotInjected() {
            ApiToolEntity tool = createTestTool("/items");
            ApiToolParameterEntity p = new ApiToolParameterEntity();
            p.setName("api-key"); p.setParameterType("header"); p.setDataType("string");
            p.setDefaultValue("{{api_key}}");
            when(apiToolParameterRepository.findByApiToolId(tool.getId())).thenReturn(List.of(p));

            HttpHeaders headers = new HttpHeaders();
            service.applyHeaderParameters(headers, tool, objectMapper.createArrayNode());

            assertNull(headers.getFirst("api-key"), "an unresolved placeholder default must never be sent verbatim");
        }

        @Test
        @DisplayName("transport-managed header default (Content-Length) is NEVER injected")
        void transportManagedDefaultNotInjected() {
            ApiToolEntity tool = createTestTool("/items");
            ApiToolParameterEntity p = new ApiToolParameterEntity();
            p.setName("Content-Length"); p.setParameterType("header"); p.setDataType("string");
            p.setDefaultValue("123");
            when(apiToolParameterRepository.findByApiToolId(tool.getId())).thenReturn(List.of(p));

            HttpHeaders headers = new HttpHeaders();
            service.applyHeaderParameters(headers, tool, objectMapper.createArrayNode());

            assertNull(headers.getFirst("Content-Length"));
        }

        @Test
        @DisplayName("a header already present (auth / Content-Type from prepareHeaders) is NEVER overwritten by a param default - case-insensitive (blast-radius safety)")
        void preSetHeaderNotClobberedByDefault() {
            ApiToolEntity tool = createTestTool("/items");
            ApiToolParameterEntity ver = new ApiToolParameterEntity();
            ver.setName("X-Api-Version"); ver.setParameterType("header"); ver.setDataType("string");
            ver.setDefaultValue("3");
            when(apiToolParameterRepository.findByApiToolId(tool.getId())).thenReturn(List.of(ver));

            HttpHeaders headers = new HttpHeaders();
            headers.set("x-api-version", "preset"); // already set upstream (lowercase → exercises case-insensitivity)

            service.applyHeaderParameters(headers, tool, objectMapper.createArrayNode());

            assertEquals("preset", headers.getFirst("X-Api-Version"),
                "the default-injection loop must respect a header already present (case-insensitive) - this guards the whole catalog-wide blast radius (auth/Content-Type set by prepareHeaders)");
        }

        @Test
        @DisplayName("non-header param with a default is NOT injected as a header")
        void nonHeaderDefaultNotInjectedAsHeader() {
            ApiToolEntity tool = createTestTool("/items");
            ApiToolParameterEntity q = new ApiToolParameterEntity();
            q.setName("limit"); q.setParameterType("query"); q.setDataType("string");
            q.setDefaultValue("50");
            when(apiToolParameterRepository.findByApiToolId(tool.getId())).thenReturn(List.of(q));

            HttpHeaders headers = new HttpHeaders();
            service.applyHeaderParameters(headers, tool, objectMapper.createArrayNode());

            assertFalse(headers.containsKey("limit"));
        }
    }

    // ========================================================================
    // executeHttpCallWithCredentials - 401 refresh-retry must re-apply header params
    // ========================================================================

    @Nested
    @DisplayName("executeHttpCallWithCredentials() - OAuth 401 refresh-retry preserves header params")
    class RefreshRetryHeaderParamsTest {

        @Test
        @SuppressWarnings({"unchecked", "rawtypes"})
        @DisplayName("regression: on 401→refresh→retry the rebuilt request RE-APPLIES header params (developer-token) - pre-fix the retry dropped them")
        void refreshRetryReappliesHeaderParams() {
            try (MockedStatic<UrlSafetyValidator> urlValidator = mockStatic(UrlSafetyValidator.class)) {
                urlValidator.when(() -> UrlSafetyValidator.validateUrl(anyString())).thenAnswer(inv -> null);

                ApiEntity api = createTestApi("https://googleads.googleapis.com/v18");
                api.setPlatformCredentialName("googleads");
                ApiToolEntity tool = createTestTool("/customers/{customerId}/googleAds:searchStream");
                tool.setMethod("POST");

                ApiToolParameterEntity dev = new ApiToolParameterEntity();
                dev.setName("developer-token"); dev.setParameterType("header"); dev.setDataType("string");
                ApiToolParameterEntity cid = new ApiToolParameterEntity();
                cid.setName("customerId"); cid.setParameterType("path"); cid.setDataType("string");
                when(apiToolParameterRepository.findByApiToolId(tool.getId())).thenReturn(List.of(dev, cid));

                // Initial credential resolves; refresh then yields a new token → triggers the retry.
                when(userCredentialService.getAccessToken("user1", "googleads")).thenReturn(Optional.of("old-token"));
                when(userCredentialService.forceRefreshAndGetToken("user1", "googleads")).thenReturn(Optional.of("new-token"));

                ArrayNode parameters = objectMapper.createArrayNode();
                parameters.add(objectMapper.createObjectNode().put("customerId", "8796682449"));
                parameters.add(objectMapper.createObjectNode().put("developer-token", "DEVTOKEN"));

                // 1st call (URI overload) → 401; retry (String overload) → 200, capture its HttpEntity.
                when(restTemplate.exchange(any(java.net.URI.class), any(HttpMethod.class), any(), eq(Object.class)))
                    .thenThrow(HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized", new HttpHeaders(), new byte[0], null));
                ArgumentCaptor<HttpEntity> retryCaptor = ArgumentCaptor.forClass(HttpEntity.class);
                when(restTemplate.exchange(anyString(), any(HttpMethod.class), retryCaptor.capture(), eq(Object.class)))
                    .thenReturn(new ResponseEntity<>(Map.of("ok", true), HttpStatus.OK));

                Map<String, Object> result = service.executeHttpCallWithCredentials(
                    api, tool, parameters, Set.of("developer-token", "customerId"), "user1", "googleads");

                assertTrue((Boolean) result.get("success"), "retry should succeed after refresh");
                HttpEntity<?> retry = retryCaptor.getValue();
                assertEquals("DEVTOKEN", retry.getHeaders().getFirst("developer-token"),
                    "the refresh-retry MUST re-apply the developer-token header - pre-fix it was dropped → Google Ads failure");
                assertEquals("Bearer new-token", retry.getHeaders().getFirst("Authorization"),
                    "retry must carry the refreshed token");
            }
        }
    }

    // ========================================================================
    // processQueryParameters tests
    // ========================================================================

    @Nested
    @DisplayName("processQueryParameters()")
    class ProcessQueryParametersTests {

        @Test
        @DisplayName("should add query parameters to URL")
        void shouldAddQueryParametersToUrl() throws Exception {
            String url = "http://api.example.com/search";
            ApiToolEntity tool = createTestTool("/search");
            ArrayNode parameters = objectMapper.createArrayNode();
            parameters.add(objectMapper.createObjectNode().put("q", "test"));
            parameters.add(objectMapper.createObjectNode().put("limit", "10"));

            String result = service.processQueryParameters(url, tool, parameters);

            assertTrue(result.contains("q=test"));
            assertTrue(result.contains("limit=10"));
        }

        @Test
        @DisplayName("should not add path parameters as query")
        void shouldNotAddPathParametersAsQuery() throws Exception {
            String url = "http://api.example.com/users/123";
            ApiToolEntity tool = createTestTool("/users/{userId}");
            ArrayNode parameters = objectMapper.createArrayNode();
            parameters.add(objectMapper.createObjectNode().put("userId", "123"));
            parameters.add(objectMapper.createObjectNode().put("fields", "name,email"));

            String result = service.processQueryParameters(url, tool, parameters);

            // Should only have fields as query, not userId
            assertTrue(result.contains("fields=name%2Cemail"));
            assertFalse(result.contains("userId="));
        }

        @Test
        @DisplayName("should handle URL with existing query string")
        void shouldHandleUrlWithExistingQueryString() throws Exception {
            String url = "http://api.example.com/search?existing=true";
            ApiToolEntity tool = createTestTool("/search");
            ArrayNode parameters = objectMapper.createArrayNode();
            parameters.add(objectMapper.createObjectNode().put("q", "test"));

            String result = service.processQueryParameters(url, tool, parameters);

            assertTrue(result.contains("existing=true"));
            assertTrue(result.contains("&q=test"));
        }

        @Test
        @DisplayName("URL machinery for Google media uploads: absolute /upload/ URL keeps its static ?uploadType and appends a declared query param with &")
        void youtubeUploadUrlRetainsUploadTypeAndAppendsQueryParam() throws Exception {
            // Pins the buildFullUrl + processQueryParameters interaction that every
            // YouTube media-upload tool relies on (multipart_related: ?uploadType=multipart
            // + &part; raw_binary: ?uploadType=media + &videoId). It does NOT assert the
            // youtube.json content itself (validate_apis.py covers that) - it guards the
            // generic engine contract: a static ?uploadType in the absolute path survives
            // verbatim and declared query params append with & (not a second ?). POST query
            // params are only recognized via metadata (the legacy GET fallback routes
            // non-path POST params to the body), so this also pins that metadata branch.

            // multipart_related shape (captions/playlistImages/watermark/videos): part query
            ApiEntity api = createTestApi("https://www.googleapis.com/youtube/v3");
            ApiToolEntity related = createTestTool(
                "https://www.googleapis.com/upload/youtube/v3/captions?uploadType=multipart");
            related.setMethod("POST");
            ApiToolParameterEntity partParam = new ApiToolParameterEntity();
            partParam.setName("part");
            partParam.setParameterType("query");
            partParam.setDataType("string");
            when(apiToolParameterRepository.findByApiToolId(related.getId()))
                .thenReturn(List.of(partParam));

            String relatedUrl = service.buildFullUrl(api, related); // absolute → verbatim
            ArrayNode relatedParams = objectMapper.createArrayNode();
            relatedParams.add(objectMapper.createObjectNode().put("part", "snippet"));
            assertEquals(
                "https://www.googleapis.com/upload/youtube/v3/captions?uploadType=multipart&part=snippet",
                service.processQueryParameters(relatedUrl, related, relatedParams),
                "uploadType=multipart must survive and part append with & - a second ? would break the upload");

            // raw_binary shape (thumbnails.set / channelBanners.insert): uploadType=media + videoId
            ApiToolEntity raw = createTestTool(
                "https://www.googleapis.com/upload/youtube/v3/thumbnails/set?uploadType=media");
            raw.setMethod("POST");
            ApiToolParameterEntity videoIdParam = new ApiToolParameterEntity();
            videoIdParam.setName("videoId");
            videoIdParam.setParameterType("query");
            videoIdParam.setDataType("string");
            when(apiToolParameterRepository.findByApiToolId(raw.getId()))
                .thenReturn(List.of(videoIdParam));

            String rawUrl = service.buildFullUrl(api, raw);
            ArrayNode rawParams = objectMapper.createArrayNode();
            rawParams.add(objectMapper.createObjectNode().put("videoId", "dQw4w9WgXcQ"));
            assertEquals(
                "https://www.googleapis.com/upload/youtube/v3/thumbnails/set?uploadType=media&videoId=dQw4w9WgXcQ",
                service.processQueryParameters(rawUrl, raw, rawParams),
                "uploadType=media must survive and videoId append with & for the media-only raw upload");
        }

        @Test
        @DisplayName("should URL encode query parameters")
        void shouldUrlEncodeQueryParameters() throws Exception {
            String url = "http://api.example.com/search";
            ApiToolEntity tool = createTestTool("/search");
            ArrayNode parameters = objectMapper.createArrayNode();
            parameters.add(objectMapper.createObjectNode().put("q", "hello world"));

            String result = service.processQueryParameters(url, tool, parameters);

            assertTrue(result.contains("q=hello+world") || result.contains("q=hello%20world"));
        }

        // ====================================================================
        // Regression: query params with dataType=array MUST serialize as
        // OpenAPI 3 default - repeated query (?k=v1&k=v2). Bug ran from
        // catalog-service inception until 2026-05-06: the suppressed branch
        // called .asText() and emitted a single CSV value, which Gmail
        // interpreted as a literal header named "Subject,From" → returned
        // 0 headers, breaking the entire Gmail Auto-Labeler classification
        // pipeline (~234 query+array params catalog-wide were silently mis-
        // serialized).
        // ====================================================================

        /** Repro of the Gmail Auto-Labeler bug: legacy plans pass CSV string. */
        @Test
        @DisplayName("Regression: query+array param with CSV string input emits repeated query (?k=v1&k=v2)")
        void arrayParamCsvStringEmitsRepeatedQuery() throws Exception {
            String url = "https://gmail.googleapis.com/gmail/v1/users/me/messages/abc";
            ApiToolEntity tool = createTestTool("/users/{userId}/messages/{id}");

            ApiToolParameterEntity headersParam = new ApiToolParameterEntity();
            headersParam.setName("metadataHeaders");
            headersParam.setParameterType("query");
            headersParam.setDataType("array");
            ApiToolParameterEntity formatParam = new ApiToolParameterEntity();
            formatParam.setName("format");
            formatParam.setParameterType("query");
            formatParam.setDataType("string");
            when(apiToolParameterRepository.findByApiToolId(tool.getId()))
                .thenReturn(List.of(headersParam, formatParam));

            ArrayNode parameters = objectMapper.createArrayNode();
            parameters.add(objectMapper.createObjectNode().put("format", "metadata"));
            // Legacy plan: user wrote "Subject,From" as a CSV string instead of an array.
            parameters.add(objectMapper.createObjectNode().put("metadataHeaders", "Subject,From"));

            String result = service.processQueryParameters(url, tool, parameters);

            // Must emit two repeated entries - Gmail returns 0 headers when
            // metadataHeaders is sent as a single CSV value.
            assertTrue(result.contains("metadataHeaders=Subject"),
                "Expected ?metadataHeaders=Subject in: " + result);
            assertTrue(result.contains("metadataHeaders=From"),
                "Expected &metadataHeaders=From in: " + result);
            assertFalse(result.contains("metadataHeaders=Subject%2CFrom"),
                "Pre-fix bug: single CSV ?metadataHeaders=Subject%2CFrom - Gmail treats this as a literal header name and returns 0 headers. URL was: " + result);
            // Non-array param unaffected.
            assertTrue(result.contains("format=metadata"),
                "format=metadata (non-array) should still be present: " + result);
        }

        /** Modern plan style: user passes a real JSON array. */
        @Test
        @DisplayName("query+array param with JSON array input emits repeated query (?k=v1&k=v2)")
        void arrayParamJsonArrayEmitsRepeatedQuery() throws Exception {
            String url = "https://api.example.com/items";
            ApiToolEntity tool = createTestTool("/items");

            ApiToolParameterEntity tagsParam = new ApiToolParameterEntity();
            tagsParam.setName("tags");
            tagsParam.setParameterType("query");
            tagsParam.setDataType("array");
            when(apiToolParameterRepository.findByApiToolId(tool.getId()))
                .thenReturn(List.of(tagsParam));

            ArrayNode tagsArray = objectMapper.createArrayNode().add("alpha").add("beta").add("gamma");
            ArrayNode parameters = objectMapper.createArrayNode();
            parameters.add(objectMapper.createObjectNode().set("tags", tagsArray));

            String result = service.processQueryParameters(url, tool, parameters);

            // Three repeated entries, in input order (queryParts is a List).
            int alphaIdx = result.indexOf("tags=alpha");
            int betaIdx  = result.indexOf("tags=beta");
            int gammaIdx = result.indexOf("tags=gamma");
            assertTrue(alphaIdx > 0, "tags=alpha must be present in: " + result);
            assertTrue(betaIdx  > 0, "tags=beta must be present in: "  + result);
            assertTrue(gammaIdx > 0, "tags=gamma must be present in: " + result);
            // Pre-fix bug: .asText() on ArrayNode returned "" → URL would have ?tags=
            assertFalse(result.contains("tags=&") || result.endsWith("tags="),
                "Pre-fix bug: ArrayNode.asText() returned empty string and emitted ?tags= - URL was: " + result);
        }

        /** Empty array inputs emit nothing at all (no spurious ?k= clause). */
        @Test
        @DisplayName("query+array param with empty array emits no query entries")
        void arrayParamEmptyArrayEmitsNothing() throws Exception {
            String url = "https://api.example.com/items";
            ApiToolEntity tool = createTestTool("/items");

            ApiToolParameterEntity tagsParam = new ApiToolParameterEntity();
            tagsParam.setName("tags");
            tagsParam.setParameterType("query");
            tagsParam.setDataType("array");
            when(apiToolParameterRepository.findByApiToolId(tool.getId()))
                .thenReturn(List.of(tagsParam));

            ArrayNode parameters = objectMapper.createArrayNode();
            parameters.add(objectMapper.createObjectNode().set("tags", objectMapper.createArrayNode()));

            String result = service.processQueryParameters(url, tool, parameters);

            assertEquals(url, result, "Empty array must not append any query string - URL stays clean");
        }

        /** Single-value CSV behaves as a single repeated entry. */
        @Test
        @DisplayName("query+array param with single value emits one entry")
        void arrayParamSingleValueEmitsOneEntry() throws Exception {
            String url = "https://gmail.googleapis.com/gmail/v1/users/me/messages/abc";
            ApiToolEntity tool = createTestTool("/users/{userId}/messages/{id}");

            ApiToolParameterEntity headersParam = new ApiToolParameterEntity();
            headersParam.setName("metadataHeaders");
            headersParam.setParameterType("query");
            headersParam.setDataType("array");
            when(apiToolParameterRepository.findByApiToolId(tool.getId()))
                .thenReturn(List.of(headersParam));

            ArrayNode parameters = objectMapper.createArrayNode();
            parameters.add(objectMapper.createObjectNode().put("metadataHeaders", "Subject"));

            String result = service.processQueryParameters(url, tool, parameters);

            assertTrue(result.endsWith("?metadataHeaders=Subject"),
                "Single value should produce exactly one ?metadataHeaders=Subject pair, got: " + result);
        }

        /**
         * Bracket-suffix param contract - 17 params in the catalog use the
         * PHP-style {@code fields[]} / {@code records[]} naming (Airtable,
         * Acuity Scheduling, Brex). The {@code []} is part of the param NAME,
         * not a serialization style, so the URL must read
         * {@code ?fields%5B%5D=Name&fields%5B%5D=Status}. Asserts the bracket
         * stays in each repeated entry.
         */
        @Test
        @DisplayName("Bracket-suffix param name (fields[]) repeats correctly: ?fields[]=v1&fields[]=v2")
        void arrayParamWithBracketSuffixNameStaysBracketed() throws Exception {
            String url = "https://api.airtable.com/v0/appXYZ/tblABC";
            ApiToolEntity tool = createTestTool("/v0/{baseId}/{tableId}");

            ApiToolParameterEntity fieldsParam = new ApiToolParameterEntity();
            fieldsParam.setName("fields[]");
            fieldsParam.setParameterType("query");
            fieldsParam.setDataType("array");
            when(apiToolParameterRepository.findByApiToolId(tool.getId()))
                .thenReturn(List.of(fieldsParam));

            ArrayNode parameters = objectMapper.createArrayNode();
            parameters.add(objectMapper.createObjectNode().set("fields[]",
                objectMapper.createArrayNode().add("Name").add("Status")));

            String result = service.processQueryParameters(url, tool, parameters);

            // Brackets in the param NAME are emitted literally - the URLEncoder
            // call only encodes VALUES. Per RFC 3986 §3.4, '[' and ']' are
            // reserved-but-allowed in the query string, and Airtable / Acuity /
            // Brex expect the literal bracket form (?fields[]=…&fields[]=…).
            assertTrue(result.contains("fields[]=Name"),
                "Bracket-suffix kept on first entry: " + result);
            assertTrue(result.contains("fields[]=Status"),
                "Bracket-suffix kept on second entry: " + result);
            // Two distinct repeated occurrences, not collapsed to one.
            int firstIdx = result.indexOf("fields[]=");
            int secondIdx = result.indexOf("fields[]=", firstIdx + 1);
            assertTrue(firstIdx > 0 && secondIdx > firstIdx,
                "Two repeated entries expected, got: " + result);
        }

        /** Non-array params are untouched - guards against regression on the 99% of params. */
        @Test
        @DisplayName("Non-array params unchanged: dataType=string still single-value (no regression)")
        void nonArrayParamsUnchangedAfterFix() throws Exception {
            String url = "https://api.example.com/search";
            ApiToolEntity tool = createTestTool("/search");

            ApiToolParameterEntity qParam = new ApiToolParameterEntity();
            qParam.setName("q");
            qParam.setParameterType("query");
            qParam.setDataType("string");
            ApiToolParameterEntity limitParam = new ApiToolParameterEntity();
            limitParam.setName("limit");
            limitParam.setParameterType("query");
            limitParam.setDataType("integer");
            when(apiToolParameterRepository.findByApiToolId(tool.getId()))
                .thenReturn(List.of(qParam, limitParam));

            ArrayNode parameters = objectMapper.createArrayNode();
            // String containing a comma - must NOT be split on comma when type=string.
            parameters.add(objectMapper.createObjectNode().put("q", "pizza, fries"));
            parameters.add(objectMapper.createObjectNode().put("limit", "10"));

            String result = service.processQueryParameters(url, tool, parameters);

            // String param keeps the comma encoded as %2C - not split.
            assertTrue(result.contains("q=pizza%2C+fries") || result.contains("q=pizza%2C%20fries"),
                "String param with comma must NOT be split into repeated query - got: " + result);
            assertFalse(result.contains("q=pizza&q=fries"),
                "Regression: string param wrongly split into repeated. URL: " + result);
            assertTrue(result.contains("limit=10"));
        }

        // ====================================================================
        // Regression: empty {} objects in the parameter array (from null values
        // serialized by Jackson) caused NoSuchElementException on
        // param.fieldNames().next(). The fix guards all iteration sites.
        // ====================================================================

        @Test
        @DisplayName("Regression: empty {} object in parameters array is gracefully skipped (SerpAPI google_flights)")
        void emptyObjectInParametersArrayIsSkipped() throws Exception {
            String url = "http://serpapi.com/search";
            ApiToolEntity tool = createTestTool("/search");
            ArrayNode parameters = objectMapper.createArrayNode();
            // Valid params
            parameters.add(objectMapper.createObjectNode().put("engine", "google_flights"));
            parameters.add(objectMapper.createObjectNode().put("departure_id", "CDG"));
            // Empty object {} - this is what null values produced before the ToolExecutionManager fix
            parameters.add(objectMapper.createObjectNode());
            // Another valid param after the empty one
            parameters.add(objectMapper.createObjectNode().put("arrival_id", "DLA"));

            // Should NOT throw NoSuchElementException
            String result = service.processQueryParameters(url, tool, parameters);

            // Valid params should still be processed
            assertTrue(result.contains("engine=google_flights"));
            assertTrue(result.contains("departure_id=CDG"));
            assertTrue(result.contains("arrival_id=DLA"));
        }

        @Test
        @DisplayName("Regression: parameter with explicit null value node is gracefully skipped")
        void nullValueNodeInParametersArrayIsSkipped() throws Exception {
            String url = "http://api.example.com/search";
            ApiToolEntity tool = createTestTool("/search");
            ArrayNode parameters = objectMapper.createArrayNode();
            parameters.add(objectMapper.createObjectNode().put("q", "test"));
            // Param with explicit null value
            parameters.add(objectMapper.createObjectNode().putNull("stops"));
            parameters.add(objectMapper.createObjectNode().put("limit", "10"));

            String result = service.processQueryParameters(url, tool, parameters);

            assertTrue(result.contains("q=test"));
            assertTrue(result.contains("limit=10"));
            // null value should not appear
            assertFalse(result.contains("stops"));
        }
    }

    // ========================================================================
    // prepareHeaders tests
    // ========================================================================

    @Nested
    @DisplayName("prepareHeaders()")
    class PrepareHeadersTests {

        @Test
        @DisplayName("should add default headers")
        void shouldAddDefaultHeaders() {
            ApiEntity api = createTestApi("http://api.example.com");
            ApiToolEntity tool = createTestTool("/users");

            HttpHeaders headers = service.prepareHeaders(api, tool);

            assertEquals("application/json", headers.getFirst("Content-Type"));
            assertEquals("application/json", headers.getFirst("Accept"));
        }

        @Test
        @DisplayName("should add auth header when configured")
        void shouldAddAuthHeaderWhenConfigured() {
            ApiEntity api = createTestApi("http://api.example.com");
            api.setAuthType("api_key");
            api.setAuthHeaderName("X-API-Key");
            api.setAuthHeaderValue("secret-key");
            ApiToolEntity tool = createTestTool("/users");

            HttpHeaders headers = service.prepareHeaders(api, tool);

            assertEquals("secret-key", headers.getFirst("X-API-Key"));
        }

        @Test
        @DisplayName("should not add auth header when auth type is none")
        void shouldNotAddAuthHeaderWhenAuthTypeIsNone() {
            ApiEntity api = createTestApi("http://api.example.com");
            api.setAuthType("none");
            api.setAuthHeaderName("X-API-Key");
            api.setAuthHeaderValue("secret-key");
            ApiToolEntity tool = createTestTool("/users");

            HttpHeaders headers = service.prepareHeaders(api, tool);

            assertNull(headers.getFirst("X-API-Key"));
        }
    }

    // ========================================================================
    // prepareRequestBody tests
    // ========================================================================

    @Nested
    @DisplayName("prepareRequestBody()")
    class PrepareRequestBodyTests {

        @Test
        @DisplayName("should return null for GET method")
        void shouldReturnNullForGetMethod() throws Exception {
            ApiToolEntity tool = createTestTool("/users");
            tool.setMethod("GET");
            ArrayNode parameters = objectMapper.createArrayNode();
            parameters.add(objectMapper.createObjectNode().put("name", "John"));

            Object result = service.prepareRequestBody(tool, parameters);

            assertNull(result);
        }

        @Test
        @DisplayName("should return null for DELETE method")
        void shouldReturnNullForDeleteMethod() throws Exception {
            ApiToolEntity tool = createTestTool("/users/{id}");
            tool.setMethod("DELETE");
            ArrayNode parameters = objectMapper.createArrayNode();

            Object result = service.prepareRequestBody(tool, parameters);

            assertNull(result);
        }

        @Test
        @DisplayName("should return body for POST method")
        void shouldReturnBodyForPostMethod() throws Exception {
            ApiToolEntity tool = createTestTool("/users");
            tool.setMethod("POST");
            ArrayNode parameters = objectMapper.createArrayNode();
            parameters.add(objectMapper.createObjectNode().put("name", "John"));
            parameters.add(objectMapper.createObjectNode().put("email", "john@test.com"));

            Object result = service.prepareRequestBody(tool, parameters);

            assertNotNull(result);
            assertTrue(result instanceof Map);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) result;
            assertEquals(2, body.size());
        }

        @Test
        @DisplayName("should exclude path parameters from body")
        void shouldExcludePathParametersFromBody() throws Exception {
            ApiToolEntity tool = createTestTool("/users/{userId}");
            tool.setMethod("PUT");
            ArrayNode parameters = objectMapper.createArrayNode();
            parameters.add(objectMapper.createObjectNode().put("userId", "123"));
            parameters.add(objectMapper.createObjectNode().put("name", "John"));

            Object result = service.prepareRequestBody(tool, parameters);

            assertNotNull(result);
            assertTrue(result instanceof Map);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) result;
            assertFalse(body.containsKey("userId"));
            assertTrue(body.containsKey("name"));
        }

        @Test
        @DisplayName("inlineBody=true sends the param value AS the body - no {input:{...}} wrapping (regression: Apify run_actor)")
        void inlineBodyParamBecomesEntireBodyNoWrapping() throws Exception {
            ApiToolEntity tool = createTestTool("/acts/{actorId}/runs");
            tool.setMethod("POST");

            ApiToolParameterEntity inlineParam = new ApiToolParameterEntity();
            inlineParam.setName("input");
            inlineParam.setParameterType("body");
            inlineParam.setDataType("object");
            inlineParam.setExtras("{\"inlineBody\":true}");
            when(apiToolParameterRepository.findByApiToolId(tool.getId()))
                .thenReturn(List.of(inlineParam));

            ArrayNode parameters = objectMapper.createArrayNode();
            parameters.add(objectMapper.createObjectNode().set("input",
                objectMapper.createObjectNode()
                    .put("searchQuery", "pizza")
                    .put("maxItems", 50)));

            Object result = service.prepareRequestBody(tool, parameters);

            assertNotNull(result);
            assertTrue(result instanceof Map, "inlineBody must yield the value itself, not a Map wrapper around 'input'");
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) result;
            assertFalse(body.containsKey("input"),
                "Pre-fix bug: body had {\"input\":{...}} - Actor received an empty dataset because real fields were nested under 'input'");
            assertEquals("pizza", body.get("searchQuery"));
            assertEquals(50, body.get("maxItems"));
        }

        @Test
        @DisplayName("inlineBody=true with type=array sends the JSON array AS the body (regression: Apify push_items / batch_add_requests)")
        void inlineBodyArrayBecomesEntireJsonArrayBody() throws Exception {
            ApiToolEntity tool = createTestTool("/datasets/{datasetId}/items");
            tool.setMethod("POST");

            ApiToolParameterEntity inlineParam = new ApiToolParameterEntity();
            inlineParam.setName("items");
            inlineParam.setParameterType("body");
            inlineParam.setDataType("array");
            inlineParam.setExtras("{\"inlineBody\":true}");
            when(apiToolParameterRepository.findByApiToolId(tool.getId()))
                .thenReturn(List.of(inlineParam));

            ArrayNode itemsArray = objectMapper.createArrayNode();
            itemsArray.addObject().put("title", "first").put("score", 1);
            itemsArray.addObject().put("title", "second").put("score", 2);

            ArrayNode parameters = objectMapper.createArrayNode();
            parameters.add(objectMapper.createObjectNode().set("items", itemsArray));

            Object result = service.prepareRequestBody(tool, parameters);

            assertNotNull(result);
            assertTrue(result instanceof List,
                "Apify push_items expects a top-level JSON array as the body - wrapping it as {items:[...]} would break the API");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> body = (List<Map<String, Object>>) result;
            assertEquals(2, body.size());
            assertEquals("first", body.get(0).get("title"));
            assertEquals(2, body.get(1).get("score"));
        }

        @Test
        @DisplayName("inlineBody coerces stringified int (regression: Apify maxItems must be integer)")
        void inlineBodyCoercesStringifiedInt() throws Exception {
            ApiToolEntity tool = createTestTool("/acts/{actorId}/runs");
            tool.setMethod("POST");

            ApiToolParameterEntity inlineParam = new ApiToolParameterEntity();
            inlineParam.setName("input");
            inlineParam.setParameterType("body");
            inlineParam.setDataType("object");
            inlineParam.setExtras("{\"inlineBody\":true}");
            when(apiToolParameterRepository.findByApiToolId(tool.getId()))
                .thenReturn(List.of(inlineParam));

            ArrayNode parameters = objectMapper.createArrayNode();
            parameters.add(objectMapper.createObjectNode().set("input",
                objectMapper.createObjectNode()
                    .put("searchQuery", "CTO France")
                    .put("maxItems", "10")
                    .put("threshold", "0.85")
                    .put("debug", "true")
                    .put("zipCode", "01234")
                    .put("phone", "+33123456789")));

            Object result = service.prepareRequestBody(tool, parameters);

            assertTrue(result instanceof Map);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) result;
            assertEquals("CTO France", body.get("searchQuery"));
            assertEquals(10L, body.get("maxItems"),
                "Pre-fix bug: Apify rejected '10' as string - must coerce to Long");
            assertEquals(0.85, body.get("threshold"));
            assertEquals(Boolean.TRUE, body.get("debug"));
            assertEquals("01234", body.get("zipCode"),
                "Leading-zero strings must NOT be coerced (zip codes, IDs)");
            assertEquals("+33123456789", body.get("phone"),
                "'+'-prefixed strings must NOT be coerced (phone numbers)");
        }

        @Test
        @DisplayName("inlineBody coerces nested arrays and maps recursively")
        void inlineBodyCoercesRecursively() throws Exception {
            ApiToolEntity tool = createTestTool("/v1/");
            tool.setMethod("POST");

            ApiToolParameterEntity inlineParam = new ApiToolParameterEntity();
            inlineParam.setName("request_body");
            inlineParam.setParameterType("body");
            inlineParam.setDataType("object");
            inlineParam.setExtras("{\"inlineBody\":true}");
            when(apiToolParameterRepository.findByApiToolId(tool.getId()))
                .thenReturn(List.of(inlineParam));

            ArrayNode pages = objectMapper.createArrayNode();
            pages.add("1");
            pages.add("2");
            ArrayNode parameters = objectMapper.createArrayNode();
            parameters.add(objectMapper.createObjectNode().set("request_body",
                objectMapper.createObjectNode()
                    .put("retries", "3")
                    .<com.fasterxml.jackson.databind.node.ObjectNode>set("pages", pages)
                    .set("filters",
                        objectMapper.createObjectNode()
                            .put("limit", "20")
                            .put("sortAsc", "false"))));

            Object result = service.prepareRequestBody(tool, parameters);

            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) result;
            assertEquals(3L, body.get("retries"));
            assertEquals(List.of(1L, 2L), body.get("pages"));
            @SuppressWarnings("unchecked")
            Map<String, Object> filters = (Map<String, Object>) body.get("filters");
            assertEquals(20L, filters.get("limit"));
            assertEquals(Boolean.FALSE, filters.get("sortAsc"));
        }

        @Test
        @DisplayName("inlineBody=false (default) keeps the legacy wrapping behavior")
        void inlineBodyDefaultPreservesFieldNameWrapping() throws Exception {
            ApiToolEntity tool = createTestTool("/users");
            tool.setMethod("POST");

            ApiToolParameterEntity nameParam = new ApiToolParameterEntity();
            nameParam.setName("name");
            nameParam.setParameterType("body");
            nameParam.setDataType("string");
            when(apiToolParameterRepository.findByApiToolId(tool.getId()))
                .thenReturn(List.of(nameParam));

            ArrayNode parameters = objectMapper.createArrayNode();
            parameters.add(objectMapper.createObjectNode().put("name", "John"));

            Object result = service.prepareRequestBody(tool, parameters);

            assertTrue(result instanceof Map);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) result;
            assertEquals("John", body.get("name"));
        }
    }

    // ========================================================================
    // convertToExpectedType - declared-type scalar coercion
    // ========================================================================

    @Nested
    @DisplayName("convertToExpectedType() - declared-type scalar coercion")
    class ConvertToExpectedTypeScalarCoercion {

        @Test
        @DisplayName("dataType=integer + string '10' → Long(10) (regression: any body param declared integer)")
        void integerDataTypeCoercesNumericString() {
            assertEquals(10L, service.convertToExpectedType("10", "integer"));
            assertEquals(0L, service.convertToExpectedType("0", "integer"));
            assertEquals(-3L, service.convertToExpectedType("-3", "integer"));
            assertEquals(10L, service.convertToExpectedType("10", "int"));
            assertEquals(10L, service.convertToExpectedType("10", "long"));
            assertEquals(10L, service.convertToExpectedType("10", "INTEGER"),
                "case-insensitive - JSON dataType is sometimes upper-cased");
        }

        @Test
        @DisplayName("dataType=integer + ambiguous string → unchanged (preserves leading-zero IDs / phones / scientific)")
        void integerDataTypeLeavesAmbiguousStringsAlone() {
            assertEquals("01234", service.convertToExpectedType("01234", "integer"),
                "Leading-zero zip code must NOT be coerced");
            assertEquals("+33123", service.convertToExpectedType("+33123", "integer"),
                "'+'-prefixed phone fragment must NOT be coerced");
            assertEquals("1e3", service.convertToExpectedType("1e3", "integer"),
                "Scientific notation must NOT be coerced (too risky)");
            assertEquals("3.14", service.convertToExpectedType("3.14", "integer"),
                "Decimal must NOT match integer pattern");
            assertEquals("abc", service.convertToExpectedType("abc", "integer"));
        }

        @Test
        @DisplayName("dataType=number/double/float + decimal string → Double; integer string → Long")
        void numberDataTypeCoercesDecimalAndInt() {
            assertEquals(3.14, service.convertToExpectedType("3.14", "number"));
            assertEquals(0.5, service.convertToExpectedType("0.5", "number"));
            assertEquals(10L, service.convertToExpectedType("10", "number"),
                "Integer-shaped strings stay integer-typed (Jackson serializes Long → JSON int)");
            assertEquals(3.14, service.convertToExpectedType("3.14", "double"));
            assertEquals(3.14, service.convertToExpectedType("3.14", "float"));
        }

        @Test
        @DisplayName("dataType=boolean + 'true'/'false' → Boolean; other strings unchanged")
        void booleanDataTypeCoercesExactStrings() {
            assertEquals(Boolean.TRUE, service.convertToExpectedType("true", "boolean"));
            assertEquals(Boolean.FALSE, service.convertToExpectedType("false", "boolean"));
            assertEquals(Boolean.TRUE, service.convertToExpectedType("true", "bool"));
            assertEquals("True", service.convertToExpectedType("True", "boolean"),
                "Case-mixed must NOT coerce - exact lowercase only to match the JSON spec");
            assertEquals("yes", service.convertToExpectedType("yes", "boolean"));
            assertEquals("1", service.convertToExpectedType("1", "boolean"),
                "Numeric '1' must NOT coerce to boolean - booleans require literal true/false");
        }

        @Test
        @DisplayName("dataType=string or unknown → no coercion (preserves caller intent)")
        void stringDataTypePreservesValue() {
            assertEquals("10", service.convertToExpectedType("10", "string"));
            assertEquals("true", service.convertToExpectedType("true", "string"));
            assertEquals("10", service.convertToExpectedType("10", "object"),
                "Object dataType + non-JSON-object string passes through unchanged; JSON-object strings ARE parsed (see ConvertToExpectedTypeObjectCoercion)");
            assertEquals("10", service.convertToExpectedType("10", null),
                "Null dataType (legacy fallback): preserve as-is");
            assertEquals("10", service.convertToExpectedType("10", "unknown_type"));
        }

        @Test
        @DisplayName("Already-typed values pass through unchanged regardless of declared dataType")
        void alreadyTypedValuesPassThrough() {
            assertEquals(42, service.convertToExpectedType(42, "integer"));
            assertEquals(3.14, service.convertToExpectedType(3.14, "number"));
            assertEquals(Boolean.TRUE, service.convertToExpectedType(Boolean.TRUE, "boolean"));
        }

        @Test
        @DisplayName("Numeric overflow falls back to string (NumberFormatException defensive guard)")
        void overflowFallsBackToString() {
            String tooBig = "99999999999999999999";
            assertEquals(tooBig, service.convertToExpectedType(tooBig, "integer"),
                "20-digit number overflows Long.MAX_VALUE - must NOT throw, must fall back to original string");
            assertEquals(tooBig, service.convertToExpectedType(tooBig, "number"));
        }

        @Test
        @DisplayName("null value short-circuits without NPE regardless of dataType")
        void nullValueShortCircuits() {
            assertNull(service.convertToExpectedType(null, "integer"));
            assertNull(service.convertToExpectedType(null, "number"));
            assertNull(service.convertToExpectedType(null, "boolean"));
            assertNull(service.convertToExpectedType(null, null));
        }

        @Test
        @DisplayName("Empty string is preserved (no coerce attempt)")
        void emptyStringPreserved() {
            assertEquals("", service.convertToExpectedType("", "integer"));
            assertEquals("", service.convertToExpectedType("", "number"));
            assertEquals("", service.convertToExpectedType("", "boolean"));
        }

        @Test
        @DisplayName("dataType=integer rejects '+10' leading plus (pin: STRICT_INT_RE intentionally bans + prefix)")
        void integerDataTypeRejectsLeadingPlus() {
            assertEquals("+10", service.convertToExpectedType("+10", "integer"),
                "Leading + must NOT be coerced - phone fragments and intl codes look like '+33...'");
        }

        @Test
        @DisplayName("dataType=integer rejects '1_000' Java-style underscore (pin: regex must match strict JSON ints)")
        void integerDataTypeRejectsUnderscoreSeparators() {
            assertEquals("1_000", service.convertToExpectedType("1_000", "integer"),
                "Java's parseLong rejects underscores in str form; STRICT_INT_RE must too - no future leniency creep");
        }

        @Test
        @DisplayName("dataType=integer + '-0' coerces to Long(0) (pin: harmless edge of STRICT_INT_RE)")
        void integerDataTypeNegativeZeroCoercesToZero() {
            assertEquals(0L, service.convertToExpectedType("-0", "integer"),
                "STRICT_INT_RE matches '-0' and Long.parseLong('-0')=0L; documented edge, not a bug");
        }

        @Test
        @DisplayName("Turkish-locale guard: dataType lowercase uses Locale.ROOT (regression: 'INTEGER' must coerce in tr_TR)")
        void turkishLocaleDoesNotBreakDataTypeMatching() {
            // Pre-fix: dataType.toLowerCase() under tr_TR → "ınteger" (dotless ı),
            // which would NOT match the "integer" switch arm → silent no-op coercion.
            // Post-fix: toLowerCase(Locale.ROOT) → "integer" regardless of JVM locale.
            // We can't change JVM locale here without side-effects, so this test
            // exercises the case-insensitive path explicitly (covered by the
            // integerDataTypeCoercesNumericString test for "INTEGER"); the regression
            // contract is that Locale.ROOT is used - verified by source review.
            assertEquals(10L, service.convertToExpectedType("10", "INTEGER"));
            assertEquals(10L, service.convertToExpectedType("10", "Integer"));
            assertEquals(10L, service.convertToExpectedType("10", "INT"));
            assertEquals(Boolean.TRUE, service.convertToExpectedType("true", "BOOLEAN"));
            assertEquals(Boolean.TRUE, service.convertToExpectedType("true", "BOOL"));
            assertEquals(3.14, service.convertToExpectedType("3.14", "NUMBER"));
        }
    }

    // ========================================================================
    // convertToExpectedType - object coercion (json-body parity with arrays)
    //
    // Regression: youtube update_video has snippet (dataType=object) in a
    // json-body endpoint. Workflows pass it as a JSON string ("{\"title\":...}").
    // Pre-fix, convertToExpectedType had an `array` branch but no `object` branch,
    // so the string passed through and the body became {"snippet":"{...}"} (string)
    // → YouTube 400 "Invalid value at 'resource.snippet'". The multipart_related
    // encoder already parsed it (insert_video worked); this brings the plain
    // json-body path to parity. Affects ALL object-typed body params, not just YouTube.
    // ========================================================================

    @Nested
    @DisplayName("convertToExpectedType() - object coercion (json-body parity with arrays)")
    class ConvertToExpectedTypeObjectCoercion {

        @Test
        @DisplayName("dataType=object + JSON-object string → parsed Map (regression: update_video snippet was sent as a raw string)")
        void objectStringIsParsedToMap() {
            Object out = service.convertToExpectedType(
                "{\"title\":\"LiveContext OAuth demo (updated)\",\"categoryId\":\"22\"}", "object");
            assertInstanceOf(Map.class, out, "JSON-object string must coerce to a Map, not stay a String");
            @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) out;
            assertEquals("LiveContext OAuth demo (updated)", m.get("title"));
            assertEquals("22", m.get("categoryId"));
        }

        @Test
        @DisplayName("dataType=object + already-a-Map → returned unchanged")
        void mapPassesThroughUnchanged() {
            Map<String, Object> in = Map.of("title", "x");
            assertSame(in, service.convertToExpectedType(in, "object"));
        }

        @Test
        @DisplayName("dataType=object + non-object string → unchanged, fails safe (array string and scalar are NOT objects)")
        void nonObjectStringPassesThrough() {
            assertEquals("hello", service.convertToExpectedType("hello", "object"));
            assertEquals("10", service.convertToExpectedType("10", "object"));
            assertEquals("[1,2]", service.convertToExpectedType("[1,2]", "object"),
                "A JSON array string is not an object - must pass through unchanged");
        }

        @Test
        @DisplayName("dataType=object + malformed {...} string → unchanged, never throws")
        void malformedObjectStringPassesThrough() {
            String malformed = "{not valid json}";
            assertEquals(malformed, service.convertToExpectedType(malformed, "object"),
                "Unparseable {...} must fall back to the original string, never throw");
        }

        @Test
        @DisplayName("OBJECT (uppercase) coerces too - Locale.ROOT case-insensitive")
        void uppercaseObjectDataTypeCoerces() {
            assertInstanceOf(Map.class, service.convertToExpectedType("{\"k\":\"v\"}", "OBJECT"));
        }
    }

    // ========================================================================
    // convertToExpectedType - array coercion
    //
    // Sister fix to the query-array repeated-query change (commit 453575ad8).
    // Pre-fix this helper wrapped CSV strings in a single-element list - the
    // form-urlencoded body encoder then emitted ?key=Subject%2CFrom (single
    // CSV) and JSON body emitted {"key":["Subject,From"]} (single-element).
    // Both rejected/misinterpreted by strict APIs. 2767 body+array params
    // catalog-wide were silently mis-shaped this way; 0 form-urlencoded tools
    // shipped today so the bug stayed hidden, but JSON body bodies were
    // affected whenever a caller authored a CSV string instead of a real
    // JSON array (templating / SpEL output / legacy plans).
    // ========================================================================

    @Nested
    @DisplayName("convertToExpectedType() - dataType=array")
    class ConvertToExpectedTypeArrayCoercion {

        /** Modern JSON array input is preserved unchanged (no regression on the 99% path). */
        @Test
        @DisplayName("dataType=array + List input → returned as-is")
        void arrayDataTypeKeepsListInputAsIs() {
            List<String> input = List.of("a", "b", "c");
            assertEquals(input, service.convertToExpectedType(input, "array"));
        }

        /** JSON-array string `[a,b]` is parsed into a list. Pre-existing behavior. */
        @Test
        @DisplayName("dataType=array + JSON-array string '[\"a\",\"b\"]' → parsed list")
        void arrayDataTypeParsesJsonArrayString() {
            Object result = service.convertToExpectedType("[\"a\",\"b\"]", "array");
            assertTrue(result instanceof List, "Expected List, got: " + result);
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) result;
            assertEquals(List.of("a", "b"), list);
        }

        /** Empty JSON array string `[]` returns an empty list. */
        @Test
        @DisplayName("dataType=array + '[]' → empty list")
        void arrayDataTypeEmptyJsonArray() {
            Object result = service.convertToExpectedType("[]", "array");
            assertEquals(List.of(), result);
        }

        /**
         * Repro of the body-side sister bug: legacy plans pass `"a,b,c"` (CSV
         * string) instead of `["a","b","c"]`. Pre-fix: List.of("a,b,c") (single
         * literal). Post-fix: ["a","b","c"] (split on commas).
         */
        @Test
        @DisplayName("Regression: dataType=array + CSV string 'Subject,From' → [\"Subject\",\"From\"] (legacy plan compat)")
        void arrayDataTypeCsvStringSplits() {
            Object result = service.convertToExpectedType("Subject,From", "array");
            assertTrue(result instanceof List, "Expected List, got: " + result);
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) result;
            assertEquals(2, list.size(),
                "Pre-fix bug: returned List.of(\"Subject,From\") (size=1). Post-fix splits on commas. Got: " + list);
            assertEquals("Subject", list.get(0));
            assertEquals("From", list.get(1));
        }

        /** Whitespace around commas is trimmed. */
        @Test
        @DisplayName("dataType=array + ' a , b ,  c ' → [\"a\",\"b\",\"c\"] (trimmed)")
        void arrayDataTypeCsvStringTrimsWhitespace() {
            Object result = service.convertToExpectedType(" a , b ,  c ", "array");
            assertEquals(List.of("a", "b", "c"), result);
        }

        /** Single value (no comma) still produces a one-element list. */
        @Test
        @DisplayName("dataType=array + single 'Subject' → [\"Subject\"] (no comma → no split)")
        void arrayDataTypeSingleValueWrapsAsOneElement() {
            Object result = service.convertToExpectedType("Subject", "array");
            assertEquals(List.of("Subject"), result);
        }

        /** Trailing comma produces only the non-empty element. */
        @Test
        @DisplayName("dataType=array + 'a,' → [\"a\"] (trailing-comma blanks dropped)")
        void arrayDataTypeCsvStringDropsBlanks() {
            Object result = service.convertToExpectedType("a,", "array");
            assertEquals(List.of("a"), result);
        }

        /** dataType=string with comma is NOT split - ensures the split is array-scoped. */
        @Test
        @DisplayName("dataType=string + 'a,b' → 'a,b' unchanged (split is array-only, no regression on strings)")
        void stringDataTypeCommaUnchanged() {
            assertEquals("a,b", service.convertToExpectedType("a,b", "string"));
        }

        /**
         * All-blank CSV input (e.g. just "," or "  ,  ,  ") trims to an empty
         * list. The CSV branch must NOT return that empty list - it falls
         * through to the single-value wrap so the original literal is
         * preserved (a 1-element list with the raw string).
         */
        @Test
        @DisplayName("dataType=array + all-blank CSV like ',' → falls through to List.of(',') (safety valve)")
        void arrayDataTypeAllBlankCsvFallsThrough() {
            // "," → split yields ["", ""] → both blank → list stays empty →
            // safety valve `if (!list.isEmpty()) return list;` declines →
            // final return wraps the original string as 1-element list.
            assertEquals(List.of(","), service.convertToExpectedType(",", "array"));
            assertEquals(List.of(" , , "), service.convertToExpectedType(" , , ", "array"));
        }
    }

    @Nested
    @DisplayName("coerceInlineBodyScalars() - defensive edge cases")
    class CoerceInlineBodyScalarsEdgeCases {

        @Test
        @DisplayName("null value returns null (no NPE)")
        void nullReturnsNull() {
            assertNull(service.coerceInlineBodyScalars(null));
        }

        @Test
        @DisplayName("Empty map returns empty map (does not blow up the iteration)")
        void emptyMapStaysEmpty() {
            Map<String, Object> empty = new HashMap<>();
            Object out = service.coerceInlineBodyScalars(empty);
            assertTrue(out instanceof Map);
            assertTrue(((Map<?, ?>) out).isEmpty());
        }

        @Test
        @DisplayName("Empty list returns empty list")
        void emptyListStaysEmpty() {
            Object out = service.coerceInlineBodyScalars(List.of());
            assertTrue(out instanceof List);
            assertTrue(((List<?>) out).isEmpty());
        }

        @Test
        @DisplayName("Null nested values are preserved (Apify accepts JSON null)")
        void nullNestedValuesPreserved() {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("retries", "3");
            input.put("optional", null);
            input.put("flag", "true");
            @SuppressWarnings("unchecked")
            Map<String, Object> out = (Map<String, Object>) service.coerceInlineBodyScalars(input);
            assertEquals(3L, out.get("retries"));
            assertNull(out.get("optional"));
            assertEquals(Boolean.TRUE, out.get("flag"));
        }

        @Test
        @DisplayName("Numeric overflow inside nested map falls back to string (no exception)")
        void nestedNumericOverflowFallsBack() {
            Map<String, Object> input = Map.of(
                "small", "10",
                "huge", "99999999999999999999"
            );
            @SuppressWarnings("unchecked")
            Map<String, Object> out = (Map<String, Object>) service.coerceInlineBodyScalars(input);
            assertEquals(10L, out.get("small"));
            assertEquals("99999999999999999999", out.get("huge"));
        }

        @Test
        @DisplayName("Already-typed scalars (Integer, Long, Double, Boolean) inside map pass through unchanged")
        void typedScalarsPassThrough() {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("intVal", 42);
            input.put("longVal", 1234567890123L);
            input.put("doubleVal", 2.71);
            input.put("boolVal", Boolean.TRUE);
            input.put("stringVal", "hello");
            @SuppressWarnings("unchecked")
            Map<String, Object> out = (Map<String, Object>) service.coerceInlineBodyScalars(input);
            assertEquals(42, out.get("intVal"));
            assertEquals(1234567890123L, out.get("longVal"));
            assertEquals(2.71, out.get("doubleVal"));
            assertEquals(Boolean.TRUE, out.get("boolVal"));
            assertEquals("hello", out.get("stringVal"));
        }

        @Test
        @DisplayName("Original input map is NOT mutated (returns a new map)")
        void doesNotMutateInput() {
            Map<String, Object> original = new LinkedHashMap<>();
            original.put("count", "5");
            Object out = service.coerceInlineBodyScalars(original);
            assertNotSame(original, out, "Coercion must return a new container, not edit-in-place");
            assertEquals("5", original.get("count"), "Caller's map must remain string-typed");
        }
    }

    // ========================================================================
    // filterParametersByToolDefinition tests
    // ========================================================================

    @Nested
    @DisplayName("filterParametersByToolDefinition()")
    class FilterParametersByToolDefinitionTests {

        @Test
        @DisplayName("should filter out undefined parameters")
        void shouldFilterOutUndefinedParameters() throws Exception {
            ApiToolEntity tool = createTestTool("/users");
            ArrayNode parameters = objectMapper.createArrayNode();
            parameters.add(objectMapper.createObjectNode().put("name", "John"));
            parameters.add(objectMapper.createObjectNode().put("unknown", "value"));

            Set<String> allowed = Set.of("name", "email");
            JsonNode result = service.filterParametersByToolDefinition(tool, parameters, allowed);

            assertTrue(result.isArray());
            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("Drops all provided fields when the tool declares zero parameters")
        void dropsAllFieldsWhenToolDeclaresZeroParameters() throws Exception {
            // A zero-declared-param tool (e.g. Search Console list_sites, params: [])
            // must send NO params. Forwarding provided fields used to leak orchestrator
            // context into the request - see triggerContextNotForwardedAsParamForZeroParamGetTool.
            ApiToolEntity tool = createTestTool("/users");
            ArrayNode parameters = objectMapper.createArrayNode();
            parameters.add(objectMapper.createObjectNode().put("name", "John"));
            parameters.add(objectMapper.createObjectNode().put("email", "john@test.com"));

            Set<String> allowed = Set.of();
            JsonNode result = service.filterParametersByToolDefinition(tool, parameters, allowed);

            assertTrue(result.isArray());
            assertEquals(0, result.size());
        }

        @Test
        @DisplayName("Trigger context is not forwarded as a query param for a zero-param GET tool")
        void triggerContextNotForwardedAsParamForZeroParamGetTool() throws Exception {
            // Regression: prod run run_<id> - Search Console
            // list_sites (GET, zero declared params) was fed the manual-trigger blob
            // {"trigger": {...}}. The empty-allowedParamNames branch forwarded it, and
            // processQueryParameters() turned it into ?trigger=... → Google 400
            // "Unknown name 'trigger': Cannot bind query parameter." After the fix the
            // filtered param set is empty, so the query string stays clean.
            ApiToolEntity tool = createTestTool("/sites"); // createTestTool defaults method to GET
            ArrayNode parameters = objectMapper.createArrayNode();
            var triggerWrapper = objectMapper.createObjectNode();
            var triggerPayload = objectMapper.createObjectNode();
            triggerPayload.put("triggered_by", "livecontext");
            triggerPayload.put("triggered_at", "2026-05-28T19:35:33Z");
            triggerWrapper.set("trigger", triggerPayload);
            parameters.add(triggerWrapper);

            JsonNode filtered = service.filterParametersByToolDefinition(tool, parameters, Set.of());
            String url = service.processQueryParameters("https://www.googleapis.com/webmasters/v3/sites", tool, filtered);

            assertEquals(0, filtered.size());
            assertEquals("https://www.googleapis.com/webmasters/v3/sites", url);
            assertFalse(url.contains("trigger"));
        }

        @Test
        @DisplayName("should return original when not array")
        void shouldReturnOriginalWhenNotArray() {
            ApiToolEntity tool = createTestTool("/users");
            JsonNode parameters = objectMapper.createObjectNode().put("name", "John");

            JsonNode result = service.filterParametersByToolDefinition(tool, parameters, Set.of("name"));

            assertEquals(parameters, result);
        }

        @Test
        @DisplayName("Object-form trigger blob still yields a clean URL through the query builder")
        void objectFormTriggerBlobYieldsCleanUrl() {
            // The early return (parameters not an array → returned as-is) is safe only because
            // processQueryParameters / prepareRequestBody both skip non-array input. Pin that
            // contract: an object-form {"trigger":{...}} must NOT leak into the query string,
            // so a future refactor of the early return can't silently reopen the leak.
            ApiToolEntity tool = createTestTool("/sites"); // defaults method to GET
            var triggerWrapper = objectMapper.createObjectNode();
            triggerWrapper.set("trigger", objectMapper.createObjectNode().put("triggered_by", "livecontext"));

            JsonNode filtered = service.filterParametersByToolDefinition(tool, triggerWrapper, Set.of());
            String url = service.processQueryParameters("https://www.googleapis.com/webmasters/v3/sites", tool, filtered);

            assertEquals("https://www.googleapis.com/webmasters/v3/sites", url);
            assertFalse(url.contains("trigger"));
        }
    }

    // ========================================================================
    // buildRfc2822Body tests
    // ========================================================================

    @Nested
    @DisplayName("buildRfc2822Body()")
    class BuildRfc2822BodyTests {

        @Test
        @DisplayName("should build RFC 2822 message body")
        void shouldBuildRfc2822MessageBody() {
            Map<String, Object> params = new HashMap<>();
            params.put("to", "recipient@test.com");
            params.put("subject", "Test Subject");
            params.put("body", "Test body content");

            Map<String, String> result = service.buildRfc2822Body(params);

            assertNotNull(result);
            assertTrue(result.containsKey("raw"));
            assertFalse(result.get("raw").isEmpty());
        }

        @Test
        @DisplayName("should include CC and BCC when provided")
        void shouldIncludeCcAndBccWhenProvided() {
            Map<String, Object> params = new HashMap<>();
            params.put("to", "recipient@test.com");
            params.put("cc", "cc@test.com");
            params.put("bcc", "bcc@test.com");
            params.put("subject", "Test");
            params.put("body", "Content");

            Map<String, String> result = service.buildRfc2822Body(params);

            // The raw field should be Base64 encoded, so we can't easily verify headers
            assertNotNull(result.get("raw"));
        }

        @Test
        @DisplayName("should handle HTML content type")
        void shouldHandleHtmlContentType() {
            Map<String, Object> params = new HashMap<>();
            params.put("to", "recipient@test.com");
            params.put("subject", "Test");
            params.put("body", "<h1>Hello</h1>");
            params.put("isHtml", "true");

            Map<String, String> result = service.buildRfc2822Body(params);

            assertNotNull(result.get("raw"));
        }
    }

    // ========================================================================
    // buildRfc2822DraftBody tests
    // ========================================================================

    @Nested
    @DisplayName("buildRfc2822DraftBody()")
    class BuildRfc2822DraftBodyTests {

        @Test
        @DisplayName("should wrap message in draft structure")
        void shouldWrapMessageInDraftStructure() {
            Map<String, Object> params = new HashMap<>();
            params.put("to", "recipient@test.com");
            params.put("subject", "Draft Subject");
            params.put("body", "Draft content");

            Map<String, Object> result = service.buildRfc2822DraftBody(params);

            assertTrue(result.containsKey("message"));
            @SuppressWarnings("unchecked")
            Map<String, String> message = (Map<String, String>) result.get("message");
            assertTrue(message.containsKey("raw"));
        }
    }

    // ========================================================================
    // getBodyTransformType tests
    // ========================================================================

    @Nested
    @DisplayName("getBodyTransformType()")
    class GetBodyTransformTypeTests {

        @Test
        @DisplayName("should return transform type from runtime metadata")
        void shouldReturnTransformTypeFromRuntimeMetadata() {
            ApiToolEntity tool = createTestTool("/send");
            tool.setRuntimeMetadata("{\"bodyTransform\": \"rfc2822\"}");

            String result = service.getBodyTransformType(tool);

            assertEquals("rfc2822", result);
        }

        @Test
        @DisplayName("should return null when no metadata")
        void shouldReturnNullWhenNoMetadata() {
            ApiToolEntity tool = createTestTool("/send");
            tool.setRuntimeMetadata(null);

            String result = service.getBodyTransformType(tool);

            assertNull(result);
        }

        @Test
        @DisplayName("should return null when metadata has no transform")
        void shouldReturnNullWhenMetadataHasNoTransform() {
            ApiToolEntity tool = createTestTool("/send");
            tool.setRuntimeMetadata("{\"other\": \"value\"}");

            String result = service.getBodyTransformType(tool);

            assertNull(result);
        }

        @Test
        @DisplayName("should return null for blank metadata")
        void shouldReturnNullForBlankMetadata() {
            ApiToolEntity tool = createTestTool("/send");
            tool.setRuntimeMetadata("   ");

            String result = service.getBodyTransformType(tool);

            assertNull(result);
        }
    }

    // ========================================================================
    // applyBodyTransform tests
    // ========================================================================

    @Nested
    @DisplayName("applyBodyTransform()")
    class ApplyBodyTransformTests {

        @Test
        @DisplayName("should apply rfc2822 transform")
        void shouldApplyRfc2822Transform() {
            Map<String, Object> body = new HashMap<>();
            body.put("to", "test@test.com");
            body.put("subject", "Test");
            body.put("body", "Content");

            Object result = service.applyBodyTransform(body, "rfc2822");

            assertTrue(result instanceof Map);
            @SuppressWarnings("unchecked")
            Map<String, String> map = (Map<String, String>) result;
            assertTrue(map.containsKey("raw"));
        }

        @Test
        @DisplayName("should apply rfc2822_draft transform")
        void shouldApplyRfc2822DraftTransform() {
            Map<String, Object> body = new HashMap<>();
            body.put("to", "test@test.com");
            body.put("subject", "Test");
            body.put("body", "Content");

            Object result = service.applyBodyTransform(body, "rfc2822_draft");

            assertTrue(result instanceof Map);
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) result;
            assertTrue(map.containsKey("message"));
        }

        @Test
        @DisplayName("should return original body for unknown transform")
        void shouldReturnOriginalBodyForUnknownTransform() {
            Map<String, Object> body = new HashMap<>();
            body.put("key", "value");

            Object result = service.applyBodyTransform(body, "unknown");

            assertEquals(body, result);
        }
    }

    // ========================================================================
    // CredentialInjection tests
    // ========================================================================

    @Nested
    @DisplayName("CredentialInjection record")
    class CredentialInjectionTests {

        @Test
        @DisplayName("should create credential injection")
        void shouldCreateCredentialInjection() {
            HttpExecutionService.CredentialInjection injection =
                new HttpExecutionService.CredentialInjection("header", "Authorization", "api_key");

            assertEquals("header", injection.type());
            assertEquals("Authorization", injection.key());
            assertEquals("api_key", injection.field());
        }
    }

    // ========================================================================
    // V103 variant-aware injection lookup
    // ========================================================================

    @Nested
    @DisplayName("getCredentialInjection(toolId, variant)")
    class VariantAwareInjectionTests {

        @Test
        @DisplayName("Picks the tool_credentials row whose metadata.variant matches the requested variant")
        void picksMatchingVariantRow() {
            UUID toolId = UUID.randomUUID();
            Map<String, Object> row = new HashMap<>();
            row.put("metadata",
                    "{\"variant\":\"oauth2\",\"field\":\"access_token\"," +
                    "\"injection\":{\"type\":\"header\",\"key\":\"Authorization\"}}");

            // Variant-filtered query returns the OAuth2 row.
            when(jdbcTemplate.queryForList(
                    argThat((String sql) -> sql != null && sql.contains("metadata->>'variant'")),
                    eq(toolId), eq("oauth2")))
                    .thenReturn(List.of(row));

            HttpExecutionService.CredentialInjection injection =
                    service.getCredentialInjection(toolId, "oauth2");

            assertNotNull(injection);
            assertEquals("header", injection.type());
            assertEquals("Authorization", injection.key());
            assertEquals("access_token", injection.field());
            // Fallback LIMIT 1 query must NOT run when the variant query found a row.
            verify(jdbcTemplate, never()).queryForList(
                    argThat((String sql) -> sql != null && !sql.contains("metadata->>'variant'")),
                    eq(toolId));
        }

        @Test
        @DisplayName("Falls back to unfiltered LIMIT 1 when no row matches the requested variant")
        void fallsBackWhenVariantMissed() {
            UUID toolId = UUID.randomUUID();
            Map<String, Object> fallback = new HashMap<>();
            fallback.put("metadata",
                    "{\"variant\":\"primary\",\"field\":\"api_key\"," +
                    "\"injection\":{\"type\":\"header\",\"key\":\"X-API-Key\"}}");

            when(jdbcTemplate.queryForList(
                    argThat((String sql) -> sql != null && sql.contains("metadata->>'variant'")),
                    eq(toolId), eq("oauth2")))
                    .thenReturn(List.of());
            when(jdbcTemplate.queryForList(
                    argThat((String sql) -> sql != null && !sql.contains("metadata->>'variant'")),
                    eq(toolId)))
                    .thenReturn(List.of(fallback));

            HttpExecutionService.CredentialInjection injection =
                    service.getCredentialInjection(toolId, "oauth2");

            assertNotNull(injection);
            assertEquals("X-API-Key", injection.key());
            assertEquals("api_key", injection.field());
        }

        @Test
        @DisplayName("Null variant skips variant filter and uses LIMIT 1 directly - legacy single-variant APIs stay unaffected")
        void nullVariantUsesLegacyPath() {
            UUID toolId = UUID.randomUUID();
            Map<String, Object> row = new HashMap<>();
            row.put("metadata",
                    "{\"variant\":\"primary\",\"field\":\"api_key\"," +
                    "\"injection\":{\"type\":\"header\",\"key\":\"X-API-Key\"}}");

            when(jdbcTemplate.queryForList(
                    argThat((String sql) -> sql != null && !sql.contains("metadata->>'variant'")),
                    eq(toolId)))
                    .thenReturn(List.of(row));

            HttpExecutionService.CredentialInjection injection =
                    service.getCredentialInjection(toolId, null);

            assertNotNull(injection);
            assertEquals("X-API-Key", injection.key());
            // No variant-filtered SQL should have run.
            verify(jdbcTemplate, never()).queryForList(
                    argThat((String sql) -> sql != null && sql.contains("metadata->>'variant'")),
                    any(UUID.class), any(String.class));
        }

        @Test
        @DisplayName("Returns null when neither variant match nor fallback find a row")
        void returnsNullWhenNoRows() {
            UUID toolId = UUID.randomUUID();
            when(jdbcTemplate.queryForList(anyString(), eq(toolId), eq("oauth2")))
                    .thenReturn(List.of());
            when(jdbcTemplate.queryForList(anyString(), eq(toolId)))
                    .thenReturn(List.of());

            assertNull(service.getCredentialInjection(toolId, "oauth2"));
        }

        @Test
        @DisplayName("Reads prefix from injection.prefix when the importer has populated the canonical field")
        void readsPrefixFromCanonicalField() {
            UUID toolId = UUID.randomUUID();
            Map<String, Object> row = new HashMap<>();
            row.put("metadata",
                    "{\"variant\":\"bearer_token\",\"field\":\"api_token\"," +
                    "\"injection\":{\"type\":\"header\",\"key\":\"Authorization\",\"prefix\":\"Bearer \"}}");
            when(jdbcTemplate.queryForList(
                    argThat((String sql) -> sql != null && sql.contains("metadata->>'variant'")),
                    eq(toolId), eq("bearer_token")))
                    .thenReturn(List.of(row));

            HttpExecutionService.CredentialInjection injection =
                    service.getCredentialInjection(toolId, "bearer_token");

            assertNotNull(injection);
            assertEquals("Bearer ", injection.prefix());
        }

        @Test
        @DisplayName("Falls back to metadata.fakeAuth.apiKeyConfig.prefix for legacy rows that pre-date the canonical field")
        void readsPrefixFromFakeAuthFallback() {
            // 40% of current prod tool_credentials rows carry the prefix only here,
            // not under injection.prefix. Importer hasn't been updated yet - runtime
            // reads both so the fix lands without re-importing.
            UUID toolId = UUID.randomUUID();
            Map<String, Object> row = new HashMap<>();
            row.put("metadata",
                    "{\"variant\":\"bearer_token\",\"field\":\"api_token\"," +
                    "\"fakeAuth\":{\"apiKeyConfig\":{\"prefix\":\"Bearer \"}}," +
                    "\"injection\":{\"type\":\"header\",\"key\":\"Authorization\"}}");
            when(jdbcTemplate.queryForList(
                    argThat((String sql) -> sql != null && sql.contains("metadata->>'variant'")),
                    eq(toolId), eq("bearer_token")))
                    .thenReturn(List.of(row));

            HttpExecutionService.CredentialInjection injection =
                    service.getCredentialInjection(toolId, "bearer_token");

            assertNotNull(injection);
            assertEquals("Bearer ", injection.prefix());
        }

        @Test
        @DisplayName("Prefix stays null when neither field is populated (e.g. X-API-Key style raw injection)")
        void prefixNullWhenAbsent() {
            UUID toolId = UUID.randomUUID();
            Map<String, Object> row = new HashMap<>();
            row.put("metadata",
                    "{\"variant\":\"api_key\",\"field\":\"api_key\"," +
                    "\"injection\":{\"type\":\"header\",\"key\":\"X-API-Key\"}}");
            when(jdbcTemplate.queryForList(
                    argThat((String sql) -> sql != null && !sql.contains("metadata->>'variant'")),
                    eq(toolId)))
                    .thenReturn(List.of(row));

            HttpExecutionService.CredentialInjection injection =
                    service.getCredentialInjection(toolId, null);

            assertNotNull(injection);
            assertNull(injection.prefix());
        }
    }

    @Nested
    @DisplayName("resolveCredentialVariant()")
    class ResolveCredentialVariantTests {

        @Test
        @DisplayName("Reads the auth type off the user credential and lowercases it to a V103 variant identifier")
        void readsUserCredentialTypeForUserKey() {
            ApiEntity api = createTestApi("http://api.example.com");

            com.apimarketplace.credential.client.dto.AccessTokenResult result =
                    new com.apimarketplace.credential.client.dto.AccessTokenResult("tok", true, "OAuth2");
            when(userCredentialService.getAccessTokenInfo("user1", "my-cred"))
                    .thenReturn(Optional.of(result));

            assertEquals("oauth2", service.resolveCredentialVariant("user1", "my-cred", api));
        }

        @Test
        @DisplayName("API_Key normalizes to api_key")
        void apiKeyNormalizes() {
            ApiEntity api = createTestApi("http://api.example.com");

            com.apimarketplace.credential.client.dto.AccessTokenResult result =
                    new com.apimarketplace.credential.client.dto.AccessTokenResult("k", true, "API_Key");
            when(userCredentialService.getAccessTokenInfo("user1", "my-cred"))
                    .thenReturn(Optional.of(result));

            assertEquals("api_key", service.resolveCredentialVariant("user1", "my-cred", api));
        }

        @Test
        @DisplayName("workflow-selected credential id drives variant resolution instead of default credential lookup")
        void selectedCredentialIdDrivesVariantResolution() {
            ApiEntity api = createTestApi("http://api.example.com");

            com.apimarketplace.credential.client.dto.AccessTokenResult result =
                    new com.apimarketplace.credential.client.dto.AccessTokenResult("k", true, "API_Key");
            try {
                CredentialModeContext.setExplicitSource("user");
                CredentialModeContext.setSelectedCredentialId(42L);
                when(userCredentialService.getAccessTokenInfoById("user1", 42L))
                        .thenReturn(Optional.of(result));

                assertEquals("api_key", service.resolveCredentialVariant("user1", "my-cred", api));
                verify(userCredentialService, never()).getAccessTokenInfo("user1", "my-cred");
            } finally {
                CredentialModeContext.clear();
            }
        }

        @Test
        @DisplayName("explicitSource='platform' returns null so admin config drives variant selection (no user lookup)")
        void platformKeyReturnsNull() {
            ApiEntity api = createTestApi("http://api.example.com");
            try {
                CredentialModeContext.setExplicitSource("platform");
                assertNull(service.resolveCredentialVariant("user1", "my-cred", api));
                // Must NOT call getAccessTokenInfo - workflow toggle=platform skips the user lookup entirely.
                verifyNoInteractions(userCredentialService);
            } finally {
                CredentialModeContext.clear();
            }
        }

        @Test
        @DisplayName("Returns null when the user has no credential yet so fallback LIMIT 1 still picks the sole row")
        void nullWhenNoCredential() {
            ApiEntity api = createTestApi("http://api.example.com");

            when(userCredentialService.getAccessTokenInfo("user1", "my-cred"))
                    .thenReturn(Optional.empty());

            assertNull(service.resolveCredentialVariant("user1", "my-cred", api));
        }

        @Test
        @DisplayName("Returns null for blank userId - prevents a pointless auth-service call during bootstrap")
        void nullForBlankUserId() {
            ApiEntity api = createTestApi("http://api.example.com");

            assertNull(service.resolveCredentialVariant("  ", "my-cred", api));
            verifyNoInteractions(userCredentialService);
        }

        @Test
        @DisplayName("Webhook normalizes to 'webhook' even though it is not a V103 variant - LIMIT 1 fallback then applies")
        void webhookNormalizesToUnknownVariantAndFallsBack() {
            ApiEntity api = createTestApi("http://api.example.com");

            com.apimarketplace.credential.client.dto.AccessTokenResult result =
                    new com.apimarketplace.credential.client.dto.AccessTokenResult("hook", true, "Webhook");
            when(userCredentialService.getAccessTokenInfo("user1", "my-cred"))
                    .thenReturn(Optional.of(result));

            // resolver returns "webhook"; caller threads it to getCredentialInjection which will
            // find no row matching metadata->>'variant' = 'webhook' and fall through to the
            // unfiltered LIMIT 1 - the documented behavior for single-variant APIs whose
            // CredentialType has no V103 equivalent.
            assertEquals("webhook", service.resolveCredentialVariant("user1", "my-cred", api));
        }
    }

    // ========================================================================
    // tryGetCredentialValue tests
    // ========================================================================

    @Nested
    @DisplayName("tryGetCredentialValue()")
    class TryGetCredentialValueTests {

        @Test
        @DisplayName("should get user credential in user_key mode")
        void shouldGetUserCredentialInUserKeyMode() {
            ApiEntity api = createTestApi("http://api.example.com");

            when(userCredentialService.getAccessToken("user1", "my-cred"))
                .thenReturn(Optional.of("token123"));

            Optional<String> result = service.tryGetCredentialValue("user1", "my-cred", api);

            assertTrue(result.isPresent());
            assertEquals("token123", result.get());
        }

        @Test
        @DisplayName("agentic path: user has no credential → falls back to platform")
        void shouldGetPlatformCredentialInPlatformKeyMode() {
            ApiEntity api = createTestApi("http://api.example.com");
            api.setPlatformCredentialName("platform-cred");

            when(userCredentialService.getAccessToken("user1", "my-cred"))
                .thenReturn(Optional.empty());
            when(userCredentialService.getAccessToken("PLATFORM", "platform-cred"))
                .thenReturn(Optional.of("platform-token"));

            Optional<String> result = service.tryGetCredentialValue("user1", "my-cred", api);

            assertTrue(result.isPresent());
            assertEquals("platform-token", result.get());
        }

        @Test
        @DisplayName("should fallback to platform in both mode")
        void shouldFallbackToPlatformInBothMode() {
            ApiEntity api = createTestApi("http://api.example.com");
            api.setPlatformCredentialName("platform-cred");

            when(userCredentialService.getAccessToken("user1", "my-cred"))
                .thenReturn(Optional.empty());
            when(userCredentialService.getAccessToken("PLATFORM", "platform-cred"))
                .thenReturn(Optional.of("platform-token"));

            Optional<String> result = service.tryGetCredentialValue("user1", "my-cred", api);

            assertTrue(result.isPresent());
            assertEquals("platform-token", result.get());
        }

        @Test
        @DisplayName("should default to user_key for null mode")
        void shouldDefaultToUserKeyForNullMode() {
            ApiEntity api = createTestApi("http://api.example.com");

            when(userCredentialService.getAccessToken("user1", "my-cred"))
                .thenReturn(Optional.of("user-token"));

            Optional<String> result = service.tryGetCredentialValue("user1", "my-cred", api);

            assertTrue(result.isPresent());
            assertEquals("user-token", result.get());
        }
    }

    // ========================================================================
    // executeHttpCall tests
    // ========================================================================

    @Nested
    @DisplayName("executeHttpCall()")
    class ExecuteHttpCallTests {

        @Test
        @DisplayName("should execute successful HTTP call")
        void shouldExecuteSuccessfulHttpCall() throws Exception {
            try (MockedStatic<UrlSafetyValidator> urlValidator = mockStatic(UrlSafetyValidator.class)) {
                urlValidator.when(() -> UrlSafetyValidator.validateUrl(anyString())).thenAnswer(inv -> null);

                ApiEntity api = createTestApi("http://api.example.com");
                ApiToolEntity tool = createTestTool("/users");
                tool.setMethod("GET");
                ArrayNode parameters = objectMapper.createArrayNode();

                ResponseEntity<Object> response = new ResponseEntity<>(
                    Map.of("id", 1, "name", "John"),
                    HttpStatus.OK
                );

                when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(), eq(Object.class)))
                    .thenReturn(response);

                Map<String, Object> result = service.executeHttpCall(api, tool, parameters, Set.of());

                assertTrue((Boolean) result.get("success"));
                assertEquals(200, result.get("status"));
                assertNotNull(result.get("data"));
            }
        }

        @Test
        @DisplayName("should return error result on exception")
        void shouldReturnErrorResultOnException() {
            try (MockedStatic<UrlSafetyValidator> urlValidator = mockStatic(UrlSafetyValidator.class)) {
                urlValidator.when(() -> UrlSafetyValidator.validateUrl(anyString())).thenAnswer(inv -> null);

                ApiEntity api = createTestApi("http://api.example.com");
                ApiToolEntity tool = createTestTool("/users");
                tool.setMethod("GET");
                ArrayNode parameters = objectMapper.createArrayNode();

                when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(), eq(Object.class)))
                    .thenThrow(new RuntimeException("Connection refused"));

                Map<String, Object> result = service.executeHttpCall(api, tool, parameters, Set.of());

                assertFalse((Boolean) result.get("success"));
                assertEquals(0, result.get("status"));
                assertEquals("Connection refused", result.get("error"));
            }
        }
    }

    // ========================================================================
    // SSRF guard - UrlSafetyValidator runs FOR REAL (not mockStatic'd).
    //
    // Every other test in this file stubs UrlSafetyValidator with
    // mockStatic(...).thenAnswer(inv -> null), so the SSRF block is never
    // actually proven. These tests let the real validator run and assert that
    // an internal / cloud-metadata target is blocked BEFORE any outbound call:
    // the result is success=false and restTemplate is never touched.
    //
    // Literal IPs (127.0.0.1, 169.254.169.254) resolve to themselves without a
    // network DNS lookup, so the validator's loopback/link-local checks fire
    // deterministically and offline. validateUrl throws IllegalArgumentException,
    // which executeHttpCall(...) catches and converts into an error result map.
    // ========================================================================

    @Nested
    @DisplayName("SSRF guard (UrlSafetyValidator runs for real)")
    class SsrfGuardTests {

        @Test
        @DisplayName("cloud metadata endpoint (169.254.169.254) is blocked before any HTTP call")
        void cloudMetadataTargetBlocked() {
            // NOTE: deliberately NOT wrapping in mockStatic(UrlSafetyValidator) - the guard runs.
            ApiEntity api = createTestApi("http://169.254.169.254/");
            ApiToolEntity tool = createTestTool("/latest/meta-data/iam/security-credentials/");
            tool.setMethod("GET");
            ArrayNode parameters = objectMapper.createArrayNode();

            Map<String, Object> result = service.executeHttpCall(api, tool, parameters, Set.of());

            assertFalse((Boolean) result.get("success"),
                "a link-local cloud-metadata target must be rejected, not fetched");
            assertEquals(0, result.get("status"),
                "SSRF rejection surfaces as a non-HTTP error (status 0), not an upstream status");
            assertNotNull(result.get("error"), "an SSRF rejection must carry an error message");
            verifyNoInteractions(restTemplate);
        }

        @Test
        @DisplayName("loopback target (127.0.0.1) is blocked before any HTTP call")
        void loopbackTargetBlocked() {
            ApiEntity api = createTestApi("http://127.0.0.1/");
            ApiToolEntity tool = createTestTool("/admin");
            tool.setMethod("GET");
            ArrayNode parameters = objectMapper.createArrayNode();

            Map<String, Object> result = service.executeHttpCall(api, tool, parameters, Set.of());

            assertFalse((Boolean) result.get("success"),
                "a loopback target must be rejected, not fetched");
            verifyNoInteractions(restTemplate);
        }

        @Test
        @DisplayName("unsafe host injected via a path-param substitution is still blocked (validation runs after substitution)")
        void unsafeHostViaPathParamSubstitutionBlocked() {
            // The host arrives via {target}, substituted by processPathParameters BEFORE
            // UrlSafetyValidator runs - proving the guard covers param-built URLs, not just
            // static baseUrls. The path param must be in allowedParamNames to survive filtering.
            ApiEntity api = createTestApi("http://{target}/");
            ApiToolEntity tool = createTestTool("/latest/meta-data/");
            tool.setMethod("GET");

            ArrayNode parameters = objectMapper.createArrayNode();
            parameters.add(objectMapper.createObjectNode().put("target", "169.254.169.254"));

            Map<String, Object> result =
                service.executeHttpCall(api, tool, parameters, Set.of("target"));

            assertFalse((Boolean) result.get("success"),
                "an unsafe host substituted from a path param must be rejected after substitution");
            assertNotNull(result.get("error"));
            verifyNoInteractions(restTemplate);
        }

        @Test
        @DisplayName("credentialed execute path also blocks an internal target before any HTTP call")
        void cloudMetadataTargetBlockedOnCredentialedPath() {
            // executeHttpCallWithCredentials has its OWN validateUrl call site - exercise it too.
            ApiEntity api = createTestApi("http://169.254.169.254/");
            api.setPlatformCredentialName("someservice");
            ApiToolEntity tool = createTestTool("/latest/meta-data/");
            tool.setMethod("GET");
            ArrayNode parameters = objectMapper.createArrayNode();

            Map<String, Object> result = service.executeHttpCallWithCredentials(
                api, tool, parameters, Set.of(), "user1", "someservice");

            assertFalse((Boolean) result.get("success"),
                "the credentialed path must also reject an internal target");
            verifyNoInteractions(restTemplate);
        }
    }

    // ========================================================================
    // tryGetCredentialResolution + per-call override tests
    // ========================================================================

    @Nested
    @DisplayName("tryGetCredentialResolution() - credential mode override")
    class CredentialModeOverrideTests {

        private static final String USER_ID = "user-1";
        private static final String CRED = "googlegemini";
        private static final String PLATFORM_TENANT = "PLATFORM";

        @org.junit.jupiter.api.AfterEach
        void clearOverride() {
            // Defensive: ensure no thread-local leak across tests since the
            // controller normally clears it but unit tests bypass the controller.
            CredentialModeContext.clear();
        }

        @Test
        @DisplayName("user_key + no override + user has credential → returns USER source (existing behavior)")
        void userKeyExistingBehavior() {
            ApiEntity api = createTestApi("http://api.example.com");
            api.setPlatformCredentialName("googlegemini");
            when(userCredentialService.getAccessToken(USER_ID, CRED))
                    .thenReturn(Optional.of("user-token"));

            Optional<HttpExecutionService.CredentialResolution> result =
                    service.tryGetCredentialResolution(USER_ID, CRED, api);

            assertTrue(result.isPresent());
            assertEquals("user-token", result.get().value());
            assertEquals(HttpExecutionService.CredentialSource.USER, result.get().source());
        }

        @Test
        @DisplayName("explicitSource='user' (workflow toggle=user) + user has NO credential → returns empty (durci, no platform fallback)")
        void userKeyStrictNoFallback() {
            ApiEntity api = createTestApi("http://api.example.com");
            api.setPlatformCredentialName("googlegemini");
            when(userCredentialService.getAccessToken(USER_ID, CRED)).thenReturn(Optional.empty());

            try {
                CredentialModeContext.setExplicitSource("user");
                Optional<HttpExecutionService.CredentialResolution> result =
                        service.tryGetCredentialResolution(USER_ID, CRED, api);

                assertFalse(result.isPresent(),
                        "Workflow toggle=user is durci - must NOT fall back to platform credential");
                verify(userCredentialService, never()).getAccessToken(eq(PLATFORM_TENANT), anyString());
            } finally {
                CredentialModeContext.clear();
            }
        }

        @Test
        @DisplayName("explicitSource='user' + selectedCredentialId uses the selected credential, not the default lookup")
        void userKeyWithSelectedCredentialIdUsesSelectedCredential() {
            ApiEntity api = createTestApi("http://api.example.com");
            api.setPlatformCredentialName("googlegemini");
            com.apimarketplace.credential.client.dto.AccessTokenResult selected =
                    new com.apimarketplace.credential.client.dto.AccessTokenResult("selected-token", true, "OAuth2");

            try {
                CredentialModeContext.setExplicitSource("user");
                CredentialModeContext.setSelectedCredentialId(42L);
                when(userCredentialService.getAccessTokenInfoById(USER_ID, 42L))
                        .thenReturn(Optional.of(selected));

                Optional<HttpExecutionService.CredentialResolution> result =
                        service.tryGetCredentialResolution(USER_ID, CRED, api);

                assertTrue(result.isPresent());
                assertEquals("selected-token", result.get().value());
                assertEquals(HttpExecutionService.CredentialSource.USER, result.get().source());
                verify(userCredentialService, never()).getAccessToken(USER_ID, CRED);
                verify(userCredentialService, never()).getAccessToken(eq(PLATFORM_TENANT), anyString());
            } finally {
                CredentialModeContext.clear();
            }
        }

        @Test
        @DisplayName("explicitSource='user' + pinned selectedCredentialId DELETED → falls back to the user's default credential (take pinned, else default), never platform")
        void userKeySelectedCredentialDeletedFallsBackToDefault() {
            // Regression: a pinned plan whose credential was deleted/reconnected used to
            // fail (credentials_required / empty resolution) because the by-id lookup
            // returned nothing and explicitSource='user' short-circuits before the
            // default-by-name lookup. It must now fall back to the user's default
            // credential for the integration - but still never to platform.
            ApiEntity api = createTestApi("http://api.example.com");
            api.setPlatformCredentialName("googlegemini");

            try {
                CredentialModeContext.setExplicitSource("user");
                CredentialModeContext.setSelectedCredentialId(99L); // pinned, but since deleted
                when(userCredentialService.getAccessTokenInfoById(USER_ID, 99L))
                        .thenReturn(Optional.empty());
                when(userCredentialService.getAccessToken(USER_ID, CRED))
                        .thenReturn(Optional.of("default-token"));

                Optional<HttpExecutionService.CredentialResolution> result =
                        service.tryGetCredentialResolution(USER_ID, CRED, api);

                assertTrue(result.isPresent(), "Deleted pinned credential must fall back to the user's default");
                assertEquals("default-token", result.get().value());
                assertEquals(HttpExecutionService.CredentialSource.USER, result.get().source());
                verify(userCredentialService, never()).getAccessToken(eq(PLATFORM_TENANT), anyString());
            } finally {
                CredentialModeContext.clear();
            }
        }

        @Test
        @DisplayName("user_key + override='both' + user has NO credential → falls back to PLATFORM (agent path)")
        void overrideBothEnablesPlatformFallback() {
            ApiEntity api = createTestApi("http://api.example.com");
            api.setPlatformCredentialName("googlegemini");
            when(userCredentialService.getAccessToken(USER_ID, CRED)).thenReturn(Optional.empty());
            when(userCredentialService.getAccessToken(PLATFORM_TENANT, "googlegemini"))
                    .thenReturn(Optional.of("platform-token"));

            CredentialModeContext.setOverride("both");
            Optional<HttpExecutionService.CredentialResolution> result =
                    service.tryGetCredentialResolution(USER_ID, CRED, api);

            assertTrue(result.isPresent(), "Override must enable platform fallback");
            assertEquals("platform-token", result.get().value());
            assertEquals(HttpExecutionService.CredentialSource.PLATFORM, result.get().source(),
                    "Source must be PLATFORM so the billing dispatcher debits credits");
        }

        @Test
        @DisplayName("user_key + override='both' + user HAS credential → returns USER (no debit)")
        void overrideBothPrefersUserKey() {
            ApiEntity api = createTestApi("http://api.example.com");
            api.setPlatformCredentialName("googlegemini");
            when(userCredentialService.getAccessToken(USER_ID, CRED))
                    .thenReturn(Optional.of("user-token"));

            CredentialModeContext.setOverride("both");
            Optional<HttpExecutionService.CredentialResolution> result =
                    service.tryGetCredentialResolution(USER_ID, CRED, api);

            assertTrue(result.isPresent());
            assertEquals("user-token", result.get().value());
            assertEquals(HttpExecutionService.CredentialSource.USER, result.get().source(),
                    "BYOK user must not be debited credits when their own key is present");
            verify(userCredentialService, never()).getAccessToken(eq(PLATFORM_TENANT), anyString());
        }

        @Test
        @DisplayName("rejected override 'platform_key' is silently dropped → behaves like agentic (user-then-platform fallback)")
        void rejectedOverrideFallsThrough() {
            ApiEntity api = createTestApi("http://api.example.com");
            api.setPlatformCredentialName("googlegemini");
            when(userCredentialService.getAccessToken(USER_ID, CRED)).thenReturn(Optional.empty());
            when(userCredentialService.getAccessToken(PLATFORM_TENANT, "googlegemini"))
                    .thenReturn(Optional.of("platform-token"));

            // The override whitelist rejects 'platform_key' silently - the
            // override field stays null, and the resolver falls through to the
            // default agentic semantic (try user, fall back to platform).
            // Importantly, this does NOT exploit the rejection to force
            // platform - it just behaves as if no override was set.
            CredentialModeContext.setOverride("platform_key");
            Optional<HttpExecutionService.CredentialResolution> result =
                    service.tryGetCredentialResolution(USER_ID, CRED, api);

            assertTrue(result.isPresent(),
                    "Agentic fallback applies when no explicit source is set (override rejection ≠ 'user-only strict')");
            assertEquals("platform-token", result.get().value());
            assertEquals(HttpExecutionService.CredentialSource.PLATFORM, result.get().source());
        }

        @Test
        @DisplayName("override='both' (legacy) + user has nothing → falls back to PLATFORM (agentic fallback semantics preserved)")
        void overrideBothOnPlatformKeyApiStillResolvesPlatform() {
            ApiEntity api = createTestApi("http://api.example.com");
            api.setPlatformCredentialName("googlegemini");
            // 'both' tries user first; user has nothing → platform fallback.
            when(userCredentialService.getAccessToken(USER_ID, CRED)).thenReturn(Optional.empty());
            when(userCredentialService.getAccessToken(PLATFORM_TENANT, "googlegemini"))
                    .thenReturn(Optional.of("platform-token"));

            CredentialModeContext.setOverride("both");
            Optional<HttpExecutionService.CredentialResolution> result =
                    service.tryGetCredentialResolution(USER_ID, CRED, api);

            assertTrue(result.isPresent());
            assertEquals(HttpExecutionService.CredentialSource.PLATFORM, result.get().source(),
                    "platform_key APIs under 'both' override must still bill via PLATFORM source");
        }
    }

    // ========================================================================
    // ========================================================================

    // EffectiveCredentialModeTests removed: V154 dropped credential_mode column
    // and effectiveCredentialMode() helper. Resolution policy is now driven by
    // explicitSource (workflow toggle, durci) or implicit user→platform fallback
    // (agentic paths). Coverage of the new policy lives in PolicyAndResolutionTests.

    // ========================================================================
    // Authorization-header construction - defensive prefix stripping
    // ========================================================================

    @Nested
    @DisplayName("prepareHeadersWithCredentials() - Bearer-prefix de-duplication")
    class BearerPrefixDedupTests {

        @Test
        @DisplayName("Regression: user-pasted \"Bearer xxx\" must NOT produce \"Authorization: Bearer Bearer xxx\"")
        void stripsUserTypedBearerPrefix() {
            // Reproduces the prod bug seen on userId=1 / cred=apify (id=44), 2026-05-01:
            // user pasted "Bearer apify_api_xK7..." into the credential form. The runtime
            // hardcoded "Bearer " in front, producing a malformed Authorization header,
            // and Apify returned 401 → catalog surfaced the misleading
            // "Authentication expired for apify" error.
            ApiEntity api = createTestApi("https://api.apify.com/v2");
            ApiToolEntity tool = createTestTool("/acts");
            HttpExecutionService.CredentialInjection injection =
                new HttpExecutionService.CredentialInjection("header", "Authorization", "api_token", "Bearer ");

            HttpHeaders headers = service.prepareHeadersWithCredentials(
                api, tool, "user1", "apify", injection,
                Optional.of("Bearer apify_api_xK7pQ"));

            assertEquals("Bearer apify_api_xK7pQ", headers.getFirst("Authorization"));
        }

        @Test
        @DisplayName("Pasted value with leading whitespace and a Bearer prefix is normalized to a single clean header")
        void stripsLeadingWhitespaceAndBearerPrefix() {
            ApiEntity api = createTestApi("https://api.example.com");
            ApiToolEntity tool = createTestTool("/me");
            HttpExecutionService.CredentialInjection injection =
                new HttpExecutionService.CredentialInjection("header", "Authorization", "api_token", "Bearer ");

            HttpHeaders headers = service.prepareHeadersWithCredentials(
                api, tool, "user1", "cred", injection,
                Optional.of("   Bearer  raw_token_xyz"));

            assertEquals("Bearer raw_token_xyz", headers.getFirst("Authorization"));
        }

        @Test
        @DisplayName("Naked token (no user-typed prefix) still gets the prefix applied exactly once")
        void appliesPrefixWhenAbsent() {
            ApiEntity api = createTestApi("https://api.example.com");
            ApiToolEntity tool = createTestTool("/me");
            HttpExecutionService.CredentialInjection injection =
                new HttpExecutionService.CredentialInjection("header", "Authorization", "api_token", "Bearer ");

            HttpHeaders headers = service.prepareHeadersWithCredentials(
                api, tool, "user1", "cred", injection,
                Optional.of("apify_api_xK7pQ"));

            assertEquals("Bearer apify_api_xK7pQ", headers.getFirst("Authorization"));
        }

        @Test
        @DisplayName("Case-insensitive prefix match: \"bearer xxx\" still strips correctly")
        void caseInsensitivePrefixStrip() {
            ApiEntity api = createTestApi("https://api.example.com");
            ApiToolEntity tool = createTestTool("/me");
            HttpExecutionService.CredentialInjection injection =
                new HttpExecutionService.CredentialInjection("header", "Authorization", "api_token", "Bearer ");

            HttpHeaders headers = service.prepareHeadersWithCredentials(
                api, tool, "user1", "cred", injection,
                Optional.of("bearer raw_token_xyz"));

            assertEquals("Bearer raw_token_xyz", headers.getFirst("Authorization"));
        }

        @Test
        @DisplayName("Custom prefix \"Token \" (e.g. some legacy APIs) is honored from injection metadata, not hardcoded Bearer")
        void honorsCustomPrefixFromInjection() {
            // Future-proofing: APIs that declare "Token " or another custom scheme
            // in apiKeyConfig.prefix must produce the corresponding Authorization
            // header - NOT a hardcoded "Bearer ". The user-typed-prefix strip also
            // respects the declared scheme.
            ApiEntity api = createTestApi("https://api.example.com");
            ApiToolEntity tool = createTestTool("/me");
            HttpExecutionService.CredentialInjection injection =
                new HttpExecutionService.CredentialInjection("header", "Authorization", "api_token", "Token ");

            HttpHeaders headers = service.prepareHeadersWithCredentials(
                api, tool, "user1", "cred", injection,
                Optional.of("Token abc123"));

            assertEquals("Token abc123", headers.getFirst("Authorization"));
        }

        @Test
        @DisplayName("Injection without a declared prefix falls back to the historical \"Bearer \" default")
        void nullPrefixFallsBackToBearer() {
            // Backward compatibility: tool_credentials rows imported before the prefix
            // column existed (60% of current data) carry no prefix. The runtime keeps
            // the hardcoded "Bearer " default for them so behavior doesn't regress.
            ApiEntity api = createTestApi("https://api.example.com");
            ApiToolEntity tool = createTestTool("/me");
            HttpExecutionService.CredentialInjection injection =
                new HttpExecutionService.CredentialInjection("header", "Authorization", "api_token");

            HttpHeaders headers = service.prepareHeadersWithCredentials(
                api, tool, "user1", "cred", injection,
                Optional.of("raw_token"));

            assertEquals("Bearer raw_token", headers.getFirst("Authorization"));
        }

        @Test
        @DisplayName("Custom (non-Authorization) header with declared prefix strips and re-applies it")
        void customHeaderHonorsAndStripsPrefix() {
            ApiEntity api = createTestApi("https://api.example.com");
            ApiToolEntity tool = createTestTool("/me");
            HttpExecutionService.CredentialInjection injection =
                new HttpExecutionService.CredentialInjection("header", "X-Auth-Token", "api_token", "Token ");

            HttpHeaders headers = service.prepareHeadersWithCredentials(
                api, tool, "user1", "cred", injection,
                Optional.of("Token tok_abc"));

            assertEquals("Token tok_abc", headers.getFirst("X-Auth-Token"));
        }

        @Test
        @DisplayName("Custom header without a declared prefix passes the raw value through unchanged (X-API-Key style)")
        void customHeaderWithoutPrefixIsRaw() {
            ApiEntity api = createTestApi("https://api.example.com");
            ApiToolEntity tool = createTestTool("/me");
            HttpExecutionService.CredentialInjection injection =
                new HttpExecutionService.CredentialInjection("header", "X-API-Key", "api_key");

            HttpHeaders headers = service.prepareHeadersWithCredentials(
                api, tool, "user1", "cred", injection,
                Optional.of("raw_api_key_xyz"));

            assertEquals("raw_api_key_xyz", headers.getFirst("X-API-Key"));
        }
    }

    @Nested
    @DisplayName("stripUserTypedPrefix() - pure helper")
    class StripUserTypedPrefixTests {

        @Test
        @DisplayName("returns input unchanged when prefix is null")
        void nullPrefixIsNoOp() {
            assertEquals("xxx", HttpExecutionService.stripUserTypedPrefix("xxx", null));
        }

        @Test
        @DisplayName("returns null when value is null")
        void nullValueReturnsNull() {
            assertNull(HttpExecutionService.stripUserTypedPrefix(null, "Bearer "));
        }

        @Test
        @DisplayName("trims leading whitespace even when no prefix matches")
        void leftTrimsWhenNoMatch() {
            assertEquals("naked_token", HttpExecutionService.stripUserTypedPrefix("   naked_token", "Bearer "));
        }

        @Test
        @DisplayName("strips prefix and re-trims so a single trailing space inside the prefix doesn't double up")
        void stripsPrefixAndRetrim() {
            assertEquals("xyz", HttpExecutionService.stripUserTypedPrefix("Bearer    xyz", "Bearer "));
        }

        @Test
        @DisplayName("does NOT strip when value happens to start with prefix-shaped substring but isn't the prefix")
        void doesNotStripWhenSubstringDiffers() {
            // "Bearertoken" (no space after Bearer) does not start with "Bearer "
            // because regionMatches checks the full prefix length.
            assertEquals("Bearertoken", HttpExecutionService.stripUserTypedPrefix("Bearertoken", "Bearer "));
        }
    }

    // ========================================================================
    // V166: per-endpoint requiredScopes preflight
    // ========================================================================

    @Nested
    @DisplayName("preflightScopeCheck() - V166")
    class PreflightScopeCheckTests {

        @Test
        @DisplayName("no requiredScopes: short-circuits without ever calling CredentialClient")
        void preflightNoRequiredScopes_isNoOp_credentialClientNotCalled() {
            ApiEntity api = createTestApi("https://api.example.com");
            ApiToolEntity tool = createTestTool("/me");
            tool.setRequiredScopes(null);

            assertDoesNotThrow(() -> service.preflightScopeCheck("user-1", "myCred", api, tool));
            verify(userCredentialService, never()).getCredentialScopes(anyString(), anyString());
        }

        @Test
        @DisplayName("non-oauth2 credential: skips silently - scope concept does not apply")
        void preflightWithApiKeyCredential_isNoOp() {
            ApiEntity api = createTestApi("https://api.example.com");
            ApiToolEntity tool = createTestTool("/me");
            tool.setRequiredScopes(List.of("scope.read"));
            when(userCredentialService.getCredentialScopes("user-1", "myCred"))
                    .thenReturn(Optional.of(new com.apimarketplace.credential.client.dto.CredentialScopesDto(
                            "api_key", null)));

            assertDoesNotThrow(() -> service.preflightScopeCheck("user-1", "myCred", api, tool));
        }

        @Test
        @DisplayName("oauth2 with all scopes granted: passes silently")
        void preflightWithGrantedScopes_passes() {
            ApiEntity api = createTestApi("https://api.example.com");
            ApiToolEntity tool = createTestTool("/me");
            tool.setRequiredScopes(List.of("scope.read"));
            when(userCredentialService.getCredentialScopes("user-1", "myCred"))
                    .thenReturn(Optional.of(new com.apimarketplace.credential.client.dto.CredentialScopesDto(
                            "oauth2", List.of("scope.read", "scope.write"))));

            assertDoesNotThrow(() -> service.preflightScopeCheck("user-1", "myCred", api, tool));
        }

        @Test
        @DisplayName("oauth2 missing scope: throws InsufficientScopesException with the exact missing set")
        void preflightWithMissingScopes_throws() {
            ApiEntity api = createTestApi("https://api.example.com");
            api.setPlatformCredentialName("gmail");
            ApiToolEntity tool = createTestTool("/messages/send");
            tool.setRequiredScopes(List.of("gmail.send", "gmail.modify"));
            when(userCredentialService.getCredentialScopes("user-1", "myCred"))
                    .thenReturn(Optional.of(new com.apimarketplace.credential.client.dto.CredentialScopesDto(
                            "oauth2", List.of("gmail.readonly"))));

            com.apimarketplace.catalog.service.exception.InsufficientScopesException ex =
                    assertThrows(com.apimarketplace.catalog.service.exception.InsufficientScopesException.class,
                            () -> service.preflightScopeCheck("user-1", "myCred", api, tool));
            assertEquals(Set.of("gmail.send", "gmail.modify"), ex.getMissingScopes());
            assertEquals("gmail", ex.getIntegration());
            assertEquals("myCred", ex.getCredentialName());
        }

        @Test
        @DisplayName("auth-service unreachable (Optional.empty): fails open - caller proceeds")
        void preflightCredentialClientError_failsOpen() {
            ApiEntity api = createTestApi("https://api.example.com");
            ApiToolEntity tool = createTestTool("/messages/send");
            tool.setRequiredScopes(List.of("gmail.send"));
            when(userCredentialService.getCredentialScopes("user-1", "myCred"))
                    .thenReturn(Optional.empty());

            assertDoesNotThrow(() -> service.preflightScopeCheck("user-1", "myCred", api, tool));
        }

        @Test
        @DisplayName("multi-variant: credential connected via narrow variant, tool requires scope only granted by wide variant - throws")
        void preflightWithOauth2CredentialMissingScopeFromOtherVariant_throws() {
            // Setup: API has two oauth2 variants in the JSON (validator-time concept).
            // The user connected via the narrow variant (gmail.labels only). The tool
            // requires gmail.send which is only in the wide variant. Runtime preflight
            // catches this because the credential's STORED scopes are what matter, not
            // the JSON variant declarations.
            ApiEntity api = createTestApi("https://gmail.googleapis.com");
            api.setPlatformCredentialName("gmail");
            ApiToolEntity tool = createTestTool("/users/me/messages/send");
            tool.setRequiredScopes(List.of("https://www.googleapis.com/auth/gmail.send"));
            when(userCredentialService.getCredentialScopes("user-1", "narrowGmail"))
                    .thenReturn(Optional.of(new com.apimarketplace.credential.client.dto.CredentialScopesDto(
                            "oauth2", List.of("https://www.googleapis.com/auth/gmail.labels"))));

            com.apimarketplace.catalog.service.exception.InsufficientScopesException ex =
                    assertThrows(com.apimarketplace.catalog.service.exception.InsufficientScopesException.class,
                            () -> service.preflightScopeCheck("user-1", "narrowGmail", api, tool));
            assertTrue(ex.getMissingScopes().contains("https://www.googleapis.com/auth/gmail.send"));
        }

        @Test
        @DisplayName("legacy null scopes column on oauth2 credential: throws (treats null as empty granted)")
        void preflightWithLegacyCredentialNullScopesField_failsClosed() {
            ApiEntity api = createTestApi("https://api.example.com");
            ApiToolEntity tool = createTestTool("/me");
            tool.setRequiredScopes(List.of("scope.read"));
            when(userCredentialService.getCredentialScopes("user-1", "myCred"))
                    .thenReturn(Optional.of(new com.apimarketplace.credential.client.dto.CredentialScopesDto(
                            "oauth2", null)));

            assertThrows(com.apimarketplace.catalog.service.exception.InsufficientScopesException.class,
                    () -> service.preflightScopeCheck("user-1", "myCred", api, tool));
        }

        @Test
        @DisplayName("blank credentialName: short-circuits - covered by downstream resolution path")
        void preflightWithBlankCredentialName_isNoOp() {
            ApiEntity api = createTestApi("https://api.example.com");
            ApiToolEntity tool = createTestTool("/me");
            tool.setRequiredScopes(List.of("scope.read"));

            assertDoesNotThrow(() -> service.preflightScopeCheck("user-1", "", api, tool));
            assertDoesNotThrow(() -> service.preflightScopeCheck("user-1", null, api, tool));
            verify(userCredentialService, never()).getCredentialScopes(anyString(), anyString());
        }
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    private ApiEntity createTestApi(String baseUrl) {
        ApiEntity api = new ApiEntity();
        api.setId(UUID.randomUUID());
        api.setBaseUrl(baseUrl);
        api.setApiName("Test API");
        return api;
    }

    private ApiToolEntity createTestTool(String endpoint) {
        ApiToolEntity tool = new ApiToolEntity();
        tool.setId(UUID.randomUUID());
        tool.setEndpoint(endpoint);
        tool.setMethod("GET");
        return tool;
    }
}
