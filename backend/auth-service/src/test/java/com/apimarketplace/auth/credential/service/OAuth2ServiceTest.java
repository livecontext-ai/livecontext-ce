package com.apimarketplace.auth.credential.service;

import com.apimarketplace.auth.credential.domain.CredentialModels.*;
import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.OAuth2Credentials;
import com.apimarketplace.common.security.CredentialEncryptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("OAuth2Service Tests")
class OAuth2ServiceTest {

    @Mock
    private CredentialService credentialService;

    @Mock
    private PlatformCredentialService platformCredentialService;

    @Mock
    private CredentialEncryptionService encryptionService;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private OAuth2Service oAuth2Service;

    private static final String USER_ID = "user-123";
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @BeforeEach
    void setUp() {
        // SimpleMeterRegistry is a no-op collector - fine for unit tests that only want to
        // verify the service behaves correctly when emitting metrics, without asserting on
        // the counter values themselves. Dedicated metrics assertions live in
        // OAuth2RefreshMetricsTest.
        io.micrometer.core.instrument.simple.SimpleMeterRegistry meterRegistry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        oAuth2Service = new OAuth2Service(
                credentialService,
                platformCredentialService,
                encryptionService,
                redisTemplate,
                "http://localhost:8081",
                objectMapper,
                new OAuth2Engine(),
                new PkceService(),
                new com.apimarketplace.auth.credential.service.oauth2.refresh.RefreshErrorClassifier(),
                new com.apimarketplace.auth.credential.service.oauth2.refresh.RefreshBackoff(),
                new com.apimarketplace.auth.credential.metrics.OAuth2RefreshMetrics(
                        meterRegistry, mock(com.apimarketplace.auth.credential.repository.CredentialRepository.class))
        );
    }

    // ========== hasPlatformCredentials ==========

    @Nested
    @DisplayName("hasPlatformCredentials")
    class HasPlatformCredentialsTests {

        @Test
        @DisplayName("should return true when platform credentials exist")
        void hasPlatformCredentials_true() {
            when(platformCredentialService.hasOAuth2Credentials("gmail")).thenReturn(true);

            boolean result = oAuth2Service.hasPlatformCredentials("gmail");

            assertThat(result).isTrue();
            verify(platformCredentialService).hasOAuth2Credentials("gmail");
        }

        @Test
        @DisplayName("should return false when no platform credentials")
        void hasPlatformCredentials_false() {
            when(platformCredentialService.hasOAuth2Credentials("unknown")).thenReturn(false);

            boolean result = oAuth2Service.hasPlatformCredentials("unknown");

            assertThat(result).isFalse();
        }
    }

    // ========== refreshToken ==========

    @Nested
    @DisplayName("refreshToken")
    class RefreshTokenTests {

        /**
         * Phase 6 adds a per-credential Redis lock in front of {@code refreshToken}.
         * All tests in this nested class exercise the happy path through the lock;
         * the contention case gets its own dedicated test
         * ({@link #refreshToken_whenLockBusy_throwsRefreshInProgress}).
         *
         * <p>Uses {@code lenient()} because {@code refreshToken_whenLockBusy...} sets up
         * its own stub that returns {@code false}, and Mockito otherwise complains about
         * the default stub being unused for that one test.
         */
        @BeforeEach
        void acquireLockByDefault() {
            lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            // The production code writes a per-call UUID as the lock value so release
            // can compare-and-delete. Tests don't care about the exact value, just that
            // acquisition succeeded under the refresh-lock key prefix.
            lenient().when(valueOperations.setIfAbsent(
                    startsWith("oauth2:refresh-lock:"),
                    anyString(),
                    any(java.time.Duration.class)))
                    .thenReturn(true);
            // Release goes through a Lua compare-and-delete script; the default happy-path
            // return value is "1 key deleted". Individual tests override as needed.
            lenient().when(redisTemplate.execute(
                    any(org.springframework.data.redis.core.script.RedisScript.class),
                    anyList(),
                    any()))
                    .thenReturn(1L);
        }

        @Test
        @DisplayName("should throw when credential not found")
        void refreshToken_throwsForMissingCredential() {
            when(credentialService.getCredential(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> oAuth2Service.refreshToken(99L, USER_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Credential not found");
        }

        /**
         * The per-credential Redis lock prevents two refresh paths (scheduler + user-initiated
         * force-refresh) from racing on rotating providers. If {@code SETNX} fails, the caller
         * gets a distinct {@code refresh_in_progress} signal so the scheduler can retry next
         * tick and the HTTP path can surface a 409 to the caller.
         */
        @Test
        @DisplayName("returns refresh_in_progress when lock busy, does not touch credential store")
        void refreshToken_whenLockBusy_throwsRefreshInProgress() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.setIfAbsent(
                    eq("oauth2:refresh-lock:7"),
                    anyString(),
                    any(java.time.Duration.class)))
                    .thenReturn(false);

            assertThatThrownBy(() -> oAuth2Service.refreshToken(7L, USER_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("refresh_in_progress");

            // Under contention we must not even load the credential, let alone mutate it,
            // and crucially must NOT run the release script (deleting a lock we don't own).
            verify(credentialService, never()).getCredential(anyLong());
            verify(credentialService, never()).updateCredentialData(anyLong(), anyString(), anyMap());
            verify(redisTemplate, never()).execute(
                    any(org.springframework.data.redis.core.script.RedisScript.class),
                    anyList(),
                    any());
        }

        /**
         * Every successful acquire must be paired with a release - otherwise the lock would
         * block the next refresh tick from doing any useful work until the TTL expires.
         * The release is a Lua compare-and-delete keyed on the UUID we wrote at acquisition,
         * so we verify the script executes against the right key with the same value.
         */
        @Test
        @DisplayName("releases lock even when the refresh itself fails (compare-and-delete)")
        void refreshToken_releasesLockOnFailure() {
            ArgumentCaptor<String> valueCap = ArgumentCaptor.forClass(String.class);
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.setIfAbsent(
                    eq("oauth2:refresh-lock:99"),
                    valueCap.capture(),
                    any(java.time.Duration.class)))
                    .thenReturn(true);
            // Fail the refresh immediately on credential lookup.
            when(credentialService.getCredential(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> oAuth2Service.refreshToken(99L, USER_ID))
                    .isInstanceOf(IllegalArgumentException.class);

            // Release path must be the Lua script, not a blind delete, and must carry
            // the SAME UUID we used to acquire - so a TTL-expired-then-reacquired lock
            // held by a different caller is left alone.
            ArgumentCaptor<List<String>> keysCap = ArgumentCaptor.forClass(List.class);
            verify(redisTemplate).execute(
                    any(org.springframework.data.redis.core.script.RedisScript.class),
                    keysCap.capture(),
                    eq(valueCap.getValue()));
            assertThat(keysCap.getValue()).containsExactly("oauth2:refresh-lock:99");
            verify(redisTemplate, never()).delete(anyString());
        }

        @Test
        @DisplayName("should throw when credential belongs to different user")
        void refreshToken_throwsForWrongUser() {
            Credential cred = buildCredential(1L, "other-user", Map.of());
            when(credentialService.getCredential(1L)).thenReturn(Optional.of(cred));

            assertThatThrownBy(() -> oAuth2Service.refreshToken(1L, USER_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Credential not found");
        }

        @Test
        @DisplayName("should throw when no refresh token is available")
        void refreshToken_throwsForNoRefreshToken() {
            Credential cred = buildCredential(1L, USER_ID,
                    Map.of("access_token", "old_token"));
            when(credentialService.getCredential(1L)).thenReturn(Optional.of(cred));

            // Stub a standard OAuth2 template (refresh supported, not client_credentials) so
            // config resolution succeeds deterministically and the code reaches the refresh-token
            // guard. Without this, the real catalogClient would attempt a live HTTP lookup against
            // catalog-service and fail with "Cannot load OAuth2 configuration" before ever checking
            // for the refresh token - making the test depend on a running catalog-service.
            com.fasterxml.jackson.databind.node.ObjectNode template = objectMapper.createObjectNode();
            com.fasterxml.jackson.databind.node.ObjectNode metadata = template.putObject("metadata");
            com.fasterxml.jackson.databind.node.ObjectNode oauth2 = metadata.putObject("oauth2Config");
            oauth2.put("authorizationUrl", "https://accounts.google.com/o/oauth2/v2/auth");
            oauth2.put("tokenUrl", "https://oauth2.googleapis.com/token");
            oauth2.putArray("scopes");
            stubCatalogTemplate(template);

            assertThatThrownBy(() -> oAuth2Service.refreshToken(1L, USER_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No refresh token available");
        }

        @Test
        @DisplayName("should throw when no credential template id is available for token url lookup")
        void refreshToken_throwsForNoTemplateId() {
            Credential cred = buildCredential(1L, USER_ID,
                    Map.of("refresh_token", "ENC:enc_refresh"));
            when(credentialService.getCredential(1L)).thenReturn(Optional.of(cred));
            when(encryptionService.decrypt("ENC:enc_refresh")).thenReturn("decrypted_refresh");
            // No template id in credential data, fetchCredentialTemplate will be called with null
            // and return null, leading to "Cannot determine access token URL"

            assertThatThrownBy(() -> oAuth2Service.refreshToken(1L, USER_ID))
                    .isInstanceOf(RuntimeException.class);
        }

        /**
         * Post-Phase-4: providers with {@code refresh.supported=false} (client_credentials flows,
         * long-lived bearer tokens) must fail fast with a clear reason instead of hitting the
         * token endpoint with a grant the provider never accepts.
         */
        @Test
        @DisplayName("supported=false → fails fast with reason, never hits token endpoint")
        void refreshToken_rejectsWhenUnsupported() {
            Credential cred = buildCredential(1L, USER_ID, Map.of(
                    "refresh_token", "ENC:enc_refresh",
                    "credential_template_id", "template-fedex",
                    "client_id", "cid",
                    "oauth_client_secret", "ENC:csec"
            ));
            when(credentialService.getCredential(1L)).thenReturn(Optional.of(cred));
            when(encryptionService.decrypt("ENC:enc_refresh")).thenReturn("decrypted_refresh");

            // Stub the catalog to return a template declaring refresh.supported=false.
            com.fasterxml.jackson.databind.node.ObjectNode template = objectMapper.createObjectNode();
            com.fasterxml.jackson.databind.node.ObjectNode metadata = template.putObject("metadata");
            com.fasterxml.jackson.databind.node.ObjectNode oauth2 = metadata.putObject("oauth2Config");
            oauth2.put("authorizationUrl", "https://fedex.example/oauth/authorize");
            oauth2.put("tokenUrl", "https://fedex.example/oauth/token");
            oauth2.putArray("scopes");
            com.fasterxml.jackson.databind.node.ObjectNode refresh = oauth2.putObject("refresh");
            refresh.put("supported", false);
            refresh.put("reason", "client_credentials flow - no refresh token issued");

            stubCatalogTemplate(template);

            assertThatThrownBy(() -> oAuth2Service.refreshToken(1L, USER_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("refresh_not_supported")
                    .hasMessageContaining("client_credentials flow");

            // Token endpoint must NOT have been called.
            verify(credentialService, never()).updateCredentialData(anyLong(), anyString(), anyMap());
        }

        @Test
        @DisplayName("client_credentials refresh requests a new access token without refresh_token")
        void refreshToken_clientCredentialsRequestsNewAccessTokenWithoutRefreshToken() {
            Credential cred = buildCredential(1L, USER_ID, "marketo", Map.of(
                    "credential_template_id", "template-marketo",
                    "client_id", "cid",
                    "client_secret", "ENC:csec",
                    "munchkin_id", "123-ABC-456"
            ));
            when(credentialService.getCredential(1L)).thenReturn(Optional.of(cred));
            when(encryptionService.decrypt("ENC:csec")).thenReturn("decrypted-secret");
            when(encryptionService.encrypt(anyString())).thenAnswer(inv -> "ENC:" + inv.getArgument(0));

            com.fasterxml.jackson.databind.node.ObjectNode template = objectMapper.createObjectNode();
            com.fasterxml.jackson.databind.node.ObjectNode metadata = template.putObject("metadata");
            com.fasterxml.jackson.databind.node.ObjectNode oauth2 = metadata.putObject("oauth2Config");
            oauth2.put("grantType", "client_credentials");
            oauth2.put("tokenUrl", "https://{munchkin_id}.mktorest.com/identity/oauth/token");
            oauth2.put("tokenParamsLocation", "query");
            oauth2.putArray("scopes");
            com.fasterxml.jackson.databind.node.ObjectNode refresh = oauth2.putObject("refresh");
            refresh.put("supported", false);
            refresh.put("reason", "client_credentials flow");
            stubCatalogTemplate(template);

            com.fasterxml.jackson.databind.node.ObjectNode tokenBody = objectMapper.createObjectNode();
            tokenBody.put("access_token", "new-at");
            tokenBody.put("token_type", "Bearer");
            tokenBody.put("expires_in", 3600);

            org.springframework.web.client.RestTemplate mockRest =
                    mock(org.springframework.web.client.RestTemplate.class);
            org.springframework.test.util.ReflectionTestUtils.setField(
                    oAuth2Service, "restTemplate", mockRest);
            String tokenUrl = "https://123-ABC-456.mktorest.com/identity/oauth/token"
                    + "?grant_type=client_credentials&client_id=cid&client_secret=decrypted-secret";
            when(mockRest.postForEntity(eq(tokenUrl),
                    any(org.springframework.http.HttpEntity.class),
                    eq(com.fasterxml.jackson.databind.JsonNode.class)))
                    .thenReturn(new org.springframework.http.ResponseEntity<>(
                            (com.fasterxml.jackson.databind.JsonNode) tokenBody,
                            org.springframework.http.HttpStatus.OK));

            when(credentialService.updateCredentialData(eq(1L), eq(USER_ID), anyMap()))
                    .thenReturn(cred);

            oAuth2Service.refreshToken(1L, USER_ID);

            @SuppressWarnings("rawtypes")
            ArgumentCaptor<org.springframework.http.HttpEntity> requestCaptor =
                    ArgumentCaptor.forClass(org.springframework.http.HttpEntity.class);
            verify(mockRest).postForEntity(eq(tokenUrl),
                    requestCaptor.capture(),
                    eq(com.fasterxml.jackson.databind.JsonNode.class));
            @SuppressWarnings("unchecked")
            org.springframework.util.MultiValueMap<String, String> body =
                    (org.springframework.util.MultiValueMap<String, String>) requestCaptor.getValue().getBody();
            assertThat(body).isNotNull();
            assertThat(body).isEmpty();

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
            verify(credentialService).updateCredentialData(eq(1L), eq(USER_ID), dataCaptor.capture());
            assertThat(dataCaptor.getValue())
                    .containsEntry("access_token", "ENC:new-at")
                    .containsEntry("token_type", "Bearer")
                    .containsEntry("munchkin_id", "123-ABC-456");
            assertThat(dataCaptor.getValue()).containsKey("expires_at");
        }

        /**
         * Post-Phase-4: when a provider's refresh response carries quirk fields declared in its
         * JSON (e.g. Salesforce's {@code instance_url} on every refresh), the new values must be
         * persisted on top of the credential data so downstream API calls pick up the fresh host.
         */
        @Test
        @DisplayName("quirks: refreshed instance_url is persisted to credential data")
        void refreshToken_persistsRefreshedQuirkFields() {
            Credential cred = buildCredential(1L, USER_ID, Map.of(
                    "refresh_token", "ENC:enc_refresh",
                    "credential_template_id", "template-salesforce",
                    "client_id", "cid",
                    "oauth_client_secret", "ENC:csec",
                    "instance_url", "https://old.my.salesforce.com"
            ));
            when(credentialService.getCredential(1L)).thenReturn(Optional.of(cred));
            when(encryptionService.decrypt("ENC:enc_refresh")).thenReturn("decrypted_refresh");
            when(encryptionService.decrypt("ENC:csec")).thenReturn("decrypted_secret");
            when(encryptionService.encrypt(anyString())).thenAnswer(inv -> "ENC:" + inv.getArgument(0));

            // Catalog returns a template with instance_url quirk declared.
            com.fasterxml.jackson.databind.node.ObjectNode template = objectMapper.createObjectNode();
            com.fasterxml.jackson.databind.node.ObjectNode metadata = template.putObject("metadata");
            com.fasterxml.jackson.databind.node.ObjectNode oauth2 = metadata.putObject("oauth2Config");
            oauth2.put("authorizationUrl", "https://login.salesforce.com/services/oauth2/authorize");
            oauth2.put("tokenUrl", "https://login.salesforce.com/services/oauth2/token");
            oauth2.putArray("scopes");
            com.fasterxml.jackson.databind.node.ObjectNode refresh = oauth2.putObject("refresh");
            refresh.put("supported", true);
            refresh.put("rotatesRefreshToken", false);
            refresh.putObject("quirks").put("instanceUrlField", "instance_url");

            stubCatalogTemplate(template);

            // Mock the token endpoint to return a NEW instance_url (org migration scenario).
            com.fasterxml.jackson.databind.node.ObjectNode tokenBody = objectMapper.createObjectNode();
            tokenBody.put("access_token", "new-at");
            tokenBody.put("token_type", "Bearer");
            tokenBody.put("expires_in", 3600);
            tokenBody.put("instance_url", "https://new.my.salesforce.com");

            org.springframework.web.client.RestTemplate mockRest =
                    mock(org.springframework.web.client.RestTemplate.class);
            org.springframework.test.util.ReflectionTestUtils.setField(
                    oAuth2Service, "restTemplate", mockRest);
            when(mockRest.postForEntity(anyString(),
                    any(org.springframework.http.HttpEntity.class),
                    eq(com.fasterxml.jackson.databind.JsonNode.class)))
                    .thenReturn(new org.springframework.http.ResponseEntity<>(
                            (com.fasterxml.jackson.databind.JsonNode) tokenBody,
                            org.springframework.http.HttpStatus.OK));

            when(credentialService.updateCredentialData(eq(1L), eq(USER_ID), anyMap()))
                    .thenReturn(cred);

            oAuth2Service.refreshToken(1L, USER_ID);

            @SuppressWarnings("unchecked")
            org.mockito.ArgumentCaptor<Map<String, Object>> dataCaptor =
                    org.mockito.ArgumentCaptor.forClass(Map.class);
            verify(credentialService).updateCredentialData(eq(1L), eq(USER_ID), dataCaptor.capture());

            assertThat(dataCaptor.getValue())
                    .as("instance_url from the refresh response must replace the stored value")
                    .containsEntry("instance_url", "https://new.my.salesforce.com")
                    .containsEntry("access_token", "ENC:new-at");
        }

        @Test
        @DisplayName("stale template id resolves by stable integration key and repairs credential data")
        void refreshToken_repairsStaleTemplateIdByStableKey() throws Exception {
            String currentTemplateId = "44444444-5555-6666-7777-888888888888";
            com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                    new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/catalog/credentials/stale-template", exchange -> {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
            });
            server.createContext("/api/catalog/credentials/resolve", exchange -> {
                assertThat(exchange.getRequestURI().getRawQuery())
                        .contains("key=twitter")
                        .contains("variant=oauth2")
                        .contains("includeInactive=true");
                String body = """
                        {
                          "id": "%s",
                          "credential_name": "twitterx",
                          "icon_slug": "twitter",
                          "variant": "oauth2",
                          "auth_type": "oauth2",
                          "metadata": {
                            "oauth2Config": {
                              "authorizationUrl": "https://twitter.example/oauth/authorize",
                              "tokenUrl": "https://twitter.example/oauth/token",
                              "scopes": [],
                              "refresh": {
                                "supported": true,
                                "rotatesRefreshToken": false
                              }
                            }
                          }
                        }
                        """.formatted(currentTemplateId);
                byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (java.io.OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            });
            server.start();

            try {
                org.springframework.test.util.ReflectionTestUtils.setField(
                        oAuth2Service,
                        "catalogClient",
                        org.springframework.web.reactive.function.client.WebClient.builder()
                                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                                .build());

                Credential cred = buildCredential(1L, USER_ID, "twitter", Map.of(
                        "refresh_token", "ENC:enc_refresh",
                        "credential_template_id", "stale-template",
                        "client_id", "cid",
                        "oauth_client_secret", "ENC:csec"
                ));
                when(credentialService.getCredential(1L)).thenReturn(Optional.of(cred));
                when(encryptionService.decrypt("ENC:enc_refresh")).thenReturn("decrypted_refresh");
                when(encryptionService.decrypt("ENC:csec")).thenReturn("decrypted_secret");
                when(encryptionService.encrypt(anyString())).thenAnswer(inv -> "ENC:" + inv.getArgument(0));

                com.fasterxml.jackson.databind.node.ObjectNode tokenBody = objectMapper.createObjectNode();
                tokenBody.put("access_token", "new-at");
                tokenBody.put("token_type", "Bearer");
                tokenBody.put("expires_in", 3600);

                org.springframework.web.client.RestTemplate mockRest =
                        mock(org.springframework.web.client.RestTemplate.class);
                org.springframework.test.util.ReflectionTestUtils.setField(
                        oAuth2Service, "restTemplate", mockRest);
                when(mockRest.postForEntity(eq("https://twitter.example/oauth/token"),
                        any(org.springframework.http.HttpEntity.class),
                        eq(com.fasterxml.jackson.databind.JsonNode.class)))
                        .thenReturn(new org.springframework.http.ResponseEntity<>(
                                (com.fasterxml.jackson.databind.JsonNode) tokenBody,
                                org.springframework.http.HttpStatus.OK));

                when(credentialService.updateCredentialData(eq(1L), eq(USER_ID), anyMap()))
                        .thenReturn(cred);

                oAuth2Service.refreshToken(1L, USER_ID);

                @SuppressWarnings("unchecked")
                ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
                verify(credentialService, atLeast(2))
                        .updateCredentialData(eq(1L), eq(USER_ID), dataCaptor.capture());

                assertThat(dataCaptor.getAllValues().get(0))
                        .containsEntry("credential_template_id", currentTemplateId)
                        .containsEntry("credential_template_key", "twitterx")
                        .containsEntry("credential_template_icon_slug", "twitter")
                        .containsEntry("credential_template_variant", "oauth2");
                assertThat(dataCaptor.getAllValues().get(dataCaptor.getAllValues().size() - 1))
                        .containsEntry("credential_template_id", currentTemplateId)
                        .containsEntry("access_token", "ENC:new-at");
            } finally {
                server.stop(0);
            }
        }

        @Test
        @DisplayName("repaired template reference survives transient refresh failure")
        void refreshToken_keepsRepairedTemplateReferenceWhenRefreshFailsTransiently() throws Exception {
            String currentTemplateId = "44444444-5555-6666-7777-888888888888";
            com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                    new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/catalog/credentials/stale-template", exchange -> {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
            });
            server.createContext("/api/catalog/credentials/resolve", exchange -> {
                String body = """
                        {
                          "id": "%s",
                          "credential_name": "twitterx",
                          "icon_slug": "twitter",
                          "variant": "oauth2",
                          "auth_type": "oauth2",
                          "metadata": {
                            "oauth2Config": {
                              "authorizationUrl": "https://twitter.example/oauth/authorize",
                              "tokenUrl": "https://twitter.example/oauth/token",
                              "scopes": [],
                              "refresh": {
                                "supported": true,
                                "rotatesRefreshToken": false
                              }
                            }
                          }
                        }
                        """.formatted(currentTemplateId);
                byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (java.io.OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            });
            server.start();

            try {
                org.springframework.test.util.ReflectionTestUtils.setField(
                        oAuth2Service,
                        "catalogClient",
                        org.springframework.web.reactive.function.client.WebClient.builder()
                                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                                .build());

                Credential cred = buildCredential(1L, USER_ID, "twitter", Map.of(
                        "refresh_token", "ENC:enc_refresh",
                        "credential_template_id", "stale-template",
                        "client_id", "cid",
                        "oauth_client_secret", "ENC:csec"
                ));
                when(credentialService.getCredential(1L)).thenReturn(Optional.of(cred));
                when(encryptionService.decrypt("ENC:enc_refresh")).thenReturn("decrypted_refresh");
                when(encryptionService.decrypt("ENC:csec")).thenReturn("decrypted_secret");

                org.springframework.web.client.RestTemplate mockRest =
                        mock(org.springframework.web.client.RestTemplate.class);
                org.springframework.test.util.ReflectionTestUtils.setField(
                        oAuth2Service, "restTemplate", mockRest);
                when(mockRest.postForEntity(eq("https://twitter.example/oauth/token"),
                        any(org.springframework.http.HttpEntity.class),
                        eq(com.fasterxml.jackson.databind.JsonNode.class)))
                        .thenThrow(new org.springframework.web.client.ResourceAccessException("timeout"));

                when(credentialService.updateCredentialData(eq(1L), eq(USER_ID), anyMap()))
                        .thenReturn(cred);

                assertThatThrownBy(() -> oAuth2Service.refreshToken(1L, USER_ID))
                        .isInstanceOf(
                                com.apimarketplace.auth.credential.service.oauth2.refresh.RefreshTransientException.class);

                @SuppressWarnings("unchecked")
                ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
                verify(credentialService, atLeast(2))
                        .updateCredentialData(eq(1L), eq(USER_ID), dataCaptor.capture());

                assertThat(dataCaptor.getAllValues().get(dataCaptor.getAllValues().size() - 1))
                        .containsEntry("credential_template_id", currentTemplateId)
                        .containsEntry("credential_template_key", "twitterx")
                        .containsEntry("credential_template_icon_slug", "twitter")
                        .containsEntry("credential_template_variant", "oauth2")
                        .containsKey("refresh_cooldown_until");
            } finally {
                server.stop(0);
            }
        }

        /** Minimal WebClient chain mock returning the given JsonNode for any catalog lookup. */
        @SuppressWarnings({"unchecked", "rawtypes"})
        private void stubCatalogTemplate(com.fasterxml.jackson.databind.JsonNode template) {
            org.springframework.web.reactive.function.client.WebClient wc =
                    mock(org.springframework.web.reactive.function.client.WebClient.class);
            var uriSpec = mock(
                    org.springframework.web.reactive.function.client.WebClient.RequestHeadersUriSpec.class);
            var headersSpec = mock(
                    org.springframework.web.reactive.function.client.WebClient.RequestHeadersSpec.class);
            var responseSpec = mock(
                    org.springframework.web.reactive.function.client.WebClient.ResponseSpec.class);

            when(wc.get()).thenReturn(uriSpec);
            when(uriSpec.uri(anyString(), any(Object[].class))).thenReturn(headersSpec);
            when(headersSpec.retrieve()).thenReturn(responseSpec);
            when(responseSpec.bodyToMono(com.fasterxml.jackson.databind.JsonNode.class))
                    .thenReturn(reactor.core.publisher.Mono.just(template));

            org.springframework.test.util.ReflectionTestUtils.setField(
                    oAuth2Service, "catalogClient", wc);
        }
    }

    // ========== handleCallback (PKCE regression lock) ==========

    /**
     * This is the ONLY Service-level end-to-end test for {@code handleCallback}. It exists
     * specifically to lock in the wiring between {@code OAuth2State.codeVerifier} and the
     * token exchange body - the exact path that was broken when the old code had
     * "For now, we skip PKCE" as a TODO. If this test fails, Airtable/Microsoft are broken.
     *
     * <p>The catalog is deliberately unreachable in this test, so the code takes the fallback
     * config-rebuild path. That path also honors {@code codeVerifier} → we verify the exchange
     * body contains {@code code_verifier=<verifier>}.
     */
    @Nested
    @DisplayName("handleCallback - PKCE wiring")
    class HandleCallbackPkceTests {

        @Test
        @DisplayName("PKCE codeVerifier from state is propagated to the token exchange body")
        void pkceVerifierReachesTokenExchange() throws Exception {
            final String state = "state-abc-123";
            final String code = "auth-code-xyz";
            final String verifier = "pkce-verifier-to-lock-in-for-regression-test-ok";

            // Build a state blob as it would exist in Redis after a PKCE initiate().
            var stateRecord = new com.apimarketplace.auth.credential.domain.OAuth2Models.OAuth2State(
                    USER_ID,
                    "template-airtable",
                    "Airtable Credential",
                    "client-id",
                    "client-secret",
                    "https://airtable.com/oauth2/v1/authorize",
                    "https://airtable.com/oauth2/v1/token",
                    "data.records:read",
                    "Production",
                    "airtable",
                    "/icons/services/airtable.svg",
                    "/app/settings/credentials",
                    Instant.parse("2026-04-09T10:00:00Z"),
                    verifier
            );
            String stateJson = objectMapper.writeValueAsString(stateRecord);

            // Redis returns our state when the callback looks it up.
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get("oauth2:state:" + state)).thenReturn(stateJson);

            // Replace the internal RestTemplate with a mock so we can capture the POST body.
            org.springframework.web.client.RestTemplate mockRest =
                    mock(org.springframework.web.client.RestTemplate.class);
            org.springframework.test.util.ReflectionTestUtils.setField(
                    oAuth2Service, "restTemplate", mockRest);

            // Fake a successful token endpoint response.
            com.fasterxml.jackson.databind.node.ObjectNode tokenBody =
                    new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            tokenBody.put("access_token", "fake-at");
            tokenBody.put("refresh_token", "fake-rt");
            tokenBody.put("token_type", "Bearer");
            tokenBody.put("expires_in", 3600);
            when(mockRest.postForEntity(
                    anyString(),
                    any(org.springframework.http.HttpEntity.class),
                    eq(com.fasterxml.jackson.databind.JsonNode.class)))
                    .thenReturn(new org.springframework.http.ResponseEntity<>(
                            (com.fasterxml.jackson.databind.JsonNode) tokenBody,
                            org.springframework.http.HttpStatus.OK));

            when(encryptionService.encrypt(anyString()))
                    .thenAnswer(inv -> "ENC:" + inv.getArgument(0));

            // Stub credential creation so the callback can complete.
            Credential created = buildCredential(1L, USER_ID, Map.of());
            when(credentialService.createCredential(
                    anyString(), org.mockito.ArgumentMatchers.<String>any(), anyString(), anyString(),
                    any(CredentialType.class), any(CredentialEnvironment.class),
                    anyString(), anyMap(), anyList(), anyList(),
                    anyString(), anyString()))
                    .thenReturn(created);

            // Act
            String redirect = oAuth2Service.handleCallback(code, state);

            // Assert: redirect is success, NOT an error page.
            assertThat(redirect).contains("success=true");
            assertThat(redirect).doesNotContain("error=");

            // Capture the HttpEntity sent to the token endpoint.
            org.mockito.ArgumentCaptor<org.springframework.http.HttpEntity> captor =
                    org.mockito.ArgumentCaptor.forClass(org.springframework.http.HttpEntity.class);
            verify(mockRest).postForEntity(
                    eq("https://airtable.com/oauth2/v1/token"),
                    captor.capture(),
                    eq(com.fasterxml.jackson.databind.JsonNode.class));

            @SuppressWarnings("unchecked")
            org.springframework.util.MultiValueMap<String, String> body =
                    (org.springframework.util.MultiValueMap<String, String>) captor.getValue().getBody();

            assertThat(body).isNotNull();
            assertThat(body.getFirst("grant_type")).isEqualTo("authorization_code");
            assertThat(body.getFirst("code")).isEqualTo(code);
            assertThat(body.getFirst("code_verifier"))
                    .as("PKCE verifier from OAuth2State must be in the token exchange body - "
                            + "this is the Airtable/Microsoft regression path")
                    .isEqualTo(verifier);
            assertThat(body.getFirst("client_id")).isEqualTo("client-id");
            assertThat(body.getFirst("client_secret")).isEqualTo("client-secret");
        }

        @Test
        @DisplayName("non-PKCE provider: code_verifier is NOT added to body (regression guard)")
        void nonPkceProviderOmitsVerifier() throws Exception {
            final String state = "state-non-pkce";

            var stateRecord = new com.apimarketplace.auth.credential.domain.OAuth2Models.OAuth2State(
                    USER_ID, "template-github", "GitHub Credential",
                    "cid", "csec",
                    "https://github.com/login/oauth/authorize",
                    "https://github.com/login/oauth/access_token",
                    "repo",
                    "Production", "github", "/icons/services/github.svg",
                    null, Instant.parse("2026-04-09T10:00:00Z"),
                    null // no PKCE
            );
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get("oauth2:state:" + state))
                    .thenReturn(objectMapper.writeValueAsString(stateRecord));

            org.springframework.web.client.RestTemplate mockRest =
                    mock(org.springframework.web.client.RestTemplate.class);
            org.springframework.test.util.ReflectionTestUtils.setField(
                    oAuth2Service, "restTemplate", mockRest);

            com.fasterxml.jackson.databind.node.ObjectNode tokenBody =
                    new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            tokenBody.put("access_token", "fake-at");
            when(mockRest.postForEntity(
                    anyString(),
                    any(org.springframework.http.HttpEntity.class),
                    eq(com.fasterxml.jackson.databind.JsonNode.class)))
                    .thenReturn(new org.springframework.http.ResponseEntity<>(
                            (com.fasterxml.jackson.databind.JsonNode) tokenBody,
                            org.springframework.http.HttpStatus.OK));
            when(encryptionService.encrypt(anyString()))
                    .thenAnswer(inv -> "ENC:" + inv.getArgument(0));
            when(credentialService.createCredential(
                    anyString(), org.mockito.ArgumentMatchers.<String>any(), anyString(), anyString(),
                    any(CredentialType.class), any(CredentialEnvironment.class),
                    anyString(), anyMap(), anyList(), anyList(),
                    anyString(), anyString()))
                    .thenReturn(buildCredential(1L, USER_ID, Map.of()));

            oAuth2Service.handleCallback("code", state);

            org.mockito.ArgumentCaptor<org.springframework.http.HttpEntity> captor =
                    org.mockito.ArgumentCaptor.forClass(org.springframework.http.HttpEntity.class);
            verify(mockRest).postForEntity(anyString(), captor.capture(),
                    eq(com.fasterxml.jackson.databind.JsonNode.class));

            @SuppressWarnings("unchecked")
            org.springframework.util.MultiValueMap<String, String> body =
                    (org.springframework.util.MultiValueMap<String, String>) captor.getValue().getBody();

            assertThat(body.getFirst("code_verifier"))
                    .as("non-PKCE provider must not leak a verifier into the token exchange")
                    .isNull();
        }
    }

    // ========== handleCallback - scope source of truth ==========

    /**
     * Regression guard: when Google (or any provider) returns a scope string in the token
     * response that is a SUBSET of what we requested (because the user unchecked scopes at
     * the consent screen), the stored credential scopes must reflect what was actually
     * GRANTED - not what we requested. Saving the requested list produces silent 403s at
     * runtime on endpoints whose scope was declined.
     */
    @Nested
    @DisplayName("handleCallback - scope source of truth")
    class HandleCallbackScopeTests {

        @Test
        @DisplayName("Stores GRANTED scopes (tokens.scope) when provider returns a subset")
        void storesGrantedScopesWhenProviderNarrows() throws Exception {
            final String state = "state-scope-subset";
            final String requested = "https://www.googleapis.com/auth/gmail.readonly https://www.googleapis.com/auth/gmail.send";
            final String granted = "https://www.googleapis.com/auth/gmail.readonly";

            var stateRecord = new com.apimarketplace.auth.credential.domain.OAuth2Models.OAuth2State(
                    USER_ID, "template-gmail", "Gmail Credential",
                    "cid", "csec",
                    "https://accounts.google.com/o/oauth2/v2/auth",
                    "https://oauth2.googleapis.com/token",
                    requested,
                    "Production", "gmail", "/icons/services/gmail.svg",
                    null, Instant.parse("2026-04-17T10:00:00Z"),
                    null
            );
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get("oauth2:state:" + state))
                    .thenReturn(objectMapper.writeValueAsString(stateRecord));

            org.springframework.web.client.RestTemplate mockRest =
                    mock(org.springframework.web.client.RestTemplate.class);
            org.springframework.test.util.ReflectionTestUtils.setField(
                    oAuth2Service, "restTemplate", mockRest);

            com.fasterxml.jackson.databind.node.ObjectNode tokenBody =
                    new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            tokenBody.put("access_token", "fake-at");
            tokenBody.put("refresh_token", "fake-rt");
            tokenBody.put("token_type", "Bearer");
            tokenBody.put("expires_in", 3600);
            tokenBody.put("scope", granted);
            when(mockRest.postForEntity(
                    anyString(),
                    any(org.springframework.http.HttpEntity.class),
                    eq(com.fasterxml.jackson.databind.JsonNode.class)))
                    .thenReturn(new org.springframework.http.ResponseEntity<>(
                            (com.fasterxml.jackson.databind.JsonNode) tokenBody,
                            org.springframework.http.HttpStatus.OK));
            when(encryptionService.encrypt(anyString()))
                    .thenAnswer(inv -> "ENC:" + inv.getArgument(0));
            when(credentialService.createCredential(
                    anyString(), org.mockito.ArgumentMatchers.<String>any(), anyString(), anyString(),
                    any(CredentialType.class), any(CredentialEnvironment.class),
                    anyString(), anyMap(), anyList(), anyList(),
                    anyString(), anyString()))
                    .thenReturn(buildCredential(1L, USER_ID, Map.of()));

            oAuth2Service.handleCallback("code", state);

            @SuppressWarnings("unchecked")
            org.mockito.ArgumentCaptor<List<String>> scopesCaptor =
                    org.mockito.ArgumentCaptor.forClass(List.class);
            verify(credentialService).createCredential(
                    anyString(), org.mockito.ArgumentMatchers.<String>any(), anyString(), anyString(),
                    any(CredentialType.class), any(CredentialEnvironment.class),
                    anyString(), anyMap(), scopesCaptor.capture(), anyList(),
                    anyString(), anyString());

            assertThat(scopesCaptor.getValue())
                    .as("credential.scopes must reflect what Google GRANTED (one scope), "
                            + "not what we requested (two scopes)")
                    .containsExactly("https://www.googleapis.com/auth/gmail.readonly");
        }

        @Test
        @DisplayName("Falls back to REQUESTED scopes when provider returns no scope field")
        void fallsBackToRequestedWhenProviderSilent() throws Exception {
            final String state = "state-scope-silent";
            final String requested = "repo user";

            var stateRecord = new com.apimarketplace.auth.credential.domain.OAuth2Models.OAuth2State(
                    USER_ID, "template-github", "GitHub Credential",
                    "cid", "csec",
                    "https://github.com/login/oauth/authorize",
                    "https://github.com/login/oauth/access_token",
                    requested,
                    "Production", "github", "/icons/services/github.svg",
                    null, Instant.parse("2026-04-17T10:00:00Z"),
                    null
            );
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get("oauth2:state:" + state))
                    .thenReturn(objectMapper.writeValueAsString(stateRecord));

            org.springframework.web.client.RestTemplate mockRest =
                    mock(org.springframework.web.client.RestTemplate.class);
            org.springframework.test.util.ReflectionTestUtils.setField(
                    oAuth2Service, "restTemplate", mockRest);

            com.fasterxml.jackson.databind.node.ObjectNode tokenBody =
                    new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            tokenBody.put("access_token", "fake-at");
            // no "scope" field - provider stays silent about granted scopes
            when(mockRest.postForEntity(
                    anyString(),
                    any(org.springframework.http.HttpEntity.class),
                    eq(com.fasterxml.jackson.databind.JsonNode.class)))
                    .thenReturn(new org.springframework.http.ResponseEntity<>(
                            (com.fasterxml.jackson.databind.JsonNode) tokenBody,
                            org.springframework.http.HttpStatus.OK));
            when(encryptionService.encrypt(anyString()))
                    .thenAnswer(inv -> "ENC:" + inv.getArgument(0));
            when(credentialService.createCredential(
                    anyString(), org.mockito.ArgumentMatchers.<String>any(), anyString(), anyString(),
                    any(CredentialType.class), any(CredentialEnvironment.class),
                    anyString(), anyMap(), anyList(), anyList(),
                    anyString(), anyString()))
                    .thenReturn(buildCredential(1L, USER_ID, Map.of()));

            oAuth2Service.handleCallback("code", state);

            @SuppressWarnings("unchecked")
            org.mockito.ArgumentCaptor<List<String>> scopesCaptor =
                    org.mockito.ArgumentCaptor.forClass(List.class);
            verify(credentialService).createCredential(
                    anyString(), org.mockito.ArgumentMatchers.<String>any(), anyString(), anyString(),
                    any(CredentialType.class), any(CredentialEnvironment.class),
                    anyString(), anyMap(), scopesCaptor.capture(), anyList(),
                    anyString(), anyString());

            assertThat(scopesCaptor.getValue())
                    .as("when provider returns no scope, fall back to requested scopes")
                    .containsExactly("repo", "user");
        }
    }

    /**
     * PR19 round-2 regression guard for audit-1 CRITICAL-1:
     * OAuth flow initiated from an org workspace must land the resulting
     * credential with the captured {@code organization_id}. Without this
     * test, a future regression that wires the wrong field of
     * {@code OAuth2State} to position-2 of {@code createCredential} would
     * silently demote org OAuth credentials to personal scope - exactly the
     * leak vector PR19 was meant to close.
     */
    @Nested
    @DisplayName("handleCallback - PR19 org scope thread")
    class HandleCallbackOrgScopeTests {

        @Test
        @DisplayName("organizationId from OAuth2State propagates to credentialService.createCredential")
        void organizationIdFromStateReachesCreateCredential() throws Exception {
            final String state = "state-org-thread";
            final String orgId = "org-acme-42";

            var stateRecord = new com.apimarketplace.auth.credential.domain.OAuth2Models.OAuth2State(
                    USER_ID, "template-gmail", "Team Gmail",
                    "cid", "csec",
                    "https://accounts.google.com/oauth2/auth",
                    "https://oauth2.googleapis.com/token",
                    "https://www.googleapis.com/auth/gmail.readonly",
                    "Production", "gmail", "/icons/services/gmail.svg",
                    null, Instant.parse("2026-05-13T10:00:00Z"),
                    null, // no PKCE
                    orgId // org-scope capture-at-initiate
            );
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get("oauth2:state:" + state))
                    .thenReturn(objectMapper.writeValueAsString(stateRecord));

            org.springframework.web.client.RestTemplate mockRest =
                    mock(org.springframework.web.client.RestTemplate.class);
            org.springframework.test.util.ReflectionTestUtils.setField(
                    oAuth2Service, "restTemplate", mockRest);

            com.fasterxml.jackson.databind.node.ObjectNode tokenBody =
                    new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            tokenBody.put("access_token", "fake-at");
            tokenBody.put("refresh_token", "fake-rt");
            tokenBody.put("token_type", "Bearer");
            tokenBody.put("expires_in", 3600);
            when(mockRest.postForEntity(
                    anyString(),
                    any(org.springframework.http.HttpEntity.class),
                    eq(com.fasterxml.jackson.databind.JsonNode.class)))
                    .thenReturn(new org.springframework.http.ResponseEntity<>(
                            (com.fasterxml.jackson.databind.JsonNode) tokenBody,
                            org.springframework.http.HttpStatus.OK));
            when(encryptionService.encrypt(anyString()))
                    .thenAnswer(inv -> "ENC:" + inv.getArgument(0));
            when(credentialService.createCredential(
                    anyString(), org.mockito.ArgumentMatchers.<String>any(), anyString(), anyString(),
                    any(CredentialType.class), any(CredentialEnvironment.class),
                    anyString(), anyMap(), anyList(), anyList(),
                    anyString(), anyString()))
                    .thenReturn(buildCredential(1L, USER_ID, Map.of()));

            oAuth2Service.handleCallback("code", state);

            // Position 2 of the 12-arg createCredential is organizationId.
            org.mockito.ArgumentCaptor<String> orgCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(credentialService).createCredential(
                    anyString(), orgCaptor.capture(), anyString(), anyString(),
                    any(CredentialType.class), any(CredentialEnvironment.class),
                    anyString(), anyMap(), anyList(), anyList(),
                    anyString(), anyString());

            assertThat(orgCaptor.getValue())
                    .as("OAuth flow initiated from org workspace MUST land credential with "
                      + "organization_id matching the captured state - without this thread the "
                      + "credential would silently demote to personal scope (audit-1 CRITICAL-1).")
                    .isEqualTo(orgId);
        }

        @Test
        @DisplayName("null organizationId in OAuth2State stays null at createCredential (personal scope path)")
        void nullOrganizationIdPropagatesAsPersonalScope() throws Exception {
            final String state = "state-personal";

            var stateRecord = new com.apimarketplace.auth.credential.domain.OAuth2Models.OAuth2State(
                    USER_ID, "template-gmail", "Personal Gmail",
                    "cid", "csec",
                    "https://accounts.google.com/oauth2/auth",
                    "https://oauth2.googleapis.com/token",
                    "https://www.googleapis.com/auth/gmail.readonly",
                    "Production", "gmail", "/icons/services/gmail.svg",
                    null, Instant.parse("2026-05-13T10:00:00Z"),
                    null,
                    null // no org context - personal-scope OAuth flow
            );
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get("oauth2:state:" + state))
                    .thenReturn(objectMapper.writeValueAsString(stateRecord));

            org.springframework.web.client.RestTemplate mockRest =
                    mock(org.springframework.web.client.RestTemplate.class);
            org.springframework.test.util.ReflectionTestUtils.setField(
                    oAuth2Service, "restTemplate", mockRest);

            com.fasterxml.jackson.databind.node.ObjectNode tokenBody =
                    new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            tokenBody.put("access_token", "fake-at");
            when(mockRest.postForEntity(
                    anyString(),
                    any(org.springframework.http.HttpEntity.class),
                    eq(com.fasterxml.jackson.databind.JsonNode.class)))
                    .thenReturn(new org.springframework.http.ResponseEntity<>(
                            (com.fasterxml.jackson.databind.JsonNode) tokenBody,
                            org.springframework.http.HttpStatus.OK));
            when(encryptionService.encrypt(anyString()))
                    .thenAnswer(inv -> "ENC:" + inv.getArgument(0));
            when(credentialService.createCredential(
                    anyString(), org.mockito.ArgumentMatchers.<String>any(), anyString(), anyString(),
                    any(CredentialType.class), any(CredentialEnvironment.class),
                    anyString(), anyMap(), anyList(), anyList(),
                    anyString(), anyString()))
                    .thenReturn(buildCredential(1L, USER_ID, Map.of()));

            oAuth2Service.handleCallback("code", state);

            org.mockito.ArgumentCaptor<String> orgCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(credentialService).createCredential(
                    anyString(), orgCaptor.capture(), anyString(), anyString(),
                    any(CredentialType.class), any(CredentialEnvironment.class),
                    anyString(), anyMap(), anyList(), anyList(),
                    anyString(), anyString());

            assertThat(orgCaptor.getValue())
                    .as("Personal-scope OAuth flow → organizationId must stay null, no false-positive "
                      + "tagging")
                    .isNull();
        }
    }

    // ========== OAuth2InitiateRequest.hasUserCredentials ==========

    @Nested
    @DisplayName("OAuth2InitiateRequest.hasUserCredentials")
    class HasUserCredentialsTests {

        @Test
        @DisplayName("should return true when both clientId and clientSecret are provided")
        void hasUserCredentials_true() {
            var request = new com.apimarketplace.auth.credential.domain.OAuth2Models.OAuth2InitiateRequest(
                    "template-1", "My Cred", "client-id", "client-secret",
                    "Production", "gmail", null
            );

            assertThat(request.hasUserCredentials()).isTrue();
        }

        @Test
        @DisplayName("should return false when clientId is null")
        void hasUserCredentials_falseNullClientId() {
            var request = new com.apimarketplace.auth.credential.domain.OAuth2Models.OAuth2InitiateRequest(
                    "template-1", "My Cred", null, "client-secret",
                    "Production", "gmail", null
            );

            assertThat(request.hasUserCredentials()).isFalse();
        }

        @Test
        @DisplayName("should return false when clientSecret is blank")
        void hasUserCredentials_falseBlankSecret() {
            var request = new com.apimarketplace.auth.credential.domain.OAuth2Models.OAuth2InitiateRequest(
                    "template-1", "My Cred", "client-id", "  ",
                    "Production", "gmail", null
            );

            assertThat(request.hasUserCredentials()).isFalse();
        }

        @Test
        @DisplayName("should return false when both are null")
        void hasUserCredentials_falseBothNull() {
            var request = new com.apimarketplace.auth.credential.domain.OAuth2Models.OAuth2InitiateRequest(
                    "template-1", "My Cred", null, null,
                    "Production", "gmail", null
            );

            assertThat(request.hasUserCredentials()).isFalse();
        }
    }

    // ========== OAuth2SimpleInitiateRequest.toFullRequest ==========

    @Nested
    @DisplayName("OAuth2SimpleInitiateRequest.toFullRequest")
    class ToFullRequestTests {

        @Test
        @DisplayName("should convert simple request to full request with provided credentials")
        void toFullRequest_convertsCorrectly() {
            var simpleRequest = new com.apimarketplace.auth.credential.domain.OAuth2Models.OAuth2SimpleInitiateRequest(
                    "template-1", "My Cred", "Sandbox", "gmail"
            );

            var fullRequest = simpleRequest.toFullRequest("cid", "csec");

            assertThat(fullRequest.credentialTemplateId()).isEqualTo("template-1");
            assertThat(fullRequest.credentialName()).isEqualTo("My Cred");
            assertThat(fullRequest.clientId()).isEqualTo("cid");
            assertThat(fullRequest.clientSecret()).isEqualTo("csec");
            assertThat(fullRequest.environment()).isEqualTo("Sandbox");
            assertThat(fullRequest.integration()).isEqualTo("gmail");
            assertThat(fullRequest.returnUrl()).isNull();
        }
    }

    // ========== initiate - BYOK requests the user's full scope set ==========

    /**
     * Regression guard for the prod BYOK bug: a user brings their own OAuth client (clientId +
     * secret) but the connect silently dropped to the platform template's narrow scope set
     * (Gmail → only {@code send}+{@code labels}), so BYOK behaved identically to the platform
     * flow - the user's own restricted scopes ({@code readonly}/{@code modify}) were never
     * requested. The fix: a tenant-scoped (BYOK) row makes initiate() request the full CATALOG
     * scope set (template scopes ∪ catalog byokOnlyScopes). The catalog is the single source of
     * truth - the per-tenant row's {@code default_scopes} is deliberately NOT unioned (it drifts
     * from the catalog; see {@code byokInitiateIgnoresStaleRowDefaultScopes}).
     */
    @Nested
    @DisplayName("initiate - BYOK scope override")
    class InitiateByokScopeTests {

        private static final String GMAIL_TEMPLATE_JSON = """
                {
                  "id": "template-gmail",
                  "credential_name": "gmail",
                  "icon_slug": "gmail",
                  "display_name": "Gmail",
                  "auth_type": "oauth2",
                  "metadata": {
                    "oauth2Config": {
                      "authorizationUrl": "https://accounts.google.com/o/oauth2/v2/auth",
                      "tokenUrl": "https://oauth2.googleapis.com/token",
                      "scopes": [
                        "https://www.googleapis.com/auth/gmail.labels",
                        "https://www.googleapis.com/auth/gmail.send"
                      ],
                      "byokOnlyScopes": [
                        "https://www.googleapis.com/auth/gmail.readonly",
                        "https://www.googleapis.com/auth/gmail.modify"
                      ]
                    }
                  }
                }
                """;

        @BeforeEach
        void stubRedisAndCallbackUrl() {
            // initiate() persists the OAuth2State via opsForValue().set(...).
            lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            // @Value field is not injected in a plain Mockito test - set a real callback so
            // buildAuthorizationUrl's redirect_uri encoding doesn't NPE on null.
            org.springframework.test.util.ReflectionTestUtils.setField(
                    oAuth2Service, "callbackUrl",
                    "http://localhost:8083/api/credentials/oauth2/callback");
        }

        @Test
        @DisplayName("BYOK connect requests byokOnly scopes (readonly/modify), not just the platform template scopes")
        void byokInitiateRequestsFullScopesNotTemplateScopes() throws Exception {
            stubCatalogTemplateJson(GMAIL_TEMPLATE_JSON);

            // BYOK tenant row: user's own client, BLANK default_scopes - the catalog byokOnly union
            // alone must lift the connect above the platform send+labels ceiling.
            var byokRow = new com.apimarketplace.auth.credential.domain.PlatformCredentialModels.PlatformCredential(
                    1L, "gmail", "Gmail",
                    com.apimarketplace.auth.credential.domain.PlatformCredentialModels.AuthType.OAUTH2,
                    "byok-cid", "byok-csec", null, null, null,
                    "https://accounts.google.com/o/oauth2/v2/auth", "https://oauth2.googleapis.com/token", "",
                    "gmail", "Communication", "desc",
                    true, Map.of(), java.math.BigDecimal.ZERO, 0,
                    Instant.now(), Instant.now(), null, USER_ID);
            when(platformCredentialService.getRawOAuth2Credential("gmail", USER_ID, null))
                    .thenReturn(Optional.of(byokRow));

            var request = new com.apimarketplace.auth.credential.domain.OAuth2Models.OAuth2InitiateRequest(
                    "template-gmail", "My Gmail", null, null, "Production", null, "/app/settings/credentials");

            var response = oAuth2Service.initiate(request, USER_ID);

            assertThat(response.authorizationUrl())
                    .as("BYOK uses the user's own client_id")
                    .contains("byok-cid");
            assertThat(response.authorizationUrl())
                    .as("BYOK must request the restricted byokOnly scopes the platform client never asks for")
                    .contains("gmail.readonly")
                    .contains("gmail.modify");
            assertThat(response.authorizationUrl())
                    .as("platform template scopes are still part of the BYOK union")
                    .contains("gmail.send")
                    .contains("gmail.labels");
        }

        @Test
        @DisplayName("locale threading: initiate(.., uiLocale) renders Google hl from the {ui_locale} placeholder; null drops it")
        void initiateThreadsUiLocaleIntoAuthorizeUrl() throws Exception {
            String template = """
                    {
                      "id": "template-gmail",
                      "credential_name": "gmail",
                      "icon_slug": "gmail",
                      "display_name": "Gmail",
                      "auth_type": "oauth2",
                      "metadata": {
                        "oauth2Config": {
                          "authorizationUrl": "https://accounts.google.com/o/oauth2/v2/auth",
                          "tokenUrl": "https://oauth2.googleapis.com/token",
                          "scopes": ["https://www.googleapis.com/auth/gmail.send"],
                          "authorizeExtraParams": { "access_type": "offline", "hl": "{ui_locale}" }
                        }
                      }
                    }
                    """;
            stubCatalogTemplateJson(template);
            var row = new com.apimarketplace.auth.credential.domain.PlatformCredentialModels.PlatformCredential(
                    1L, "gmail", "Gmail",
                    com.apimarketplace.auth.credential.domain.PlatformCredentialModels.AuthType.OAUTH2,
                    "cid", "csec", null, null, null,
                    "https://accounts.google.com/o/oauth2/v2/auth", "https://oauth2.googleapis.com/token", "",
                    "gmail", "Communication", "desc",
                    true, Map.of(), java.math.BigDecimal.ZERO, 0,
                    Instant.now(), Instant.now(), null, USER_ID);
            when(platformCredentialService.getRawOAuth2Credential("gmail", USER_ID, null))
                    .thenReturn(Optional.of(row));
            var request = new com.apimarketplace.auth.credential.domain.OAuth2Models.OAuth2InitiateRequest(
                    "template-gmail", "My Gmail", null, null, "Production", null, "/app/settings/credentials");

            // Locale present -> the {ui_locale} placeholder is rendered as hl=fr in the authorize URL.
            var withLocale = oAuth2Service.initiate(request, USER_ID, null, "fr");
            assertThat(withLocale.authorizationUrl()).contains("hl=fr");

            // No locale -> the placeholder param is dropped; the literal {ui_locale} never leaks.
            var noLocale = oAuth2Service.initiate(request, USER_ID, null, null);
            assertThat(noLocale.authorizationUrl()).doesNotContain("hl=");
            assertThat(noLocale.authorizationUrl()).doesNotContain("ui_locale");
        }

        @Test
        @DisplayName("Catalog is the single source: a stale scope on the row's default_scopes is NOT requested")
        void byokInitiateIgnoresStaleRowDefaultScopes() throws Exception {
            // Catalog AFTER gmail.metadata was dropped: byokOnly has readonly, never metadata.
            String template = """
                    {
                      "id": "template-gmail",
                      "credential_name": "gmail",
                      "icon_slug": "gmail",
                      "display_name": "Gmail",
                      "auth_type": "oauth2",
                      "metadata": {
                        "oauth2Config": {
                          "authorizationUrl": "https://accounts.google.com/o/oauth2/v2/auth",
                          "tokenUrl": "https://oauth2.googleapis.com/token",
                          "scopes": ["https://www.googleapis.com/auth/gmail.send"],
                          "byokOnlyScopes": ["https://www.googleapis.com/auth/gmail.readonly"]
                        }
                      }
                    }
                    """;
            stubCatalogTemplateJson(template);

            // Stale row: its default_scopes STILL lists gmail.metadata (saved before the catalog
            // dropped it). The catalog-as-single-source fix must NOT let that reach the authorize
            // URL - otherwise Gmail re-applies the metadata restriction and breaks the q search.
            var byokRow = new com.apimarketplace.auth.credential.domain.PlatformCredentialModels.PlatformCredential(
                    1L, "gmail", "Gmail",
                    com.apimarketplace.auth.credential.domain.PlatformCredentialModels.AuthType.OAUTH2,
                    "byok-cid", "byok-csec", null, null, null,
                    "https://accounts.google.com/o/oauth2/v2/auth", "https://oauth2.googleapis.com/token",
                    "https://www.googleapis.com/auth/gmail.send https://www.googleapis.com/auth/gmail.readonly https://www.googleapis.com/auth/gmail.metadata",
                    "gmail", "Communication", "desc",
                    true, Map.of(), java.math.BigDecimal.ZERO, 0,
                    Instant.now(), Instant.now(), null, USER_ID);
            when(platformCredentialService.getRawOAuth2Credential("gmail", USER_ID, null))
                    .thenReturn(Optional.of(byokRow));

            var request = new com.apimarketplace.auth.credential.domain.OAuth2Models.OAuth2InitiateRequest(
                    "template-gmail", "My Gmail", null, null, "Production", null, "/app/settings/credentials");

            var response = oAuth2Service.initiate(request, USER_ID);

            assertThat(response.authorizationUrl())
                    .as("scopes come from the catalog (template + byokOnly)")
                    .contains("gmail.send")
                    .contains("gmail.readonly");
            assertThat(response.authorizationUrl())
                    .as("a stale gmail.metadata on the row's default_scopes must NOT be requested - "
                            + "the catalog is the single source of truth, the per-row copy is ignored")
                    .doesNotContain("gmail.metadata");
        }

        @Test
        @DisplayName("Resolution falls through iconSlug → integrationName → credential_name, then applies BYOK scopes")
        void byokInitiateResolvesViaCredentialNameFallback() throws Exception {
            // Template whose icon_slug and display_name differ from credential_name, so the first
            // two lookup keys MUST miss and resolution falls through to credential_name.
            String customTemplate = """
                    {
                      "id": "template-custom",
                      "credential_name": "myintegration",
                      "icon_slug": "customicon",
                      "display_name": "Custom Display",
                      "auth_type": "oauth2",
                      "metadata": {
                        "oauth2Config": {
                          "authorizationUrl": "https://auth.example.com/authorize",
                          "tokenUrl": "https://auth.example.com/token",
                          "scopes": ["base.read"],
                          "byokOnlyScopes": ["full.admin"]
                        }
                      }
                    }
                    """;
            stubCatalogTemplateJson(customTemplate);

            var byokRow = new com.apimarketplace.auth.credential.domain.PlatformCredentialModels.PlatformCredential(
                    7L, "myintegration", "Custom Display",
                    com.apimarketplace.auth.credential.domain.PlatformCredentialModels.AuthType.OAUTH2,
                    "byok-cid", "byok-csec", null, null, null,
                    "https://auth.example.com/authorize", "https://auth.example.com/token", "",
                    "customicon", "Other", "desc",
                    true, Map.of(), java.math.BigDecimal.ZERO, 0,
                    Instant.now(), Instant.now(), null, USER_ID);

            // iconSlug ("customicon") and integrationName ("Custom Display") miss; credential_name hits.
            when(platformCredentialService.getRawOAuth2Credential("customicon", USER_ID, null))
                    .thenReturn(Optional.empty());
            when(platformCredentialService.getRawOAuth2Credential("Custom Display", USER_ID, null))
                    .thenReturn(Optional.empty());
            when(platformCredentialService.getRawOAuth2Credential("myintegration", USER_ID, null))
                    .thenReturn(Optional.of(byokRow));

            var request = new com.apimarketplace.auth.credential.domain.OAuth2Models.OAuth2InitiateRequest(
                    "template-custom", "Custom", null, null, "Production", null, "/app/settings/credentials");

            var response = oAuth2Service.initiate(request, USER_ID);

            assertThat(response.authorizationUrl())
                    .as("resolution must fall through to credential_name and use that row's client")
                    .contains("byok-cid");
            assertThat(response.authorizationUrl())
                    .as("the BYOK scope override still fires on the credential_name fallback path")
                    .contains("base.read")
                    .contains("full.admin");
        }

        @Test
        @DisplayName("Platform-wide row keeps the narrow template scopes (no byokOnly over-grant)")
        void platformWideInitiateKeepsTemplateScopes() throws Exception {
            stubCatalogTemplateJson(GMAIL_TEMPLATE_JSON);

            // Platform-wide row: tenant_id null → NOT BYOK, scopes stay the template send+labels.
            var platformRow = new com.apimarketplace.auth.credential.domain.PlatformCredentialModels.PlatformCredential(
                    3L, "gmail", "Gmail",
                    com.apimarketplace.auth.credential.domain.PlatformCredentialModels.AuthType.OAUTH2,
                    "platform-cid", "platform-csec", null, null, null,
                    "https://accounts.google.com/o/oauth2/v2/auth", "https://oauth2.googleapis.com/token", null,
                    "gmail", "Communication", "desc",
                    true, Map.of(), java.math.BigDecimal.ZERO, 0,
                    Instant.now(), Instant.now(), null, null);
            when(platformCredentialService.getRawOAuth2Credential("gmail", USER_ID, null))
                    .thenReturn(Optional.of(platformRow));

            var request = new com.apimarketplace.auth.credential.domain.OAuth2Models.OAuth2InitiateRequest(
                    "template-gmail", "Platform Gmail", null, null, "Production", null, "/app/settings/credentials");

            var response = oAuth2Service.initiate(request, USER_ID);

            assertThat(response.authorizationUrl())
                    .as("platform flow uses the platform client_id")
                    .contains("platform-cid");
            assertThat(response.authorizationUrl())
                    .as("platform flow requests only the narrow template scopes")
                    .contains("gmail.send")
                    .contains("gmail.labels");
            assertThat(response.authorizationUrl())
                    .as("platform flow must NOT silently widen to the BYOK-only restricted scopes")
                    .doesNotContain("gmail.readonly")
                    .doesNotContain("gmail.modify");
        }

        // ===== CE/Cloud credential-scope parity: in CE (auth.mode=embedded) there is no
        // platform-shared OAuth app - every client the install uses is the user's own - so
        // initiate() must always request the full catalog scope set (scopes ∪ byokOnlyScopes),
        // regardless of which row/path supplied the client_id/secret. =====

        @Test
        @DisplayName("CE parity regression: embedded mode + install-wide (tenant-less) row requests the byokOnly scopes too")
        void ceEmbeddedPlatformWideRowRequestsFullCatalogScopeSet() throws Exception {
            stubCatalogTemplateJson(GMAIL_TEMPLATE_JSON);
            org.springframework.test.util.ReflectionTestUtils.setField(oAuth2Service, "authMode", "embedded");

            // Tenant-less row = what the CE operator saves in Settings. In Cloud this is the
            // shared platform app (narrow scopes); in CE it IS the user's own OAuth client.
            var installWideRow = new com.apimarketplace.auth.credential.domain.PlatformCredentialModels.PlatformCredential(
                    3L, "gmail", "Gmail",
                    com.apimarketplace.auth.credential.domain.PlatformCredentialModels.AuthType.OAUTH2,
                    "ce-cid", "ce-csec", null, null, null,
                    "https://accounts.google.com/o/oauth2/v2/auth", "https://oauth2.googleapis.com/token", null,
                    "gmail", "Communication", "desc",
                    true, Map.of(), java.math.BigDecimal.ZERO, 0,
                    Instant.now(), Instant.now(), null, null);
            when(platformCredentialService.getRawOAuth2Credential("gmail", USER_ID, null))
                    .thenReturn(Optional.of(installWideRow));

            var request = new com.apimarketplace.auth.credential.domain.OAuth2Models.OAuth2InitiateRequest(
                    "template-gmail", "CE Gmail", null, null, "Production", null, "/app/settings/credentials");

            var response = oAuth2Service.initiate(request, USER_ID);

            assertThat(response.authorizationUrl())
                    .as("CE connect uses the install-wide client_id")
                    .contains("ce-cid");
            assertThat(response.authorizationUrl())
                    .as("CE must request the byokOnly scopes on a tenant-less row - pre-fix it silently "
                            + "dropped to the narrow Cloud platform scope set (send+labels only)")
                    .contains("gmail.readonly")
                    .contains("gmail.modify");
            assertThat(response.authorizationUrl())
                    .as("the base platform scopes stay part of the CE union")
                    .contains("gmail.send")
                    .contains("gmail.labels");
        }

        @Test
        @DisplayName("CE parity regression: embedded mode + inline user-provided client requests the byokOnly scopes too")
        void ceEmbeddedInlineUserCredentialsRequestFullCatalogScopeSet() throws Exception {
            stubCatalogTemplateJson(GMAIL_TEMPLATE_JSON);
            org.springframework.test.util.ReflectionTestUtils.setField(oAuth2Service, "authMode", "embedded");

            // hasUserCredentials() path: client_id/secret typed straight into the wizard.
            var request = new com.apimarketplace.auth.credential.domain.OAuth2Models.OAuth2InitiateRequest(
                    "template-gmail", "CE Gmail", "inline-cid", "inline-csec",
                    "Production", null, "/app/settings/credentials");

            var response = oAuth2Service.initiate(request, USER_ID);

            assertThat(response.authorizationUrl())
                    .as("inline path uses the user-typed client_id")
                    .contains("inline-cid");
            assertThat(response.authorizationUrl())
                    .as("CE inline connect requests the full catalog scope set (platform + byok-only)")
                    .contains("gmail.send")
                    .contains("gmail.labels")
                    .contains("gmail.readonly")
                    .contains("gmail.modify");
        }

        @Test
        @DisplayName("CE parity: embedded mode is a no-op for templates that declare no byokOnlyScopes")
        void ceEmbeddedNoByokOnlyScopesKeepsTemplateScopes() throws Exception {
            String template = """
                    {
                      "id": "template-gmail",
                      "credential_name": "gmail",
                      "icon_slug": "gmail",
                      "display_name": "Gmail",
                      "auth_type": "oauth2",
                      "metadata": {
                        "oauth2Config": {
                          "authorizationUrl": "https://accounts.google.com/o/oauth2/v2/auth",
                          "tokenUrl": "https://oauth2.googleapis.com/token",
                          "scopes": ["https://www.googleapis.com/auth/gmail.send"]
                        }
                      }
                    }
                    """;
            stubCatalogTemplateJson(template);
            org.springframework.test.util.ReflectionTestUtils.setField(oAuth2Service, "authMode", "embedded");

            var installWideRow = new com.apimarketplace.auth.credential.domain.PlatformCredentialModels.PlatformCredential(
                    3L, "gmail", "Gmail",
                    com.apimarketplace.auth.credential.domain.PlatformCredentialModels.AuthType.OAUTH2,
                    "ce-cid", "ce-csec", null, null, null,
                    "https://accounts.google.com/o/oauth2/v2/auth", "https://oauth2.googleapis.com/token", null,
                    "gmail", "Communication", "desc",
                    true, Map.of(), java.math.BigDecimal.ZERO, 0,
                    Instant.now(), Instant.now(), null, null);
            when(platformCredentialService.getRawOAuth2Credential("gmail", USER_ID, null))
                    .thenReturn(Optional.of(installWideRow));

            var request = new com.apimarketplace.auth.credential.domain.OAuth2Models.OAuth2InitiateRequest(
                    "template-gmail", "CE Gmail", null, null, "Production", null, "/app/settings/credentials");

            var response = oAuth2Service.initiate(request, USER_ID);

            assertThat(response.authorizationUrl())
                    .as("no byokOnlyScopes declared → the template scope set is requested as-is")
                    .contains("gmail.send")
                    .doesNotContain("gmail.readonly")
                    .doesNotContain("gmail.modify");
        }

        @Test
        @DisplayName("CE parity: a tenant BYOK row in embedded mode still gets the full scope set (no regression from the CE branch)")
        void ceEmbeddedTenantByokRowStillGetsFullScopeSet() throws Exception {
            stubCatalogTemplateJson(GMAIL_TEMPLATE_JSON);
            org.springframework.test.util.ReflectionTestUtils.setField(oAuth2Service, "authMode", "embedded");

            var byokRow = new com.apimarketplace.auth.credential.domain.PlatformCredentialModels.PlatformCredential(
                    1L, "gmail", "Gmail",
                    com.apimarketplace.auth.credential.domain.PlatformCredentialModels.AuthType.OAUTH2,
                    "byok-cid", "byok-csec", null, null, null,
                    "https://accounts.google.com/o/oauth2/v2/auth", "https://oauth2.googleapis.com/token", "",
                    "gmail", "Communication", "desc",
                    true, Map.of(), java.math.BigDecimal.ZERO, 0,
                    Instant.now(), Instant.now(), null, USER_ID);
            when(platformCredentialService.getRawOAuth2Credential("gmail", USER_ID, null))
                    .thenReturn(Optional.of(byokRow));

            var request = new com.apimarketplace.auth.credential.domain.OAuth2Models.OAuth2InitiateRequest(
                    "template-gmail", "My Gmail", null, null, "Production", null, "/app/settings/credentials");

            var response = oAuth2Service.initiate(request, USER_ID);

            assertThat(response.authorizationUrl())
                    .as("tenant BYOK row in CE keeps the full union (the CE branch is idempotent over "
                            + "the Cloud tenant-BYOK widening)")
                    .contains("gmail.send")
                    .contains("gmail.labels")
                    .contains("gmail.readonly")
                    .contains("gmail.modify");
        }

        @Test
        @DisplayName("Cloud regression: keycloak mode + inline user-provided client keeps the narrow template scopes")
        void cloudInlineUserCredentialsKeepTemplateScopes() throws Exception {
            stubCatalogTemplateJson(GMAIL_TEMPLATE_JSON);
            org.springframework.test.util.ReflectionTestUtils.setField(oAuth2Service, "authMode", "keycloak");

            var request = new com.apimarketplace.auth.credential.domain.OAuth2Models.OAuth2InitiateRequest(
                    "template-gmail", "Cloud Gmail", "inline-cid", "inline-csec",
                    "Production", null, "/app/settings/credentials");

            var response = oAuth2Service.initiate(request, USER_ID);

            assertThat(response.authorizationUrl())
                    .as("Cloud inline path is unchanged by the CE branch - narrow template scopes only")
                    .contains("gmail.send")
                    .contains("gmail.labels")
                    .doesNotContain("gmail.readonly")
                    .doesNotContain("gmail.modify");
        }

        @Test
        @DisplayName("client_credentials initiate creates credential immediately using query token params")
        void clientCredentialsInitiateCreatesCredentialImmediately() throws Exception {
            String template = """
                    {
                      "id": "template-marketo",
                      "credential_name": "marketo",
                      "icon_slug": "marketo",
                      "display_name": "Marketo",
                      "auth_type": "oauth2",
                      "metadata": {
                        "oauth2Config": {
                          "grantType": "client_credentials",
                          "tokenUrl": "https://{munchkin_id}.mktorest.com/identity/oauth/token",
                          "tokenParamsLocation": "query",
                          "scopes": [],
                          "refresh": {
                            "supported": false,
                            "reason": "client_credentials flow"
                          }
                        }
                      }
                    }
                    """;
            stubCatalogTemplateJson(template);
            var row = new com.apimarketplace.auth.credential.domain.PlatformCredentialModels.PlatformCredential(
                    10L, "marketo", "Marketo",
                    com.apimarketplace.auth.credential.domain.PlatformCredentialModels.AuthType.OAUTH2,
                    "mkto-cid", "mkto-secret", null, null, null,
                    null, "https://123-ABC-456.mktorest.com/identity/oauth/token", "",
                    "marketo", "Marketing", "desc",
                    true, Map.of("munchkin_id", "123-ABC-456"), java.math.BigDecimal.ZERO, 0,
                    Instant.now(), Instant.now(), null, null);
            when(platformCredentialService.getRawOAuth2Credential("marketo", USER_ID, "org-123"))
                    .thenReturn(Optional.of(row));
            when(encryptionService.encrypt(anyString())).thenAnswer(inv -> "ENC:" + inv.getArgument(0));

            com.fasterxml.jackson.databind.node.ObjectNode tokenBody = objectMapper.createObjectNode();
            tokenBody.put("access_token", "new-at");
            tokenBody.put("token_type", "bearer");
            tokenBody.put("expires_in", 3600);
            org.springframework.web.client.RestTemplate mockRest =
                    mock(org.springframework.web.client.RestTemplate.class);
            org.springframework.test.util.ReflectionTestUtils.setField(
                    oAuth2Service, "restTemplate", mockRest);
            String tokenUrl = "https://123-ABC-456.mktorest.com/identity/oauth/token"
                    + "?grant_type=client_credentials&client_id=mkto-cid&client_secret=mkto-secret";
            when(mockRest.postForEntity(eq(tokenUrl),
                    any(org.springframework.http.HttpEntity.class),
                    eq(com.fasterxml.jackson.databind.JsonNode.class)))
                    .thenReturn(new org.springframework.http.ResponseEntity<>(
                            (com.fasterxml.jackson.databind.JsonNode) tokenBody,
                            org.springframework.http.HttpStatus.OK));
            when(credentialService.createCredential(
                    eq(USER_ID), eq("org-123"), eq("Marketo Prod"), eq("marketo"),
                    eq(CredentialType.OAuth2), eq(CredentialEnvironment.Production),
                    anyString(), anyMap(), anyList(), anyList(),
                    eq(USER_ID), any()))
                    .thenReturn(buildCredential(42L, USER_ID, "marketo", Map.of()));

            var request = new com.apimarketplace.auth.credential.domain.OAuth2Models.OAuth2InitiateRequest(
                    "template-marketo", "Marketo Prod", null, null, "Production", null, "/app/settings/credentials");

            var response = oAuth2Service.initiate(request, USER_ID, "org-123");

            assertThat(response.authorizationUrl())
                    .isEqualTo("/app/settings/credentials?success=true&credentialId=42");
            assertThat(response.state()).isEqualTo("client_credentials");
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
            verify(credentialService).createCredential(
                    eq(USER_ID), eq("org-123"), eq("Marketo Prod"), eq("marketo"),
                    eq(CredentialType.OAuth2), eq(CredentialEnvironment.Production),
                    anyString(), dataCaptor.capture(), anyList(), anyList(),
                    eq(USER_ID), any());
            assertThat(dataCaptor.getValue())
                    .containsEntry("munchkin_id", "123-ABC-456")
                    .containsEntry("access_token", "ENC:new-at")
                    .containsEntry("oauth_client_secret", "ENC:mkto-secret");
        }

        @Test
        @DisplayName("Custom API (no catalog oauth2Config): the row's scopes survive via the providerConfig fallback")
        void customApiByokScopesSurviveViaProviderConfig() throws Exception {
            // Custom API: template has NO metadata.oauth2Config, so extractOAuth2Config returns null
            // and initiate() rebuilds providerConfig from the tenant row (authUrl/tokenUrl/
            // defaultScopes). This is the path that makes dropping the default_scopes UNION safe -
            // the row's scopes still reach the authorize URL through providerConfig.scopes().
            String customTemplate = """
                    {
                      "id": "template-mycustomapi",
                      "credential_name": "mycustomapi",
                      "icon_slug": "mycustomapi",
                      "display_name": "My Custom API",
                      "auth_type": "oauth2"
                    }
                    """;
            stubCatalogTemplateJson(customTemplate);

            var row = new com.apimarketplace.auth.credential.domain.PlatformCredentialModels.PlatformCredential(
                    9L, "mycustomapi", "My Custom API",
                    com.apimarketplace.auth.credential.domain.PlatformCredentialModels.AuthType.OAUTH2,
                    "custom-cid", "custom-csec", null, null, null,
                    "https://custom.example.com/authorize", "https://custom.example.com/token",
                    "https://custom.example.com/scope.read https://custom.example.com/scope.write",
                    "mycustomapi", "Other", "desc",
                    true, Map.of(), java.math.BigDecimal.ZERO, 0,
                    Instant.now(), Instant.now(), null, USER_ID);
            // initiate()'s custom-API fallback resolves the row via getRawCredential(credName,…);
            // the BYOK client/scope override resolves it via getRawOAuth2Credential(iconSlug,…).
            when(platformCredentialService.getRawCredential("mycustomapi", USER_ID, null))
                    .thenReturn(Optional.of(row));
            when(platformCredentialService.getRawOAuth2Credential("mycustomapi", USER_ID, null))
                    .thenReturn(Optional.of(row));

            var request = new com.apimarketplace.auth.credential.domain.OAuth2Models.OAuth2InitiateRequest(
                    "template-mycustomapi", "My Custom", null, null, "Production", null, "/app/settings/credentials");

            var response = oAuth2Service.initiate(request, USER_ID);

            assertThat(response.authorizationUrl())
                    .as("custom API uses the user's own client_id")
                    .contains("custom-cid");
            assertThat(response.authorizationUrl())
                    .as("custom API scopes (from the row) survive via providerConfig even though the "
                            + "default_scopes union was removed - they have no catalog to come from")
                    .contains("scope.read")
                    .contains("scope.write");
        }

        /** Stub the catalog WebClient GET so fetchCredentialTemplate(id) returns the given JSON. */
        @SuppressWarnings({"unchecked", "rawtypes"})
        private void stubCatalogTemplateJson(String json) throws Exception {
            com.fasterxml.jackson.databind.JsonNode template = objectMapper.readTree(json);
            org.springframework.web.reactive.function.client.WebClient wc =
                    mock(org.springframework.web.reactive.function.client.WebClient.class);
            var uriSpec = mock(
                    org.springframework.web.reactive.function.client.WebClient.RequestHeadersUriSpec.class);
            var headersSpec = mock(
                    org.springframework.web.reactive.function.client.WebClient.RequestHeadersSpec.class);
            var responseSpec = mock(
                    org.springframework.web.reactive.function.client.WebClient.ResponseSpec.class);
            when(wc.get()).thenReturn(uriSpec);
            when(uriSpec.uri(anyString(), any(Object[].class))).thenReturn(headersSpec);
            when(headersSpec.retrieve()).thenReturn(responseSpec);
            when(responseSpec.bodyToMono(com.fasterxml.jackson.databind.JsonNode.class))
                    .thenReturn(reactor.core.publisher.Mono.just(template));
            org.springframework.test.util.ReflectionTestUtils.setField(
                    oAuth2Service, "catalogClient", wc);
        }
    }

    // ========== OAuth2State Record ==========

    @Nested
    @DisplayName("OAuth2State record")
    class OAuth2StateTests {

        @Test
        @DisplayName("should serialize and deserialize via ObjectMapper")
        void oauth2State_serializationRoundTrip() throws Exception {
            var state = new com.apimarketplace.auth.credential.domain.OAuth2Models.OAuth2State(
                    "user-1", "template-1", "My Cred",
                    "client-id", "client-secret",
                    "https://auth.url", "https://token.url",
                    "email profile", "Production", "gmail",
                    "https://icon.url", "/settings", Instant.parse("2026-01-01T00:00:00Z")
            );

            String json = objectMapper.writeValueAsString(state);
            var deserialized = objectMapper.readValue(json,
                    com.apimarketplace.auth.credential.domain.OAuth2Models.OAuth2State.class);

            assertThat(deserialized.userId()).isEqualTo("user-1");
            assertThat(deserialized.credentialTemplateId()).isEqualTo("template-1");
            assertThat(deserialized.credentialName()).isEqualTo("My Cred");
            assertThat(deserialized.clientId()).isEqualTo("client-id");
            assertThat(deserialized.clientSecret()).isEqualTo("client-secret");
            assertThat(deserialized.scope()).isEqualTo("email profile");
            assertThat(deserialized.integration()).isEqualTo("gmail");
            assertThat(deserialized.returnUrl()).isEqualTo("/settings");
        }
    }

    // ========== OAuth2TokenResponse Record ==========

    @Nested
    @DisplayName("OAuth2TokenResponse record")
    class OAuth2TokenResponseTests {

        @Test
        @DisplayName("should hold token response fields")
        void tokenResponse_fields() {
            var response = new com.apimarketplace.auth.credential.domain.OAuth2Models.OAuth2TokenResponse(
                    "access-tok", "refresh-tok", "Bearer", 3600L, "email profile"
            );

            assertThat(response.accessToken()).isEqualTo("access-tok");
            assertThat(response.refreshToken()).isEqualTo("refresh-tok");
            assertThat(response.tokenType()).isEqualTo("Bearer");
            assertThat(response.expiresIn()).isEqualTo(3600L);
            assertThat(response.scope()).isEqualTo("email profile");
        }

        @Test
        @DisplayName("should allow null optional fields")
        void tokenResponse_nullableFields() {
            var response = new com.apimarketplace.auth.credential.domain.OAuth2Models.OAuth2TokenResponse(
                    "access-tok", null, null, null, null
            );

            assertThat(response.accessToken()).isEqualTo("access-tok");
            assertThat(response.refreshToken()).isNull();
            assertThat(response.expiresIn()).isNull();
        }
    }

    // ========== Thundering-herd guards (fast-path + double-check + DB authoritative) ==========

    @Nested
    @DisplayName("refreshToken - guard order")
    class GuardOrderTests {

        /**
         * PR1 atomic design: a {@code oauth2:refresh-disabled:<id>} sentinel with value
         * {@code "terminal_user"} (written when a prior refresh hit invalid_grant) must short-
         * circuit the entire pipeline - no SETNX lock acquire, no credential load, no POST to the
         * provider. Key scenario: 100 parallel callers arriving on a known-revoked token must not
         * all send a wasted POST. Only 1 wins the lock anyway, but even that single POST is
         * unnecessary when the sentinel is set.
         */
        @Test
        @DisplayName("disabled sentinel=terminal_user short-circuits before lock + DB load")
        void disabledSentinelTerminalUserSkipsLockAndDbLoad() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get("oauth2:refresh-disabled:1")).thenReturn("terminal_user");

            assertThatThrownBy(() -> oAuth2Service.refreshToken(1L, USER_ID))
                    .isInstanceOf(com.apimarketplace.auth.credential.service.oauth2.refresh.RefreshTerminalException.class)
                    .satisfies(e -> {
                        var terminal = (com.apimarketplace.auth.credential.service.oauth2.refresh.RefreshTerminalException) e;
                        assertThat(terminal.bucket()).isEqualTo(
                                com.apimarketplace.auth.credential.service.oauth2.refresh.RefreshErrorBucket.TERMINAL_USER);
                    });

            // Crucial: the fast path must prevent any lock acquire AND any DB load. If SETNX runs,
            // the sentinel was defeated and 100 callers still hammer Redis. If getCredential runs,
            // the sentinel was defeated and we paid a DB round-trip per caller.
            verify(valueOperations, never()).setIfAbsent(
                    startsWith("oauth2:refresh-lock:"), anyString(), any(java.time.Duration.class));
            verify(credentialService, never()).getCredential(anyLong());
        }

        @Test
        @DisplayName("disabled sentinel=terminal_config → TERMINAL_CONFIG bucket")
        void disabledSentinelTerminalConfigMapsCorrectly() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get("oauth2:refresh-disabled:1")).thenReturn("terminal_config");

            assertThatThrownBy(() -> oAuth2Service.refreshToken(1L, USER_ID))
                    .isInstanceOf(com.apimarketplace.auth.credential.service.oauth2.refresh.RefreshTerminalException.class)
                    .satisfies(e -> {
                        var terminal = (com.apimarketplace.auth.credential.service.oauth2.refresh.RefreshTerminalException) e;
                        assertThat(terminal.bucket()).isEqualTo(
                                com.apimarketplace.auth.credential.service.oauth2.refresh.RefreshErrorBucket.TERMINAL_CONFIG);
                    });
            verify(credentialService, never()).getCredential(anyLong());
        }

        /**
         * A cooldown sentinel is the backoff marker - present during transient outages. Callers
         * get a RefreshTransientException so the upstream layer falls back to the stored token
         * rather than failing. Still must not hit DB or provider.
         */
        @Test
        @DisplayName("cooldown sentinel → transient, no lock acquire, no DB load")
        void cooldownSentinelSkipsLockAndDbLoad() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get("oauth2:refresh-disabled:1")).thenReturn(null);
            when(valueOperations.get("oauth2:refresh-cooldown:1")).thenReturn("until:some-iso");

            assertThatThrownBy(() -> oAuth2Service.refreshToken(1L, USER_ID))
                    .isInstanceOf(com.apimarketplace.auth.credential.service.oauth2.refresh.RefreshTransientException.class);

            verify(valueOperations, never()).setIfAbsent(
                    startsWith("oauth2:refresh-lock:"), anyString(), any(java.time.Duration.class));
            verify(credentialService, never()).getCredential(anyLong());
        }

        /**
         * Redis outage must not break the refresh pipeline - the DB gate is the authoritative
         * source of truth. When Redis is down the fast-path gate swallows the exception and we
         * fall through to the DB gate, which catches the same terminal state from the persisted
         * credential row.
         */
        @Test
        @DisplayName("Redis down during fast-path → falls through to DB gate (status=needs_reauth)")
        void redisDownStillHitsDbAuthoritativeGate() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            // Redis GET blows up - e.g. connection refused.
            when(valueOperations.get(anyString()))
                    .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("boom"));
            // Lock acquire still succeeds (using the other prefix-based stub from @BeforeEach).
            when(valueOperations.setIfAbsent(
                    startsWith("oauth2:refresh-lock:"), anyString(), any(java.time.Duration.class)))
                    .thenReturn(true);
            when(redisTemplate.execute(
                    any(org.springframework.data.redis.core.script.RedisScript.class), anyList(), any()))
                    .thenReturn(1L);

            // DB has the credential in needs_reauth → authoritativeGate throws TERMINAL_USER.
            Credential cred = buildCredentialWithStatus(1L, USER_ID, CredentialStatus.needs_reauth);
            when(credentialService.getCredential(1L)).thenReturn(Optional.of(cred));

            assertThatThrownBy(() -> oAuth2Service.refreshToken(1L, USER_ID))
                    .isInstanceOf(com.apimarketplace.auth.credential.service.oauth2.refresh.RefreshTerminalException.class)
                    .satisfies(e -> {
                        var terminal = (com.apimarketplace.auth.credential.service.oauth2.refresh.RefreshTerminalException) e;
                        assertThat(terminal.bucket()).isEqualTo(
                                com.apimarketplace.auth.credential.service.oauth2.refresh.RefreshErrorBucket.TERMINAL_USER);
                        assertThat(terminal.reason()).contains("needs_reauth");
                    });
        }

        @Test
        @DisplayName("DB authoritative: status=error → TERMINAL_CONFIG")
        void dbErrorStatusIsTerminalConfig() {
            Credential cred = buildCredentialWithStatus(1L, USER_ID, CredentialStatus.error);
            when(credentialService.getCredential(1L)).thenReturn(Optional.of(cred));

            assertThatThrownBy(() -> oAuth2Service.refreshToken(1L, USER_ID))
                    .isInstanceOf(com.apimarketplace.auth.credential.service.oauth2.refresh.RefreshTerminalException.class)
                    .satisfies(e -> {
                        var terminal = (com.apimarketplace.auth.credential.service.oauth2.refresh.RefreshTerminalException) e;
                        assertThat(terminal.bucket()).isEqualTo(
                                com.apimarketplace.auth.credential.service.oauth2.refresh.RefreshErrorBucket.TERMINAL_CONFIG);
                    });
        }

        /**
         * Cooldown_until in the future is an in-DB transient marker (the Redis sentinel may have
         * been evicted). It must be honored by the authoritative gate.
         */
        @Test
        @DisplayName("DB authoritative: cooldown_until in future → TRANSIENT")
        void dbCooldownInFutureIsTransient() {
            String future = Instant.now().plusSeconds(300).toString();
            Credential cred = buildCredential(1L, USER_ID, Map.of(
                    "refresh_token", "rt",
                    "refresh_cooldown_until", future
            ));
            when(credentialService.getCredential(1L)).thenReturn(Optional.of(cred));

            assertThatThrownBy(() -> oAuth2Service.refreshToken(1L, USER_ID))
                    .isInstanceOf(com.apimarketplace.auth.credential.service.oauth2.refresh.RefreshTransientException.class);
        }

        @Test
        @DisplayName("DB authoritative: cooldown_until in past is ignored (proceeds)")
        void dbCooldownInPastIsIgnored() {
            String past = Instant.now().minusSeconds(300).toString();
            Credential cred = buildCredential(1L, USER_ID, Map.of(
                    "refresh_token", "rt",
                    "refresh_cooldown_until", past
                    // No credential_template_id - refresh will fail downstream, but the gate passes.
            ));
            when(credentialService.getCredential(1L)).thenReturn(Optional.of(cred));

            // Gate passes → downstream refresh path fails due to missing template_id, NOT a gate throw.
            assertThatThrownBy(() -> oAuth2Service.refreshToken(1L, USER_ID))
                    .isNotInstanceOf(com.apimarketplace.auth.credential.service.oauth2.refresh.RefreshTerminalException.class)
                    .isNotInstanceOf(com.apimarketplace.auth.credential.service.oauth2.refresh.RefreshTransientException.class);
        }

        /**
         * Malformed cooldown_until must not throw - we log + continue. Otherwise a stray bad
         * value (old migration artifact) would permanently brick every refresh for that credential.
         */
        @Test
        @DisplayName("DB authoritative: malformed cooldown_until logs + continues")
        void dbMalformedCooldownIgnored() {
            Credential cred = buildCredential(1L, USER_ID, Map.of(
                    "refresh_token", "rt",
                    "refresh_cooldown_until", "not-a-timestamp"
            ));
            when(credentialService.getCredential(1L)).thenReturn(Optional.of(cred));

            assertThatThrownBy(() -> oAuth2Service.refreshToken(1L, USER_ID))
                    .isNotInstanceOf(com.apimarketplace.auth.credential.service.oauth2.refresh.RefreshTerminalException.class)
                    .isNotInstanceOf(com.apimarketplace.auth.credential.service.oauth2.refresh.RefreshTransientException.class);
        }

        /**
         * New sentinel encoding is {@code "<state>|<provider>"}. Fast-path parses out the
         * provider hint so a purely Redis-path rejection still records the metric with a typed
         * provider label (instead of {@code "unknown"}). Bare-state values from pre-upgrade
         * sentinels must still work - that branch is already covered by the two
         * {@code disabledSentinel*} tests above which pass bare {@code "terminal_user"} /
         * {@code "terminal_config"} values.
         */
        @Test
        @DisplayName("piped sentinel 'terminal_user|gmail' → still TERMINAL_USER, reason carries only the state")
        void pipedDisabledSentinelIsParsedCorrectly() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get("oauth2:refresh-disabled:1")).thenReturn("terminal_user|gmail");

            assertThatThrownBy(() -> oAuth2Service.refreshToken(1L, USER_ID))
                    .isInstanceOf(com.apimarketplace.auth.credential.service.oauth2.refresh.RefreshTerminalException.class)
                    .satisfies(e -> {
                        var terminal = (com.apimarketplace.auth.credential.service.oauth2.refresh.RefreshTerminalException) e;
                        assertThat(terminal.bucket()).isEqualTo(
                                com.apimarketplace.auth.credential.service.oauth2.refresh.RefreshErrorBucket.TERMINAL_USER);
                        // The message reflects the parsed state only - the provider hint must not
                        // leak into the sentinel= suffix (otherwise log-scanning scripts break).
                        assertThat(terminal.reason()).contains("terminal_user").doesNotContain("|gmail");
                    });

            verify(credentialService, never()).getCredential(anyLong());
        }

        @Test
        @DisplayName("piped cooldown sentinel '2026-01-01T00:00:00Z|slack' → TRANSIENT, no DB load")
        void pipedCooldownSentinelIsParsedCorrectly() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get("oauth2:refresh-disabled:1")).thenReturn(null);
            when(valueOperations.get("oauth2:refresh-cooldown:1"))
                    .thenReturn("2026-01-01T00:00:00Z|slack");

            assertThatThrownBy(() -> oAuth2Service.refreshToken(1L, USER_ID))
                    .isInstanceOf(com.apimarketplace.auth.credential.service.oauth2.refresh.RefreshTransientException.class);

            verify(credentialService, never()).getCredential(anyLong());
        }

        /** @BeforeEach - acquire-lock stub, mirrors RefreshTokenTests.acquireLockByDefault. */
        @BeforeEach
        void acquireLockByDefault() {
            lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            lenient().when(valueOperations.setIfAbsent(
                    startsWith("oauth2:refresh-lock:"),
                    anyString(),
                    any(java.time.Duration.class)))
                    .thenReturn(true);
            lenient().when(redisTemplate.execute(
                    any(org.springframework.data.redis.core.script.RedisScript.class),
                    anyList(),
                    any()))
                    .thenReturn(1L);
        }
    }

    // ========== releaseTerminal defensive behavior ==========

    @Nested
    @DisplayName("releaseTerminal - persistence failure must not mask the refresh error")
    class ReleaseTerminalTests {

        /**
         * Contract: if scrubSensitiveFields throws (DB outage, contention, constraint violation)
         * we must still attempt to write the Redis sentinel. Otherwise a partial state (stale DB
         * row + no sentinel) leaves a revoked credential looking refreshable - 100 concurrent
         * callers then stampede the provider's token endpoint.
         */
        @Test
        @DisplayName("scrubSensitiveFields throws → Redis sentinel is still written")
        void scrubFailureDoesNotBlockSentinel() {
            com.apimarketplace.auth.credential.service.oauth2.refresh.RefreshTerminalException terminal
                    = new com.apimarketplace.auth.credential.service.oauth2.refresh.RefreshTerminalException(
                    com.apimarketplace.auth.credential.service.oauth2.refresh.RefreshErrorBucket.TERMINAL_USER,
                    "invalid_grant", 400, "refresh token revoked");
            Credential cred = buildCredentialWithStatus(42L, USER_ID, CredentialStatus.active);
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            doThrow(new RuntimeException("db offline"))
                    .when(credentialService).scrubSensitiveFields(
                            anyLong(), anyString(), anySet(), any(), anyMap());

            oAuth2Service.releaseTerminal(cred, terminal);

            // The sentinel write is a hard requirement - without it the fast-path gate cannot
            // short-circuit a revoked credential on the next refresh attempt.
            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
            verify(valueOperations).set(keyCaptor.capture(), valueCaptor.capture(), any(java.time.Duration.class));
            assertThat(keyCaptor.getValue()).isEqualTo("oauth2:refresh-disabled:42");
            // Provider hint must be encoded so the fast-path metric can tag the label.
            assertThat(valueCaptor.getValue()).isEqualTo("terminal_user|gmail");
            verify(redisTemplate).delete("oauth2:refresh-cooldown:42");
        }

        /**
         * Contract: if Redis is down when we try to write the sentinel, the scrub must have
         * happened FIRST (DB is the source of truth). Otherwise a Redis outage could leave the
         * credential's tokens in place after a known-revoked failure - the exact scenario the
         * terminal path exists to prevent.
         */
        @Test
        @DisplayName("Redis down → scrubSensitiveFields was still called first")
        void redisDownDoesNotBlockDbScrub() {
            com.apimarketplace.auth.credential.service.oauth2.refresh.RefreshTerminalException terminal
                    = new com.apimarketplace.auth.credential.service.oauth2.refresh.RefreshTerminalException(
                    com.apimarketplace.auth.credential.service.oauth2.refresh.RefreshErrorBucket.TERMINAL_CONFIG,
                    "invalid_scope", 400, "scope drift");
            Credential cred = buildCredentialWithStatus(42L, USER_ID, CredentialStatus.active);
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            doThrow(new org.springframework.data.redis.RedisConnectionFailureException("redis down"))
                    .when(valueOperations).set(anyString(), anyString(), any(java.time.Duration.class));

            oAuth2Service.releaseTerminal(cred, terminal);

            ArgumentCaptor<java.util.Set<String>> fieldsCaptor = ArgumentCaptor.forClass(java.util.Set.class);
            ArgumentCaptor<CredentialStatus> statusCaptor = ArgumentCaptor.forClass(CredentialStatus.class);
            verify(credentialService).scrubSensitiveFields(
                    eq(42L), eq(USER_ID), fieldsCaptor.capture(), statusCaptor.capture(), anyMap());
            // TERMINAL_CONFIG → error status, access_token + refresh_token must be in the scrub set.
            assertThat(statusCaptor.getValue()).isEqualTo(CredentialStatus.error);
            assertThat(fieldsCaptor.getValue()).contains("access_token", "refresh_token");
        }
    }

    // ========== Helpers ==========

    private Credential buildCredential(Long id, String tenantId, Map<String, Object> data) {
        return buildCredential(id, tenantId, "gmail", data);
    }

    private Credential buildCredential(Long id, String tenantId, String integration, Map<String, Object> data) {
        Instant now = Instant.now();
        return new Credential(
                id, tenantId, "test-cred", integration,
                CredentialType.OAuth2, CredentialEnvironment.Production,
                CredentialStatus.active, "Test credential",
                data,
                List.of("email"), List.of("oauth2"),
                tenantId, "icon.url", true,
                null, now, now
        );
    }

    private Credential buildCredentialWithStatus(Long id, String tenantId, CredentialStatus status) {
        Instant now = Instant.now();
        return new Credential(
                id, tenantId, "test-cred", "gmail",
                CredentialType.OAuth2, CredentialEnvironment.Production,
                status, "Test credential",
                Map.of("refresh_token", "rt"),
                List.of("email"), List.of("oauth2"),
                tenantId, "icon.url", true,
                null, now, now
        );
    }
}
