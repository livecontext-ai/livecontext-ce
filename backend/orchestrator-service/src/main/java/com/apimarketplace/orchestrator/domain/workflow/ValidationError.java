package com.apimarketplace.orchestrator.domain.workflow;

import java.util.Map;

/**
 * Erreur de validation
 */
public record ValidationError(String type, String message, String path, Map<String, Object> context) {}
