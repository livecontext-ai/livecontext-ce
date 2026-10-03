package com.apimarketplace.catalog.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LC-033 (catalog half). The CORS defaults allowed {@code http://localhost:3000/8080} WITH
 * credentials, although catalog-service has no browser ingress in cloud. Defaults are now "no
 * CORS grant, no credentials", and a wildcard origin forces credentials off whatever the property
 * says (Spring does not refuse that pair for allowedOriginPatterns).
 */
@DisplayName("catalog WebConfig CORS defaults (LC-033)")
class WebConfigCorsTest {

    /** Exposes the protected registrations. */
    private static final class InspectableRegistry extends CorsRegistry {
        Map<String, CorsConfiguration> configs() {
            return getCorsConfigurations();
        }
    }

    private static Map<String, CorsConfiguration> register(String[] origins, boolean credentials) {
        WebConfig config = new WebConfig();
        ReflectionTestUtils.setField(config, "allowedOrigins", origins);
        ReflectionTestUtils.setField(config, "allowedMethods", new String[] {"GET"});
        ReflectionTestUtils.setField(config, "allowedHeaders", new String[] {"*"});
        ReflectionTestUtils.setField(config, "allowCredentials", credentials);
        ReflectionTestUtils.setField(config, "maxAge", 3600L);
        // The widget mapping (LC-032) reads its own origin list; this is its compiled default.
        ReflectionTestUtils.setField(config, "widgetAllowedOrigins", new String[] {"*"});
        InspectableRegistry registry = new InspectableRegistry();
        config.addCorsMappings(registry);
        return registry.configs();
    }

    @Test
    @DisplayName("the compiled-in defaults grant no origin and no credentials")
    void compiledDefaultsAreClosed() throws Exception {
        String origins = WebConfig.class.getDeclaredField("allowedOrigins")
                .getAnnotation(org.springframework.beans.factory.annotation.Value.class).value();
        String credentials = WebConfig.class.getDeclaredField("allowCredentials")
                .getAnnotation(org.springframework.beans.factory.annotation.Value.class).value();

        assertThat(origins).isEqualTo("${cors.allowed-origins:}");
        assertThat(credentials).isEqualTo("${cors.allow-credentials:false}");
    }

    @Test
    @DisplayName("an empty origin list registers no CORS mapping at all")
    void emptyOriginsRegisterNothing() {
        // Spring binds an empty property to an empty array or to [""]; both mean "none".
        assertThat(register(new String[0], false)).isEmpty();
        assertThat(register(new String[] {""}, false)).isEmpty();
    }

    @Test
    @DisplayName("a wildcard origin forces credentials off even when the property asks for them")
    void wildcardForcesCredentialsOff() {
        CorsConfiguration cors = register(new String[] {"*"}, true).get("/**");

        assertThat(cors.getAllowCredentials()).isFalse();
    }

    @Test
    @DisplayName("the public widget mapping never allows credentials, even when the global setting does")
    void widgetMappingIsNeverCredentialed() {
        CorsConfiguration widget = register(new String[] {"https://app.example"}, true).get("/api/internal/widget/**");

        assertThat(widget.getAllowCredentials()).isFalse();
        assertThat(widget.getAllowedOriginPatterns()).containsExactly("*");
    }

    @Test
    @DisplayName("an explicit origin keeps the configured credentials setting")
    void explicitOriginKeepsCredentials() {
        CorsConfiguration cors = register(new String[] {"https://app.example"}, true).get("/**");

        assertThat(cors.getAllowCredentials()).isTrue();
        assertThat(cors.getAllowedOriginPatterns()).containsExactly("https://app.example");
    }
}
