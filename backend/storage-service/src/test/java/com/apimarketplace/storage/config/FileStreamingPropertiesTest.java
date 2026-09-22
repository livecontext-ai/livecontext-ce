package com.apimarketplace.storage.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.io.ClassPathResource;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two properties the file-streaming endpoints depend on, both of which fail SILENTLY
 * if a merge ever drops them. Same shape and same reason as
 * {@code ApiCatalogBundleServingPropertiesTest} in catalog-service.
 *
 * <ul>
 *   <li>{@code spring.mvc.async.request-timeout} - {@code GET /api/files/proxy-signed}
 *       returns a {@link org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody},
 *       so the container's async deadline applies to the whole object-store to client
 *       transfer. The container default is 30s, and over the 3 days to 2026-09-21 this
 *       endpoint served 112 requests past 30s and 77 past 60s. Losing this property
 *       raises no error: it aborts the exchange on an already-committed response, so the
 *       client just receives a truncated file.</li>
 *   <li>{@code spring.jpa.open-in-view} - left at Spring's default of true, a query
 *       issued outside a transaction during rendering would hold one of the 20 Hikari
 *       connections for the entire (now much longer) stream.</li>
 * </ul>
 *
 * <p>Neither is observable from a unit test of the endpoint itself, so they are pinned
 * here against the shipped configuration file.
 */
@DisplayName("storage-service file-streaming properties")
class FileStreamingPropertiesTest {

    private static Properties applicationYml() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties props = yaml.getObject();
        assertThat(props).as("application.yml must be on the test classpath").isNotNull();
        return props;
    }

    /** Parsed the way Spring binds it, so `10m` is as valid here as `600000`. */
    private static Duration asyncRequestTimeout() {
        String value = applicationYml().getProperty("spring.mvc.async.request-timeout");
        assertThat(value)
                .as("spring.mvc.async.request-timeout must stay set: without it the container's "
                        + "30s default truncates proxy-signed downloads mid-stream")
                .isNotNull();
        return DurationStyle.detectAndParse(value, ChronoUnit.MILLIS);
    }

    @Test
    @DisplayName("The async request timeout is set well above the slowest observed download")
    void asyncRequestTimeoutIsSet() {
        assertThat(asyncRequestTimeout())
                .as("must leave room above the ~61s worst case observed in production")
                .isGreaterThanOrEqualTo(Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("The async request timeout stays FINITE so a stuck stream releases its slot")
    void asyncRequestTimeoutIsFinite() {
        // -1 (no timeout) is the tempting simplification and it is wrong here: these
        // streams run on the bounded mvcStreamingTaskExecutor, and the async deadline is
        // the only backstop for a stream whose task was rejected during a graceful
        // shutdown (CommonAsyncConfig.RunInCallerUnlessShuttingDown documents that path).
        assertThat(asyncRequestTimeout())
                .as("must be a real deadline, not -1")
                .isPositive()
                .isLessThanOrEqualTo(Duration.ofMinutes(30));
    }

    @Test
    @DisplayName("Open-in-view stays off so a slow stream can never pin a pooled connection")
    void openInViewIsDisabled() {
        assertThat(applicationYml().getProperty("spring.jpa.open-in-view"))
                .as("spring.jpa.open-in-view must stay false")
                .isEqualTo("false");
    }
}
