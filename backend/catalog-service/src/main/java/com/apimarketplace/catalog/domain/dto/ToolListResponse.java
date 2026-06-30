package com.apimarketplace.catalog.domain.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import com.apimarketplace.catalog.domain.ToolCard;

/**
 * Reponse de liste d'outils
 */
@Data
@Builder
public class ToolListResponse {
    private List<ToolCard> tools;
    private int total;
    private int limit;
    private int offset;
    private String error;
}
