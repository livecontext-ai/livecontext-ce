package com.apimarketplace.catalog.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.util.StdDateFormat;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;
import java.util.TimeZone;

/**
 * Web configuration including CORS settings and JSON serialization.
 *
 * CORS is configured globally here instead of per-controller @CrossOrigin annotations.
 * Allowed origins are configurable via properties for security.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    // Empty by default (LC-033): catalog-service has no browser ingress in cloud (the gateway owns
    // CORS there). The CE monolith, where this is the only CORS config, sets it explicitly.
    @Value("${cors.allowed-origins:}")
    private String[] allowedOrigins;

    @Value("${cors.allowed-methods:GET,POST,PUT,DELETE,OPTIONS,PATCH}")
    private String[] allowedMethods;

    @Value("${cors.allowed-headers:*}")
    private String[] allowedHeaders;

    // Auth is a Bearer header, never a cookie, so credentialed CORS is never needed by default.
    @Value("${cors.allow-credentials:false}")
    private boolean allowCredentials;

    @Value("${cors.max-age:3600}")
    private long maxAge;

    /**
     * CASA LC-032: the ONLY browser-facing endpoint deliberately called from an origin this
     * install does not control is the public chat widget - a script (`/widget.js`) a customer
     * embeds on their own third-party site, whose embed page then calls back to
     * {@code /api/internal/widget/{token}/*} (config, session, chat, history). Everything else
     * behind {@code /**} is scoped to {@link #allowedOrigins}: the frontend never calls this
     * backend directly from browser JS (it proxies server-side, see AGENTS.md
     * "Never call backend directly"), so a wildcard there is not a feature, it is exposure.
     * Origins here are never sent credentials (matches {@link WidgetSessionService}'s own
     * anonymous, token-scoped session model - see also each widget handler's own
     * {@code AgentWidgetConfigService.validateOrigin} allow-list, checked independently of CORS).
     */
    @Value("${cors.widget-allowed-origins:*}")
    private String[] widgetAllowedOrigins;

    /**
     * Configure CORS globally for all endpoints.
     * This replaces the need for @CrossOrigin(origins = "*") on each controller.
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = explicitOrigins(allowedOrigins);
        if (origins.length == 0) {
            return; // no CORS grant at all
        }
        registry.addMapping("/**")
            .allowedOriginPatterns(origins)
            .allowedMethods(allowedMethods)
            .allowedHeaders(allowedHeaders)
            // A wildcard origin FORCES credentials off whatever the property says: the pair means
            // "any site may read authenticated responses", and Spring does not refuse it for
            // allowedOriginPatterns.
            .allowCredentials(allowCredentials && !hasWildcard(origins))
            .maxAge(maxAge);

        // CASA LC-032: the public widget stays reachable from any origin (that is the point of an
        // embeddable widget) but NEVER with credentials, regardless of the global allow-credentials
        // setting above - a widget visitor is always anonymous.
        registry.addMapping("/api/internal/widget/**")
            .allowedOriginPatterns(widgetAllowedOrigins)
            .allowedMethods(allowedMethods)
            .allowedHeaders(allowedHeaders)
            .allowCredentials(false)
            .maxAge(maxAge);
    }

    static String[] explicitOrigins(String[] configured) {
        if (configured == null) {
            return new String[0];
        }
        return java.util.Arrays.stream(configured)
            .map(String::trim)
            .filter(o -> !o.isEmpty())
            .toArray(String[]::new);
    }

    static boolean hasWildcard(String[] origins) {
        return java.util.Arrays.stream(origins).anyMatch(o -> o.contains("*"));
    }

    @Override
    public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.add(new MappingJackson2HttpMessageConverter(objectMapper()));
    }

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        
        // Module configuration
        mapper.registerModule(new JavaTimeModule());
        
        // Configuration to handle circular references
        mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        mapper.configure(SerializationFeature.FAIL_ON_SELF_REFERENCES, false);
        mapper.configure(SerializationFeature.WRITE_SELF_REFERENCES_AS_NULL, true);
        
        // Configuration to avoid infinite recursion
        mapper.configure(SerializationFeature.WRITE_NULL_MAP_VALUES, false);
        mapper.configure(SerializationFeature.WRITE_EMPTY_JSON_ARRAYS, true);
        
        // Date format configuration - UTC across the board so that LocalDateTime
        // and java.util.Date round-trip as UTC instants regardless of JVM TZ.
        mapper.setDateFormat(new StdDateFormat());
        mapper.setTimeZone(TimeZone.getTimeZone("UTC"));

        return mapper;
    }
}
