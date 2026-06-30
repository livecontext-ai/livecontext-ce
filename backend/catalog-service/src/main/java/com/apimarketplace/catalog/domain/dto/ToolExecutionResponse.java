package com.apimarketplace.catalog.domain.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * Reponse d'execution d'un outil
 */
@Data
@Builder
public class ToolExecutionResponse {
    private boolean success;
    private Object result;
    private String error;
    private Map<String, Object> metadata;
    private long executionTimeMs;
    private String toolId;
    private String requestId;
}
