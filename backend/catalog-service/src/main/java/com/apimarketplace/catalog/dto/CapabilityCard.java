package com.apimarketplace.catalog.dto;

import com.apimarketplace.catalog.util.SearchScoreClassifier;
import java.util.List;
import java.util.Map;

/**
 * DTO for capability card response
 */
public record CapabilityCard(
    String id,
    String name,
    String prov,
    List<String> needs,
    Map<String, Object> supp,
    boolean auth,
    double score,
    SearchScoreClassifier.Quality quality,
    String qualityLabel,
    int qualityPercentage
) {
    
    /**
     * Create a capability card from database result
     */
    public static CapabilityCard fromDbResult(Map<String, Object> row) {
        String id = getString(row, "tool_name_id");
        String name = getString(row, "name");
        String provider = getString(row, "provider");
        String method = getString(row, "method");
        String endpointPattern = getString(row, "endpoint_pattern");
        Boolean requiresUserCredentials = getBoolean(row, "requires_user_credentials");
        String runScope = getString(row, "run_scope");
        Double score = getDouble(row, "vec_score");
        if (score == null) score = getDouble(row, "bm25_score");
        if (score == null) score = 0.0;
        
        // Derive needs from method and endpoint pattern
        List<String> needs = deriveNeeds(method, endpointPattern);
        
        // Create support information
        Map<String, Object> supp = Map.of(
            "start_end", runScope != null ? runScope : "external",
            "limit", 100, // Default limit
            "sort", "relevance" // Default sort
        );
        
        // Classify as cosine similarity (assuming vector search)
        SearchScoreClassifier.Quality quality = SearchScoreClassifier.classifyCosineSimilarity(score);
        
        return new CapabilityCard(
            id,
            name,
            provider != null ? provider : "unknown",
            needs,
            supp,
            requiresUserCredentials != null ? requiresUserCredentials : false,
            score,
            quality,
            quality.getDisplayName(),
            SearchScoreClassifier.getQualityPercentage(quality)
        );
    }
    
    /**
     * Create a capability card for RRF fusion results
     */
    public static CapabilityCard forRRF(String id, String name, String provider, 
                                       List<String> needs, Map<String, Object> supp, 
                                       boolean auth, double rrfScore, 
                                       boolean hasKnnResults, boolean hasLexicalResults) {
        SearchScoreClassifier.Quality quality = SearchScoreClassifier.classifyRRFScore(
            rrfScore, hasKnnResults, hasLexicalResults);
        
        return new CapabilityCard(
            id,
            name,
            provider,
            needs,
            supp,
            auth,
            rrfScore,
            quality,
            quality.getDisplayName(),
            SearchScoreClassifier.getQualityPercentage(quality)
        );
    }
    
    /**
     * Derive needs from method and endpoint pattern
     */
    private static List<String> deriveNeeds(String method, String endpointPattern) {
        if (method == null || endpointPattern == null) {
            return List.of("query");
        }
        
        // Simple heuristic based on method and endpoint
        if ("GET".equals(method)) {
            if (endpointPattern.contains("user") || endpointPattern.contains("profile")) {
                return List.of("username", "user_id");
            } else if (endpointPattern.contains("post") || endpointPattern.contains("tweet")) {
                return List.of("post_id", "content");
            } else if (endpointPattern.contains("comment")) {
                return List.of("comment_id", "content");
            } else {
                return List.of("query", "filters");
            }
        } else if ("POST".equals(method)) {
            return List.of("data", "content");
        } else if ("PUT".equals(method) || "PATCH".equals(method)) {
            return List.of("id", "data");
        } else if ("DELETE".equals(method)) {
            return List.of("id");
        }
        
        return List.of("query");
    }
    
    private static String getString(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value != null ? value.toString() : null;
    }
    
    private static Boolean getBoolean(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        } else if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        return null;
    }
    
    private static Double getDouble(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        } else if (value instanceof String) {
            try {
                return Double.parseDouble((String) value);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
