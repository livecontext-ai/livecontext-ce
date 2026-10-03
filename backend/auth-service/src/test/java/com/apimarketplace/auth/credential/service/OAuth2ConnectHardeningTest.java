package com.apimarketplace.auth.credential.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.apimarketplace.auth.credential.domain.CredentialModels.Credential;
import com.apimarketplace.auth.credential.domain.CredentialModels.CredentialEnvironment;
import com.apimarketplace.auth.credential.domain.CredentialModels.CredentialStatus;
import com.apimarketplace.auth.credential.domain.CredentialModels.CredentialType;
import com.apimarketplace.auth.credential.domain.OAuth2Models.OAuth2InitiateRequest;
import com.apimarketplace.auth.credential.domain.OAuth2Models.OAuth2State;
import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.AuthType;
import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.PlatformCredential;
import com.apimarketplace.auth.credential.util.OAuth2ReturnPath;
import com.apimarketplace.common.security.CredentialEncryptionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The OAuth2 connect flow's CASA controls, exercised through the real {@link OAuth2Service}:
 * browser binding (LC-005), state protection and atomic consume (LC-068, LC-089), return-path
 * sanitising (LC-023), minimum scope (LC-072), BYOK/host-var SSRF (LC-052) and the connect
 * audit event (LC-058).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OAuth2 connect hardening (CASA batch B1)")
class OAuth2ConnectHardeningTest {

    private static final String USER = "user-1";
    private static final String ORG = "org-1";
    private static final String BINDING = "binding-value-held-by-the-initiating-browser";
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());

    private static final String TEMPLATE = """
            {
              "id": "tmpl-drive", "credential_name": "google_drive", "icon_slug": "google_drive",
              "display_name": "Google Drive", "auth_type": "oauth2",
              "metadata": { "oauth2Config": {
                "authorizationUrl": "https://accounts.google.com/o/oauth2/v2/auth",
                "tokenUrl": "https://oauth2.googleapis.com/token",
                "revokeUrl": "https://oauth2.googleapis.com/revoke",
                "scopes": ["https://www.googleapis.com/auth/drive.file",
                           "https://www.googleapis.com/auth/drive.readonly"],
                "pkce": true
              } }
            }
            """;

    @Mock private CredentialService credentialService;
    @Mock private PlatformCredentialService platformCredentialService;
    @Mock private CredentialEncryptionService encryptionService;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private CredentialAuditRecorder auditRecorder;

    private OAuth2Service service;
    private RestTemplate tokenClient;

    @BeforeEach
    void setUp() {
        service = new OAuth2Service(credentialService, platformCredentialService, encryptionService,
                redisTemplate, "http://localhost:8081", JSON, new OAuth2Engine(), new PkceService(),
                new com.apimarketplace.auth.credential.service.oauth2.refresh.RefreshErrorClassifier(),
                new com.apimarketplace.auth.credential.service.oauth2.refresh.RefreshBackoff(),
                new com.apimarketplace.auth.credential.metrics.OAuth2RefreshMetrics(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                        mock(com.apimarketplace.auth.credential.repository.CredentialRepository.class)),
                org.springframework.web.reactive.function.client.WebClient.builder());
        ReflectionTestUtils.setField(service, "callbackUrl", "https://livecontext.ai/api/credentials/oauth2/callback");
        ReflectionTestUtils.setField(service, "frontendUrl", "https://livecontext.ai");
        ReflectionTestUtils.setField(service, "auditRecorder", auditRecorder);
        tokenClient = mock(RestTemplate.class);
        ReflectionTestUtils.setField(service, "restTemplate", tokenClient);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        // Reversible fake cipher so a test can tell ciphertext from plaintext in the blob.
        lenient().when(encryptionService.encrypt(anyString())).thenAnswer(i -> "ENC(" + i.getArgument(0) + ")");
        lenient().when(encryptionService.decrypt(anyString())).thenAnswer(i -> {
            String v = i.getArgument(0);
            return v.startsWith("ENC(") ? v.substring(4, v.length() - 1) : v;
        });
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private void stubCatalog(String json) throws Exception {
        JsonNode template = json == null ? null : JSON.readTree(json);
        WebClient wc = mock(WebClient.class);
        var uriSpec = mock(WebClient.RequestHeadersUriSpec.class);
        var headersSpec = mock(WebClient.RequestHeadersSpec.class);
        var responseSpec = mock(WebClient.ResponseSpec.class);
        lenient().when(wc.get()).thenReturn(uriSpec);
        lenient().when(uriSpec.uri(anyString(), any(Object[].class))).thenReturn(headersSpec);
        lenient().when(headersSpec.retrieve()).thenReturn(responseSpec);
        lenient().when(responseSpec.bodyToMono(JsonNode.class))
                .thenReturn(template == null ? reactor.core.publisher.Mono.empty() : reactor.core.publisher.Mono.just(template));
        ReflectionTestUtils.setField(service, "catalogClient", wc);
    }

    private static PlatformCredential platformRow(Long id, String tenantId, String clientId, String secret,
                                                  String authUrl, String tokenUrl) {
        return new PlatformCredential(id, "google_drive", "Google Drive", AuthType.OAUTH2,
                clientId, secret, null, null, null, authUrl, tokenUrl, "",
                "google_drive", "Storage", "desc", true, Map.of(), BigDecimal.ZERO, 0,
                Instant.now(), Instant.now(), null, tenantId);
    }

    private String capturedStateJson() {
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(anyString(), json.capture(), any(Duration.class));
        return json.getValue();
    }

    private static OAuth2State state(String bindingHash, Long platformId, String clientSecret, String returnUrl) {
        return new OAuth2State(USER, "tmpl-drive", "Drive", "cid", clientSecret,
                "https://accounts.google.com/o/oauth2/v2/auth", "https://oauth2.googleapis.com/token",
                "https://www.googleapis.com/auth/drive.file", "Production", "google_drive", null,
                returnUrl, Instant.now(), "ENC(verifier-123)", ORG, null, bindingHash, platformId);
    }

    private void stubConsume(String stateKey, OAuth2State s) throws Exception {
        when(redisTemplate.execute(eq(OAuth2Service.CONSUME_STATE_SCRIPT), eq(List.of("oauth2:state:" + stateKey))))
                .thenReturn(JSON.writeValueAsString(s));
    }

    private void stubTokenEndpointOk() {
        ObjectNode body = JSON.createObjectNode();
        body.put("access_token", "at-1");
        body.put("refresh_token", "rt-1");
        body.put("scope", "https://www.googleapis.com/auth/drive.file");
        when(tokenClient.postForEntity(anyString(), any(HttpEntity.class), eq(JsonNode.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));
    }

    private void stubCreate() {
        when(credentialService.createCredential(anyString(), any(), anyString(), anyString(),
                any(CredentialType.class), any(CredentialEnvironment.class), anyString(), anyMap(),
                anyList(), anyList(), anyString(), any()))
                .thenReturn(new Credential(55L, USER, ORG, "Drive", "google_drive", CredentialType.OAuth2,
                        CredentialEnvironment.Production, CredentialStatus.active, null, Map.of(),
                        List.of(), List.of(), USER, null, true, null, Instant.now(), Instant.now()));
    }

    @SuppressWarnings("unchecked")
    private MultiValueMap<String, String> capturedTokenBody() {
        ArgumentCaptor<HttpEntity> entity = ArgumentCaptor.forClass(HttpEntity.class);
        verify(tokenClient).postForEntity(anyString(), entity.capture(), eq(JsonNode.class));
        return (MultiValueMap<String, String>) entity.getValue().getBody();
    }

    private void verifyNothingStored() {
        verifyNoInteractions(tokenClient);
        verify(credentialService, never()).createCredential(anyString(), any(), anyString(), anyString(),
                any(), any(), anyString(), anyMap(), anyList(), anyList(), anyString(), any());
    }

    // ───────────────────────────── LC-005 ─────────────────────────────

    @Nested
    @DisplayName("LC-005 browser binding")
    class Binding {

        @Test
        @DisplayName("initiate records the hash of the browser's value, never the value")
        void initiateStoresBindingHash() throws Exception {
            stubCatalog(TEMPLATE);
            when(platformCredentialService.getRawOAuth2Credential("google_drive", USER, ORG))
                    .thenReturn(Optional.of(platformRow(7L, null, "cid", "platform-secret", null, null)));

            service.initiate(new OAuth2InitiateRequest("tmpl-drive", "Drive", null, null, "Production", null, null),
                    USER, ORG, null, OAuth2BrowserBinding.hash(BINDING));

            OAuth2State stored = JSON.readValue(capturedStateJson(), OAuth2State.class);
            assertThat(stored.browserBindingHash()).isEqualTo(OAuth2BrowserBinding.hash(BINDING));
            assertThat(capturedStateJson()).doesNotContain(BINDING);
        }

        @Test
        @DisplayName("a callback from a browser without the cookie never reaches the provider (victim-completes-attacker-flow)")
        void missingCookieRefused() throws Exception {
            stubConsume("st", state(OAuth2BrowserBinding.hash(BINDING), null, "ENC(sec)", null));

            String redirect = service.handleCallback("victim-code", "st", null);

            assertThat(redirect).contains("error=invalid_state");
            verifyNothingStored();
        }

        @Test
        @DisplayName("another browser's cookie is refused")
        void wrongCookieRefused() throws Exception {
            stubConsume("st", state(OAuth2BrowserBinding.hash(BINDING), null, "ENC(sec)", null));

            String redirect = service.handleCallback("code", "st", "some-other-browser");

            assertThat(redirect).contains("error=invalid_state");
            verifyNothingStored();
        }

        @Test
        @DisplayName("a state blob carrying no binding is refused whatever the browser presents (fail closed)")
        void unboundStateRefused() throws Exception {
            stubConsume("st", state(null, null, "ENC(sec)", null));

            String redirect = service.handleCallback("code", "st", BINDING);

            assertThat(redirect).contains("error=invalid_state");
            verifyNothingStored();
        }

        @Test
        @DisplayName("the initiating browser completes the flow; the connect is audited with the GRANTED scopes (LC-058)")
        void matchingCookieCompletesAndAudits() throws Exception {
            stubCatalog(TEMPLATE);
            stubConsume("st", state(OAuth2BrowserBinding.hash(BINDING), null, "ENC(inline-secret)", null));
            stubTokenEndpointOk();
            stubCreate();

            String redirect = service.handleCallback("code", "st", BINDING);

            assertThat(redirect).startsWith("https://livecontext.ai/app/settings/credentials?").contains("success=true");
            MultiValueMap<String, String> body = capturedTokenBody();
            assertThat(body.getFirst("client_secret")).isEqualTo("inline-secret");
            assertThat(body.getFirst("code_verifier")).isEqualTo("verifier-123");
            verify(auditRecorder).recordOAuthConnected(USER, 55L, "google_drive",
                    List.of("https://www.googleapis.com/auth/drive.file"));
        }

        @Test
        @DisplayName("LC-065: the connect stores the template's revocation endpoint on the credential")
        void connectStoresRevokeUrl() throws Exception {
            stubCatalog(TEMPLATE);
            stubConsume("st", state(OAuth2BrowserBinding.hash(BINDING), null, "ENC(inline-secret)", null));
            stubTokenEndpointOk();
            stubCreate();

            service.handleCallback("code", "st", BINDING);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
            verify(credentialService).createCredential(anyString(), any(), anyString(), anyString(),
                    any(), any(), anyString(), data.capture(), anyList(), anyList(), anyString(), any());
            assertThat(data.getValue()).containsEntry(OAuth2RevocationService.REVOKE_URL_FIELD,
                    "https://oauth2.googleapis.com/revoke");
        }
    }

    // ───────────────────────────── LC-068 ─────────────────────────────

    @Nested
    @DisplayName("LC-068 no secret in clear in the Redis state")
    class StateProtection {

        @Test
        @DisplayName("a platform client secret is not in the blob at all; the PKCE verifier is encrypted")
        void platformSecretNotStored() throws Exception {
            stubCatalog(TEMPLATE);
            when(platformCredentialService.getRawOAuth2Credential("google_drive", USER, ORG))
                    .thenReturn(Optional.of(platformRow(7L, null, "cid", "platform-secret", null, null)));

            service.initiate(new OAuth2InitiateRequest("tmpl-drive", "Drive", null, null, "Production", null, null),
                    USER, ORG, null, OAuth2BrowserBinding.hash(BINDING));

            String json = capturedStateJson();
            OAuth2State stored = JSON.readValue(json, OAuth2State.class);
            assertThat(json).doesNotContain("platform-secret");
            assertThat(stored.clientSecret()).isNull();
            assertThat(stored.platformCredentialId()).isEqualTo(7L);
            assertThat(stored.codeVerifier()).startsWith("ENC(");
        }

        @Test
        @DisplayName("an inline (user-typed) secret has nowhere else to live, so it is stored encrypted")
        void inlineSecretEncrypted() throws Exception {
            stubCatalog(TEMPLATE);

            service.initiate(new OAuth2InitiateRequest("tmpl-drive", "Drive", "own-cid", "own-secret",
                    "Production", null, null), USER, ORG, null, OAuth2BrowserBinding.hash(BINDING));

            OAuth2State stored = JSON.readValue(capturedStateJson(), OAuth2State.class);
            assertThat(stored.clientSecret()).isEqualTo("ENC(own-secret)");
            assertThat(stored.platformCredentialId()).isNull();
        }

        @Test
        @DisplayName("the callback re-reads the secret from the platform row the flow started with")
        void callbackRereadsSecretFromRow() throws Exception {
            stubCatalog(TEMPLATE);
            stubConsume("st", state(OAuth2BrowserBinding.hash(BINDING), 7L, null, null));
            when(platformCredentialService.getRawCredentialById(7L))
                    .thenReturn(Optional.of(platformRow(7L, null, "cid", "row-secret", null, null)));
            stubTokenEndpointOk();
            stubCreate();

            service.handleCallback("code", "st", BINDING);

            assertThat(capturedTokenBody().getFirst("client_secret")).isEqualTo("row-secret");
        }

        @Test
        @DisplayName("a row whose client id changed since initiate is not used: no exchange")
        void changedRowRefused() throws Exception {
            stubCatalog(TEMPLATE);
            stubConsume("st", state(OAuth2BrowserBinding.hash(BINDING), 7L, null, null));
            when(platformCredentialService.getRawCredentialById(7L))
                    .thenReturn(Optional.of(platformRow(7L, null, "rotated-cid", "row-secret", null, null)));

            String redirect = service.handleCallback("code", "st", BINDING);

            assertThat(redirect).contains("error=token_exchange_failed");
            verifyNothingStored();
        }

        @Test
        @DisplayName("another tenant's BYOK row is not used: no exchange")
        void foreignTenantRowRefused() throws Exception {
            stubCatalog(TEMPLATE);
            stubConsume("st", state(OAuth2BrowserBinding.hash(BINDING), 7L, null, null));
            when(platformCredentialService.getRawCredentialById(7L))
                    .thenReturn(Optional.of(platformRow(7L, "someone-else", "cid", "their-secret", null, null)));

            String redirect = service.handleCallback("code", "st", BINDING);

            assertThat(redirect).contains("error=token_exchange_failed");
            verifyNothingStored();
        }
    }

    // ───────────────────────────── LC-023 ─────────────────────────────

    @Nested
    @DisplayName("LC-023 return path")
    class ReturnPath {

        @Test
        @DisplayName("a hostile return_url never enters the state blob")
        void initiateSanitizes() throws Exception {
            stubCatalog(TEMPLATE);

            service.initiate(new OAuth2InitiateRequest("tmpl-drive", "Drive", "cid", "sec", "Production", null,
                    ".evil.tld/phish"), USER, ORG, null, OAuth2BrowserBinding.hash(BINDING));

            assertThat(JSON.readValue(capturedStateJson(), OAuth2State.class).returnUrl())
                    .isEqualTo(OAuth2ReturnPath.DEFAULT);
        }

        @Test
        @DisplayName("a hostile value in an older blob still cannot leave the origin; a safe query is kept")
        void redirectSanitizes() {
            assertThat(service.buildRedirectUrl(state(null, null, null, "@evil.tld/x"), Map.of("error", "e")))
                    .isEqualTo("https://livecontext.ai/app/settings/credentials?error=e");
            assertThat(service.buildRedirectUrl(state(null, null, null, "/fr/app/chat/9?tab=a"), Map.of("success", "true")))
                    .isEqualTo("https://livecontext.ai/fr/app/chat/9?tab=a&success=true");
            assertThat(service.buildClientCredentialsReturnUrl("//evil.tld", 3L))
                    .isEqualTo("/app/settings/credentials?success=true&credentialId=3");
        }
    }

    // ───────────────────────────── LC-072 ─────────────────────────────

    @Nested
    @DisplayName("LC-072 minimum scope")
    class ScopeSubset {

        @Test
        @DisplayName("a requested subset narrows the authorize request")
        void subsetNarrows() throws Exception {
            stubCatalog(TEMPLATE);
            var request = new OAuth2InitiateRequest("tmpl-drive", "Drive", "cid", "sec", "Production", null, null,
                    null, List.of("https://www.googleapis.com/auth/drive.file"));

            String url = service.initiate(request, USER, ORG, null, OAuth2BrowserBinding.hash(BINDING)).authorizationUrl();

            assertThat(url).contains("drive.file").doesNotContain("drive.readonly");
        }

        @Test
        @DisplayName("no subset: the full template list, exactly as before")
        void noSubsetKeepsAll() throws Exception {
            stubCatalog(TEMPLATE);

            String url = service.initiate(new OAuth2InitiateRequest("tmpl-drive", "Drive", "cid", "sec",
                    "Production", null, null), USER, ORG, null, OAuth2BrowserBinding.hash(BINDING)).authorizationUrl();

            assertThat(url).contains("drive.file").contains("drive.readonly");
        }

        @Test
        @DisplayName("a scope the integration does not offer is refused, never added")
        void unknownScopeRefused() {
            assertThatThrownBy(() -> OAuth2Service.narrowScopes(
                    List.of("a", "b"), List.of("a", "https://mail.google.com/"), "Drive"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("https://mail.google.com/");
            assertThatThrownBy(() -> OAuth2Service.narrowScopes(List.of("a"), List.of(" "), "Drive"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(OAuth2Service.narrowScopes(List.of("a", "b", "c"), List.of("c", "a", "a"), "X"))
                    .containsExactly("a", "c");
        }
    }

    // ───────────────────────────── LC-052 ─────────────────────────────

    @Nested
    @DisplayName("LC-052 user-controlled token endpoints")
    class EndpointSsrf {

        private static final String CUSTOM_TEMPLATE = """
                { "id": "tmpl-custom", "credential_name": "acme", "icon_slug": "acme",
                  "display_name": "Acme", "auth_type": "oauth2", "metadata": {} }
                """;

        @Test
        @DisplayName("a BYOK row whose token URL points inside the cluster is refused at use")
        void byokInternalTokenUrlRefused() throws Exception {
            stubCatalog(CUSTOM_TEMPLATE);
            when(platformCredentialService.getRawCredential("acme", USER, ORG)).thenReturn(Optional.of(
                    platformRow(9L, USER, "cid", "sec", "https://8.8.8.8/authorize", "https://10.0.0.5/token")));

            assertThatThrownBy(() -> service.initiate(new OAuth2InitiateRequest("tmpl-custom", "Acme", null, null,
                    "Production", null, null), USER, ORG, null, OAuth2BrowserBinding.hash(BINDING)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("tokenUrl");
        }

        @Test
        @DisplayName("a BYOK row with an http token URL is refused at use")
        void byokHttpTokenUrlRefused() throws Exception {
            stubCatalog(CUSTOM_TEMPLATE);
            when(platformCredentialService.getRawCredential("acme", USER, ORG)).thenReturn(Optional.of(
                    platformRow(9L, USER, "cid", "sec", "https://8.8.8.8/authorize", "http://8.8.8.8/token")));

            assertThatThrownBy(() -> service.initiate(new OAuth2InitiateRequest("tmpl-custom", "Acme", null, null,
                    "Production", null, null), USER, ORG, null, OAuth2BrowserBinding.hash(BINDING)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("https");
        }

        @Test
        @DisplayName("a per-instance host var cannot aim the token exchange at an internal address")
        void hostVarInternalRefused() throws Exception {
            stubCatalog("""
                    { "id": "tmpl-host", "credential_name": "selfhosted", "icon_slug": "selfhosted",
                      "display_name": "Self Hosted", "auth_type": "oauth2",
                      "metadata": { "oauth2Config": {
                        "authorizationUrl": "https://{host}/oauth/authorize",
                        "tokenUrl": "https://{host}/oauth/token", "scopes": ["read"] } } }
                    """);
            var request = new OAuth2InitiateRequest("tmpl-host", "SH", "cid", "sec", "Production", null, null,
                    Map.of("host", "169.254.169.254"));

            assertThatThrownBy(() -> service.initiate(request, USER, ORG, null, OAuth2BrowserBinding.hash(BINDING)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("tokenUrl");
        }
    }

    // ───────────────────────────── LC-089 ─────────────────────────────

    @Nested
    @DisplayName("LC-089 the raw state never reaches the logs")
    class StateLogging {

        private ListAppender<ILoggingEvent> appender;
        private Logger logger;

        @BeforeEach
        void attach() {
            logger = (Logger) LoggerFactory.getLogger(OAuth2Service.class);
            appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
        }

        @AfterEach
        void detach() {
            logger.detachAppender(appender);
        }

        @Test
        @DisplayName("initiate and callback log a one-way reference only")
        void noRawStateLogged() throws Exception {
            stubCatalog(TEMPLATE);
            String state = service.initiate(new OAuth2InitiateRequest("tmpl-drive", "Drive", "cid", "sec",
                    "Production", null, null), USER, ORG, null, OAuth2BrowserBinding.hash(BINDING)).state();
            service.handleCallback("code", state, BINDING); // consume answers null: invalid_state path

            assertThat(appender.list).isNotEmpty();
            assertThat(appender.list).allSatisfy(e -> assertThat(e.getFormattedMessage()).doesNotContain(state));
            assertThat(appender.list).anySatisfy(e ->
                    assertThat(e.getFormattedMessage()).contains(OAuth2StateRef.of(state)));
        }
    }

    // ───────────────────────────── LC-058 refresh failure ─────────────────────────────

    @Test
    @DisplayName("LC-058: a terminal refresh failure (revoked grant) is an audit event, with no token in it")
    void terminalRefreshFailureIsAudited() throws Exception {
        stubCatalog(TEMPLATE);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        lenient().when(redisTemplate.execute(any(org.springframework.data.redis.core.script.RedisScript.class),
                anyList(), any())).thenReturn(1L);
        Credential cred = new Credential(55L, USER, ORG, "Drive", "google_drive", CredentialType.OAuth2,
                CredentialEnvironment.Production, CredentialStatus.active, null,
                Map.of("refresh_token", "ENC(rt-secret)", "client_id", "cid",
                        "oauth_client_secret", "ENC(sec)", "credential_template_id", "tmpl-drive"),
                List.of(), List.of(), USER, null, true, null, Instant.now(), Instant.now());
        when(credentialService.getCredential(55L)).thenReturn(Optional.of(cred));
        when(tokenClient.postForEntity(anyString(), any(HttpEntity.class), eq(JsonNode.class)))
                .thenThrow(org.springframework.web.client.HttpClientErrorException.create(HttpStatus.BAD_REQUEST,
                        "Bad Request", org.springframework.http.HttpHeaders.EMPTY,
                        "{\"error\":\"invalid_grant\"}".getBytes(), java.nio.charset.StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.refreshToken(55L, USER))
                .isInstanceOf(com.apimarketplace.auth.credential.service.oauth2.refresh.RefreshTerminalException.class);

        verify(auditRecorder).recordRefreshFailed(eq(USER), eq(55L), eq("google_drive"), anyString(),
                eq(400), eq(true));
    }
}
