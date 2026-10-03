package com.apimarketplace.catalog.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CASA LC-032: CE combined a wildcard "cors.allowed-origins: *" with loopback X-User-ID trust.
 * The loopback half is fixed by the in-process secret (MonolithSecurityFilter); this pins the
 * OTHER half - the default mapping must be scoped to {@code cors.allowed-origins} (never "*" by
 * construction here, regardless of what CE's own yml sets it to) while the public widget embed
 * stays wildcard-but-credential-less, since it is the one endpoint genuinely meant to be called
 * from an origin this install does not control.
 */
@DisplayName("WebConfig CORS scope (LC-032)")
class WebConfigCorsScopeTest {

    private static Map<String, CorsConfiguration> configurationsFor(WebConfig webConfig) {
        CorsRegistry registry = new CorsRegistry();
        webConfig.addCorsMappings(registry);
        return ReflectionTestUtils.invokeMethod(registry, "getCorsConfigurations");
    }

    private static WebConfig webConfig(String[] allowedOrigins, String[] widgetAllowedOrigins,
                                       boolean allowCredentials) {
        WebConfig webConfig = new WebConfig();
        ReflectionTestUtils.setField(webConfig, "allowedOrigins", allowedOrigins);
        ReflectionTestUtils.setField(webConfig, "allowedMethods",
                new String[] {"GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"});
        ReflectionTestUtils.setField(webConfig, "allowedHeaders", new String[] {"*"});
        ReflectionTestUtils.setField(webConfig, "allowCredentials", allowCredentials);
        ReflectionTestUtils.setField(webConfig, "maxAge", 3600L);
        ReflectionTestUtils.setField(webConfig, "widgetAllowedOrigins", widgetAllowedOrigins);
        return webConfig;
    }

    @Test
    @DisplayName("the default mapping carries exactly the configured origins, not the widget's")
    void defaultMappingUsesConfiguredOrigins() {
        Map<String, CorsConfiguration> configs = configurationsFor(
                webConfig(new String[] {"https://app.example.com"}, new String[] {"*"}, false));

        CorsConfiguration defaultConfig = configs.get("/**");
        assertThat(defaultConfig).isNotNull();
        assertThat(defaultConfig.getAllowedOriginPatterns()).containsExactly("https://app.example.com");
    }

    @Test
    @DisplayName("a wildcard configured for cors.allowed-origins is confined to /**, never silently widened")
    void defaultMappingCanStillBeWildcardIfExplicitlyConfigured() {
        // Regression guard the other way: this test does not assert CE's yml value (that lives in
        // application-ce.yml and is an ops decision), only that the CODE does not hard-code "*"
        // for "/**" - whatever cors.allowed-origins resolves to is what "/**" gets, nothing more.
        Map<String, CorsConfiguration> configs = configurationsFor(
                webConfig(new String[] {"*"}, new String[] {"*"}, false));
        assertThat(configs.get("/**").getAllowedOriginPatterns()).containsExactly("*");
    }

    @Test
    @DisplayName("the public widget mapping is always wildcard-callable, independent of the default origins")
    void widgetMappingIsIndependentOfDefaultOrigins() {
        Map<String, CorsConfiguration> configs = configurationsFor(
                webConfig(new String[] {"https://app.example.com"}, new String[] {"*"}, false));

        CorsConfiguration widgetConfig = configs.get("/api/internal/widget/**");
        assertThat(widgetConfig).isNotNull();
        assertThat(widgetConfig.getAllowedOriginPatterns()).containsExactly("*");
    }

    @Test
    @DisplayName("the public widget mapping never allows credentials, even if the global setting does")
    void widgetMappingNeverAllowsCredentials() {
        Map<String, CorsConfiguration> configs = configurationsFor(
                webConfig(new String[] {"https://app.example.com"}, new String[] {"*"}, true));

        assertThat(configs.get("/**").getAllowCredentials()).isTrue();
        assertThat(configs.get("/api/internal/widget/**").getAllowCredentials()).isFalse();
    }
}
