package com.apimarketplace.catalog.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SearchConfig")
class SearchConfigTest {

    @Nested
    @DisplayName("AutoPickConfig")
    class AutoPickConfigTests {

        @Test
        @DisplayName("should have correct default values")
        void hasCorrectDefaults() {
            SearchConfig.AutoPickConfig autoPick = new SearchConfig.AutoPickConfig();

            assertThat(autoPick.getMarginRatio()).isEqualTo(1.3);
            assertThat(autoPick.getMinScore()).isEqualTo(0.015);
        }

        @Test
        @DisplayName("should allow setting custom values")
        void allowsCustomValues() {
            SearchConfig.AutoPickConfig autoPick = new SearchConfig.AutoPickConfig();
            autoPick.setMarginRatio(1.5);
            autoPick.setMinScore(0.02);

            assertThat(autoPick.getMarginRatio()).isEqualTo(1.5);
            assertThat(autoPick.getMinScore()).isEqualTo(0.02);
        }
    }

    @Nested
    @DisplayName("RerankingConfig")
    class RerankingConfigTests {

        @Test
        @DisplayName("should have correct default values")
        void hasCorrectDefaults() {
            SearchConfig.RerankingConfig reranking = new SearchConfig.RerankingConfig();

            assertThat(reranking.isEnabled()).isTrue();
            assertThat(reranking.getTopK()).isEqualTo(20);
            assertThat(reranking.getModel()).isEqualTo("cross-encoder");
        }

        @Test
        @DisplayName("should allow disabling reranking")
        void allowsDisablingReranking() {
            SearchConfig.RerankingConfig reranking = new SearchConfig.RerankingConfig();
            reranking.setEnabled(false);
            reranking.setTopK(10);
            reranking.setModel("custom-model");

            assertThat(reranking.isEnabled()).isFalse();
            assertThat(reranking.getTopK()).isEqualTo(10);
            assertThat(reranking.getModel()).isEqualTo("custom-model");
        }
    }

    @Nested
    @DisplayName("QueryUnderstandingConfig")
    class QueryUnderstandingConfigTests {

        @Test
        @DisplayName("should have correct default values")
        void hasCorrectDefaults() {
            SearchConfig.QueryUnderstandingConfig queryUnderstanding = new SearchConfig.QueryUnderstandingConfig();

            assertThat(queryUnderstanding.isEnabled()).isTrue();
            assertThat(queryUnderstanding.isExtractHints()).isTrue();
        }

        @Test
        @DisplayName("should allow disabling features")
        void allowsDisablingFeatures() {
            SearchConfig.QueryUnderstandingConfig queryUnderstanding = new SearchConfig.QueryUnderstandingConfig();
            queryUnderstanding.setEnabled(false);
            queryUnderstanding.setExtractHints(false);

            assertThat(queryUnderstanding.isEnabled()).isFalse();
            assertThat(queryUnderstanding.isExtractHints()).isFalse();
        }
    }

    @Nested
    @DisplayName("SearchConfig root")
    class SearchConfigRootTests {

        @Test
        @DisplayName("should initialize with nested config objects")
        void initializesWithNestedConfigs() {
            SearchConfig config = new SearchConfig();

            assertThat(config.getAutoPick()).isNotNull();
            assertThat(config.getReranking()).isNotNull();
            assertThat(config.getQueryUnderstanding()).isNotNull();
        }
    }
}
