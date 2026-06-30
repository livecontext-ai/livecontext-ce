package com.apimarketplace.catalog.dto;

import java.util.List;
import java.util.Map;

/**
 * DTO for capability search response
 */
public record CapabilityResponse(
    List<CapabilityCard> cards,
    Map<String, Object> meta,
    List<Map<String, Object>> knnResults,
    List<Map<String, Object>> lexicalResults
) {
    
    /**
     * Create a capability response with metadata
     */
    public static CapabilityResponse of(List<CapabilityCard> cards, Map<String, Object> meta) {
        return new CapabilityResponse(cards, meta, List.of(), List.of());
    }
    
    /**
     * Create a capability response with default metadata
     */
    public static CapabilityResponse of(List<CapabilityCard> cards) {
        Map<String, Object> meta = Map.of(
            "total", cards.size(),
            "timestamp", System.currentTimeMillis(),
            "version", "1.0"
        );
        return new CapabilityResponse(cards, meta, List.of(), List.of());
    }
    
    /**
     * Create a capability response with custom metadata
     */
    public static CapabilityResponse of(List<CapabilityCard> cards, int total, long latencyMs, String query) {
        Map<String, Object> meta = Map.of(
            "total", total,
            "returned", cards.size(),
            "query", query,
            "latency_ms", latencyMs,
            "timestamp", System.currentTimeMillis(),
            "version", "1.0"
        );
        return new CapabilityResponse(cards, meta, List.of(), List.of());
    }
    
    /**
     * Create a capability response with KNN and lexical results for debugging
     */
    public static CapabilityResponse of(List<CapabilityCard> cards, Map<String, Object> meta, 
                                      List<Map<String, Object>> knnResults, List<Map<String, Object>> lexicalResults) {
        return new CapabilityResponse(cards, meta, knnResults, lexicalResults);
    }
}
