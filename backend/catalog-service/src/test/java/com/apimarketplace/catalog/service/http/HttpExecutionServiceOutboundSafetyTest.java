package com.apimarketplace.catalog.service.http;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.apimarketplace.catalog.domain.ApiEntity;
import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.repository.ApiToolParameterRepository;
import com.apimarketplace.catalog.service.UserCredentialService;
import com.apimarketplace.common.security.CredentialEncryptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CASA readiness regressions for the catalog execution path.
 *
 * <ul>
 *   <li>LC-006: the typed path reached {@code restTemplate.exchange} with no SSRF check.</li>
 *   <li>LC-007: the legacy path checked the url BEFORE credential template substitution, so a
 *       placeholder host ({@code http://{h}:8083}) passed and was then filled in from the
 *       caller's own credential data.</li>
 *   <li>LC-010: the final url (query string, injected credential) and the request parameters were
 *       logged at INFO.</li>
 * </ul>
 *
 * <p>Every target here is an IP literal, so the real validator runs with no DNS.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("HttpExecutionService - outbound SSRF check and log redaction (LC-006, LC-007, LC-010)")
class HttpExecutionServiceOutboundSafetyTest {

    @Mock private ApiToolParameterRepository apiToolParameterRepository;
    @Mock private UserCredentialService userCredentialService;
    @Mock private CredentialEncryptionService encryptionService;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private RestTemplate restTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpExecutionService service;

    @BeforeEach
    void setUp() {
        lenient().when(apiToolParameterRepository.findByApiToolId(any())).thenReturn(List.of());
        service = new HttpExecutionService(
                apiToolParameterRepository, userCredentialService, encryptionService,
                objectMapper, jdbcTemplate, restTemplate, new ErrorPolicyEngine());
    }

    private static ApiEntity api(String baseUrl) {
        ApiEntity api = new ApiEntity();
        api.setId(UUID.randomUUID());
        api.setBaseUrl(baseUrl);
        api.setSource("custom");
        return api;
    }

    private static ApiToolEntity tool(String method, String endpoint, String executionSpec) {
        ApiToolEntity t = new ApiToolEntity();
        t.setId(UUID.randomUUID());
        t.setMethod(method);
        t.setEndpoint(endpoint);
        t.setExecutionSpec(executionSpec);
        return t;
    }

    @Nested
    @DisplayName("LC-006: typed path")
    class TypedPath {

        @Test
        @DisplayName("a fixed-host custom API pointing into the cluster never reaches the wire")
        void typedPathRefusesInternalTarget() {
            ApiToolEntity tool = tool("GET", "/api/internal/credentials/all",
                    "{\"mode\":\"sync\",\"request\":{\"bodyType\":\"json\"},\"response\":{\"type\":\"json\"}}");

            Map<String, Object> result = service.executeHttpCallTyped(
                    api("http://10.0.0.5:8083"), tool, objectMapper.createArrayNode(),
                    Set.of(), "user-1", null, "user-1");

            assertThat(result.get("success")).isEqualTo(false);
            assertThat(String.valueOf(result.get("error"))).contains("private/internal");
            verify(restTemplate, never()).exchange(any(URI.class), any(HttpMethod.class), any(), eq(Object.class));
        }

        @Test
        @DisplayName("the binary branch is guarded too")
        void binaryBranchRefusesInternalTarget() {
            ApiToolEntity tool = tool("GET", "/latest/meta-data",
                    "{\"mode\":\"sync\",\"request\":{\"bodyType\":\"json\"},\"response\":{\"type\":\"binary\"}}");

            Map<String, Object> result = service.executeHttpCallTyped(
                    api("http://169.254.169.254"), tool, objectMapper.createArrayNode(),
                    Set.of(), "user-1", null, "user-1");

            assertThat(result.get("success")).isEqualTo(false);
            verify(restTemplate, never()).exchange(any(URI.class), any(HttpMethod.class), any(), eq(byte[].class));
        }

        @Test
        @DisplayName("a public target is still called")
        void typedPathAllowsPublicTarget() {
            ApiToolEntity tool = tool("GET", "/v1/things",
                    "{\"mode\":\"sync\",\"request\":{\"bodyType\":\"json\"},\"response\":{\"type\":\"json\"}}");
            when(restTemplate.exchange(any(URI.class), any(HttpMethod.class), any(), eq(Object.class)))
                    .thenReturn(new ResponseEntity<>(Map.of("ok", true), HttpStatus.OK));

            Map<String, Object> result = service.executeHttpCallTyped(
                    api("http://93.184.216.34"), tool, objectMapper.createArrayNode(),
                    Set.of(), "user-1", null, "user-1");

            assertThat(result.get("success")).isEqualTo(true);
        }
    }

    @Nested
    @DisplayName("LC-007: the FINAL url is validated, after credential substitution")
    class PlaceholderHost {

        @Test
        @DisplayName("a placeholder host filled from the caller's credential cannot point into the cluster")
        void placeholderHostFilledFromCredentialIsRefused() {
            when(userCredentialService.getCredentialDataMap("user-1", "acme"))
                    .thenReturn(Map.of("h", "127.0.0.1"));
            ApiToolEntity tool = tool("GET", "/api/internal/credentials/all", null);

            Map<String, Object> result = service.executeHttpCallWithCredentials(
                    api("http://{h}:8083"), tool, objectMapper.createArrayNode(), Set.of(), "user-1", "acme");

            assertThat(result.get("success")).isEqualTo(false);
            verify(restTemplate, never()).exchange(any(URI.class), any(HttpMethod.class), any(), eq(Object.class));
            verify(restTemplate, never()).exchange(anyString(), any(HttpMethod.class), any(), eq(Object.class));
        }

        @Test
        @DisplayName("a placeholder host filled with a public value still works")
        void placeholderHostFilledWithPublicValueWorks() {
            when(userCredentialService.getCredentialDataMap("user-1", "acme"))
                    .thenReturn(Map.of("h", "93.184.216.34"));
            when(restTemplate.exchange(any(URI.class), any(HttpMethod.class), any(), eq(Object.class)))
                    .thenReturn(new ResponseEntity<>(Map.of("ok", true), HttpStatus.OK));
            ApiToolEntity tool = tool("GET", "/v1/things", null);

            Map<String, Object> result = service.executeHttpCallWithCredentials(
                    api("https://{h}"), tool, objectMapper.createArrayNode(), Set.of(), "user-1", "acme");

            assertThat(result.get("success")).isEqualTo(true);
        }
    }

    @Nested
    @DisplayName("LC-002 item 3: a host-bound credential only reaches the bound host, on the FINAL url")
    class HostBinding {

        @AfterEach
        void clear() {
            CredentialHostBinding.clear();
        }

        private void bindTo(String host) {
            CredentialHostBinding.set(new CredentialHostBinding.Binding("shipped", Set.of(host), Set.of()));
        }

        @Test
        @DisplayName("refused: a placeholder host filled from the caller's credential leaves the bound host")
        void substitutedHostOutsideTheBindingIsRefused() {
            bindTo("93.184.216.34");
            when(userCredentialService.getCredentialDataMap("user-1", "shipped"))
                    .thenReturn(Map.of("h", "93.184.216.35"));

            Map<String, Object> result = service.executeHttpCallWithCredentials(
                    api("https://{h}"), tool("GET", "/v1", null), objectMapper.createArrayNode(), Set.of(),
                    "user-1", "shipped");

            assertThat(result.get("success")).isEqualTo(false);
            assertThat(String.valueOf(result.get("error"))).contains("credential_key_conflict");
            verify(restTemplate, never()).exchange(any(URI.class), any(HttpMethod.class), any(), eq(Object.class));
        }

        @Test
        @DisplayName("allowed: https to the bound host")
        void boundHostIsCalled() {
            bindTo("93.184.216.34");
            when(restTemplate.exchange(any(URI.class), any(HttpMethod.class), any(), eq(Object.class)))
                    .thenReturn(new ResponseEntity<>(Map.of("ok", true), HttpStatus.OK));

            Map<String, Object> result = service.executeHttpCallWithCredentials(
                    api("https://93.184.216.34"), tool("GET", "/v1", null), objectMapper.createArrayNode(),
                    Set.of(), "user-1", "shipped");

            assertThat(result.get("success")).isEqualTo(true);
        }

        @Test
        @DisplayName("refused: plain http to the bound host")
        void httpToBoundHostIsRefused() {
            bindTo("93.184.216.34");
            Map<String, Object> result = service.executeHttpCallTyped(
                    api("http://93.184.216.34"), tool("GET", "/v1",
                            "{\"mode\":\"sync\",\"request\":{\"bodyType\":\"json\"},\"response\":{\"type\":\"binary\"}}"),
                    objectMapper.createArrayNode(), Set.of(), "user-1", null, "user-1");
            assertThat(result.get("success")).isEqualTo(false);
            verify(restTemplate, never()).exchange(any(URI.class), any(HttpMethod.class), any(), eq(byte[].class));
        }
    }

    @Test
    @DisplayName("item 9: the legacy path hands RestTemplate the validated URI object, never a String")
    void legacyPathPassesTheValidatedUri() {
        when(restTemplate.exchange(any(URI.class), any(HttpMethod.class), any(), eq(Object.class)))
                .thenReturn(new ResponseEntity<>(Map.of("ok", true), HttpStatus.OK));

        service.executeHttpCall(api("http://93.184.216.34"), tool("GET", "/v1", null),
                objectMapper.createArrayNode(), Set.of());

        verify(restTemplate).exchange(eq(URI.create("http://93.184.216.34/v1")), any(HttpMethod.class), any(), eq(Object.class));
        verify(restTemplate, never()).exchange(anyString(), any(HttpMethod.class), any(), eq(Object.class));
    }

    @Nested
    @DisplayName("LC-010: no query string, credential or parameter value in the logs")
    class LogRedaction {

        private ListAppender<ILoggingEvent> appender;
        private Logger logger;
        private Level previousLevel;

        @BeforeEach
        void attach() {
            logger = (Logger) LoggerFactory.getLogger(HttpExecutionService.class);
            previousLevel = logger.getLevel();
            logger.setLevel(Level.DEBUG); // redaction must hold even at DEBUG
            appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
        }

        @AfterEach
        void detach() {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
        }

        private String allLogs() {
            return appender.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.joining("\n"));
        }

        @Test
        @DisplayName("a credential substituted into the query and a parameter value never reach the log")
        void secretsNeverLogged() {
            when(userCredentialService.getCredentialDataMap("user-1", "acme"))
                    .thenReturn(Map.of("api_key", "sk-SECRET-CRED-123"));
            when(restTemplate.exchange(any(URI.class), any(HttpMethod.class), any(), eq(Object.class)))
                    .thenThrow(new ResourceAccessException(
                            "I/O error on GET request for \"http://93.184.216.34/search?key=sk-SECRET-CRED-123&q=secret-mail-query\""));
            ApiToolEntity tool = tool("POST", "/search?key={api_key}", null);
            ArrayNode params = objectMapper.createArrayNode();
            params.add(objectMapper.createObjectNode().put("note", "BODY-SECRET-VALUE"));

            Map<String, Object> result = service.executeHttpCallWithCredentials(
                    api("http://93.184.216.34"), tool, params, Set.of("note"), "user-1", "acme");

            assertThat(result.get("success")).isEqualTo(false);
            String logs = allLogs();
            assertThat(appender.list).as("the call must actually have logged").isNotEmpty();
            assertThat(logs).doesNotContain("sk-SECRET-CRED-123");
            assertThat(logs).doesNotContain("secret-mail-query");
            assertThat(logs).doesNotContain("BODY-SECRET-VALUE");
            // The host and the endpoint template stay, so the line is still useful.
            assertThat(logs).contains("93.184.216.34/search");
        }

        @Test
        @DisplayName("the legacy no-credential path logs parameter NAMES only")
        void legacyPathLogsNamesOnly() {
            when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(), eq(Object.class)))
                    .thenReturn(new ResponseEntity<>(Map.of("ok", true), HttpStatus.OK));
            ApiToolEntity tool = tool("POST", "/v1/things", null);
            ArrayNode params = objectMapper.createArrayNode();
            params.add(objectMapper.createObjectNode().put("note", "BODY-SECRET-VALUE"));

            service.executeHttpCall(api("http://93.184.216.34"), tool, params, Set.of("note"));

            String logs = allLogs();
            assertThat(logs).doesNotContain("BODY-SECRET-VALUE");
            assertThat(logs).contains("note");
        }
    }

    @Test
    @DisplayName("redactForLog strips every query string and caps the text")
    void redactForLogStripsQueries() {
        String redacted = HttpExecutionService.redactForLog(
                "failed: https://x.example.com/a?token=abc and http://y.example.com/b?c=d");
        assertThat(redacted).doesNotContain("token=abc").doesNotContain("c=d")
                .contains("https://x.example.com/a?<redacted>");
        assertThat(HttpExecutionService.redactForLog("z".repeat(5000)).length()).isLessThan(400);
    }
}
