package com.apimarketplace.common.storage.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("StorageMappingConfig Tests")
class StorageMappingConfigTest {

    @Nested
    @DisplayName("Default Values")
    class DefaultValuesTests {

        @Test
        @DisplayName("no-arg constructor falls back to the dev localhost catalog URL")
        void shouldHaveDefaultCatalogBaseUrl() {
            StorageMappingConfig config = new StorageMappingConfig();

            assertThat(config.getCatalogBaseUrl()).isEqualTo("http://localhost:8081");
        }

        @Test
        @DisplayName("should be enabled by default")
        void shouldBeEnabledByDefault() {
            StorageMappingConfig config = new StorageMappingConfig();

            assertThat(config.isEnabled()).isTrue();
        }

        @Test
        @DisplayName("should have default timeout of 30 seconds")
        void shouldHaveDefaultTimeoutOf30Seconds() {
            StorageMappingConfig config = new StorageMappingConfig();

            assertThat(config.getTimeoutSeconds()).isEqualTo(30);
        }
    }

    @Nested
    @DisplayName("Catalog URL resolution precedence")
    class CatalogUrlPrecedenceTests {

        // The k3s-regression guard: with NO explicit storage.mapping.catalog-base-url, the effective URL
        // must be the platform services.catalog-url (injected by the constructor), NOT the localhost default.
        @Test
        @DisplayName("inherits the platform services.catalog-url when no explicit override is set")
        void inheritsPlatformCatalogUrlWhenNoExplicitOverride() {
            StorageMappingConfig config =
                new StorageMappingConfig("http://livecontext-livecontext-catalog:8081");

            assertThat(config.getCatalogBaseUrl())
                .isEqualTo("http://livecontext-livecontext-catalog:8081");
        }

        @Test
        @DisplayName("explicit storage.mapping.catalog-base-url override wins over the platform URL")
        void explicitOverrideWinsOverPlatformUrl() {
            StorageMappingConfig config =
                new StorageMappingConfig("http://livecontext-livecontext-catalog:8081");
            config.setCatalogBaseUrl("http://explicit-override:9999");

            assertThat(config.getCatalogBaseUrl()).isEqualTo("http://explicit-override:9999");
        }

        @Test
        @DisplayName("blank explicit override is ignored and the platform URL is used")
        void blankExplicitOverrideFallsBackToPlatformUrl() {
            StorageMappingConfig config =
                new StorageMappingConfig("http://livecontext-livecontext-catalog:8081");
            config.setCatalogBaseUrl("   ");

            assertThat(config.getCatalogBaseUrl())
                .isEqualTo("http://livecontext-livecontext-catalog:8081");
        }

        @Test
        @DisplayName("null explicit override is ignored and the platform URL is used")
        void nullExplicitOverrideFallsBackToPlatformUrl() {
            StorageMappingConfig config =
                new StorageMappingConfig("http://livecontext-livecontext-catalog:8081");
            config.setCatalogBaseUrl(null);

            assertThat(config.getCatalogBaseUrl())
                .isEqualTo("http://livecontext-livecontext-catalog:8081");
        }
    }

    @Nested
    @DisplayName("Setters and Getters")
    class SettersGettersTests {

        @Test
        @DisplayName("should set and get catalogBaseUrl")
        void shouldSetAndGetCatalogBaseUrl() {
            StorageMappingConfig config = new StorageMappingConfig();
            config.setCatalogBaseUrl("http://catalog:8080");

            assertThat(config.getCatalogBaseUrl()).isEqualTo("http://catalog:8080");
        }

        @Test
        @DisplayName("should set and get enabled")
        void shouldSetAndGetEnabled() {
            StorageMappingConfig config = new StorageMappingConfig();
            config.setEnabled(false);

            assertThat(config.isEnabled()).isFalse();
        }

        @Test
        @DisplayName("should set and get timeoutSeconds")
        void shouldSetAndGetTimeoutSeconds() {
            StorageMappingConfig config = new StorageMappingConfig();
            config.setTimeoutSeconds(60);

            assertThat(config.getTimeoutSeconds()).isEqualTo(60);
        }
    }
}
