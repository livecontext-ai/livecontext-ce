package com.apimarketplace.catalog.service.exception;

/**
 * Exception thrown when monetization configuration is invalid or processing fails.
 */
public class MonetizationException extends CatalogServiceException {

    public MonetizationException(String message) {
        super(message, "MONETIZATION_ERROR");
    }

    public MonetizationException(String message, Throwable cause) {
        super(message, "MONETIZATION_ERROR", cause);
    }

    public static MonetizationException invalidType(String type) {
        return new MonetizationException(
            "Unsupported monetization type: " + type + ". Supported types: FREEMIUM, PAID");
    }

    public static MonetizationException missingConfiguration(String toolName) {
        return new MonetizationException(
            "No valid monetization configuration found for tool: " + toolName);
    }

    public static MonetizationException toolNotInPlan(String toolName) {
        return new MonetizationException(
            "Tool '" + toolName + "' is not included in any selected plan for PAID monetization");
    }

    public static MonetizationException invalidPaidConfig() {
        return new MonetizationException(
            "Invalid PAID monetization configuration: selectedPlans or planTools missing");
    }
}
