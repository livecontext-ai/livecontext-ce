package com.apimarketplace.orchestrator.schedule;

import java.util.Map;

/**
 * Configuration for a schedule trigger.
 * Stored in Trigger.input for triggers of type "schedule".
 *
 * <p>Example configuration in workflow plan:
 * <pre>
 * {
 *   "triggers": [{
 *     "type": "schedule",
 *     "label": "daily_sync",
 *     "input": {
 *       "cron": "0 9 * * *",
 *       "timezone": "Europe/Paris",
 *       "maxExecutions": null,
 *       "enabled": true
 *     }
 *   }]
 * }
 * </pre>
 */
public record ScheduleConfig(
        String cron,           // Cron expression (e.g., "0 9 * * *")
        String timezone,       // Timezone (e.g., "Europe/Paris", default: "UTC")
        Integer maxExecutions, // Max executions limit (null = unlimited)
        boolean enabled        // Whether schedule is active
) {
    public ScheduleConfig {
        if (cron == null || cron.isBlank()) {
            throw new IllegalArgumentException("Cron expression is required");
        }
        timezone = timezone != null && !timezone.isBlank() ? timezone : "UTC";
    }

    /**
     * Creates a ScheduleConfig from a map (typically from Trigger.input).
     *
     * @param input The input map from trigger configuration
     * @return ScheduleConfig instance
     */
    public static ScheduleConfig fromMap(Map<String, Object> input) {
        if (input == null) {
            throw new IllegalArgumentException("Input map cannot be null");
        }

        String cron = (String) input.get("cron");
        String timezone = (String) input.getOrDefault("timezone", "UTC");

        Integer maxExecutions = null;
        Object maxExecValue = input.get("maxExecutions");
        if (maxExecValue != null) {
            if (maxExecValue instanceof Number) {
                maxExecutions = ((Number) maxExecValue).intValue();
            } else if (maxExecValue instanceof String && !((String) maxExecValue).isBlank()) {
                maxExecutions = Integer.parseInt((String) maxExecValue);
            }
        }

        boolean enabled = true;
        Object enabledValue = input.get("enabled");
        if (enabledValue != null) {
            if (enabledValue instanceof Boolean) {
                enabled = (Boolean) enabledValue;
            } else if (enabledValue instanceof String) {
                enabled = Boolean.parseBoolean((String) enabledValue);
            }
        }

        return new ScheduleConfig(cron, timezone, maxExecutions, enabled);
    }

    /**
     * Converts this config to a map for storage.
     *
     * @return Map representation of this config
     */
    public Map<String, Object> toMap() {
        return Map.of(
                "cron", cron,
                "timezone", timezone,
                "maxExecutions", maxExecutions != null ? maxExecutions : "",
                "enabled", enabled
        );
    }
}
