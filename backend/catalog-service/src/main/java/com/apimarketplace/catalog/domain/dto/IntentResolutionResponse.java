package com.apimarketplace.catalog.domain.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Reponse de resolution d'intention
 */
@Data
@Builder
public class IntentResolutionResponse {
    private String query;
    private List<ToolCandidate> candidates;
    private String error;
    private int totalCandidates;
}
