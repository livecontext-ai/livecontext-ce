package com.apimarketplace.catalog.dto;

import java.util.List;

/**
 * DTO for receiving pre-computed synthesis data from API import.
 * This allows direct population of lexical_search_index without AI generation.
 */
public record SynthesisDataRequest(
    // Tool name (optional - resolved from DB if absent)
    String toolName,

    // Required identity fields
    String provider,
    String resource,
    String action,
    String endpoint,

    // Summary fields
    String summary,
    String summaryExtended,

    // Keywords for search
    List<String> keywordsPrimary,
    List<String> keywordsSynonyms,
    List<String> keywordsParams,
    List<String> useCases,

    // Parameters
    List<String> paramsRequired,
    List<String> paramsOptional,
    List<String> paramExamples,

    // Legacy keywords field (backward compatibility)
    String keywords
) {
    /**
     * Check if this request has valid synthesis data
     */
    public boolean hasValidSynthesis() {
        return provider != null && !provider.isBlank()
            && resource != null && !resource.isBlank()
            && action != null && !action.isBlank();
    }

    /**
     * Build a combined keywords string from primary and synonyms
     */
    public String buildKeywordsString() {
        StringBuilder sb = new StringBuilder();
        if (keywordsPrimary != null && !keywordsPrimary.isEmpty()) {
            sb.append(String.join(", ", keywordsPrimary));
        }
        if (keywordsSynonyms != null && !keywordsSynonyms.isEmpty()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(String.join(", ", keywordsSynonyms));
        }
        if (sb.length() == 0 && keywords != null) {
            return keywords;
        }
        return sb.toString();
    }
}
