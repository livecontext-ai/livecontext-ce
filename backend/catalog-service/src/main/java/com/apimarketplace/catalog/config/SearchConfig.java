package com.apimarketplace.catalog.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for search ranking improvements including RRF, reranking, and query understanding.
 */
@Configuration
@ConfigurationProperties(prefix = "search")
@Getter
@Setter
public class SearchConfig {

    private AutoPickConfig autoPick = new AutoPickConfig();
    private RerankingConfig reranking = new RerankingConfig();
    private QueryUnderstandingConfig queryUnderstanding = new QueryUnderstandingConfig();

    @Getter
    @Setter
    public static class AutoPickConfig {
        /**
         * Ratio threshold for auto-pick: top score must be this many times better than second.
         * Example: 1.3 means top must be 30% better than second (default: 1.3)
         */
        private double marginRatio = 1.3;

        /** Minimum RRF score required for auto-pick consideration (default: 0.015) */
        private double minScore = 0.015;
    }

    @Getter
    @Setter
    public static class RerankingConfig {
        /** Enable cross-encoder reranking (default: true) */
        private boolean enabled = true;

        /** Number of top candidates to rerank (default: 20) */
        private int topK = 20;

        /** Reranking model type (default: cross-encoder) */
        private String model = "cross-encoder";
    }

    @Getter
    @Setter
    public static class QueryUnderstandingConfig {
        /** Enable query understanding (default: true) */
        private boolean enabled = true;

        /** Auto-extract hints (provider, action, resource) from query (default: true) */
        private boolean extractHints = true;
    }
}
