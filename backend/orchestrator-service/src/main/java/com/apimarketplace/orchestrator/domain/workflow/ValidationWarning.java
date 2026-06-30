package com.apimarketplace.orchestrator.domain.workflow;

import java.util.Map;

/**
 * Avertissement de validation
 */
public record ValidationWarning(String type, String message, String path, Map<String, Object> context) {}
