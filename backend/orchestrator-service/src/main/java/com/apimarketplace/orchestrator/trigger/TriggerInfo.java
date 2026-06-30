package com.apimarketplace.orchestrator.trigger;

import com.apimarketplace.orchestrator.domain.workflow.Trigger;

import java.util.Map;

/**
 * DTO representing trigger information for multi-DAG workflows.
 *
 * Provides frontend with details about available triggers in a workflow,
 * enabling trigger selection UI for multi-trigger workflows.
 */
public record TriggerInfo(
    String triggerId,           // Normalized key, e.g., "trigger:my_webhook"
    String label,               // Display label, e.g., "My Webhook"
    String type,                // Trigger type: "webhook", "manual", "chat", "datasource", "form", "schedule"
    boolean isReusable,         // Can fire multiple times (epochs)
    Map<String, Object> config  // Type-specific configuration (form fields, chat match config, etc.)
) {

    /**
     * Create TriggerInfo from a Trigger domain object.
     *
     * @param trigger The trigger domain object
     * @return TriggerInfo DTO
     */
    public static TriggerInfo fromTrigger(Trigger trigger) {
        return new TriggerInfo(
            trigger.getNormalizedKey(),
            trigger.label() != null ? trigger.label() : trigger.id(),
            trigger.type(),
            TriggerType.isReusableTriggerType(trigger.type()),
            extractConfig(trigger)
        );
    }

    /**
     * Extract type-specific configuration from trigger.
     */
    private static Map<String, Object> extractConfig(Trigger trigger) {
        if (trigger.params() == null) {
            return Map.of();
        }

        // Include relevant config based on trigger type
        return switch (trigger.type() != null ? trigger.type().toLowerCase() : "") {
            case "form" -> Map.of(
                "formTitle", trigger.params().getOrDefault("formTitle", ""),
                "formDescription", trigger.params().getOrDefault("formDescription", ""),
                "submitButtonText", trigger.params().getOrDefault("submitButtonText", "Submit"),
                "fields", trigger.params().getOrDefault("fields", java.util.List.of())
            );
            case "chat" -> {
                var chatMatch = trigger.chatMatch();
                if (chatMatch != null) {
                    yield Map.of(
                        "matchType", chatMatch.type() != null ? chatMatch.type() : "any",
                        "matchValue", chatMatch.value() != null ? chatMatch.value() : "",
                        "caseSensitive", chatMatch.caseSensitive()
                    );
                }
                yield Map.of();
            }
            case "webhook" -> Map.of(
                "httpMethod", trigger.params().getOrDefault("httpMethod", "POST"),
                "authType", trigger.params().getOrDefault("authType", "none")
            );
            case "datasource" -> Map.of(
                "datasourceId", trigger.params().getOrDefault("datasourceId", ""),
                "strategy", trigger.strategy() != null ? trigger.strategy() : "single"
            );
            case "schedule" -> Map.of(
                "cron", trigger.params().getOrDefault("cron", ""),
                "timezone", trigger.params().getOrDefault("timezone", "UTC")
            );
            default -> Map.of();
        };
    }
}
