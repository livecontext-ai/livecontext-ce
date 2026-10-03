package com.apimarketplace.auth.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LC-033 (auth-service half). {@code corsConfigurationSource()} registered a wildcard origin
 * pattern together with {@code allowCredentials(true)}, which reflects ANY requesting origin back
 * with credentials allowed. Asserted on the real bean output, not on the source text.
 */
@DisplayName("auth-service SecurityConfig CORS (LC-033)")
class SecurityConfigCorsTest {

    private static CorsConfiguration corsFor(String origin) {
        CorsConfigurationSource source = new SecurityConfig().corsConfigurationSource();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/me");
        request.addHeader("Origin", origin);
        return source.getCorsConfiguration(request);
    }

    @Test
    @DisplayName("never allows credentials on a cross-origin request")
    void credentialsAreNeverAllowed() {
        CorsConfiguration cors = corsFor("https://evil.example");

        assertThat(cors).isNotNull();
        assertThat(cors.getAllowCredentials()).isNotEqualTo(Boolean.TRUE);
    }

    @Test
    @DisplayName("a wildcard origin is never paired with credentials, whatever origin asks")
    void wildcardNeverPairedWithCredentials() {
        CorsConfiguration cors = corsFor("null");

        boolean wildcard = cors.getAllowedOriginPatterns() != null
                && cors.getAllowedOriginPatterns().contains("*");
        assertThat(wildcard && Boolean.TRUE.equals(cors.getAllowCredentials())).isFalse();
    }
}
