package com.apimarketplace.orchestrator.service.validation;

/**
 * Represents a validation error for a node parameter.
 */
public record ValidationError(
    String parameter,
    String code,
    String message
) {}
