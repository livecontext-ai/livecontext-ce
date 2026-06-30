package com.apimarketplace.orchestrator.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for StateManagerConfig.
 */
@DisplayName("StateManagerConfig")
class StateManagerConfigTest {

    private StateManagerConfig config;

    @BeforeEach
    void setUp() {
        config = new StateManagerConfig();
    }

    @Test
    @DisplayName("Should have enabled=true by default")
    void shouldBeEnabledByDefault() {
        assertThat(config.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("Should set enabled to false")
    void shouldSetEnabledToFalse() {
        config.setEnabled(false);
        assertThat(config.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("Should set enabled to true")
    void shouldSetEnabledToTrue() {
        config.setEnabled(false);
        config.setEnabled(true);
        assertThat(config.isEnabled()).isTrue();
    }
}
