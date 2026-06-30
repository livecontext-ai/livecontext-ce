package com.apimarketplace.catalog.integration;

import com.apimarketplace.catalog.config.SearchConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SearchConfigIntegrationTest - Search configuration defaults and behavior")
class SearchConfigIntegrationTest {

    @Nested
    @DisplayName("Default configuration values")
    class DefaultValues {

        @Test
        @DisplayName("AutoPick config should have correct defaults")
        void autoPickDefaults() {
            SearchConfig config = new SearchConfig();
            SearchConfig.AutoPickConfig autoPick = config.getAutoPick();

            assertThat(autoPick.getMarginRatio()).isEqualTo(1.3);
            assertThat(autoPick.getMinScore()).isEqualTo(0.015);
        }

        @Test
        @DisplayName("Reranking config should have correct defaults")
        void rerankingDefaults() {
            SearchConfig config = new SearchConfig();
            SearchConfig.RerankingConfig reranking = config.getReranking();

            assertThat(reranking.isEnabled()).isTrue();
            assertThat(reranking.getTopK()).isEqualTo(20);
            assertThat(reranking.getModel()).isEqualTo("cross-encoder");
        }

        @Test
        @DisplayName("QueryUnderstanding config should have correct defaults")
        void queryUnderstandingDefaults() {
            SearchConfig config = new SearchConfig();
            SearchConfig.QueryUnderstandingConfig qu = config.getQueryUnderstanding();

            assertThat(qu.isEnabled()).isTrue();
            assertThat(qu.isExtractHints()).isTrue();
        }
    }

    @Nested
    @DisplayName("Configurable values")
    class ConfigurableValues {

        @Test
        @DisplayName("should allow disabling reranking")
        void shouldAllowDisablingReranking() {
            SearchConfig config = new SearchConfig();
            config.getReranking().setEnabled(false);

            assertThat(config.getReranking().isEnabled()).isFalse();
        }

        @Test
        @DisplayName("should allow disabling query understanding")
        void shouldAllowDisablingQueryUnderstanding() {
            SearchConfig config = new SearchConfig();
            config.getQueryUnderstanding().setEnabled(false);
            config.getQueryUnderstanding().setExtractHints(false);

            assertThat(config.getQueryUnderstanding().isEnabled()).isFalse();
            assertThat(config.getQueryUnderstanding().isExtractHints()).isFalse();
        }

        @Test
        @DisplayName("should allow updating auto-pick thresholds")
        void shouldAllowUpdatingAutoPickThresholds() {
            SearchConfig config = new SearchConfig();
            config.getAutoPick().setMarginRatio(2.0);
            config.getAutoPick().setMinScore(0.05);

            assertThat(config.getAutoPick().getMarginRatio()).isEqualTo(2.0);
            assertThat(config.getAutoPick().getMinScore()).isEqualTo(0.05);
        }
    }

    @Nested
    @DisplayName("Auto-pick scoring validation")
    class AutoPickScoringValidation {

        @Test
        @DisplayName("auto-pick margin ratio should correctly determine dominance")
        void autoPickMarginRatio() {
            SearchConfig config = new SearchConfig();
            double marginRatio = config.getAutoPick().getMarginRatio();

            double topScore = 0.05;
            double secondScore = 0.03;

            boolean isDominant = (topScore / secondScore) >= marginRatio;
            assertThat(isDominant).isTrue(); // 0.05/0.03 = 1.67 >= 1.3

            double closeSecond = 0.04;
            boolean isCloseNotDominant = (topScore / closeSecond) >= marginRatio;
            assertThat(isCloseNotDominant).isFalse(); // 0.05/0.04 = 1.25 < 1.3
        }
    }
}
