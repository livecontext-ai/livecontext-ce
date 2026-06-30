package com.apimarketplace.agent.dto;

import java.util.Map;
import java.util.UUID;

public record CreateRecurrenceRequest(
        String title,
        String instructions,
        String cronExpression,
        String timezone,
        UUID targetAgentId,
        String priority,
        Map<String, Object> taskContext) {
}
