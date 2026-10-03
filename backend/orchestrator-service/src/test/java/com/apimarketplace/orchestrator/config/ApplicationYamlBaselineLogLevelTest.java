package com.apimarketplace.orchestrator.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LC-090 regression: the profile-less {@code application.yml} set the orchestrator package (and
 * {@code http.client.logging.level}) to DEBUG. Only {@code application-prod.yml} lowered it, so
 * any environment started without {@code SPRING_PROFILES_ACTIVE=prod} logged request payloads and
 * workflow node inputs. The baseline must not enable DEBUG/TRACE anywhere.
 */
@DisplayName("application.yml baseline log levels (LC-090)")
class ApplicationYamlBaselineLogLevelTest {

    private static Properties baseline() {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        // Read the MAIN file by path: src/test/resources/application.yml shadows it on the test
        // classpath, and a classpath lookup would silently check the test file instead.
        FileSystemResource main = new FileSystemResource("src/main/resources/application.yml");
        assertThat(main.exists()).as("run from the orchestrator-service module dir").isTrue();
        factory.setResources(main);
        return factory.getObject();
    }

    @Test
    @DisplayName("the orchestrator package logs at INFO in the baseline")
    void orchestratorPackageIsInfo() {
        assertThat(baseline().getProperty("logging.level.com.apimarketplace.orchestrator")).isEqualTo("INFO");
    }

    @Test
    @DisplayName("no logging.level.* and no http.client.logging.level is DEBUG or TRACE in the baseline")
    void noDebugOrTraceLevelInBaseline() {
        Properties props = baseline();
        props.stringPropertyNames().stream()
            .filter(key -> key.startsWith("logging.level.") || key.equals("http.client.logging.level"))
            .forEach(key -> assertThat(props.getProperty(key).trim().toUpperCase())
                .as(key)
                .isNotIn("DEBUG", "TRACE"));
    }
}
