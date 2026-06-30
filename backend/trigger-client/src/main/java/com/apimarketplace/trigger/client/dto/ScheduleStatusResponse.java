package com.apimarketplace.trigger.client.dto;

import java.time.Instant;

/**
 * Response DTO for schedule status.
 */
public record ScheduleStatusResponse(
    String cron,
    String timezone,
    boolean enabled,
    Instant lastExecutionAt,
    Instant nextExecutionAt,
    int executionCount,
    String status,
    Integer maxExecutions
) {}
