package com.apimarketplace.catalog.service.http;

import com.apimarketplace.catalog.domain.ApiEntity;
import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.domain.ApiToolParameterEntity;
import com.apimarketplace.catalog.repository.ApiToolParameterRepository;
import com.apimarketplace.catalog.service.UserCredentialService;
import com.apimarketplace.common.security.CredentialEncryptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Regressions from the catalog tool-health board (prod, last 30 days to 2026-09-29), one nested
 * group per failure the engine produced for a reason that was not the provider's.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HttpExecutionService - tool-health regressions (text bodies, host values, unfilled URLs)")
class HttpExecutionServiceToolHealthRegressionTest {

    @Mock private ApiToolParameterRepository apiToolParameterRepository;
    @Mock private UserCredentialService userCredentialService;
    @Mock private CredentialEncryptionService encryptionService;
    @Mock private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** A public IP literal: SSRF validation accepts it without a DNS lookup. */
    private static final String PUBLIC_BASE = "https://93.184.216.34/v2";

    @BeforeEach
    void setUp() {
        lenient().when(encryptionService.decrypt(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(jdbcTemplate.queryForList(anyString(), (Object) any())).thenReturn(new ArrayList<>());
        lenient().when(jdbcTemplate.queryForList(anyString(), (Object) any(), (Object) any())).thenReturn(new ArrayList<>());
        lenient().when(userCredentialService.getAccessTokenInfo(anyString(), anyString())).thenReturn(Optional.empty());
        lenient().when(userCredentialService.getAccessToken(anyString(), anyString())).thenReturn(Optional.empty());
        CredentialModeContext.clear();
    }

    private HttpExecutionService service(RestTemplate restTemplate) {
        return new HttpExecutionService(
                apiToolParameterRepository, userCredentialService, encryptionService,
                objectMapper, jdbcTemplate, restTemplate, new ErrorPolicyEngine());
    }

    private ApiToolEntity tool(String method, String endpoint, ApiToolParameterEntity... params) {
        ApiToolEntity t = new ApiToolEntity();
        t.setId(UUID.randomUUID());
        t.setMethod(method);
        t.setEndpoint(endpoint);
        t.setToolSlug("probe_tool");
        lenient().when(apiToolParameterRepository.findByApiToolId(t.getId())).thenReturn(Arrays.asList(params));
        return t;
    }

    private ApiToolEntity typed(ApiToolEntity t, String responseType) {
        t.setExecutionMode("sync");
        t.setExecutionSpec("{\"mode\":\"sync\",\"response\":{\"type\":\"" + responseType + "\"}}");
        t.setOutputSchema("[]");
        return t;
    }

    private static ApiToolParameterEntity param(String name, String type, String defaultValue) {
        ApiToolParameterEntity p = new ApiToolParameterEntity();
        p.setName(name);
        p.setParameterType(type);
        p.setDefaultValue(defaultValue);
        return p;
    }

    private static ApiEntity api(String baseUrl) {
        ApiEntity api = new ApiEntity();
        api.setBaseUrl(baseUrl);
        return api;
    }

    private ArrayNode params(Map<String, String> values) {
        ArrayNode arr = objectMapper.createArrayNode();
        values.forEach((k, v) -> arr.add(objectMapper.createObjectNode().put(k, v)));
        return arr;
    }

    private void givenCredential(String name, Map<String, String> data) {
        lenient().when(userCredentialService.getCredentialDataMap("user1", name)).thenReturn(data);
    }

    /** The user HAS a connection of this integration (its primary token resolves). */
    private void givenConnected(String name) {
        lenient().when(userCredentialService.getAccessToken("user1", name)).thenReturn(Optional.of("tok"));
    }

    // ── A2: a textual, non-JSON answer is read as text ──────────────────────

    @Nested
    @DisplayName("A2: a text/csv (or other textual) answer is returned as text, not failed")
    class TextualResponses {

        private RestTemplate restTemplate;
        private MockRestServiceServer server;
        private HttpExecutionService service;

        @BeforeEach
        void wire() {
            restTemplate = new RestTemplate();
            server = MockRestServiceServer.bindTo(restTemplate).build();
            service = service(restTemplate);
            givenCredential("apify", Map.of("api_token", "t", "other", "x"));
        }

        private Map<String, Object> call(byte[] body, MediaType type) {
            server.expect(requestTo(PUBLIC_BASE + "/datasets/d1/items"))
                    .andRespond(withSuccess(body, type));
            return service.executeHttpCallWithCredentials(api(PUBLIC_BASE),
                    tool("GET", "/datasets/d1/items"), params(Map.of()), null, "user1", "apify");
        }

        private Map<String, Object> call(String body, MediaType type) {
            return call(body.getBytes(StandardCharsets.UTF_8), type);
        }

        @Test
        @DisplayName("precondition: a default RestTemplate cannot read text/csv into Object (why the converter exists)")
        void defaultRestTemplateCannotReadCsv() {
            RestTemplate plain = new RestTemplate();
            MockRestServiceServer plainServer = MockRestServiceServer.bindTo(plain).build();
            plainServer.expect(requestTo("https://93.184.216.34/x"))
                    .andRespond(withSuccess("a,b\n1,2\n", MediaType.parseMediaType("text/csv;charset=utf-8")));

            assertThatThrownBy(() -> plain.exchange(URI.create("https://93.184.216.34/x"),
                    HttpMethod.GET, HttpEntity.EMPTY, Object.class))
                    .isInstanceOf(RestClientException.class)
                    .hasMessageContaining("no suitable HttpMessageConverter");
        }

        @Test
        @DisplayName("REGRESSION Apify get-dataset-items format=csv: the CSV comes back as data")
        void csvBecomesText() {
            Map<String, Object> result = call("a,b\n1,2\n", MediaType.parseMediaType("text/csv;charset=utf-8"));

            assertThat(result.get("success")).as(String.valueOf(result.get("error"))).isEqualTo(true);
            assertThat(result.get("data")).isEqualTo("a,b\n1,2\n");
        }

        @Test
        @DisplayName("the legacy credential-less send (String URL) reads text/csv too")
        void legacySendReadsCsv() {
            server.expect(requestTo(PUBLIC_BASE + "/datasets/d1/items"))
                    .andRespond(withSuccess("a,b\n1,2\n", MediaType.parseMediaType("text/csv")));

            Map<String, Object> result = service.executeHttpCall(api(PUBLIC_BASE),
                    tool("GET", "/datasets/d1/items"), params(Map.of()));

            assertThat(result.get("success")).as(String.valueOf(result.get("error"))).isEqualTo(true);
            assertThat(result.get("data")).isEqualTo("a,b\n1,2\n");
        }

        @Test
        @DisplayName("the 401 refresh-retry send reads text/csv too")
        void refreshRetrySendReadsCsv() {
            server.expect(requestTo(PUBLIC_BASE + "/datasets/d1/items"))
                    .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
            server.expect(requestTo(PUBLIC_BASE + "/datasets/d1/items"))
                    .andRespond(withSuccess("a,b\n", MediaType.parseMediaType("text/csv")));
            when(userCredentialService.forceRefreshAndGetToken("user1", "apify")).thenReturn(Optional.of("fresh"));

            Map<String, Object> result = service.executeHttpCallWithCredentials(api(PUBLIC_BASE),
                    tool("GET", "/datasets/d1/items"), params(Map.of()), null, "user1", "apify");

            assertThat(result.get("success")).as(String.valueOf(result.get("error"))).isEqualTo(true);
            assertThat(result.get("data")).isEqualTo("a,b\n");
            server.verify();
        }

        @Test
        @DisplayName("text/plain is read as text")
        void plainBecomesText() {
            assertThat(call("OK", MediaType.TEXT_PLAIN).get("data")).isEqualTo("OK");
        }

        @Test
        @DisplayName("application/xml is read as text")
        void xmlBecomesText() {
            assertThat(call("<a>1</a>", MediaType.APPLICATION_XML).get("data")).isEqualTo("<a>1</a>");
        }

        @Test
        @DisplayName("the charset the response names is honoured (ISO-8859-1)")
        void declaredCharsetHonoured() {
            byte[] latin1 = "café".getBytes(StandardCharsets.ISO_8859_1);

            Map<String, Object> result = call(latin1, MediaType.parseMediaType("text/plain;charset=ISO-8859-1"));

            assertThat(result.get("data")).isEqualTo("café");
        }

        @Test
        @DisplayName("JSON is still parsed by Jackson into a Map (no-op for the JSON corpus)")
        void jsonStillParsed() {
            assertThat(call("{\"n\":1}", MediaType.APPLICATION_JSON).get("data")).isEqualTo(Map.of("n", 1));
        }

        @Test
        @DisplayName("a 200 text/html page (login wall, captive portal) is still a failure, never success=true")
        void htmlStillFails() {
            Map<String, Object> result = call("<html>Sign in</html>", MediaType.TEXT_HTML);

            assertThat(result.get("success")).isEqualTo(false);
            assertThat(String.valueOf(result.get("error"))).contains("text/html");
        }

        @Test
        @DisplayName("an MCP event stream comes back as its JSON-RPC response")
        void eventStreamBecomesJson() {
            Map<String, Object> result = call(
                    "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}\n\n",
                    MediaType.TEXT_EVENT_STREAM);

            assertThat(result.get("data")).isEqualTo(
                    Map.of("jsonrpc", "2.0", "id", 1, "result", Map.of("ok", true)));
        }

        @Test
        @DisplayName("JSON served as text/plain or text/json comes back parsed")
        void jsonServedAsTextIsParsed() {
            assertThat(call("  {\"n\":1}\n", MediaType.TEXT_PLAIN).get("data")).isEqualTo(Map.of("n", 1));
            server.reset();
            assertThat(call("[1,2]", MediaType.parseMediaType("text/json")).get("data")).isEqualTo(List.of(1, 2));
            server.reset();
            assertThat(call("{not json", MediaType.TEXT_PLAIN).get("data")).isEqualTo("{not json");
        }

        @Test
        @DisplayName("REGRESSION: a multi-line NDJSON body keeps EVERY record (was: the first one only)")
        void ndjsonKeepsEveryRecord() {
            Map<String, Object> result = call("{\"a\":1}\n{\"a\":2}\n\n{\"a\":3}\n",
                    MediaType.parseMediaType("application/x-ndjson"));

            assertThat(result.get("data")).isEqualTo(List.of(Map.of("a", 1), Map.of("a", 2), Map.of("a", 3)));
        }

        @Test
        @DisplayName("NDJSON with a line that is not JSON comes back whole, as text")
        void ndjsonWithBadLineStaysText() {
            String body = "{\"a\":1}\nnot json\n";

            assertThat(call(body, MediaType.parseMediaType("application/x-ndjson")).get("data")).isEqualTo(body);
        }

        @Test
        @DisplayName("REGRESSION: JSON followed by text stays text (was: parsed to the JSON, the rest dropped)")
        void jsonFollowedByTextStaysText() {
            assertThat(call("[1] garbage", MediaType.TEXT_PLAIN).get("data")).isEqualTo("[1] garbage");
            server.reset();
            assertThat(call("{\"a\":1}\n{\"a\":2}", MediaType.TEXT_PLAIN).get("data"))
                    .isEqualTo("{\"a\":1}\n{\"a\":2}");
        }

        @Test
        @DisplayName("application/xhtml+xml is refused like text/html")
        void xhtmlStillFails() {
            Map<String, Object> result = call("<html/>", MediaType.parseMediaType("application/xhtml+xml"));

            assertThat(result.get("success")).isEqualTo(false);
        }

        @Test
        @DisplayName("application/yaml is still parsed by the YAML converter; x-yaml and text/yaml come back as text")
        void yamlVariants() {
            assertThat(call("a: 1\n", MediaType.parseMediaType("application/yaml")).get("data"))
                    .isEqualTo(Map.of("a", 1));
            server.reset();
            assertThat(call("a: 1\n", MediaType.parseMediaType("application/x-yaml")).get("data")).isEqualTo("a: 1\n");
            server.reset();
            assertThat(call("a: 1\n", MediaType.parseMediaType("text/yaml")).get("data")).isEqualTo("a: 1\n");
        }

        @Test
        @DisplayName("the shared RestTemplate is never modified: its converter list is the stock one after a CSV read")
        void sharedRestTemplateUntouched() {
            List<Class<?>> before = restTemplate.getMessageConverters().stream()
                    .<Class<?>>map(Object::getClass).toList();

            assertThat(call("a,b\n", MediaType.parseMediaType("text/csv")).get("data")).isEqualTo("a,b\n");

            assertThat(restTemplate.getMessageConverters().stream().<Class<?>>map(Object::getClass).toList())
                    .isEqualTo(before)
                    .isEqualTo(new RestTemplate().getMessageConverters().stream()
                            .<Class<?>>map(Object::getClass).toList());
        }
    }

    @Nested
    @DisplayName("A2: an endpoint that DECLARES a text answer takes an HTML body as its result")
    class DeclaredTextResponse {

        private MockRestServiceServer server;
        private HttpExecutionService service;

        @BeforeEach
        void wire() {
            RestTemplate restTemplate = new RestTemplate();
            server = MockRestServiceServer.bindTo(restTemplate).build();
            service = service(restTemplate);
            givenCredential("site", Map.of("api_key", "k", "other", "x"));
        }

        private Map<String, Object> typedCall(String responseType) {
            return typedCall(responseType, "<html><b>hi</b></html>", MediaType.TEXT_HTML);
        }

        private Map<String, Object> typedCall(String responseType, String body, MediaType type) {
            server.expect(requestTo(PUBLIC_BASE + "/page")).andRespond(withSuccess(body, type));
            return service.executeHttpCallTyped(api(PUBLIC_BASE), typed(tool("GET", "/page"), responseType),
                    params(Map.of()), null, "user1", "site", "tenant-1");
        }

        @Test
        @DisplayName("response.type=text with no Content-Type, or octet-stream, is read as text")
        void declaredTextWithoutOrBinaryContentType() {
            Map<String, Object> untyped = typedCall("text", "plain words", null);
            assertThat(untyped.get("success")).as(String.valueOf(untyped.get("error"))).isEqualTo(true);
            assertThat(untyped.get("data")).isEqualTo("plain words");

            server.reset();
            Map<String, Object> octet = typedCall("text", "more words", MediaType.APPLICATION_OCTET_STREAM);
            assertThat(octet.get("data")).isEqualTo("more words");
        }

        @Test
        @DisplayName("response.type=json with no Content-Type still fails as before")
        void declaredJsonWithoutContentTypeStillFails() {
            assertThat(typedCall("json", "plain words", null).get("success")).isEqualTo(false);
        }

        @Test
        @DisplayName("response.type=text: the HTML is the data")
        void declaredTextReadsHtml() {
            Map<String, Object> result = typedCall("text");

            assertThat(result.get("success")).as(String.valueOf(result.get("error"))).isEqualTo(true);
            assertThat(result.get("data")).isEqualTo("<html><b>hi</b></html>");
        }

        @Test
        @DisplayName("response.type=json: the same HTML stays the failure it always was")
        void declaredJsonStillFailsOnHtml() {
            Map<String, Object> result = typedCall("json");

            assertThat(result.get("success")).isEqualTo(false);
        }
    }

    @Nested
    @DisplayName("A2: what the fallback converter reads, and what it leaves alone")
    class FallbackConverterScope {

        @Test
        @DisplayName("textual non-JSON types are read; JSON, HTML, binary and wildcards are not")
        void textualDetection() {
            assertThat(TextualResponseBody.isTextualNonJson(MediaType.parseMediaType("text/csv"))).isTrue();
            assertThat(TextualResponseBody.isTextualNonJson(MediaType.parseMediaType("application/x-ndjson"))).isTrue();
            assertThat(TextualResponseBody.isTextualNonJson(MediaType.parseMediaType("application/atom+xml"))).isTrue();
            assertThat(TextualResponseBody.isTextualNonJson(MediaType.TEXT_HTML)).isFalse();
            assertThat(TextualResponseBody.isTextualNonJson(MediaType.APPLICATION_XHTML_XML)).isFalse();
            assertThat(TextualResponseBody.isTextualNonJson(MediaType.APPLICATION_JSON)).isFalse();
            assertThat(TextualResponseBody.isTextualNonJson(MediaType.parseMediaType("application/problem+json"))).isFalse();
            assertThat(TextualResponseBody.isTextualNonJson(MediaType.IMAGE_PNG)).isFalse();
            assertThat(TextualResponseBody.isTextualNonJson(MediaType.APPLICATION_OCTET_STREAM)).isFalse();
            assertThat(TextualResponseBody.isTextualNonJson(MediaType.APPLICATION_PDF)).isFalse();
            assertThat(TextualResponseBody.isTextualNonJson(MediaType.ALL)).isFalse();
            assertThat(TextualResponseBody.isTextualNonJson(null)).isFalse();
        }

        @Test
        @DisplayName("read() hands nothing back for HTML or binary bodies, so the caller keeps failing")
        void readRefusesHtmlAndBinary() {
            assertThat(TextualResponseBody.read("<html/>".getBytes(StandardCharsets.UTF_8), MediaType.TEXT_HTML)).isEmpty();
            assertThat(TextualResponseBody.read(new byte[] {1, 2}, MediaType.IMAGE_PNG)).isEmpty();
            assertThat(TextualResponseBody.read(new byte[] {1, 2}, null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("A6: what an event stream is read as")
    class EventStreamVariants {

        private Object read(String body) {
            return TextualResponseBody.jsonFromEventStream(body);
        }

        @Test
        @DisplayName("a notification before the reply: the LAST JSON-RPC response wins")
        void lastRpcResponseWins() {
            assertThat(read("data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\n"
                    + "data: {\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"ok\":1}}\n\n"))
                    .isEqualTo(Map.of("jsonrpc", "2.0", "id", 7, "result", Map.of("ok", 1)));
        }

        @Test
        @DisplayName("a JSON-RPC error is a response too")
        void rpcErrorIsResponse() {
            assertThat(read("data: {\"id\":1,\"error\":{\"code\":-32601}}\n\n"))
                    .isEqualTo(Map.of("id", 1, "error", Map.of("code", -32601)));
        }

        @Test
        @DisplayName("CRLF line endings, comment lines and empty-data events are handled")
        void crlfCommentsAndEmptyData() {
            String body = ": keep-alive\r\n\r\ndata:\r\n\r\nevent: message\r\ndata: {\"id\":2,\"result\":true}\r\n\r\n";

            assertThat(read(body)).isEqualTo(Map.of("id", 2, "result", true));
        }

        @Test
        @DisplayName("a multi-line data field is joined with a newline before parsing")
        void multiLineDataJoined() {
            assertThat(read("data: {\"a\":\ndata: 1}\n")).isEqualTo(Map.of("a", 1));
        }

        @Test
        @DisplayName("a single non-RPC JSON event is returned as its JSON")
        void singleJsonEvent() {
            assertThat(read("data: {\"a\":1}\n\n")).isEqualTo(Map.of("a", 1));
        }

        @Test
        @DisplayName("several non-response events, or non-JSON data, stay raw text")
        void otherwiseRawText() {
            String two = "data: {\"a\":1}\n\ndata: {\"b\":2}\n\n";
            assertThat(read(two)).isEqualTo(two);
            String text = "data: hello\n\n";
            assertThat(read(text)).isEqualTo(text);
        }
    }

    // ── A3: a host value pasted with its scheme and trailing slash ──────────

    @Nested
    @DisplayName("A3: a host credential value carrying a scheme, slashes or spaces still builds one URL")
    class HostValueFitting {

        @Mock private RestTemplate restTemplate;

        private URI sentUri(ApiEntity api, ApiToolEntity tool, String cred) {
            lenient().when(restTemplate.exchange(any(URI.class), any(HttpMethod.class), any(HttpEntity.class), eq(Object.class)))
                    .thenReturn(ResponseEntity.ok(Map.of("ok", true)));
            Map<String, Object> result = service(restTemplate)
                    .executeHttpCallWithCredentials(api, tool, params(Map.of()), null, "user1", cred);
            assertThat(result.get("success")).as(String.valueOf(result.get("error"))).isEqualTo(true);
            ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
            verify(restTemplate).exchange(uri.capture(), any(HttpMethod.class), any(HttpEntity.class), eq(Object.class));
            return uri.getValue();
        }

        @Test
        @DisplayName("REGRESSION Ghost: admin_domain 'https://company-bible.ghost.io/' no longer yields https://https://...//")
        void ghostSchemeAndSlashStripped() {
            givenCredential("ghost", Map.of("admin_domain", "https://company-bible.ghost.io/", "admin_api_key", "id:secret"));

            assertThat(sentUri(api("https://{admin_domain}/ghost/api"), tool("GET", "/admin/site/"), "ghost"))
                    .isEqualTo(URI.create("https://company-bible.ghost.io/ghost/api/admin/site/"));
        }

        @Test
        @DisplayName("a bare host value is sent exactly as before")
        void bareHostUnchanged() {
            givenCredential("ghost", Map.of("admin_domain", "company-bible.ghost.io", "admin_api_key", "id:secret"));

            assertThat(sentUri(api("https://{admin_domain}/ghost/api"), tool("GET", "/admin/site/"), "ghost"))
                    .isEqualTo(URI.create("https://company-bible.ghost.io/ghost/api/admin/site/"));
        }

        @Test
        @DisplayName("never a downgrade: http://{host} filled with 'https://host' goes out over https")
        void httpsValueUpgradesHttpTemplate() {
            givenCredential("coolify", Map.of("host", "https://coolify.example.org", "api_key", "k"));

            assertThat(sentUri(api("http://{host}:8000/api/v1"), tool("GET", "/applications"), "coolify"))
                    .isEqualTo(URI.create("https://coolify.example.org:8000/api/v1/applications"));
        }

        @Test
        @DisplayName("an https template stays https when the value says http")
        void httpsTemplateNeverDowngraded() {
            givenCredential("ghost", Map.of("admin_domain", "http://blog.example.org", "admin_api_key", "x"));

            assertThat(sentUri(api("https://{admin_domain}/ghost/api"), tool("GET", "/admin/site/"), "ghost"))
                    .isEqualTo(URI.create("https://blog.example.org/ghost/api/admin/site/"));
        }

        @Test
        @DisplayName("host followed by a port: 'h/' loses its slash (was http://h/:8000)")
        void hostBeforePortLosesSlash() {
            givenCredential("coolify", Map.of("host", "coolify.example.org/", "api_key", "k"));

            assertThat(sentUri(api("http://{host}:8000/api/v1"), tool("GET", "/applications"), "coolify"))
                    .isEqualTo(URI.create("http://coolify.example.org:8000/api/v1/applications"));
        }

        @Test
        @DisplayName("padded values are trimmed, in the host slot and as a leading URL")
        void paddedValuesTrimmed() {
            givenCredential("ghost", Map.of("admin_domain", "  https://blog.example.org/  ", "admin_api_key", "x"));
            assertThat(sentUri(api("https://{admin_domain}/ghost/api"), tool("GET", "/admin/site/"), "ghost"))
                    .isEqualTo(URI.create("https://blog.example.org/ghost/api/admin/site/"));

            assertThat(HttpExecutionService.fitUrlVariableValue("{instance_url}/api/v1", 0, 14,
                    " https://example.org/ ")).isEqualTo("https://example.org");
        }

        @Test
        @DisplayName("a leading-variable base URL keeps the value's own scheme and loses only the doubled slash")
        void leadingVariableKeepsScheme() {
            givenCredential("selfhosted", Map.of("instance_url", "http://example.org:8000/", "api_key", "k"));

            assertThat(sentUri(api("{instance_url}/api/v1"), tool("GET", "/applications"), "selfhosted"))
                    .isEqualTo(URI.create("http://example.org:8000/api/v1/applications"));
        }

        @Test
        @DisplayName("a value in a PATH slot keeps its trailing slash and its spaces (a token is opaque)")
        void pathSlotValueUntouched() {
            String template = "https://api.telegram.org/bot{token}/getMe";
            int start = template.indexOf("{token}");
            assertThat(HttpExecutionService.fitUrlVariableValue(template, start, start + 7, "abc/")).isEqualTo("abc/");
            assertThat(HttpExecutionService.fitUrlVariableValue(template, start, start + 7, " abc ")).isEqualTo(" abc ");
        }

        @Test
        @DisplayName("a subdomain slot: a bare value is kept, a pasted scheme and slash are dropped")
        void subdomainSlot() {
            String template = "https://{domain}.freshdesk.com/api/v2";
            assertThat(HttpExecutionService.fitUrlVariableValue(template, 8, 16, "acme")).isEqualTo("acme");
            assertThat(HttpExecutionService.fitUrlVariableValue(template, 8, 16, "https://acme/")).isEqualTo("acme");
        }

        @Test
        @DisplayName("REGRESSION Coolify: {your-coolify-host} is filled from the field the importer names your_coolify_host")
        void hyphenatedVariableFilledFromNormalizedField() {
            givenCredential("coolify", Map.of("your_coolify_host", "coolify.example.org", "api_key", "k"));

            assertThat(sentUri(api("http://{your-coolify-host}:8000/api/v1"), tool("GET", "/applications"), "coolify"))
                    .isEqualTo(URI.create("http://coolify.example.org:8000/api/v1/applications"));
        }

        @Test
        @DisplayName("a single-field credential still fills a PATH variable with its value (Twilio-style ids)")
        void singleFieldFillsPathVariable() {
            givenCredential("twilio", Map.of("account_sid", "AC123"));
            lenient().when(userCredentialService.getAccessToken("user1", "twilio")).thenReturn(Optional.of("AC123"));

            assertThat(sentUri(api("https://api.twilio.com/2010-04-01/Accounts/{AccountSid}"),
                    tool("GET", "/Calls.json"), "twilio"))
                    .isEqualTo(URI.create("https://api.twilio.com/2010-04-01/Accounts/AC123/Calls.json"));
        }

        @Test
        @DisplayName("an IPv6 literal host is kept whole")
        void ipv6HostKept() {
            givenCredential("ghost", Map.of("admin_domain", "https://[2001:db8::1]/", "admin_api_key", "x"));

            assertThat(sentUri(api("https://{admin_domain}/ghost/api"), tool("GET", "/admin/site/"), "ghost"))
                    .isEqualTo(URI.create("https://[2001:db8::1]/ghost/api/admin/site/"));
        }

        @Test
        @DisplayName("a host:port value keeps its port and loses the slash")
        void hostWithPortKept() {
            givenCredential("ghost", Map.of("admin_domain", "blog.example.org:8443/", "admin_api_key", "x"));

            assertThat(sentUri(api("https://{admin_domain}/ghost/api"), tool("GET", "/admin/site/"), "ghost"))
                    .isEqualTo(URI.create("https://blog.example.org:8443/ghost/api/admin/site/"));
        }

        @Test
        @DisplayName("a host value with a path keeps the path (a site served under a sub-path)")
        void hostWithPathKept() {
            givenCredential("ghost", Map.of("admin_domain", "https://example.org/blog/", "admin_api_key", "x"));

            assertThat(sentUri(api("https://{admin_domain}/ghost/api"), tool("GET", "/admin/site/"), "ghost"))
                    .isEqualTo(URI.create("https://example.org/blog/ghost/api/admin/site/"));
        }

        @Test
        @DisplayName("the typed path fills {your-coolify-host} from the your_coolify_host field too")
        void hyphenatedVariableOnTypedPath() {
            givenCredential("coolify", Map.of("your_coolify_host", "coolify.example.org", "api_key", "k"));
            when(restTemplate.exchange(any(URI.class), any(HttpMethod.class), any(HttpEntity.class), eq(Object.class)))
                    .thenReturn(ResponseEntity.ok(Map.of("ok", true)));

            Map<String, Object> result = service(restTemplate).executeHttpCallTyped(
                    api("http://{your-coolify-host}:8000/api/v1"), typed(tool("GET", "/applications"), "json"),
                    params(Map.of()), null, "user1", "coolify", "tenant-1");

            assertThat(result.get("success")).as(String.valueOf(result.get("error"))).isEqualTo(true);
            verify(restTemplate).exchange(eq(URI.create("http://coolify.example.org:8000/api/v1/applications")),
                    eq(HttpMethod.GET), any(HttpEntity.class), eq(Object.class));
        }

        @Test
        @DisplayName("a mixed-case 'HTTPS://' value is recognised: scheme stripped, template upgraded")
        void mixedCaseSchemeValue() {
            givenCredential("coolify", Map.of("host", "HTTPS://Coolify.Example.org/", "api_key", "k"));

            assertThat(sentUri(api("http://{host}:8000/api/v1"), tool("GET", "/applications"), "coolify"))
                    .isEqualTo(URI.create("https://Coolify.Example.org:8000/api/v1/applications"));
        }

        @Test
        @DisplayName("two placeholders: the host one upgrades the scheme, the path one is left as stored")
        void twoPlaceholdersWithUpgrade() {
            givenCredential("svc", Map.of("host", "https://x.example.org", "tenant", "t1", "api_key", "k"));

            assertThat(sentUri(api("http://{host}/{tenant}/api"), tool("GET", "/items"), "svc"))
                    .isEqualTo(URI.create("https://x.example.org/t1/api/items"));
        }

        @Test
        @DisplayName("the 401 refresh-retry path (String URL) sends the same fitted URL")
        void refreshRetryUsesFittedUrl() {
            givenCredential("ghost", Map.of("admin_domain", "https://company-bible.ghost.io/", "admin_api_key", "id:secret"));
            when(restTemplate.exchange(any(URI.class), any(HttpMethod.class), any(HttpEntity.class), eq(Object.class)))
                    .thenThrow(HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized",
                            new HttpHeaders(), new byte[0], StandardCharsets.UTF_8));
            when(userCredentialService.forceRefreshAndGetToken("user1", "ghost")).thenReturn(Optional.of("fresh"));
            when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(Object.class)))
                    .thenReturn(ResponseEntity.ok(Map.of("ok", true)));

            Map<String, Object> result = service(restTemplate).executeHttpCallWithCredentials(
                    api("https://{admin_domain}/ghost/api"), tool("GET", "/admin/site/"), params(Map.of()),
                    null, "user1", "ghost");

            assertThat(result.get("success")).as(String.valueOf(result.get("error"))).isEqualTo(true);
            verify(restTemplate).exchange(eq("https://company-bible.ghost.io/ghost/api/admin/site/"),
                    eq(HttpMethod.GET), any(HttpEntity.class), eq(Object.class));
        }
    }

    // ── A4: an unfilled placeholder is refused before sending ───────────────

    @Nested
    @DisplayName("A4: a placeholder left in the URL is refused before anything is sent, naming it")
    class UnresolvedPlaceholder {

        @Mock private RestTemplate restTemplate;

        private String credentialedError(String baseUrl, ApiToolEntity tool, String cred, Map<String, String> params) {
            Map<String, Object> result = service(restTemplate).executeHttpCallWithCredentials(
                    api(baseUrl), tool, params(params), null, "user1", cred);
            assertThat(result.get("success")).isEqualTo(false);
            verifyNoInteractions(restTemplate);
            return String.valueOf(result.get("error"));
        }

        @Test
        @DisplayName("REGRESSION Coolify, connection EXISTS: names the value and asks for require with force=true")
        void coolifyExistingConnectionAsksForForce() {
            givenCredential("coolify", Map.of("api_key", "k", "note", "x"));
            givenConnected("coolify");

            String error = credentialedError("http://{your-coolify-host}:8000/api/v1",
                    tool("GET", "/applications"), "coolify", Map.of());

            assertThat(error)
                    .contains("the value your-coolify-host")
                    .contains("credential(action='require', services=['coolify'], reason='<why you need it>', force=true)")
                    .contains("Nothing was sent to the provider")
                    .doesNotContain("Illegal character");
        }

        @Test
        @DisplayName("Coolify, NOTHING connected: asks for a plain require, and says what to do if it already exists")
        void coolifyNoConnectionAsksForRequire() {
            givenCredential("coolify", Map.of("api_key", "k", "note", "x"));

            String error = credentialedError("http://{your-coolify-host}:8000/api/v1",
                    tool("GET", "/applications"), "coolify", Map.of());

            assertThat(error)
                    .contains("credential(action='require', services=['coolify'], reason='<why you need it>')")
                    .contains("If that answers that the connection already exists, call it again with force=true")
                    .contains("none is connected");
        }

        @Test
        @DisplayName("several account values: named together, plural wording")
        void pluralAccountValues() {
            givenCredential("svc", Map.of("api_key", "k", "note", "x"));
            givenConnected("svc");

            String error = credentialedError("https://{region}.{tenant}.example.org",
                    tool("GET", "/items"), "svc", Map.of());

            assertThat(error).contains("the values region, tenant").contains("does not include them")
                    .contains("includes them");
        }

        @Test
        @DisplayName("a credential variable in the endpoint path that is not a declared parameter is an account value")
        void undeclaredEndpointVariableIsAccountValue() {
            givenCredential("tg", Map.of("api_key", "k", "note", "x"));
            givenConnected("tg");

            String error = credentialedError(PUBLIC_BASE, tool("GET", "/accounts/{account_sid}/calls"), "tg", Map.of());

            assertThat(error).contains("the value account_sid from the user's tg connection")
                    .doesNotContain("pass it in params");
        }

        @Test
        @DisplayName("mixed: an account value AND a brace from a value, both explained, the value never quoted")
        void mixedMessage() {
            givenCredential("svc", Map.of("api_key", "k", "note", "x"));
            givenConnected("svc");

            String error = credentialedError("https://{tenant}.example.org",
                    tool("GET", "/items/{id}", param("id", "path", null)), "svc",
                    Map.of("id", "{\"secret\":1}"));

            assertThat(error).contains("the value tenant").contains("remove the braces")
                    .doesNotContain("secret");
        }

        @Test
        @DisplayName("a brace inside an agent's value alone: refused without quoting the value back")
        void braceInValueNotEchoed() {
            givenCredential("svc", Map.of("api_key", "k", "note", "x"));

            String error = credentialedError(PUBLIC_BASE, tool("GET", "/items/{id}", param("id", "path", null)),
                    "svc", Map.of("id", "{\"secret\":1}"));

            assertThat(error).contains("remove the braces").doesNotContain("secret");
        }

        @Test
        @DisplayName("the credential-less path names the value and never offers a connection it cannot use")
        void legacyPathRefused() {
            Map<String, Object> result = service(restTemplate).executeHttpCall(
                    api("http://{your-coolify-host}:8000/api/v1"), tool("GET", "/applications"), params(Map.of()));

            String error = String.valueOf(result.get("error"));
            assertThat(result.get("success")).isEqualTo(false);
            assertThat(error)
                    .contains("the value your-coolify-host")
                    .contains("tell the user this integration is missing your-coolify-host")
                    .doesNotContain("credential(action=")
                    .doesNotContain("on the connection");
            verifyNoInteractions(restTemplate);
        }

        @Test
        @DisplayName("typed path refuses the same way")
        void typedPathRefused() {
            givenCredential("coolify", Map.of("api_key", "k", "note", "x"));
            givenConnected("coolify");

            Map<String, Object> result = service(restTemplate).executeHttpCallTyped(
                    api("http://{your-coolify-host}:8000/api/v1"), typed(tool("GET", "/applications"), "json"),
                    params(Map.of()), null, "user1", "coolify", "tenant-1");

            assertThat(result.get("success")).isEqualTo(false);
            assertThat(String.valueOf(result.get("error")))
                    .contains("your-coolify-host")
                    .contains("force=true")
                    .contains("Nothing was sent to the provider")
                    .doesNotContain("Illegal character");
            verifyNoInteractions(restTemplate);
        }

        @Test
        @DisplayName("a declared path parameter still in braces is named as a parameter to pass")
        void declaredPathParameterNamed() {
            ApiToolEntity t = tool("GET", "/items/{id}", param("id", "path", "x"));

            assertThatThrownBy(() -> service(restTemplate).requireResolvedUrl(
                    PUBLIC_BASE + "/items/{id}", api(PUBLIC_BASE), t, "svc", true))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("the path parameter id: pass it in params")
                    .hasMessageContaining("Nothing was sent to the provider");
        }

        @Test
        @DisplayName("a single-field credential never fills a HOST with its secret: refused, the key never sent or quoted")
        void singleFieldSecretNeverFillsHost() {
            givenCredential("coolify", Map.of("api_key", "sk-secret-123"));
            givenConnected("coolify");

            String error = credentialedError("http://{host}:8000/api/v1", tool("GET", "/applications"),
                    "coolify", Map.of());

            assertThat(error).contains("the value host").contains("cannot take host yet")
                    .doesNotContain("sk-secret-123");
        }

        @Test
        @DisplayName("platform credential only (no user connection): the plain require call, not force=true")
        void platformCredentialIsNotAUserConnection() {
            ApiEntity coolify = api("http://{your-coolify-host}:8000/api/v1");
            coolify.setPlatformCredentialName("coolify_platform");
            lenient().when(userCredentialService.getAccessToken(
                    com.apimarketplace.catalog.service.credential.PlatformTenant.ID, "coolify_platform"))
                    .thenReturn(Optional.of("platform-key"));
            givenCredential("coolify", Map.of("api_key", "k", "note", "x"));

            Map<String, Object> result = service(restTemplate).executeHttpCallWithCredentials(
                    coolify, tool("GET", "/applications"), params(Map.of()), null, "user1", "coolify");

            String error = String.valueOf(result.get("error"));
            assertThat(result.get("success")).isEqualTo(false);
            assertThat(error).contains("none is connected")
                    .contains("reason='<why you need it>')")
                    .doesNotContain("reason='<why you need it>', force=true)");
            verifyNoInteractions(restTemplate);
        }

        @Test
        @DisplayName("a declared path parameter named by the BASE URL (azure_devops {organization}) is one the agent passes")
        void declaredPathParamInBaseUrl() {
            givenCredential("azure_devops", Map.of("api_key", "k", "note", "x"));
            givenConnected("azure_devops");

            String error = credentialedError("https://dev.azure.com/{organization}",
                    tool("GET", "/_apis/projects", param("organization", "path", null)), "azure_devops", Map.of());

            assertThat(error)
                    .contains("the path parameter organization: pass it in params, or have the user add it to the"
                            + " azure_devops connection")
                    .doesNotContain("You cannot supply");
        }

        @Test
        @DisplayName("the same declared base-URL parameter on the credential-less path: pass it in params, nothing else")
        void declaredPathParamInBaseUrlWithoutConnection() {
            ApiToolEntity t = tool("GET", "/_apis/projects", param("organization", "path", null));

            assertThatThrownBy(() -> service(restTemplate).requireResolvedUrl(
                    "https://dev.azure.com/{organization}/_apis/projects", api("https://dev.azure.com/{organization}"),
                    t, null, false))
                    .hasMessageContaining("the path parameter organization: pass it in params.")
                    .hasMessageNotContaining("connection");
        }

        @Test
        @DisplayName("a fully resolved URL (encoded braces included) is not touched by the check")
        void resolvedUrlPasses() {
            assertThatCode(() -> service(restTemplate).requireResolvedUrl(
                    "https://api.example.org/v1/items?q=%7Bx%7D", api(PUBLIC_BASE), tool("GET", "/items"), "svc", true))
                    .doesNotThrowAnyException();
        }
    }
}
