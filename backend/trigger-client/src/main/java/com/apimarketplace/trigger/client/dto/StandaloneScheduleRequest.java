package com.apimarketplace.trigger.client.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for creating/updating standalone schedules.
 */
public record StandaloneScheduleRequest(
    @Size(max = 255, message = "Name must not exceed 255 characters")
    String name,

    @Size(max = 1000, message = "Description must not exceed 1000 characters")
    String description,

    @NotBlank(message = "Cron expression is required")
    String cron,

    String timezone,

    Integer maxExecutions,

    Boolean enabled,

    Integer expiresInDays,

    String sourceNodeId
) {
    public StandaloneScheduleRequest {
        if (timezone == null || timezone.isBlank()) timezone = "UTC";
        if (enabled == null) enabled = true;
    }
}
