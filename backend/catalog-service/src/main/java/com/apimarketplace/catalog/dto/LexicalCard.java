package com.apimarketplace.catalog.dto;

import com.apimarketplace.catalog.util.SearchScoreClassifier;
import java.util.List;
import java.util.Map;

/**
 * DTO for lexical search result card
 */
public record LexicalCard(
    String toolId,
    String displayName,
    String provider,
    String resource,
    String action,
    List<String> categories,
    Map<String, Object> metadata,
    boolean requiresCredentials,
    double bm25Score,
    SearchScoreClassifier.Quality quality,
    String qualityLabel,
    int qualityPercentage
) {
    
    /**
     * Create a lexical card with basic information
     */
    public static LexicalCard of(String toolId, String displayName, String provider, 
                               String resource, String action, double bm25Score) {
        SearchScoreClassifier.Quality quality = SearchScoreClassifier.classifyBM25Score(bm25Score);
        return new LexicalCard(
            toolId,
            displayName,
            provider,
            resource,
            action,
            List.of(),
            Map.of(),
            false,
            bm25Score,
            quality,
            quality.getDisplayName(),
            SearchScoreClassifier.getQualityPercentage(quality)
        );
    }
    
    /**
     * Create a lexical card with full information
     */
    public static LexicalCard of(String toolId, String displayName, String provider,
                               String resource, String action, List<String> categories,
                               Map<String, Object> metadata, boolean requiresCredentials,
                               double bm25Score) {
        SearchScoreClassifier.Quality quality = SearchScoreClassifier.classifyBM25Score(bm25Score);
        return new LexicalCard(
            toolId,
            displayName,
            provider,
            resource,
            action,
            categories,
            metadata,
            requiresCredentials,
            bm25Score,
            quality,
            quality.getDisplayName(),
            SearchScoreClassifier.getQualityPercentage(quality)
        );
    }
}
