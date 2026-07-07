package com.apimarketplace.common.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ServicePrefixRewriteFilter")
class ServicePrefixRewriteFilterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("rewrites gateway-style auth-service API paths to monolith controller paths")
    void rewritesAuthServiceApiPrefixToControllerPath() throws Exception {
        ServicePrefixRewriteFilter filter = new ServicePrefixRewriteFilter();
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/api/auth-service/api/onboarding/status"
        );
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getForwardedUrl()).isNull();
        assertThat(chain.getRequest()).isNotNull();
        var rewritten = (jakarta.servlet.http.HttpServletRequest) chain.getRequest();
        assertThat(rewritten.getRequestURI()).isEqualTo("/api/onboarding/status");
        assertThat(rewritten.getServletPath()).isEqualTo("/api/onboarding/status");
    }

    @Test
    @DisplayName("leaves unknown service prefixes on the normal filter chain")
    void unknownServicePrefixIsNotRewritten() throws Exception {
        ServicePrefixRewriteFilter filter = new ServicePrefixRewriteFilter();
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/api/unknown-service/api/onboarding/status"
        );
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getForwardedUrl()).isNull();
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @ParameterizedTest
    @CsvSource({
            "/widget.js,/api/internal/widget/loader.js",
            "/widget/wid_abc/config,/api/internal/widget/wid_abc/config",
            "/share/sl_0123456789abcdef0123456789abcdef,/api/public/share/sl_0123456789abcdef0123456789abcdef",
            "/c/conversation-token,/api/shared/c/conversation-token",
            "/webhook/wh_abc,/api/internal/webhook/wh_abc",
            "/approval-callback/telegram,/api/internal/approval-callback/telegram",
            "/chat/chat-token,/api/internal/chat/chat-token",
            "/form/form-token,/api/internal/form/form-token",
            "/app/public/app-token,/api/internal/app/public/app-token"
    })
    @DisplayName("rewrites gateway public routes to monolith controller paths")
    void rewritesGatewayPublicRoutesToControllerPaths(String publicPath, String expectedPath) throws Exception {
        ServicePrefixRewriteFilter filter = new ServicePrefixRewriteFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", publicPath);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getForwardedUrl()).isNull();
        assertThat(chain.getRequest()).isNotNull();
        var rewritten = (jakarta.servlet.http.HttpServletRequest) chain.getRequest();
        assertThat(rewritten.getRequestURI()).isEqualTo(expectedPath);
        assertThat(rewritten.getServletPath()).isEqualTo(expectedPath);
    }

    @Test
    @DisplayName("keeps monolith JWT header injection after rewriting service prefixes")
    void rewrittenRequestStillPassesThroughMonolithSecurityFilter() throws Exception {
        KeyPair keyPair = generateRsaKeyPair();
        ServicePrefixRewriteFilter rewriteFilter = new ServicePrefixRewriteFilter();
        MonolithSecurityFilter securityFilter = new MonolithSecurityFilter(() -> keyPair.getPublic(), List.of());
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/api/auth-service/api/onboarding/status"
        );
        request.setRemoteAddr("203.0.113.10");
        request.addHeader("Authorization", "Bearer " + signedJwt(keyPair, Map.of(
                "sub", "71",
                "userId", 71,
                "email", "ce-route@example.test",
                "provider", "local",
                "roles", List.of("USER"),
                "token_type", "access",
                "exp", Instant.now().plusSeconds(300).getEpochSecond()
        )));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        rewriteFilter.doFilter(
                request,
                response,
                (rewrittenRequest, rewrittenResponse) ->
                        securityFilter.doFilter(rewrittenRequest, rewrittenResponse, (securedRequest, securedResponse) ->
                                captured.set(securedRequest)
                        )
        );

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(captured.get()).isNotNull();
        var secured = (jakarta.servlet.http.HttpServletRequest) captured.get();
        assertThat(secured.getRequestURI()).isEqualTo("/api/onboarding/status");
        assertThat(secured.getHeader("X-User-ID")).isEqualTo("71");
        assertThat(secured.getHeader("X-Provider-ID")).isEqualTo("local:ce-route@example.test");
    }

    private static KeyPair generateRsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static String signedJwt(KeyPair keyPair, Map<String, Object> claims) throws Exception {
        String header = base64Url(MAPPER.writeValueAsBytes(Map.of("alg", "RS256", "typ", "JWT")));
        String payload = base64Url(MAPPER.writeValueAsBytes(claims));
        String signingInput = header + "." + payload;
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(keyPair.getPrivate());
        signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + base64Url(signature.sign());
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
