package com.apimarketplace.catalog.domain.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class ToolCandidate {
    private String toolId;
    private String name;
    private String description;
    private String category;
    private double confidence;
    private String reason;
    private Map<String, Object> metadata;
}


