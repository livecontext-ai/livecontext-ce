package com.apimarketplace.catalog.service.http;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.apimarketplace.catalog.domain.ApiEntity;
import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.domain.ApiToolParameterEntity;
import com.apimarketplace.catalog.repository.ApiToolParameterRepository;
import com.apimarketplace.catalog.service.UserCredentialService;
import com.apimarketplace.common.security.CredentialEncryptionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.ArrayList;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression, 2026-09-25: a {@code body_field} integration (Authorize.Net, Personio,
 * LibreTranslate) receives its secret as an ordinary parameter, resolved upstream from
 * {@code {{credential.client_secret}}}, and the INFO lines "Request body", "Parameters JSON",
 * "Available parameter" and "Replaced {x} with" printed it in clear. The value must still reach
 * the provider unchanged; only what is logged changes.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HttpExecutionService - parameter and body VALUES never reach the log")
class HttpExecutionServiceParamValueLogTest {

    private static final String SECRET = "sk_live_FAKE_body_field_secret_987";
    private static final UUID TOOL = UUID.randomUUID();

    @Mock private ApiToolParameterRepository apiToolParameterRepository;
    @Mock private UserCredentialService userCredentialService;
    @Mock private CredentialEncryptionService encryptionService;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private RestTemplate restTemplate;

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpExecutionService service;
    private ListAppender<ILoggingEvent> logs;
    private Logger root;
    private Logger serviceLogger;
    private ch.qos.logback.classic.Level previousLevel;

    @BeforeEach
    void setUp() {
        lenient().when(jdbcTemplate.queryForList(anyString(), (Object) any())).thenReturn(new ArrayList<>());
        lenient().when(jdbcTemplate.queryForList(anyString(), (Object) any(), (Object) any())).thenReturn(new ArrayList<>());
        lenient().when(userCredentialService.getAccessTokenInfo(anyString(), anyString())).thenReturn(Optional.empty());
        lenient().when(userCredentialService.getAccessToken(anyString(), anyString())).thenReturn(Optional.empty());
        service = new HttpExecutionService(apiToolParameterRepository, userCredentialService, encryptionService,
                mapper, jdbcTemplate, restTemplate, new ErrorPolicyEngine(2, 10_000L));
        CredentialModeContext.clear();
        root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);
        // DEBUG on purpose: the DEBUG lines printed values too, and a DEBUG session on a live
        // service must not be the moment a secret lands in the log.
        serviceLogger = (Logger) LoggerFactory.getLogger(HttpExecutionService.class);
        previousLevel = serviceLogger.getLevel();
        serviceLogger.setLevel(ch.qos.logback.classic.Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.setLevel(previousLevel);
        root.detachAppender(logs);
    }

    private static ApiToolParameterEntity param(String name, String type) {
        ApiToolParameterEntity p = new ApiToolParameterEntity();
        p.setId(UUID.randomUUID());
        p.setApiToolId(TOOL);
        p.setName(name);
        p.setParameterType(type);
        p.setDataType("string");
        return p;
    }

    private static ApiEntity api() {
        ApiEntity a = new ApiEntity();
        a.setBaseUrl("https://api.clickup.com");
        return a;
    }

    private static ApiToolEntity tool(String endpoint) {
        ApiToolEntity t = new ApiToolEntity();
        t.setId(TOOL);
        t.setMethod("POST");
        t.setEndpoint(endpoint);
        return t;
    }

    private String everythingLogged() {
        StringBuilder sb = new StringBuilder();
        logs.list.forEach(e -> sb.append(e.getFormattedMessage()).append('\n'));
        return sb.toString();
    }

    private JsonNode params(String json) throws Exception {
        return mapper.readTree(json);
    }

    @Test
    @DisplayName("Regression: a body_field secret is SENT in the body but appears in no log line")
    void bodySecretSentButNotLogged() throws Exception {
        when(apiToolParameterRepository.findByApiToolId(TOOL))
                .thenReturn(List.of(param("client_id", "body"), param("client_secret", "body")));
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(Object.class)))
                .thenReturn(ResponseEntity.ok(Map.of("ok", true)));

        service.executeHttpCallWithCredentials(api(), tool("/v1/auth"),
                params("[{\"client_id\":\"cid-1\"},{\"client_secret\":\"" + SECRET + "\"}]"),
                Set.of("client_id", "client_secret"), "user1", "authorizenet");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpEntity<Object>> sent = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(any(URI.class), eq(HttpMethod.POST), sent.capture(), eq(Object.class));
        assertThat(String.valueOf(sent.getValue().getBody())).contains(SECRET);
        assertThat(everythingLogged())
                .doesNotContain(SECRET)
                .contains("client_secret=<string " + SECRET.length() + ">")
                // The DEBUG per-field line ran and is shaped as well.
                .contains("Added body param: client_secret=<string " + SECRET.length() + ">");
    }

    @Test
    @DisplayName("Regression: a path parameter's value is substituted, and the parameter dumps name it without its value")
    void pathValueSubstitutedButNotDumped() throws Exception {
        when(apiToolParameterRepository.findByApiToolId(TOOL)).thenReturn(List.of(param("account", "path")));
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(Object.class)))
                .thenReturn(ResponseEntity.ok(Map.of("ok", true)));

        service.executeHttpCallWithCredentials(api(), tool("/v1/accounts/{account}/charge"),
                params("[{\"account\":\"" + SECRET + "\"}]"), Set.of("account"), "user1", "authorizenet");

        verify(restTemplate).exchange(eq(URI.create("https://api.clickup.com/v1/accounts/" + SECRET + "/charge")),
                eq(HttpMethod.POST), any(HttpEntity.class), eq(Object.class));
        // The URL lines keep path values on purpose: they are resource identifiers and the URL is
        // what says which endpoint was called (a credential path variable is masked there by
        // CredentialUrlScrubber). The parameter dumps are what carried body_field secrets.
        String dumps = logs.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("Parameters JSON") || m.contains("Available parameter")
                        || m.contains("Replaced {") || m.contains("Request body"))
                .collect(java.util.stream.Collectors.joining(" | "));
        assertThat(dumps).contains("{{account}} with <string " + SECRET.length() + ">")
                .doesNotContain(SECRET);
    }

    @Test
    @DisplayName("The credential-less path (executeHttpCall) logs shapes too")
    void credentialLessPathLogsShapes() throws Exception {
        when(apiToolParameterRepository.findByApiToolId(TOOL)).thenReturn(List.of(param("client_secret", "body")));
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Object.class)))
                .thenReturn(ResponseEntity.ok(Map.of("ok", true)));

        service.executeHttpCall(api(), tool("/v1/auth"),
                params("[{\"client_secret\":\"" + SECRET + "\"}]"), Set.of("client_secret"));

        assertThat(everythingLogged()).doesNotContain(SECRET)
                .contains("Parameters before filtering: [{client_secret=<string " + SECRET.length() + ">}]")
                .contains("Request body: {client_secret=<string " + SECRET.length() + ">}");
    }
}
