package com.apimarketplace.agent.cloud;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CloudLlmRuntimeAccess - default catalog-relay methods")
class CloudLlmRuntimeAccessDefaultsTest {

    /**
     * Minimal implementation overriding ONLY the abstract LLM methods, exactly like a
     * pre-existing non-CE implementation that predates the catalog-relay extension.
     */
    private final CloudLlmRuntimeAccess legacyImplementation = new CloudLlmRuntimeAccess() {
        @Override
        public CloudLlmSource getEffectiveLlmSource(String tenantId) {
            return CloudLlmSource.CLOUD;
        }

        @Override
        public Optional<CloudLlmRuntimeCredentials> resolveCloudRuntime(String tenantId) {
            return Optional.of(new CloudLlmRuntimeCredentials("tok", "install-1", "https://livecontext.ai/api"));
        }
    };

    @Test
    @DisplayName("getEffectiveCatalogSource defaults to BYOK even when the LLM source is CLOUD (toggle independence)")
    void catalogSourceDefaultsToByok() {
        assertThat(legacyImplementation.getEffectiveCatalogSource("42")).isEqualTo(CloudLlmSource.BYOK);
        // The pre-existing LLM behavior stays untouched by the extension.
        assertThat(legacyImplementation.getEffectiveLlmSource("42")).isEqualTo(CloudLlmSource.CLOUD);
    }

    @Test
    @DisplayName("resolveCatalogCloudRuntime defaults to empty even when LLM relay credentials resolve")
    void catalogRuntimeDefaultsToEmpty() {
        assertThat(legacyImplementation.resolveCatalogCloudRuntime("42")).isEmpty();
        assertThat(legacyImplementation.resolveCloudRuntime("42")).isPresent();
    }

    @Test
    @DisplayName("resolveActiveCatalogRuntime defaults to empty")
    void activeCatalogRuntimeDefaultsToEmpty() {
        assertThat(legacyImplementation.resolveActiveCatalogRuntime()).isEmpty();
    }
}
