package com.apimarketplace.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the common-lib GatewayAuthenticationFilter.
 * Tests HMAC verification, public path matching, and error responses.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GatewayAuthenticationFilter Tests")
class GatewayAuthenticationFilterTest {

    private GatewayAuthenticationFilter filter;
    private GatewayFilterProperties properties;

    @Mock
    private FilterChain filterChain;

    private static final String TEST_GATEWAY_SECRET_KEY = "test-gateway-hmac-key-for-unit-tests";
    private static final List<String> DEFAULT_PUBLIC_PATHS = List.of(
            "/health",
            "/actuator",
            "/api/internal/"
    );

    @BeforeEach
    void setUp() {
        properties = new GatewayFilterProperties();
        properties.setSecretKey(TEST_GATEWAY_SECRET_KEY);
        properties.setVerificationEnabled(true);
        properties.setPublicPaths(DEFAULT_PUBLIC_PATHS);
        filter = new GatewayAuthenticationFilter(properties);
    }

    // -----------------------------------------------------------------------
    // Public Endpoints
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Public Endpoints")
    class PublicEndpointTests {

        @ParameterizedTest
        @ValueSource(strings = {
                "/health",
                "/actuator/health",
                "/actuator/info",
                "/api/internal/webhook/abc123",
                "/api/internal/chat/session"
        })
        @DisplayName("should allow public endpoints without gateway headers")
        void shouldAllowPublicEndpoints(String path) throws IOException, ServletException {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            request.setRequestURI(path);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        }

        @Test
        @DisplayName("should block non-public endpoints without headers")
        void shouldBlockNonPublicEndpoints() throws IOException, ServletException {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/protected");
            request.setRequestURI("/api/protected");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
            verify(filterChain, never()).doFilter(any(), any());
        }

        @Test
        @DisplayName("should require HMAC for dangerous internal endpoints even when public prefix matches")
        void shouldRequireHmacForDangerousInternalEndpoints() throws IOException, ServletException {
            properties.setHmacRequiredPaths(List.of("/api/internal/credentials/access-token"));
            String path = "/api/internal/credentials/access-token/gmail";
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            request.setRequestURI(path);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
            verify(filterChain, never()).doFilter(any(), any());
        }

        @Test
        @DisplayName("should allow HMAC-signed dangerous internal endpoint")
        void shouldAllowSignedDangerousInternalEndpoint() throws IOException, ServletException {
            properties.setHmacRequiredPaths(List.of("/api/internal/credentials/access-token"));
            String timestamp = String.valueOf(System.currentTimeMillis());
            String providerId = "internal-credential-client";
            String validSecret = generateExpectedSecret(providerId, timestamp);
            String path = "/api/internal/credentials/access-token/gmail";
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            request.setRequestURI(path);
            request.addHeader("X-Gateway-Secret", validSecret);
            request.addHeader("X-Gateway-Timestamp", timestamp);
            request.addHeader("X-Provider-ID", providerId);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        }
    }

    // -----------------------------------------------------------------------
    // isPublicEndpoint (package-private)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("isPublicEndpoint()")
    class IsPublicEndpointTests {

        @Test
        @DisplayName("should match exact path")
        void shouldMatchExactPath() {
            assertThat(filter.isPublicEndpoint("/health")).isTrue();
        }

        @Test
        @DisplayName("should match path prefix")
        void shouldMatchPrefix() {
            assertThat(filter.isPublicEndpoint("/actuator/prometheus")).isTrue();
        }

        @Test
        @DisplayName("should not match unrelated path")
        void shouldNotMatchUnrelatedPath() {
            assertThat(filter.isPublicEndpoint("/api/workflows")).isFalse();
        }

        @Test
        @DisplayName("should return false when public paths are empty")
        void shouldReturnFalseWhenEmpty() {
            properties.setPublicPaths(List.of());
            assertThat(filter.isPublicEndpoint("/health")).isFalse();
        }

        @Test
        @DisplayName("should return false when public paths are null")
        void shouldReturnFalseWhenNull() {
            properties.setPublicPaths(null);
            assertThat(filter.isPublicEndpoint("/health")).isFalse();
        }
    }

    @Nested
    @DisplayName("isHmacRequiredEndpoint()")
    class IsHmacRequiredEndpointTests {

        @Test
        @DisplayName("should match configured HMAC-required prefix")
        void shouldMatchHmacRequiredPrefix() {
            properties.setHmacRequiredPaths(List.of("/api/internal/credentials/access-token"));

            assertThat(filter.isHmacRequiredEndpoint("/api/internal/credentials/access-token/slack")).isTrue();
            assertThat(filter.isHmacRequiredEndpoint("/api/internal/credentials/default")).isFalse();
        }

        @Test
        @DisplayName("should return false when HMAC-required paths are null")
        void shouldReturnFalseWhenNull() {
            properties.setHmacRequiredPaths(null);

            assertThat(filter.isHmacRequiredEndpoint("/api/internal/credentials/access-token/slack")).isFalse();
        }

    }

    @Nested
    @DisplayName("Secret configuration")
    class SecretConfigurationTests {

        @Test
        @DisplayName("should fail fast when verification is enabled without a secret")
        void shouldFailFastWhenVerificationEnabledWithoutSecret() {
            GatewayFilterProperties unsafe = new GatewayFilterProperties();
            unsafe.setVerificationEnabled(true);
            unsafe.setSecretKey("");

            assertThatThrownBy(() -> new GatewayAuthenticationFilter(unsafe))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("gateway.filter.secret-key");
        }
    }

    // -----------------------------------------------------------------------
    // Verification Disabled
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Verification Disabled")
    class VerificationDisabledTests {

        @Test
        @DisplayName("should allow all requests when verification is disabled")
        void shouldAllowAllRequestsWhenDisabled() throws Exception {
            properties.setVerificationEnabled(false);

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/protected");
            request.setRequestURI("/api/protected");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        }

        @Test
        @DisplayName("should allow requests without any headers when disabled")
        void shouldAllowWithoutHeadersWhenDisabled() throws Exception {
            properties.setVerificationEnabled(false);

            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/workflows");
            request.setRequestURI("/api/workflows");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
        }
    }

    // -----------------------------------------------------------------------
    // Missing Headers
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Missing Headers")
    class MissingHeadersTests {

        @Test
        @DisplayName("should return UNAUTHORIZED when X-Gateway-Secret is missing")
        void shouldRejectWhenSecretMissing() throws IOException, ServletException {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/protected");
            request.setRequestURI("/api/protected");
            request.addHeader("X-Gateway-Timestamp", String.valueOf(System.currentTimeMillis()));
            request.addHeader("X-Provider-ID", "provider-123");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
            verify(filterChain, never()).doFilter(any(), any());
        }

        @Test
        @DisplayName("should return UNAUTHORIZED when X-Gateway-Timestamp is missing")
        void shouldRejectWhenTimestampMissing() throws IOException, ServletException {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/protected");
            request.setRequestURI("/api/protected");
            request.addHeader("X-Gateway-Secret", "gw_some_secret");
            request.addHeader("X-Provider-ID", "provider-123");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
            verify(filterChain, never()).doFilter(any(), any());
        }

        @Test
        @DisplayName("should return UNAUTHORIZED when providerId is missing from both params and headers")
        void shouldRejectWhenProviderIdMissing() throws IOException, ServletException {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/protected");
            request.setRequestURI("/api/protected");
            request.addHeader("X-Gateway-Secret", "gw_some_secret");
            request.addHeader("X-Gateway-Timestamp", String.valueOf(System.currentTimeMillis()));
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
            verify(filterChain, never()).doFilter(any(), any());
        }

        @Test
        @DisplayName("should return UNAUTHORIZED when all gateway headers are missing")
        void shouldRejectWhenAllHeadersMissing() throws IOException, ServletException {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/protected");
            request.setRequestURI("/api/protected");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
            verify(filterChain, never()).doFilter(any(), any());
        }
    }

    // -----------------------------------------------------------------------
    // Provider ID Resolution
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Provider ID Resolution")
    class ProviderIdResolutionTests {

        @Test
        @DisplayName("should use X-Provider-ID header when providerId query param is missing")
        void shouldUseProviderIdHeader() throws IOException, ServletException {
            String timestamp = String.valueOf(System.currentTimeMillis());
            String providerId = "provider-123";
            String expectedSecret = generateExpectedSecret(providerId, timestamp);

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/protected");
            request.setRequestURI("/api/protected");
            request.addHeader("X-Gateway-Secret", expectedSecret);
            request.addHeader("X-Gateway-Timestamp", timestamp);
            request.addHeader("X-Provider-ID", providerId);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
        }

        @Test
        @DisplayName("should prefer providerId query parameter over header")
        void shouldPreferQueryParamOverHeader() throws IOException, ServletException {
            String timestamp = String.valueOf(System.currentTimeMillis());
            String queryProviderId = "provider-from-query";
            String expectedSecret = generateExpectedSecret(queryProviderId, timestamp);

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/protected");
            request.setRequestURI("/api/protected");
            request.addHeader("X-Gateway-Secret", expectedSecret);
            request.addHeader("X-Gateway-Timestamp", timestamp);
            request.addHeader("X-Provider-ID", "provider-from-header");
            request.setParameter("providerId", queryProviderId);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
        }
    }

    // -----------------------------------------------------------------------
    // Secret Validation
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Secret Validation")
    class SecretValidationTests {

        @Test
        @DisplayName("should allow request with valid gateway secret")
        void shouldAllowValidSecret() throws IOException, ServletException {
            String timestamp = String.valueOf(System.currentTimeMillis());
            String providerId = "provider-123";
            String validSecret = generateExpectedSecret(providerId, timestamp);

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/protected");
            request.setRequestURI("/api/protected");
            request.addHeader("X-Gateway-Secret", validSecret);
            request.addHeader("X-Gateway-Timestamp", timestamp);
            request.setParameter("providerId", providerId);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
        }

        @Test
        @DisplayName("should reject request with invalid gateway secret")
        void shouldRejectInvalidSecret() throws IOException, ServletException {
            String timestamp = String.valueOf(System.currentTimeMillis());

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/protected");
            request.setRequestURI("/api/protected");
            request.addHeader("X-Gateway-Secret", "gw_invalid_secret_abc");
            request.addHeader("X-Gateway-Timestamp", timestamp);
            request.setParameter("providerId", "provider-123");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
            verify(filterChain, never()).doFilter(any(), any());
        }

        @Test
        @DisplayName("should reject secret without 'gw_' prefix")
        void shouldRejectSecretWithoutPrefix() throws IOException, ServletException {
            String timestamp = String.valueOf(System.currentTimeMillis());

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/protected");
            request.setRequestURI("/api/protected");
            request.addHeader("X-Gateway-Secret", "invalid_without_gw_prefix");
            request.addHeader("X-Gateway-Timestamp", timestamp);
            request.setParameter("providerId", "provider-123");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
            verify(filterChain, never()).doFilter(any(), any());
        }

        @Test
        @DisplayName("should reject request with expired timestamp (older than 5 minutes)")
        void shouldRejectExpiredTimestamp() throws IOException, ServletException {
            String expiredTimestamp = String.valueOf(System.currentTimeMillis() - 360_000);
            String providerId = "provider-123";
            String secret = generateExpectedSecret(providerId, expiredTimestamp);

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/protected");
            request.setRequestURI("/api/protected");
            request.addHeader("X-Gateway-Secret", secret);
            request.addHeader("X-Gateway-Timestamp", expiredTimestamp);
            request.setParameter("providerId", providerId);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
            verify(filterChain, never()).doFilter(any(), any());
        }

        @Test
        @DisplayName("should reject request with non-numeric timestamp")
        void shouldRejectNonNumericTimestamp() throws IOException, ServletException {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/protected");
            request.setRequestURI("/api/protected");
            request.addHeader("X-Gateway-Secret", "gw_abc_123456");
            request.addHeader("X-Gateway-Timestamp", "not-a-number");
            request.setParameter("providerId", "provider-123");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
            verify(filterChain, never()).doFilter(any(), any());
        }

        @Test
        @DisplayName("should reject when X-User-ID is tampered after signing")
        void shouldRejectTamperedUserIdHeader() throws IOException, ServletException {
            // The signature binds to (providerId|userId|orgId|timestamp). Sign for u1/o1...
            String timestamp = String.valueOf(System.currentTimeMillis());
            String providerId = "provider-123";
            String validSecretForU1 = generateExpectedSecret(providerId, timestamp, "u1", "o1");

            // ...but send a DIFFERENT identity header (X-User-ID=u2). The signature
            // recomputed by the filter over u2 will not match -> 401, chain not invoked.
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/protected");
            request.setRequestURI("/api/protected");
            request.addHeader("X-Gateway-Secret", validSecretForU1);
            request.addHeader("X-Gateway-Timestamp", timestamp);
            request.addHeader("X-Provider-ID", providerId);
            request.addHeader("X-User-ID", "u2");
            request.addHeader("X-Organization-ID", "o1");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
            verify(filterChain, never()).doFilter(any(), any());
        }

        @Test
        @DisplayName("should allow when X-User-ID matches the signed identity")
        void shouldAllowUntamperedUserIdHeader() throws IOException, ServletException {
            // Positive mirror: same secret with the X-User-ID it was signed for (u1) -> chain invoked.
            String timestamp = String.valueOf(System.currentTimeMillis());
            String providerId = "provider-123";
            String validSecretForU1 = generateExpectedSecret(providerId, timestamp, "u1", "o1");

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/protected");
            request.setRequestURI("/api/protected");
            request.addHeader("X-Gateway-Secret", validSecretForU1);
            request.addHeader("X-Gateway-Timestamp", timestamp);
            request.addHeader("X-Provider-ID", providerId);
            request.addHeader("X-User-ID", "u1");
            request.addHeader("X-Organization-ID", "o1");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        }

        @Test
        @DisplayName("should reject secret with correct prefix but wrong hash")
        void shouldRejectWrongHash() throws IOException, ServletException {
            String timestamp = String.valueOf(System.currentTimeMillis());

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/protected");
            request.setRequestURI("/api/protected");
            request.addHeader("X-Gateway-Secret",
                    "gw_AAAAAAAAAAAAAAAAAAAAAAAAAAAAaaaa_" + timestamp.substring(timestamp.length() - 6));
            request.addHeader("X-Gateway-Timestamp", timestamp);
            request.setParameter("providerId", "provider-123");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
            verify(filterChain, never()).doFilter(any(), any());
        }
    }

    // -----------------------------------------------------------------------
    // generateExpectedSecret (package-private)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("generateExpectedSecret()")
    class GenerateExpectedSecretTests {

        @Test
        @DisplayName("should produce consistent results for same input")
        void shouldBeConsistent() {
            String s1 = filter.generateExpectedSecret("provider-1", "1700000000000", null, null);
            String s2 = filter.generateExpectedSecret("provider-1", "1700000000000", null, null);

            assertThat(s1).isEqualTo(s2);
        }

        @Test
        @DisplayName("should start with 'gw_' prefix")
        void shouldHaveGwPrefix() {
            String secret = filter.generateExpectedSecret("provider-1", "1700000000000", null, null);

            assertThat(secret).startsWith("gw_");
        }

        @Test
        @DisplayName("should bind to user + org when scoped")
        void shouldDifferForDifferentUserOrg() {
            String s1 = filter.generateExpectedSecret("provider-1", "1700000000000", "u1", "o1");
            String s2 = filter.generateExpectedSecret("provider-1", "1700000000000", "u2", "o1");
            String s3 = filter.generateExpectedSecret("provider-1", "1700000000000", "u1", "o2");

            assertThat(s1).isNotEqualTo(s2);
            assertThat(s1).isNotEqualTo(s3);
        }

        @Test
        @DisplayName("should differ for different provider IDs")
        void shouldDifferForDifferentProviders() {
            String s1 = filter.generateExpectedSecret("provider-1", "1700000000000", null, null);
            String s2 = filter.generateExpectedSecret("provider-2", "1700000000000", null, null);

            assertThat(s1).isNotEqualTo(s2);
        }

        @Test
        @DisplayName("should differ for different timestamps")
        void shouldDifferForDifferentTimestamps() {
            String s1 = filter.generateExpectedSecret("provider-1", "1700000000000", null, null);
            String s2 = filter.generateExpectedSecret("provider-1", "1700000000001", null, null);

            assertThat(s1).isNotEqualTo(s2);
        }
    }

    // -----------------------------------------------------------------------
    // Response Content
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Response Content")
    class ResponseContentTests {

        @Test
        @DisplayName("should return JSON error body for missing headers")
        void shouldReturnJsonErrorForMissingHeaders() throws IOException, ServletException {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/protected");
            request.setRequestURI("/api/protected");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            assertThat(response.getContentType()).isEqualTo("application/json");
            assertThat(response.getContentAsString()).contains("Unauthorized");
            assertThat(response.getContentAsString()).contains("Missing gateway authentication headers");
        }

        @Test
        @DisplayName("should return JSON error body for invalid secret")
        void shouldReturnJsonErrorForInvalidSecret() throws IOException, ServletException {
            String timestamp = String.valueOf(System.currentTimeMillis());

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/protected");
            request.setRequestURI("/api/protected");
            request.addHeader("X-Gateway-Secret", "gw_bad_secret");
            request.addHeader("X-Gateway-Timestamp", timestamp);
            request.setParameter("providerId", "provider-123");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            assertThat(response.getContentType()).isEqualTo("application/json");
            assertThat(response.getContentAsString()).contains("Unauthorized");
            assertThat(response.getContentAsString()).contains("Invalid gateway secret");
        }
    }

    // -----------------------------------------------------------------------
    // Custom Public Paths
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Custom Public Paths")
    class CustomPublicPathsTests {

        @Test
        @DisplayName("should respect custom public paths from properties")
        void shouldRespectCustomPaths() throws IOException, ServletException {
            properties.setPublicPaths(List.of("/custom/public", "/other/"));

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/custom/public");
            request.setRequestURI("/custom/public");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
        }

        @Test
        @DisplayName("should block previously public path after changing config")
        void shouldBlockAfterConfigChange() throws IOException, ServletException {
            properties.setPublicPaths(List.of("/custom/only"));

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/health");
            request.setRequestURI("/health");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
            verify(filterChain, never()).doFilter(any(), any());
        }
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    private String generateExpectedSecret(String providerId, String timestamp) {
        return generateExpectedSecret(providerId, timestamp, null, null);
    }

    private String generateExpectedSecret(String providerId, String timestamp,
                                           String userId, String organizationId) {
        try {
            String safeUser = userId != null ? userId : "";
            String safeOrg = organizationId != null ? organizationId : "";
            String data = providerId + "|" + safeUser + "|" + safeOrg + "|" + timestamp;
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    TEST_GATEWAY_SECRET_KEY.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    "HmacSHA256"));
            byte[] hash = mac.doFinal(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "gw_" + Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
