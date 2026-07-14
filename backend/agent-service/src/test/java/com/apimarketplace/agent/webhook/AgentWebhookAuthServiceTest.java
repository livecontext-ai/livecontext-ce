package com.apimarketplace.agent.webhook;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.Base64;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AgentWebhookAuthService Tests")
class AgentWebhookAuthServiceTest {

    private AgentWebhookAuthService service;

    @BeforeEach
    void setUp() {
        service = new AgentWebhookAuthService();
    }

    // ---------------------------------------------------------------------------
    // Helper: build AgentWebhookConfig with the real record (httpMethod is first field)
    // ---------------------------------------------------------------------------

    private static AgentWebhookConfig configWithNoAuth() {
        return new AgentWebhookConfig("POST", "none", null, null, null, null, null, null);
    }

    private static AgentWebhookConfig basicConfig(String username, String password) {
        return new AgentWebhookConfig("POST", "basic", username, password, null, null, null, null);
    }

    private static AgentWebhookConfig headerConfig(String headerName, String headerValue) {
        return new AgentWebhookConfig("POST", "header", null, null, headerName, headerValue, null, null);
    }

    private static AgentWebhookConfig jwtConfig(String secretKey, String algorithm) {
        return new AgentWebhookConfig("POST", "jwt", null, null, null, null, secretKey, algorithm);
    }

    private static HttpHeaders headersWithBasic(String username, String password) {
        String credentials = username + ":" + password;
        String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Basic " + encoded);
        return headers;
    }

    private static HttpHeaders headersWithBearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return headers;
    }

    private static String buildJwt(String secret) {
        return JWT.create()
                .withIssuer("test")
                .sign(Algorithm.HMAC256(secret));
    }

    // ---------------------------------------------------------------------------
    // validateAuth (top-level dispatch)
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("validateAuth()")
    class ValidateAuthTests {

        @Test
        @DisplayName("should return success when config is null")
        void shouldSucceedWhenConfigIsNull() {
            AgentWebhookAuthService.AuthResult result = service.validateAuth(null, new HttpHeaders());

            assertThat(result.valid()).isTrue();
            assertThat(result.message()).isNull();
        }

        @Test
        @DisplayName("should return success when config has authType none")
        void shouldSucceedWhenNoAuthRequired() {
            AgentWebhookAuthService.AuthResult result = service.validateAuth(configWithNoAuth(), new HttpHeaders());

            assertThat(result.valid()).isTrue();
        }

        @Test
        @DisplayName("should return success when config authType is null (no auth)")
        void shouldSucceedWhenAuthTypeIsNull() {
            AgentWebhookConfig config = new AgentWebhookConfig("POST", null, null, null, null, null, null, null);

            AgentWebhookAuthService.AuthResult result = service.validateAuth(config, new HttpHeaders());

            assertThat(result.valid()).isTrue();
        }

        @Test
        @DisplayName("should FAIL CLOSED for an unrecognized auth type (requiresAuth is true)")
        void shouldFailClosedForUnknownAuthType() {
            // Regression: an authType outside {none,basic,header,jwt} still makes
            // requiresAuth()==true, so it must be rejected, not accepted with no check.
            AgentWebhookConfig config = new AgentWebhookConfig("POST", "custom_unknown", null, null, null, null, null, null);

            AgentWebhookAuthService.AuthResult result = service.validateAuth(config, new HttpHeaders());

            assertThat(result.valid()).isFalse();
            assertThat(result.message()).contains("Unsupported authentication type");
        }

        @Test
        @DisplayName("should FAIL CLOSED for 'bearer' authType even with a Bearer header present")
        void shouldFailClosedForBearerLikeType() {
            AgentWebhookConfig config = new AgentWebhookConfig("POST", "bearer", null, null, null, null, null, null);
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer anything");

            AgentWebhookAuthService.AuthResult result = service.validateAuth(config, headers);

            assertThat(result.valid()).isFalse();
        }
    }

    // ---------------------------------------------------------------------------
    // Basic Auth
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("validateAuth() - Basic auth")
    class BasicAuthTests {

        @Test
        @DisplayName("should pass with correct username and password")
        void shouldPassWithCorrectCredentials() {
            AgentWebhookAuthService.AuthResult result = service.validateAuth(
                    basicConfig("alice", "s3cr3t"),
                    headersWithBasic("alice", "s3cr3t"));

            assertThat(result.valid()).isTrue();
        }

        @Test
        @DisplayName("should fail when password is wrong")
        void shouldFailWithWrongPassword() {
            AgentWebhookAuthService.AuthResult result = service.validateAuth(
                    basicConfig("alice", "s3cr3t"),
                    headersWithBasic("alice", "wrongpass"));

            assertThat(result.valid()).isFalse();
            assertThat(result.message()).isNotBlank();
        }

        @Test
        @DisplayName("should fail when username is wrong")
        void shouldFailWithWrongUsername() {
            AgentWebhookAuthService.AuthResult result = service.validateAuth(
                    basicConfig("alice", "s3cr3t"),
                    headersWithBasic("bob", "s3cr3t"));

            assertThat(result.valid()).isFalse();
        }

        @Test
        @DisplayName("should fail when Authorization header is missing entirely")
        void shouldFailWhenHeaderMissing() {
            AgentWebhookAuthService.AuthResult result = service.validateAuth(
                    basicConfig("alice", "s3cr3t"),
                    new HttpHeaders());

            assertThat(result.valid()).isFalse();
            assertThat(result.message()).containsIgnoringCase("missing");
        }

        @Test
        @DisplayName("should fail when Authorization header has wrong scheme")
        void shouldFailWithWrongScheme() {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer sometoken");

            AgentWebhookAuthService.AuthResult result = service.validateAuth(
                    basicConfig("alice", "s3cr3t"), headers);

            assertThat(result.valid()).isFalse();
        }

        @Test
        @DisplayName("should fail when Authorization header contains invalid Base64")
        void shouldFailWithInvalidBase64() {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.AUTHORIZATION, "Basic !!!not-valid-base64!!!");

            AgentWebhookAuthService.AuthResult result = service.validateAuth(
                    basicConfig("alice", "s3cr3t"), headers);

            assertThat(result.valid()).isFalse();
            assertThat(result.message()).containsIgnoringCase("encoding");
        }

        @Test
        @DisplayName("should fail with server configuration error when expected credentials are null")
        void shouldFailWhenExpectedCredentialsAreNull() {
            AgentWebhookAuthService.AuthResult result = service.validateAuth(
                    basicConfig(null, null),
                    headersWithBasic("alice", "s3cr3t"));

            assertThat(result.valid()).isFalse();
            assertThat(result.message()).containsIgnoringCase("configuration");
        }
    }

    // ---------------------------------------------------------------------------
    // Header Auth
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("validateAuth() - Header auth")
    class HeaderAuthTests {

        @Test
        @DisplayName("should pass when request contains the correct custom header and value")
        void shouldPassWithCorrectHeader() {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Api-Key", "my-secret-key");

            AgentWebhookAuthService.AuthResult result = service.validateAuth(
                    headerConfig("X-Api-Key", "my-secret-key"), headers);

            assertThat(result.valid()).isTrue();
        }

        @Test
        @DisplayName("should fail when required custom header is absent")
        void shouldFailWhenHeaderMissing() {
            AgentWebhookAuthService.AuthResult result = service.validateAuth(
                    headerConfig("X-Api-Key", "my-secret-key"),
                    new HttpHeaders());

            assertThat(result.valid()).isFalse();
            assertThat(result.message()).containsIgnoringCase("missing");
        }

        @Test
        @DisplayName("should fail when custom header value is wrong")
        void shouldFailWithWrongHeaderValue() {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Api-Key", "wrong-value");

            AgentWebhookAuthService.AuthResult result = service.validateAuth(
                    headerConfig("X-Api-Key", "my-secret-key"), headers);

            assertThat(result.valid()).isFalse();
        }

        @Test
        @DisplayName("should fail with server configuration error when header name is null")
        void shouldFailWhenHeaderNameIsNull() {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Api-Key", "my-secret-key");

            AgentWebhookAuthService.AuthResult result = service.validateAuth(
                    headerConfig(null, "my-secret-key"), headers);

            assertThat(result.valid()).isFalse();
            assertThat(result.message()).containsIgnoringCase("configuration");
        }

        @Test
        @DisplayName("should fail with server configuration error when header name is blank")
        void shouldFailWhenHeaderNameIsBlank() {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Api-Key", "my-secret-key");

            AgentWebhookAuthService.AuthResult result = service.validateAuth(
                    headerConfig("   ", "my-secret-key"), headers);

            assertThat(result.valid()).isFalse();
            assertThat(result.message()).containsIgnoringCase("configuration");
        }

        @Test
        @DisplayName("should fail with server configuration error when expected header value is blank")
        void shouldFailWhenExpectedValueIsBlank() {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Api-Key", "some-value");

            AgentWebhookAuthService.AuthResult result = service.validateAuth(
                    headerConfig("X-Api-Key", "  "), headers);

            assertThat(result.valid()).isFalse();
            assertThat(result.message()).containsIgnoringCase("configuration");
        }
    }

    // ---------------------------------------------------------------------------
    // JWT Auth
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("validateAuth() - JWT auth")
    class JwtAuthTests {

        private static final String SECRET = "my-super-secret-jwt-key-for-testing";

        @Test
        @DisplayName("should pass with a valid HS256 JWT signed with the configured secret")
        void shouldPassWithValidJwt() {
            String token = buildJwt(SECRET);

            AgentWebhookAuthService.AuthResult result = service.validateAuth(
                    jwtConfig(SECRET, "HS256"),
                    headersWithBearer(token));

            assertThat(result.valid()).isTrue();
        }

        @Test
        @DisplayName("should pass when algorithm field is null (defaults to HS256)")
        void shouldPassWithNullAlgorithmDefaultingToHs256() {
            String token = buildJwt(SECRET);

            AgentWebhookAuthService.AuthResult result = service.validateAuth(
                    jwtConfig(SECRET, null),
                    headersWithBearer(token));

            assertThat(result.valid()).isTrue();
        }

        @Test
        @DisplayName("should fail with a JWT signed with a different secret")
        void shouldFailWithWrongSecret() {
            String token = buildJwt("wrong-secret");

            AgentWebhookAuthService.AuthResult result = service.validateAuth(
                    jwtConfig(SECRET, "HS256"),
                    headersWithBearer(token));

            assertThat(result.valid()).isFalse();
            assertThat(result.message()).containsIgnoringCase("invalid jwt");
        }

        @Test
        @DisplayName("should fail with a tampered / malformed JWT string")
        void shouldFailWithMalformedJwt() {
            AgentWebhookAuthService.AuthResult result = service.validateAuth(
                    jwtConfig(SECRET, "HS256"),
                    headersWithBearer("this.is.not.a.real.jwt"));

            assertThat(result.valid()).isFalse();
        }

        @Test
        @DisplayName("should fail when the Authorization header is missing")
        void shouldFailWhenBearerHeaderMissing() {
            AgentWebhookAuthService.AuthResult result = service.validateAuth(
                    jwtConfig(SECRET, "HS256"),
                    new HttpHeaders());

            assertThat(result.valid()).isFalse();
            assertThat(result.message()).containsIgnoringCase("missing");
        }

        @Test
        @DisplayName("should fail when the Authorization header uses Basic scheme instead of Bearer")
        void shouldFailWhenWrongScheme() {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.AUTHORIZATION, "Basic somebase64");

            AgentWebhookAuthService.AuthResult result = service.validateAuth(
                    jwtConfig(SECRET, "HS256"), headers);

            assertThat(result.valid()).isFalse();
        }

        @Test
        @DisplayName("should fail with server configuration error when secret key is null")
        void shouldFailWhenSecretKeyIsNull() {
            String token = buildJwt(SECRET);

            AgentWebhookAuthService.AuthResult result = service.validateAuth(
                    jwtConfig(null, "HS256"),
                    headersWithBearer(token));

            assertThat(result.valid()).isFalse();
            assertThat(result.message()).containsIgnoringCase("configuration");
        }

        @Test
        @DisplayName("should fail with server configuration error when secret key is blank")
        void shouldFailWhenSecretKeyIsBlank() {
            String token = buildJwt(SECRET);

            AgentWebhookAuthService.AuthResult result = service.validateAuth(
                    jwtConfig("   ", "HS256"),
                    headersWithBearer(token));

            assertThat(result.valid()).isFalse();
            assertThat(result.message()).containsIgnoringCase("configuration");
        }

        @Test
        @DisplayName("should pass with HS384 algorithm when token is signed with HS384")
        void shouldPassWithHs384Algorithm() {
            String token = JWT.create()
                    .withIssuer("test")
                    .sign(Algorithm.HMAC384(SECRET));

            AgentWebhookAuthService.AuthResult result = service.validateAuth(
                    jwtConfig(SECRET, "HS384"),
                    headersWithBearer(token));

            assertThat(result.valid()).isTrue();
        }

        @Test
        @DisplayName("should pass with HS512 algorithm when token is signed with HS512")
        void shouldPassWithHs512Algorithm() {
            String token = JWT.create()
                    .withIssuer("test")
                    .sign(Algorithm.HMAC512(SECRET));

            AgentWebhookAuthService.AuthResult result = service.validateAuth(
                    jwtConfig(SECRET, "HS512"),
                    headersWithBearer(token));

            assertThat(result.valid()).isTrue();
        }
    }
}
