package com.apimarketplace.orchestrator.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for AgentDefaultsConfig.
 */
@DisplayName("AgentDefaultsConfig")
class AgentDefaultsConfigTest {

    private AgentDefaultsConfig config;

    @BeforeEach
    void setUp() {
        config = new AgentDefaultsConfig();
    }

    @Nested
    @DisplayName("default values")
    class DefaultValueTests {

        @Test
        @DisplayName("Should have default temperature of 0.7")
        void shouldHaveDefaultTemperature() {
            assertThat(config.getTemperature()).isEqualTo(0.7);
        }

        @Test
        @DisplayName("Should have default maxTokens of 16000 (clamped per-model at runtime)")
        void shouldHaveDefaultMaxTokens() {
            assertThat(config.getMaxTokens()).isEqualTo(16000);
        }

        @Test
        @DisplayName("Should have default maxIterations of 100")
        void shouldHaveDefaultMaxIterations() {
            assertThat(config.getMaxIterations()).isEqualTo(100);
        }
    }

    @Nested
    @DisplayName("setters")
    class SetterTests {

        @Test
        @DisplayName("Should set temperature")
        void shouldSetTemperature() {
            config.setTemperature(0.5);
            assertThat(config.getTemperature()).isEqualTo(0.5);
        }

        @Test
        @DisplayName("Should set maxTokens")
        void shouldSetMaxTokens() {
            config.setMaxTokens(8000);
            assertThat(config.getMaxTokens()).isEqualTo(8000);
        }

        @Test
        @DisplayName("Should set maxIterations")
        void shouldSetMaxIterations() {
            config.setMaxIterations(100);
            assertThat(config.getMaxIterations()).isEqualTo(100);
        }
    }
}
