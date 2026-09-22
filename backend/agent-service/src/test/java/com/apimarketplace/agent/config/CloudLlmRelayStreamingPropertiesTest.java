package com.apimarketplace.agent.config;

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
 * Pins {@code spring.mvc.async.request-timeout} for the CE LLM relay.
 *
 * <p>{@code CloudLlmRelayController.stream} returns a
 * {@link org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody} that
 * relays an entire LLM completion to a self-hosted install as NDJSON, so the container's
 * async deadline governs the whole completion rather than the first byte. The container
 * default is 30s, which one long completion routinely exceeds, and the failure is silent:
 * the exchange is aborted on an already-committed response, so the CE side receives
 * truncated NDJSON instead of an error it could retry.
 *
 * <p>Same shape as {@code FileStreamingPropertiesTest} (storage-service) and the
 * {@code MonolithCeConfigContractTest} case for the CE monolith: all three mount a
 * streaming endpoint, and in all three the property is invisible to every other test.
 */
@DisplayName("agent-service CE LLM relay streaming properties")
class CloudLlmRelayStreamingPropertiesTest {

    private static Properties applicationYml() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties props = yaml.getObject();
        assertThat(props).as("application.yml must be on the test classpath").isNotNull();
        return props;
    }

    /** Parsed the way Spring binds it, so {@code 10m} is as valid here as {@code 600000}. */
    private static Duration asyncRequestTimeout() {
        String value = applicationYml().getProperty("spring.mvc.async.request-timeout");
        assertThat(value)
                .as("spring.mvc.async.request-timeout must stay set: without it the container's "
                        + "30s default truncates a relayed LLM completion mid-stream")
                .isNotNull();
        return DurationStyle.detectAndParse(value, ChronoUnit.MILLIS);
    }

    @Test
    @DisplayName("The async deadline covers a long completion and stays finite")
    void asyncRequestTimeoutIsSetAndFinite() {
        // Finite on purpose: -1 would leave a wedged relay holding its MVC async executor
        // slot forever, and this service shares that pool with every other async handler.
        assertThat(asyncRequestTimeout())
                .as("a single LLM completion must be able to finish, without a wedged relay "
                        + "holding an executor slot indefinitely")
                .isGreaterThanOrEqualTo(Duration.ofMinutes(10))
                .isLessThanOrEqualTo(Duration.ofMinutes(30));
    }
}
