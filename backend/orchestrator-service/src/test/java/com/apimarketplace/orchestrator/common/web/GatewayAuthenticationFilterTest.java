package com.apimarketplace.orchestrator.common.web;

import com.apimarketplace.common.web.GatewayAuthenticationFilter;
import com.apimarketplace.common.web.GatewayFilterProperties;
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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Tests the common-lib GatewayAuthenticationFilter with orchestrator-specific public paths.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GatewayAuthenticationFilter Tests (orchestrator paths)")
class GatewayAuthenticationFilterTest {

    private GatewayAuthenticationFilter filter;
    private GatewayFilterProperties properties;

    @Mock
    private FilterChain filterChain;

    private static final String TEST_GATEWAY_SECRET_KEY = "test-gateway-hmac-key-for-orchestrator-tests";

    /** Orchestrator's full public paths list (mirrors application.yml). */
    private static final List<String> ORCHESTRATOR_PUBLIC_PATHS = List.of(
            "/health",
            "/actuator",
            "/api/v2/monitoring/health",
            "/api/internal/webhook",
            "/api/internal/chat",
            "/api/internal/form",
            "/api/internal/app/public",
            "/api/internal/websearch",
            "/api/websearch/screenshots",
            "/api/internal/widget",
            "/api/credentials/oauth2/callback",
            "/api/agent-tools",
            "/api/agent-prompts",
            "/api/workflows/",
            "/api/workflow-builder/sessions/",
            "/api/credentials/by-integration/",
            "/api/internal/agent-observability",
            "/api/internal/publication-support"
    );

    @BeforeEach
    void setUp() {
        properties = new GatewayFilterProperties();
        properties.setSecretKey(TEST_GATEWAY_SECRET_KEY);
        properties.setVerificationEnabled(true);
        properties.setPublicPaths(ORCHESTRATOR_PUBLIC_PATHS);
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
                "/api/v2/monitoring/health",
                "/api/internal/webhook/abc123",
                "/api/internal/webhook/agent/xyz",
                "/api/internal/widget/session",
                "/api/credentials/oauth2/callback",
                "/api/agent-tools",
                "/api/agent-tools/categories",
                "/api/agent-tools/search",
                "/api/agent-prompts",
                "/api/agent-prompts/blocks"
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
        @DisplayName("should reject secret with correct prefix but wrong hash")
        void shouldRejectWrongHash() throws IOException, ServletException {
            String timestamp = String.valueOf(System.currentTimeMillis());

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/protected");
            request.setRequestURI("/api/protected");
            request.addHeader("X-Gateway-Secret", "gw_AAAAAAAAAAAAAAAAAAAAAAAAAAAAaaaa_" + timestamp.substring(timestamp.length() - 6));
            request.addHeader("X-Gateway-Timestamp", timestamp);
            request.setParameter("providerId", "provider-123");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
            verify(filterChain, never()).doFilter(any(), any());
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
    // Helper
    // -----------------------------------------------------------------------

    private String generateExpectedSecret(String providerId, String timestamp) {
        try {
            String data = providerId + "|||" + timestamp;
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    TEST_GATEWAY_SECRET_KEY.getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return "gw_" + Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
