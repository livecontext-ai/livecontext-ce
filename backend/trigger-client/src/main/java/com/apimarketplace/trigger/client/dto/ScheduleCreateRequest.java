package com.apimarketplace.trigger.client.dto;

/**
 * Request DTO for creating/updating a schedule.
 */
public record ScheduleCreateRequest(
    String cron,
    String timezone,
    Integer maxExecutions,
    boolean enabled,
    Integer expiresInDays
) {}
