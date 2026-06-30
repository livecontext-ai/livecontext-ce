package com.apimarketplace.agent.dto;

public record UpdateRecurrenceRequest(
        Boolean enabled,
        String cronExpression,
        String title,
        String instructions,
        String priority) {
}
