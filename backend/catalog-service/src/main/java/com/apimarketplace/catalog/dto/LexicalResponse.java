package com.apimarketplace.catalog.dto;

import java.util.List;
import java.util.Map;

/**
 * DTO for lexical search response
 */
public record LexicalResponse(
    List<LexicalCard> cards,
    Map<String, Object> meta,
    List<Map<String, Object>> rawResults
) {
    
    /**
     * Create a lexical response with metadata
     */
    public static LexicalResponse of(List<LexicalCard> cards, Map<String, Object> meta) {
        return new LexicalResponse(cards, meta, List.of());
    }
    
    /**
     * Create a lexical response with default metadata
     */
    public static LexicalResponse of(List<LexicalCard> cards) {
        Map<String, Object> meta = Map.of(
            "total", cards.size(),
            "timestamp", System.currentTimeMillis(),
            "version", "1.0"
        );
        return new LexicalResponse(cards, meta, List.of());
    }
    
    /**
     * Create a lexical response with custom metadata and raw results
     */
    public static LexicalResponse of(List<LexicalCard> cards, Map<String, Object> meta, 
                                   List<Map<String, Object>> rawResults) {
        return new LexicalResponse(cards, meta, rawResults);
    }
}
