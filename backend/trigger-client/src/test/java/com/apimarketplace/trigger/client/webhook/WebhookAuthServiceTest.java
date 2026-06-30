package com.apimarketplace.trigger.client.webhook;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link WebhookAuthService} in trigger-client.
 */
@DisplayName("WebhookAuthService")
class WebhookAuthServiceTest {

    private WebhookAuthService authService;

    @BeforeEach
    void setUp() {
        authService = new WebhookAuthService();
    }

    @Nested
    @DisplayName("WebhookAuthResult")
    class WebhookAuthResultTests {

        @Test
        @DisplayName("success should create valid result")
        void successShouldCreateValid() {
            WebhookAuthService.WebhookAuthResult result = WebhookAuthService.WebhookAuthResult.success();
            assertThat(result.valid()).isTrue();
            assertThat(result.message()).isNull();
        }

        @Test
        @DisplayName("failure should create invalid result with message")
        void failureShouldCreateInvalid() {
            WebhookAuthService.WebhookAuthResult result = WebhookAuthService.WebhookAuthResult.failure("Bad token");
            assertThat(result.valid()).isFalse();
            assertThat(result.message()).isEqualTo("Bad token");
        }
    }

    @Nested
    @DisplayName("validateAuth - general")
    class ValidateAuthGeneralTests {

        @Test
        @DisplayName("Should return success when config is null")
        void shouldSucceedWhenConfigNull() {
            var result = authService.validateAuth(null, new HttpHeaders());
            assertThat(result.valid()).isTrue();
        }

        @Test
        @DisplayName("Should return success when auth is not required")
        void shouldSucceedWhenNoAuthRequired() {
            WebhookConfig config = WebhookConfig.defaults();
            var result = authService.validateAuth(config, new HttpHeaders());
            assertThat(result.valid()).isTrue();
        }

        @Test
        @DisplayName("Should return success for unknown auth type")
        void shouldSucceedForUnknownAuthType() {
            WebhookConfig config = new WebhookConfig(null, "POST", "custom_type", null, null, null, null, null, null);
            var result = authService.validateAuth(config, new HttpHeaders());
            assertThat(result.valid()).isTrue();
        }
    }

    @Nested
    @DisplayName("validateAuth - basic")
    class BasicAuthTests {

        private WebhookConfig basicConfig(String username, String password) {
            return new WebhookConfig(null, "POST", "basic", username, password, null, null, null, null);
        }

        private HttpHeaders headersWithBasicAuth(String username, String password) {
            HttpHeaders headers = new HttpHeaders();
            String credentials = username + ":" + password;
            String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            headers.set(HttpHeaders.AUTHORIZATION, "Basic " + encoded);
            return headers;
        }

        @Test
        @DisplayName("Should succeed with correct credentials")
        void shouldSucceedWithCorrectCredentials() {
            var result = authService.validateAuth(basicConfig("admin", "pass"), headersWithBasicAuth("admin", "pass"));
            assertThat(result.valid()).isTrue();
        }

        @Test
        @DisplayName("Should fail when authorization header is missing")
        void shouldFailWhenHeaderMissing() {
            var result = authService.validateAuth(basicConfig("admin", "pass"), new HttpHeaders());
            assertThat(result.valid()).isFalse();
            assertThat(result.message()).contains("Missing Basic authentication");
        }

        @Test
        @DisplayName("Should fail with wrong credentials")
        void shouldFailWithWrongCredentials() {
            var result = authService.validateAuth(basicConfig("admin", "pass"), headersWithBasicAuth("admin", "wrong"));
            assertThat(result.valid()).isFalse();
            assertThat(result.message()).contains("Invalid credentials");
        }

        @Test
        @DisplayName("Should fail when expected credentials are null")
        void shouldFailWhenExpectedNull() {
            var result = authService.validateAuth(basicConfig(null, null), headersWithBasicAuth("admin", "pass"));
            assertThat(result.valid()).isFalse();
            assertThat(result.message()).contains("Server configuration error");
        }

        @Test
        @DisplayName("Should fail with invalid base64")
        void shouldFailWithInvalidBase64() {
            WebhookConfig config = basicConfig("admin", "pass");
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.AUTHORIZATION, "Basic %%%not-base64");

            var result = authService.validateAuth(config, headers);
            assertThat(result.valid()).isFalse();
            assertThat(result.message()).contains("Invalid Basic auth encoding");
        }

        @Test
        @DisplayName("Should handle password containing colons correctly")
        void shouldHandlePasswordWithColons() {
            var result = authService.validateAuth(
                    basicConfig("admin", "pass:with:colons"),
                    headersWithBasicAuth("admin", "pass:with:colons"));
            assertThat(result.valid()).isTrue();
        }
    }

    @Nested
    @DisplayName("validateAuth - header")
    class HeaderAuthTests {

        private WebhookConfig headerConfig(String headerName, String headerValue) {
            return new WebhookConfig(null, "POST", "header", null, null, headerName, headerValue, null, null);
        }

        @Test
        @DisplayName("Should succeed with matching header")
        void shouldSucceedWithMatchingHeader() {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-API-Key", "secret-key-123");

            var result = authService.validateAuth(headerConfig("X-API-Key", "secret-key-123"), headers);
            assertThat(result.valid()).isTrue();
        }

        @Test
        @DisplayName("Should fail when required header is missing")
        void shouldFailWhenHeaderMissing() {
            var result = authService.validateAuth(headerConfig("X-API-Key", "secret"), new HttpHeaders());
            assertThat(result.valid()).isFalse();
            assertThat(result.message()).contains("Missing header");
        }

        @Test
        @DisplayName("Should fail when header value does not match")
        void shouldFailWhenValueMismatch() {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-API-Key", "wrong-value");

            var result = authService.validateAuth(headerConfig("X-API-Key", "secret"), headers);
            assertThat(result.valid()).isFalse();
            assertThat(result.message()).contains("Invalid header value");
        }

        @Test
        @DisplayName("Should fail when header name is blank")
        void shouldFailWhenHeaderNameBlank() {
            var result = authService.validateAuth(headerConfig("", "value"), new HttpHeaders());
            assertThat(result.valid()).isFalse();
            assertThat(result.message()).contains("Server configuration error");
        }

        @Test
        @DisplayName("Should fail when expected value is null")
        void shouldFailWhenExpectedValueNull() {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-API-Key", "value");

            var result = authService.validateAuth(headerConfig("X-API-Key", null), headers);
            assertThat(result.valid()).isFalse();
            assertThat(result.message()).contains("Server configuration error");
        }
    }

    @Nested
    @DisplayName("validateAuth - jwt")
    class JwtAuthTests {

        private static final String SECRET = "my-super-secret-key-for-testing-256bit!!";

        private WebhookConfig jwtConfig(String secretKey, String algorithm) {
            return new WebhookConfig(null, "POST", "jwt", null, null, null, null, secretKey, algorithm);
        }

        @Test
        @DisplayName("Should succeed with valid HS256 JWT")
        void shouldSucceedWithValidHs256Jwt() {
            Algorithm algo = Algorithm.HMAC256(SECRET);
            String token = JWT.create()
                    .withIssuer("test")
                    .withExpiresAt(Instant.now().plusSeconds(3600))
                    .sign(algo);

            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);

            var result = authService.validateAuth(jwtConfig(SECRET, "HS256"), headers);
            assertThat(result.valid()).isTrue();
        }

        @Test
        @DisplayName("Should fail when bearer token is missing")
        void shouldFailWhenBearerMissing() {
            var result = authService.validateAuth(jwtConfig("secret", "HS256"), new HttpHeaders());
            assertThat(result.valid()).isFalse();
            assertThat(result.message()).contains("Missing Bearer token");
        }

        @Test
        @DisplayName("Should fail when secret key is null")
        void shouldFailWhenSecretKeyNull() {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer some-token");

            var result = authService.validateAuth(jwtConfig(null, "HS256"), headers);
            assertThat(result.valid()).isFalse();
            assertThat(result.message()).contains("Server configuration error");
        }

        @Test
        @DisplayName("Should fail with invalid JWT token")
        void shouldFailWithInvalidJwt() {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer not-a-valid-jwt");

            var result = authService.validateAuth(jwtConfig("secret-key", "HS256"), headers);
            assertThat(result.valid()).isFalse();
            assertThat(result.message()).contains("Invalid JWT");
        }

        @Test
        @DisplayName("Should fail when auth header uses wrong scheme")
        void shouldFailWithWrongScheme() {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.AUTHORIZATION, "Basic some-token");

            var result = authService.validateAuth(jwtConfig("secret", "HS256"), headers);
            assertThat(result.valid()).isFalse();
            assertThat(result.message()).contains("Missing Bearer token");
        }

        @Test
        @DisplayName("Should fail when JWT is expired")
        void shouldFailWithExpiredJwt() {
            Algorithm algo = Algorithm.HMAC256(SECRET);
            String token = JWT.create()
                    .withIssuer("test")
                    .withExpiresAt(Instant.now().minusSeconds(3600))
                    .sign(algo);

            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);

            var result = authService.validateAuth(jwtConfig(SECRET, "HS256"), headers);
            assertThat(result.valid()).isFalse();
            assertThat(result.message()).contains("Invalid JWT");
        }
    }
}
