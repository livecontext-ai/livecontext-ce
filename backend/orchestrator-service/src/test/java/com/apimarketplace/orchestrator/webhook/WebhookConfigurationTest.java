package com.apimarketplace.orchestrator.webhook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for WebhookConfiguration.
 * Verifies that the configuration class can be instantiated.
 */
@DisplayName("WebhookConfiguration")
class WebhookConfigurationTest {

    @Test
    @DisplayName("Should be instantiable")
    void shouldBeInstantiable() {
        WebhookConfiguration config = new WebhookConfiguration();
        assertThat(config).isNotNull();
    }
}
