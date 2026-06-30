package com.apimarketplace.agent.dto;

import com.apimarketplace.agent.domain.AgentTaskRecurrenceEntity;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record RecurrenceResponse(
        UUID id,
        String tenantId,
        UUID createdByAgentId,
        String createdByUserId,
        UUID targetAgentId,
        String title,
        String instructions,
        Map<String, Object> taskContext,
        String priority,
        String cronExpression,
        String timezone,
        boolean enabled,
        Instant lastFiredAt,
        Instant nextFireAt,
        long fireCount,
        Instant createdAt,
        Instant updatedAt) {

    public static RecurrenceResponse from(AgentTaskRecurrenceEntity r) {
        return new RecurrenceResponse(
                r.getId(), r.getTenantId(), r.getCreatedByAgentId(), r.getCreatedByUserId(),
                r.getTargetAgentId(), r.getTitle(), r.getInstructions(), r.getTaskContext(),
                r.getPriority(), r.getCronExpression(), r.getTimezone(), r.isEnabled(),
                r.getLastFiredAt(), r.getNextFireAt(), r.getFireCount(),
                r.getCreatedAt(), r.getUpdatedAt());
    }
}
